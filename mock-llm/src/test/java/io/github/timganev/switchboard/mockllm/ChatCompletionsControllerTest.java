package io.github.timganev.switchboard.mockllm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatCompletionsController.class)
class ChatCompletionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAnswerChatCompletionInOpenAiFormat() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.model").value("qwen36"))
                .andExpect(jsonPath("$.choices[0].message.content").value(ChatCompletionsController.CONTENT));
    }
}
