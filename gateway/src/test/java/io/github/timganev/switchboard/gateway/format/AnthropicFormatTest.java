package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.ToolCallPart;
import io.github.timganev.switchboard.commons.chat.ToolResultPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static io.github.timganev.switchboard.gateway.format.JsonAssertions.assertSameJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicFormatTest {

    private final JsonMapper jsonMapper = new JsonMapper();
    private final AnthropicFormat format = new AnthropicFormat(jsonMapper);

    @Test
    void shouldReadToolUseAndToolResultBlocks() {
        JsonNode json = jsonMapper.readTree("""
                {"model": "claude", "max_tokens": 100, "system": [{"type": "text", "text": "Be brief."}], "messages": [
                  {"role": "user", "content": "Weather in Sofia?"},
                  {"role": "assistant", "content": [
                    {"type": "text", "text": "Checking."},
                    {"type": "tool_use", "id": "toolu_1", "name": "weather", "input": {"city": "Sofia"}}]},
                  {"role": "user", "content": [
                    {"type": "tool_result", "tool_use_id": "toolu_1", "content": [{"type": "text", "text": "12C"}], "is_error": false}]}
                ]}
                """);

        ChatRequest request = format.readRequest(json);

        assertThat(request.system()).isEqualTo("Be brief.");
        assertThat(request.messages()).containsExactly(
                new ChatMessage(Role.USER, List.of(new TextPart("Weather in Sofia?"))),
                new ChatMessage(Role.ASSISTANT, List.of(
                        new TextPart("Checking."),
                        new ToolCallPart("toolu_1", "weather", "{\"city\":\"Sofia\"}"))),
                new ChatMessage(Role.USER, List.of(new ToolResultPart("toolu_1", "12C", false))));
    }

    @Test
    void shouldWriteBackTheSameRequestItRead() {
        JsonNode original = jsonMapper.readTree("""
                {"model": "claude", "system": "Be brief.", "messages": [
                  {"role": "user", "content": [{"type": "text", "text": "Weather in Sofia?"}]},
                  {"role": "assistant", "content": [
                    {"type": "tool_use", "id": "toolu_1", "name": "weather", "input": {"city": "Sofia"}}]},
                  {"role": "user", "content": [
                    {"type": "tool_result", "tool_use_id": "toolu_1", "content": "Service down", "is_error": true}]}
                ],
                "tools": [{"name": "weather", "description": "Current weather",
                  "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}}],
                "max_tokens": 256, "temperature": 0.2}
                """);

        JsonNode written = format.writeRequest(format.readRequest(original));

        assertSameJson(written, original);
    }

    @Test
    void shouldUseDefaultMaxTokensWhenRequestHasNone() {
        ChatRequest request = new ChatRequest("claude", null,
                List.of(new ChatMessage(Role.USER, List.of(new TextPart("Hi")))), null, null, null);

        JsonNode json = format.writeRequest(request);

        assertThat(json.get("max_tokens").asInt()).isEqualTo(AnthropicFormat.DEFAULT_MAX_TOKENS);
    }

    @Test
    void shouldTurnOpenAiToolCallArgumentsIntoAnthropicToolInput() {
        JsonNode openAiRequest = jsonMapper.readTree("""
                {"model": "claude", "messages": [
                  {"role": "user", "content": "Weather in Sofia?"},
                  {"role": "assistant", "content": null, "tool_calls": [
                    {"id": "call_1", "type": "function", "function": {"name": "weather", "arguments": "{\\"city\\":\\"Sofia\\"}"}}]},
                  {"role": "tool", "tool_call_id": "call_1", "content": "12C"}
                ]}
                """);

        JsonNode json = format.writeRequest(new OpenAiFormat(jsonMapper).readRequest(openAiRequest));

        assertThat(json.get("messages").get(1).get("content").get(0)).isEqualTo(jsonMapper.readTree("""
                {"type": "tool_use", "id": "call_1", "name": "weather", "input": {"city": "Sofia"}}
                """));
        assertThat(json.get("messages").get(2).get("content").get(0)).isEqualTo(jsonMapper.readTree("""
                {"type": "tool_result", "tool_use_id": "call_1", "content": "12C"}
                """));
    }

    @Test
    void shouldReadResponseAndMapStopReason() {
        JsonNode json = jsonMapper.readTree("""
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude",
                 "content": [{"type": "text", "text": "Hello"}],
                 "stop_reason": "max_tokens", "stop_sequence": null,
                 "usage": {"input_tokens": 10, "output_tokens": 5}}
                """);

        ChatResponse response = format.readResponse(json);

        assertThat(response).isEqualTo(new ChatResponse("msg_1", "claude",
                List.of(new TextPart("Hello")), StopReason.MAX_TOKENS, new Usage(10, 5)));
        assertSameJson(format.writeResponse(response), json);
    }

    @Test
    void shouldMoveSystemMessagesIntoSystemPrompt() {
        JsonNode json = jsonMapper.readTree("""
                {"model": "claude", "max_tokens": 100, "system": [{"type": "text", "text": "Be brief."}], "messages": [
                  {"role": "user", "content": "Hi"},
                  {"role": "system", "content": [{"type": "text", "text": "Today is Thursday.", "cache_control": {"type": "ephemeral"}}]}
                ]}
                """);

        ChatRequest request = format.readRequest(json);

        assertThat(request.system()).isEqualTo("Be brief.\n\nToday is Thursday.");
        assertThat(request.messages()).containsExactly(new ChatMessage(Role.USER, List.of(new TextPart("Hi"))));
    }

    @Test
    void shouldRejectUnknownRole() {
        JsonNode json = jsonMapper.readTree("""
                {"model": "claude", "messages": [{"role": "tool", "content": "12C"}]}
                """);

        assertThatThrownBy(() -> format.readRequest(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported message role: tool");
    }
}
