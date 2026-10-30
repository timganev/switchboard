package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.commons.kafka.EventPublisher;
import io.github.timganev.switchboard.guardian.surrogate.SurrogateFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(GuardianProperties.class)
class GuardianConfiguration {

    @Bean
    SurrogateFactory surrogateFactory() {
        return new SurrogateFactory();
    }

    @Bean
    EventPublisher eventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new EventPublisher(kafkaTemplate);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
