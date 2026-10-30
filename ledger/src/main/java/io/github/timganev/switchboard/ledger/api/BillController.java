package io.github.timganev.switchboard.ledger.api;

import io.github.timganev.switchboard.ledger.Bills;
import io.github.timganev.switchboard.ledger.BudgetEvents;
import io.github.timganev.switchboard.ledger.Ledger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/v1/bills")
class BillController {

    private final Bills bills;
    private final Ledger ledger;
    private final BudgetEvents budgetEvents;
    private final Clock clock;

    BillController(Bills bills, Ledger ledger, BudgetEvents budgetEvents, Clock clock) {
        this.bills = bills;
        this.ledger = ledger;
        this.budgetEvents = budgetEvents;
        this.clock = clock;
    }

    // Only the current month is republished: the topic keeps one status per team, and an older month would replace it.
    @PostMapping
    List<Bills.Comparison> record(@RequestBody Bill bill) {
        Bills.Reconciliation reconciliation = bills.record(bill.lines());
        YearMonth currentMonth = YearMonth.now(clock);
        reconciliation.teams().forEach(team -> budgetEvents.publish(ledger.status(team, currentMonth)));
        return reconciliation.lines();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    record Bill(List<Bills.BillLine> lines) {
    }
}
