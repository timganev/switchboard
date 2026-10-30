package io.github.timganev.switchboard.gateway.events;

import io.github.timganev.switchboard.commons.ClientFormat;
import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import io.github.timganev.switchboard.commons.kafka.EventPublisher;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CallEventsKafkaTest {

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    @Test
    void shouldPublishACallThatOtherServicesCanRead() {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class)));
        CallRecord call;
        try (EventPublisher publisher = new EventPublisher(template)) {
            CallEvents callEvents = new CallEvents(publisher, Clock.systemUTC());
            call = callEvents.start(new MockHttpServletRequest(), ClientFormat.OPENAI, "qwen36");
            callEvents.completed(call, 200);
        }
        template.flush();

        List<ConsumerRecord<String, LlmCallCompleted>> records = new ArrayList<>();
        try (KafkaConsumer<String, LlmCallCompleted> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "io.github.timganev.switchboard.commons.event"),
                new StringDeserializer(), new JacksonJsonDeserializer<>(LlmCallCompleted.class))) {
            consumer.subscribe(List.of(Topics.LLM_CALL_COMPLETED));
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo("default");
            assertThat(record.value().callId()).isEqualTo(call.callId());
            assertThat(record.value().clientFormat()).isEqualTo(ClientFormat.OPENAI);
            assertThat(record.value().httpStatus()).isEqualTo(200);
            assertThat(record.value().timestamp()).isNotNull();
        });
    }
}
