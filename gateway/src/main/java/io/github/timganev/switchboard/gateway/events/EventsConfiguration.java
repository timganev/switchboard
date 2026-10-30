package io.github.timganev.switchboard.gateway.events;

import io.github.timganev.switchboard.commons.kafka.EventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

@Configuration
class EventsConfiguration {

    @Bean
    EventPublisher eventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new EventPublisher(kafkaTemplate);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
