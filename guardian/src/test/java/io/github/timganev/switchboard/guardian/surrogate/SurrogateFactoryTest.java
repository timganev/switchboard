package io.github.timganev.switchboard.guardian.surrogate;

import io.github.timganev.switchboard.guardian.detect.Detector;
import io.github.timganev.switchboard.guardian.detect.Finding;
import io.github.timganev.switchboard.guardian.detect.SensitiveType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SurrogateFactoryTest {

    private final SurrogateFactory factory = new SurrogateFactory();
    private final Detector detector = new Detector();
    private final byte[] key = factory.key("a-test-secret-that-is-long-enough!".getBytes(StandardCharsets.UTF_8), "conversation");

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "EGN     | 7501020018",
            "LNCH    | 1002003008",
            "IBAN    | BG80 BNBG 9661 1020 3456 78",
            "CARD    | 4111 1111 1111 1111",
            "PHONE   | +359 888 123 456",
            "EMAIL   | maria.ivanova@firma.bg",
            "API_KEY | sk-ant-api03-abcdefghijklmnopqrstuvwxyz012345",
            "JWT     | eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"})
    void shouldMakeADifferentValidValueOfTheSameType(SensitiveType type, String value) {
        String surrogate = factory.surrogate(key, type, value, 0);

        assertThat(surrogate).isNotEqualTo(value);
        assertThat(detector.scan(surrogate)).extracting(Finding::type).containsExactly(type);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"EGN | 7501020018", "IBAN | BG80BNBG96611020345678"})
    void shouldGiveTheSameSurrogateForTheSameKeyAndADifferentOneForAnotherKey(SensitiveType type, String value) {
        byte[] otherKey = factory.key("a-test-secret-that-is-long-enough!".getBytes(StandardCharsets.UTF_8), "another conversation");

        assertThat(factory.surrogate(key, type, value, 0)).isEqualTo(factory.surrogate(key, type, value, 0));
        assertThat(factory.surrogate(key, type, value, 0)).isNotEqualTo(factory.surrogate(otherKey, type, value, 0));
        assertThat(factory.surrogate(key, type, value, 0)).isNotEqualTo(factory.surrogate(key, type, value, 1));
    }
}
