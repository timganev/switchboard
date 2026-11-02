package io.github.timganev.switchboard.leaklab;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;

/**
 * {@code candidates} is how many people the attacker chooses from, so a blind guess is right once in that many cases.
 */
@ConfigurationProperties("switchboard.leak-lab")
public record LeakLabProperties(Endpoint guardian, Model attacker, Model judge, Model cloud, Model local, Price cloudPrice,
                                long seed, int population, int candidates) {

    public LeakLabProperties {
        if (candidates < 2 || candidates > population) {
            throw new IllegalArgumentException("switchboard.leak-lab.candidates must be at least 2 and at most the population");
        }
    }

    public record Endpoint(URI url, Duration timeout) {
    }

    public record Model(URI url, String model, Duration timeout, String reasoningEffort) {
    }

    public record Price(BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {

        public BigDecimal cost(int inputTokens, int outputTokens) {
            return inputUsdPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputUsdPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                    .movePointLeft(6);
        }
    }
}
