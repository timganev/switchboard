package io.github.timganev.switchboard.gateway.backend;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.List;

@Configuration
@EnableConfigurationProperties(BackendProperties.class)
class BackendConfiguration {

    @Bean
    List<Backend> backends(RestClient.Builder builder, BackendProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return properties.instances().stream().map(instance -> {
            RestClient.Builder instanceBuilder = builder.clone()
                    .baseUrl(instance.url().toString())
                    .requestFactory(requestFactory);
            if (instance.apiKey() != null && !instance.apiKey().isBlank()) {
                instanceBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + instance.apiKey());
            }
            return new Backend(instance.name(), instance.locality(), instance.model(), instanceBuilder.build());
        }).toList();
    }
}
