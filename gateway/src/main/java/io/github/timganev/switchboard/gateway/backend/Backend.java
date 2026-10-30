package io.github.timganev.switchboard.gateway.backend;

import org.springframework.web.client.RestClient;

/**
 * A model instance with its HTTP client; {@code model} is null when the client's model name is passed through.
 */
public record Backend(String name, Locality locality, String model, RestClient restClient) {
}
