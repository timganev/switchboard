package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.privacy.SurrogateRestorer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Puts the real values back into a streamed answer as it arrives, in the text and in every tool call's arguments.
 */
public class RestoringModelStream implements ModelStream {

    private final ModelStream stream;
    private final SurrogateRestorer restorer;

    public RestoringModelStream(ModelStream stream, SurrogateRestorer restorer) {
        this.stream = stream;
        this.restorer = restorer;
    }

    @Override
    public void forEach(EventConsumer consumer) throws IOException {
        SurrogateRestorer.Stream text = restorer.stream();
        Map<Integer, SurrogateRestorer.Stream> toolCalls = new LinkedHashMap<>();
        stream.forEach(event -> {
            switch (event) {
                case StreamEvent.TextDelta delta -> emitText(consumer, text.push(delta.text()));
                case StreamEvent.ToolCallDelta delta -> emitToolCall(consumer, delta.index(),
                        toolCalls.computeIfAbsent(delta.index(), index -> restorer.stream()).push(delta.argumentsFragment()));
                case StreamEvent.ToolCallStarted started -> {
                    // A held-back tail must go out before the next block starts, or it would land in the wrong one.
                    emitText(consumer, text.finish());
                    flushToolCalls(consumer, toolCalls);
                    consumer.accept(started);
                }
                case StreamEvent.MessageFinished finished -> {
                    emitText(consumer, text.finish());
                    flushToolCalls(consumer, toolCalls);
                    consumer.accept(finished);
                }
                case StreamEvent.MessageStarted started -> consumer.accept(started);
            }
        });
    }

    @Override
    public void close() {
        stream.close();
    }

    private static void emitText(EventConsumer consumer, String text) throws IOException {
        if (!text.isEmpty()) {
            consumer.accept(new StreamEvent.TextDelta(text));
        }
    }

    private static void emitToolCall(EventConsumer consumer, int index, String fragment) throws IOException {
        if (!fragment.isEmpty()) {
            consumer.accept(new StreamEvent.ToolCallDelta(index, fragment));
        }
    }

    private static void flushToolCalls(EventConsumer consumer, Map<Integer, SurrogateRestorer.Stream> toolCalls) throws IOException {
        for (Map.Entry<Integer, SurrogateRestorer.Stream> toolCall : toolCalls.entrySet()) {
            emitToolCall(consumer, toolCall.getKey(), toolCall.getValue().finish());
        }
    }
}
