package com.rincoltech.bms.lending.insights;

import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import java.util.Map;

/**
 * The display forms of the facts, from integer minor units, counts and basis points with no floating point
 * (ADR-004). One place, so the insights sentences, the facts a prompt carries and the advisor's validator agree on
 * what a figure looks like. Amounts and counts are grouped in threes ({@code 1,250,000}) so that a prompt never holds
 * a run of six digits, which the AI gateway's input screen refuses.
 */
public final class FactDisplay {

    /** Currency exponents, as the currencies table seeds them (chapter 6 section 6.4). */
    private static final Map<String, Integer> EXPONENTS = Map.of("UGX", 0, "KES", 2, "TZS", 2, "USD", 2);

    private FactDisplay() {}

    /** The digits after the decimal point of an amount in this currency: 0 for shillings. */
    public static int exponent(String currency) {
        return EXPONENTS.getOrDefault(currency, 0);
    }

    /** {@code UGX 1,250,000} or {@code KES 1,250.50}: no floating point touches the amount. */
    public static String money(long minor, String currency) {
        int exponent = exponent(currency);
        String digits = String.valueOf(Math.abs(minor));
        while (digits.length() < exponent + 1) {
            digits = "0" + digits;
        }
        String whole = digits.substring(0, digits.length() - exponent);
        String fraction = exponent > 0 ? "." + digits.substring(digits.length() - exponent) : "";
        return (minor < 0 ? "-" : "") + currency + " " + grouped(whole) + fraction;
    }

    /** {@code 12.5 percent} from basis points, rounded half up (away from zero) to one decimal, with its minus sign (chapter 14 section 14.1). */
    public static String percent(long bp) {
        long tenths = (Math.abs(bp) + 5) / 10;
        return (bp < 0 && tenths != 0 ? "-" : "") + (tenths / 10) + "." + (tenths % 10) + " percent";
    }

    /** A count with thousands separators. */
    public static String count(long n) {
        return (n < 0 ? "-" : "") + grouped(String.valueOf(Math.abs(n)));
    }

    /** The display form of a value of this kind. */
    public static String of(Kind kind, long value, String currency) {
        return switch (kind) {
            case MONEY -> money(value, currency);
            case BASIS_POINTS -> percent(value);
            case COUNT -> count(value);
            case DAYS -> count(value) + (value == 1 ? " day" : " days");
            case HOURS -> count(value) + (value == 1 ? " hour" : " hours");
        };
    }

    /**
     * A change in the fact's units, with its sign: {@code +0.40 points} for a rate (basis points are hundredths of a
     * point, so two decimals are exact), {@code -UGX 120,000}, {@code +3}, {@code +2 days}; {@code no change} for zero.
     */
    public static String change(Kind kind, long change, String currency) {
        if (change == 0) {
            return "no change";
        }
        String sign = change > 0 ? "+" : "-";
        long magnitude = Math.abs(change);
        return switch (kind) {
            case MONEY -> sign + money(magnitude, currency);
            case BASIS_POINTS -> sign + (magnitude / 100) + "." + String.format("%02d", magnitude % 100) + " points";
            case COUNT -> sign + count(magnitude);
            case DAYS -> sign + count(magnitude) + (magnitude == 1 ? " day" : " days");
            case HOURS -> sign + count(magnitude) + (magnitude == 1 ? " hour" : " hours");
        };
    }

    private static String grouped(String whole) {
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(whole.charAt(i));
        }
        return grouped.toString();
    }
}
