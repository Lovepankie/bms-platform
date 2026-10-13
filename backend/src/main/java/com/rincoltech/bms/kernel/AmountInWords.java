package com.rincoltech.bms.kernel;

import java.util.Map;

/**
 * An amount in English words for printed documents (ADR-034 quality bar: amounts in figures and in
 * words), for example 600,000 UGX as "Six hundred thousand Uganda shillings only". The currencies
 * are those the platform seeds (chapter 6), each with its exponent: UGX has no minor unit, the
 * others have two decimals, read as cents.
 */
public final class AmountInWords {

    private record Unit(String one, String many, int exponent) {}

    private static final Map<String, Unit> UNITS = Map.of(
            "UGX", new Unit("Uganda shilling", "Uganda shillings", 0),
            "KES", new Unit("Kenya shilling", "Kenya shillings", 2),
            "TZS", new Unit("Tanzania shilling", "Tanzania shillings", 2),
            "USD", new Unit("US dollar", "US dollars", 2));

    private static final String[] ONES = {
        "zero",
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine",
        "ten",
        "eleven",
        "twelve",
        "thirteen",
        "fourteen",
        "fifteen",
        "sixteen",
        "seventeen",
        "eighteen",
        "nineteen"
    };
    private static final String[] TENS = {
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };
    private static final String[] SCALES = {"", "thousand", "million", "billion", "trillion", "quadrillion"};

    private AmountInWords() {}

    /** @throws IllegalArgumentException for a negative amount or a currency the platform does not seed */
    public static String of(long minor, String currency) {
        if (minor < 0) {
            throw new IllegalArgumentException("amount is negative");
        }
        Unit unit = UNITS.get(currency);
        if (unit == null) {
            throw new IllegalArgumentException("no words for currency " + currency);
        }
        long scale = unit.exponent() == 0 ? 1 : 100;
        long major = minor / scale;
        long cents = minor % scale;
        StringBuilder words =
                new StringBuilder(number(major)).append(' ').append(major == 1 ? unit.one() : unit.many());
        if (cents > 0) {
            words.append(" and ").append(number(cents)).append(cents == 1 ? " cent" : " cents");
        }
        words.append(" only");
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** British style: "one hundred and five", "two thousand and ten", "one million, two hundred thousand". */
    static String number(long n) {
        if (n < 0 || n >= 1_000_000_000_000_000_000L) {
            throw new IllegalArgumentException("amount is outside what the scales name (below 10^18)");
        }
        if (n == 0) {
            return ONES[0];
        }
        StringBuilder out = new StringBuilder();
        int group = 0;
        long rest = n;
        String[] groups = new String[SCALES.length];
        while (rest > 0) {
            groups[group++] = rest % 1000 == 0 ? null : String.valueOf(rest % 1000);
            rest /= 1000;
        }
        for (int g = group - 1; g >= 0; g--) {
            if (groups[g] == null) {
                continue;
            }
            int value = Integer.parseInt(groups[g]);
            if (!out.isEmpty()) {
                // A last group below one hundred reads "and ninety", otherwise groups are separated by commas.
                out.append(g == 0 && value < 100 ? " and " : ", ");
            }
            out.append(hundreds(value));
            if (!SCALES[g].isEmpty()) {
                out.append(' ').append(SCALES[g]);
            }
        }
        return out.toString();
    }

    private static String hundreds(int n) {
        StringBuilder out = new StringBuilder();
        if (n >= 100) {
            out.append(ONES[n / 100]).append(" hundred");
            n %= 100;
            if (n > 0) {
                out.append(" and ");
            }
        }
        if (n >= 20) {
            out.append(TENS[n / 10]);
            if (n % 10 > 0) {
                out.append('-').append(ONES[n % 10]);
            }
        } else if (n > 0 || out.isEmpty()) {
            out.append(ONES[n]);
        }
        return out.toString();
    }
}
