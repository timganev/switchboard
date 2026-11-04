package io.github.timganev.switchboard.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound only when API keys are on, so the admin token is required exactly then.
 */
@ConfigurationProperties("switchboard.auth")
public record AuthProperties(String type, String adminToken) {

    private static final int MIN_TOKEN_LENGTH = 32;

    public AuthProperties {
        if (adminToken == null || adminToken.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("switchboard.auth.admin-token must be at least " + MIN_TOKEN_LENGTH + " characters");
        }
    }
}
