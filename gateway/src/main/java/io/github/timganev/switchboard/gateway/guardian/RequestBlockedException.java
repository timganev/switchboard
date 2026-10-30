package io.github.timganev.switchboard.gateway.guardian;

import io.github.timganev.switchboard.commons.event.SensitivityReason;

import java.util.List;

public class RequestBlockedException extends RuntimeException {

    private final List<SensitivityReason> reasons;

    public RequestBlockedException(List<SensitivityReason> reasons) {
        super("The privacy guardian blocked the request");
        this.reasons = List.copyOf(reasons);
    }

    public List<SensitivityReason> reasons() {
        return reasons;
    }
}
