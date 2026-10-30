package io.github.timganev.switchboard.guardian.detect;

import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds sensitive values with patterns, and keeps only the ones whose check digits are right.
 */
@Component
public class Detector {

    // Goes into every privacy decision, so a change in the rules shows up next to the verdicts it changed.
    public static final String VERSION = "1";

    private static final List<Rule> RULES = List.of(
            new Rule(SensitiveType.PRIVATE_KEY, "-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----", value -> true),
            new Rule(SensitiveType.JWT, "\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", value -> true),
            new Rule(SensitiveType.API_KEY, "\\b(?:sk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{36}"
                    + "|github_pat_[A-Za-z0-9_]{22,}|AKIA[0-9A-Z]{16}|xox[abprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35})", value -> true),
            new Rule(SensitiveType.EMAIL, "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", value -> true),
            new Rule(SensitiveType.IBAN, "\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\\b", Checksums::isIban),
            new Rule(SensitiveType.CARD, "(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)", Checksums::isCard),
            new Rule(SensitiveType.PHONE, "(?<![\\w+])(?:\\+359|00359|0)[ -]?(?:8[7-9]|2)(?:[ -]?\\d){7}(?!\\d)", value -> true),
            new Rule(SensitiveType.EGN, "(?<!\\d)\\d{10}(?!\\d)", Checksums::isEgn),
            new Rule(SensitiveType.LNCH, "(?<!\\d)\\d{10}(?!\\d)", Checksums::isLnch));

    public List<Finding> scan(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Finding> accepted = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                Finding finding = new Finding(rule.type(), matcher.group(), matcher.start(), matcher.end());
                if (rule.valid().test(finding.value()) && accepted.stream().noneMatch(other -> overlaps(other, finding))) {
                    accepted.add(finding);
                }
            }
        }
        accepted.sort(Comparator.comparingInt(Finding::start));
        return accepted;
    }

    private static boolean overlaps(Finding a, Finding b) {
        return a.start() < b.end() && b.start() < a.end();
    }

    private record Rule(SensitiveType type, Pattern pattern, Predicate<String> valid) {

        Rule(SensitiveType type, String regex, Predicate<String> valid) {
            this(type, Pattern.compile(regex), valid);
        }
    }
}
