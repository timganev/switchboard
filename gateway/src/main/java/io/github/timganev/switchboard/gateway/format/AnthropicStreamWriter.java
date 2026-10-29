package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.StreamEvent;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes stream events as Anthropic message stream events, opening and closing content blocks as needed.
 */
public class AnthropicStreamWriter implements StreamWriter {

    private final OutputStream out;
    private final JsonMapper jsonMapper;
    private final Map<Integer, Integer> blockByToolCall = new HashMap<>();
    private int nextBlock;
    private Integer openBlock;
    private boolean openBlockIsText;

    public AnthropicStreamWriter(OutputStream out, JsonMapper jsonMapper) {
        this.out = out;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void write(StreamEvent event) throws IOException {
        switch (event) {
            case StreamEvent.MessageStarted started -> {
                ObjectNode json = jsonMapper.createObjectNode().put("type", "message_start");
                ObjectNode message = json.putObject("message")
                        .put("id", started.id())
                        .put("type", "message")
                        .put("role", "assistant")
                        .put("model", started.model());
                message.putArray("content");
                message.putNull("stop_reason");
                message.putNull("stop_sequence");
                message.putObject("usage").put("input_tokens", 0).put("output_tokens", 0);
                send("message_start", json);
            }
            case StreamEvent.TextDelta text -> {
                if (openBlock == null || !openBlockIsText) {
                    ObjectNode block = jsonMapper.createObjectNode().put("type", "text").put("text", "");
                    openBlock(block, true);
                }
                ObjectNode json = jsonMapper.createObjectNode().put("type", "content_block_delta").put("index", openBlock);
                json.putObject("delta").put("type", "text_delta").put("text", text.text());
                send("content_block_delta", json);
            }
            case StreamEvent.ToolCallStarted toolCall -> {
                ObjectNode block = jsonMapper.createObjectNode()
                        .put("type", "tool_use")
                        .put("id", toolCall.id())
                        .put("name", toolCall.name());
                block.putObject("input");
                openBlock(block, false);
                blockByToolCall.put(toolCall.index(), openBlock);
            }
            case StreamEvent.ToolCallDelta toolCall -> {
                ObjectNode json = jsonMapper.createObjectNode()
                        .put("type", "content_block_delta")
                        .put("index", blockByToolCall.get(toolCall.index()));
                json.putObject("delta").put("type", "input_json_delta").put("partial_json", toolCall.argumentsFragment());
                send("content_block_delta", json);
            }
            case StreamEvent.MessageFinished finished -> {
                closeOpenBlock();
                ObjectNode delta = jsonMapper.createObjectNode().put("type", "message_delta");
                delta.putObject("delta")
                        .put("stop_reason", AnthropicFormat.writeStopReason(finished.stopReason()))
                        .putNull("stop_sequence");
                delta.putObject("usage")
                        .put("input_tokens", finished.usage().inputTokens())
                        .put("output_tokens", finished.usage().outputTokens());
                send("message_delta", delta);
                send("message_stop", jsonMapper.createObjectNode().put("type", "message_stop"));
            }
        }
    }

    @Override
    public void fail(String message) throws IOException {
        ObjectNode json = jsonMapper.createObjectNode().put("type", "error");
        json.putObject("error").put("type", "api_error").put("message", message);
        send("error", json);
    }

    private void openBlock(ObjectNode block, boolean text) throws IOException {
        closeOpenBlock();
        openBlock = nextBlock++;
        openBlockIsText = text;
        ObjectNode json = jsonMapper.createObjectNode().put("type", "content_block_start").put("index", openBlock);
        json.set("content_block", block);
        send("content_block_start", json);
    }

    private void closeOpenBlock() throws IOException {
        if (openBlock != null) {
            send("content_block_stop", jsonMapper.createObjectNode().put("type", "content_block_stop").put("index", openBlock));
            openBlock = null;
        }
    }

    private void send(String eventName, ObjectNode json) throws IOException {
        String event = "event: " + eventName + "\ndata: " + jsonMapper.writeValueAsString(json) + "\n\n";
        out.write(event.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
