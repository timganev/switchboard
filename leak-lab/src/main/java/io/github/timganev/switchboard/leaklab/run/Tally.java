package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.leaklab.dataset.LeakCase;
import io.github.timganev.switchboard.leaklab.strategy.Strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Adds up one strategy's trials into its score.
 */
final class Tally {

    private final Strategy strategy;
    private int cases;
    private int values;
    private int valuesLeaked;
    private int reidentified;
    private int unnamedCases;
    private int unnamedReidentified;
    private int graded;
    private int gradeSum;
    private int answered;
    private Duration latency = Duration.ZERO;
    private int inputTokens;
    private int outputTokens;
    private BigDecimal cloudCost = BigDecimal.ZERO;

    Tally(Strategy strategy) {
        this.strategy = strategy;
    }

    Strategy strategy() {
        return strategy;
    }

    void exposure(LeakCase leakCase, int leaked, boolean found) {
        cases++;
        values += leakCase.secrets().size();
        valuesLeaked += leaked;
        reidentified += found ? 1 : 0;
        if (!leakCase.named()) {
            unnamedCases++;
            unnamedReidentified += found ? 1 : 0;
        }
    }

    void answer(Duration took, int input, int output, BigDecimal cost) {
        answered++;
        latency = latency.plus(took);
        inputTokens += input;
        outputTokens += output;
        cloudCost = cloudCost.add(cost);
    }

    void grade(int grade) {
        graded++;
        gradeSum += grade;
    }

    Score score() {
        return new Score(strategy, cases, values, valuesLeaked, rate(valuesLeaked, values), reidentified, rate(reidentified, cases),
                unnamedCases, unnamedReidentified, rate(unnamedReidentified, unnamedCases), graded,
                graded == 0 ? 0 : Math.round(100.0 * gradeSum / graded) / 100.0, inputTokens, outputTokens,
                cloudCost.setScale(6, RoundingMode.HALF_UP), answered == 0 ? 0 : latency.toMillis() / answered);
    }

    private static double rate(int part, int whole) {
        return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 1000.0;
    }
}
