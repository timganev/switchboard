package io.github.timganev.switchboard.leaklab;

import io.github.timganev.switchboard.leaklab.model.ModelClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(LeakLabProperties.class)
class LeakLabConfiguration {

    @Bean
    RestClient guardianRestClient(RestClient.Builder builder, LeakLabProperties properties) {
        return client(builder, properties.guardian().url(), properties.guardian().timeout());
    }

    @Bean
    ModelClient attackerModel(RestClient.Builder builder, LeakLabProperties properties) {
        return model(builder, properties.attacker());
    }

    @Bean
    ModelClient judgeModel(RestClient.Builder builder, LeakLabProperties properties) {
        return model(builder, properties.judge());
    }

    @Bean
    ModelClient cloudModel(RestClient.Builder builder, LeakLabProperties properties) {
        return model(builder, properties.cloud());
    }

    @Bean
    ModelClient localModel(RestClient.Builder builder, LeakLabProperties properties) {
        return model(builder, properties.local());
    }

    private static ModelClient model(RestClient.Builder builder, LeakLabProperties.Model model) {
        return new ModelClient(client(builder, model.url(), model.timeout()), model.model(), model.reasoningEffort());
    }

    private static RestClient client(RestClient.Builder builder, URI url, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(url.toString()).requestFactory(requestFactory).build();
    }
}
