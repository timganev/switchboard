package io.github.timganev.switchboard.gateway.guardian;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GuardianClientTest {

    private static final ChatRequest REQUEST = new ChatRequest("qwen36", null,
            List.of(new ChatMessage(Role.USER, List.of(new TextPart("EGN 7501020018")))), null, null, null);

    private MockRestServiceServer guardian;
    private GuardianClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://guardian");
        guardian = MockRestServiceServer.bindTo(builder).build();
        client = new GuardianClient(builder.build());
    }

    @Test
    void shouldSendTheRequestWithTypedPartsAndReadTheVerdict() {
        guardian.expect(requestTo("http://guardian/v1/inspect"))
                .andExpect(jsonPath("$.messages[0].parts[0].type").value("text"))
                .andRespond(withSuccess("""
                        {"route": "SURROGATE", "reasons": [{"type": "EGN", "count": 1}],
                         "request": {"model": "qwen36", "messages": [
                           {"role": "USER", "parts": [{"type": "text", "text": "EGN 8807143345"}]}]}}
                        """, MediaType.APPLICATION_JSON));

        Inspection inspection = client.inspect(REQUEST);

        assertThat(inspection.route()).isEqualTo(Route.SURROGATE);
        assertThat(inspection.reasons()).containsExactly(new SensitivityReason("EGN", 1));
        assertThat(inspection.request().messages().getFirst().parts()).containsExactly(new TextPart("EGN 8807143345"));
    }

    @Test
    void shouldFailClosedWhenTheGuardianFails() {
        guardian.expect(requestTo("http://guardian/v1/inspect")).andRespond(withServerError());

        assertThatThrownBy(() -> client.inspect(REQUEST)).isInstanceOf(GuardianUnavailableException.class);
    }
}
