package io.github.timganev.switchboard.gateway.backend;

import java.util.Set;

public class NoBackendAvailableException extends RuntimeException {

    public NoBackendAvailableException(Set<Locality> allowed) {
        super("No model instance is configured for " + allowed);
    }
}
