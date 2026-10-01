package com.rincoltech.bms.lending.products;

import com.rincoltech.bms.kernel.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The normative schedule rules of chapter 3 section 3.4: R-ROUND, R-TERM, R-RATE, R-FLAT and
 * R-DECL. The product form's preview and (from increment 5) real loan schedules both call this one
 * class, so they cannot disagree (FR-PRD-03). Amounts are integer minor units and rates are basis
 * points; the arithmetic in between is decimal with 34 significant digits, never floating point
 * (ADR-004).
 */
public final class ScheduleCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BP = BigDecimal.valueOf(10_000);

    private ScheduleCalculator() {}

    /**
     * @param interestMethod {@code flat} or {@code declining}
     * @param rateUnit {@code per_term}, {@code per_day}, {@code per_week}, {@code per_month} or {@code per_year}
     * @param termUnit {@code day}, {@code week} or {@code month}
     * @param repaymentPattern {@code bullet} or {@code instalments}
     * @param instalmentFrequency {@code daily}, {@code weekly}, {@code fortnightly} or {@code monthly}; null for bullet
     */
    public record Terms(
            String interestMethod,
            int interestRateBp,
            String rateUnit,
            String termUnit,
            int termCount,
            String repaymentPattern,
            String instalmentFrequency) {}

    /** One schedule item (instalment): what falls due on {@code dueDate}. */
    public record Item(int no, LocalDate dueDate, long principalMinor, long interestMinor, long feeMinor) {

        public long totalMinor() {
            return principalMinor + interestMinor + feeMinor;
        }
    }

    /**
     * The schedule for a principal disbursed on a date.
     *
     * @param addedFeesMinor fees with timing {@code added_to_loan}, spread like flat interest
     */
    public static List<Item> schedule(Terms t, long principalMinor, long addedFeesMinor, LocalDate disbursedOn) {
        if (principalMinor <= 0) {
            throw new IllegalArgumentException("principal must be positive");
        }
        int n = instalments(t);
        BigDecimal termRate = termRate(t);
        boolean declining = "declining".equals(t.interestMethod()) && n > 1;
        long[] principal = new long[n];
        long[] interest = new long[n];
        if (declining) {
            declining(principalMinor, termRate.divide(BigDecimal.valueOf(n), MC), principal, interest);
        } else {
            // R-FLAT; a declining bullet has nothing to amortise and is the same schedule.
            spread(principalMinor, principal);
            spread(round(BigDecimal.valueOf(principalMinor).multiply(termRate, MC)), interest);
        }
        long[] fees = new long[n];
        spread(addedFeesMinor, fees);
        List<Item> items = new ArrayList<>(n);
        for (int k = 1; k <= n; k++) {
            items.add(new Item(k, dueDate(t, k, n, disbursedOn), principal[k - 1], interest[k - 1], fees[k - 1]));
        }
        return List.copyOf(items);
    }

    /**
     * R-TERM: the number of schedule items. A term that does not divide into whole instalments is
     * refused with {@code invalid_term_frequency}.
     */
    public static int instalments(Terms t) {
        if ("bullet".equals(t.repaymentPattern())) {
            return 1;
        }
        int count = t.termCount();
        String pair = t.termUnit() + "/" + t.instalmentFrequency();
        int n = switch (pair) {
            case "month/monthly", "week/weekly", "day/daily" -> count;
            case "week/fortnightly" -> count % 2 == 0 ? count / 2 : 0;
            case "day/weekly" -> count % 7 == 0 ? count / 7 : 0;
            default -> 0;
        };
        if (n < 1) {
            throw ApiException.rule(
                    "invalid_term_frequency",
                    "A term of " + count + " " + t.termUnit() + "(s) does not divide into " + t.instalmentFrequency()
                            + " instalments.");
        }
        return n;
    }

    /** True when the frequency can ever fit the term unit (the count is checked by {@link #instalments}). */
    public static boolean frequencyFitsUnit(String termUnit, String instalmentFrequency) {
        return switch (termUnit + "/" + instalmentFrequency) {
            case "month/monthly", "week/weekly", "week/fortnightly", "day/daily", "day/weekly" -> true;
            default -> false;
        };
    }

    /**
     * R-RATE: the rate for the whole term. A rate unit that does not fit the term unit is refused
     * with {@code invalid_rate_unit}.
     */
    public static BigDecimal termRate(Terms t) {
        BigDecimal rate = BigDecimal.valueOf(t.interestRateBp()).divide(BP, MC);
        BigDecimal count = BigDecimal.valueOf(t.termCount());
        return switch (t.rateUnit()) {
            case "per_term" -> rate;
            case "per_day", "per_week", "per_month" -> {
                requireRateUnitFits(t.rateUnit(), t.termUnit());
                yield rate.multiply(count, MC);
            }
            case "per_year" ->
                switch (t.termUnit()) {
                    case "day" -> rate.multiply(count, MC).divide(BigDecimal.valueOf(365), MC);
                    case "week" ->
                        rate.multiply(count, MC)
                                .multiply(BigDecimal.valueOf(7), MC)
                                .divide(BigDecimal.valueOf(365), MC);
                    default -> rate.multiply(count, MC).divide(BigDecimal.valueOf(12), MC);
                };
            default -> throw invalidRateUnit(t.rateUnit(), t.termUnit());
        };
    }

    /** {@code per_day}, {@code per_week} and {@code per_month} are allowed only with the matching term unit. */
    public static void requireRateUnitFits(String rateUnit, String termUnit) {
        boolean periodic = rateUnit.equals("per_day") || rateUnit.equals("per_week") || rateUnit.equals("per_month");
        if (periodic && !rateUnit.equals("per_" + termUnit)) {
            throw invalidRateUnit(rateUnit, termUnit);
        }
    }

    private static ApiException invalidRateUnit(String rateUnit, String termUnit) {
        return ApiException.rule(
                "invalid_rate_unit", "A rate " + rateUnit + " cannot be used with a term in " + termUnit + "s.");
    }

    /** R-DECL: equal instalments on a declining balance; the last item takes the whole remaining balance. */
    private static void declining(long principalMinor, BigDecimal i, long[] principal, long[] interest) {
        int n = principal.length;
        if (i.signum() == 0) {
            spread(principalMinor, principal);
            return;
        }
        BigDecimal p = BigDecimal.valueOf(principalMinor);
        BigDecimal discount = BigDecimal.ONE.divide(BigDecimal.ONE.add(i).pow(n, MC), MC);
        long instalment = round(p.multiply(i, MC).divide(BigDecimal.ONE.subtract(discount), MC));
        long balance = principalMinor;
        for (int k = 0; k < n; k++) {
            interest[k] = round(BigDecimal.valueOf(balance).multiply(i, MC));
            principal[k] = k == n - 1 ? balance : instalment - interest[k];
            balance -= principal[k];
        }
    }

    /** floor(total / n) on every item, the remainder on the last, so the items sum to the total exactly. */
    private static void spread(long total, long[] into) {
        int n = into.length;
        long each = total / n;
        for (int k = 0; k < n - 1; k++) {
            into[k] = each;
        }
        into[n - 1] = total - each * (n - 1);
    }

    /**
     * R-TERM: each due date is computed from the disbursement date, not from the previous item, so
     * month-end clamping never drifts (31 January, 28 February, 31 March).
     */
    private static LocalDate dueDate(Terms t, int k, int n, LocalDate disbursedOn) {
        if (n == 1 && "bullet".equals(t.repaymentPattern())) {
            return switch (t.termUnit()) {
                case "day" -> disbursedOn.plusDays(t.termCount());
                case "week" -> disbursedOn.plusWeeks(t.termCount());
                default -> disbursedOn.plusMonths(t.termCount());
            };
        }
        return switch (t.instalmentFrequency()) {
            case "daily" -> disbursedOn.plusDays(k);
            case "weekly" -> disbursedOn.plusDays(7L * k);
            case "fortnightly" -> disbursedOn.plusDays(14L * k);
            default -> disbursedOn.plusMonths(k);
        };
    }

    /** A fee or charge of {@code bp} basis points of an amount, rounded per R-ROUND. */
    public static long percentOf(long amountMinor, int bp) {
        return round(BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(bp), MC)
                .divide(BP, MC));
    }

    /** R-ROUND: half up to the minor unit, once, at the line computed. */
    static long round(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
