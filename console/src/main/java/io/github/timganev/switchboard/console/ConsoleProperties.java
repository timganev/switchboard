package io.github.timganev.switchboard.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Where the gateway and the guardian are, and how the console authenticates to the gateway.
 */
@ConfigurationProperties("switchboard.console")
public record ConsoleProperties(URI gatewayUrl, URI guardianUrl, String apiKey, String adminToken, String team, String model,
                                Duration timeout) {

    public ConsoleProperties {
        if (gatewayUrl == null || guardianUrl == null) {
            throw new IllegalArgumentException("switchboard.console.gateway-url and guardian-url are required");
        }
        team = team == null || team.isBlank() ? "playground" : team;
        model = model == null || model.isBlank() ? "qwen36" : model;
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
    }
}
