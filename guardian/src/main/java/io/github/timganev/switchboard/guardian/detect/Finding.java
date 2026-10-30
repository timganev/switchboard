package io.github.timganev.switchboard.guardian.detect;

import io.github.timganev.switchboard.commons.privacy.SensitiveType;

public record Finding(SensitiveType type, String value, int start, int end) {
}
