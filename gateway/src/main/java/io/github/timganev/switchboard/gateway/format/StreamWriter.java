package io.github.timganev.switchboard.gateway.format;

import io.github.timganev.switchboard.commons.chat.StreamEvent;

import java.io.IOException;

/**
 * Writes stream events to the client as server-sent events in the client's API format.
 */
public interface StreamWriter {

    void write(StreamEvent event) throws IOException;

    void fail(String message) throws IOException;
}
