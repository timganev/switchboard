package io.github.timganev.switchboard.commons;

/**
 * Headers that tie one call together across the gateway, the guardian and the events.
 */
public final class CallHeaders {

    public static final String CALL_ID = "X-Switchboard-Call-Id";
    public static final String SESSION = "X-Switchboard-Session";
    public static final String TEAM = "X-Switchboard-Team";

    private CallHeaders() {
    }
}
