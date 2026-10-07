package com.rincoltech.bms.lending.savings.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * The savings interest rules of FR-SAV-05, pure and exact (ADR-004, R-ROUND). Interest is the sum
 * of exact daily (or monthly) amounts, computed as one integer numerator over one denominator and
 * rounded half up to the minor unit once, at posting: never a rounded amount per day.
 *
 * <ul>
 *   <li>{@code daily_balance}: the sum over the period's days of end-of-day balance x rate / 365.
 *       A day whose balance is below the product's minimum for interest earns nothing.
 *   <li>{@code minimum_monthly_balance}: for each calendar month of the period, the lowest end-of-day
 *       balance x rate / 12. A month earns only when the account was open on every day of it and
 *       the period covers all of it (ADR-032), and only when that lowest balance reaches the
 *       minimum for interest.
 * </ul>
 */
final class Interest {

    private static final BigInteger BP = BigInteger.valueOf(10_000);
    private static final BigInteger DAYS = BigInteger.valueOf(365);
    private static final BigInteger MONTHS = BigInteger.valueOf(12);

    private Interest() {}

    /**
     * The interest of one period.
     *
     * @param balances end-of-day balance for every day from {@code start} to {@code end}
     * @param openedOn the account's opening date
     * @throws IllegalStateException when a day of the period has no end-of-day balance
     */
    static long forPeriod(
            String calc,
            int rateBp,
            long minForInterest,
            LocalDate openedOn,
            LocalDate start,
            LocalDate end,
            Map<LocalDate, Long> balances) {
        return switch (calc) {
            case "none" -> 0;
            case "daily_balance" -> halfUp(dailyNumerator(rateBp, minForInterest, start, end, balances), DAYS);
            case "minimum_monthly_balance" ->
                halfUp(monthlyNumerator(rateBp, minForInterest, openedOn, start, end, balances), MONTHS);
            default -> throw new IllegalArgumentException("unknown interest calculation " + calc);
        };
    }

    /** Sum of balance x rate in basis points over the days; divide by 10 000 x 365 for the amount. */
    static BigInteger dailyNumerator(
            int rateBp, long minForInterest, LocalDate start, LocalDate end, Map<LocalDate, Long> balances) {
        BigInteger sum = BigInteger.ZERO;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            long b = balance(balances, d);
            if (b > 0 && b >= minForInterest) {
                sum = sum.add(BigInteger.valueOf(b));
            }
        }
        return sum.multiply(BigInteger.valueOf(rateBp));
    }

    /** Sum of the qualifying months' lowest balance x rate in basis points; divide by 10 000 x 12. */
    static BigInteger monthlyNumerator(
            int rateBp,
            long minForInterest,
            LocalDate openedOn,
            LocalDate start,
            LocalDate end,
            Map<LocalDate, Long> balances) {
        BigInteger sum = BigInteger.ZERO;
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
            LocalDate first = m.atDay(1);
            LocalDate last = m.atEndOfMonth();
            if (first.isBefore(start) || last.isAfter(end) || openedOn.isAfter(first)) {
                continue;
            }
            long min = Long.MAX_VALUE;
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                min = Math.min(min, balance(balances, d));
            }
            if (min > 0 && min >= minForInterest) {
                sum = sum.add(BigInteger.valueOf(min));
            }
        }
        return sum.multiply(BigInteger.valueOf(rateBp));
    }

    /** {@code numerator / (10 000 x divisor)}, rounded half up to a whole minor unit (R-ROUND). */
    static long halfUp(BigInteger numerator, BigInteger divisor) {
        return new BigDecimal(numerator)
                .divide(new BigDecimal(BP.multiply(divisor)), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** The posting period end on or after {@code day} for a posting frequency. */
    static LocalDate periodEnd(String posting, LocalDate day) {
        return switch (posting) {
            case "monthly" -> YearMonth.from(day).atEndOfMonth();
            case "quarterly" ->
                YearMonth.of(day.getYear(), ((day.getMonthValue() - 1) / 3 + 1) * 3)
                        .atEndOfMonth();
            case "yearly" -> LocalDate.of(day.getYear(), 12, 31);
            default -> throw new IllegalArgumentException("unknown posting frequency " + posting);
        };
    }

    static boolean isPeriodEnd(String posting, LocalDate day) {
        return periodEnd(posting, day).equals(day);
    }

    private static long balance(Map<LocalDate, Long> balances, LocalDate d) {
        Long b = balances.get(d);
        if (b == null) {
            throw new IllegalStateException("no end-of-day balance for " + d);
        }
        return b;
    }
}
