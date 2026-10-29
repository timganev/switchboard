package io.github.timganev.switchboard.gateway.format;

import tools.jackson.databind.JsonNode;

import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

final class JsonAssertions {

    // Compares numbers by value, so a long written by the code equals an int parsed from the expected JSON.
    private static final Comparator<JsonNode> NUMBERS_BY_VALUE = (first, second) ->
            first.isNumber() && second.isNumber()
                    ? first.decimalValue().compareTo(second.decimalValue())
                    : first.equals(second) ? 0 : 1;

    private JsonAssertions() {
    }

    static void assertSameJson(JsonNode actual, JsonNode expected) {
        assertThat(actual.equals(NUMBERS_BY_VALUE, expected))
                .as("expected %s but was %s", expected, actual)
                .isTrue();
    }
}
