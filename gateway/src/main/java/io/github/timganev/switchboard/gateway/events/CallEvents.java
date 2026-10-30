package io.github.timganev.switchboard.gateway.events;

import io.github.timganev.switchboard.commons.CallHeaders;
import io.github.timganev.switchboard.commons.ClientFormat;
import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import io.github.timganev.switchboard.commons.kafka.EventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Starts a record for every call and publishes it as llm-call-completed when the call ends, however it ends.
 */
@Component
public class CallEvents {

    static final String DEFAULT_TEAM = "default";
    private static final String ATTRIBUTE = CallRecord.class.getName();
    private static final Usage NO_USAGE = new Usage(0, 0);

    private final EventPublisher publisher;
    private final Clock clock;

    public CallEvents(EventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    // Until teams authenticate, the team is whatever the client names in the header.
    public CallRecord start(HttpServletRequest request, ClientFormat clientFormat, String model) {
        String team = request.getHeader(CallHeaders.TEAM);
        CallRecord call = new CallRecord(request.getHeader(CallHeaders.SESSION),
                team == null || team.isBlank() ? DEFAULT_TEAM : team.strip(), clientFormat, model);
        request.setAttribute(ATTRIBUTE, call);
        return call;
    }

    public void completed(CallRecord call, int httpStatus) {
        if (!call.complete()) {
            return;
        }
        Usage usage = call.usage() == null ? NO_USAGE : call.usage();
        publisher.publish(Topics.LLM_CALL_COMPLETED, call.team(), new LlmCallCompleted(
                call.callId(),
                call.sessionId(),
                call.team(),
                call.clientFormat(),
                call.route(),
                call.backend() == null ? null : call.backend().locality(),
                call.backend() == null ? null : call.backend().name(),
                call.model(),
                usage.inputTokens(),
                usage.outputTokens(),
                0,
                call.elapsedMillis(),
                httpStatus,
                null,
                Instant.now(clock)));
    }

    /**
     * Finishes the call that belongs to this HTTP request, if one was started.
     */
    public void failed(HttpServletRequest request, int httpStatus) {
        if (request.getAttribute(ATTRIBUTE) instanceof CallRecord call) {
            completed(call, httpStatus);
        }
    }
}
