package io.github.timganev.switchboard.leaklab;

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
    RestClient attackerRestClient(RestClient.Builder builder, LeakLabProperties properties) {
        return client(builder, properties.attacker().url(), properties.attacker().timeout());
    }

    private static RestClient client(RestClient.Builder builder, URI url, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(url.toString()).requestFactory(requestFactory).build();
    }
}
