package io.github.timganev.switchboard.guardian.events;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.PrivacyDecisionMade;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.kafka.EventPublisher;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import io.github.timganev.switchboard.commons.privacy.Substitution;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DecisionEventsTest {

    private final EventPublisher publisher = mock(EventPublisher.class);
    private final DecisionEvents decisionEvents = new DecisionEvents(publisher, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void shouldPublishTheVerdictWithTypesAndCountsButNoValues() {
        Inspection inspection = new Inspection(Route.SURROGATE, List.of(new SensitivityReason("EGN", 1)), null,
                List.of(new Substitution(SensitiveType.EGN, "7501020018", "8807143345")));

        decisionEvents.decided("call-1", "s-1", inspection);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(Topics.PRIVACY_DECISION_MADE), eq("call-1"), event.capture());
        PrivacyDecisionMade decision = (PrivacyDecisionMade) event.getValue();
        assertThat(decision.callId()).isEqualTo("call-1");
        assertThat(decision.sessionId()).isEqualTo("s-1");
        assertThat(decision.verdict()).isEqualTo(Route.SURROGATE);
        assertThat(decision.reasons()).containsExactly(new SensitivityReason("EGN", 1));
        assertThat(decision.detectorVersions()).containsKey("detector");
        assertThat(decision.toString()).doesNotContain("7501020018").doesNotContain("8807143345");
    }
}
