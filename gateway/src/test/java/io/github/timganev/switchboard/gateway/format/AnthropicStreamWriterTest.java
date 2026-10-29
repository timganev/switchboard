package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.Usage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicStreamWriterTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final AnthropicStreamWriter writer = new AnthropicStreamWriter(out, new JsonMapper());

    @Test
    void shouldOpenAndCloseBlocksForTextFollowedByToolCall() throws Exception {
        writer.write(new StreamEvent.MessageStarted("c1", "qwen36"));
        writer.write(new StreamEvent.TextDelta("Checking."));
        writer.write(new StreamEvent.ToolCallStarted(0, "call_1", "weather"));
        writer.write(new StreamEvent.ToolCallDelta(0, "{\"city\":\"Sofia\"}"));
        writer.write(new StreamEvent.MessageFinished(StopReason.TOOL_USE, new Usage(9, 5)));

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("""
                event: message_start
                data: {"type":"message_start","message":{"id":"c1","type":"message","role":"assistant","model":"qwen36","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":0,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking."}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_1","name":"weather","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":\\"Sofia\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"input_tokens":9,"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """);
    }

    @Test
    void shouldKeepConsecutiveTextDeltasInOneBlock() throws Exception {
        writer.write(new StreamEvent.MessageStarted("c1", "qwen36"));
        writer.write(new StreamEvent.TextDelta("Hel"));
        writer.write(new StreamEvent.TextDelta("lo"));
        writer.write(new StreamEvent.MessageFinished(StopReason.END_TURN, new Usage(1, 2)));

        String stream = out.toString(StandardCharsets.UTF_8);
        assertThat(stream.split("event: content_block_start", -1)).hasSize(2);
        assertThat(stream.split("event: content_block_stop", -1)).hasSize(2);
    }

    @Test
    void shouldWriteErrorEvent() throws Exception {
        writer.fail("The model backend stopped answering");

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("""
                event: error
                data: {"type":"error","error":{"type":"api_error","message":"The model backend stopped answering"}}

                """);
    }
}
