package io.github.timganev.switchboard.leaklab.dataset;

import java.util.List;

/**
 * A prompt about one person, with the identifiers it contains; {@code named} says whether the name itself is in the prompt.
 */
public record LeakCase(Person person, boolean named, String prompt, List<Secret> secrets) {

    public LeakCase {
        secrets = List.copyOf(secrets);
    }
}
