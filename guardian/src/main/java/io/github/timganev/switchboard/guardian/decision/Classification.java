package io.github.timganev.switchboard.guardian.decision;

import java.util.Set;

/**
 * The categories the model found; {@code failed} means it gave no usable answer, which counts as a finding.
 */
public record Classification(Set<String> categories, boolean failed) {

    public static final Classification NOTHING = new Classification(Set.of(), false);
    public static final Classification FAILED = new Classification(Set.of(), true);

    public Classification {
        categories = Set.copyOf(categories);
    }

    public boolean found() {
        return failed || !categories.isEmpty();
    }
}
