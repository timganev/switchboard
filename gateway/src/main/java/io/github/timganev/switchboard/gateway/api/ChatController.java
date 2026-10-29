package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.gateway.backend.ModelClient;
import io.github.timganev.switchboard.gateway.backend.ModelStream;
import io.github.timganev.switchboard.gateway.format.AnthropicFormat;
import io.github.timganev.switchboard.gateway.format.AnthropicStreamWriter;
import io.github.timganev.switchboard.gateway.format.OpenAiFormat;
import io.github.timganev.switchboard.gateway.format.OpenAiStreamWriter;
import io.github.timganev.switchboard.gateway.format.StreamWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Function;

/**
 * The two client-facing APIs: OpenAI chat completions and Anthropic messages, with or without streaming.
 */
@RestController
@RequestMapping("/v1")
class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ModelClient modelClient;
    private final OpenAiFormat openAiFormat;
    private final AnthropicFormat anthropicFormat;
    private final JsonMapper jsonMapper;

    ChatController(ModelClient modelClient, OpenAiFormat openAiFormat, AnthropicFormat anthropicFormat, JsonMapper jsonMapper) {
        this.modelClient = modelClient;
        this.openAiFormat = openAiFormat;
        this.anthropicFormat = anthropicFormat;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping("/chat/completions")
    ResponseEntity<StreamingResponseBody> chatCompletions(@RequestBody JsonNode body) {
        ChatRequest request = openAiFormat.readRequest(body);
        if (isStreaming(body)) {
            boolean includeUsage = body.path("stream_options").path("include_usage").asBoolean(false);
            return stream(request, out -> new OpenAiStreamWriter(out, jsonMapper, includeUsage));
        }
        return json(openAiFormat.writeResponse(modelClient.send(request)));
    }

    @PostMapping("/messages")
    ResponseEntity<StreamingResponseBody> messages(@RequestBody JsonNode body) {
        ChatRequest request = anthropicFormat.readRequest(body);
        if (isStreaming(body)) {
            return stream(request, out -> new AnthropicStreamWriter(out, jsonMapper));
        }
        return json(anthropicFormat.writeResponse(modelClient.send(request)));
    }

    // Spring picks the return value handler by the declared type, so plain JSON answers are written as a body too.
    private ResponseEntity<StreamingResponseBody> json(JsonNode json) {
        byte[] bytes = jsonMapper.writeValueAsBytes(json);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out -> out.write(bytes));
    }

    private boolean isStreaming(JsonNode body) {
        return body.path("stream").asBoolean(false);
    }

    private ResponseEntity<StreamingResponseBody> stream(ChatRequest request, Function<OutputStream, StreamWriter> writerFactory) {
        ModelStream modelStream = modelClient.openStream(request);
        StreamingResponseBody body = out -> {
            StreamWriter writer = writerFactory.apply(out);
            try (modelStream) {
                modelStream.forEach(writer::write);
            } catch (IOException | RuntimeException exception) {
                log.warn("Stream broke: {}", exception.toString());
                writer.fail("The model backend stopped answering");
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }
}
