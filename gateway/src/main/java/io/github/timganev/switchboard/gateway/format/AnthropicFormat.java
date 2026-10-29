package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.ContentPart;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.ToolCallPart;
import io.github.timganev.switchboard.commons.chat.ToolDefinition;
import io.github.timganev.switchboard.commons.chat.ToolResultPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates the Anthropic messages format to and from the internal chat model.
 */
@Component
public class AnthropicFormat {

    // The Anthropic API requires max_tokens; OpenAI clients often leave it out.
    static final int DEFAULT_MAX_TOKENS = 4096;

    private final JsonMapper jsonMapper;

    public AnthropicFormat(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public ChatRequest readRequest(JsonNode json) {
        List<String> systemTexts = new ArrayList<>();
        JsonNode system = json.get("system");
        if (system != null && !system.isNull()) {
            systemTexts.add(readText(system));
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode message : FormatSupport.required(json, "messages")) {
            String role = FormatSupport.requiredText(message, "role");
            JsonNode content = FormatSupport.required(message, "content");
            switch (role) {
                case "user" -> messages.add(new ChatMessage(Role.USER, readParts(content)));
                case "assistant" -> messages.add(new ChatMessage(Role.ASSISTANT, readParts(content)));
                // Claude Code sends reminders as mid-conversation system messages; local chat templates accept one system prompt up front.
                case "system" -> systemTexts.add(readText(content));
                default -> throw new IllegalArgumentException("Unsupported message role: " + role);
            }
        }
        return new ChatRequest(
                FormatSupport.requiredText(json, "model"),
                systemTexts.isEmpty() ? null : String.join("\n\n", systemTexts),
                messages,
                readTools(json.path("tools")),
                json.has("max_tokens") ? json.get("max_tokens").asInt() : null,
                json.has("temperature") ? json.get("temperature").asDouble() : null);
    }

    public ObjectNode writeRequest(ChatRequest request) {
        ObjectNode json = jsonMapper.createObjectNode();
        json.put("model", request.model());
        if (request.system() != null) {
            json.put("system", request.system());
        }
        ArrayNode messages = json.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode written = messages.addObject().put("role", message.role() == Role.USER ? "user" : "assistant");
            written.set("content", writeParts(message.parts()));
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = json.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode written = tools.addObject().put("name", tool.name());
                if (tool.description() != null) {
                    written.put("description", tool.description());
                }
                written.set("input_schema", jsonMapper.readTree(tool.parametersJson()));
            }
        }
        json.put("max_tokens", request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens());
        if (request.temperature() != null) {
            json.put("temperature", request.temperature());
        }
        return json;
    }

    public ChatResponse readResponse(JsonNode json) {
        JsonNode usage = json.path("usage");
        return new ChatResponse(
                json.path("id").asString(),
                json.path("model").asString(),
                readParts(FormatSupport.required(json, "content")),
                readStopReason(json.path("stop_reason").asString()),
                new Usage(usage.path("input_tokens").asLong(), usage.path("output_tokens").asLong()));
    }

    public ObjectNode writeResponse(ChatResponse response) {
        ObjectNode json = jsonMapper.createObjectNode();
        json.put("id", response.id());
        json.put("type", "message");
        json.put("role", "assistant");
        json.put("model", response.model());
        json.set("content", writeParts(response.parts()));
        json.put("stop_reason", writeStopReason(response.stopReason()));
        json.putNull("stop_sequence");
        json.putObject("usage")
                .put("input_tokens", response.usage().inputTokens())
                .put("output_tokens", response.usage().outputTokens());
        return json;
    }

    private List<ContentPart> readParts(JsonNode content) {
        if (content.isString()) {
            return List.of(new TextPart(content.asString()));
        }
        if (!content.isArray()) {
            throw new IllegalArgumentException("Message content must be a string or an array");
        }
        List<ContentPart> parts = new ArrayList<>();
        for (JsonNode block : content) {
            String type = FormatSupport.requiredText(block, "type");
            parts.add(switch (type) {
                case "text" -> new TextPart(FormatSupport.requiredText(block, "text"));
                case "tool_use" -> new ToolCallPart(
                        FormatSupport.requiredText(block, "id"),
                        FormatSupport.requiredText(block, "name"),
                        jsonMapper.writeValueAsString(FormatSupport.required(block, "input")));
                case "tool_result" -> new ToolResultPart(
                        FormatSupport.requiredText(block, "tool_use_id"),
                        block.has("content") ? readText(block.get("content")) : "",
                        block.path("is_error").asBoolean(false));
                default -> throw new IllegalArgumentException("Unsupported content type: " + type);
            });
        }
        return parts;
    }

    private String readText(JsonNode content) {
        return readParts(content).stream()
                .map(part -> {
                    if (!(part instanceof TextPart text)) {
                        throw new IllegalArgumentException("Expected only text blocks");
                    }
                    return text.text();
                })
                .reduce((first, second) -> first + "\n\n" + second)
                .orElse("");
    }

    private ArrayNode writeParts(List<ContentPart> parts) {
        ArrayNode content = jsonMapper.createArrayNode();
        for (ContentPart part : parts) {
            switch (part) {
                case TextPart text -> content.addObject().put("type", "text").put("text", text.text());
                case ToolCallPart toolCall -> content.addObject()
                        .put("type", "tool_use")
                        .put("id", toolCall.id())
                        .put("name", toolCall.name())
                        .set("input", jsonMapper.readTree(toolCall.argumentsJson()));
                case ToolResultPart result -> {
                    ObjectNode block = content.addObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", result.toolCallId())
                            .put("content", result.content());
                    if (result.error()) {
                        block.put("is_error", true);
                    }
                }
            }
        }
        return content;
    }

    private List<ToolDefinition> readTools(JsonNode tools) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (JsonNode tool : tools) {
            definitions.add(new ToolDefinition(
                    FormatSupport.requiredText(tool, "name"),
                    tool.has("description") ? tool.get("description").asString() : null,
                    jsonMapper.writeValueAsString(FormatSupport.required(tool, "input_schema"))));
        }
        return definitions;
    }

    static StopReason readStopReason(String stopReason) {
        return switch (stopReason) {
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "tool_use" -> StopReason.TOOL_USE;
            default -> StopReason.END_TURN;
        };
    }

    static String writeStopReason(StopReason stopReason) {
        return switch (stopReason) {
            case END_TURN -> "end_turn";
            case MAX_TOKENS -> "max_tokens";
            case TOOL_USE -> "tool_use";
        };
    }
}
