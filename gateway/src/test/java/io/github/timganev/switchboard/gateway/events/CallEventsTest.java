package io.github.timganev.switchboard.gateway.events;

import io.github.timganev.switchboard.commons.ClientFormat;
import io.github.timganev.switchboard.commons.Locality;
import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.Topics;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.commons.event.LlmCallCompleted;
import io.github.timganev.switchboard.commons.kafka.EventPublisher;
import io.github.timganev.switchboard.gateway.backend.Backend;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CallEventsTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private final EventPublisher publisher = mock(EventPublisher.class);
    private final CallEvents callEvents = new CallEvents(publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldPublishWhatTheCallLearnedKeyedByTeam() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Switchboard-Team", " payments ");
        request.addHeader("X-Switchboard-Session", "s-1");
        CallRecord call = callEvents.start(request, ClientFormat.ANTHROPIC, "qwen36");
        call.route(Route.SURROGATE);
        call.answeredBy(new Backend("cloud-1", Locality.CLOUD, "big-model", null));
        call.usage(new Usage(120, 30));

        callEvents.completed(call, 200);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(Topics.LLM_CALL_COMPLETED), eq("payments"), event.capture());
        LlmCallCompleted completed = (LlmCallCompleted) event.getValue();
        assertThat(completed.callId()).isEqualTo(call.callId());
        assertThat(completed.sessionId()).isEqualTo("s-1");
        assertThat(completed.clientFormat()).isEqualTo(ClientFormat.ANTHROPIC);
        assertThat(completed.route()).isEqualTo(Route.SURROGATE);
        assertThat(completed.locality()).isEqualTo(Locality.CLOUD);
        assertThat(completed.instance()).isEqualTo("cloud-1");
        assertThat(completed.model()).isEqualTo("big-model");
        assertThat(completed.inputTokens()).isEqualTo(120);
        assertThat(completed.outputTokens()).isEqualTo(30);
        assertThat(completed.httpStatus()).isEqualTo(200);
        assertThat(completed.timestamp()).isEqualTo(NOW);
    }

    @Test
    void shouldReportAFailedCallOnceWithTheDefaultTeam() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CallRecord call = callEvents.start(request, ClientFormat.OPENAI, "qwen36");

        callEvents.failed(request, 503);
        callEvents.completed(call, 200);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(1)).publish(eq(Topics.LLM_CALL_COMPLETED), eq("default"), event.capture());
        LlmCallCompleted completed = (LlmCallCompleted) event.getValue();
        assertThat(completed.httpStatus()).isEqualTo(503);
        assertThat(completed.route()).isNull();
        assertThat(completed.instance()).isNull();
        assertThat(completed.model()).isEqualTo("qwen36");
        assertThat(completed.inputTokens()).isZero();
    }

    @Test
    void shouldIgnoreAFailureBeforeTheCallStarted() {
        callEvents.failed(new MockHttpServletRequest(), 400);

        verify(publisher, times(0)).publish(any(), any(), any());
    }
}
