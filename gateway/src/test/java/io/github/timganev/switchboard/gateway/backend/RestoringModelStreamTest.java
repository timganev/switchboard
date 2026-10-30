package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import io.github.timganev.switchboard.commons.privacy.Substitution;
import io.github.timganev.switchboard.commons.privacy.SurrogateRestorer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RestoringModelStreamTest {

    private static final SurrogateRestorer RESTORER = new SurrogateRestorer(
            List.of(new Substitution(SensitiveType.EGN, "7501020018", "8807143345")));

    @Test
    void shouldRestoreTextAndToolArgumentsAndKeepTheBlocksInOrder() throws IOException {
        List<StreamEvent> events = new ArrayList<>();
        ModelStream stream = new RestoringModelStream(streamOf(
                new StreamEvent.MessageStarted("r1", "qwen36"),
                new StreamEvent.TextDelta("Looking up 88071"),
                new StreamEvent.TextDelta("43345"),
                new StreamEvent.ToolCallStarted(0, "t1", "lookup"),
                new StreamEvent.ToolCallDelta(0, "{\"egn\": \"8807"),
                new StreamEvent.ToolCallDelta(0, "143345\"}"),
                new StreamEvent.MessageFinished(StopReason.TOOL_USE, new Usage(5, 7))), RESTORER);

        stream.forEach(events::add);

        int toolStart = events.indexOf(new StreamEvent.ToolCallStarted(0, "t1", "lookup"));
        assertThat(text(events.subList(0, toolStart))).isEqualTo("Looking up 7501020018");
        assertThat(arguments(events.subList(toolStart, events.size()))).isEqualTo("{\"egn\": \"7501020018\"}");
        assertThat(events.getFirst()).isEqualTo(new StreamEvent.MessageStarted("r1", "qwen36"));
        assertThat(events.getLast()).isEqualTo(new StreamEvent.MessageFinished(StopReason.TOOL_USE, new Usage(5, 7)));
    }

    private static String text(List<StreamEvent> events) {
        StringBuilder out = new StringBuilder();
        events.stream().filter(StreamEvent.TextDelta.class::isInstance).forEach(event -> out.append(((StreamEvent.TextDelta) event).text()));
        return out.toString();
    }

    private static String arguments(List<StreamEvent> events) {
        StringBuilder out = new StringBuilder();
        events.stream().filter(StreamEvent.ToolCallDelta.class::isInstance)
                .forEach(event -> out.append(((StreamEvent.ToolCallDelta) event).argumentsFragment()));
        return out.toString();
    }

    private static ModelStream streamOf(StreamEvent... events) {
        return new ModelStream() {
            @Override
            public void forEach(EventConsumer consumer) throws IOException {
                for (StreamEvent event : events) {
                    consumer.accept(event);
                }
            }

            @Override
            public void close() {
            }
        };
    }
}
