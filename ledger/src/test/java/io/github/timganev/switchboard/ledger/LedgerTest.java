package io.github.timganev.switchboard.ledger;

import io.github.timganev.switchboard.commons.event.BudgetState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerTest {

    @ParameterizedTest
    @CsvSource(nullValues = "none", value = {
            "5, none, OK",
            "0, 10, OK",
            "7.99, 10, OK",
            "8, 10, WARN",
            "9.99, 10, WARN",
            "10, 10, BLOCKED",
            "12, 10, BLOCKED",
            "0, 0, BLOCKED"
    })
    void shouldTurnWarnAtEightyPercentAndBlockedAtTheLimit(BigDecimal spent, BigDecimal limit, BudgetState state) {
        assertThat(Ledger.state(spent, limit, new BigDecimal("0.8"))).isEqualTo(state);
    }
}
