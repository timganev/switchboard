package io.github.timganev.switchboard.commons.privacy;

import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;

import java.util.List;

/**
 * Asks the guardian to put the real values back; without substitutions it rebuilds them from the original request.
 */
public record RestoreRequest(ChatRequest original, ChatResponse response, List<Substitution> substitutions) {
}
