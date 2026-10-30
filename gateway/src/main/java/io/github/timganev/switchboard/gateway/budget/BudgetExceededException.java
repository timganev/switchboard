package io.github.timganev.switchboard.gateway.budget;

public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String team) {
        super("The monthly budget of team " + team + " is spent and no local model is configured");
    }
}
