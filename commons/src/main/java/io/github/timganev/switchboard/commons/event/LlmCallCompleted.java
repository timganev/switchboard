package io.github.timganev.switchboard.commons.event;

import io.github.timganev.switchboard.commons.ClientFormat;
import io.github.timganev.switchboard.commons.Locality;
import io.github.timganev.switchboard.commons.Route;

import java.time.Instant;

// Route, locality and instance are null when the call ended before an instance was chosen.
public record LlmCallCompleted(
        String callId,
        String sessionId,
        String team,
        ClientFormat clientFormat,
        Route route,
        Locality locality,
        String instance,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long latencyMs,
        int httpStatus,
        DecisionModelRef decisionModel,
        Instant timestamp) {
}
