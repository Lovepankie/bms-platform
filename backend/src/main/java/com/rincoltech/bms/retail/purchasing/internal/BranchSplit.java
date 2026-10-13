package com.rincoltech.bms.retail.purchasing.internal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Splits a payment against one open item over the branches that owe it (ADR-033, issue #198). A credit restock credits trade
 * creditors in each branch its stock went into, so a payment must debit each of those branches by its share, or a branch is
 * left with a debit balance and another with a credit. The split is in proportion to what each branch still owes, in exact
 * integers: every branch takes the floor of its proportion, and the units left over go, one each, to the branches with the
 * largest remainder (the lowest id first on a tie). A branch never takes more than it owes, the parts add up to the amount,
 * and paying what is left settles every branch exactly. Splitting by what is still owed, not by what was first credited,
 * is what keeps later part payments exact.
 */
final class BranchSplit {

    private BranchSplit() {}

    /**
     * @param owed what each branch still owes on the item, each at least zero
     * @param amount at most the sum of {@code owed}
     */
    static Map<UUID, Long> split(Map<UUID, Long> owed, long amount) {
        Map<UUID, Long> out = new TreeMap<>();
        long total = owed.values().stream().mapToLong(Long::longValue).sum();
        if (amount < 0 || amount > total) {
            throw new IllegalArgumentException("amount " + amount + " is not within " + total);
        }
        if (amount == 0) {
            return out;
        }
        BigInteger a = BigInteger.valueOf(amount);
        BigInteger t = BigInteger.valueOf(total);
        record Part(UUID branch, long floor, BigInteger remainder) {}
        List<Part> parts = new ArrayList<>();
        long given = 0;
        for (Map.Entry<UUID, Long> e : new TreeMap<>(owed).entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            BigInteger[] qr = a.multiply(BigInteger.valueOf(e.getValue())).divideAndRemainder(t);
            parts.add(new Part(e.getKey(), qr[0].longValueExact(), qr[1]));
            given += qr[0].longValueExact();
        }
        long left = amount - given;
        List<Part> byRemainder = new ArrayList<>(parts);
        byRemainder.sort(Comparator.comparing(Part::remainder).reversed().thenComparing(Part::branch));
        Map<UUID, Long> extra = new TreeMap<>();
        for (int i = 0; i < left; i++) {
            extra.merge(byRemainder.get(i).branch(), 1L, Long::sum);
        }
        for (Part p : parts) {
            long v = p.floor() + extra.getOrDefault(p.branch(), 0L);
            if (v > 0) {
                out.put(p.branch(), v);
            }
        }
        return out;
    }
}
