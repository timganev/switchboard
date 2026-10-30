package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.ContentPart;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.ToolCallPart;
import io.github.timganev.switchboard.commons.chat.ToolResultPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Restoration;
import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import io.github.timganev.switchboard.guardian.detect.Detector;
import io.github.timganev.switchboard.guardian.surrogate.SurrogateFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardianTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough!";
    private static final String EGN = "7501020018";
    private static final String IBAN = "BG80BNBG96611020345678";

    private final Guardian guardian = guardian(Map.of());

    @Test
    void shouldLetARequestWithoutSensitiveDataGoToTheCloudUnchanged() {
        ChatRequest request = request(List.of(user("What is a contract?")));

        Inspection inspection = guardian.inspect(request);

        assertThat(inspection.route()).isEqualTo(Route.CLOUD);
        assertThat(inspection.reasons()).isEmpty();
        assertThat(inspection.request()).isEqualTo(request);
    }

    @Test
    void shouldReplaceSensitiveValuesEverywhereWithSurrogates() {
        ChatRequest request = new ChatRequest("qwen36", "Client EGN " + EGN, List.of(
                user("Summarise the risks for " + EGN + " and IBAN " + IBAN),
                new ChatMessage(Role.ASSISTANT, List.of(new ToolCallPart("t1", "lookup", "{\"iban\": \"" + IBAN + "\"}"))),
                new ChatMessage(Role.USER, List.of(new ToolResultPart("t1", "Owner of " + IBAN + " is " + EGN, false)))),
                null, null, null);

        Inspection inspection = guardian.inspect(request);

        assertThat(inspection.route()).isEqualTo(Route.SURROGATE);
        assertThat(inspection.reasons()).containsExactly(new SensitivityReason("IBAN", 1), new SensitivityReason("EGN", 1));
        assertThat(String.join(" ", ChatTexts.of(inspection.request()))).doesNotContain(EGN).doesNotContain(IBAN);
    }

    @Test
    void shouldHandTheGatewayTheSubstitutionsSoItCanRestoreAStream() {
        ChatRequest request = request(List.of(user("EGN " + EGN)));

        Inspection inspection = guardian.inspect(request);

        assertThat(inspection.substitutions()).singleElement().satisfies(substitution -> {
            assertThat(substitution.type()).isEqualTo(SensitiveType.EGN);
            assertThat(substitution.value()).isEqualTo(EGN);
            assertThat(text(inspection.request(), 0)).isEqualTo("EGN " + substitution.surrogate());
        });
    }

    @Test
    void shouldGiveAValueTheSameSurrogateOnEveryTurnOfAConversation() {
        ChatMessage first = user("My EGN is " + EGN);
        ChatRequest turn1 = request(List.of(first));
        ChatRequest turn2 = request(List.of(first, assistant("Noted."), user("Again: " + EGN)));

        String surrogate1 = text(guardian.inspect(turn1).request(), 0).substring("My EGN is ".length());
        String surrogate2 = text(guardian.inspect(turn2).request(), 2).substring("Again: ".length());

        assertThat(surrogate1).isEqualTo(surrogate2).isNotEqualTo(EGN);
    }

    @Test
    void shouldPutTheRealValuesBackIntoTheAnswer() {
        ChatRequest original = request(List.of(user("Check IBAN " + IBAN)));
        String surrogate = text(guardian.inspect(original).request(), 0).substring("Check IBAN ".length());
        ChatResponse answer = answer("IBAN " + surrogate + " looks fine.");

        Restoration restoration = guardian.restore(original, answer);

        assertThat(restoration.response().parts()).containsExactly(new TextPart("IBAN " + IBAN + " looks fine."));
        assertThat(restoration.responseReasons()).isEmpty();
    }

    @Test
    void shouldPutTheRealValueBackWhenTheModelRewroteTheSurrogate() {
        ChatRequest original = request(List.of(user("Check EGN " + EGN)));
        String surrogate = text(guardian.inspect(original).request(), 0).substring("Check EGN ".length());
        String dashed = String.join("-", surrogate.split(""));

        Restoration restoration = guardian.restore(original, answer("Digits: " + dashed));

        assertThat(restoration.response().parts()).containsExactly(new TextPart("Digits: " + String.join("-", EGN.split(""))));
        assertThat(restoration.responseReasons()).isEmpty();
    }

    @Test
    void shouldReportSensitiveDataTheModelProducedOnItsOwn() {
        ChatRequest original = request(List.of(user("Invent a contact for " + EGN)));

        Restoration restoration = guardian.restore(original, answer("Write to petar@firma.bg about " + EGN));

        assertThat(restoration.responseReasons()).containsExactly(new SensitivityReason("EMAIL", 1));
    }

    @Test
    void shouldBlockAPrivateKey() {
        Inspection inspection = guardian.inspect(request(List.of(user("Fix this: -----BEGIN PRIVATE KEY----- MIIE"))));

        assertThat(inspection.route()).isEqualTo(Route.BLOCK);
        assertThat(inspection.request()).isNull();
    }

    @Test
    void shouldKeepARequestLocalWhenThePolicySaysSoAndTheStrictestRouteWins() {
        Guardian strict = guardian(Map.of(SensitiveType.EGN, Route.LOCAL));
        ChatRequest request = request(List.of(user("EGN " + EGN + ", IBAN " + IBAN)));

        Inspection inspection = strict.inspect(request);

        assertThat(inspection.route()).isEqualTo(Route.LOCAL);
        assertThat(inspection.request()).isEqualTo(request);
    }

    private static Guardian guardian(Map<SensitiveType, Route> policy) {
        return new Guardian(new Detector(), new SurrogateFactory(), new GuardianProperties(SECRET, policy));
    }

    private static ChatRequest request(List<ChatMessage> messages) {
        return new ChatRequest("qwen36", null, messages, null, null, null);
    }

    private static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, List.of(new TextPart(text)));
    }

    private static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, List.of(new TextPart(text)));
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse("r1", "qwen36", List.of(new TextPart(text)), StopReason.END_TURN, new Usage(1, 1));
    }

    private static String text(ChatRequest request, int message) {
        ContentPart part = request.messages().get(message).parts().getFirst();
        return ((TextPart) part).text();
    }
}
