package io.github.timganev.switchboard.ledger.api;

import io.github.timganev.switchboard.commons.event.BudgetStatusChanged;
import io.github.timganev.switchboard.ledger.BudgetEvents;
import io.github.timganev.switchboard.ledger.Ledger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;

@RestController
@RequestMapping("/v1/budgets")
class BudgetController {

    private final Ledger ledger;
    private final BudgetEvents budgetEvents;
    private final Clock clock;

    BudgetController(Ledger ledger, BudgetEvents budgetEvents, Clock clock) {
        this.ledger = ledger;
        this.budgetEvents = budgetEvents;
        this.clock = clock;
    }

    @GetMapping("/{team}")
    BudgetStatusChanged status(@PathVariable String team) {
        return ledger.status(team, YearMonth.now(clock));
    }

    @PutMapping("/{team}")
    BudgetStatusChanged limit(@PathVariable String team, @RequestBody BudgetLimit limit) {
        BudgetStatusChanged status = ledger.limit(team, limit.monthlyLimitUsd());
        budgetEvents.publish(status);
        return status;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    record BudgetLimit(BigDecimal monthlyLimitUsd) {
    }
}
