package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.retail.reports.internal.ReportsRepository.DayFigures;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The recognised sales and cost of credit sales under the tenant setting {@code credit_sale_recognition = on_payment}
 * (ADR-043). A credit sale counts only as the buyer pays, and a part payment counts a part of the sale's profit.
 *
 * <p>For one sale and the end of a day, with {@code total} and {@code cost} from the sale, {@code returned} and
 * {@code returnedCost} the value and cost of the returns that stand, {@code refunded} the money given back on them and
 * {@code paid} the payments received less the payments reversed:
 *
 * <pre>
 *   net      = total - returned                     what the sale is worth now
 *   kept     = paid - refunded                      money the shop keeps for it
 *   revenue  = min(net, kept), at least 0           received
 *   cost     = round_half_up(revenue * (cost - returnedCost) / net)
 *   profit   = revenue - cost
 * </pre>
 *
 * Each of the two figures is cumulative, and the figure of a day is the change since the sale's last earlier event, so
 * the days add up exactly to the whole and an early payment is never recounted. With no return this is the payment times
 * the sale's profit margin. Only integers: the one division is rounded half up once on the cumulative amount.
 */
final class CreditRecognition {

    private CreditRecognition() {}

    /** One day of one credit sale: the sums of what happened on it (see {@code ReportsRepository}'s event query). */
    record Event(
            UUID saleId,
            UUID branchId,
            long totalMinor,
            long costMinor,
            LocalDate day,
            long paidMinor,
            long returnedMinor,
            long returnedCostMinor,
            long refundedMinor) {}

    /** The cumulative figures of one sale at the end of a day. */
    record State(long revenue, long cost) {}

    static State state(long total, long cost, long paid, long returned, long returnedCost, long refunded) {
        long net = total - returned;
        long kept = paid - refunded;
        long revenue = Math.max(0, Math.min(net, kept));
        long remainingCost = Math.max(0, cost - returnedCost);
        long recognisedCost = net <= 0 || revenue == 0
                ? 0
                : BigDecimal.valueOf(revenue)
                        .multiply(BigDecimal.valueOf(remainingCost))
                        .divide(BigDecimal.valueOf(net), 0, RoundingMode.HALF_UP)
                        .longValueExact();
        return new State(revenue, recognisedCost);
    }

    /**
     * The change of each sale's figures on each day of the window, summed per branch and day, as report figures (sales =
     * received, cost of sales = its cost, usage 0). {@code events} must be ordered by sale then day, as the query returns
     * them and hold every event up to the end of the window.
     */
    static List<DayFigures> deltas(List<Event> events, LocalDate from, LocalDate to) {
        return deltas(events, from, to, null);
    }

    /**
     * As above, for the events of the branches in {@code branches} (null: all). Every event of a sale moves its running figures, but
     * a change is credited to the branch of the event that caused it (the branch that took the payment), and only reported when
     * that branch is in scope.
     */
    static List<DayFigures> deltas(List<Event> events, LocalDate from, LocalDate to, java.util.Set<UUID> branches) {
        Map<String, long[]> out = new LinkedHashMap<>();
        Map<String, UUID> branchOf = new LinkedHashMap<>();
        Map<String, LocalDate> dayOf = new LinkedHashMap<>();
        UUID current = null;
        long paid = 0;
        long returned = 0;
        long returnedCost = 0;
        long refunded = 0;
        // What each branch took of this sale, so that a return credited to the buyer's balance (no cash out) is spread
        // over them.
        Map<UUID, Long> paidBy = new LinkedHashMap<>();
        State before = new State(0, 0);
        for (Event e : events) {
            if (!e.saleId().equals(current)) {
                current = e.saleId();
                paid = 0;
                returned = 0;
                returnedCost = 0;
                refunded = 0;
                paidBy = new LinkedHashMap<>();
                before = new State(0, 0);
            }
            paid = Math.addExact(paid, e.paidMinor());
            returned = Math.addExact(returned, e.returnedMinor());
            returnedCost = Math.addExact(returnedCost, e.returnedCostMinor());
            refunded = Math.addExact(refunded, e.refundedMinor());
            if (e.paidMinor() != 0) {
                paidBy.merge(e.branchId(), e.paidMinor(), Long::sum);
            }
            State after = state(e.totalMinor(), e.costMinor(), paid, returned, returnedCost, refunded);
            long dRevenue = after.revenue() - before.revenue();
            long dCost = after.cost() - before.cost();
            before = after;
            if (e.day().isBefore(from) || e.day().isAfter(to)) {
                continue;
            }
            // Cash went back out of the branch of the event (the refund's), so that branch takes the change. A return
            // credited to
            // the balance paid nothing out: its change goes to the branches that took the sale's money, in proportion
            // to it.
            List<Map.Entry<UUID, Long>> takers = new ArrayList<>();
            if (e.returnedMinor() != 0 && e.refundedMinor() == 0 && e.paidMinor() == 0) {
                paidBy.entrySet().stream()
                        .filter(x -> x.getValue() > 0)
                        .sorted(Map.Entry.<UUID, Long>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .forEach(takers::add);
            }
            if (takers.isEmpty()) {
                add(out, branchOf, dayOf, e.day(), e.branchId(), dRevenue, dCost, branches);
            } else {
                long total = takers.stream().mapToLong(Map.Entry::getValue).sum();
                long[] revenue = spread(dRevenue, takers, total);
                long[] cost = spread(dCost, takers, total);
                for (int i = 0; i < takers.size(); i++) {
                    add(out, branchOf, dayOf, e.day(), takers.get(i).getKey(), revenue[i], cost[i], branches);
                }
            }
        }
        List<DayFigures> rows = new ArrayList<>();
        out.forEach((key, f) -> rows.add(new DayFigures(branchOf.get(key), dayOf.get(key), f[0], f[1], 0)));
        return rows;
    }

    private static void add(
            Map<String, long[]> out,
            Map<String, UUID> branchOf,
            Map<String, LocalDate> dayOf,
            LocalDate day,
            UUID branch,
            long revenue,
            long cost,
            java.util.Set<UUID> branches) {
        if (branches != null && !branches.contains(branch)) {
            return;
        }
        String key = day + "|" + branch;
        branchOf.putIfAbsent(key, branch);
        dayOf.putIfAbsent(key, day);
        long[] f = out.computeIfAbsent(key, k -> new long[2]);
        f[0] = Math.addExact(f[0], revenue);
        f[1] = Math.addExact(f[1], cost);
    }

    /**
     * {@code amount} shared out in proportion to the weights, in whole units: each takes the rounded-down share and the first (the
     * one that took most) takes what is left, so the shares always add up to the amount.
     */
    static long[] spread(long amount, List<Map.Entry<UUID, Long>> weights, long total) {
        long[] shares = new long[weights.size()];
        long given = 0;
        for (int i = 0; i < shares.length; i++) {
            shares[i] = java.math.BigInteger.valueOf(amount)
                    .multiply(java.math.BigInteger.valueOf(weights.get(i).getValue()))
                    .divide(java.math.BigInteger.valueOf(total))
                    .longValueExact();
            given = Math.addExact(given, shares[i]);
        }
        shares[0] = Math.addExact(shares[0], amount - given);
        return shares;
    }
}
