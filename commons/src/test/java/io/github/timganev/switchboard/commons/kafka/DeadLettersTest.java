package io.github.timganev.switchboard.commons.kafka;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLettersTest {

    @Test
    void shouldExplainAFailureWithTheDeepestMessageAndNameItsRoot() {
        ConnectException connect = new ConnectException();
        connect.initCause(new ClosedChannelException());
        Exception failure = new RuntimeException("Listener failed",
                new IllegalStateException("I/O error on PUT request for \"http://localhost:9200/switchboard-calls\"", connect));

        assertThat(DeadLetters.reason(failure))
                .isEqualTo("IllegalStateException: I/O error on PUT request for \"http://localhost:9200/switchboard-calls\" (ClosedChannelException)");
    }

    @Test
    void shouldKeepOnlyTheFirstLineOfALongMessage() {
        Exception failure = new RuntimeException("failed to deserialize",
                new IllegalArgumentException("Unrecognized token 'not'\n at [Source: REDACTED; line: 1]\n" + "x".repeat(500)));

        assertThat(DeadLetters.reason(failure)).isEqualTo("IllegalArgumentException: Unrecognized token 'not'");
    }
}
