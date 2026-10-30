package io.github.timganev.switchboard.trace;

import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import io.github.timganev.switchboard.commons.event.PrivacyDecisionMade;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Writes what happened to a call and why; both events carry types, counts and routes, never a sensitive value.
 */
@Component
class TraceListener {

    private final TraceStore store;

    TraceListener(TraceStore store) {
        this.store = store;
    }

    @KafkaListener(topics = Topics.LLM_CALL_COMPLETED)
    void callCompleted(LlmCallCompleted call) {
        store.merge(call.callId(), call);
    }

    @KafkaListener(topics = Topics.PRIVACY_DECISION_MADE)
    void privacyDecisionMade(PrivacyDecisionMade decision) {
        store.merge(decision.callId(), Map.of("callId", decision.callId(), "privacy", decision));
    }
}
