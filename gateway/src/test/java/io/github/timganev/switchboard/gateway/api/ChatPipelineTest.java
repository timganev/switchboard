package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.ClientFormat;
import io.github.timganev.switchboard.commons.Locality;
import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.Role;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Restoration;
import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import io.github.timganev.switchboard.commons.privacy.Substitution;
import io.github.timganev.switchboard.gateway.backend.Backend;
import io.github.timganev.switchboard.gateway.backend.Dispatched;
import io.github.timganev.switchboard.gateway.backend.Dispatcher;
import io.github.timganev.switchboard.gateway.backend.ModelStream;
import io.github.timganev.switchboard.gateway.budget.BudgetBoard;
import io.github.timganev.switchboard.gateway.budget.BudgetExceededException;
import io.github.timganev.switchboard.gateway.events.CallRecord;
import io.github.timganev.switchboard.gateway.guardian.GuardianClient;
import io.github.timganev.switchboard.gateway.guardian.RequestBlockedException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPipelineTest {

    private static final ChatRequest ORIGINAL = request("Summarise the contract of EGN 7501020018");
    private static final ChatRequest SURROGATED = request("Summarise the contract of EGN 8807143345");
    private static final List<SensitivityReason> EGN = List.of(new SensitivityReason("EGN", 1));
    private static final Backend LOCAL_BACKEND = new Backend("local-1", Locality.LOCAL, null, null);
    private static final Backend CLOUD_BACKEND = new Backend("cloud-1", Locality.CLOUD, "big-model", null);
    private static final List<Substitution> SUBSTITUTIONS = List.of(new Substitution(SensitiveType.EGN, "7501020018", "8807143345"));

    private final GuardianClient guardianClient = mock(GuardianClient.class);
    private final Dispatcher dispatcher = mock(Dispatcher.class);
    private final BudgetBoard budgetBoard = mock(BudgetBoard.class);
    private final ChatPipeline pipeline = new ChatPipeline(guardianClient, dispatcher, budgetBoard);
    private final CallRecord call = new CallRecord(null, "team-a", ClientFormat.OPENAI, "qwen36");

    @Test
    void shouldSendOnlySurrogatesOutAndRestoreTheAnswer() {
        when(guardianClient.inspect(ORIGINAL, call.callId(), null)).thenReturn(new Inspection(Route.SURROGATE, EGN, SURROGATED, SUBSTITUTIONS));
        when(dispatcher.send(EnumSet.allOf(Locality.class), SURROGATED)).thenReturn(new Dispatched<>(CLOUD_BACKEND, answer("About 8807143345")));
        when(guardianClient.restore(ORIGINAL, answer("About 8807143345")))
                .thenReturn(new Restoration(answer("About 7501020018"), List.of()));

        ChatPipeline.Answer answer = pipeline.send(call, ORIGINAL);

        assertThat(answer.response()).isEqualTo(answer("About 7501020018"));
        assertThat(answer.inspection().route()).isEqualTo(Route.SURROGATE);
        assertThat(call.route()).isEqualTo(Route.SURROGATE);
        assertThat(call.backend()).isEqualTo(CLOUD_BACKEND);
        assertThat(call.model()).isEqualTo("big-model");
        assertThat(call.usage()).isEqualTo(new Usage(1, 1));
    }

    @Test
    void shouldKeepALocalRequestOnLocalInstancesWithoutRestoring() {
        when(guardianClient.inspect(ORIGINAL, call.callId(), null)).thenReturn(new Inspection(Route.LOCAL, EGN, ORIGINAL, null));
        when(dispatcher.send(EnumSet.of(Locality.LOCAL), ORIGINAL)).thenReturn(new Dispatched<>(LOCAL_BACKEND, answer("Done")));

        pipeline.send(call, ORIGINAL);

        verify(guardianClient, never()).restore(any(), any());
    }

    @Test
    void shouldSendATeamOverItsBudgetToLocalInstancesOnly() {
        when(budgetBoard.isBlocked("team-a")).thenReturn(true);
        when(dispatcher.serves(EnumSet.of(Locality.LOCAL))).thenReturn(true);
        when(guardianClient.inspect(ORIGINAL, call.callId(), null)).thenReturn(new Inspection(Route.CLOUD, List.of(), ORIGINAL, null));
        when(dispatcher.send(EnumSet.of(Locality.LOCAL), ORIGINAL)).thenReturn(new Dispatched<>(LOCAL_BACKEND, answer("Done")));

        pipeline.send(call, ORIGINAL);

        assertThat(call.isBudgetSpent()).isTrue();
        assertThat(call.backend()).isEqualTo(LOCAL_BACKEND);
    }

    @Test
    void shouldRefuseATeamOverItsBudgetWhenNoLocalInstanceIsConfigured() {
        when(budgetBoard.isBlocked("team-a")).thenReturn(true);
        when(guardianClient.inspect(ORIGINAL, call.callId(), null)).thenReturn(new Inspection(Route.CLOUD, List.of(), ORIGINAL, null));

        assertThatThrownBy(() -> pipeline.send(call, ORIGINAL))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessage("The monthly budget of team team-a is spent and no local model is configured");
        verify(dispatcher, never()).send(any(), any());
    }

    @Test
    void shouldNotSendABlockedRequestAnywhere() {
        when(guardianClient.inspect(ORIGINAL, call.callId(), null)).thenReturn(
                new Inspection(Route.BLOCK, List.of(new SensitivityReason("PRIVATE_KEY", 1)), null, null));

        assertThatThrownBy(() -> pipeline.send(call, ORIGINAL)).isInstanceOf(RequestBlockedException.class);
        assertThat(call.route()).isEqualTo(Route.BLOCK);
        verify(dispatcher, never()).send(any(), any());
    }

    @Test
    void shouldRestoreAStreamedAnswerAsItArrivesEvenWhenASurrogateIsSplit() throws Exception {
        when(guardianClient.inspect(ORIGINAL, call.callId(), null)).thenReturn(new Inspection(Route.SURROGATE, EGN, SURROGATED, SUBSTITUTIONS));
        when(dispatcher.openStream(EnumSet.allOf(Locality.class), SURROGATED)).thenReturn(new Dispatched<>(CLOUD_BACKEND, streamOf(
                new StreamEvent.MessageStarted("r1", "qwen36"),
                new StreamEvent.TextDelta("About 8807"),
                new StreamEvent.TextDelta("14-3345, fine."),
                new StreamEvent.MessageFinished(StopReason.END_TURN, new Usage(1, 1)))));
        List<StreamEvent> events = new ArrayList<>();

        pipeline.stream(call, ORIGINAL).stream().forEach(events::add);

        String text = events.stream()
                .filter(StreamEvent.TextDelta.class::isInstance)
                .map(event -> ((StreamEvent.TextDelta) event).text())
                .collect(Collectors.joining());
        assertThat(text).isEqualTo("About 750102-0018, fine.");
        assertThat(events.getLast()).isEqualTo(new StreamEvent.MessageFinished(StopReason.END_TURN, new Usage(1, 1)));
        verify(guardianClient, never()).restore(any(), any());
    }

    private static ChatRequest request(String text) {
        return new ChatRequest("qwen36", null, List.of(new ChatMessage(Role.USER, List.of(new TextPart(text)))), null, null, null);
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse("r1", "qwen36", List.of(new TextPart(text)), StopReason.END_TURN, new Usage(1, 1));
    }

    private static ModelStream streamOf(StreamEvent... events) {
        return new ModelStream() {
            @Override
            public void forEach(EventConsumer consumer) throws IOException {
                for (StreamEvent event : events) {
                    consumer.accept(event);
                }
            }

            @Override
            public void close() {
            }
        };
    }
}
