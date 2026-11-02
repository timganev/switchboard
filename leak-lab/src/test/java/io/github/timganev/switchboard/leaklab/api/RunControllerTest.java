package io.github.timganev.switchboard.leaklab.api;

import io.github.timganev.switchboard.leaklab.run.LeakRun;
import io.github.timganev.switchboard.leaklab.run.Runs;
import io.github.timganev.switchboard.leaklab.run.Score;
import io.github.timganev.switchboard.leaklab.strategy.Strategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RunController.class)
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Runs runs;

    @Test
    void shouldStartARunInTheBackground() throws Exception {
        when(runs.start(5)).thenReturn(new LeakRun("r-1", LeakRun.Status.RUNNING, 5, 0, 0.1, List.of(), null, Instant.now(), null));

        mockMvc.perform(post("/v1/runs").param("cases", "5"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("r-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void shouldRefuseABadCountAndAnswerNotFoundForAnUnknownRun() throws Exception {
        when(runs.start(0)).thenThrow(new IllegalArgumentException("cases must be between 1 and 40"));
        when(runs.find("nope")).thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/runs").param("cases", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/runs/nope")).andExpect(status().isNotFound());
    }

    @Test
    void shouldDrawTheChartOnlyForAFinishedRun() throws Exception {
        List<Score> scores = List.of(score(Strategy.NONE, 1.0, 4.8), score(Strategy.MASK, 0.75, 3.1), score(Strategy.LOCAL, 0, 4.7));
        when(runs.find("done")).thenReturn(Optional.of(new LeakRun("done", LeakRun.Status.DONE, 4, 4, 0.1, scores, null, Instant.now(), Instant.now())));
        when(runs.find("running")).thenReturn(Optional.of(new LeakRun("running", LeakRun.Status.RUNNING, 4, 1, 0.1, List.of(), null, Instant.now(), null)));

        mockMvc.perform(get("/v1/runs/done/chart.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string(allOf(containsString(">NONE</text>"), containsString(">MASK</text>"),
                        containsString(">LOCAL</text>"), containsString("blind guess"))));
        mockMvc.perform(get("/v1/runs/running/chart.svg")).andExpect(status().isNotFound());
    }

    private static Score score(Strategy strategy, double reidentified, double quality) {
        return new Score(strategy, 4, 10, 0, 0, 0, reidentified, 2, 0, 0, 4, quality, 4000, 400, BigDecimal.ZERO, 300);
    }
}
