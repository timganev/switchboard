package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.event.DecisionModelRef;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.guardian.decision.Classification;
import io.github.timganev.switchboard.guardian.decision.DecisionModel;
import io.github.timganev.switchboard.guardian.decision.DecisionModelProperties;
import io.github.timganev.switchboard.guardian.decision.NoDecisionModel;
import io.github.timganev.switchboard.guardian.detect.Detector;
import io.github.timganev.switchboard.guardian.surrogate.SurrogateFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionSlotTest {

    private static final DecisionModelRef MODEL = new DecisionModelRef("local-model", "1");

    private final Guardian guardian = new Guardian(new Detector(), new SurrogateFactory(),
            new GuardianProperties("a-test-secret-that-is-long-enough!", Map.of()));
    private final DecisionModel model = mock(DecisionModel.class);

    DecisionSlotTest() {
        when(model.ref()).thenReturn(MODEL);
    }

    @Test
    void shouldKeepTheDetectorsVerdictWhenTheSlotIsOff() {
        DecisionSlot.Decision decision = slot(new NoDecisionModel(), null).decide(request("Ivan Petrov, EGN 7501020018"));

        assertThat(decision.inspection().route()).isEqualTo(Route.SURROGATE);
        assertThat(decision.model()).isNull();
        assertThat(decision.escalatedByModel()).isFalse();
    }

    @Test
    void shouldKeepTheRequestLocalWhenTheModelFindsAName() {
        when(model.classify(anyString())).thenReturn(new Classification(Set.of("NAME"), false));

        DecisionSlot.Decision decision = slot(model, null).decide(request("Ivan Petrov, EGN 7501020018"));

        assertThat(decision.inspection().route()).isEqualTo(Route.LOCAL);
        assertThat(decision.inspection().reasons()).containsExactly(new SensitivityReason("EGN", 1), new SensitivityReason("NAME", 1));
        assertThat(decision.inspection().request().messages().getFirst().parts()).containsExactly(new TextPart("Ivan Petrov, EGN 7501020018"));
        assertThat(decision.inspection().substitutions()).isEmpty();
        assertThat(decision.model()).isEqualTo(MODEL);
        assertThat(decision.escalatedByModel()).isTrue();
    }

    @Test
    void shouldTreatAFailedModelAsAFinding() {
        when(model.classify(anyString())).thenReturn(Classification.FAILED);

        DecisionSlot.Decision decision = slot(model, Route.BLOCK).decide(request("What is a contract?"));

        assertThat(decision.inspection().route()).isEqualTo(Route.BLOCK);
        assertThat(decision.inspection().request()).isNull();
        assertThat(decision.inspection().reasons()).containsExactly(new SensitivityReason(DecisionSlot.FAILED_REASON, 1));
    }

    @Test
    void shouldLeaveACleanRequestAlone() {
        when(model.classify(anyString())).thenReturn(Classification.NOTHING);

        DecisionSlot.Decision decision = slot(model, null).decide(request("What is a contract?"));

        assertThat(decision.inspection().route()).isEqualTo(Route.CLOUD);
        assertThat(decision.model()).isEqualTo(MODEL);
        assertThat(decision.escalatedByModel()).isFalse();
    }

    @Test
    void shouldNotAskTheModelWhenItCouldNotTightenTheRoute() {
        DecisionSlot.Decision decision = slot(model, null).decide(request("-----BEGIN PRIVATE KEY----- MIIE"));

        assertThat(decision.inspection().route()).isEqualTo(Route.BLOCK);
        verify(model, never()).classify(anyString());
    }

    private DecisionSlot slot(DecisionModel decisionModel, Route route) {
        return new DecisionSlot(guardian, decisionModel, new DecisionModelProperties(null, null, null, null, null, route));
    }

    private static ChatRequest request(String text) {
        return new ChatRequest("m", null, List.of(new ChatMessage(Role.USER, List.of(new TextPart(text)))), null, null, null);
    }
}
