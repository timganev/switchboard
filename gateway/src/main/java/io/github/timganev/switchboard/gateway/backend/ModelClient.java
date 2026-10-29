package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.gateway.format.OpenAiFormat;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Sends a chat request to the OpenAI-compatible backend.
 */
@Component
public class ModelClient {

    private final RestClient backendRestClient;
    private final OpenAiFormat openAiFormat;

    public ModelClient(RestClient backendRestClient, OpenAiFormat openAiFormat) {
        this.backendRestClient = backendRestClient;
        this.openAiFormat = openAiFormat;
    }

    public ChatResponse send(ChatRequest request) {
        JsonNode response = backendRestClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(openAiFormat.writeRequest(request))
                .retrieve()
                .body(JsonNode.class);
        return openAiFormat.readResponse(response);
    }
}
