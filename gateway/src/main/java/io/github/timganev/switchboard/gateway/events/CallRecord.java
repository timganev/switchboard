package io.github.timganev.switchboard.gateway.events;

import io.github.timganev.switchboard.commons.ClientFormat;
import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.gateway.backend.Backend;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the gateway learns about one call while it runs; it becomes an llm-call-completed event at the end.
 */
public final class CallRecord {

    private final String callId = UUID.randomUUID().toString();
    private final long startNanos = System.nanoTime();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final String sessionId;
    private final String team;
    private final ClientFormat clientFormat;
    private final String requestedModel;
    private volatile Route route;
    private volatile Backend backend;
    private volatile Usage usage;

    public CallRecord(String sessionId, String team, ClientFormat clientFormat, String requestedModel) {
        this.sessionId = sessionId;
        this.team = team;
        this.clientFormat = clientFormat;
        this.requestedModel = requestedModel;
    }

    public String callId() {
        return callId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String team() {
        return team;
    }

    public ClientFormat clientFormat() {
        return clientFormat;
    }

    public Route route() {
        return route;
    }

    public Backend backend() {
        return backend;
    }

    public Usage usage() {
        return usage;
    }

    public String model() {
        return backend != null && backend.model() != null ? backend.model() : requestedModel;
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public void route(Route route) {
        this.route = route;
    }

    public void answeredBy(Backend backend) {
        this.backend = backend;
    }

    public void usage(Usage usage) {
        this.usage = usage;
    }

    // True only the first time, so a call is reported once even if both the body and an error handler finish it.
    boolean complete() {
        return completed.compareAndSet(false, true);
    }
}
