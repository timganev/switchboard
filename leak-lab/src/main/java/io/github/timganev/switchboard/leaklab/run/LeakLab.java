package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.leaklab.LeakLabProperties;
import io.github.timganev.switchboard.leaklab.attack.Attacker;
import io.github.timganev.switchboard.leaklab.dataset.Candidates;
import io.github.timganev.switchboard.leaklab.dataset.Dataset;
import io.github.timganev.switchboard.leaklab.dataset.LeakCase;
import io.github.timganev.switchboard.leaklab.dataset.Person;
import io.github.timganev.switchboard.leaklab.dataset.Population;
import io.github.timganev.switchboard.leaklab.model.Completion;
import io.github.timganev.switchboard.leaklab.model.ModelClient;
import io.github.timganev.switchboard.leaklab.quality.Judge;
import io.github.timganev.switchboard.leaklab.strategy.Strategy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.IntConsumer;

/**
 * Runs every case through every strategy, attacks what each one let out, and has the judge grade the answers the user got.
 */
@Service
public class LeakLab {

    private static final int ANSWER_TOKENS = 1500;

    private final GuardianClient guardian;
    private final Attacker attacker;
    private final ModelClient cloudModel;
    private final ModelClient localModel;
    private final Judge judge;
    private final LeakLabProperties properties;

    LeakLab(GuardianClient guardian, Attacker attacker, ModelClient cloudModel, ModelClient localModel, Judge judge,
            LeakLabProperties properties) {
        this.guardian = guardian;
        this.attacker = attacker;
        this.cloudModel = cloudModel;
        this.localModel = localModel;
        this.judge = judge;
        this.properties = properties;
    }

    public int available() {
        return properties.population();
    }

    public List<Score> run(int caseCount, IntConsumer done) {
        List<Person> population = Population.generate(properties.population(), properties.seed());
        List<LeakCase> cases = Dataset.of(population).subList(0, Math.min(caseCount, population.size()));
        List<Tally> tallies = Arrays.stream(Strategy.values()).map(Tally::new).toList();
        for (int i = 0; i < cases.size(); i++) {
            LeakCase leakCase = cases.get(i);
            long started = System.nanoTime();
            Inspection inspection = guardian.inspect(leakCase.prompt());
            Duration inspected = Duration.ofNanos(System.nanoTime() - started);
            List<Person> candidates = Candidates.of(leakCase.person(), population, properties.candidates(), properties.seed());
            List<Answered> answers = new ArrayList<>();
            for (Tally tally : tallies) {
                Strategy strategy = tally.strategy();
                Optional<String> outbound = strategy.outbound(leakCase.prompt(), inspection);
                int leaked = outbound.map(text -> Leakage.leaked(text, leakCase.secrets())).orElse(0);
                boolean reidentified = outbound.flatMap(text -> attacker.identify(text, candidates))
                        .filter(leakCase.person().id()::equals)
                        .isPresent();
                tally.exposure(leakCase, leaked, reidentified);
                if (strategy.blocked(inspection)) {
                    tally.grade(Judge.WORST);
                    continue;
                }
                Completion completion = outbound.isPresent()
                        ? cloudModel.complete(outbound.get(), ANSWER_TOKENS)
                        : localModel.complete(leakCase.prompt(), ANSWER_TOKENS);
                tally.answer(strategy.guarded() ? completion.took().plus(inspected) : completion.took(),
                        completion.inputTokens(), completion.outputTokens(),
                        outbound.isPresent() ? properties.cloudPrice().cost(completion.inputTokens(), completion.outputTokens()) : BigDecimal.ZERO);
                answers.add(new Answered(tally, strategy.restored(completion.text(), inspection)));
            }
            grade(leakCase, answers, i);
            done.accept(i + 1);
        }
        return tallies.stream().map(Tally::score).toList();
    }

    public double chance() {
        return 1.0 / properties.candidates();
    }

    // Shuffled per case, so no strategy gains from always being shown first.
    private void grade(LeakCase leakCase, List<Answered> answers, int caseIndex) {
        Collections.shuffle(answers, new SplittableRandom(properties.seed() + caseIndex));
        Map<Integer, Integer> grades = judge.grade(leakCase.prompt(), answers.stream().map(Answered::text).toList());
        grades.forEach((index, grade) -> answers.get(index).tally().grade(grade));
    }

    private record Answered(Tally tally, String text) {
    }
}
