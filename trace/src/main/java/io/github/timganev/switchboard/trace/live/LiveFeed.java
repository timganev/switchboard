package io.github.timganev.switchboard.trace.live;

import io.github.timganev.switchboard.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes every finished call to the open consoles as an HTML row that htmx swaps into the table.
 * Each push reads the merged trace again, so the row is complete whichever of the two events came last.
 */
@Component
public class LiveFeed {

    private static final Logger log = LoggerFactory.getLogger(LiveFeed.class);
    private static final int REMEMBERED_ROWS = 1000;

    private final TraceStore store;
    private final ITemplateEngine templateEngine;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    // The calls already pushed as rows; an update for any other call has no row to replace.
    private final Set<String> pushed = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED_ROWS;
        }
    });

    public LiveFeed(TraceStore store, ITemplateEngine templateEngine) {
        this.store = store;
        this.templateEngine = templateEngine;
    }

    public SseEmitter subscribe(String team) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, team);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(failure -> subscribers.remove(subscriber));
        try {
            // Sends the headers now, so the browser sees the stream open before the first call.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException exception) {
            subscribers.remove(subscriber);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    // Runs before the graceful shutdown, which would otherwise wait 30 s for every open console.
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        subscribers.forEach(subscriber -> subscriber.emitter().complete());
        subscribers.clear();
    }

    public synchronized void callCompleted(String callId) {
        // A redelivered call replaces its row instead of adding a second one.
        if (push(callId, pushed.contains(callId) ? "updated" : "added")) {
            pushed.add(callId);
        }
    }

    public synchronized void decisionMade(String callId) {
        if (pushed.contains(callId)) {
            push(callId, "updated");
        }
    }

    // The console is a view; a failure here must not send the event to the dead letter topic.
    private boolean push(String callId, String change) {
        if (subscribers.isEmpty()) {
            return false;
        }
        Optional<JsonNode> trace;
        try {
            trace = store.find(callId);
        } catch (RuntimeException exception) {
            log.warn("Could not read trace {} for the console: {}", callId, exception.toString());
            return false;
        }
        if (trace.isEmpty() || !trace.get().hasNonNull("timestamp")) {
            return false;
        }
        CallView call = CallView.of(trace.get());
        Context context = new Context();
        context.setVariable("call", call);
        context.setVariable("change", change);
        String html = templateEngine.process("console", Set.of("change"), context);
        for (Subscriber subscriber : subscribers) {
            if (subscriber.team() != null && !subscriber.team().equals(call.team())) {
                continue;
            }
            try {
                subscriber.emitter().send(SseEmitter.event().name("call").data(html));
            } catch (IOException | IllegalStateException exception) {
                subscribers.remove(subscriber);
            }
        }
        return true;
    }

    private record Subscriber(SseEmitter emitter, String team) {
    }
}
