package io.github.timganev.switchboard.leaklab.run;

import io.github.timganev.switchboard.leaklab.strategy.Strategy;

import java.math.BigDecimal;

/**
 * {@code quality} is the judge's mean grade from 1 to 5; {@code cloudCostUsd} prices only the tokens that went to the cloud.
 */
public record Score(Strategy strategy, int cases, int values, int valuesLeaked, double leakRate,
                    int reidentified, double reidentificationRate, int unnamedCases, int unnamedReidentified,
                    double unnamedReidentificationRate, int graded, double quality, int inputTokens, int outputTokens,
                    BigDecimal cloudCostUsd, long meanLatencyMs) {
}
