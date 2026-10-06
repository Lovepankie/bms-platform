package com.rincoltech.bms.lending.loans.internal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The normative servicing rules of chapter 3 section 3.4 over a loan's schedule items: R-ALLOC,
 * R-PAYOFF and R-DPD, and the re-allocation that a repayment reversal needs (FR-REP-05). Pure: no
 * database and no clock, so the golden tests drive it directly with fabricated schedules. Amounts
 * are integer minor units (ADR-004); nothing here rounds, because every amount it moves is already
 * a whole number of minor units on a schedule item.
 */
final class Servicing {

    static final List<String> DEFAULT_ORDER = List.of("penalty", "fee", "interest", "principal");

    /** Allocation components that move cash; {@code interest_rebate} is a waiver, not money. */
    static final List<String> CASH_COMPONENTS = List.of("penalty", "fee", "interest", "principal", "overpayment");

    static final String REBATE = "interest_rebate";
    static final String OVERPAYMENT = "overpayment";

    private Servicing() {}

    /** One schedule item: its contracted amounts and what has been paid or waived against them. */
    static final class Item {

        final UUID id;
        final int no;
        final LocalDate dueDate;
        final long principalDue;
        final long interestDue;
        final long feesDue;
        final long penaltiesDue;
        long principalPaid;
        long interestPaid;
        long feesPaid;
        long penaltiesPaid;
        long interestWaived;
        long feesWaived;
        long penaltiesWaived;
        LocalDate paidOn;

        Item(UUID id, int no, LocalDate dueDate, long principalDue, long interestDue, long feesDue, long penaltiesDue) {
            this.id = id;
            this.no = no;
            this.dueDate = dueDate;
            this.principalDue = principalDue;
            this.interestDue = interestDue;
            this.feesDue = feesDue;
            this.penaltiesDue = penaltiesDue;
        }

        /** The same contracted amounts with nothing paid or waived: the starting point of a replay. */
        Item fresh() {
            return new Item(id, no, dueDate, principalDue, interestDue, feesDue, penaltiesDue);
        }

        long unpaid(String component) {
            return switch (component) {
                case "principal" -> principalDue - principalPaid;
                case "interest" -> interestDue - interestPaid - interestWaived;
                case "fee" -> feesDue - feesPaid - feesWaived;
                case "penalty" -> penaltiesDue - penaltiesPaid - penaltiesWaived;
                default -> throw new IllegalArgumentException(component);
            };
        }

        /** Applies one allocation row (a negative amount takes it back). */
        void settle(String component, long amount) {
            switch (component) {
                case "principal" -> principalPaid += amount;
                case "interest" -> interestPaid += amount;
                case "fee" -> feesPaid += amount;
                case "penalty" -> penaltiesPaid += amount;
                case REBATE -> interestWaived += amount;
                default -> throw new IllegalArgumentException(component);
            }
        }

        /** Principal, interest and fee unpaid: what makes an item overdue (R-DPD). */
        long unpaidContract() {
            return unpaid("principal") + unpaid("interest") + unpaid("fee");
        }

        long unpaidTotal() {
            return unpaidContract() + unpaid("penalty");
        }

        boolean settled() {
            return unpaidTotal() == 0;
        }

        long paidTotal() {
            return principalPaid + interestPaid + feesPaid + penaltiesPaid;
        }

        long waivedTotal() {
            return interestWaived + feesWaived + penaltiesWaived;
        }

        /** FR-DIS-04 statuses on business date {@code today}. */
        String status(LocalDate today) {
            if (settled()) {
                return paidTotal() == 0 && waivedTotal() > 0 ? "waived" : "paid";
            }
            if (dueDate.isBefore(today) && unpaidContract() > 0) {
                return "overdue";
            }
            if (paidTotal() > 0) {
                return "partially_paid";
            }
            return dueDate.equals(today) ? "due" : "pending";
        }
    }

    /** One allocation row: {@code itemId} is null only for {@code overpayment}. */
    record Allocation(UUID itemId, String component, long amountMinor) {

