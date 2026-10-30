package io.github.timganev.switchboard.ledger;

import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.BudgetStatusChanged;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes the latest status of a team on the compacted topic; the topic itself is the gateway's view of the budgets.
 */
@Component
public class BudgetEvents {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, Object> kafka;

    public BudgetEvents(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    // Waits for the broker, so a failed send makes the listener retry instead of losing the status.
    public void publish(BudgetStatusChanged status) {
        try {
            kafka.send(Topics.BUDGET_STATUS_CHANGED, status.team(), status).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Could not publish the budget status of " + status.team(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing the budget status of " + status.team(), exception);
        }
    }
}
