package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.retail.stock.CreditPeriods;
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

    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record DaySales(UUID branchId, LocalDate day, boolean credit, long sales, long cost, long saleCount) {}

    record Position(UUID branchId, long atPrice, long atCost, int out, int low) {}

    List<DaySales> sales(List<UUID> branchIds, LocalDate from, LocalDate to) {
        return sales(branchIds, from, to, CreditPeriods.NONE);
    }

    /**
     * As above; a credit sale that is not an imported one and counts from the start day of {@code periods} on (the later of its date
     * and the day it was entered; counted when the
     * buyer pays, ADR-043) is left out, with the lines its returns took back, since it reaches the figures through the payments.
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
     * Stock value above zero per branch (active items) and, per branch, the items out of stock or low by
     * the stock list's rule: every active item judged on its balance in that branch (an inactive item, issue
     * #342, is left out like the list leaves it out), an item with no balance row counting as zero.
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
        return jdbc.sql("SELECT br.id AS branch_id,"
                        + " coalesce(sum(round(b.qty * p.sell_minor)) FILTER (WHERE b.qty > 0 AND p.active), 0) AS at_price,"
                        + " coalesce(sum(round(b.qty * p.cost_minor)) FILTER (WHERE b.qty > 0 AND p.active), 0) AS at_cost,"
                        + " count(*) FILTER (WHERE coalesce(b.qty, 0) <= 0) AS out_of_stock,"
                        + " count(*) FILTER (WHERE coalesce(b.qty, 0) <= :low) AS low_stock"
                        + "  FROM branches br"
                        + "  CROSS JOIN retail_products p"
                        + "  LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.product_id = p.id"
                        + "   AND b.branch_id = br.id"
                        + " WHERE p.active"
                        + "   AND (br.status = 'active' OR EXISTS (SELECT 1 FROM retail_stock_balances h"
                        + "        WHERE h.tenant_id = br.tenant_id AND h.branch_id = br.id AND h.qty <> 0))" + branch
                        + " GROUP BY br.id")
                .params(params)
                .query((rs, n) -> new Position(
                        rs.getObject("branch_id", UUID.class),
                        rs.getLong("at_price"),
                        rs.getLong("at_cost"),
                        rs.getInt("out_of_stock"),
                        rs.getInt("low_stock")))
                .list();
    }

    /**
     * Items out of stock and low over the given branches (all of them when null), as the stock list
     * judges All branches: one balance per item, summed over the branches, an item with no row counting
     * as zero. Returns {out, low}; low includes out.
     */
    int[] counts(List<UUID> branchIds, BigDecimal lowThreshold) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new int[] {0, 0};
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("low", lowThreshold);
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND b.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT count(*) FILTER (WHERE total <= 0) AS out_of_stock,"
                        + " count(*) FILTER (WHERE total <= :low) AS low_stock"
                        + "  FROM (SELECT p.id, coalesce(sum(b.qty), 0) AS total"
                        + "          FROM retail_products p"
                        + "          LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.product_id = p.id"
                        + branch
                        + "         WHERE p.active"
                        + "         GROUP BY p.id) t")
                .params(params)
                .query((rs, n) -> new int[] {rs.getInt("out_of_stock"), rs.getInt("low_stock")})
                .single();
    }
}
