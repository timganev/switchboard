package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.leaklab.strategy.Strategy;

/**
 * How one strategy did: the share of real values that left, and how often the attacker named the right person.
 */
public record Score(Strategy strategy, int cases, int values, int valuesLeaked, double leakRate,
                    int reidentified, double reidentificationRate, int unnamedCases, int unnamedReidentified,
                    double unnamedReidentificationRate) {

    static Score of(Strategy strategy, int cases, int values, int valuesLeaked, int reidentified, int unnamedCases, int unnamedReidentified) {
        return new Score(strategy, cases, values, valuesLeaked, rate(valuesLeaked, values), reidentified, rate(reidentified, cases),
                unnamedCases, unnamedReidentified, rate(unnamedReidentified, unnamedCases));
    }

    private static double rate(int part, int whole) {
        return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 1000.0;
    }
}
