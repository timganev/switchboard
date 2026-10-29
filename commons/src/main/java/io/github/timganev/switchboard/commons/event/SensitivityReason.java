package io.github.timganev.switchboard.commons.event;

// Carries the type and count only; the sensitive value itself never leaves the guardian.
public record SensitivityReason(String type, int count) {
}
