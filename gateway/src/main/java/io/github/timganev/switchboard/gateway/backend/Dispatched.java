package io.github.timganev.switchboard.gateway.backend;

/**
 * A result together with the instance that produced it.
 */
public record Dispatched<T>(Backend backend, T result) {
}
