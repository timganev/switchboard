package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.Usage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiStreamWriterTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Test
    void shouldWriteChunksUsageAndDone() throws Exception {
        OpenAiStreamWriter writer = new OpenAiStreamWriter(out, new JsonMapper(), true);

        writer.write(new StreamEvent.MessageStarted("c1", "qwen36"));
        writer.write(new StreamEvent.TextDelta("Hello"));
        writer.write(new StreamEvent.MessageFinished(StopReason.END_TURN, new Usage(9, 2)));

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("""
                data: {"id":"c1","object":"chat.completion.chunk","model":"qwen36","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"c1","object":"chat.completion.chunk","model":"qwen36","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

                data: {"id":"c1","object":"chat.completion.chunk","model":"qwen36","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"c1","object":"chat.completion.chunk","model":"qwen36","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}

                data: [DONE]

                """);
    }

    @Test
    void shouldWriteToolCallChunksWithoutUsageWhenNotRequested() throws Exception {
        OpenAiStreamWriter writer = new OpenAiStreamWriter(out, new JsonMapper(), false);

        writer.write(new StreamEvent.MessageStarted("c2", "qwen36"));
        writer.write(new StreamEvent.ToolCallStarted(0, "call_1", "weather"));
        writer.write(new StreamEvent.ToolCallDelta(0, "{\"city\":\"Sofia\"}"));
        writer.write(new StreamEvent.MessageFinished(StopReason.TOOL_USE, new Usage(9, 5)));

        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("""
                        "tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"weather","arguments":""}}]""")
                .contains("""
                        "tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":\\"Sofia\\"}"}}]""")
                .contains("\"finish_reason\":\"tool_calls\"")
                .doesNotContain("usage")
                .endsWith("data: [DONE]\n\n");
    }
}
