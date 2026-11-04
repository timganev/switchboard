package io.github.timganev.switchboard.guardian.decision;

import java.util.List;
import java.util.Set;

/**
 * The categories the model found and the names it proved; {@code failed} means it gave no usable answer, which counts as a finding.
 */
public record Classification(Set<String> categories, List<String> names, boolean failed) {

    public static final Classification NOTHING = new Classification(Set.of(), false);
    public static final Classification FAILED = new Classification(Set.of(), true);

    public Classification {
        categories = Set.copyOf(categories);
        names = List.copyOf(names);
    }

    public Classification(Set<String> categories, boolean failed) {
        this(categories, List.of(), failed);
    }

    public boolean found() {
        return failed || !categories.isEmpty();
    }

    // Only names can be swapped for surrogates; health data or a failure keeps the whole request in.
    public boolean onlyNames() {
        return !failed && !names.isEmpty() && categories.equals(Set.of("NAME"));
    }
}
