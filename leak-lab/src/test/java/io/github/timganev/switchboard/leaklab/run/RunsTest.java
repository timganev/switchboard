package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.leaklab.LeakLabProperties;
import io.github.timganev.switchboard.leaklab.strategy.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunsTest {

    private static final Score SCORE = new Score(Strategy.SURROGATE, 2, 5, 0, 0, 1, 0.5, 1, 0, 0, 2, 4.5, 100, 50, new BigDecimal("0.001050"), 900);

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void shouldSaveAFinishedRunAndLoadItAfterARestart(@TempDir Path resultsDir) {
        LeakLab leakLab = mock(LeakLab.class);
        when(leakLab.available()).thenReturn(40);
        when(leakLab.chance()).thenReturn(0.1);
        when(leakLab.run(anyInt(), any())).thenReturn(List.of(SCORE));

        String id = new Runs(leakLab, jsonMapper, properties(resultsDir)).start(2).id();

        await().until(() -> Files.exists(resultsDir.resolve(id + ".json")));
        LeakRun loaded = new Runs(leakLab, jsonMapper, properties(resultsDir)).find(id).orElseThrow();
        assertThat(loaded.status()).isEqualTo(LeakRun.Status.DONE);
        assertThat(loaded.scores()).containsExactly(SCORE);
    }

    @Test
    void shouldSkipAFileThatIsNotARun(@TempDir Path resultsDir) throws Exception {
        Files.writeString(resultsDir.resolve("notes.json"), "not json");

        assertThat(new Runs(mock(LeakLab.class), jsonMapper, properties(resultsDir)).find("notes")).isEmpty();
    }

    private static LeakLabProperties properties(Path resultsDir) {
        return new LeakLabProperties(null, null, null, null, null, null, null, 42, 40, 10, resultsDir);
    }
}