        String key() {
            return itemId + "/" + component;
        }
    }

    /**
     * R-PAYOFF as at a value date. {@code rebates} holds, per item, the unpaid interest the rebate
     * waives; {@code interestMinor} is the interest still charged.
     */
    record Quote(
            LocalDate valueDate,
            long principalMinor,
            long interestMinor,
            long feesMinor,
            long penaltiesMinor,
            long rebateMinor,
            Map<UUID, Long> rebates) {

        long totalMinor() {
            return principalMinor + interestMinor + feesMinor + penaltiesMinor;
        }
    }

    /**
     * R-PAYOFF. The flat method charges every unpaid scheduled interest unless the product rebates
     * early settlement; the declining method (and flat with the rebate) charges the interest of the
     * items due on or before the value date and of the next item after it, and rebates the rest.
     */
    static Quote payoff(List<Item> items, LocalDate valueDate, String interestMethod, boolean flatRebate) {
        boolean rebating = "declining".equals(interestMethod) || flatRebate;
        long principal = 0;
        long interest = 0;
        long fees = 0;
        long penalties = 0;
        long rebate = 0;
        Map<UUID, Long> rebates = new LinkedHashMap<>();
        boolean nextTaken = false;
        for (Item i : byDueDate(items)) {
            principal += i.unpaid("principal");
            fees += i.unpaid("fee");
            penalties += i.unpaid("penalty");
            long unpaidInterest = i.unpaid("interest");
            boolean charged = !rebating || !i.dueDate.isAfter(valueDate) || !nextTaken;
            if (rebating && i.dueDate.isAfter(valueDate)) {
                nextTaken = true;
            }
            if (charged) {
                interest += unpaidInterest;
            } else if (unpaidInterest > 0) {
                rebate += unpaidInterest;
                rebates.put(i.id, unpaidInterest);
            }
        }
        return new Quote(valueDate, principal, interest, fees, penalties, rebate, rebates);
    }

    /** What one repayment did: its rows, whether it was a payoff, and the excess held as a credit. */
    record Applied(List<Allocation> allocations, boolean payoff, long excessMinor) {}

