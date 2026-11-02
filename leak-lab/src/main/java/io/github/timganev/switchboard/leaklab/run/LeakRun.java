package io.github.timganev.switchboard.leaklab.run;

import java.time.Instant;
import java.util.List;

public record LeakRun(String id, Status status, int cases, int done, double chance, List<Score> scores, String error,
                      Instant startedAt, Instant finishedAt) {

    public enum Status {
        RUNNING,
        DONE,
        FAILED
    }
}
