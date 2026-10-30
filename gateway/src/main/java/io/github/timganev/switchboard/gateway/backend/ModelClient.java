package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.gateway.format.OpenAiFormat;
import io.github.timganev.switchboard.gateway.format.OpenAiStreamReader;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * Sends a chat request to one OpenAI-compatible model instance.
 */
@Component
public class ModelClient {

    private final OpenAiFormat openAiFormat;
    private final JsonMapper jsonMapper;

    public ModelClient(OpenAiFormat openAiFormat, JsonMapper jsonMapper) {
        this.openAiFormat = openAiFormat;
        this.jsonMapper = jsonMapper;
    }

    public ChatResponse send(Backend backend, ChatRequest request) {
        JsonNode response = backend.restClient().post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(openAiFormat.writeRequest(forBackend(backend, request)))
                .retrieve()
                .body(JsonNode.class);
        return openAiFormat.readResponse(response);
    }

    /**
     * Opens a streamed answer; a backend error surfaces here, before anything is sent to the client.
     */
    public ModelStream openStream(Backend backend, ChatRequest request) {
        ObjectNode body = openAiFormat.writeRequest(forBackend(backend, request));
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        ClientHttpResponse response = backend.restClient().post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(body)
                .exchange((clientRequest, clientResponse) -> {
                    if (clientResponse.getStatusCode().isError()) {
                        try (clientResponse) {
                            throw new RestClientResponseException(
                                    "Backend returned " + clientResponse.getStatusCode().value(),
                                    clientResponse.getStatusCode(),
                                    clientResponse.getStatusText(),
                                    clientResponse.getHeaders(),
                                    clientResponse.getBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                        }
                    }
                    return clientResponse;
                }, false);
        return new SseModelStream(response, new OpenAiStreamReader(jsonMapper));
    }

    private static ChatRequest forBackend(Backend backend, ChatRequest request) {
        if (backend.model() == null) {
            return request;
        }
        return new ChatRequest(backend.model(), request.system(), request.messages(), request.tools(),
                request.maxTokens(), request.temperature());
    }
}
