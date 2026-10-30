package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatMessage;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.event.SensitivityReason;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Restoration;
import io.github.timganev.switchboard.guardian.detect.Detector;
import io.github.timganev.switchboard.guardian.detect.Finding;
import io.github.timganev.switchboard.guardian.detect.SensitiveType;
import io.github.timganev.switchboard.guardian.surrogate.Replacer;
import io.github.timganev.switchboard.guardian.surrogate.SurrogateFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides the route of a request and swaps sensitive values for surrogates and back, without storing anything.
 * The surrogates are rebuilt from the request each time: the APIs resend the whole history, so the same value always gets the same surrogate.
 */
@Service
public class Guardian {

    private final Detector detector;
    private final SurrogateFactory surrogateFactory;
    private final GuardianProperties properties;
    private final byte[] secret;

    public Guardian(Detector detector, SurrogateFactory surrogateFactory, GuardianProperties properties) {
        this.detector = detector;
        this.surrogateFactory = surrogateFactory;
        this.properties = properties;
        this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
    }

    public Inspection inspect(ChatRequest request) {
        Map<SensitiveType, Set<String>> found = scan(ChatTexts.of(request));
        Route route = found.keySet().stream()
                .map(properties.policy()::get)
                .max(Comparator.naturalOrder())
                .orElse(Route.CLOUD);
        List<SensitivityReason> reasons = found.entrySet().stream()
                .map(entry -> new SensitivityReason(entry.getKey().name(), entry.getValue().size()))
                .toList();
        return switch (route) {
            case BLOCK -> new Inspection(route, reasons, null);
            case SURROGATE -> {
                Map<String, String> surrogates = surrogates(request, found);
                yield new Inspection(route, reasons, ChatTexts.map(request, text -> Replacer.replace(text, surrogates)));
            }
            default -> new Inspection(route, reasons, request);
        };
    }

    public Restoration restore(ChatRequest original, ChatResponse response) {
        Map<SensitiveType, Set<String>> found = scan(ChatTexts.of(original));
        Map<String, String> realBySurrogate = surrogates(original, found).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        ChatResponse restored = ChatTexts.map(response, text -> Replacer.replace(text, realBySurrogate));

        Set<String> known = found.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Map<SensitiveType, Set<String>> produced = scan(ChatTexts.of(restored));
        produced.values().forEach(values -> values.removeAll(known));
        List<SensitivityReason> responseReasons = produced.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> new SensitivityReason(entry.getKey().name(), entry.getValue().size()))
                .toList();
        return new Restoration(restored, responseReasons);
    }

    private Map<SensitiveType, Set<String>> scan(List<String> texts) {
        Map<SensitiveType, Set<String>> found = new EnumMap<>(SensitiveType.class);
        for (String text : texts) {
            for (Finding finding : detector.scan(text)) {
                found.computeIfAbsent(finding.type(), type -> new LinkedHashSet<>()).add(finding.value());
            }
        }
        return found;
    }

    private Map<String, String> surrogates(ChatRequest request, Map<SensitiveType, Set<String>> found) {
        byte[] key = surrogateFactory.key(secret, conversationContext(request));
        Set<String> realValues = found.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Set<String> used = new HashSet<>();
        Map<String, String> surrogates = new LinkedHashMap<>();
        found.forEach((type, values) -> {
            if (properties.policy().get(type) != Route.SURROGATE) {
                return;
            }
            for (String value : values) {
                String surrogate;
                int attempt = 0;
                // Two real values must never share a surrogate, or the answer could not be restored.
                do {
                    surrogate = surrogateFactory.surrogate(key, type, value, attempt++);
                } while (realValues.contains(surrogate) || used.contains(surrogate));
                used.add(surrogate);
                surrogates.put(value, surrogate);
            }
        });
        return surrogates;
    }

    // A key per conversation, so the provider cannot link the same value across conversations.
    private static String conversationContext(ChatRequest request) {
        if (request.messages().isEmpty()) {
            return "";
        }
        ChatMessage first = request.messages().getFirst();
        return first.parts().stream()
                .filter(TextPart.class::isInstance)
                .map(part -> ((TextPart) part).text())
                .collect(Collectors.joining("\n"));
    }
}
