package io.github.timganev.switchboard.guardian.decision;

import io.github.timganev.switchboard.commons.Route;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * There is no cloud option: asking a cloud model whether a prompt is sensitive would already send the prompt out.
 */
@ConfigurationProperties("switchboard.guardian.decision-model")
public record DecisionModelProperties(Type type, URI url, String model, Duration timeout, String reasoningEffort, Route route) {

    public enum Type {
        NONE,
        LOCAL
    }

    public DecisionModelProperties {
        type = type == null ? Type.NONE : type;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        route = route == null ? Route.LOCAL : route;
        // The model names categories, not values that could be replaced, so a finding can only keep the request in or stop it.
        if (route != Route.LOCAL && route != Route.BLOCK) {
            throw new IllegalArgumentException("switchboard.guardian.decision-model.route may only be LOCAL or BLOCK");
        }
        if (type == Type.LOCAL && (url == null || model == null)) {
            throw new IllegalArgumentException("A local decision model needs switchboard.guardian.decision-model.url and model");
        }
    }
}
