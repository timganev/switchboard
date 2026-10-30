package io.github.timganev.switchboard.commons;

// Ordered from least to most strict; when reasons disagree, the strictest route wins.
public enum Route {
    CLOUD,
    SURROGATE,
    SKETCH,
    LOCAL,
    BLOCK
}
