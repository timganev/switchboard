package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.event.DecisionModelRef;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.guardian.decision.Classification;
import io.github.timganev.switchboard.guardian.decision.DecisionModel;
import io.github.timganev.switchboard.guardian.decision.DecisionModelProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the decision model only when it could still tighten the route; names alone become surrogates, anything else keeps the request in.
 */
@Service
public class DecisionSlot {

    public static final String FAILED_REASON = "DECISION_MODEL_FAILED";

    private final Guardian guardian;
    private final DecisionModel decisionModel;
    private final Route escalation;

    public DecisionSlot(Guardian guardian, DecisionModel decisionModel, DecisionModelProperties properties) {
        this.guardian = guardian;
        this.decisionModel = decisionModel;
        this.escalation = properties.route();
    }

    public Decision decide(ChatRequest request) {
        Inspection inspection = guardian.inspect(request);
        DecisionModelRef model = decisionModel.ref();
        if (model == null || inspection.route().compareTo(escalation) >= 0) {
            return new Decision(inspection, model, false);
        }
        Classification classification = decisionModel.classify(String.join("\n", ChatTexts.of(request)));
        if (!classification.found()) {
            return new Decision(inspection, model, false);
        }
        if (classification.onlyNames()) {
            return new Decision(guardian.inspect(request, classification.names()), model, true);
        }
        List<SensitivityReason> reasons = new ArrayList<>(inspection.reasons());
        classification.categories().stream().sorted().forEach(category -> reasons.add(new SensitivityReason(category, 1)));
        if (classification.failed()) {
            reasons.add(new SensitivityReason(FAILED_REASON, 1));
        }
        Inspection escalated = escalation == Route.BLOCK
                ? new Inspection(Route.BLOCK, reasons, null, null)
                : new Inspection(escalation, reasons, request, null);
        return new Decision(escalated, model, true);
    }

    /**
     * The verdict, which model took part in it, and whether that model tightened it.
     */
    public record Decision(Inspection inspection, DecisionModelRef model, boolean escalatedByModel) {
    }
}
