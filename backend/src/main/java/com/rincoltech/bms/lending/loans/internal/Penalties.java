package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.products.ProductCatalog.PenaltyRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * R-PEN (chapter 3 section 3.4) as a pure function: which penalty periods of one schedule item are
 * due on a business date and how much each charges. Period 1 is charged on the first day after the
 * grace days end, period k one period unit later each time, while the item is overdue. Each charge
 * is never larger than the room left under the cap. No database and no clock, so the tests drive
 * it directly.
 */
final class Penalties {

    private static final BigDecimal BP = BigDecimal.valueOf(10_000);

    private Penalties() {}

    /** One penalty period to charge. */
    record Charge(int periodNo, LocalDate chargeDate, long amountMinor) {}

    /**
     * The charges still to make on {@code businessDate}: every period whose date has come, is on or
     * after {@code from}, and is not in {@code charged}, in period order. Periods dated before
     * {@code from} (the first arrears run of the business) are never charged, so switching the run on
     * does not charge a long overdue item every period since its due date in one night (the review of
     * #270, finding 2). A missed night is caught up with each period on its own date. The percent
     * base is what is unpaid now, which is never more than it was on an earlier period's date, so a
     * late run never charges more than a punctual one would have; and an item no longer overdue is
     * charged nothing, even for a missed period (the safer side for the borrower, finding 3).
     *
     * @param charged the period numbers already charged for this item, cancelled ones included
     * @param chargedSoFar the item's penalties charged so far, net of cancels, waivers included, for the cap
     */
    static List<Charge> due(
            Item item,
            PenaltyRule rule,
            LocalDate businessDate,
            LocalDate from,
            Set<Integer> charged,
            long chargedSoFar) {
        List<Charge> out = new ArrayList<>();
        if (rule == null || !rule.charges() || !overdue(item, businessDate)) {
            return out;
        }
        Long cap = rule.capBp() == null ? null : percent(item.principalDue + item.interestDue, rule.capBp());
        long total = chargedSoFar;
        LocalDate first = item.dueDate.plusDays(rule.graceDays() + 1L);
        for (int k = 1; ; k++) {
            LocalDate on = step(first, rule.periodUnit(), k - 1);
            if (on.isAfter(businessDate)) {
                break;
            }
            if (charged.contains(k) || on.isBefore(from)) {
                continue;
            }
            long amount = rule.method().equals("flat_per_period")
                    ? rule.flatMinor()
                    : percent(item.unpaid("principal") + item.unpaid("interest"), rule.rateBp());
            if (cap != null) {
                amount = Math.min(amount, cap - total);
            }
            if (amount <= 0) {
                break; // the cap is reached, or the percent rounds to nothing; later periods charge nothing either
            }
            out.add(new Charge(k, on, amount));
            total = Math.addExact(total, amount);
        }
        return out;
    }

    /** R-DPD: due before the business date with principal, interest or fees unpaid. */
    static boolean overdue(Item item, LocalDate businessDate) {
        return item.dueDate.isBefore(businessDate) && item.unpaidContract() > 0;
    }

    private static LocalDate step(LocalDate first, String unit, int n) {
        return switch (unit) {
            case "day" -> first.plusDays(n);
            case "week" -> first.plusWeeks(n);
            default -> first.plusMonths(n);
        };
    }

    /** {@code bp} of an amount, R-ROUND half up to the minor unit. */
    static long percent(long amountMinor, int bp) {
        return BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(bp))
                .divide(BP, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * One charged period of an item as it stands: the first charge's amount ({@code chargedMinor}, never exceeded),
     * what stands of its rows net of cancels ({@code standingMinor}, waivers included), and what was waived on it.
     */
    record Period(int periodNo, LocalDate date, long chargedMinor, long standingMinor, long waivedMinor) {}

    /**
     * What each charged period should stand at once the item's payments are known by value date (the review of #270):
     * a period is owed only when the item was overdue at the start of its date (payments dated before it counted), a
     * percent penalty on the principal and interest unpaid then, under the cap in period order; never more than was
     * first charged, and never less than what was waived on it. A back-dated payment lowers it (the excess is
     * cancelled); a reversal raises it again (charged again), up to what was first charged.
     *
     * @param paidBefore principal and interest paid ({@code [0]}) and fees paid ({@code [1]}) by value date
     */
    static Map<Integer, Long> targets(
            Item item, PenaltyRule rule, List<Period> periods, NavigableMap<LocalDate, long[]> paidBefore) {
        Map<Integer, Long> out = new TreeMap<>();
        Long cap = rule.capBp() == null ? null : percent(item.principalDue + item.interestDue, rule.capBp());
        long total = 0;
        List<Period> ordered = new ArrayList<>(periods);
        ordered.sort(Comparator.comparingInt(Period::periodNo));
        for (Period p : ordered) {
            long pi = 0;
            long fees = 0;
            for (long[] v : paidBefore.headMap(p.date(), false).values()) {
                pi += v[0];
                fees += v[1];
            }
            long baseUnpaid = item.principalDue + item.interestDue - pi;
            boolean overdue = item.dueDate.isBefore(p.date()) && baseUnpaid + item.feesDue - fees > 0;
            long correct = !overdue
                    ? 0
                    : rule.method().equals("flat_per_period")
                            ? rule.flatMinor()
                            : percent(Math.max(0, baseUnpaid), rule.rateBp());
            if (cap != null) {
                correct = Math.max(0, Math.min(correct, cap - total));
            }
            total += correct;
            out.put(p.periodNo(), Math.max(p.waivedMinor(), Math.min(correct, p.chargedMinor())));
        }
        return out;
    }
}
