package io.github.timganev.switchboard.mockllm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class ChatCompletionsController {

    static final String MODEL = "mock-model";
    static final String CONTENT = "Mock response";

    @GetMapping("/v1/models")
    Map<String, Object> models() {
        return Map.of("object", "list", "data", List.of(Map.of("id", MODEL, "object", "model")));
    }

    @PostMapping("/v1/chat/completions")
    Map<String, Object> complete(@RequestBody Map<String, Object> request) {
        return Map.of(
                "id", "chatcmpl-" + UUID.randomUUID(),
                "object", "chat.completion",
                "model", request.getOrDefault("model", MODEL),
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", CONTENT),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 0, "completion_tokens", 2, "total_tokens", 2));
    }
}
