package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.privacy.Inspection;
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
import java.util.List;
import java.util.function.Function;

/**
 * The two client-facing APIs: OpenAI chat completions and Anthropic messages, with or without streaming.
 */
@RestController
@RequestMapping("/v1")
class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatPipeline pipeline;
    private final OpenAiFormat openAiFormat;
    private final AnthropicFormat anthropicFormat;
    private final JsonMapper jsonMapper;

    ChatController(ChatPipeline pipeline, OpenAiFormat openAiFormat, AnthropicFormat anthropicFormat, JsonMapper jsonMapper) {
        this.pipeline = pipeline;
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
        ChatPipeline.Answer answer = pipeline.send(request);
        return json(answer, openAiFormat.writeResponse(answer.response()));
    }

    @PostMapping("/messages")
    ResponseEntity<StreamingResponseBody> messages(@RequestBody JsonNode body) {
        ChatRequest request = anthropicFormat.readRequest(body);
        if (isStreaming(body)) {
            return stream(request, out -> new AnthropicStreamWriter(out, jsonMapper));
        }
        ChatPipeline.Answer answer = pipeline.send(request);
        return json(answer, anthropicFormat.writeResponse(answer.response()));
    }

    // Spring picks the return value handler by the declared type, so plain JSON answers are written as a body too.
    private ResponseEntity<StreamingResponseBody> json(ChatPipeline.Answer answer, JsonNode json) {
        byte[] bytes = jsonMapper.writeValueAsBytes(json);
        return ok(answer.inspection(), answer.responseReasons()).contentType(MediaType.APPLICATION_JSON).body(out -> out.write(bytes));
    }

    private boolean isStreaming(JsonNode body) {
        return body.path("stream").asBoolean(false);
    }

    private ResponseEntity<StreamingResponseBody> stream(ChatRequest request, Function<OutputStream, StreamWriter> writerFactory) {
        ChatPipeline.Streamed streamed = pipeline.stream(request);
        ModelStream modelStream = streamed.stream();
        StreamingResponseBody body = out -> {
            StreamWriter writer = writerFactory.apply(out);
            try (modelStream) {
                modelStream.forEach(writer::write);
            } catch (IOException | RuntimeException exception) {
                log.warn("Stream broke: {}", exception.toString());
                writer.fail("The model backend stopped answering");
            }
        };
        // The response scan needs the whole answer, so a live stream carries no findings header.
        return ok(streamed.inspection(), List.of()).contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }

    private static ResponseEntity.BodyBuilder ok(Inspection inspection, List<SensitivityReason> responseReasons) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(SwitchboardHeaders.ROUTE, SwitchboardHeaders.route(inspection.route(), inspection.reasons()));
        if (!responseReasons.isEmpty()) {
            builder.header(SwitchboardHeaders.RESPONSE_FINDINGS, SwitchboardHeaders.types(responseReasons));
        }
        return builder;
    }
}
