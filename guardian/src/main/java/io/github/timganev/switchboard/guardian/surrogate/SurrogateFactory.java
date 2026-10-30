package io.github.timganev.switchboard.guardian.surrogate;

import io.github.timganev.switchboard.commons.privacy.SensitiveType;
import io.github.timganev.switchboard.guardian.detect.Checksums;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a fake but valid value of the same type: the same input and key always give the same surrogate.
 */
public class SurrogateFactory {

    private static final Pattern API_KEY_PREFIX = Pattern.compile("^(?:sk-(?:ant-|proj-)?|ghp_|github_pat_|AKIA|xox[abprs]-|AIza)");
    private static final List<String> FIRST_NAMES = List.of("ivan", "maria", "georgi", "elena", "petar", "nina", "stefan", "vera");
    private static final List<String> LAST_NAMES = List.of("petrov", "ivanova", "dimitrov", "georgieva", "stoyanov", "koleva");
    private static final LocalDate OLDEST_BIRTH_DATE = LocalDate.of(1950, 1, 1);
    private static final int BIRTH_DATE_SPAN_DAYS = 20_000;

    public byte[] key(byte[] secret, String context) {
        return hmac(secret, context);
    }

    public String surrogate(byte[] key, SensitiveType type, String value, int attempt) {
        byte[] digest = hmac(key, type + "\u0000" + value + "\u0000" + attempt);
        SplittableRandom random = new SplittableRandom(ByteBuffer.wrap(digest).getLong());
        return switch (type) {
            case EGN -> egn(random);
            case LNCH -> lnch(random);
            case IBAN -> iban(value, random);
            case CARD -> card(value, random);
            case PHONE -> phone(value, random);
            case EMAIL -> email(random);
            case API_KEY -> apiKey(value, random);
            case JWT -> jwt(value, random);
            case PRIVATE_KEY -> throw new IllegalArgumentException("A private key is never replaced by a surrogate");
        };
    }

    private static String egn(SplittableRandom random) {
        LocalDate birth = OLDEST_BIRTH_DATE.plusDays(random.nextInt(BIRTH_DATE_SPAN_DAYS));
        int month = birth.getYear() >= 2000 ? birth.getMonthValue() + 40 : birth.getMonthValue();
        String digits = "%02d%02d%02d%03d".formatted(birth.getYear() % 100, month, birth.getDayOfMonth(), random.nextInt(1000));
        return digits + Checksums.egnCheckDigit(digits);
    }

    private static String lnch(SplittableRandom random) {
        String digits = "%09d".formatted(random.nextInt(1_000_000_000));
        return digits + Checksums.lnchCheckDigit(digits);
    }

    private static String iban(String value, SplittableRandom random) {
        String compact = value.replace(" ", "");
        String country = compact.substring(0, 2);
        String bban = reshape(compact.substring(4), random);
        String fake = country + Checksums.ibanCheckDigits(country, bban) + bban;
        return withSpacesOf(value, fake);
    }

    private static String card(String value, SplittableRandom random) {
        String digits = value.replaceAll("[ -]", "");
        String body = digits.charAt(0) + reshape(digits.substring(1, digits.length() - 1), random);
        String fake = body + Checksums.luhnCheckDigit(body);
        StringBuilder out = new StringBuilder();
        int next = 0;
        for (char c : value.toCharArray()) {
            out.append(Character.isDigit(c) ? fake.charAt(next++) : c);
        }
        return out.toString();
    }

    private static String phone(String value, SplittableRandom random) {
        char[] chars = value.toCharArray();
        int toReplace = 7;
        for (int i = chars.length - 1; i >= 0 && toReplace > 0; i--) {
            if (Character.isDigit(chars[i])) {
                chars[i] = (char) ('0' + random.nextInt(10));
                toReplace--;
            }
        }
        return new String(chars);
    }

    private static String email(SplittableRandom random) {
        return FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size())) + "."
                + LAST_NAMES.get(random.nextInt(LAST_NAMES.size())) + random.nextInt(100) + "@example.com";
    }

    private static String apiKey(String value, SplittableRandom random) {
        Matcher prefix = API_KEY_PREFIX.matcher(value);
        int keep = prefix.find() ? prefix.end() : 0;
        return value.substring(0, keep) + reshape(value.substring(keep), random);
    }

    private static String jwt(String value, SplittableRandom random) {
        String[] segments = value.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            boolean header = i < 2 && segments[i].startsWith("eyJ");
            segments[i] = header ? "eyJ" + reshape(segments[i].substring(3), random) : reshape(segments[i], random);
        }
        return String.join(".", segments);
    }

    // Keeps the shape: digits stay digits, letters stay letters of the same case, everything else is kept.
    private static String reshape(String value, SplittableRandom random) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c >= '0' && c <= '9') {
                out.append((char) ('0' + random.nextInt(10)));
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) ('A' + random.nextInt(26)));
            } else if (c >= 'a' && c <= 'z') {
                out.append((char) ('a' + random.nextInt(26)));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String withSpacesOf(String original, String compact) {
        StringBuilder out = new StringBuilder();
        int next = 0;
        for (char c : original.toCharArray()) {
            out.append(c == ' ' ? ' ' : compact.charAt(next++));
        }
        return out.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is not available", exception);
        }
    }
}
