package io.github.timganev.switchboard.trace.live;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One row of the console, read from a trace document.
 */
public record CallView(String callId, String time, String team, String route, String reasons, String where,
                       String model, long inputTokens, long outputTokens, long latencyMs, int status) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    public static CallView of(JsonNode trace) {
        List<String> reasons = new ArrayList<>();
        trace.path("privacy").path("reasons").forEach(reason ->
                reasons.add(reason.path("type").asString() + (reason.path("count").asInt() > 1 ? " ×" + reason.path("count").asInt() : "")));
        String route = text(trace, "route");
        String instance = text(trace, "instance");
        String locality = text(trace, "locality");
        return new CallView(
                text(trace, "callId"),
                trace.hasNonNull("timestamp") ? TIME.format(Instant.parse(trace.get("timestamp").asString())) : "",
                text(trace, "team"),
                route.isEmpty() ? "" : route.toLowerCase(Locale.ROOT),
                String.join(", ", reasons),
                instance.isEmpty() ? "" : instance + " (" + locality.toLowerCase(Locale.ROOT) + ")",
                text(trace, "model"),
                trace.path("inputTokens").asLong(),
                trace.path("outputTokens").asLong(),
                trace.path("latencyMs").asLong(),
                trace.path("httpStatus").asInt());
    }

    private static String text(JsonNode trace, String field) {
        return trace.hasNonNull(field) ? trace.get(field).asString() : "";
    }
}
