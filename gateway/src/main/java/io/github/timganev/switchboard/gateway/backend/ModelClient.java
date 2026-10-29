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
 * Sends a chat request to the OpenAI-compatible backend.
 */
@Component
public class ModelClient {

    private final RestClient backendRestClient;
    private final OpenAiFormat openAiFormat;
    private final JsonMapper jsonMapper;

    public ModelClient(RestClient backendRestClient, OpenAiFormat openAiFormat, JsonMapper jsonMapper) {
        this.backendRestClient = backendRestClient;
        this.openAiFormat = openAiFormat;
        this.jsonMapper = jsonMapper;
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

    /**
     * Opens a streamed answer; a backend error surfaces here, before anything is sent to the client.
     */
    public ModelStream openStream(ChatRequest request) {
        ObjectNode body = openAiFormat.writeRequest(request);
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        ClientHttpResponse response = backendRestClient.post()
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
}
