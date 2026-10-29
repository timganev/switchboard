package io.github.timganev.switchboard.commons.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;

public record BudgetStatusChanged(
        String team,
        YearMonth period,
        BigDecimal spentEstimate,
        BigDecimal spentReconciled,
        BigDecimal limit,
        BudgetState state,
        Instant timestamp) {
}
