package io.github.timganev.switchboard.leaklab.strategy;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import io.github.timganev.switchboard.commons.privacy.Substitution;
import io.github.timganev.switchboard.commons.privacy.SurrogateRestorer;

import java.util.EnumMap;
import java.util.LinkedHashMap;
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
    // The guardian with its decision model slot on; measured only when such a guardian is configured.
    SURROGATE_MODEL,
    LOCAL;

    /**
     * What leaves the boundary under this strategy; empty when nothing does.
     */
    public Optional<String> outbound(String prompt, Inspection inspection) {
        return switch (this) {
            case NONE -> Optional.of(prompt);
            case MASK -> Optional.of(replaced(prompt, inspection, false));
            case PLACEHOLDER -> Optional.of(replaced(prompt, inspection, true));
            case SURROGATE, SURROGATE_MODEL -> switch (inspection.route()) {
                case CLOUD -> Optional.of(prompt);
                case SURROGATE -> Optional.of(text(inspection.request()));
                case LOCAL, BLOCK -> Optional.empty();
                case SKETCH -> throw new IllegalStateException("SKETCH is not measured yet");
            };
            case LOCAL -> Optional.empty();
        };
    }

    /**
     * True when the user gets no answer at all, because the guardian blocked the request.
     */
    public boolean blocked(Inspection inspection) {
        return (this == SURROGATE || this == SURROGATE_MODEL) && inspection.route() == Route.BLOCK;
    }

    /**
     * True when the guardian is on the request's path, so its time counts towards the latency.
     */
    public boolean guarded() {
        return this != NONE && this != LOCAL;
    }

    /**
     * True when the strategy is judged on the verdict of the guardian with the decision model.
     */
    public boolean withModel() {
        return this == SURROGATE_MODEL;
    }

    /**
     * The answer as the user receives it; masked values are lost, the others go back in.
     */
    public String restored(String answer, Inspection inspection) {
        return switch (this) {
            case NONE, MASK, LOCAL -> answer;
            case PLACEHOLDER -> {
                String restored = answer;
                for (Map.Entry<String, String> placeholder : placeholders(inspection).entrySet()) {
                    restored = restored.replace(placeholder.getValue(), placeholder.getKey());
                }
                yield restored;
            }
            case SURROGATE, SURROGATE_MODEL -> new SurrogateRestorer(inspection.substitutions()).restore(answer);
        };
    }

    private static String replaced(String prompt, Inspection inspection, boolean numbered) {
        if (inspection.route() != Route.CLOUD && inspection.route() != Route.SURROGATE) {
            throw new IllegalStateException("The guardian chose " + inspection.route() + ", so there are no findings to mask");
        }
        String replaced = prompt;
        for (Map.Entry<String, String> placeholder : placeholders(inspection).entrySet()) {
            replaced = replaced.replace(placeholder.getKey(), numbered ? placeholder.getValue() : "***");
        }
        return replaced;
    }

    // Real value to placeholder, numbered per type in the order the guardian found them.
    private static Map<String, String> placeholders(Inspection inspection) {
        Map<SensitiveType, Integer> counts = new EnumMap<>(SensitiveType.class);
        Map<String, String> placeholders = new LinkedHashMap<>();
        for (Substitution substitution : inspection.substitutions()) {
            if (!placeholders.containsKey(substitution.value())) {
                int number = counts.merge(substitution.type(), 1, Integer::sum);
                placeholders.put(substitution.value(), "[" + substitution.type() + "_" + number + "]");
            }
        }
        return placeholders;
    }

    private static String text(ChatRequest request) {
        return request.messages().stream()
                .flatMap(message -> message.parts().stream())
                .filter(TextPart.class::isInstance)
                .map(part -> ((TextPart) part).text())
                .collect(Collectors.joining("\n"));
    }
}
