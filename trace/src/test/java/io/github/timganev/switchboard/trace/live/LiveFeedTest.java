package io.github.timganev.switchboard.trace.live;

import io.github.timganev.switchboard.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.thymeleaf.ITemplateEngine;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveFeedTest {

    private final TraceStore store = mock(TraceStore.class);
    private final ITemplateEngine engine = mock(ITemplateEngine.class);
    private final LiveFeed feed = new LiveFeed(store, engine);

    @Test
    void shouldUpdateOnlyARowItHasAlreadyPushed() {
        feed.subscribe(null);
        when(store.find("c-1")).thenReturn(Optional.of(JsonMapper.builder().build().readTree("""
                {"callId": "c-1", "team": "payments", "timestamp": "2026-10-30T16:44:43Z"}
                """)));
        when(engine.process(eq("console"), any(Set.class), any())).thenReturn("<tr></tr>");

        feed.decisionMade("c-1");
        verify(store, never()).find("c-1");

        feed.callCompleted("c-1");
        feed.decisionMade("c-1");
        feed.callCompleted("c-1");

        verify(engine, times(1)).process(eq("console"), any(Set.class),
                argThat(context -> "added".equals(context.getVariable("change"))));
        verify(engine, times(2)).process(eq("console"), any(Set.class),
                argThat(context -> "updated".equals(context.getVariable("change"))));
    }

    @Test
    void shouldNotReadTheTraceWhenNobodyIsWatching() {
        feed.callCompleted("c-1");

        verify(store, never()).find(any());
    }
}
