package io.github.timganev.switchboard.leaklab;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * {@code candidates} is how many people the attacker chooses from, so a blind guess is right once in that many cases.
 */
@ConfigurationProperties("switchboard.leak-lab")
public record LeakLabProperties(Endpoint guardian, Attacker attacker, long seed, int population, int candidates) {

    public LeakLabProperties {
        if (candidates < 2 || candidates > population) {
            throw new IllegalArgumentException("switchboard.leak-lab.candidates must be at least 2 and at most the population");
        }
    }

    public record Endpoint(URI url, Duration timeout) {
    }

    public record Attacker(URI url, String model, Duration timeout) {
    }
}
