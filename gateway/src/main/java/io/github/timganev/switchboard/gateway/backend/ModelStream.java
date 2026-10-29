package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.StreamEvent;

import java.io.IOException;

/**
 * An open streamed answer from the model backend; must be closed.
 */
public interface ModelStream extends AutoCloseable {

    void forEach(EventConsumer consumer) throws IOException;

    @Override
    void close();

    @FunctionalInterface
    interface EventConsumer {

        void accept(StreamEvent event) throws IOException;
    }
}
