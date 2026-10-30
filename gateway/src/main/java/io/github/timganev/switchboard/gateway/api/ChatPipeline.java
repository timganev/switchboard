package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Restoration;
import io.github.timganev.switchboard.gateway.backend.Dispatcher;
import io.github.timganev.switchboard.gateway.backend.Locality;
import io.github.timganev.switchboard.gateway.backend.ModelStream;
import io.github.timganev.switchboard.gateway.backend.ReplayModelStream;
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

    ChatPipeline(GuardianClient guardianClient, Dispatcher dispatcher) {
        this.guardianClient = guardianClient;
        this.dispatcher = dispatcher;
    }

    Answer send(ChatRequest request) {
        return send(request, inspect(request));
    }

    Streamed stream(ChatRequest request) {
        Inspection inspection = inspect(request);
        if (inspection.route() == Route.SURROGATE) {
            // A surrogate can be split across chunks, so the answer is restored whole and then replayed as a stream.
            Answer answer = send(request, inspection);
            return new Streamed(inspection, new ReplayModelStream(answer.response()), answer.responseReasons());
        }
        return new Streamed(inspection, dispatcher.openStream(allowedLocalities(inspection.route()), inspection.request()), List.of());
    }

    static Set<Locality> allowedLocalities(Route route) {
        return switch (route) {
            case CLOUD, SURROGATE -> EnumSet.allOf(Locality.class);
            case SKETCH, LOCAL -> EnumSet.of(Locality.LOCAL);
            case BLOCK -> throw new IllegalArgumentException("A blocked request has no allowed instances");
        };
    }

    private Inspection inspect(ChatRequest request) {
        Inspection inspection = guardianClient.inspect(request);
        if (inspection.route() == Route.BLOCK) {
            throw new RequestBlockedException(inspection.reasons());
        }
        return inspection;
    }

    private Answer send(ChatRequest request, Inspection inspection) {
        ChatResponse response = dispatcher.send(allowedLocalities(inspection.route()), inspection.request());
        if (inspection.route() != Route.SURROGATE) {
            return new Answer(inspection, response, List.of());
        }
        Restoration restoration = guardianClient.restore(request, response);
        return new Answer(inspection, restoration.response(), restoration.responseReasons());
    }

    record Answer(Inspection inspection, ChatResponse response, List<SensitivityReason> responseReasons) {
    }

    record Streamed(Inspection inspection, ModelStream stream, List<SensitivityReason> responseReasons) {
    }
}
