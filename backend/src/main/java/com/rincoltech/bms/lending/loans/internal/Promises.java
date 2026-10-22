package com.rincoltech.bms.lending.loans.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-CLN-05, pure: a promise to pay is kept once the repayments from the day it was made to the
 * promised date reach the promised amount, and broken the day after the promised date if they
 * never did. Until then it stays pending.
 */
final class Promises {

    private Promises() {}

    /** The new status, or null while the promise is still open. */
    static String settle(long paidMinor, long promisedMinor, LocalDate promisedDate, LocalDate today) {
        if (paidMinor >= promisedMinor) {
            return "kept";
        }
        return today.isAfter(promisedDate) ? "broken" : null;
    }

    /** A promise to pay: the day it was made, in the tenant's zone, to the day promised. */
    record Promise(
            UUID id, UUID loanId, LocalDate madeOn, Instant madeAt, LocalDate promisedDate, long promisedMinor) {}

    /** A repayment or recovery on a loan that stands (not reversed), by value date. */
    record Payment(UUID loanId, LocalDate valueDate, Instant recordedAt, long amountMinor) {}

    /**
     * The status of every promise on {@code today} (pending as {@code "pending"}), with each payment counted toward
     * one promise only (the review of #272): a loan's promises, due soonest first, take the payments dated in their
     * own window, earliest first, as far as each promise needs. A promise due sooner takes its payments before a later one;
     * a payment counts only when it was recorded after the promise was made. Computed afresh every night from the payments that
     * stand, so a back-dated payment can keep a promise that was broken, and a reversal can break one that was kept.
     */
    static Map<UUID, String> settleAll(List<Promise> promises, List<Payment> payments, LocalDate today) {
        Map<UUID, String> out = new LinkedHashMap<>();
        Map<UUID, List<long[]>> left = new LinkedHashMap<>();
        List<Payment> byDate = new ArrayList<>(payments);
        byDate.sort(Comparator.comparing(Payment::valueDate));
        Map<UUID, List<Payment>> perLoan = new LinkedHashMap<>();
        for (Payment p : byDate) {
            perLoan.computeIfAbsent(p.loanId(), k -> new ArrayList<>()).add(p);
            left.computeIfAbsent(p.loanId(), k -> new ArrayList<>()).add(new long[] {p.amountMinor()});
        }
        List<Promise> ordered = new ArrayList<>(promises);
        ordered.sort(Comparator.comparing(Promise::promisedDate)
                .thenComparing(Promise::madeOn)
                .thenComparing(Promise::id));
        for (Promise promise : ordered) {
            List<Payment> ps = perLoan.getOrDefault(promise.loanId(), List.of());
            List<long[]> rest = left.getOrDefault(promise.loanId(), List.of());
            long paid = 0;
            for (int i = 0; i < ps.size() && paid < promise.promisedMinor(); i++) {
                LocalDate d = ps.get(i).valueDate();
                // Only a payment recorded after the promise was made keeps it (a payment at 10:00 never keeps a promise
                // logged at 14:00), dated from the day of the promise to the day promised: a value date entered later
                // and dated back into that window still counts (the second review of #272).
                if (d.isBefore(promise.madeOn())
                        || d.isAfter(promise.promisedDate())
                        || !ps.get(i).recordedAt().isAfter(promise.madeAt())) {
                    continue;
                }
                long take = Math.min(rest.get(i)[0], promise.promisedMinor() - paid);
                rest.get(i)[0] -= take;
                paid += take;
            }
            String status = settle(paid, promise.promisedMinor(), promise.promisedDate(), today);
            out.put(promise.id(), status == null ? "pending" : status);
        }
        return out;
    }
}
