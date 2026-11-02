package io.github.timganev.switchboard.leaklab.model;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelClientTest {

    private static final String ANSWER = """
            {"choices": [{"message": {"role": "assistant", "content": "Done."}}], "usage": {"prompt_tokens": 31, "completion_tokens": 15}}""";

    @Test
    void shouldReportTheAnswerWithItsTokens() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://model");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://model/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("m"))
                .andExpect(jsonPath("$.max_tokens").value(100))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$.messages[0].content").value("Hi"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        Completion completion = new ModelClient(builder.build(), "m", "none").complete("Hi", 100);

        assertThat(completion.text()).isEqualTo("Done.");
        assertThat(completion.inputTokens()).isEqualTo(31);
        assertThat(completion.outputTokens()).isEqualTo(15);
        assertThat(completion.took()).isPositive();
        server.verify();
    }

    @Test
    void shouldLeaveTheReasoningEffortOutWhenItIsNotSet() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://model");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://model/v1/chat/completions"))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        new ModelClient(builder.build(), "m", null).complete("Hi", 100);

        server.verify();
    }
}
