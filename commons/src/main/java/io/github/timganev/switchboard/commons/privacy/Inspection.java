package io.github.timganev.switchboard.commons.privacy;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.event.SensitivityReason;

import java.util.List;

/**
 * The guardian's verdict; {@code request} is what may leave the gateway, and is null when the route is {@code BLOCK}.
 */
public record Inspection(Route route, List<SensitivityReason> reasons, ChatRequest request) {

    public Inspection {
        reasons = List.copyOf(reasons);
    }
}
