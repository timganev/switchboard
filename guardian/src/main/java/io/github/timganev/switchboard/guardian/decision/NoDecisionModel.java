package io.github.timganev.switchboard.guardian.decision;

import io.github.timganev.switchboard.commons.event.DecisionModelRef;

public class NoDecisionModel implements DecisionModel {

    @Override
    public Classification classify(String text) {
        return Classification.NOTHING;
    }

    @Override
    public DecisionModelRef ref() {
        return null;
    }
}
