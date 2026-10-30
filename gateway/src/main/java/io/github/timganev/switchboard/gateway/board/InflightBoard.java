package io.github.timganev.switchboard.gateway.board;

import java.util.List;

/**
 * Counts the requests each model instance is answering right now.
 */
public interface InflightBoard {

    /**
     * Picks the least busy candidate and counts one request against it until the lease is closed.
     */
    Lease acquire(List<String> candidates);
}
