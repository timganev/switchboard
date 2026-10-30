package io.github.timganev.switchboard.ledger;

import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Prices every completed call and publishes its team's status after each one, so a lost status heals on the next call.
 */
@Component
class CallListener {

    private final Ledger ledger;
    private final BudgetEvents budgetEvents;

    CallListener(Ledger ledger, BudgetEvents budgetEvents) {
        this.ledger = ledger;
        this.budgetEvents = budgetEvents;
    }

    @KafkaListener(topics = {Topics.LLM_CALL_COMPLETED, LedgerConfiguration.REDRIVE_TOPIC})
    void completed(LlmCallCompleted call) {
        budgetEvents.publish(ledger.record(call));
    }
}
