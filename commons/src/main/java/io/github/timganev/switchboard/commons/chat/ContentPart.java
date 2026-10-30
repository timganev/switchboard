package io.github.timganev.switchboard.commons.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextPart.class, name = "text"),
        @JsonSubTypes.Type(value = ToolCallPart.class, name = "tool_call"),
        @JsonSubTypes.Type(value = ToolResultPart.class, name = "tool_result")})
public sealed interface ContentPart permits TextPart, ToolCallPart, ToolResultPart {
}
