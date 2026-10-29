package io.github.timganev.switchboard.commons.chat;

import java.util.List;

public record ChatRequest(
        String model,
        String system,
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        Integer maxTokens,
        Double temperature) {

    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
