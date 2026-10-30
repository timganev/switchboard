package io.github.timganev.switchboard.ledger;

import io.github.timganev.switchboard.commons.ClientFormat;
import io.github.timganev.switchboard.commons.Locality;
import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.BudgetState;
import io.github.timganev.switchboard.commons.event.BudgetStatusChanged;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import io.github.timganev.switchboard.commons.kafka.DeadLetters;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LedgerIntegrationTest {

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Container
    @ServiceConnection
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PriceBook priceBook;

    @Autowired
    private Ledger ledger;

    @Test
    void shouldBlockATeamOnceItsCloudSpendReachesTheLimit() throws Exception {
        mockMvc.perform(put("/v1/budgets/team-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyLimitUsd\": 0.02}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OK"));

        // 1000 input and 1000 output tokens at the fallback price of 3 and 15 USD per million: 0.018 USD.
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-a", call("team-a", Locality.CLOUD, "unpriced-model", 1000, 1000));
        BudgetStatusChanged warn = awaitStatus("team-a", status -> status.state() == BudgetState.WARN);
        assertThat(warn.spentEstimate()).isEqualByComparingTo("0.018");

        kafka.send(Topics.LLM_CALL_COMPLETED, "team-a", call("team-a", Locality.CLOUD, "unpriced-model", 1000, 1000));
        BudgetStatusChanged blocked = awaitStatus("team-a", status -> status.state() == BudgetState.BLOCKED);
        assertThat(blocked.spentEstimate()).isEqualByComparingTo("0.036");
        assertThat(blocked.limit()).isEqualByComparingTo("0.02");
    }

    @Test
    void shouldKeepASmallLimitExactly() throws Exception {
        mockMvc.perform(put("/v1/budgets/team-small")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyLimitUsd\": 0.0001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(0.0001))
                .andExpect(jsonPath("$.state").value("OK"));
    }

    @Test
    void shouldChargeNothingForLocalCallsAndCountARedeliveredCallOnce() {
        LlmCallCompleted cloud = call("team-b", Locality.CLOUD, "unpriced-model", 1_000_000, 0);
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-b", cloud);
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-b", cloud);
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-b", call("team-b", Locality.LOCAL, "qwen36", 1_000_000, 1_000_000));
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-b", call("team-b", null, "qwen36", 0, 0));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT COUNT(*) FROM llm_call WHERE team = 'team-b'").query(Long.class).single()).isEqualTo(3));
        BigDecimal spent = jdbc.sql("SELECT SUM(cost_usd) FROM llm_call WHERE team = 'team-b'").query(BigDecimal.class).single();
        assertThat(spent).isEqualByComparingTo("3");
    }

    @Test
    void shouldUseTheModelsOwnPriceThatWasValidAtTheTime() {
        jdbc.sql("""
                INSERT INTO model_price (model, valid_from, input_usd_per_mtok, output_usd_per_mtok) VALUES
                  ('priced-model', '2026-01-01T00:00:00Z', 1, 2),
                  ('priced-model', '2026-06-01T00:00:00Z', 10, 20)""").update();

        assertThat(priceBook.cost("priced-model", 1_000_000, 1_000_000, Instant.parse("2026-03-01T00:00:00Z"))).isEqualByComparingTo("3");
        assertThat(priceBook.cost("priced-model", 1_000_000, 1_000_000, Instant.parse("2026-07-01T00:00:00Z"))).isEqualByComparingTo("30");
        assertThat(priceBook.cost("other-model", 1_000_000, 0, Instant.parse("2026-07-01T00:00:00Z"))).isEqualByComparingTo("3");
    }

    @Test
    void shouldParkAnUnreadableRecordOnTheDeadLetterTopic() {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(Topics.LLM_CALL_COMPLETED, "poison", "not an event"));
        }

        List<ConsumerRecord<String, String>> parked = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer(new StringDeserializer())) {
            consumer.subscribe(List.of(DeadLetters.topic("ledger")));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(parked::add);
                return parked.stream().anyMatch(record -> "poison".equals(record.key()));
            });
        }
        assertThat(parked).filteredOn(record -> "poison".equals(record.key()))
                .singleElement().satisfies(record -> assertThat(record.value()).isEqualTo("not an event"));
    }

    @Test
    void shouldRedriveAParkedCallIntoTheLedger() throws Exception {
        // As if the ledger had parked it while Postgres was down.
        kafka.send(DeadLetters.topic("ledger"), "team-redriven", call("team-redriven", Locality.CLOUD, "unpriced-model", 1_000_000, 0)).get();
        mockMvc.perform(get("/v1/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", hasItem("team-redriven")));

        mockMvc.perform(post("/v1/dead-letters/redrive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isNumber());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT COALESCE(SUM(cost_usd), 0) FROM llm_call WHERE team = 'team-redriven'").query(BigDecimal.class).single())
                        .isEqualByComparingTo("3"));
        mockMvc.perform(get("/v1/dead-letters")).andExpect(jsonPath("$[*].key", not(hasItem("team-redriven"))));
    }

    @Test
    void shouldParkARedrivenRecordAgainWhenItStillFails() throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(Topics.LLM_CALL_COMPLETED, "still-poison", "not an event"));
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mockMvc.perform(get("/v1/dead-letters"))
                .andExpect(jsonPath("$[?(@.key == 'still-poison')].topic").value(hasItem(Topics.LLM_CALL_COMPLETED)))
                .andExpect(jsonPath("$[?(@.key == 'still-poison')].reason").value(hasItem(org.hamcrest.Matchers.notNullValue()))));

        mockMvc.perform(post("/v1/dead-letters/redrive")).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mockMvc.perform(get("/v1/dead-letters"))
                .andExpect(jsonPath("$[?(@.key == 'still-poison')].topic").value(hasItem(DeadLetters.redriveTopic("ledger")))));
    }

    @Test
    void shouldShareABilledDayOutByEachTeamsEstimate() throws Exception {
        Instant billedDay = Instant.parse("2026-09-15T10:00:00Z");
        // At the fallback price: 3 and 6 USD on the billed day, 3 USD on a day the provider has not billed yet.
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-x", call("team-x", Locality.CLOUD, "billed-model", 1_000_000, 0, billedDay));
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-y", call("team-y", Locality.CLOUD, "billed-model", 2_000_000, 0, billedDay));
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-x", call("team-x", Locality.CLOUD, "billed-model", 1_000_000, 0, billedDay.plusSeconds(86_400)));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT COUNT(*) FROM llm_call WHERE team IN ('team-x', 'team-y')").query(Long.class).single()).isEqualTo(3));

        mockMvc.perform(post("/v1/bills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\": [{\"date\": \"2026-09-15\", \"model\": \"billed-model\", \"costUsd\": 18}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].estimatedUsd").value(9.0))
                .andExpect(jsonPath("$[0].billedUsd").value(18))
                .andExpect(jsonPath("$[0].differenceUsd").value(9.0));

        BudgetStatusChanged x = ledger.status("team-x", YearMonth.of(2026, 9));
        assertThat(x.spentEstimate()).isEqualByComparingTo("6");
        assertThat(x.spentReconciled()).isEqualByComparingTo("9");
        assertThat(ledger.status("team-y", YearMonth.of(2026, 9)).spentReconciled()).isEqualByComparingTo("12");

        // A corrected bill replaces the first import.
        mockMvc.perform(post("/v1/bills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\": [{\"date\": \"2026-09-15\", \"model\": \"billed-model\", \"costUsd\": 4.5}]}"))
                .andExpect(status().isOk());
        assertThat(ledger.status("team-x", YearMonth.of(2026, 9)).spentReconciled()).isEqualByComparingTo("4.5");
    }

    @Test
    void shouldBlockATeamWhenTheBillForThisMonthIsAboveTheEstimate() throws Exception {
        mockMvc.perform(put("/v1/budgets/team-z").contentType(MediaType.APPLICATION_JSON).content("{\"monthlyLimitUsd\": 5}"))
                .andExpect(status().isOk());
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-z", call("team-z", Locality.CLOUD, "today-model", 1_000_000, 0));
        BudgetStatusChanged estimated = awaitStatus("team-z", status -> status.spentEstimate().signum() > 0);
        assertThat(estimated.state()).isEqualTo(BudgetState.OK);

        mockMvc.perform(post("/v1/bills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\": [{\"date\": \"" + LocalDate.now(ZoneOffset.UTC) + "\", \"model\": \"today-model\", \"costUsd\": 6}]}"))
                .andExpect(status().isOk());

        BudgetStatusChanged billed = awaitStatus("team-z", status -> status.state() == BudgetState.BLOCKED);
        assertThat(billed.spentEstimate()).isEqualByComparingTo("3");
        assertThat(billed.spentReconciled()).isEqualByComparingTo("6");
    }

    @Test
    void shouldRefuseABillWithTheSameDayAndModelTwice() throws Exception {
        mockMvc.perform(post("/v1/bills").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines": [{"date": "2026-09-01", "model": "m", "costUsd": 1},
                                           {"date": "2026-09-01", "model": "m", "costUsd": 2}]}"""))
                .andExpect(status().isBadRequest());
    }

    private static LlmCallCompleted call(String team, Locality locality, String model, long input, long output) {
        return call(team, locality, model, input, output, Instant.now());
    }

    private static LlmCallCompleted call(String team, Locality locality, String model, long input, long output, Instant at) {
        return new LlmCallCompleted(UUID.randomUUID().toString(), null, team, ClientFormat.OPENAI, Route.CLOUD, locality,
                locality == null ? null : "instance-1", model, input, output, 0, 100, 200, null, at);
    }

    private static BudgetStatusChanged awaitStatus(String team, Predicate<BudgetStatusChanged> matching) {
        List<BudgetStatusChanged> statuses = new ArrayList<>();
        try (KafkaConsumer<String, BudgetStatusChanged> consumer = consumer(new JacksonJsonDeserializer<>(BudgetStatusChanged.class))) {
            consumer.subscribe(List.of(Topics.BUDGET_STATUS_CHANGED));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (team.equals(record.key())) {
                        statuses.add(record.value());
                    }
                });
                return statuses.stream().anyMatch(matching);
            });
        }
        return statuses.stream().filter(matching).findFirst().orElseThrow();
    }

    private static <V> KafkaConsumer<String, V> consumer(org.apache.kafka.common.serialization.Deserializer<V> valueDeserializer) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "io.github.timganev.switchboard.commons.event"),
                new StringDeserializer(), valueDeserializer);
    }
}
