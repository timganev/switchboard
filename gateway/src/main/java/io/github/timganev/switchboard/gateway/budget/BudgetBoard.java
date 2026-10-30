package io.github.timganev.switchboard.gateway.budget;

import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.event.BudgetState;
import io.github.timganev.switchboard.commons.event.BudgetStatusChanged;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every team's latest budget status, read from the compacted topic into memory; no lookup sits on the request path.
 * Until the topic is read, nobody is blocked: the budget is a cost control, not a privacy one, so it fails open.
 */
@Component
public class BudgetBoard implements ConsumerSeekAware {

    private final Map<String, BudgetStatusChanged> statuses = new ConcurrentHashMap<>();
    private final Clock clock;

    public BudgetBoard(Clock clock) {
        this.clock = clock;
    }

    // A group of its own per replica, so every replica reads every team.
    @KafkaListener(topics = Topics.BUDGET_STATUS_CHANGED, groupId = "gateway-budgets-#{T(java.util.UUID).randomUUID()}")
    public void changed(BudgetStatusChanged status) {
        // A late call from last month must not hide this month's status.
        statuses.merge(status.team(), status, (known, update) -> update.period().isBefore(known.period()) ? known : update);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        callback.seekToBeginning(assignments.keySet());
    }

    public boolean isBlocked(String team) {
        BudgetStatusChanged status = statuses.get(team);
        return status != null && status.state() == BudgetState.BLOCKED && status.period().equals(YearMonth.now(clock));
    }
}
