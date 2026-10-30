package io.github.timganev.switchboard.ledger;

import io.github.timganev.switchboard.commons.Locality;
import io.github.timganev.switchboard.commons.event.BudgetState;
import io.github.timganev.switchboard.commons.event.BudgetStatusChanged;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * The system of record for money: every call with its cost, and each team's monthly limit.
 */
@Service
public class Ledger {

    private final JdbcClient jdbc;
    private final PriceBook priceBook;
    private final LedgerProperties properties;
    private final Clock clock;

    public Ledger(JdbcClient jdbc, PriceBook priceBook, LedgerProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.priceBook = priceBook;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records a call once, however often it is delivered, and returns its team's budget status.
     */
    @Transactional
    public BudgetStatusChanged record(LlmCallCompleted call) {
        // Local models cost nothing; a call that never reached a model has no locality.
        BigDecimal cost = call.locality() == Locality.CLOUD
                ? priceBook.cost(call.model(), call.inputTokens(), call.outputTokens(), call.timestamp())
                : BigDecimal.ZERO;
        jdbc.sql("""
                        INSERT INTO llm_call (call_id, team, model, locality, input_tokens, output_tokens, cost_usd, occurred_at)
                        VALUES (:callId, :team, :model, :locality, :inputTokens, :outputTokens, :cost, :occurredAt)
                        ON CONFLICT (call_id) DO NOTHING""")
                .param("callId", call.callId())
                .param("team", call.team())
                .param("model", call.model())
                .param("locality", call.locality() == null ? null : call.locality().name())
                .param("inputTokens", call.inputTokens())
                .param("outputTokens", call.outputTokens())
                .param("cost", cost)
                .param("occurredAt", OffsetDateTime.ofInstant(call.timestamp(), ZoneOffset.UTC))
                .update();
        return status(call.team(), YearMonth.from(call.timestamp().atOffset(ZoneOffset.UTC)));
    }

    @Transactional
    public BudgetStatusChanged limit(String team, BigDecimal monthlyLimitUsd) {
        if (monthlyLimitUsd == null || monthlyLimitUsd.signum() < 0) {
            throw new IllegalArgumentException("The monthly limit must be zero or more");
        }
        jdbc.sql("""
                        INSERT INTO team_budget (team, monthly_limit_usd) VALUES (:team, :limit)
                        ON CONFLICT (team) DO UPDATE SET monthly_limit_usd = EXCLUDED.monthly_limit_usd""")
                .param("team", team)
                .param("limit", monthlyLimitUsd)
                .update();
        return status(team, YearMonth.now(clock));
    }

    /**
     * The estimate, and the estimate corrected by the bills so far: a billed day and model is shared out by each team's estimate.
     */
    @Transactional(readOnly = true)
    public BudgetStatusChanged status(String team, YearMonth period) {
        Spent spent = jdbc.sql("""
                        WITH estimate AS (
                            SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, model, SUM(cost_usd) AS cost_usd
                            FROM llm_call
                            WHERE locality = 'CLOUD' AND occurred_at >= :from AND occurred_at < :to
                            GROUP BY 1, 2)
                        SELECT COALESCE(SUM(c.cost_usd), 0),
                               COALESCE(ROUND(SUM(CASE WHEN b.cost_usd IS NULL OR e.cost_usd = 0 THEN c.cost_usd
                                                       ELSE c.cost_usd * b.cost_usd / e.cost_usd END), 6), 0)
                        FROM llm_call c
                        LEFT JOIN provider_bill b ON c.locality = 'CLOUD'
                             AND b.bill_date = (c.occurred_at AT TIME ZONE 'UTC')::date AND b.model = c.model
                        LEFT JOIN estimate e ON e.day = b.bill_date AND e.model = b.model
                        WHERE c.team = :team AND c.occurred_at >= :from AND c.occurred_at < :to""")
                .param("team", team)
                .param("from", period.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                .param("to", period.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                .query((row, number) -> new Spent(row.getBigDecimal(1), row.getBigDecimal(2)))
                .single();
        BigDecimal limit = jdbc.sql("SELECT monthly_limit_usd FROM team_budget WHERE team = :team")
                .param("team", team)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
        // The corrected figure decides the state: it is the best known spend.
        return new BudgetStatusChanged(team, period, spent.estimate(), spent.reconciled(), limit,
                state(spent.reconciled(), limit, properties.warnAt()), Instant.now(clock));
    }

    static BudgetState state(BigDecimal spent, BigDecimal limit, BigDecimal warnAt) {
        if (limit == null) {
            return BudgetState.OK;
        }
        if (spent.compareTo(limit) >= 0) {
            return BudgetState.BLOCKED;
        }
        return spent.compareTo(limit.multiply(warnAt)) >= 0 ? BudgetState.WARN : BudgetState.OK;
    }

    private record Spent(BigDecimal estimate, BigDecimal reconciled) {
    }
}
