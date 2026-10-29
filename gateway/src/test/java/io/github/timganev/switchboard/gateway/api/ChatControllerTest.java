package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.gateway.backend.ModelClient;
import io.github.timganev.switchboard.gateway.format.AnthropicFormat;
import io.github.timganev.switchboard.gateway.format.OpenAiFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import({OpenAiFormat.class, AnthropicFormat.class})
class ChatControllerTest {

    private static final ChatResponse ANSWER = new ChatResponse("resp-1", "qwen36",
            List.of(new TextPart("Hello")), StopReason.END_TURN, new Usage(12, 2));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelClient modelClient;

    @Test
    void shouldAnswerOpenAiRequestInOpenAiFormat() throws Exception {
        when(modelClient.send(any())).thenReturn(ANSWER);

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello"))
                .andExpect(jsonPath("$.usage.total_tokens").value(14));
    }

    @Test
    void shouldAnswerAnthropicRequestInAnthropicFormat() throws Exception {
        when(modelClient.send(any())).thenReturn(ANSWER);

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "max_tokens": 100, "system": "Be brief.",
                                 "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.content[0].text").value("Hello"))
                .andExpect(jsonPath("$.stop_reason").value("end_turn"));

        verify(modelClient).send(new ChatRequest("qwen36", "Be brief.",
                List.of(new ChatMessage(Role.USER, List.of(new TextPart("Hi")))), null, 100, null));
    }

    @Test
    void shouldRejectStreamingWithOpenAiError() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.message").value("Streaming is not supported yet"));

        verifyNoInteractions(modelClient);
    }

    @Test
    void shouldReturnBadGatewayWithAnthropicErrorWhenBackendIsUnreachable() throws Exception {
        when(modelClient.send(any())).thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "max_tokens": 100, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("api_error"));
    }
}
