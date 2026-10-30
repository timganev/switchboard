package io.github.timganev.switchboard.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * {@code warnAt} is the share of the monthly limit after which a team's state turns WARN.
 */
@ConfigurationProperties("switchboard.ledger")
public record LedgerProperties(BigDecimal warnAt) {

    public LedgerProperties {
        if (warnAt == null || warnAt.signum() <= 0 || warnAt.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("switchboard.ledger.warn-at must be above 0 and at most 1");
        }
    }
}
