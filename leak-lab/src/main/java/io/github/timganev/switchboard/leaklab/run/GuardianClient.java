package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.commons.CallHeaders;
import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Asks the real guardian what it would let out, the same way the gateway does.
 */
@Component
class GuardianClient {

    private static final String TEAM = "leak-lab";

    private final RestClient guardianRestClient;

    GuardianClient(RestClient guardianRestClient) {
        this.guardianRestClient = guardianRestClient;
    }

    Inspection inspect(String prompt) {
        ChatRequest request = new ChatRequest("leak-lab", null, List.of(new ChatMessage(Role.USER, List.of(new TextPart(prompt)))), null, null, null);
        Inspection inspection = guardianRestClient.post()
                .uri("/v1/inspect")
                .header(CallHeaders.TEAM, TEAM)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Inspection.class);
        if (inspection == null) {
            throw new IllegalStateException("Empty answer from the guardian");
        }
        return inspection;
    }
}