    /**
     * R-ALLOC: applies a repayment to the items, which it changes in place. At or above the payoff
     * amount it settles everything, rebates per R-PAYOFF and holds the excess as an overpayment;
     * otherwise it pays items oldest first, each in the product's allocation order, so items not
     * yet due are reached only after every earlier item is fully paid.
     */
    static Applied apply(
            List<Item> items,
            long amountMinor,
            LocalDate valueDate,
            String interestMethod,
            boolean flatRebate,
            List<String> allocationOrder) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        List<String> order = allocationOrder == null || allocationOrder.isEmpty() ? DEFAULT_ORDER : allocationOrder;
        List<Allocation> rows = new ArrayList<>();
        Quote quote = payoff(items, valueDate, interestMethod, flatRebate);
        if (amountMinor >= quote.totalMinor()) {
            for (Item i : byDueDate(items)) {
                long rebated = quote.rebates().getOrDefault(i.id, 0L);
                for (String component : order) {
                    long amount = i.unpaid(component) - (component.equals("interest") ? rebated : 0);
                    take(rows, i, component, amount, valueDate);
                }
                take(rows, i, REBATE, rebated, valueDate);
            }
            long excess = amountMinor - quote.totalMinor();
            if (excess > 0) {
                rows.add(new Allocation(null, OVERPAYMENT, excess));
            }
            return new Applied(List.copyOf(rows), quote.totalMinor() > 0, excess);
        }
        long remaining = amountMinor;
        for (Item i : byDueDate(items)) {
            for (String component : order) {
                long amount = Math.min(remaining, i.unpaid(component));
                take(rows, i, component, amount, valueDate);
                remaining -= amount;
            }
            if (remaining == 0) {
                break;
            }
        }
        return new Applied(List.copyOf(rows), false, 0);
    }

    private static void take(List<Allocation> rows, Item i, String component, long amount, LocalDate valueDate) {
        if (amount <= 0) {
            return;
        }
        i.settle(component, amount);
        rows.add(new Allocation(i.id, component, amount));
        if (i.settled() && i.paidOn == null) {
            i.paidOn = valueDate;
        }
    }

    /** One surviving repayment, in the order it is replayed. */
    record Payment(UUID txnId, long amountMinor, LocalDate valueDate) {}

    /** The rows each payment gets when the payments are applied again from a fresh schedule. */
    record Replay(Map<UUID, List<Allocation>> allocations, List<Item> items) {}

    /**
     * FR-REP-05: applies the payments again, in value date order, to the items as contracted, so a
     * reversed repayment's money is taken out and every later repayment is re-allocated as if it
     * had never been there.
     */
    static Replay replay(
            List<Item> contracted,
            List<Payment> payments,
            String interestMethod,
            boolean flatRebate,
            List<String> allocationOrder) {
        List<Item> items = contracted.stream().map(Item::fresh).toList();
        Map<UUID, List<Allocation>> rows = new LinkedHashMap<>();
        for (Payment p : payments) {
            rows.put(
                    p.txnId(),
                    apply(items, p.amountMinor(), p.valueDate(), interestMethod, flatRebate, allocationOrder)
                            .allocations());
        }
        return new Replay(rows, items);
    }

    /** Sums rows by item and component, dropping zero sums; the insertion order is kept. */
    static Map<String, Allocation> net(List<Allocation> rows) {
        Map<String, Allocation> sums = new LinkedHashMap<>();
        for (Allocation a : rows) {
            sums.merge(
                    a.key(), a, (x, y) -> new Allocation(x.itemId(), x.component(), x.amountMinor() + y.amountMinor()));
        }
        sums.values().removeIf(a -> a.amountMinor() == 0);
        return sums;
    }

    /** {@code after} minus {@code before}, by item and component, without zero rows. */
    static List<Allocation> difference(List<Allocation> before, List<Allocation> after) {
        Map<String, Allocation> d = new LinkedHashMap<>(net(after));
        for (Allocation b : net(before).values()) {
            d.merge(
                    b.key(),
                    new Allocation(b.itemId(), b.component(), -b.amountMinor()),
                    (x, y) -> new Allocation(x.itemId(), x.component(), x.amountMinor() + y.amountMinor()));
        }
        return d.values().stream().filter(a -> a.amountMinor() != 0).toList();
    }

    /** A loan's position on a business date: balances, R-DPD arrears and the next due date. */
    record Position(
            long principalOutstandingMinor,
            long interestOutstandingMinor,
            long feesOutstandingMinor,
            long penaltiesOutstandingMinor,
            long arrearsMinor,
            int daysPastDue,
            LocalDate nextDueDate,
            boolean settled) {}

    static Position position(List<Item> items, LocalDate today) {
        long principal = 0;
        long interest = 0;
        long fees = 0;
        long penalties = 0;
        long arrears = 0;
        LocalDate oldestOverdue = null;
        LocalDate next = null;
        for (Item i : byDueDate(items)) {
            principal += i.unpaid("principal");
            interest += i.unpaid("interest");
            fees += i.unpaid("fee");
            penalties += i.unpaid("penalty");
            if (i.dueDate.isBefore(today) && i.unpaidContract() > 0) {
                arrears += i.unpaidContract();
                if (oldestOverdue == null) {
                    oldestOverdue = i.dueDate;
                }
            }
            if (next == null && !i.settled()) {
                next = i.dueDate;
            }
        }
        int dpd = oldestOverdue == null ? 0 : (int) ChronoUnit.DAYS.between(oldestOverdue, today);
        return new Position(
                principal, interest, fees, penalties, arrears, dpd, next, principal + interest + fees + penalties == 0);
    }

    private static List<Item> byDueDate(List<Item> items) {
        return items.stream()
                .sorted(Comparator.comparing((Item i) -> i.dueDate).thenComparingInt(i -> i.no))
                .toList();
    }
}
