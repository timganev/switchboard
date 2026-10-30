package io.github.timganev.switchboard.gateway.budget;

import io.github.timganev.switchboard.commons.event.BudgetState;
import io.github.timganev.switchboard.commons.event.BudgetStatusChanged;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetBoardTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    private final BudgetBoard board = new BudgetBoard(Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void shouldBlockOnlyATeamWhoseBudgetIsSpentThisMonth() {
        board.changed(status("team-a", OCTOBER, BudgetState.BLOCKED));
        board.changed(status("team-b", OCTOBER, BudgetState.WARN));
        board.changed(status("team-c", OCTOBER.minusMonths(1), BudgetState.BLOCKED));

        assertThat(board.isBlocked("team-a")).isTrue();
        assertThat(board.isBlocked("team-b")).isFalse();
        assertThat(board.isBlocked("team-c")).isFalse();
        assertThat(board.isBlocked("unknown")).isFalse();
    }

    @Test
    void shouldFollowTheLatestStatusButIgnoreALateOneFromLastMonth() {
        board.changed(status("team-a", OCTOBER, BudgetState.BLOCKED));
        board.changed(status("team-a", OCTOBER.minusMonths(1), BudgetState.OK));
        assertThat(board.isBlocked("team-a")).isTrue();

        board.changed(status("team-a", OCTOBER, BudgetState.OK));
        assertThat(board.isBlocked("team-a")).isFalse();
    }

    private static BudgetStatusChanged status(String team, YearMonth period, BudgetState state) {
        return new BudgetStatusChanged(team, period, BigDecimal.TEN, null, BigDecimal.TEN, state, Instant.now());
    }
}
