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

class OpenAiFormatTest {

    private final JsonMapper jsonMapper = new JsonMapper();
    private final OpenAiFormat format = new OpenAiFormat(jsonMapper);

    @Test
    void shouldCollectSystemAndDeveloperMessagesIntoSystemPrompt() {
        JsonNode json = jsonMapper.readTree("""
                {"model": "gpt", "messages": [
                  {"role": "system", "content": "Be brief."},
                  {"role": "developer", "content": [{"type": "text", "text": "Answer in English."}]},
                  {"role": "user", "content": "Hi"}
                ]}
                """);

        ChatRequest request = format.readRequest(json);

        assertThat(request.system()).isEqualTo("Be brief.\n\nAnswer in English.");
        assertThat(request.messages()).containsExactly(new ChatMessage(Role.USER, List.of(new TextPart("Hi"))));
    }

    @Test
    void shouldGroupConsecutiveToolResultsIntoOneUserMessage() {
        JsonNode json = jsonMapper.readTree("""
                {"model": "gpt", "messages": [
                  {"role": "user", "content": "Weather in Sofia and Varna?"},
                  {"role": "assistant", "content": null, "tool_calls": [
                    {"id": "call_1", "type": "function", "function": {"name": "weather", "arguments": "{\\"city\\":\\"Sofia\\"}"}},
                    {"id": "call_2", "type": "function", "function": {"name": "weather", "arguments": "{\\"city\\":\\"Varna\\"}"}}
                  ]},
                  {"role": "tool", "tool_call_id": "call_1", "content": "12C"},
                  {"role": "tool", "tool_call_id": "call_2", "content": "15C"}
                ]}
                """);

        ChatRequest request = format.readRequest(json);

        assertThat(request.messages()).hasSize(3);
        assertThat(request.messages().get(1).parts()).containsExactly(
                new ToolCallPart("call_1", "weather", "{\"city\":\"Sofia\"}"),
                new ToolCallPart("call_2", "weather", "{\"city\":\"Varna\"}"));
        assertThat(request.messages().get(2)).isEqualTo(new ChatMessage(Role.USER, List.of(
                new ToolResultPart("call_1", "12C", false),
                new ToolResultPart("call_2", "15C", false))));
    }

    @Test
    void shouldWriteBackTheSameRequestItRead() {
        JsonNode original = jsonMapper.readTree("""
                {"model": "gpt", "messages": [
                  {"role": "system", "content": "Be brief."},
                  {"role": "user", "content": "Weather in Sofia?"},
                  {"role": "assistant", "content": null, "tool_calls": [
                    {"id": "call_1", "type": "function", "function": {"name": "weather", "arguments": "{\\"city\\":\\"Sofia\\"}"}}
                  ]},
                  {"role": "tool", "tool_call_id": "call_1", "content": "12C"},
                  {"role": "user", "content": [{"type": "text", "text": "Thanks."}, {"type": "text", "text": "And tomorrow?"}]}
                ],
                "tools": [{"type": "function", "function": {"name": "weather", "description": "Current weather",
                  "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}}],
                "max_tokens": 256, "temperature": 0.2}
                """);

        JsonNode written = format.writeRequest(format.readRequest(original));

        assertSameJson(written, original);
    }

    @Test
    void shouldReadToolCallResponse() {
        JsonNode json = jsonMapper.readTree("""
                {"id": "chatcmpl-1", "object": "chat.completion", "model": "gpt",
                 "choices": [{"index": 0, "finish_reason": "tool_calls", "message": {"role": "assistant", "content": null,
                   "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "weather", "arguments": "{}"}}]}}],
                 "usage": {"prompt_tokens": 30, "completion_tokens": 7, "total_tokens": 37}}
                """);

        ChatResponse response = format.readResponse(json);

        assertThat(response).isEqualTo(new ChatResponse("chatcmpl-1", "gpt",
                List.of(new ToolCallPart("call_1", "weather", "{}")), StopReason.TOOL_USE, new Usage(30, 7)));
    }

    @Test
    void shouldWriteResponseInChatCompletionFormat() {
        ChatResponse response = new ChatResponse("msg_1", "claude",
                List.of(new TextPart("Hello")), StopReason.MAX_TOKENS, new Usage(10, 5));

        JsonNode json = format.writeResponse(response);

        assertSameJson(json, jsonMapper.readTree("""
                {"id": "msg_1", "object": "chat.completion", "model": "claude",
                 "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello"}, "finish_reason": "length"}],
                 "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}
                """));
    }

    @Test
    void shouldRejectUnsupportedContentType() {
        JsonNode json = jsonMapper.readTree("""
                {"model": "gpt", "messages": [{"role": "user", "content": [
                  {"type": "image_url", "image_url": {"url": "https://example.com/cat.png"}}]}]}
                """);

        assertThatThrownBy(() -> format.readRequest(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported content type: image_url");
    }

    @Test
    void shouldRejectRequestWithoutModel() {
        JsonNode json = jsonMapper.readTree("""
                {"messages": [{"role": "user", "content": "Hi"}]}
                """);

        assertThatThrownBy(() -> format.readRequest(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing required field: model");
    }
}
