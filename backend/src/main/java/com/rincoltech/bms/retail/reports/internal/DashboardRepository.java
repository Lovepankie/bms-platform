package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.retail.stock.CreditPeriods;
import com.rincoltech.bms.retail.stock.LowStockRule;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL of the owner dashboard (issue #149): thirty days of sales by branch, day and way of
 * paying (bounded by the date window and the branch scope), and the stock position per branch (the
 * active items against the branches in scope; one row a branch comes back).
 */
@Repository
class DashboardRepository {

    /** SQL: true for a branch the stock figures report, active or closed and still holding stock (issue #490). */
    private static String reportedSql(String br) {
        return "(" + br + ".status = 'active' OR EXISTS (SELECT 1 FROM retail_stock_balances h WHERE h.tenant_id = "
                + br + ".tenant_id AND h.branch_id = " + br + ".id AND h.qty <> 0))";
    }

    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record DaySales(UUID branchId, LocalDate day, boolean credit, long sales, long cost, long saleCount) {}

    /** {@code out} and {@code low} are null for a branch that has held no item: it shows no stock line (issue #490). */
    record Position(UUID branchId, long atPrice, long atCost, Integer out, Integer low) {}

    List<DaySales> sales(List<UUID> branchIds, LocalDate from, LocalDate to) {
        return sales(branchIds, from, to, CreditPeriods.NONE);
    }

    /**
     * As above; a credit sale that is not an imported one and counts from the start day of {@code periods} on (the
     * later of its date and the day it was entered; counted when the buyer pays, ADR-043) is left out, with the lines
     * its returns took back, since it reaches the figures through the payments.
     */
    List<DaySales> sales(List<UUID> branchIds, LocalDate from, LocalDate to, CreditPeriods periods) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND s.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT s.branch_id, s.sale_date AS day, (s.payment_method = 'credit') AS credit,"
                        + " sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost,"
                        + " count(DISTINCT s.id) AS sale_count"
                        + "  FROM " + SalesAnalysisRepository.NET_LINES
                        + " WHERE s.status = 'completed' AND s.sale_date BETWEEN :from AND :to" + branch
                        + (periods.isEmpty()
                                ? ""
                                : " AND (s.payment_method <> 'credit' OR s.historical OR NOT "
                                        + periods.sql("s.origin_date", "s.origin_created_at", params, "crpD") + ")")
                        + " GROUP BY s.branch_id, s.sale_date, (s.payment_method = 'credit')")
                .params(params)
                .query((rs, n) -> new DaySales(
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("day").toLocalDate(),
                        rs.getBoolean("credit"),
                        rs.getLong("sales"),
                        rs.getLong("cost"),
                        rs.getLong("sale_count")))
                .list();
    }

    /** The first day with a completed sale in the branches (every branch when null), or null with none: where All starts (issue #428). */
    LocalDate firstSaleDay(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND branch_id IN (:branchIds)";
        }
        // min() over no rows is one row holding NULL, which single() refuses; optional() maps it to no value.
        return jdbc.sql("SELECT min(sale_date) FROM retail_sales WHERE status = 'completed'" + branch)
                .params(params)
                .query(Date.class)
                .optional()
                .map(Date::toLocalDate)
                .orElse(null);
    }

    /**
     * Stock value above zero per branch (active items) and, per branch, the items out of stock or low by the stock
     * list's rule (issue #490, {@link LowStockRule}): an active item counts only at a branch that has held it, low at
     * or below the branch's minimum, else {@code lowThreshold} (an inactive item, issue #342, is left out like the list
     * leaves it out). A branch that has held no item keeps its row, with null counts, so its shop line stays and shows
     * no stock figures.
     */
    List<Position> positions(List<UUID> branchIds, BigDecimal lowThreshold) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("low", lowThreshold);
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND br.id IN (:branchIds)";
        }
        // A MATERIALIZED CTE keeps x apart, so held and level are probed once a row, not once an aggregate (flattened
        // it took
        // 1.4 s on the 25x bench, ADR-077).
        return jdbc.sql("WITH x AS MATERIALIZED ("
                        + "SELECT br.id AS branch_id, p.sell_minor, coalesce(b.avg_cost_minor, 0) AS cost_minor, coalesce(b.qty, 0) AS qty,"
                        + "       " + LowStockRule.heldSql("p.tenant_id", "p.id", "br.id") + " AS held,"
                        + "       " + LowStockRule.levelSql("p.tenant_id", "p.id", "br.id", ":low") + " AS level"
                        + "  FROM branches br"
                        + "  CROSS JOIN retail_products p"
                        + "  LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.product_id = p.id"
                        + "   AND b.branch_id = br.id"
                        + " WHERE p.active AND p.tenant_id = br.tenant_id AND " + reportedSql("br")
                        + branch
                        + ")"
                        + " SELECT x.branch_id,"
                        + " coalesce(sum(round(x.qty * x.sell_minor)) FILTER (WHERE x.qty > 0), 0) AS at_price,"
                        + " coalesce(sum(round(x.qty * x.cost_minor)) FILTER (WHERE x.qty > 0), 0) AS at_cost,"
                        + " CASE WHEN bool_or(x.held) THEN count(*) FILTER (WHERE x.held AND x.qty <= 0)::int END"
                        + " AS out_of_stock,"
                        + " CASE WHEN bool_or(x.held) THEN count(*) FILTER (WHERE x.held AND x.qty <= x.level)::int END"
                        + " AS low_stock"
                        + "  FROM x GROUP BY x.branch_id")
                .params(params)
                .query((rs, n) -> new Position(
                        rs.getObject("branch_id", UUID.class),
                        rs.getLong("at_price"),
                        rs.getLong("at_cost"),
                        rs.getObject("out_of_stock", Integer.class),
                        rs.getObject("low_stock", Integer.class)))
                .list();
    }

    /**
     * Items out of stock and low over the given branches, as the stock list judges them (issue #490, {@link
     * LowStockRule}): an item counts only where it has been held; out when its balance summed over the branches is at
     * zero or below, low when it is low at one of them. Only the branches {@link #positions} reports are judged
     * (active, or closed and still holding stock; every such branch when null), so a closed and emptied shop counts for
     * nothing, as it has no shop line and no column in All branches. Returns {out, low}; low includes out.
     */
    int[] counts(List<UUID> branchIds, BigDecimal lowThreshold) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new int[] {0, 0};
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("low", lowThreshold);
        String branch = "";
        String scope = reportedSql("lb");
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND b.branch_id IN (:branchIds)";
            scope += " AND lb.id IN (:branchIds)";
        }
        return jdbc.sql("SELECT count(*) FILTER (WHERE "
                        + LowStockRule.overBranchesSql(false, "t.tenant_id", "t.id", scope, "t.total", ":low")
                        + ") AS out_of_stock,"
                        + " count(*) FILTER (WHERE "
                        + LowStockRule.overBranchesSql(true, "t.tenant_id", "t.id", scope, "t.total", ":low")
                        + ") AS low_stock"
                        + "  FROM (SELECT p.tenant_id, p.id, coalesce(sum(b.qty), 0) AS total"
                        + "          FROM retail_products p"
                        + "          LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id"
                        + " AND b.product_id = p.id"
                        + branch
                        + "         WHERE p.active"
                        + "         GROUP BY p.tenant_id, p.id) t")
                .params(params)
                .query((rs, n) -> new int[] {rs.getInt("out_of_stock"), rs.getInt("low_stock")})
                .single();
    }
}
