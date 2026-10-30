package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.gateway.board.InflightBoard;
import io.github.timganev.switchboard.gateway.board.Lease;
import io.github.timganev.switchboard.gateway.board.LocalInflightBoard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatcherTest {

    private static final ChatRequest REQUEST = new ChatRequest("qwen36", null,
            List.of(new ChatMessage(Role.USER, List.of(new TextPart("Hi")))), null, null, null);
    private static final ChatResponse ANSWER = new ChatResponse("r1", "qwen36",
            List.of(new TextPart("Hello")), StopReason.END_TURN, new Usage(1, 1));

    private final Backend local1 = new Backend("local-1", Locality.LOCAL, null, null);
    private final Backend local2 = new Backend("local-2", Locality.LOCAL, null, null);
    private final Backend cloud = new Backend("cloud", Locality.CLOUD, null, null);
    private final ModelClient modelClient = mock(ModelClient.class);
    private RecordingBoard board;
    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        board = new RecordingBoard();
        dispatcher = new Dispatcher(List.of(local1, local2, cloud), modelClient, board);
    }

    @Test
    void shouldOfferOnlyLocalInstancesForALocalRequest() {
        when(modelClient.send(any(), eq(REQUEST))).thenReturn(ANSWER);

        dispatcher.send(EnumSet.of(Locality.LOCAL), REQUEST);

        assertThat(board.offered).containsExactly(List.of("local-1", "local-2"));
    }

    @Test
    void shouldTryTheNextInstanceWhenOneIsBusy() {
        when(modelClient.send(local1, REQUEST)).thenThrow(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "busy", null, null, null));
        when(modelClient.send(local2, REQUEST)).thenReturn(ANSWER);

        assertThat(dispatcher.send(EnumSet.of(Locality.LOCAL), REQUEST)).isEqualTo(ANSWER);
        assertThat(board.offered).containsExactly(List.of("local-1", "local-2"), List.of("local-2"));
        assertThat(board.delegate.count("local-1")).isZero();
        assertThat(board.delegate.count("local-2")).isZero();
    }

    @Test
    void shouldFailRatherThanLeaveTheAllowedSetWhenAllLocalInstancesAreDown() {
        when(modelClient.send(any(), eq(REQUEST))).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> dispatcher.send(EnumSet.of(Locality.LOCAL), REQUEST))
                .isInstanceOf(ResourceAccessException.class);
        verify(modelClient, never()).send(cloud, REQUEST);
    }

    @Test
    void shouldNotRetryARejectedRequest() {
        when(modelClient.send(local1, REQUEST)).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad", null, null, null));

        assertThatThrownBy(() -> dispatcher.send(EnumSet.of(Locality.LOCAL), REQUEST))
                .isInstanceOf(HttpClientErrorException.class);
        verify(modelClient, never()).send(local2, REQUEST);
    }

    @Test
    void shouldReportWhenNoInstanceIsConfiguredForTheRoute() {
        Dispatcher cloudOnly = new Dispatcher(List.of(cloud), modelClient, board);

        assertThatThrownBy(() -> cloudOnly.send(EnumSet.of(Locality.LOCAL), REQUEST))
                .isInstanceOf(NoBackendAvailableException.class)
                .hasMessage("No model instance is configured for [LOCAL]");
    }

    @Test
    void shouldHoldTheLeaseUntilTheStreamIsClosed() {
        when(modelClient.openStream(any(), eq(REQUEST))).thenReturn(mock(ModelStream.class));

        ModelStream stream = dispatcher.openStream(EnumSet.of(Locality.LOCAL), REQUEST);
        assertThat(board.delegate.count("local-1")).isEqualTo(1);

        stream.close();
        assertThat(board.delegate.count("local-1")).isZero();
    }

    private static final class RecordingBoard implements InflightBoard {

        private final LocalInflightBoard delegate = new LocalInflightBoard();
        private final List<List<String>> offered = new ArrayList<>();

        @Override
        public Lease acquire(List<String> candidates) {
            offered.add(candidates);
            return delegate.acquire(candidates);
        }
    }
}
