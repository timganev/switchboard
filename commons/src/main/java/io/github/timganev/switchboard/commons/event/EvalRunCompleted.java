package io.github.timganev.switchboard.commons.event;

import java.math.BigDecimal;
import java.time.Instant;

public record EvalRunCompleted(
        String runId,
        String strategy,
        String dataset,
        double leakRate,
        double qualityScore,
        BigDecimal costUsd,
        long p50LatencyMs,
        Instant timestamp) {
}
