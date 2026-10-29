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
 * Translates the OpenAI chat completions format to and from the internal chat model.
 */
@Component
public class OpenAiFormat {

    private final JsonMapper jsonMapper;

    public OpenAiFormat(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public ChatRequest readRequest(JsonNode json) {
        List<String> systemTexts = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode message : FormatSupport.required(json, "messages")) {
            String role = FormatSupport.requiredText(message, "role");
            switch (role) {
                case "system", "developer" -> systemTexts.add(readText(message.path("content")));
                case "user" -> messages.add(new ChatMessage(Role.USER, readTextParts(message.path("content"))));
                case "assistant" -> messages.add(new ChatMessage(Role.ASSISTANT, readAssistantParts(message)));
                case "tool" -> addToolResult(messages, new ToolResultPart(
                        FormatSupport.requiredText(message, "tool_call_id"), readText(message.path("content")), false));
                default -> throw new IllegalArgumentException("Unsupported message role: " + role);
            }
        }
        JsonNode maxTokens = json.has("max_completion_tokens") ? json.get("max_completion_tokens") : json.get("max_tokens");
        return new ChatRequest(
                FormatSupport.requiredText(json, "model"),
                systemTexts.isEmpty() ? null : String.join("\n\n", systemTexts),
                messages,
                readTools(json.path("tools")),
                maxTokens == null ? null : maxTokens.asInt(),
                json.has("temperature") ? json.get("temperature").asDouble() : null);
    }

    public ObjectNode writeRequest(ChatRequest request) {
        ObjectNode json = jsonMapper.createObjectNode();
        json.put("model", request.model());
        ArrayNode messages = json.putArray("messages");
        if (request.system() != null) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        for (ChatMessage message : request.messages()) {
            if (message.role() == Role.ASSISTANT) {
                messages.add(writeAssistantMessage(message.parts()));
            } else {
                writeUserMessages(messages, message.parts());
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = json.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                if (tool.description() != null) {
                    function.put("description", tool.description());
                }
                function.set("parameters", jsonMapper.readTree(tool.parametersJson()));
            }
        }
        if (request.maxTokens() != null) {
            json.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            json.put("temperature", request.temperature());
        }
        return json;
    }

    public ChatResponse readResponse(JsonNode json) {
        JsonNode choice = FormatSupport.required(json, "choices").path(0);
        JsonNode usage = json.path("usage");
        return new ChatResponse(
                json.path("id").asString(),
                json.path("model").asString(),
                readAssistantParts(FormatSupport.required(choice, "message")),
                readStopReason(choice.path("finish_reason").asString()),
                new Usage(usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong()));
    }

    public ObjectNode writeResponse(ChatResponse response) {
        ObjectNode json = jsonMapper.createObjectNode();
        json.put("id", response.id());
        json.put("object", "chat.completion");
        json.put("model", response.model());
        ObjectNode choice = json.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("message", writeAssistantMessage(response.parts()));
        choice.put("finish_reason", writeStopReason(response.stopReason()));
        json.putObject("usage")
                .put("prompt_tokens", response.usage().inputTokens())
                .put("completion_tokens", response.usage().outputTokens())
                .put("total_tokens", response.usage().inputTokens() + response.usage().outputTokens());
        return json;
    }

    private List<ContentPart> readAssistantParts(JsonNode message) {
        List<ContentPart> parts = new ArrayList<>();
        JsonNode content = message.path("content");
        if (!content.isMissingNode() && !content.isNull()) {
            parts.addAll(readTextParts(content));
        }
        for (JsonNode toolCall : message.path("tool_calls")) {
            JsonNode function = FormatSupport.required(toolCall, "function");
            parts.add(new ToolCallPart(
                    FormatSupport.requiredText(toolCall, "id"),
                    FormatSupport.requiredText(function, "name"),
                    function.path("arguments").asString("{}")));
        }
        return parts;
    }

