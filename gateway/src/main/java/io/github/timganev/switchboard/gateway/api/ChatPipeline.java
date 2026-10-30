package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.Locality;
import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Restoration;
import io.github.timganev.switchboard.commons.privacy.SurrogateRestorer;
import io.github.timganev.switchboard.gateway.backend.Dispatched;
import io.github.timganev.switchboard.gateway.backend.Dispatcher;
import io.github.timganev.switchboard.gateway.backend.ModelStream;
import io.github.timganev.switchboard.gateway.backend.RestoringModelStream;
import io.github.timganev.switchboard.gateway.budget.BudgetBoard;
import io.github.timganev.switchboard.gateway.budget.BudgetExceededException;
import io.github.timganev.switchboard.gateway.events.CallRecord;
import io.github.timganev.switchboard.gateway.guardian.GuardianClient;
import io.github.timganev.switchboard.gateway.guardian.RequestBlockedException;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The hot path: the guardian's verdict, then the least busy instance the verdict allows, then the real values back.
 */
@Component
class ChatPipeline {

    private final GuardianClient guardianClient;
    private final Dispatcher dispatcher;
    private final BudgetBoard budgetBoard;

    ChatPipeline(GuardianClient guardianClient, Dispatcher dispatcher, BudgetBoard budgetBoard) {
        this.guardianClient = guardianClient;
        this.dispatcher = dispatcher;
        this.budgetBoard = budgetBoard;
    }

    Answer send(CallRecord call, ChatRequest request) {
        Inspection inspection = inspect(call, request);
        Dispatched<ChatResponse> dispatched = dispatcher.send(allowed(call, inspection.route()), inspection.request());
        call.answeredBy(dispatched.backend());
        call.usage(dispatched.result().usage());
        if (inspection.route() != Route.SURROGATE) {
            return new Answer(inspection, dispatched.result(), List.of());
        }
        Restoration restoration = guardianClient.restore(request, dispatched.result());
        return new Answer(inspection, restoration.response(), restoration.responseReasons());
    }

    Streamed stream(CallRecord call, ChatRequest request) {
        Inspection inspection = inspect(call, request);
        Dispatched<ModelStream> dispatched = dispatcher.openStream(allowed(call, inspection.route()), inspection.request());
        call.answeredBy(dispatched.backend());
        ModelStream stream = dispatched.result();
        if (inspection.route() == Route.SURROGATE) {
            stream = new RestoringModelStream(stream, new SurrogateRestorer(inspection.substitutions()));
        }
        return new Streamed(inspection, stream);
    }

    static Set<Locality> allowedLocalities(Route route) {
        return switch (route) {
            case CLOUD, SURROGATE -> EnumSet.allOf(Locality.class);
            case SKETCH, LOCAL -> EnumSet.of(Locality.LOCAL);
            case BLOCK -> throw new IllegalArgumentException("A blocked request has no allowed instances");
        };
    }

    // A team over its budget may still use local models, which cost nothing.
    private Set<Locality> allowed(CallRecord call, Route route) {
        Set<Locality> allowed = allowedLocalities(route);
        if (budgetBoard.isBlocked(call.team())) {
            allowed.retainAll(EnumSet.of(Locality.LOCAL));
            call.budgetSpent();
            if (!dispatcher.serves(allowed)) {
                throw new BudgetExceededException(call.team());
            }
        }
        return allowed;
    }

    private Inspection inspect(CallRecord call, ChatRequest request) {
        Inspection inspection = guardianClient.inspect(request, call.callId(), call.sessionId());
        call.route(inspection.route());
        if (inspection.route() == Route.BLOCK) {
            throw new RequestBlockedException(inspection.reasons());
        }
        return inspection;
    }

    record Answer(Inspection inspection, ChatResponse response, List<SensitivityReason> responseReasons) {
    }

    record Streamed(Inspection inspection, ModelStream stream) {
    }
}
