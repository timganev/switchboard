package io.github.timganev.switchboard.leaklab.strategy;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import io.github.timganev.switchboard.commons.privacy.Substitution;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ways to protect a prompt; the baselines replace exactly what the guardian found, so only the replacement differs.
 */
public enum Strategy {
    NONE,
    MASK,
    PLACEHOLDER,
    SURROGATE,
    LOCAL;

    /**
     * What leaves the boundary under this strategy; empty when nothing does.
     */
    public Optional<String> outbound(String prompt, Inspection inspection) {
        return switch (this) {
            case NONE -> Optional.of(prompt);
            case MASK -> Optional.of(replaced(prompt, inspection, false));
            case PLACEHOLDER -> Optional.of(replaced(prompt, inspection, true));
            case SURROGATE -> switch (inspection.route()) {
                case CLOUD -> Optional.of(prompt);
                case SURROGATE -> Optional.of(text(inspection.request()));
                case LOCAL, BLOCK -> Optional.empty();
                case SKETCH -> throw new IllegalStateException("SKETCH is not measured yet");
            };
            case LOCAL -> Optional.empty();
        };
    }

    private static String replaced(String prompt, Inspection inspection, boolean numbered) {
        if (inspection.route() != Route.CLOUD && inspection.route() != Route.SURROGATE) {
            throw new IllegalStateException("The guardian chose " + inspection.route() + ", so there are no findings to mask");
        }
        Map<SensitiveType, Integer> counts = new EnumMap<>(SensitiveType.class);
        String replaced = prompt;
        for (Substitution substitution : inspection.substitutions()) {
            int number = counts.merge(substitution.type(), 1, Integer::sum);
            replaced = replaced.replace(substitution.value(), numbered ? "[" + substitution.type() + "_" + number + "]" : "***");
        }
        return replaced;
    }

    private static String text(ChatRequest request) {
        return request.messages().stream()
                .flatMap(message -> message.parts().stream())
                .filter(TextPart.class::isInstance)
                .map(part -> ((TextPart) part).text())
                .collect(Collectors.joining("\n"));
    }
}
