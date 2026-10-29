package io.github.timganev.switchboard.commons.chat;

public record ToolCallPart(String id, String name, String argumentsJson) implements ContentPart {
}
