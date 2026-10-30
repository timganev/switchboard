package io.github.timganev.switchboard.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Prices a call with the price that was valid when it happened; a model without its own price uses the fallback row.
 */
@Component
public class PriceBook {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final JdbcClient jdbc;

    public PriceBook(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal cost(String model, long inputTokens, long outputTokens, Instant at) {
        Price price = jdbc.sql("""
                        SELECT input_usd_per_mtok, output_usd_per_mtok FROM model_price
                        WHERE model IN (:model, '*') AND valid_from <= :at
                        ORDER BY CASE WHEN model = '*' THEN 1 ELSE 0 END, valid_from DESC
                        LIMIT 1""")
                .param("model", model == null ? "*" : model)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((row, number) -> new Price(row.getBigDecimal(1), row.getBigDecimal(2)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("No price is valid at " + at));
        return price.input().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.output().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private record Price(BigDecimal input, BigDecimal output) {
    }
}
