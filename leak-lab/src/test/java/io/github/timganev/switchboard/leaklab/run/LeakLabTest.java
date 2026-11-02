package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Substitution;
import io.github.timganev.switchboard.leaklab.LeakLabProperties;
import io.github.timganev.switchboard.leaklab.attack.Attacker;
import io.github.timganev.switchboard.leaklab.dataset.Dataset;
import io.github.timganev.switchboard.leaklab.dataset.LeakCase;
import io.github.timganev.switchboard.leaklab.dataset.Person;
import io.github.timganev.switchboard.leaklab.dataset.Population;
import io.github.timganev.switchboard.leaklab.dataset.Secret;
import io.github.timganev.switchboard.leaklab.strategy.Strategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeakLabTest {

    private static final LeakLabProperties PROPERTIES = new LeakLabProperties(null, null, 42, 8, 4);

    private final List<Person> population = Population.generate(8, 42);
    private final List<LeakCase> cases = Dataset.of(population);

    @Test
    void shouldScoreEachStrategyOnWhatItLetOut() {
        GuardianClient guardian = mock(GuardianClient.class);
        cases.forEach(leakCase -> when(guardian.inspect(leakCase.prompt())).thenReturn(surrogated(leakCase)));
        // A simple attacker: it knows the person only by a name or a real EGN.
        Attacker attacker = mock(Attacker.class);
        when(attacker.identify(anyString(), anyList())).thenAnswer(invocation -> {
            String outbound = invocation.getArgument(0);
            List<Person> candidates = invocation.getArgument(1);
            return candidates.stream().filter(person -> outbound.contains(person.name()) || outbound.contains(person.egn()))
                    .map(Person::id).findFirst();
        });
        List<Integer> done = new ArrayList<>();

        Map<Strategy, Score> scores = new LeakLab(guardian, attacker, PROPERTIES).run(8, done::add).stream()
                .collect(Collectors.toMap(Score::strategy, Function.identity()));

        assertThat(done).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(scores.get(Strategy.NONE)).satisfies(score -> {
            assertThat(score.leakRate()).isEqualTo(1.0);
            assertThat(score.reidentificationRate()).isEqualTo(1.0);
        });
        assertThat(List.of(Strategy.MASK, Strategy.PLACEHOLDER, Strategy.SURROGATE)).allSatisfy(strategy -> {
            assertThat(scores.get(strategy).leakRate()).isZero();
            assertThat(scores.get(strategy).reidentificationRate()).isEqualTo(0.5);
            assertThat(scores.get(strategy).unnamedReidentificationRate()).isZero();
        });
        assertThat(scores.get(Strategy.LOCAL)).satisfies(score -> {
            assertThat(score.values()).isEqualTo(scores.get(Strategy.NONE).values());
            assertThat(score.valuesLeaked()).isZero();
            assertThat(score.reidentified()).isZero();
        });
        // Nothing leaves under LOCAL, so there is nothing to attack.
        verify(attacker, times(8 * 4)).identify(anyString(), anyList());
    }

    private static Inspection surrogated(LeakCase leakCase) {
        List<Substitution> substitutions = new ArrayList<>();
        String outbound = leakCase.prompt();
        for (Secret secret : leakCase.secrets()) {
            String surrogate = "FAKE-" + secret.type() + "-" + substitutions.size();
            substitutions.add(new Substitution(secret.type(), secret.value(), surrogate));
            outbound = outbound.replace(secret.value(), surrogate);
        }
        return new Inspection(Route.SURROGATE, List.of(), request(outbound), substitutions);
    }

    private static ChatRequest request(String text) {
        return new ChatRequest("m", null, List.of(new ChatMessage(Role.USER, List.of(new TextPart(text)))), null, null, null);
    }
}
