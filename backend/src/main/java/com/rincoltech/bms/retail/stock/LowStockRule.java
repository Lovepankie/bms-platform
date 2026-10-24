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
 * The low-stock push and the Stock health page both use this rule, the page through the same comparison in its query.
 * The minimum is named first when both trip, because it is the number the owner chose.
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

    /*
     * The counts of Home and the dashboard and the Out and Low filters of the stock lists (issue #490, ADR-077) judge
     * an item only where it has been held: a balance row or a movement at the branch. A catalogue item a branch never
     * carried is neither out nor low there. Low at a branch is at or below its level: the branch's own minimum, else
     * the item's minimum for every branch, else the fixed threshold the caller binds. Over several branches together,
     * as All branches lists them, an item is low when it is low at one of them, and out when the balances summed over
     * them are at zero or below and one of them has held it. The SQL below is that one rule, shared so the figures and
     * the filters agree. Every subquery is correlated on {@code tenant} (the outer row's tenant column) as well, so it
     * seeks the (tenant_id, branch_id, product_id) indexes whatever the row-level security policy adds.
     */

    /** SQL: true when the branch has held the item, a balance row or a movement there. */
    public static String heldSql(String tenant, String product, String branch) {
        return "(EXISTS (SELECT 1 FROM retail_stock_balances held_b WHERE held_b.tenant_id = " + tenant
                + " AND held_b.branch_id = " + branch + " AND held_b.product_id = " + product + ")"
                + " OR EXISTS (SELECT 1 FROM retail_stock_movements held_m WHERE held_m.tenant_id = " + tenant
                + " AND held_m.branch_id = " + branch + " AND held_m.product_id = " + product + "))";
    }

    /**
     * SQL: the level at or below which the item is low at the branch: its own minimum, else the item's, else {@code
     * threshold}.
     */
    public static String levelSql(String tenant, String product, String branch, String threshold) {
        return "coalesce((SELECT min_b.min_qty FROM retail_stock_minimums min_b WHERE min_b.tenant_id = " + tenant
                + " AND min_b.product_id = " + product + " AND min_b.branch_id = " + branch + "),"
                + " (SELECT min_a.min_qty FROM retail_stock_minimums min_a WHERE min_a.tenant_id = " + tenant
                + " AND min_a.product_id = " + product + " AND min_a.branch_id IS NULL), " + threshold + ")";
    }

    /** SQL: true when the item is out ({@code low} false) or low at the branch, {@code qty} being its balance there. */
    public static String atBranchSql(
            boolean low, String tenant, String product, String branch, String qty, String threshold) {
        return "(" + qty + " <= " + (low ? levelSql(tenant, product, branch, threshold) : "0") + " AND "
                + heldSql(tenant, product, branch) + ")";
    }

    /**
     * SQL: true when the item is out or low over the branches of {@code scope} (a condition on the branch {@code lb})
     * together: out when {@code total}, their summed balance, is at zero or below and one of them has held it; low when
     * it is low at one of them.
     */
    public static String overBranchesSql(
            boolean low, String tenant, String product, String scope, String total, String threshold) {
        if (!low) {
            return "(" + total + " <= 0 AND EXISTS (SELECT 1 FROM branches lb WHERE lb.tenant_id = " + tenant + " AND "
                    + scope + " AND " + heldSql(tenant, product, "lb.id") + "))";
        }
        return "EXISTS (SELECT 1 FROM branches lb LEFT JOIN retail_stock_balances lq ON lq.tenant_id = lb.tenant_id"
                + " AND lq.branch_id = lb.id AND lq.product_id = " + product + " WHERE lb.tenant_id = " + tenant
                + " AND "
                + scope + " AND " + atBranchSql(true, tenant, product, "lb.id", "coalesce(lq.qty, 0)", threshold) + ")";
    }

    /** A quantity without trailing zeros: 10, 2.5. */
    public static String plain(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }
}
