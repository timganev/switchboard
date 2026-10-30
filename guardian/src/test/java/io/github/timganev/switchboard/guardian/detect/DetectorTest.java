package io.github.timganev.switchboard.guardian.detect;

import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DetectorTest {

    private final Detector detector = new Detector();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "EGN     | 7501020018",
            "EGN     | 0441150122",
            "LNCH    | 1002003008",
            "IBAN    | BG80BNBG96611020345678",
            "IBAN    | BG80 BNBG 9661 1020 3456 78",
            "IBAN    | DE89370400440532013000",
            "CARD    | 4111 1111 1111 1111",
            "CARD    | 5500-0000-0000-0004",
            "PHONE   | +359 888 123 456",
            "PHONE   | 0888123456",
            "EMAIL   | maria.ivanova@firma.bg",
            "API_KEY | sk-ant-api03-abcdefghijklmnopqrstuvwxyz012345",
            "API_KEY | AKIAIOSFODNN7EXAMPLE",
            "JWT     | eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
            "PRIVATE_KEY | -----BEGIN RSA PRIVATE KEY-----"})
    void shouldFindEachSensitiveType(SensitiveType type, String value) {
        assertThat(detector.scan("Please look at " + value + " today."))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.type()).isEqualTo(type);
                    assertThat(finding.value()).isEqualTo(value);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "7501020019",
            "7513320018",
            "BG81BNBG96611020345678",
            "4111 1111 1111 1112",
            "order 12345"})
    void shouldIgnoreNumbersWithWrongCheckDigitsOrDates(String text) {
        assertThat(detector.scan(text)).isEmpty();
    }

    @Test
    void shouldReportAPhoneNumberOnceEvenIfItAlsoLooksLikeAnId() {
        assertThat(detector.scan("call 0888123456")).extracting(Finding::type).containsExactly(SensitiveType.PHONE);
    }

    @Test
    void shouldFindSeveralValuesInOrder() {
        assertThat(detector.scan("EGN 7501020018, IBAN BG80BNBG96611020345678, mail a.b@c.bg"))
                .extracting(Finding::type)
                .containsExactly(SensitiveType.EGN, SensitiveType.IBAN, SensitiveType.EMAIL);
    }
}
