package io.github.timganev.switchboard.leaklab.strategy;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import io.github.timganev.switchboard.commons.privacy.Substitution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class StrategyTest {

    private static final String PROMPT = "EGN 7501020018, phone +359 88 123 4567, second phone 0887 654 321";
    private static final Inspection SURROGATED = new Inspection(Route.SURROGATE, List.of(),
            request("EGN 8807143345, phone +359 88 765 4321, second phone 0889 111 222"),
            List.of(new Substitution(SensitiveType.EGN, "7501020018", "8807143345"),
                    new Substitution(SensitiveType.PHONE, "+359 88 123 4567", "+359 88 765 4321"),
                    new Substitution(SensitiveType.PHONE, "0887 654 321", "0889 111 222")));

    @Test
    void shouldReplaceWhatTheGuardianFoundInEachWay() {
        assertThat(Strategy.NONE.outbound(PROMPT, SURROGATED)).contains(PROMPT);
        assertThat(Strategy.MASK.outbound(PROMPT, SURROGATED)).contains("EGN ***, phone ***, second phone ***");
        assertThat(Strategy.PLACEHOLDER.outbound(PROMPT, SURROGATED)).contains("EGN [EGN_1], phone [PHONE_1], second phone [PHONE_2]");
        assertThat(Strategy.SURROGATE.outbound(PROMPT, SURROGATED)).contains("EGN 8807143345, phone +359 88 765 4321, second phone 0889 111 222");
        assertThat(Strategy.LOCAL.outbound(PROMPT, SURROGATED)).isEmpty();
    }

    @Test
    void shouldLetNothingOutWhenTheGuardianKeepsTheRequestLocal() {
        Inspection local = new Inspection(Route.LOCAL, List.of(), request(PROMPT), null);

        assertThat(Strategy.SURROGATE.outbound(PROMPT, local)).isEmpty();
        assertThatIllegalStateException().isThrownBy(() -> Strategy.MASK.outbound(PROMPT, local));
    }

    @Test
    void shouldGiveTheUserBackEveryValueButTheMaskedOnes() {
        assertThat(Strategy.MASK.restored("Call *** today.", SURROGATED)).isEqualTo("Call *** today.");
        assertThat(Strategy.PLACEHOLDER.restored("Call [PHONE_2], not [PHONE_1].", SURROGATED))
                .isEqualTo("Call 0887 654 321, not +359 88 123 4567.");
        assertThat(Strategy.SURROGATE.restored("Call 0889 111 222 about 8807143345.", SURROGATED))
                .isEqualTo("Call 0887 654 321 about 7501020018.");
    }

    @Test
    void shouldCountTheGuardianOnlyWhereItIsOnThePath() {
        Inspection blocked = new Inspection(Route.BLOCK, List.of(), null, null);

        assertThat(Strategy.SURROGATE.blocked(blocked)).isTrue();
        assertThat(Strategy.LOCAL.blocked(blocked)).isFalse();
        assertThat(Strategy.values()).filteredOn(Strategy::guarded).containsExactly(Strategy.MASK, Strategy.PLACEHOLDER, Strategy.SURROGATE, Strategy.SURROGATE_MODEL);
    }

    private static ChatRequest request(String text) {
        return new ChatRequest("m", null, List.of(new ChatMessage(Role.USER, List.of(new TextPart(text)))), null, null, null);
    }
}
