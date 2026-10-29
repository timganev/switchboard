package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.StreamEvent;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes stream events as OpenAI chat completion chunks.
 */
public class OpenAiStreamWriter implements StreamWriter {

    private final OutputStream out;
    private final JsonMapper jsonMapper;
    private final boolean includeUsage;
    private String id = "";
    private String model = "";

    public OpenAiStreamWriter(OutputStream out, JsonMapper jsonMapper, boolean includeUsage) {
        this.out = out;
        this.jsonMapper = jsonMapper;
        this.includeUsage = includeUsage;
    }

    @Override
    public void write(StreamEvent event) throws IOException {
        switch (event) {
            case StreamEvent.MessageStarted started -> {
                id = started.id();
                model = started.model();
                ObjectNode chunk = chunk();
                delta(chunk).put("role", "assistant").put("content", "");
                send(chunk);
            }
            case StreamEvent.TextDelta text -> {
                ObjectNode chunk = chunk();
                delta(chunk).put("content", text.text());
                send(chunk);
            }
            case StreamEvent.ToolCallStarted toolCall -> {
                ObjectNode chunk = chunk();
                ObjectNode call = delta(chunk).putArray("tool_calls").addObject()
                        .put("index", toolCall.index())
                        .put("id", toolCall.id())
                        .put("type", "function");
                call.putObject("function").put("name", toolCall.name()).put("arguments", "");
                send(chunk);
            }
            case StreamEvent.ToolCallDelta toolCall -> {
                ObjectNode chunk = chunk();
                delta(chunk).putArray("tool_calls").addObject()
                        .put("index", toolCall.index())
                        .putObject("function").put("arguments", toolCall.argumentsFragment());
                send(chunk);
            }
            case StreamEvent.MessageFinished finished -> {
                ObjectNode chunk = chunk();
                delta(chunk);
                ((ObjectNode) chunk.get("choices").get(0)).put("finish_reason", OpenAiFormat.writeStopReason(finished.stopReason()));
                send(chunk);
                if (includeUsage) {
                    ObjectNode usageChunk = chunk();
                    usageChunk.putObject("usage")
                            .put("prompt_tokens", finished.usage().inputTokens())
                            .put("completion_tokens", finished.usage().outputTokens())
                            .put("total_tokens", finished.usage().inputTokens() + finished.usage().outputTokens());
                    send(usageChunk);
                }
                sendData("[DONE]");
            }
        }
    }

    @Override
    public void fail(String message) throws IOException {
        ObjectNode error = jsonMapper.createObjectNode();
        error.putObject("error").put("type", "api_error").put("message", message);
        send(error);
    }

    private ObjectNode chunk() {
        ObjectNode chunk = jsonMapper.createObjectNode()
                .put("id", id)
                .put("object", "chat.completion.chunk")
                .put("model", model);
        chunk.putArray("choices");
        return chunk;
    }

    private ObjectNode delta(ObjectNode chunk) {
        ObjectNode choice = ((ArrayNode) chunk.get("choices")).addObject().put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        choice.putNull("finish_reason");
        return delta;
    }

    private void send(ObjectNode json) throws IOException {
        sendData(jsonMapper.writeValueAsString(json));
    }

    private void sendData(String data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
