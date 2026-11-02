package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.commons.kafka.EventPublisher;
import io.github.timganev.switchboard.guardian.decision.DecisionModel;
import io.github.timganev.switchboard.guardian.decision.DecisionModelProperties;
import io.github.timganev.switchboard.guardian.decision.LocalDecisionModel;
import io.github.timganev.switchboard.guardian.decision.NoDecisionModel;
import io.github.timganev.switchboard.guardian.surrogate.SurrogateFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({GuardianProperties.class, DecisionModelProperties.class})
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
    DecisionModel decisionModel(DecisionModelProperties properties) {
        return switch (properties.type()) {
            case NONE -> new NoDecisionModel();
            case LOCAL -> {
                HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
                requestFactory.setReadTimeout(properties.timeout());
                RestClient restClient = RestClient.builder().baseUrl(properties.url().toString()).requestFactory(requestFactory).build();
                yield new LocalDecisionModel(restClient, properties.model(), properties.reasoningEffort());
            }
        };
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