    private List<ContentPart> readTextParts(JsonNode content) {
        if (content.isString()) {
            return List.of(new TextPart(content.asString()));
        }
        if (!content.isArray()) {
            throw new IllegalArgumentException("Message content must be a string or an array");
        }
        List<ContentPart> parts = new ArrayList<>();
        for (JsonNode part : content) {
            String type = FormatSupport.requiredText(part, "type");
            if (!type.equals("text")) {
                throw new IllegalArgumentException("Unsupported content type: " + type);
            }
            parts.add(new TextPart(FormatSupport.requiredText(part, "text")));
        }
        return parts;
    }

    private String readText(JsonNode content) {
        return readTextParts(content).stream()
                .map(part -> ((TextPart) part).text())
                .reduce((first, second) -> first + "\n\n" + second)
                .orElse("");
    }

    // OpenAI sends each tool result as its own message; consecutive ones belong to one user turn.
    private void addToolResult(List<ChatMessage> messages, ToolResultPart result) {
        ChatMessage last = messages.isEmpty() ? null : messages.getLast();
        if (last != null && last.role() == Role.USER && last.parts().stream().allMatch(ToolResultPart.class::isInstance)) {
            List<ContentPart> parts = new ArrayList<>(last.parts());
            parts.add(result);
            messages.set(messages.size() - 1, new ChatMessage(Role.USER, parts));
        } else {
            messages.add(new ChatMessage(Role.USER, List.of(result)));
        }
    }

    private List<ToolDefinition> readTools(JsonNode tools) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (JsonNode tool : tools) {
            JsonNode function = FormatSupport.required(tool, "function");
            definitions.add(new ToolDefinition(
                    FormatSupport.requiredText(function, "name"),
                    function.has("description") ? function.get("description").asString() : null,
                    jsonMapper.writeValueAsString(function.path("parameters"))));
        }
        return definitions;
    }

    private ObjectNode writeAssistantMessage(List<ContentPart> parts) {
        ObjectNode message = jsonMapper.createObjectNode().put("role", "assistant");
        List<String> texts = parts.stream()
                .filter(TextPart.class::isInstance)
                .map(part -> ((TextPart) part).text())
                .toList();
        if (texts.isEmpty()) {
            message.putNull("content");
        } else {
            message.put("content", String.join("", texts));
        }
        List<ToolCallPart> toolCalls = parts.stream()
                .filter(ToolCallPart.class::isInstance)
                .map(ToolCallPart.class::cast)
                .toList();
        if (!toolCalls.isEmpty()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (ToolCallPart toolCall : toolCalls) {
                ObjectNode call = calls.addObject().put("id", toolCall.id()).put("type", "function");
                call.putObject("function").put("name", toolCall.name()).put("arguments", toolCall.argumentsJson());
            }
        }
        return message;
    }

    private void writeUserMessages(ArrayNode messages, List<ContentPart> parts) {
        List<TextPart> texts = new ArrayList<>();
        for (ContentPart part : parts) {
            switch (part) {
                case ToolResultPart result -> messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", result.toolCallId())
                        .put("content", result.content());
                case TextPart text -> texts.add(text);
                case ToolCallPart toolCall ->
                        throw new IllegalArgumentException("A user message cannot contain a tool call: " + toolCall.id());
            }
        }
        if (texts.size() == 1) {
            messages.addObject().put("role", "user").put("content", texts.getFirst().text());
        } else if (texts.size() > 1) {
            ArrayNode content = messages.addObject().put("role", "user").putArray("content");
            texts.forEach(text -> content.addObject().put("type", "text").put("text", text.text()));
        }
    }

    static StopReason readStopReason(String finishReason) {
        return switch (finishReason) {
            case "length" -> StopReason.MAX_TOKENS;
            case "tool_calls" -> StopReason.TOOL_USE;
            default -> StopReason.END_TURN;
        };
    }

    static String writeStopReason(StopReason stopReason) {
        return switch (stopReason) {
            case END_TURN -> "stop";
            case MAX_TOKENS -> "length";
            case TOOL_USE -> "tool_calls";
        };
    }
}
