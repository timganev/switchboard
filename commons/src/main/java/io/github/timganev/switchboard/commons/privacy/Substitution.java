package io.github.timganev.switchboard.commons.privacy;

/**
 * One real value and the surrogate that stood in for it; it lives only in memory for the length of one request.
 */
public record Substitution(SensitiveType type, String value, String surrogate) {
}
