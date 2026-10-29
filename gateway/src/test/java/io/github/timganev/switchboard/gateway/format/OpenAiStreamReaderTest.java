package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.Usage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiStreamReaderTest {

    private final OpenAiStreamReader reader = new OpenAiStreamReader(new JsonMapper());

    @Test
    void shouldReadTextStreamWithUsage() {
        List<StreamEvent> events = readAll(
                """
                {"id": "c1", "model": "qwen36", "choices": [{"index": 0, "delta": {"role": "assistant", "content": ""}, "finish_reason": null}]}""",
                """
                {"id": "c1", "model": "qwen36", "choices": [{"index": 0, "delta": {"content": "Hel"}, "finish_reason": null}]}""",
                """
                {"id": "c1", "model": "qwen36", "choices": [{"index": 0, "delta": {"content": "lo"}, "finish_reason": "stop"}]}""",
                """
                {"id": "c1", "model": "qwen36", "choices": [], "usage": {"prompt_tokens": 9, "completion_tokens": 2}}""",
                "[DONE]");

        assertThat(events).containsExactly(
                new StreamEvent.MessageStarted("c1", "qwen36"),
                new StreamEvent.TextDelta("Hel"),
                new StreamEvent.TextDelta("lo"),
                new StreamEvent.MessageFinished(StopReason.END_TURN, new Usage(9, 2)));
    }

    @Test
    void shouldReadToolCallStream() {
        List<StreamEvent> events = readAll(
                """
                {"id": "c2", "model": "qwen36", "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [
                  {"index": 0, "id": "call_1", "type": "function", "function": {"name": "weather", "arguments": ""}}]}}]}""",
                """
                {"id": "c2", "model": "qwen36", "choices": [{"index": 0, "delta": {"tool_calls": [
                  {"index": 0, "function": {"arguments": "{\\"city\\":"}}]}}]}""",
                """
                {"id": "c2", "model": "qwen36", "choices": [{"index": 0, "delta": {"tool_calls": [
                  {"index": 0, "function": {"arguments": "\\"Sofia\\"}"}}]}, "finish_reason": "tool_calls"}]}""",
                "[DONE]");

        assertThat(events).containsExactly(
                new StreamEvent.MessageStarted("c2", "qwen36"),
                new StreamEvent.ToolCallStarted(0, "call_1", "weather"),
                new StreamEvent.ToolCallDelta(0, "{\"city\":"),
                new StreamEvent.ToolCallDelta(0, "\"Sofia\"}"),
                new StreamEvent.MessageFinished(StopReason.TOOL_USE, new Usage(0, 0)));
    }

    private List<StreamEvent> readAll(String... data) {
        List<StreamEvent> events = new ArrayList<>();
        for (String payload : data) {
            events.addAll(reader.read(payload));
        }
        return events;
    }
}
