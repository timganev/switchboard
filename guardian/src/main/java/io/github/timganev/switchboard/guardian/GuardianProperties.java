package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The surrogate secret, and the route each sensitive type sends a request to; unlisted types get a surrogate.
 */
@ConfigurationProperties("switchboard.guardian")
public record GuardianProperties(String secret, Map<SensitiveType, Route> policy) {

    private static final int MIN_SECRET_LENGTH = 32;

    public GuardianProperties {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("switchboard.guardian.secret must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        Map<SensitiveType, Route> merged = new EnumMap<>(SensitiveType.class);
        for (SensitiveType type : SensitiveType.values()) {
            merged.put(type, Route.SURROGATE);
        }
        merged.put(SensitiveType.PRIVATE_KEY, Route.BLOCK);
        if (policy != null) {
            merged.putAll(policy);
        }
        if (merged.containsValue(Route.SKETCH)) {
            throw new IllegalArgumentException("The SKETCH route is not supported yet");
        }
        if (!Set.of(Route.LOCAL, Route.BLOCK).contains(merged.get(SensitiveType.PRIVATE_KEY))) {
            throw new IllegalArgumentException("A private key may only route to LOCAL or BLOCK");
        }
        policy = Map.copyOf(merged);
    }
}
