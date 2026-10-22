package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.Servicing.Applied;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Position;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * An existing loan brought in from a paper register (ADR-071, FR-EXL-02): the past repayments applied to its schedule,
 * which runs from the real disbursement date, in value date order (then the order they were entered) and each by the
 * product's allocation order (R-ALLOC), as if the app had recorded them on the day. The schedule carries no penalty:
 * none is charged for the time before the loan was brought in. Pure: no database and no clock, so the preview, the
 * import's check and the approval all compute the same thing.
 */
final class BringIn {

    private BringIn() {}

    /** One repayment the register shows, as entered; {@code lineNo} is its place in the entry, from 1. */
    record Line(int lineNo, LocalDate valueDate, long amountMinor, String paymentMethodKey, String note) {}

    /** One line applied: what it paid, item by item and component by component. */
    record Paid(Line line, Applied applied) {}

    /**
     * The loan as the register leaves it on {@code today}: the items with what was paid on them, each line's rows, the
     * position (balances, arrears, days past due from the real due dates, next due date) and the overpayment, which a
     * caller refuses.
     */
    record Plan(List<Item> items, List<Paid> paid, Position position, long totalPaidMinor, LocalDate lastRepaymentOn) {

        /** The first line that paid more than the loan still owed, or null. */
        Line overpaid() {
            return paid.stream()
                    .filter(p -> p.applied().excessMinor() > 0)
                    .map(Paid::line)
                    .findFirst()
                    .orElse(null);
        }

        /** The day the last item was settled, when the register shows the loan repaid in full. */
        LocalDate settledOn() {
            return position.settled() ? lastRepaymentOn : null;
        }
    }

    /** Fresh items for a computed schedule, for a preview or a check; the approval uses the stored items instead. */
    static List<Item> items(List<ScheduleCalculator.Item> schedule) {
        return schedule.stream()
                .map(i -> new Item(
                        UUID.randomUUID(), i.no(), i.dueDate(), i.principalMinor(), i.interestMinor(), i.feeMinor(), 0))
                .toList();
    }

    /** The lines in the order they are applied: value date, then entry order. */
    static List<Line> inOrder(List<Line> lines) {
        return lines.stream()
                .sorted(Comparator.comparing(Line::valueDate).thenComparingInt(Line::lineNo))
                .toList();
    }

    /** Applies the lines to {@code items}, which it changes in place, and reads the position on {@code today}. */
    static Plan apply(
            List<Item> items,
            List<Line> lines,
            String interestMethod,
            boolean flatRebate,
            List<String> allocationOrder,
            LocalDate today) {
        List<Paid> paid = new ArrayList<>();
        long total = 0;
        LocalDate last = null;
        for (Line line : inOrder(lines)) {
            Applied applied = Servicing.apply(
                    items, line.amountMinor(), line.valueDate(), interestMethod, flatRebate, allocationOrder);
            paid.add(new Paid(line, applied));
            total = Math.addExact(total, line.amountMinor());
            last = line.valueDate();
        }
        return new Plan(items, List.copyOf(paid), Servicing.position(items, today), total, last);
    }
}
