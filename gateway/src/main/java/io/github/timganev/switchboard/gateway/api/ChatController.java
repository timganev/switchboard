package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.gateway.backend.ModelClient;
import io.github.timganev.switchboard.gateway.format.AnthropicFormat;
import io.github.timganev.switchboard.gateway.format.OpenAiFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The two client-facing APIs: OpenAI chat completions and Anthropic messages.
 */
@RestController
@RequestMapping("/v1")
class ChatController {

    private final ModelClient modelClient;
    private final OpenAiFormat openAiFormat;
    private final AnthropicFormat anthropicFormat;

    ChatController(ModelClient modelClient, OpenAiFormat openAiFormat, AnthropicFormat anthropicFormat) {
        this.modelClient = modelClient;
        this.openAiFormat = openAiFormat;
        this.anthropicFormat = anthropicFormat;
    }

    @PostMapping("/chat/completions")
    JsonNode chatCompletions(@RequestBody JsonNode body) {
        rejectStreaming(body);
        return openAiFormat.writeResponse(modelClient.send(openAiFormat.readRequest(body)));
    }

    @PostMapping("/messages")
    JsonNode messages(@RequestBody JsonNode body) {
        rejectStreaming(body);
        return anthropicFormat.writeResponse(modelClient.send(anthropicFormat.readRequest(body)));
    }

    private void rejectStreaming(JsonNode body) {
        if (body.path("stream").asBoolean(false)) {
            throw new IllegalArgumentException("Streaming is not supported yet");
        }
    }
}
