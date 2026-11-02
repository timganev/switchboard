package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.leaklab.LeakLabProperties;
import io.github.timganev.switchboard.leaklab.attack.Attacker;
import io.github.timganev.switchboard.leaklab.dataset.Candidates;
import io.github.timganev.switchboard.leaklab.dataset.Dataset;
import io.github.timganev.switchboard.leaklab.dataset.LeakCase;
import io.github.timganev.switchboard.leaklab.dataset.Person;
import io.github.timganev.switchboard.leaklab.dataset.Population;
import io.github.timganev.switchboard.leaklab.strategy.Strategy;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * Runs every case through every strategy and attacks what each one let out.
 */
@Service
public class LeakLab {

    private final GuardianClient guardian;
    private final Attacker attacker;
    private final LeakLabProperties properties;

    LeakLab(GuardianClient guardian, Attacker attacker, LeakLabProperties properties) {
        this.guardian = guardian;
        this.attacker = attacker;
        this.properties = properties;
    }

    public int available() {
        return properties.population();
    }

    public List<Score> run(int caseCount, IntConsumer done) {
        List<Person> population = Population.generate(properties.population(), properties.seed());
        List<LeakCase> cases = Dataset.of(population).subList(0, Math.min(caseCount, population.size()));
        Tally[] tallies = Arrays.stream(Strategy.values()).map(Tally::new).toArray(Tally[]::new);
        for (int i = 0; i < cases.size(); i++) {
            LeakCase leakCase = cases.get(i);
            Inspection inspection = guardian.inspect(leakCase.prompt());
            List<Person> candidates = Candidates.of(leakCase.person(), population, properties.candidates(), properties.seed());
            for (Tally tally : tallies) {
                Optional<String> outbound = tally.strategy.outbound(leakCase.prompt(), inspection);
                int leaked = outbound.map(text -> Leakage.leaked(text, leakCase.secrets())).orElse(0);
                boolean reidentified = outbound.flatMap(text -> attacker.identify(text, candidates))
                        .filter(leakCase.person().id()::equals)
                        .isPresent();
                tally.add(leakCase, leaked, reidentified);
            }
            done.accept(i + 1);
        }
        return Arrays.stream(tallies).map(Tally::score).toList();
    }

    public double chance() {
        return 1.0 / properties.candidates();
    }

    private static final class Tally {

        private final Strategy strategy;
        private int cases;
        private int values;
        private int valuesLeaked;
        private int reidentified;
        private int unnamedCases;
        private int unnamedReidentified;

        private Tally(Strategy strategy) {
            this.strategy = strategy;
        }

        private void add(LeakCase leakCase, int leaked, boolean found) {
            cases++;
            values += leakCase.secrets().size();
            valuesLeaked += leaked;
            reidentified += found ? 1 : 0;
            if (!leakCase.named()) {
                unnamedCases++;
                unnamedReidentified += found ? 1 : 0;
            }
        }

        private Score score() {
            return Score.of(strategy, cases, values, valuesLeaked, reidentified, unnamedCases, unnamedReidentified);
        }
    }
}
