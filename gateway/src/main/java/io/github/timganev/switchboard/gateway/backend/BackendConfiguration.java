package io.github.timganev.switchboard.gateway.backend;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(BackendProperties.class)
class BackendConfiguration {

    @Bean
    RestClient backendRestClient(RestClient.Builder builder, BackendProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        builder.baseUrl(properties.url().toString()).requestFactory(requestFactory);
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        return builder.build();
    }
}
