package io.github.timganev.switchboard.commons.chat;

public record ToolResultPart(String toolCallId, String content, boolean error) implements ContentPart {
}
