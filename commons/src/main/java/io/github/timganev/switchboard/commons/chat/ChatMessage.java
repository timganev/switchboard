package io.github.timganev.switchboard.commons.chat;

import java.util.List;

public record ChatMessage(Role role, List<ContentPart> parts) {

    public ChatMessage {
        parts = List.copyOf(parts);
    }
}
