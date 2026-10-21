package com.rincoltech.bms.retail.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * When an item at a shop or store is low (issue #412, ADR-057): two rules, whichever trips first.
 *
 * <ul>
 *   <li>the <b>minimum</b>: the stock is below the level set for the item at that branch (the branch's own level, else the
 *       item's level for every branch); an item with no level has no minimum rule;
 *   <li>the <b>pace</b>: at the pace it sold over the last {@value #PACE_DAYS} days, what is left lasts fewer than the lead
 *       days ({@value #DEFAULT_LEAD_DAYS} unless the Stock health page asks for another number); an item that did not sell in
 *       that time has no pace rule.
 * </ul>
 *
 * The low-stock push and the Stock health page both use this rule, the page through the same comparison in its query. The
 * minimum is named first when both trip, because it is the number the owner chose.
 */
public final class LowStockRule {

    /** The window of the selling pace, in days. */
    public static final int PACE_DAYS = 30;

    /** The lead days of the pace rule when nobody chose another number. */
    public static final int DEFAULT_LEAD_DAYS = 14;

    private LowStockRule() {}

    /** Which rule said the item is low, with the figure the words show. */
    public sealed interface Verdict permits BelowMinimum, RunningOut {

        /** The words after "is", for a notification or a row: "below your minimum of 10". */
        String words();
    }

    /** Below the level set for the item; {@code empty} when nothing is left at all. */
    public record BelowMinimum(BigDecimal minQty, boolean empty) implements Verdict {

        @Override
        public String words() {
            return empty ? OUT_OF_STOCK : "below your minimum of " + plain(minQty);
        }
    }

    /** At the recent pace it runs out within the lead days; {@code daysLeft} is rounded down; {@code empty} when none is left. */
    public record RunningOut(int daysLeft, boolean empty) implements Verdict {

        @Override
        public String words() {
            if (empty) {
                return OUT_OF_STOCK;
            }
            if (daysLeft < 1) {
                return "about to run out at this pace";
            }
            return "about " + daysLeft + (daysLeft == 1 ? " day" : " days") + " from running out at this pace";
        }
    }

    /** The words for a branch with none left (a balance of zero or below), whichever rule tripped. */
    public static final String OUT_OF_STOCK = "out of stock";

    /**
     * @param qty the stock at the branch (a negative balance counts as none)
     * @param minQty the level that applies at the branch, or null when none is set
     * @param soldInPace units sold at the branch over the last {@link #PACE_DAYS} days (zero when it did not sell)
     * @param leadDays the lead days of the pace rule
     * @return why the item is low, or empty when neither rule trips
     */
    public static Optional<Verdict> judge(BigDecimal qty, BigDecimal minQty, BigDecimal soldInPace, int leadDays) {
        BigDecimal left = qty.max(BigDecimal.ZERO);
        if (minQty != null && qty.compareTo(minQty) < 0) {
            return Optional.of(new BelowMinimum(minQty, left.signum() == 0));
        }
        if (soldInPace != null
                && soldInPace.signum() > 0
                && left.multiply(BigDecimal.valueOf(PACE_DAYS))
                                .compareTo(soldInPace.multiply(BigDecimal.valueOf(leadDays)))
                        < 0) {
            int days = left.multiply(BigDecimal.valueOf(PACE_DAYS))
                    .divide(soldInPace, 0, RoundingMode.DOWN)
                    .intValueExact();
            return Optional.of(new RunningOut(days, left.signum() == 0));
        }
        return Optional.empty();
    }

    /** A quantity without trailing zeros: 10, 2.5. */
    public static String plain(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }
}
