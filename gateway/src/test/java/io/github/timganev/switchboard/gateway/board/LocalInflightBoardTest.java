package io.github.timganev.switchboard.gateway.board;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalInflightBoardTest {

    private final LocalInflightBoard board = new LocalInflightBoard();

    @Test
    void shouldPickTheLeastBusyInstance() {
        Lease first = board.acquire(List.of("a", "b"));
        Lease second = board.acquire(List.of("a", "b"));

        assertThat(first.instance()).isEqualTo("a");
        assertThat(second.instance()).isEqualTo("b");
    }

    @Test
    void shouldCountALeaseOnlyOnceWhenClosedTwice() {
        board.acquire(List.of("a"));
        Lease lease = board.acquire(List.of("a"));

        lease.close();
        lease.close();

        assertThat(board.count("a")).isEqualTo(1);
    }
}
