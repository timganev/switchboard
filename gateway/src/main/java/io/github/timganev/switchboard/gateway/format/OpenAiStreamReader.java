package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.Usage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code data:} payloads of one OpenAI chat completions stream into stream events.
 */
public class OpenAiStreamReader {

    private static final String DONE = "[DONE]";

    private final JsonMapper jsonMapper;
    private boolean started;
    private StopReason stopReason = StopReason.END_TURN;
    private Usage usage = new Usage(0, 0);

    public OpenAiStreamReader(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public List<StreamEvent> read(String data) {
        if (data.equals(DONE)) {
            List<StreamEvent> events = new ArrayList<>(start("", ""));
            events.add(new StreamEvent.MessageFinished(stopReason, usage));
            return events;
        }
        JsonNode chunk = jsonMapper.readTree(data);
        List<StreamEvent> events = new ArrayList<>(start(chunk.path("id").asString(), chunk.path("model").asString()));
        JsonNode chunkUsage = chunk.path("usage");
        if (chunkUsage.isObject()) {
            usage = new Usage(chunkUsage.path("prompt_tokens").asLong(), chunkUsage.path("completion_tokens").asLong());
        }
        JsonNode choice = chunk.path("choices").path(0);
        JsonNode delta = choice.path("delta");
        if (delta.path("content").isString() && !delta.path("content").asString().isEmpty()) {
            events.add(new StreamEvent.TextDelta(delta.path("content").asString()));
        }
        for (JsonNode toolCall : delta.path("tool_calls")) {
            int index = toolCall.path("index").asInt();
            JsonNode function = toolCall.path("function");
            if (toolCall.has("id")) {
                events.add(new StreamEvent.ToolCallStarted(index, toolCall.path("id").asString(), function.path("name").asString()));
            }
            String arguments = function.path("arguments").asString("");
            if (!arguments.isEmpty()) {
                events.add(new StreamEvent.ToolCallDelta(index, arguments));
            }
        }
        if (choice.path("finish_reason").isString()) {
            stopReason = OpenAiFormat.readStopReason(choice.path("finish_reason").asString());
        }
        return events;
    }

    private List<StreamEvent> start(String id, String model) {
        if (started) {
            return List.of();
        }
        started = true;
        return List.of(new StreamEvent.MessageStarted(id, model));
    }
}
