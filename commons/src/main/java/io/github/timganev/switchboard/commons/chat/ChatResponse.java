package io.github.timganev.switchboard.commons.chat;

import java.util.List;

public record ChatResponse(String id, String model, List<ContentPart> parts, StopReason stopReason, Usage usage) {

    public ChatResponse {
        parts = List.copyOf(parts);
    }
}
