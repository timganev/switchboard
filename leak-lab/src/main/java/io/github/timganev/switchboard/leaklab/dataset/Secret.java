package io.github.timganev.switchboard.leaklab.dataset;

import io.github.timganev.switchboard.commons.privacy.SensitiveType;

public record Secret(SensitiveType type, String value) {
}
