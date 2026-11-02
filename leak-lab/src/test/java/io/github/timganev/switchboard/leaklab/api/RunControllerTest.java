package io.github.timganev.switchboard.leaklab.api;

import io.github.timganev.switchboard.leaklab.run.LeakRun;
import io.github.timganev.switchboard.leaklab.run.Runs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
