package io.github.timganev.switchboard.guardian.surrogate;

import java.util.Comparator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Replaces many values in one pass, longest first, so a replacement is never replaced again.
 */
public final class Replacer {

    private Replacer() {
    }

    public static String replace(String text, Map<String, String> replacements) {
        if (text == null || replacements.isEmpty()) {
            return text;
        }
        Pattern pattern = Pattern.compile(replacements.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|")));
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacements.get(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
