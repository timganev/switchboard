package io.github.timganev.switchboard.leaklab.dataset;

import io.github.timganev.switchboard.commons.privacy.Checksums;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetTest {

    private final List<Person> population = Population.generate(40, 42);

    @Test
    void shouldMakeTheSamePeopleFromTheSameSeed() {
        assertThat(Population.generate(40, 42)).isEqualTo(population);
        assertThat(Population.generate(40, 7)).isNotEqualTo(population);
    }

    @Test
    void shouldGiveEveryoneIdentifiersThatPassTheRealChecks() {
        assertThat(population).allSatisfy(person -> {
            assertThat(Checksums.isEgn(person.egn())).as(person.egn()).isTrue();
            assertThat(person.egn().substring(4, 6)).isEqualTo("%02d".formatted(person.birthDate().getDayOfMonth()));
            assertThat(Checksums.isIban(person.iban().replace(" ", ""))).as(person.iban()).isTrue();
            assertThat(person.phone()).matches("\\+359 8[7-9] \\d{3} \\d{4}");
        });
    }

    @Test
    void shouldLabelEveryValueThatIsInThePromptAndLeaveTheNameOutOfEveryOtherOne() {
        List<LeakCase> cases = Dataset.of(population);

        assertThat(cases).hasSize(40).allSatisfy(leakCase -> {
            assertThat(leakCase.secrets()).isNotEmpty().allSatisfy(secret -> assertThat(leakCase.prompt()).contains(secret.value()));
            assertThat(leakCase.prompt()).contains(leakCase.person().town(), leakCase.person().job());
            assertThat(leakCase.prompt().contains(leakCase.person().name())).isEqualTo(leakCase.named());
        });
        assertThat(cases).filteredOn(LeakCase::named).hasSize(20);
    }

    @Test
    void shouldHideTheRightPersonAmongThoseMostLikeThem() {
        Person target = population.getFirst();

        List<Person> candidates = Candidates.of(target, population, 10, 42);

        assertThat(candidates).hasSize(10).contains(target).doesNotHaveDuplicates();
        assertThat(candidates).isEqualTo(Candidates.of(target, population, 10, 42));
        assertThat(candidates).filteredOn(person -> person != target)
                .anyMatch(person -> person.town().equals(target.town()) || person.job().equals(target.job()));
    }
}
