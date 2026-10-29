package io.github.timganev.switchboard.mockllm;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class ChatCompletionsController {

    static final String MODEL = "mock-model";
    static final String CONTENT = "Mock response";

    private final JsonMapper jsonMapper;

    ChatCompletionsController(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/v1/models")
    Map<String, Object> models() {
        return Map.of("object", "list", "data", List.of(Map.of("id", MODEL, "object", "model")));
    }

    @PostMapping("/v1/chat/completions")
    ResponseEntity<StreamingResponseBody> complete(@RequestBody Map<String, Object> request) {
        String id = "chatcmpl-" + UUID.randomUUID();
        Object model = request.getOrDefault("model", MODEL);
        if (Boolean.TRUE.equals(request.get("stream"))) {
            boolean includeUsage = request.get("stream_options") instanceof Map<?, ?> options
                    && Boolean.TRUE.equals(options.get("include_usage"));
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream(id, model, includeUsage));
        }
        byte[] answer = jsonMapper.writeValueAsBytes(Map.of(
                "id", id,
                "object", "chat.completion",
                "model", model,
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", CONTENT),
                        "finish_reason", "stop")),
                "usage", usage()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out -> out.write(answer));
    }

    private StreamingResponseBody stream(String id, Object model, boolean includeUsage) {
        return out -> {
            send(out, chunk(id, model, Map.of("role", "assistant", "content", ""), null));
            for (String word : CONTENT.split("(?= )")) {
                send(out, chunk(id, model, Map.of("content", word), null));
            }
            send(out, chunk(id, model, Map.of(), "stop"));
            if (includeUsage) {
                Map<String, Object> usageChunk = chunk(id, model, null, null);
                usageChunk.put("choices", List.of());
                usageChunk.put("usage", usage());
                send(out, usageChunk);
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        };
    }

    private Map<String, Object> chunk(String id, Object model, Map<String, Object> delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private Map<String, Object> usage() {
        return Map.of("prompt_tokens", 0, "completion_tokens", 2, "total_tokens", 2);
    }

    private void send(OutputStream out, Map<String, Object> chunk) throws IOException {
        out.write(("data: " + jsonMapper.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
