package io.github.timganev.switchboard.guardian.surrogate;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReplacerTest {

    @Test
    void shouldReplaceLongestFirstAndNeverReplaceAReplacement() {
        String result = Replacer.replace("a ab b", Map.of("a", "b", "ab", "x", "b", "a"));

        assertThat(result).isEqualTo("b x a");
    }
}
