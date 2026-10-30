package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.gateway.format.OpenAiFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelClientTest {

    private static final ChatRequest REQUEST = new ChatRequest("qwen36", "Be brief.",
            List.of(new ChatMessage(Role.USER, List.of(new TextPart("Hi")))), null, null, null);

    private MockRestServiceServer backend;
    private Backend instance;
    private ModelClient modelClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend/v1");
        backend = MockRestServiceServer.bindTo(builder).build();
        instance = new Backend("local-1", Locality.LOCAL, null, builder.build());
        JsonMapper jsonMapper = new JsonMapper();
        modelClient = new ModelClient(new OpenAiFormat(jsonMapper), jsonMapper);
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

        ChatResponse response = modelClient.send(instance, REQUEST);

        assertThat(response).isEqualTo(new ChatResponse("chatcmpl-1", "qwen36",
                List.of(new TextPart("Hello")), StopReason.END_TURN, new Usage(12, 2)));
        backend.verify();
    }

    @Test
    void shouldReplaceTheModelNameWhenTheInstanceNamesOne() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cloud/v1");
        MockRestServiceServer cloud = MockRestServiceServer.bindTo(builder).build();
        cloud.expect(requestTo("http://cloud/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
                .andRespond(withSuccess("""
                        {"id": "c1", "model": "claude-sonnet-5-5",
                         "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "Hello"}}],
                         "usage": {"prompt_tokens": 1, "completion_tokens": 1}}
                        """, MediaType.APPLICATION_JSON));

        modelClient.send(new Backend("cloud", Locality.CLOUD, "claude-sonnet-5-5", builder.build()), REQUEST);

        cloud.verify();
    }

    @Test
    void shouldFailWhenBackendReturnsServerError() {
        backend.expect(requestTo("http://backend/v1/chat/completions")).andRespond(withServerError());

        assertThatThrownBy(() -> modelClient.send(instance, REQUEST)).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void shouldStreamEventsUntilDone() throws Exception {
        backend.expect(requestTo("http://backend/v1/chat/completions"))
                .andExpect(jsonPath("$.stream").value(true))
                .andExpect(jsonPath("$.stream_options.include_usage").value(true))
                .andRespond(withSuccess("""
                        data: {"id": "c1", "model": "qwen36", "choices": [{"index": 0, "delta": {"content": "Hi"}, "finish_reason": "stop"}]}

                        data: {"id": "c1", "model": "qwen36", "choices": [], "usage": {"prompt_tokens": 3, "completion_tokens": 1}}

                        data: [DONE]

                        """, MediaType.TEXT_EVENT_STREAM));
        List<StreamEvent> events = new ArrayList<>();

        try (ModelStream stream = modelClient.openStream(instance, REQUEST)) {
            stream.forEach(events::add);
        }

        assertThat(events).containsExactly(
                new StreamEvent.MessageStarted("c1", "qwen36"),
                new StreamEvent.TextDelta("Hi"),
                new StreamEvent.MessageFinished(StopReason.END_TURN, new Usage(3, 1)));
    }

    @Test
    void shouldFailBeforeStreamingWhenBackendRejectsTheRequest() {
        backend.expect(requestTo("http://backend/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("slow down"));

        assertThatThrownBy(() -> modelClient.openStream(instance, REQUEST))
                .isInstanceOf(RestClientResponseException.class)
                .hasMessage("Backend returned 429");
    }

    @Test
    void shouldFailWhenStreamEndsWithoutDone() {
        backend.expect(requestTo("http://backend/v1/chat/completions"))
                .andRespond(withSuccess("""
                        data: {"id": "c1", "model": "qwen36", "choices": [{"index": 0, "delta": {"content": "Hi"}}]}

                        """, MediaType.TEXT_EVENT_STREAM));

        assertThatThrownBy(() -> {
            try (ModelStream stream = modelClient.openStream(instance, REQUEST)) {
                stream.forEach(event -> { });
            }
        }).hasMessage("The backend stream ended without [DONE]");
    }
}
