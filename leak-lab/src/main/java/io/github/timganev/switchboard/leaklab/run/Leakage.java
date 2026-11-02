package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.leaklab.dataset.Secret;

import java.util.List;
import java.util.Locale;

/**
 * Counts the real values that left the boundary, even when the separators around them changed.
 */
final class Leakage {

    private Leakage() {
    }

    static int leaked(String outbound, List<Secret> secrets) {
        String text = normalized(outbound);
        return (int) secrets.stream().filter(secret -> text.contains(normalized(secret.value()))).count();
    }

    private static String normalized(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}@.]", "").toLowerCase(Locale.ROOT);
    }
}
