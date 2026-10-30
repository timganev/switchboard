package io.github.timganev.switchboard.guardian.detect;

public record Finding(SensitiveType type, String value, int start, int end) {
}
