package io.github.timganev.switchboard.trace;

import io.github.timganev.switchboard.commons.ClientFormat;
import io.github.timganev.switchboard.commons.Locality;
import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import io.github.timganev.switchboard.commons.event.PrivacyDecisionMade;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TraceIntegrationTest {

    @Container
    @ServiceConnection
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    @Container
    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>("opensearchproject/opensearch:3.8.0")
            .withEnv(Map.of(
                    "discovery.type", "single-node",
                    "DISABLE_SECURITY_PLUGIN", "true",
                    "DISABLE_INSTALL_DEMO_CONFIG", "true",
                    "OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m",
                    "cluster.routing.allocation.disk.threshold_enabled", "false"))
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void openSearch(DynamicPropertyRegistry registry) {
        registry.add("switchboard.trace.opensearch.url", () -> "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
    }

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldMergeTheCallAndThePrivacyDecisionIntoOneTrace() {
        String callId = UUID.randomUUID().toString();
        kafka.send(Topics.PRIVACY_DECISION_MADE, callId, new PrivacyDecisionMade(callId, "s-1", Route.SURROGATE,
                List.of(new SensitivityReason("EGN", 1), new SensitivityReason("IBAN", 1)), Map.of("detector", "1"),
                null, false, false, List.of(), Instant.now()));
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-a", call(callId, "team-a", Instant.now()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mockMvc.perform(get("/v1/calls/{id}", callId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team").value("team-a"))
                .andExpect(jsonPath("$.route").value("SURROGATE"))
                .andExpect(jsonPath("$.instance").value("cloud-1"))
                .andExpect(jsonPath("$.privacy.verdict").value("SURROGATE"))
                .andExpect(jsonPath("$.privacy.reasons[0].type").value("EGN"))
                .andExpect(jsonPath("$.privacy.reasons[1].type").value("IBAN")));
    }

    @Test
    void shouldListATeamsCallsNewestFirst() {
        String older = UUID.randomUUID().toString();
        String newer = UUID.randomUUID().toString();
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-b", call(older, "team-b", Instant.parse("2026-10-30T10:00:00Z")));
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-b", call(newer, "team-b", Instant.parse("2026-10-30T11:00:00Z")));
        kafka.send(Topics.LLM_CALL_COMPLETED, "team-c", call(UUID.randomUUID().toString(), "team-c", Instant.now()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mockMvc.perform(get("/v1/calls").param("team", "team-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].callId", contains(newer, older))));
    }

    @Test
    void shouldAnswerNotFoundForAnUnknownCall() throws Exception {
        mockMvc.perform(get("/v1/calls/{id}", "unknown")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/calls").param("team", "nobody")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    private static LlmCallCompleted call(String callId, String team, Instant at) {
        return new LlmCallCompleted(callId, "s-1", team, ClientFormat.ANTHROPIC, Route.SURROGATE, Locality.CLOUD, "cloud-1",
                "big-model", 120, 30, 0, 850, 200, null, at);
    }
}
