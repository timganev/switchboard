package io.github.timganev.switchboard.gateway.backend;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * The OpenAI-compatible backend the gateway forwards to; {@code url} is the base URL including {@code /v1}.
 */
@ConfigurationProperties("switchboard.backend")
public record BackendProperties(URI url, String apiKey, Duration connectTimeout, Duration readTimeout) {
}
