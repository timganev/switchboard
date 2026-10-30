package io.github.timganev.switchboard.ledger;

import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.kafka.DeadLetters;
import io.github.timganev.switchboard.commons.kafka.Redrive;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
class LedgerConfiguration {

    static final String CONSUMER = "ledger";
    static final String REDRIVE_TOPIC = CONSUMER + "-redrive";

    @Bean
    CommonErrorHandler deadLetters(KafkaTemplate<Object, Object> kafkaTemplate) {
        return DeadLetters.errorHandler(kafkaTemplate, CONSUMER);
    }

    @Bean
    KafkaAdmin.NewTopics deadLetterTopics() {
        return DeadLetters.topics(CONSUMER);
    }

    @Bean
    Redrive redrive(ConsumerFactory<?, ?> consumerFactory, KafkaTemplate<Object, Object> kafkaTemplate) {
        return new Redrive(consumerFactory, kafkaTemplate, CONSUMER);
    }

    // Compacted: Kafka keeps the latest status per team, so a reader that starts from the beginning sees every budget.
    @Bean
    NewTopic budgetStatusTopic() {
        return TopicBuilder.name(Topics.BUDGET_STATUS_CHANGED).partitions(1).compact().build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
