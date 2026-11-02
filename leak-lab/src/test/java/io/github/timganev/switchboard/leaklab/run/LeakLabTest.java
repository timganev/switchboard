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
import io.github.timganev.switchboard.leaklab.model.Completion;
import io.github.timganev.switchboard.leaklab.model.ModelClient;
import io.github.timganev.switchboard.leaklab.quality.Judge;
import io.github.timganev.switchboard.leaklab.strategy.Strategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeakLabTest {

    private static final LeakLabProperties PROPERTIES = new LeakLabProperties(null, null, null, null, null,
            new LeakLabProperties.Price(BigDecimal.valueOf(3), BigDecimal.valueOf(15)), 42, 8, 4);

    private final List<Person> population = Population.generate(8, 42);
    private final List<LeakCase> cases = Dataset.of(population);

    @Test
    void shouldScoreEachStrategyOnWhatItLetOutAndWhatTheUserGotBack() {
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
        // Both models echo the prompt, 1000 tokens in and 100 out.
        ModelClient cloud = echo(Duration.ofMillis(200));
        ModelClient local = echo(Duration.ofMillis(900));
        // A simple judge: an answer is good when it still holds every real EGN of the request.
        Judge judge = mock(Judge.class);
        when(judge.grade(anyString(), anyList())).thenAnswer(invocation -> {
            String request = invocation.getArgument(0);
            List<String> answers = invocation.getArgument(1);
            String egn = cases.stream().filter(leakCase -> leakCase.prompt().equals(request)).findFirst().orElseThrow().person().egn();
            Map<Integer, Integer> grades = new HashMap<>();
            for (int i = 0; i < answers.size(); i++) {
                grades.put(i, answers.get(i).contains(egn) ? Judge.BEST : 2);
            }
            return grades;
        });
        List<Integer> done = new ArrayList<>();

        Map<Strategy, Score> scores = new LeakLab(guardian, attacker, cloud, local, judge, PROPERTIES).run(8, done::add).stream()
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
        // Masked values are lost; placeholders and surrogates come back.
        assertThat(scores).allSatisfy((strategy, score) -> assertThat(score.graded()).isEqualTo(8));
        assertThat(scores.get(Strategy.MASK).quality()).isEqualTo(2.0);
        assertThat(List.of(Strategy.NONE, Strategy.PLACEHOLDER, Strategy.SURROGATE, Strategy.LOCAL))
                .allSatisfy(strategy -> assertThat(scores.get(strategy).quality()).isEqualTo(5.0));
        // 8 cloud answers of 1000 tokens in and 100 out at 3 and 15 USD per million; local answers cost nothing.
        assertThat(scores.get(Strategy.NONE).cloudCostUsd()).isEqualByComparingTo("0.036");
        assertThat(scores.get(Strategy.LOCAL).cloudCostUsd()).isEqualByComparingTo("0");
        assertThat(scores.get(Strategy.LOCAL).outputTokens()).isEqualTo(800);
        assertThat(scores.get(Strategy.NONE).meanLatencyMs()).isEqualTo(200);
        assertThat(scores.get(Strategy.MASK).meanLatencyMs()).isGreaterThanOrEqualTo(200);
        assertThat(scores.get(Strategy.LOCAL).meanLatencyMs()).isEqualTo(900);
        // Nothing leaves under LOCAL, so there is nothing to attack; the judge sees every case once.
        verify(attacker, times(8 * 4)).identify(anyString(), anyList());
        verify(judge, times(8)).grade(anyString(), anyList());
    }

    private static ModelClient echo(Duration took) {
        ModelClient model = mock(ModelClient.class);
        when(model.complete(anyString(), anyInt())).thenAnswer(invocation -> new Completion(invocation.getArgument(0), 1000, 100, took));
        return model;
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
