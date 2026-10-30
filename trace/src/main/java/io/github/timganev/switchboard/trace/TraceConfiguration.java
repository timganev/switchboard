package io.github.timganev.switchboard.trace;

import io.github.timganev.switchboard.commons.kafka.DeadLetters;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(TraceProperties.class)
class TraceConfiguration {

    @Bean
    RestClient openSearchRestClient(RestClient.Builder builder, TraceProperties properties) {
        TraceProperties.OpenSearch openSearch = properties.opensearch();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(openSearch.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(openSearch.timeout());
        return builder.clone().baseUrl(openSearch.url().toString()).requestFactory(requestFactory).build();
    }

    @Bean
    CommonErrorHandler deadLetters(KafkaTemplate<Object, Object> kafkaTemplate) {
        return DeadLetters.errorHandler(kafkaTemplate, "trace");
    }
}
