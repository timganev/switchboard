package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.gateway.format.OpenAiFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelClientTest {

    private static final ChatRequest REQUEST = new ChatRequest("qwen36", "Be brief.",
            List.of(new ChatMessage(Role.USER, List.of(new TextPart("Hi")))), null, null, null);

    private MockRestServiceServer backend;
    private ModelClient modelClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend/v1");
        backend = MockRestServiceServer.bindTo(builder).build();
        modelClient = new ModelClient(builder.build(), new OpenAiFormat(new JsonMapper()));
    }

    @Test
    void shouldSendRequestInOpenAiFormatAndReadTheAnswer() {
        backend.expect(requestTo("http://backend/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("qwen36"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("Hi"))
                .andRespond(withSuccess("""
                        {"id": "chatcmpl-1", "model": "qwen36",
                         "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "Hello"}}],
                         "usage": {"prompt_tokens": 12, "completion_tokens": 2}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = modelClient.send(REQUEST);

        assertThat(response).isEqualTo(new ChatResponse("chatcmpl-1", "qwen36",
                List.of(new TextPart("Hello")), StopReason.END_TURN, new Usage(12, 2)));
        backend.verify();
    }

    @Test
    void shouldFailWhenBackendReturnsServerError() {
        backend.expect(requestTo("http://backend/v1/chat/completions")).andRespond(withServerError());

        assertThatThrownBy(() -> modelClient.send(REQUEST)).isInstanceOf(HttpServerErrorException.class);
    }
}
