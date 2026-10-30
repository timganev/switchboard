package io.github.timganev.switchboard.gateway.board;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Counts only this replica's requests; used alone, or while Redis is unreachable.
 */
public class LocalInflightBoard implements InflightBoard {

    private final Map<String, Integer> counts = new HashMap<>();

    @Override
    public synchronized Lease acquire(List<String> candidates) {
        String best = candidates.stream()
                .min(Comparator.comparingInt(candidate -> counts.getOrDefault(candidate, 0)))
                .orElseThrow(() -> new IllegalArgumentException("No candidates to choose from"));
        counts.merge(best, 1, Integer::sum);
        AtomicBoolean closed = new AtomicBoolean();
        return new Lease() {
            @Override
            public String instance() {
                return best;
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    release(best);
                }
            }
        };
    }

    public synchronized int count(String instance) {
        return counts.getOrDefault(instance, 0);
    }

    private synchronized void release(String instance) {
        counts.computeIfPresent(instance, (key, count) -> count > 1 ? count - 1 : null);
    }
}
