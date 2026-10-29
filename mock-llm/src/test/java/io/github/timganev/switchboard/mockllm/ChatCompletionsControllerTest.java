package io.github.timganev.switchboard.mockllm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatCompletionsController.class)
class ChatCompletionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAnswerChatCompletionInOpenAiFormat() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.model").value("qwen36"))
                .andExpect(jsonPath("$.choices[0].message.content").value(ChatCompletionsController.CONTENT));
    }

    @Test
    void shouldStreamWordsUsageAndDone() throws Exception {
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
                .andExpect(content().string(containsString("\"delta\":{\"content\":\"Mock\"}")))
                .andExpect(content().string(containsString("\"delta\":{\"content\":\" response\"}")))
                .andExpect(content().string(containsString("\"finish_reason\":\"stop\"")))
                .andExpect(content().string(containsString("\"usage\"")))
                .andExpect(content().string(endsWith("data: [DONE]\n\n")));
    }
}
