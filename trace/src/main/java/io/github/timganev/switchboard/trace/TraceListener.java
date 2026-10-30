package io.github.timganev.switchboard.trace;

import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import io.github.timganev.switchboard.commons.event.PrivacyDecisionMade;
import io.github.timganev.switchboard.trace.live.LiveFeed;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Writes what happened to a call and why; both events carry types, counts and routes, never a sensitive value.
 */
@Component
class TraceListener {

    private final TraceStore store;
    private final LiveFeed liveFeed;

    TraceListener(TraceStore store, LiveFeed liveFeed) {
        this.store = store;
        this.liveFeed = liveFeed;
    }

    @KafkaListener(topics = Topics.LLM_CALL_COMPLETED)
    void callCompleted(LlmCallCompleted call) {
        store.merge(call.callId(), call);
        liveFeed.callCompleted(call.callId());
    }

    @KafkaListener(topics = Topics.PRIVACY_DECISION_MADE)
    void privacyDecisionMade(PrivacyDecisionMade decision) {
        store.merge(decision.callId(), Map.of("callId", decision.callId(), "privacy", decision));
        liveFeed.decisionMade(decision.callId());
    }

    // Both kinds of event come back on one topic; the type header tells them apart.
    @KafkaListener(topics = TraceConfiguration.REDRIVE_TOPIC)
    void redriven(ConsumerRecord<String, Object> record) {
        switch (record.value()) {
            case LlmCallCompleted call -> callCompleted(call);
            case PrivacyDecisionMade decision -> privacyDecisionMade(decision);
            default -> throw new IllegalArgumentException("Not a trace event: " + record.value());
        }
    }
}
