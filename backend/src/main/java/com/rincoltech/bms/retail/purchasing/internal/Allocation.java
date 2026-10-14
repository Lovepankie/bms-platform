package com.rincoltech.bms.retail.purchasing.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The arithmetic of landed cost and supplier price credits, in exact integers and nothing else (ADR-033, issue #199 amendment, issue
 * #199). Two jobs:
 *
 * <p>{@link #spread} divides a total over lines in proportion to a basis (the value of the received lines, their quantity in
 * thousandths, or a weight in thousandths). Every line takes the floor of its exact proportion, and the units left over
 * (always fewer than the lines) go one each to the lines with the largest fractional remainder; on a tie the line with the
 * larger basis comes first, then the earlier line. So the shares always add up to the total, no share is negative, a line
 * with a zero basis takes nothing, and the same input always gives the same answer. This is the rule of the shop's
 * payments across branches ({@code BranchSplit}), not the half up per line of the first design, which can make the
 * parts exceed the total.
 *
 * <p>{@link #onHandPart} splits one line's share between the goods still on hand and the goods already gone: the part for
 * the units on hand is the share times those units over all the units, rounded half up once, and the rest belongs to the units
 * that were sold, used or moved.
 */
final class Allocation {

    private Allocation() {}

    /**
     * @param total at least zero
     * @param basis one non-negative weight per line, at least one of them positive when the total is positive
     * @return one share per line, in the order given, adding up to {@code total}
     * @throws IllegalArgumentException when the total is positive and every basis is zero
     */
    static long[] spread(long total, long[] basis) {
        long[] out = new long[basis.length];
        if (total < 0) {
            throw new IllegalArgumentException("total " + total + " is negative");
        }
        BigInteger sum = BigInteger.ZERO;
        for (long b : basis) {
            if (b < 0) {
                throw new IllegalArgumentException("a basis is negative");
            }
            sum = sum.add(BigInteger.valueOf(b));
        }
        if (total == 0) {
            return out;
        }
        if (sum.signum() == 0) {
            throw new IllegalArgumentException("every basis is zero");
        }
        BigInteger t = BigInteger.valueOf(total);
        record Part(int index, long basis, BigInteger remainder) {}
        List<Part> parts = new ArrayList<>();
        long given = 0;
        for (int i = 0; i < basis.length; i++) {
            BigInteger[] qr = t.multiply(BigInteger.valueOf(basis[i])).divideAndRemainder(sum);
            out[i] = qr[0].longValueExact();
            given += out[i];
            parts.add(new Part(i, basis[i], qr[1]));
        }
        long left = total - given;
        parts.sort(Comparator.comparing(Part::remainder)
                .reversed()
                .thenComparing(Comparator.comparingLong(Part::basis).reversed())
                .thenComparingInt(Part::index));
        for (int i = 0; i < left; i++) {
            out[parts.get(i).index()]++;
        }
        return out;
    }

    /** The result of {@link #onHandPart}: the share that belongs to goods on hand and the share already gone. */
    record Split(long inventory, long gone) {}

    /**
     * @param share at least zero
     * @param onHand the units of the line still on hand, between zero and {@code qty}
     * @param qty the units of the line, positive
     */
    static Split onHandPart(long share, BigDecimal onHand, BigDecimal qty) {
        if (qty.signum() <= 0 || onHand.signum() < 0 || onHand.compareTo(qty) > 0 || share < 0) {
            throw new IllegalArgumentException("bad split " + share + " " + onHand + "/" + qty);
        }
        if (onHand.compareTo(qty) == 0) {
            return new Split(share, 0);
        }
        long inventory = BigDecimal.valueOf(share)
                .multiply(onHand)
                .divide(qty, 0, RoundingMode.HALF_UP)
                .longValueExact();
        return new Split(inventory, share - inventory);
    }

    /** The first-in first-out rule for the units of one received line still on hand (ADR-033, issue #199 amendment). */
    static BigDecimal remaining(BigDecimal balance, BigDecimal laterInflow, BigDecimal qty) {
        BigDecimal left = balance.subtract(laterInflow);
        if (left.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return left.min(qty);
    }
}
