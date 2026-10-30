package io.github.timganev.switchboard.commons.privacy;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Puts the real values back in place of their surrogates, also when the model rewrote a surrogate
 * with spaces, dashes or another letter case, or wrote a phone number with another prefix.
 */
public final class SurrogateRestorer {

    private static final Set<SensitiveType> SEPARABLE = EnumSet.of(
            SensitiveType.EGN, SensitiveType.LNCH, SensitiveType.CARD, SensitiveType.IBAN, SensitiveType.PHONE);
    private static final Set<SensitiveType> CASELESS = EnumSet.of(SensitiveType.EMAIL, SensitiveType.IBAN);
    private static final int MAX_SEPARATORS = 2;
    private static final String SEPARATOR = "[ .\\-\\u00A0]{0," + MAX_SEPARATORS + "}";
    private static final String PHONE_PREFIX = "(?:\\+359|00359|0)";
    private static final int PHONE_PREFIX_LENGTH = "00359".length();

    private final List<Substitution> substitutions;
    private final Pattern pattern;
    private final int longestMatch;

    public SurrogateRestorer(List<Substitution> substitutions) {
        // Longest first, so a surrogate that contains another one wins.
        this.substitutions = substitutions.stream()
                .sorted(Comparator.comparingInt((Substitution substitution) -> substitution.surrogate().length()).reversed())
                .toList();
        this.pattern = this.substitutions.isEmpty() ? null : Pattern.compile(this.substitutions.stream()
                .map(substitution -> "(" + regex(substitution) + ")")
                .collect(Collectors.joining("|")));
        this.longestMatch = this.substitutions.stream().mapToInt(SurrogateRestorer::longestMatch).max().orElse(0);
    }

    public String restore(String text) {
        if (text == null || pattern == null) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement(matcher)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Restores text that arrives in pieces; a surrogate split between two pieces is still found.
     */
    public Stream stream() {
        return new Stream();
    }

    public final class Stream {

        private final StringBuilder buffer = new StringBuilder();
        // Text before this index was already emitted and stays only as look-behind context.
        private int start;

        private Stream() {
        }

        /**
         * Returns the text that is safe to emit; it holds back a tail that could still be the start of a surrogate.
         */
        public String push(String piece) {
            if (pattern == null) {
                return piece;
            }
            buffer.append(piece);
            return emit(Math.max(start, buffer.length() - longestMatch));
        }

        public String finish() {
            return pattern == null ? "" : emit(buffer.length());
        }

        private String emit(int cut) {
            StringBuilder out = new StringBuilder();
            Matcher matcher = pattern.matcher(buffer);
            matcher.useTransparentBounds(true).useAnchoringBounds(false).region(start, buffer.length());
            int position = start;
            while (matcher.find() && matcher.start() < cut) {
                if (matcher.end() > cut) {
                    cut = matcher.start();
                    break;
                }
                out.append(buffer, position, matcher.start()).append(replacement(matcher));
                position = matcher.end();
            }
            out.append(buffer, position, cut);
            int keepFrom = Math.max(0, cut - 1);
            buffer.delete(0, keepFrom);
            start = cut - keepFrom;
            return out.toString();
        }
    }

    private String replacement(Matcher matcher) {
        for (int group = 1; group <= substitutions.size(); group++) {
            if (matcher.group(group) != null) {
                return reshape(substitutions.get(group - 1), matcher.group(group));
            }
        }
        throw new IllegalStateException("A match without a group");
    }

    // Keeps the model's spacing when it wrote as many letters and digits as the real value has.
    private static String reshape(Substitution substitution, String matched) {
        String real = alphanumeric(substitution.value());
        if (!SEPARABLE.contains(substitution.type()) || alphanumeric(matched).length() != real.length()) {
            return substitution.value();
        }
        StringBuilder out = new StringBuilder(matched.length());
        int next = 0;
        for (char c : matched.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) ? real.charAt(next++) : c);
        }
        return out.toString();
    }

    private static String regex(Substitution substitution) {
        String surrogate = substitution.surrogate();
        String regex;
        if (substitution.type() == SensitiveType.PHONE) {
            regex = "(?<![A-Za-z0-9+])(?:" + PHONE_PREFIX + SEPARATOR + ")?" + separated(nationalNumber(surrogate)) + "(?![A-Za-z0-9])";
        } else if (SEPARABLE.contains(substitution.type())) {
            regex = "(?<![A-Za-z0-9])" + separated(alphanumeric(surrogate)) + "(?![A-Za-z0-9])";
        } else {
            regex = Pattern.quote(surrogate);
        }
        return CASELESS.contains(substitution.type()) ? "(?i:" + regex + ")" : regex;
    }

    private static int longestMatch(Substitution substitution) {
        if (!SEPARABLE.contains(substitution.type())) {
            return substitution.surrogate().length();
        }
        boolean phone = substitution.type() == SensitiveType.PHONE;
        int characters = phone ? nationalNumber(substitution.surrogate()).length() : alphanumeric(substitution.surrogate()).length();
        int separated = characters + (characters - 1) * MAX_SEPARATORS;
        return phone ? PHONE_PREFIX_LENGTH + MAX_SEPARATORS + separated : separated;
    }

    private static String separated(String characters) {
        return characters.chars()
                .mapToObj(c -> Pattern.quote(String.valueOf((char) c)))
                .collect(Collectors.joining(SEPARATOR));
    }

    private static String nationalNumber(String phone) {
        String digits = alphanumeric(phone);
        for (String prefix : List.of("00359", "359", "0")) {
            if (digits.startsWith(prefix)) {
                return digits.substring(prefix.length());
            }
        }
        return digits;
    }

    private static String alphanumeric(String value) {
        return value.chars()
                .filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
