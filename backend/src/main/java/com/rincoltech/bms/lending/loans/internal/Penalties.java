package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.products.ProductCatalog.PenaltyRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
     * The charges still to make on {@code businessDate}: every period whose date has come and that
     * is not in {@code charged}, in period order.
     *
     * @param charged the period numbers already charged for this item
     * @param chargedSoFar the item's penalties charged so far, for the cap
     */
    static List<Charge> due(
            Item item, PenaltyRule rule, LocalDate businessDate, Set<Integer> charged, long chargedSoFar) {
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
            if (charged.contains(k)) {
                continue;
            }
            long amount = rule.method().equals("flat_per_period")
                    ? rule.flatMinor()
                    : percent(item.unpaid("principal") + item.unpaid("interest"), rule.rateBp());
            if (cap != null) {
                amount = Math.min(amount, cap - total);
            }
            if (amount <= 0) {
                break; // the cap is reached; later periods charge nothing either
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
}
