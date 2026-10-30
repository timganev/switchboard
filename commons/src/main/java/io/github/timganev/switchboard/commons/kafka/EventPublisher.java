package io.github.timganev.switchboard.commons.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publishes events off the caller's path: a slow or missing broker never delays or fails a request.
 */
public class EventPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaOperations<String, Object> kafka;
    // The producer blocks while it waits for broker metadata, so each send gets its own virtual thread.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EventPublisher(KafkaOperations<String, Object> kafka) {
        this.kafka = kafka;
    }

    public void publish(String topic, String key, Object event) {
        executor.execute(() -> {
            try {
                kafka.send(topic, key, event).whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.warn("Could not publish to {}: {}", topic, failure.toString());
                    }
                });
            } catch (RuntimeException exception) {
                log.warn("Could not publish to {}: {}", topic, exception.toString());
            }
        });
    }

    @Override
    public void close() {
        executor.close();
    }
}
