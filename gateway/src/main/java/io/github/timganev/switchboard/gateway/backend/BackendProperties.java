package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.Locality;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The OpenAI-compatible model instances the gateway forwards to; each {@code url} is a base URL including {@code /v1}.
 */
@ConfigurationProperties("switchboard.backend")
public record BackendProperties(Duration connectTimeout, Duration readTimeout, List<Instance> instances) {

    public BackendProperties {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("switchboard.backend.instances must list at least one model instance");
        }
        Set<String> names = new HashSet<>();
        for (Instance instance : instances) {
            if (!names.add(instance.name())) {
                throw new IllegalArgumentException("Duplicate model instance name: " + instance.name());
            }
        }
        instances = List.copyOf(instances);
    }

    /**
     * One model instance; {@code model} overrides the client's model name, and a missing locality counts as cloud.
     */
    public record Instance(String name, URI url, String apiKey, String model, Locality locality) {

        public Instance {
            if (name == null || name.isBlank() || url == null) {
                throw new IllegalArgumentException("Every model instance needs a name and a url");
            }
            locality = locality == null ? Locality.CLOUD : locality;
        }
    }
}
