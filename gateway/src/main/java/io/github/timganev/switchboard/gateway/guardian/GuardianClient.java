package io.github.timganev.switchboard.gateway.guardian;

import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Restoration;
import io.github.timganev.switchboard.commons.privacy.RestoreRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks the guardian for a verdict before a request leaves, and for the real values when the answer comes back.
 */
@Component
public class GuardianClient {

    private final RestClient guardianRestClient;

    public GuardianClient(RestClient guardianRestClient) {
        this.guardianRestClient = guardianRestClient;
    }

    public Inspection inspect(ChatRequest request) {
        return call("/v1/inspect", request, Inspection.class);
    }

    public Restoration restore(ChatRequest original, ChatResponse response) {
        return call("/v1/restore", new RestoreRequest(original, response), Restoration.class);
    }

    private <T> T call(String path, Object body, Class<T> answerType) {
        T answer;
        try {
            answer = guardianRestClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(answerType);
        } catch (RestClientException exception) {
            throw new GuardianUnavailableException(exception);
        }
        if (answer == null) {
            throw new GuardianUnavailableException(new IllegalStateException("Empty answer from " + path));
        }
        return answer;
    }
}
