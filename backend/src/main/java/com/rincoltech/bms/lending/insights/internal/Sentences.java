package com.rincoltech.bms.lending.insights.internal;

import java.util.Map;

/**
 * The plain sentences of the morning brief and the daily digest: one per movement, in words a
 * busy owner reads at a glance, with amounts formatted from integer minor units (ADR-004).
 */
final class Sentences {

    /** Currency exponents, as the currencies table seeds them (chapter 6 section 6.4). */
    private static final Map<String, Integer> EXPONENTS = Map.of("UGX", 0, "KES", 2, "TZS", 2, "USD", 2);

    private Sentences() {}

    /** {@code UGX 1,250,000} or {@code KES 1,250.50}: no floating point touches the amount. */
    static String money(long minor, String currency) {
        int exponent = EXPONENTS.getOrDefault(currency, 0);
        String digits = String.valueOf(Math.abs(minor));
        while (digits.length() < exponent + 1) {
            digits = "0" + digits;
        }
        String whole = digits.substring(0, digits.length() - exponent);
        String fraction = exponent > 0 ? "." + digits.substring(digits.length() - exponent) : "";
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(whole.charAt(i));
        }
        return (minor < 0 ? "-" : "") + currency + " " + grouped + fraction;
    }

    /** {@code 12.5 percent} from basis points, rounded half up to one decimal (chapter 14 section 14.1). */
    static String percent(long bp) {
        long tenths = Math.floorDiv(bp + 5, 10);
        return (tenths / 10) + "." + Math.abs(tenths % 10) + " percent";
    }

    private static String loans(long n) {
        return n == 1 ? "1 loan" : n + " loans";
    }

    static String disbursed(long amount, long count, long lastWeekAmount, String currency) {
        if (count == 0) {
            return lastWeekAmount == 0
                    ? "No loans were disbursed today."
                    : "No loans were disbursed today; " + money(lastWeekAmount, currency)
                            + " went out on the same day last week.";
        }
        return money(amount, currency) + " went out today on " + loans(count) + ", "
                + compare(amount, lastWeekAmount, currency) + ".";
    }

    static String collected(long amount, long lastWeekAmount, String currency) {
        if (amount == 0) {
            return lastWeekAmount == 0
                    ? "No repayments have come in today."
                    : "No repayments have come in today; " + money(lastWeekAmount, currency)
                            + " came in on the same day last week.";
        }
        return money(amount, currency) + " came in today, " + compare(amount, lastWeekAmount, currency) + ".";
    }

    static String dueToday(long expected, long collectedOnDue, Long rateBp, String currency) {
        if (expected == 0) {
            return "Nothing was due today.";
        }
        return money(collectedOnDue, currency) + " of the " + money(expected, currency) + " due today has been paid ("
                + percent(rateBp == null ? 0 : rateBp) + ").";
    }

    static String newArrears(long count, long amount, String currency) {
        if (count == 0) {
            return "No loan fell into arrears today.";
        }
        return loans(count) + " fell into arrears today, owing " + money(amount, currency) + " overdue.";
    }

    static String goingBad(long count, long principal, String currency) {
        if (count == 0) {
            return "No loan will pass 30 days late this week.";
        }
        return loans(count) + " holding " + money(principal, currency)
                + " will pass 30 days late within 7 days unless paid.";
    }

    private static String compare(long now, long before, String currency) {
        if (before == 0) {
            return "against nothing on the same day last week";
        }
        if (now == before) {
            return "the same as on the same day last week";
        }
        long changeBp = Metrics.bp(Math.abs(now - before), before);
        return (now > before ? "up " : "down ") + percent(changeBp) + " on the same day last week ("
                + money(before, currency) + ")";
    }
}
