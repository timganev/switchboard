package io.github.timganev.switchboard.commons.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retries a failed record a few times, then parks it on the consumer's dead letter topic instead of blocking the partition.
 */
public final class DeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);
    private static final long RETRY_INTERVAL_MS = 1000;
    private static final long RETRIES = 2;
    private static final int REASON_LENGTH = 300;

    public static final String REASON_HEADER = "switchboard-dlt-reason";

    private DeadLetters() {
    }

    public static String topic(String consumer) {
        return consumer + "-dlq";
    }

    public static String redriveTopic(String consumer) {
        return consumer + "-redrive";
    }

    // Declared up front, so a consumer subscribes to its redrive topic and lists its dead letters before anything failed.
    public static KafkaAdmin.NewTopics topics(String consumer) {
        return new KafkaAdmin.NewTopics(TopicBuilder.name(topic(consumer)).build(), TopicBuilder.name(redriveTopic(consumer)).build());
    }

    public static CommonErrorHandler errorHandler(KafkaTemplate<Object, Object> template, String consumer) {
        // A record that could not be read is parked as the raw bytes it arrived with.
        KafkaTemplate<Object, Object> bytesTemplate = new KafkaTemplate<>(template.getProducerFactory().copyWithConfigurationOverride(
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, template);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, exception) -> new TopicPartition(topic(consumer), -1));
        recoverer.setHeadersFunction((record, exception) -> new RecordHeaders(
                new RecordHeader[]{new RecordHeader(REASON_HEADER, reason(exception).getBytes(StandardCharsets.UTF_8))}));
        return new DefaultErrorHandler((record, exception) -> {
            log.error("Parking {}-{}@{} on {}: {}", record.topic(), record.partition(), record.offset(), topic(consumer), reason(exception));
            recoverer.accept(record, exception);
        }, new FixedBackOff(RETRY_INTERVAL_MS, RETRIES));
    }

    // The outer exceptions only say that listening or reading failed; the deepest one with a message says why.
    static String reason(Throwable exception) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(exception);
        Throwable explained = exception;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                explained = cause;
            }
        }
        String reason = explained.getClass().getSimpleName()
                + (explained.getMessage() == null ? "" : ": " + explained.getMessage().lines().findFirst().orElse(""))
                + (root == explained ? "" : " (" + root.getClass().getSimpleName() + ")");
        return reason.length() <= REASON_LENGTH ? reason : reason.substring(0, REASON_LENGTH);
    }
}
