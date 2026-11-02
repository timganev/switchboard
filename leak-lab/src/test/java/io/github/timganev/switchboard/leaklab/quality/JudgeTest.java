package io.github.timganev.switchboard.leaklab.quality;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeTest {

    @Test
    void shouldReadTheLastGradeForEachAnswer() {
        String reply = """
                A: 3, maybe
                **Answer B**: 2
                - C - 5
                A: 4
                F: 5""";

        assertThat(Judge.grades(reply, 4)).isEqualTo(Map.of(0, 4, 1, 2, 2, 5));
    }

    @Test
    void shouldShowTheRequestAndLabelEveryAnswer() {
        String prompt = Judge.prompt("Translate: hello", List.of("здравей", "***"));

        assertThat(prompt).contains("Translate: hello", "Answer A:\n\"\"\"\nздравей", "Answer B:\n\"\"\"\n***");
    }
}
