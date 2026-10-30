package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.gateway.backend.Dispatcher;
import io.github.timganev.switchboard.gateway.backend.Locality;
import io.github.timganev.switchboard.gateway.backend.ModelStream;
import io.github.timganev.switchboard.gateway.format.AnthropicFormat;
import io.github.timganev.switchboard.gateway.format.OpenAiFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import({OpenAiFormat.class, AnthropicFormat.class})
class ChatControllerTest {

    private static final ChatResponse ANSWER = new ChatResponse("resp-1", "qwen36",
            List.of(new TextPart("Hello")), StopReason.END_TURN, new Usage(12, 2));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Dispatcher dispatcher;

    @Test
    void shouldAnswerOpenAiRequestInOpenAiFormat() throws Exception {
        when(dispatcher.send(any(), any())).thenReturn(ANSWER);

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello"))
                .andExpect(jsonPath("$.usage.total_tokens").value(14));
    }

    @Test
    void shouldAnswerAnthropicRequestInAnthropicFormat() throws Exception {
        when(dispatcher.send(any(), any())).thenReturn(ANSWER);

        MvcResult result = mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "max_tokens": 100, "system": "Be brief.",
                                 "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.content[0].text").value("Hello"))
                .andExpect(jsonPath("$.stop_reason").value("end_turn"));

        verify(dispatcher).send(EnumSet.allOf(Locality.class), new ChatRequest("qwen36", "Be brief.",
                List.of(new ChatMessage(Role.USER, List.of(new TextPart("Hi")))), null, 100, null));
    }

    @Test
    void shouldStreamAnthropicEvents() throws Exception {
        when(dispatcher.openStream(any(), any())).thenReturn(streamOf(
                new StreamEvent.MessageStarted("resp-1", "qwen36"),
                new StreamEvent.TextDelta("Hello"),
                new StreamEvent.MessageFinished(StopReason.END_TURN, new Usage(12, 2))));

        MvcResult result = mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "max_tokens": 100, "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event: message_start")))
                .andExpect(content().string(containsString("\"text\":\"Hello\"")))
                .andExpect(content().string(endsWith("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")));
    }

    @Test
    void shouldStreamOpenAiChunksWithUsageWhenRequested() throws Exception {
        when(dispatcher.openStream(any(), any())).thenReturn(streamOf(
                new StreamEvent.MessageStarted("resp-1", "qwen36"),
                new StreamEvent.TextDelta("Hello"),
                new StreamEvent.MessageFinished(StopReason.END_TURN, new Usage(12, 2))));

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "stream": true, "stream_options": {"include_usage": true},
                                 "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"content\":\"Hello\"")))
                .andExpect(content().string(containsString("\"total_tokens\":14")))
                .andExpect(content().string(endsWith("data: [DONE]\n\n")));
    }

    @Test
    void shouldEndAnthropicStreamWithErrorEventWhenBackendBreaks() throws Exception {
        when(dispatcher.openStream(any(), any())).thenReturn(new ModelStream() {
            @Override
            public void forEach(EventConsumer consumer) throws IOException {
                consumer.accept(new StreamEvent.MessageStarted("resp-1", "qwen36"));
                throw new IOException("Connection reset");
            }

            @Override
            public void close() {
            }
        });

        MvcResult result = mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "max_tokens": 100, "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().string(endsWith(
                        "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"The model backend stopped answering\"}}\n\n")));
    }

    @Test
    void shouldReturnBadGatewayBeforeStreamingWhenBackendRejectsTheRequest() throws Exception {
        when(dispatcher.openStream(any(), any())).thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.type").value("api_error"));
    }

    @Test
    void shouldReturnBadGatewayWithAnthropicErrorWhenBackendIsUnreachable() throws Exception {
        when(dispatcher.send(any(), any())).thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "max_tokens": 100, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("api_error"));
    }

    private static ModelStream streamOf(StreamEvent... events) {
        return new ModelStream() {
            @Override
            public void forEach(EventConsumer consumer) throws IOException {
                for (StreamEvent event : events) {
                    consumer.accept(event);
                }
            }

            @Override
            public void close() {
            }
        };
    }
}
