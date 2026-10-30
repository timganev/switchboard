package io.github.timganev.switchboard.guardian.events;

import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.PrivacyDecisionMade;
import io.github.timganev.switchboard.commons.kafka.EventPublisher;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.guardian.detect.Detector;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes every verdict with its reasons; the reasons carry types and counts, never the values.
 */
@Component
public class DecisionEvents {

    private final EventPublisher publisher;
    private final Clock clock;

    public DecisionEvents(EventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void decided(String callId, String sessionId, Inspection inspection) {
        String id = callId == null ? UUID.randomUUID().toString() : callId;
        publisher.publish(Topics.PRIVACY_DECISION_MADE, id, new PrivacyDecisionMade(
                id,
                sessionId,
                inspection.route(),
                inspection.reasons(),
                Map.of("detector", Detector.VERSION),
                null,
                false,
                false,
                List.of(),
                Instant.now(clock)));
    }
}
