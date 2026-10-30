package io.github.timganev.switchboard.gateway.board;

/**
 * One request counted against an instance; closing it more than once has no effect.
 */
public interface Lease extends AutoCloseable {

    String instance();

    @Override
    void close();
}
