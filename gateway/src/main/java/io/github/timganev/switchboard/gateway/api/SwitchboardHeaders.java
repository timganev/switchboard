package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.event.SensitivityReason;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Tells the client which route its request took and why, without any of the sensitive values.
 */
final class SwitchboardHeaders {

    static final String ROUTE = "X-Switchboard-Route";
    static final String RESPONSE_FINDINGS = "X-Switchboard-Response-Findings";

    private SwitchboardHeaders() {
    }

    static String route(Route route, List<SensitivityReason> reasons) {
        String value = route.name().toLowerCase(Locale.ROOT);
        return reasons.isEmpty() ? value : value + "; reason=" + types(reasons);
    }

    static String types(List<SensitivityReason> reasons) {
        return reasons.stream().map(SensitivityReason::type).collect(Collectors.joining(","));
    }
}
