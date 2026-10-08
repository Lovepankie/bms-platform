package com.rincoltech.bms.lending.investments.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The return rules of chapter 3 section 3.25.1 (R-INV-1 to R-INV-6). Pure: no database and no
 * clock, so the golden tests drive it directly. Amounts are integer minor units (ADR-004); every
 * rounding is half up, once, at the amount it produces (R-ROUND), with exact rational arithmetic
 * before it.
 */
final class InvestmentReturns {

    static final String FLAT = "flat";
    static final String COMPOUND = "compound";
    static final String AT_MATURITY = "at_maturity";
    static final String MONTHLY = "monthly";
    static final String QUARTERLY = "quarterly";

    private static final BigInteger BP_MONTHS = BigInteger.valueOf(120_000);
    private static final BigInteger BP = BigInteger.valueOf(10_000);

    private InvestmentReturns() {}

    /** The terms one schedule is computed from. */
    record Terms(long principalMinor, int rateBp, String method, int termMonths, String payoutFrequency) {}

    /** One month of the term: its dates, the principal its return is computed on, and whether it pays out. */
    record Period(int no, LocalDate start, LocalDate end, long openingBalanceMinor, long returnMinor, boolean payout) {}

    /**
     * R-INV-1 to R-INV-4: one period per month of the term. Period {@code k} runs from
     * {@code start + (k-1) months} to {@code start + k months} (R-TERM month rule: each date from
     * the start date, clamped to the month's last day, so clamping never drifts).
     */
    static List<Period> schedule(Terms t, LocalDate start) {
        if (t.principalMinor() <= 0 || t.termMonths() < 1 || t.rateBp() < 0) {
            throw new IllegalArgumentException("principal and term must be positive");
        }
        if (COMPOUND.equals(t.method()) && !AT_MATURITY.equals(t.payoutFrequency())) {
            throw new IllegalArgumentException("a compounding return is paid at maturity (R-INV-3)");
        }
        List<Period> periods = new ArrayList<>(t.termMonths());
        long previousCumulative = 0;
        long balance = t.principalMinor();
        for (int k = 1; k <= t.termMonths(); k++) {
            LocalDate from = start.plusMonths(k - 1);
            LocalDate to = start.plusMonths(k);
            long amount;
            long opening;
            if (COMPOUND.equals(t.method())) {
                // R-INV-3: each month earns on the principal plus the return already earned.
                opening = balance;
                amount = roundHalfUp(BigInteger.valueOf(balance).multiply(BigInteger.valueOf(t.rateBp())), BP_MONTHS);
                balance = Math.addExact(balance, amount);
            } else {
                // R-INV-2: the cumulative return rounded once per month; a month takes the difference,
                // so the months sum to the agreed return of FR-INV-03 exactly.
                opening = t.principalMinor();
                long cumulative = flat(t.principalMinor(), t.rateBp(), k);
                amount = cumulative - previousCumulative;
                previousCumulative = cumulative;
            }
            periods.add(new Period(k, from, to, opening, amount, payout(t.payoutFrequency(), k, t.termMonths())));
        }
        return periods;
    }

    /** FR-INV-03: {@code round(amount x rate x months / 12)}, the flat return of whole months. */
    static long flat(long principalMinor, int rateBp, int months) {
        return roundHalfUp(
                BigInteger.valueOf(principalMinor)
                        .multiply(BigInteger.valueOf(rateBp))
                        .multiply(BigInteger.valueOf(months)),
                BP_MONTHS);
    }

    /** The sum of a schedule's returns: the agreed return. */
    static long total(List<Period> periods) {
        return periods.stream().mapToLong(Period::returnMinor).reduce(0, Math::addExact);
    }

    /** R-INV-4: whether month {@code k} of {@code n} ends a payout period. */
    static boolean payout(String frequency, int k, int n) {
        return switch (frequency) {
            case MONTHLY -> true;
            case QUARTERLY -> k % 3 == 0 || k == n;
            case AT_MATURITY -> k == n;
            default -> throw new IllegalArgumentException(frequency);
        };
    }

    /** Whole months and remaining days from {@code start} to {@code end}, by the R-TERM month rule. */
    record Held(int months, LocalDate monthsEnd, LocalDate end) {

        long days() {
            return ChronoUnit.DAYS.between(monthsEnd, end);
        }
    }

    static Held held(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end before start");
        }
        int months = 0;
        while (!start.plusMonths(months + 1).isAfter(end)) {
            months++;
        }
        return new Held(months, start.plusMonths(months), end);
    }

    /**
     * R-INV-5: a simple return at {@code rateBp} per year from {@code start} to {@code end}: whole
     * months at rate / 12 each, and the remaining days at rate / (days in that calendar year), so a
     * day of a leap year earns 1/366. Rounded once, half up.
     */
    static long simple(long principalMinor, int rateBp, LocalDate start, LocalDate end) {
        Held h = held(start, end);
        // Exact fraction of a year: months / 12 + the sum over calendar years of days / length.
        BigInteger numerator = BigInteger.valueOf(h.months());
        BigInteger denominator = BigInteger.valueOf(12);
        LocalDate cursor = h.monthsEnd();
        while (cursor.isBefore(end)) {
            LocalDate yearEnd = LocalDate.of(cursor.getYear() + 1, 1, 1);
            LocalDate segmentEnd = end.isBefore(yearEnd) ? end : yearEnd;
            long days = ChronoUnit.DAYS.between(cursor, segmentEnd);
            BigInteger length = BigInteger.valueOf(Year.of(cursor.getYear()).length());
            numerator = numerator.multiply(length).add(BigInteger.valueOf(days).multiply(denominator));
            denominator = denominator.multiply(length);
            cursor = segmentEnd;
        }
        return roundHalfUp(
                BigInteger.valueOf(principalMinor)
                        .multiply(BigInteger.valueOf(rateBp))
                        .multiply(numerator),
                denominator.multiply(BP));
    }

    /** The settlement of an early withdrawal (R-INV-6), all amounts in minor units. */
    record EarlySettlement(
            long principalMinor,
            long earnedMinor,
            long accruedMinor,
            long paidMinor,
            long penaltyMinor,
            long cashMinor) {

        /** Return still owed now: earned less already paid; negative when paid returns are clawed back. */
        long returnNowMinor() {
            return earnedMinor - paidMinor;
        }
    }

    /**
     * R-INV-6: the return earned to the withdrawal date under the product's rule (forfeit: none;
     * reduced rate: R-INV-5 at that rate), the penalty on principal, and the cash paid: principal
     * less penalty plus earned less already paid. The penalty is capped so the cash is never
     * negative.
     */
    static EarlySettlement early(
            long principalMinor,
            String rule,
            Integer reducedRateBp,
            int penaltyBp,
            LocalDate start,
            LocalDate withdrawal,
            long accruedMinor,
            long paidMinor) {
        long earned = switch (rule) {
            case "forfeit_return" -> 0;
            case "reduced_rate" -> simple(principalMinor, reducedRateBp, start, withdrawal);
            default -> throw new IllegalArgumentException(rule);
        };
        long penalty = roundHalfUp(BigInteger.valueOf(principalMinor).multiply(BigInteger.valueOf(penaltyBp)), BP);
        long beforePenalty = principalMinor + earned - paidMinor;
        penalty = Math.max(0, Math.min(penalty, beforePenalty));
        return new EarlySettlement(principalMinor, earned, accruedMinor, paidMinor, penalty, beforePenalty - penalty);
    }

    static long roundHalfUp(BigInteger numerator, BigInteger denominator) {
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
