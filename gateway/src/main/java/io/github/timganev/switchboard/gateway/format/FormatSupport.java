package io.github.timganev.switchboard.gateway.format;

import tools.jackson.databind.JsonNode;

final class FormatSupport {

    private FormatSupport() {
    }

    static JsonNode required(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    static String requiredText(JsonNode json, String field) {
        JsonNode value = required(json, field);
        if (!value.isString()) {
            throw new IllegalArgumentException("Field must be a string: " + field);
        }
        return value.asString();
    }
}
