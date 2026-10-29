package io.github.timganev.switchboard.commons.event;

import io.github.timganev.switchboard.commons.Route;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PrivacyDecisionMade(
        String callId,
        String sessionId,
        Route verdict,
        List<SensitivityReason> reasons,
        Map<String, String> detectorVersions,
        DecisionModelRef decisionModel,
        boolean escalatedByModel,
        boolean trifectaTriggered,
        List<String> removedTools,
        Instant timestamp) {
}
