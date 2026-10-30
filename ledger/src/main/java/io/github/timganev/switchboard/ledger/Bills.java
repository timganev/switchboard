package io.github.timganev.switchboard.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Takes in what the provider charged per day and model, days later, and compares it with what the ledger estimated.
 */
@Component
public class Bills {

    private final JdbcClient jdbc;
    private final Clock clock;

    public Bills(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Stores the lines, replacing an earlier import of the same day and model, and returns the teams whose current month changed.
     */
    @Transactional
    public Reconciliation record(List<BillLine> lines) {
        validate(lines);
        OffsetDateTime now = OffsetDateTime.now(clock);
        YearMonth currentMonth = YearMonth.from(now);
        Set<String> teams = new TreeSet<>();
        List<Comparison> comparisons = lines.stream().map(line -> {
            jdbc.sql("""
                            INSERT INTO provider_bill (bill_date, model, cost_usd, imported_at) VALUES (:day, :model, :cost, :now)
                            ON CONFLICT (bill_date, model) DO UPDATE SET cost_usd = EXCLUDED.cost_usd, imported_at = EXCLUDED.imported_at""")
                    .param("day", line.date())
                    .param("model", line.model())
                    .param("cost", line.costUsd())
                    .param("now", now)
                    .update();
            if (YearMonth.from(line.date()).equals(currentMonth)) {
                teams.addAll(cloudCalls(line, "SELECT DISTINCT team").query(String.class).list());
            }
            BigDecimal estimate = cloudCalls(line, "SELECT COALESCE(SUM(cost_usd), 0)").query(BigDecimal.class).single();
            return new Comparison(line.date(), line.model(), estimate, line.costUsd(), line.costUsd().subtract(estimate));
        }).toList();
        return new Reconciliation(comparisons, teams);
    }

    private JdbcClient.StatementSpec cloudCalls(BillLine line, String select) {
        return jdbc.sql(select + " FROM llm_call WHERE locality = 'CLOUD' AND model = :model AND occurred_at >= :from AND occurred_at < :to")
                .param("model", line.model())
                .param("from", line.date().atStartOfDay().atOffset(ZoneOffset.UTC))
                .param("to", line.date().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    private static void validate(List<BillLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A bill needs at least one line");
        }
        Set<String> seen = new HashSet<>();
        for (BillLine line : lines) {
            if (line.date() == null || line.model() == null || line.model().isBlank() || line.costUsd() == null || line.costUsd().signum() < 0) {
                throw new IllegalArgumentException("Every line needs a date, a model and a cost of zero or more");
            }
            if (!seen.add(line.date() + " " + line.model())) {
                throw new IllegalArgumentException("The bill has " + line.model() + " on " + line.date() + " twice");
            }
        }
    }

    public record BillLine(LocalDate date, String model, BigDecimal costUsd) {
    }

    /**
     * A billed amount with no estimate behind it stays visible here but is charged to no team.
     */
    public record Comparison(LocalDate date, String model, BigDecimal estimatedUsd, BigDecimal billedUsd, BigDecimal differenceUsd) {
    }

    public record Reconciliation(List<Comparison> lines, Set<String> teams) {
    }
}
