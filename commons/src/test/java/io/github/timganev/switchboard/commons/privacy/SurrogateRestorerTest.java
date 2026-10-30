package io.github.timganev.switchboard.commons.privacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SurrogateRestorerTest {

    private static final Substitution EGN = new Substitution(SensitiveType.EGN, "7501020018", "8807143345");
    private static final Substitution IBAN = new Substitution(SensitiveType.IBAN, "BG80 BNBG 9661 1020 3456 78", "BG19 STSA 9300 0123 4567 89");
    private static final Substitution PHONE = new Substitution(SensitiveType.PHONE, "+359 88 123 4567", "+359 88 765 4321");
    private static final Substitution EMAIL = new Substitution(SensitiveType.EMAIL, "petar@firma.bg", "nina.koleva7@example.com");
    private static final Substitution API_KEY = new Substitution(SensitiveType.API_KEY, "sk-realrealrealrealreal1", "sk-fakefakefakefakefake2");

    private final SurrogateRestorer restorer = new SurrogateRestorer(List.of(EGN, IBAN, PHONE, EMAIL, API_KEY));

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "EGN 8807143345.|EGN 7501020018.",
            "EGN 8-8-0-7-1-4-3-3-4-5|EGN 7-5-0-1-0-2-0-0-1-8",
            "EGN 880 714 3345|EGN 750 102 0018",
            "IBAN BG19 STSA 9300 0123 4567 89|IBAN BG80 BNBG 9661 1020 3456 78",
            "IBAN BG19STSA93000123456789|IBAN BG80BNBG96611020345678",
            "IBAN bg19 stsa 9300 0123 4567 89|IBAN BG80 BNBG 9661 1020 3456 78",
            "Call +359 88 765 4321|Call +359 88 123 4567",
            "Call 0887654321|Call +359 88 123 4567",
            "Call +359887654321|Call +359881234567",
            "Mail Nina.Koleva7@Example.com|Mail petar@firma.bg",
            "Key sk-fakefakefakefakefake2|Key sk-realrealrealrealreal1"
    })
    void shouldRestoreASurrogateHoweverTheModelWroteIt(String answer, String restored) {
        assertThat(restorer.restore(answer)).isEqualTo(restored);
    }

    @Test
    void shouldLeaveAlikeNumbersAlone() {
        assertThat(restorer.restore("Order 188071433451 and 8807143346")).isEqualTo("Order 188071433451 and 8807143346");
    }

    @Test
    void shouldMatchAnUpperCaseKeyOnlyExactly() {
        assertThat(restorer.restore("SK-FAKEFAKEFAKEFAKEFAKE2")).isEqualTo("SK-FAKEFAKEFAKEFAKEFAKE2");
    }

    @Test
    void shouldRestoreASurrogateSplitAcrossPiecesOfAStream() {
        String answer = "The client 8807143345 with IBAN BG19 STSA 9300 0123 4567 89 wrote from nina.koleva7@example.com today.";
        for (int size = 1; size <= 7; size++) {
            SurrogateRestorer.Stream stream = restorer.stream();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < answer.length(); i += size) {
                out.append(stream.push(answer.substring(i, Math.min(answer.length(), i + size))));
            }
            out.append(stream.finish());

            assertThat(out).as("pieces of %d", size)
                    .hasToString("The client 7501020018 with IBAN BG80 BNBG 9661 1020 3456 78 wrote from petar@firma.bg today.");
        }
    }

    @Test
    void shouldNotTreatTheStartOfAPieceAsAWordBoundary() {
        SurrogateRestorer.Stream stream = restorer.stream();

        String out = stream.push("x".repeat(100) + "1") + stream.push("8807143345") + stream.finish();

        assertThat(out).isEqualTo("x".repeat(100) + "18807143345");
    }

    @Test
    void shouldHoldBackOnlyWhatCouldStillBeASurrogate() {
        SurrogateRestorer.Stream stream = restorer.stream();

        String first = stream.push("x".repeat(200));

        assertThat(first).hasSizeGreaterThan(100);
        assertThat(first + stream.finish()).isEqualTo("x".repeat(200));
    }

    @Test
    void shouldPassTextThroughWhenThereIsNothingToRestore() {
        SurrogateRestorer.Stream stream = new SurrogateRestorer(List.of()).stream();

        assertThat(stream.push("abc")).isEqualTo("abc");
        assertThat(stream.finish()).isEmpty();
    }
}
