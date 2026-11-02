package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.leaklab.LeakLabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs take minutes against a real attacker, so each one runs in the background and is polled; finished runs are saved as files.
 */
@Component
public class Runs {

    private static final Logger log = LoggerFactory.getLogger(Runs.class);

    private final LeakLab leakLab;
    private final JsonMapper jsonMapper;
    private final Path resultsDir;
    private final Map<String, LeakRun> runs = new ConcurrentHashMap<>();

    Runs(LeakLab leakLab, JsonMapper jsonMapper, LeakLabProperties properties) {
        this.leakLab = leakLab;
        this.jsonMapper = jsonMapper;
        this.resultsDir = properties.resultsDir();
        load();
    }

    public LeakRun start(int cases) {
        if (cases < 1 || cases > leakLab.available()) {
            throw new IllegalArgumentException("cases must be between 1 and " + leakLab.available());
        }
        String id = UUID.randomUUID().toString();
        LeakRun run = new LeakRun(id, LeakRun.Status.RUNNING, cases, 0, leakLab.chance(), List.of(), null, Instant.now(), null);
        runs.put(id, run);
        Thread.ofVirtual().name("leak-run-" + id).start(() -> {
            try {
                List<Score> scores = leakLab.run(cases, done -> runs.computeIfPresent(id, (key, current) -> withDone(current, done)));
                save(runs.computeIfPresent(id, (key, current) -> finished(current, LeakRun.Status.DONE, scores, null)));
            } catch (RuntimeException exception) {
                log.error("Leak run {} failed", id, exception);
                runs.computeIfPresent(id, (key, current) -> finished(current, LeakRun.Status.FAILED, List.of(), exception.toString()));
            }
        });
        return run;
    }

    public Optional<LeakRun> find(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    // A run that could not be saved stays in memory; losing the file must not lose the result.
    private void save(LeakRun run) {
        if (resultsDir == null) {
            return;
        }
        try {
            Files.createDirectories(resultsDir);
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(resultsDir.resolve(run.id() + ".json").toFile(), run);
        } catch (IOException | JacksonException exception) {
            log.error("Could not save leak run {} to {}", run.id(), resultsDir, exception);
        }
    }

    private void load() {
        if (resultsDir == null || !Files.isDirectory(resultsDir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(resultsDir, "*.json")) {
            for (Path file : files) {
                try {
                    LeakRun run = jsonMapper.readValue(file.toFile(), LeakRun.class);
                    runs.put(run.id(), run);
                } catch (JacksonException exception) {
                    log.warn("Skipping {}, which is not a leak run: {}", file, exception.getOriginalMessage());
                }
            }
        } catch (IOException exception) {
            log.warn("Could not read saved leak runs from {}", resultsDir, exception);
        }
    }

    private static LeakRun withDone(LeakRun run, int done) {
        return new LeakRun(run.id(), run.status(), run.cases(), done, run.chance(), run.scores(), null, run.startedAt(), null);
    }

    private static LeakRun finished(LeakRun run, LeakRun.Status status, List<Score> scores, String error) {
        return new LeakRun(run.id(), status, run.cases(), run.done(), run.chance(), scores, error, run.startedAt(), Instant.now());
    }
}
