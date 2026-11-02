package io.github.timganev.switchboard.leaklab.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs take minutes against a real attacker, so each one runs in the background and is polled; they are kept in memory.
 */
@Component
public class Runs {

    private static final Logger log = LoggerFactory.getLogger(Runs.class);

    private final LeakLab leakLab;
    private final Map<String, LeakRun> runs = new ConcurrentHashMap<>();

    Runs(LeakLab leakLab) {
        this.leakLab = leakLab;
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
                runs.computeIfPresent(id, (key, current) -> finished(current, LeakRun.Status.DONE, scores, null));
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

    private static LeakRun withDone(LeakRun run, int done) {
        return new LeakRun(run.id(), run.status(), run.cases(), done, run.chance(), run.scores(), null, run.startedAt(), null);
    }

    private static LeakRun finished(LeakRun run, LeakRun.Status status, List<Score> scores, String error) {
        return new LeakRun(run.id(), status, run.cases(), run.done(), run.chance(), scores, error, run.startedAt(), Instant.now());
    }
}
