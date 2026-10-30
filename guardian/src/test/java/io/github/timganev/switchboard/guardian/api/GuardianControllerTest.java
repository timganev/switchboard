package io.github.timganev.switchboard.guardian.api;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.guardian.Guardian;
import io.github.timganev.switchboard.guardian.GuardianProperties;
import io.github.timganev.switchboard.guardian.detect.Detector;
import io.github.timganev.switchboard.guardian.events.DecisionEvents;
import io.github.timganev.switchboard.guardian.surrogate.SurrogateFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GuardianController.class)
@Import(GuardianControllerTest.Config.class)
class GuardianControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DecisionEvents decisionEvents;

    @Test
    void shouldAnswerAnInspectionAsJson() throws Exception {
        mockMvc.perform(post("/v1/inspect")
                        .header("X-Switchboard-Call-Id", "call-1")
                        .header("X-Switchboard-Session", "session-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "qwen36", "messages": [
                                  {"role": "USER", "parts": [{"type": "text", "text": "EGN 7501020018"}]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("SURROGATE"))
                .andExpect(jsonPath("$.reasons[0].type").value("EGN"))
                .andExpect(jsonPath("$.reasons[0].count").value(1))
                .andExpect(jsonPath("$.request.messages[0].parts[0].type").value("text"))
                .andExpect(jsonPath("$.request.messages[0].parts[0].text").value(not("EGN 7501020018")))
                .andExpect(jsonPath("$.substitutions[0].type").value("EGN"));

        verify(decisionEvents).decided(eq("call-1"), eq("session-1"), argThat(inspection -> inspection.route() == Route.SURROGATE));
    }

    @Test
    void shouldRestoreAnAnswer() throws Exception {
        mockMvc.perform(post("/v1/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"original": {"model": "qwen36", "messages": [
                                   {"role": "USER", "parts": [{"type": "text", "text": "Hi"}]}]},
                                 "response": {"id": "r1", "model": "qwen36", "stopReason": "END_TURN",
                                   "usage": {"inputTokens": 1, "outputTokens": 1},
                                   "parts": [{"type": "text", "text": "Hello"}]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.parts[0].text").value("Hello"))
                .andExpect(jsonPath("$.responseReasons").isEmpty());
    }

    static class Config {

        @Bean
        Guardian guardian() {
            return new Guardian(new Detector(), new SurrogateFactory(),
                    new GuardianProperties("a-test-secret-that-is-long-enough!", Map.of()));
        }
    }
}
