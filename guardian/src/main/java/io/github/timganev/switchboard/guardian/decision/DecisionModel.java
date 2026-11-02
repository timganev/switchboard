package io.github.timganev.switchboard.guardian.decision;

import io.github.timganev.switchboard.commons.event.DecisionModelRef;

/**
 * The optional model slot: it looks for personal data that patterns cannot find, and can only tighten the route, never loosen it.
 */
public interface DecisionModel {

    Classification classify(String text);

    /**
     * Which model decided, for the trace; null when the slot is off.
     */
    DecisionModelRef ref();
}
