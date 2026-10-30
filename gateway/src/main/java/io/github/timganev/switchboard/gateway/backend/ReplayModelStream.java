package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.ContentPart;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.ToolCallPart;
import io.github.timganev.switchboard.commons.chat.ToolResultPart;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays back a complete answer as stream events.
 */
public class ReplayModelStream implements ModelStream {

    private final List<StreamEvent> events;

    public ReplayModelStream(ChatResponse response) {
        List<StreamEvent> list = new ArrayList<>();
        list.add(new StreamEvent.MessageStarted(response.id(), response.model()));
        int toolIndex = 0;
        for (ContentPart part : response.parts()) {
            switch (part) {
                case TextPart text -> list.add(new StreamEvent.TextDelta(text.text()));
                case ToolCallPart toolCall -> {
                    list.add(new StreamEvent.ToolCallStarted(toolIndex, toolCall.id(), toolCall.name()));
                    list.add(new StreamEvent.ToolCallDelta(toolIndex, toolCall.argumentsJson()));
                    toolIndex++;
                }
                case ToolResultPart ignored -> {
                }
            }
        }
        list.add(new StreamEvent.MessageFinished(response.stopReason(), response.usage()));
        this.events = List.copyOf(list);
    }

    @Override
    public void forEach(EventConsumer consumer) throws IOException {
        for (StreamEvent event : events) {
            consumer.accept(event);
        }
    }

    @Override
    public void close() {
    }
}
