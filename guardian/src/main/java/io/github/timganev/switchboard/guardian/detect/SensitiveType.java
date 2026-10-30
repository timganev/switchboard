package io.github.timganev.switchboard.guardian.detect;

// Ordered by priority: when two findings overlap, the earlier type wins.
public enum SensitiveType {
    PRIVATE_KEY,
    JWT,
    API_KEY,
    EMAIL,
    IBAN,
    CARD,
    PHONE,
    EGN,
    LNCH
}
