package io.github.timganev.switchboard.commons.chat;

/**
 * One step of a streamed answer, independent of the client's API format.
 */
public sealed interface StreamEvent {

    record MessageStarted(String id, String model) implements StreamEvent {
    }

    record TextDelta(String text) implements StreamEvent {
    }

    record ToolCallStarted(int index, String id, String name) implements StreamEvent {
    }

    record ToolCallDelta(int index, String argumentsFragment) implements StreamEvent {
    }

    record MessageFinished(StopReason stopReason, Usage usage) implements StreamEvent {
    }
}
