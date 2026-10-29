package io.github.timganev.switchboard.commons.chat;

public sealed interface ContentPart permits TextPart, ToolCallPart, ToolResultPart {
}
