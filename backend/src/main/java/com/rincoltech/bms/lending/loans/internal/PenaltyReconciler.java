package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.products.ProductCatalog.PenaltyRule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps a loan's charged penalty periods true to its payments by value date (the review of #270, findings 1 and 2),
 * after every repayment and every reversal, under the loan's row lock, in the money's own transaction. Each charged
 * period should stand at {@link Penalties#targets}: a back-dated repayment that paid the instalment up, or lowered a
 * percent penalty's base, cancels the excess (a dated {@code lending_charge_cancels} row per charge row, never more than
 * what is neither waived nor paid); a reversal that shows a cancelled amount was owed after all charges it again as a
 * new row ({@code penalty_recharge}), never by undoing the cancel. Nothing is posted (ADR-004).
 */
@Component
class PenaltyReconciler {

    private final ServicingRepository repo;

    PenaltyReconciler(ServicingRepository repo) {
        this.repo = repo;
    }

    /** What one reconcile did. */
    record Moved(long cancelledMinor, long rechargedMinor) {}

    Moved reconcile(UUID loanId, PenaltyRule rule, List<Item> items, UUID causeTxnId, LocalDate today) {
        if (rule == null || !rule.charges()) {
            return new Moved(0, 0);
        }
        return reconcile(loanId, rule, items, causeTxnId, today, repo.contractPaidByValueDate(loanId), true, true);
    }

    /**
     * As above, with the payments by value date given. A reversal first plans with {@code write} false, on a fresh copy
     * of the items, from the replay it is about to write, so the penalties are set right BEFORE the surviving
     * repayments are allocated (the delta review of #270); it then writes with {@code adjust} false, its replayed items
     * already carrying the planned penalties.
     */
    Moved reconcile(
            UUID loanId,
            PenaltyRule rule,
            List<Item> items,
            UUID causeTxnId,
            LocalDate today,
            Map<UUID, NavigableMap<LocalDate, long[]>> paid,
            boolean write,
            boolean adjust) {
        if (rule == null || !rule.charges()) {
            return new Moved(0, 0);
        }
        Map<UUID, Map<Integer, List<ServicingRepository.PeriodRow>>> rows = new LinkedHashMap<>();
        for (ServicingRepository.PeriodRow r : repo.penaltyRows(loanId)) {
            rows.computeIfAbsent(r.itemId(), k -> new TreeMap<>())
                    .computeIfAbsent(r.periodNo(), k -> new ArrayList<>())
                    .add(r);
        }
        if (rows.isEmpty()) {
            return new Moved(0, 0);
        }
        long cancelled = 0;
        long recharged = 0;
        for (Item item : items) {
            Map<Integer, List<ServicingRepository.PeriodRow>> periods = rows.get(item.id);
            if (periods == null) {
                continue;
            }
            List<Penalties.Period> standing = new ArrayList<>();
            periods.forEach((no, list) -> {
                ServicingRepository.PeriodRow first = list.getFirst();
                long stands = 0;
                long waived = 0;
                for (ServicingRepository.PeriodRow r : list) {
                    stands += r.amountMinor() - r.cancelledMinor();
                    waived += r.waivedMinor();
                }
                standing.add(new Penalties.Period(no, first.date(), first.amountMinor(), stands, waived));
            });
            Map<Integer, Long> targets =
                    Penalties.targets(item, rule, standing, paid.getOrDefault(item.id, new TreeMap<>()));
            for (Penalties.Period p : standing) {
                long delta = targets.get(p.periodNo()) - p.standingMinor();
                List<ServicingRepository.PeriodRow> list = periods.get(p.periodNo());
                if (delta < 0) {
                    // Never more than the item's penalties nobody has paid or waived.
                    long left = Math.min(-delta, Math.max(0, item.unpaid("penalty")));
                    String reason =
                            targets.get(p.periodNo()) <= p.waivedMinor() ? "paid_before_charged" : "base_reduced";
                    for (int i = list.size() - 1; i >= 0 && left > 0; i--) {
                        ServicingRepository.PeriodRow r = list.get(i);
                        long take = Math.min(left, r.cancellable());
                        if (take > 0) {
                            if (write) {
                                repo.cancelCharge(r, loanId, take, today, reason, causeTxnId);
                            }
                            if (adjust) {
                                item.penaltyAdjusted(r.date(), -take);
                            }
                            left -= take;
                            cancelled += take;
                        }
                    }
                } else if (delta > 0) {
                    if (write) {
                        repo.rechargePeriod(list.getFirst(), loanId, delta, causeTxnId);
                    }
                    if (adjust) {
                        item.penaltyAdjusted(p.date(), delta);
                    }
                    recharged += delta;
                }
            }
        }
        return new Moved(cancelled, recharged);
    }

    /** What a replay pays of each item's principal and interest ({@code [0]}) and fees ({@code [1]}) by value date. */
    static Map<UUID, NavigableMap<LocalDate, long[]>> paidBy(Servicing.Replay replay, Map<UUID, LocalDate> valueDates) {
        Map<UUID, NavigableMap<LocalDate, long[]>> out = new LinkedHashMap<>();
        replay.allocations().forEach((txnId, rows) -> {
            LocalDate on = valueDates.get(txnId);
            for (Servicing.Allocation a : rows) {
                if (a.itemId() == null) {
                    continue;
                }
                int slot = switch (a.component()) {
                    case "principal", "interest", Servicing.REBATE -> 0;
                    case "fee" -> 1;
                    default -> -1;
                };
                if (slot >= 0) {
                    out.computeIfAbsent(a.itemId(), k -> new TreeMap<>()).computeIfAbsent(on, k -> new long[2])[slot] +=
                            a.amountMinor();
                }
            }
        });
        return out;
    }

    /**
     * Replays until the penalties a replay implies are the ones it was allocated against (the final review of #270):
     * {@code replayOf} allocates the surviving payments against planned items, {@code planFor} works out the items'
     * penalties from a replay's own payments. Converged when a pass plans the same penalties as the pass before; then
     * the last replay was allocated against exactly the penalties its payments imply. Not converged within
     * {@code maxPasses}: a 409 {@code reversal_not_settled}, thrown before the caller writes anything, so the whole
     * reversal rolls back.
     */
    static Servicing.Replay settleReplay(
            List<Item> start,
            java.util.function.Function<List<Item>, Servicing.Replay> replayOf,
            java.util.function.Function<Servicing.Replay, List<Item>> planFor,
            int maxPasses) {
        List<Item> planned = start;
        Servicing.Replay replay = replayOf.apply(planned);
        for (int pass = 0; pass < maxPasses; pass++) {
            List<Item> next = planFor.apply(replay);
            if (penaltiesOf(next).equals(penaltiesOf(planned))) {
                return replay;
            }
            planned = next;
            replay = replayOf.apply(planned);
        }
        throw new com.rincoltech.bms.kernel.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                "reversal_not_settled",
                "Reversal could not be settled",
                "The penalties and the repayments that remain did not settle; nothing was reversed. Tell support.");
    }

    /** Each item's penalties due and dated charges: what a plan is compared on. */
    static Map<UUID, List<Object>> penaltiesOf(List<Item> items) {
        Map<UUID, List<Object>> out = new LinkedHashMap<>();
        for (Item i : items) {
            out.put(
                    i.id,
                    List.of(i.penaltiesDue, i.penaltyCharges == null ? Map.of() : new TreeMap<>(i.penaltyCharges)));
        }
        return out;
    }
}
