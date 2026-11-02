package io.github.timganev.switchboard.leaklab.model;

import java.time.Duration;

/**
 * {@code outputTokens} include the tokens a reasoning model spent thinking, because they are billed too.
 */
public record Completion(String text, int inputTokens, int outputTokens, Duration took) {
}
