package io.github.timganev.switchboard.guardian.detect;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * The check digits that turn a pattern match into a real identifier.
 */
public final class Checksums {

    private static final int[] EGN_WEIGHTS = {2, 4, 8, 5, 10, 9, 7, 3, 6};
    private static final int[] LNCH_WEIGHTS = {21, 19, 17, 13, 11, 9, 7, 3, 1};

    private Checksums() {
    }

    public static boolean isEgn(String digits) {
        if (!digits.matches("\\d{10}") || egnCheckDigit(digits) != digits.charAt(9) - '0') {
            return false;
        }
        int year = Integer.parseInt(digits.substring(0, 2));
        int month = Integer.parseInt(digits.substring(2, 4));
        int day = Integer.parseInt(digits.substring(4, 6));
        if (month > 40) {
            year += 2000;
            month -= 40;
        } else if (month > 20) {
            year += 1800;
            month -= 20;
        } else {
            year += 1900;
        }
        try {
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    public static int egnCheckDigit(String digits) {
        return weightedSum(digits, EGN_WEIGHTS) % 11 % 10;
    }

    public static boolean isLnch(String digits) {
        return digits.matches("\\d{10}") && lnchCheckDigit(digits) == digits.charAt(9) - '0';
    }

    public static int lnchCheckDigit(String digits) {
        return weightedSum(digits, LNCH_WEIGHTS) % 10;
    }

    public static boolean isIban(String iban) {
        String compact = iban.replace(" ", "");
        return compact.length() >= 15 && compact.length() <= 34 && ibanRemainder(compact) == 1;
    }

    public static String ibanCheckDigits(String countryCode, String bban) {
        int check = 98 - ibanRemainder(countryCode + "00" + bban);
        return check < 10 ? "0" + check : String.valueOf(check);
    }

    public static boolean isCard(String number) {
        String digits = number.replaceAll("[ -]", "");
        return digits.length() >= 13 && digits.length() <= 19 && luhnSum(digits) % 10 == 0;
    }

    public static int luhnCheckDigit(String digitsWithoutCheck) {
        return (10 - luhnSum(digitsWithoutCheck + "0") % 10) % 10;
    }

    private static int weightedSum(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        return sum;
    }

    private static int ibanRemainder(String compact) {
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            numeric.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
    }

    private static int luhnSum(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum;
    }
}
