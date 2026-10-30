package io.github.timganev.switchboard.commons.privacy;

import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.event.SensitivityReason;

import java.util.List;

/**
 * The answer with real values back in place, plus sensitive data the model produced on its own.
 */
public record Restoration(ChatResponse response, List<SensitivityReason> responseReasons) {

    public Restoration {
        responseReasons = List.copyOf(responseReasons);
    }
}
