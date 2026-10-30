package io.github.timganev.switchboard.commons.privacy;

import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;

/**
 * Asks the guardian to put the real values back; it rebuilds the surrogates from the original request instead of storing them.
 */
public record RestoreRequest(ChatRequest original, ChatResponse response) {
}
