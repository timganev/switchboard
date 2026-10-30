package io.github.timganev.switchboard.gateway.guardian;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(GuardianProperties.class)
class GuardianConfiguration {

    @Bean
    RestClient guardianRestClient(RestClient.Builder builder, GuardianProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return builder.clone().baseUrl(properties.url().toString()).requestFactory(requestFactory).build();
    }
}
