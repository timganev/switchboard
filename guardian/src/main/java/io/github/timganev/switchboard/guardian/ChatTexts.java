package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.ContentPart;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.ToolCallPart;
import io.github.timganev.switchboard.commons.chat.ToolDefinition;
import io.github.timganev.switchboard.commons.chat.ToolResultPart;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Every piece of free text in a request or answer: system prompt, messages, tool arguments, tool results and tool definitions.
 */
final class ChatTexts {

    private ChatTexts() {
    }

    static List<String> of(ChatRequest request) {
        List<String> texts = new ArrayList<>();
        map(request, text -> {
            texts.add(text);
            return text;
        });
        return texts;
    }

    static List<String> of(ChatResponse response) {
        List<String> texts = new ArrayList<>();
        map(response, text -> {
            texts.add(text);
            return text;
        });
        return texts;
    }

    static ChatRequest map(ChatRequest request, UnaryOperator<String> mapper) {
        UnaryOperator<String> safe = text -> text == null ? null : mapper.apply(text);
        List<ChatMessage> messages = request.messages().stream()
                .map(message -> new ChatMessage(message.role(), map(message.parts(), safe)))
                .toList();
        List<ToolDefinition> tools = request.tools().stream()
                .map(tool -> new ToolDefinition(tool.name(), safe.apply(tool.description()), safe.apply(tool.parametersJson())))
                .toList();
        return new ChatRequest(request.model(), safe.apply(request.system()), messages, tools,
                request.maxTokens(), request.temperature());
    }

    static ChatResponse map(ChatResponse response, UnaryOperator<String> mapper) {
        UnaryOperator<String> safe = text -> text == null ? null : mapper.apply(text);
        return new ChatResponse(response.id(), response.model(), map(response.parts(), safe), response.stopReason(), response.usage());
    }

    private static List<ContentPart> map(List<ContentPart> parts, UnaryOperator<String> mapper) {
        return parts.stream().<ContentPart>map(part -> switch (part) {
            case TextPart text -> new TextPart(mapper.apply(text.text()));
            case ToolCallPart toolCall -> new ToolCallPart(toolCall.id(), toolCall.name(), mapper.apply(toolCall.argumentsJson()));
            case ToolResultPart toolResult -> new ToolResultPart(toolResult.toolCallId(), mapper.apply(toolResult.content()), toolResult.error());
        }).toList();
    }
}
