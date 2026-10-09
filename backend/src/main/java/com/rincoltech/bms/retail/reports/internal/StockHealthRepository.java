package com.rincoltech.bms.retail.reports.internal;

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
 * Read-only SQL of the stock health report (issue #149). Each statement is bounded by a date window
 * (30 days of velocity, 90 of dead stock, the caller's range for shrinkage), by branch scope and by a
 * row limit. Sales are read as in {@link SalesAnalysisRepository}; balances by their primary key.
 */
@Repository
class StockHealthRepository {

    private final JdbcClient jdbc;

    StockHealthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Pace(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String unit,
            BigDecimal qty,
            BigDecimal sold,
            int total) {}

    private static String in(List<UUID> branchIds, String column) {
        return branchIds == null ? "" : " AND " + column + " IN (:branchIds)";
    }

    private static Map<String, Object> params(List<UUID> branchIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        return params;
    }

    /**
     * Items sold in the window per branch, lowest cover first (stock over units sold, so the order is the
     * order of days of cover). With {@code leadDays} only those whose cover is under that many days, judged
     * exactly: stock times the window under lead days times units sold. Negative stock counts as none.
     */
    List<Pace> pace(
            List<UUID> branchIds, LocalDate since, LocalDate today, int windowDays, Integer leadDays, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds);
        params.put("since", Date.valueOf(since));
        params.put("today", Date.valueOf(today));
        params.put("window", windowDays);
        params.put("limit", limit);
        String lead = "";
        if (leadDays != null) {
            lead = " AND greatest(coalesce(b.qty, 0), 0) * :window < :lead * sold.sold";
            params.put("lead", leadDays);
        }
        return jdbc.sql(
                        "WITH sold AS (SELECT s.branch_id, l.product_id, sum(l.qty) AS sold"
                                + "  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id"
                                + " WHERE s.status = 'completed' AND s.sale_date BETWEEN :since AND :today"
                                + in(branchIds, "s.branch_id") + " GROUP BY s.branch_id, l.product_id)"
                                + " SELECT sold.branch_id, p.id, p.code, p.description, u.name AS unit,"
                                + " coalesce(b.qty, 0) AS qty, sold.sold, count(*) OVER () AS total"
                                + "  FROM sold"
                                + "  JOIN retail_products p ON p.tenant_id = current_setting('app.tenant_id')::uuid AND p.id = sold.product_id"
                                + "  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id"
                                + "  LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id"
                                + "       AND b.branch_id = sold.branch_id AND b.product_id = sold.product_id"
                                + " WHERE p.active" + lead
                                + " ORDER BY greatest(coalesce(b.qty, 0), 0) / sold.sold, p.code, sold.branch_id, p.id LIMIT :limit")
                .params(params)
                .query((rs, n) -> new Pace(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("sold"),
                        rs.getInt("total")))
                .list();
    }

    record DeadTotal(UUID branchId, int items, long atPrice, long atCost) {}

    record DeadRow(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String unit,
            BigDecimal qty,
            long atPrice,
            long atCost) {}

    private static final String DEAD_FROM = """
              FROM retail_stock_balances b
              JOIN retail_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
             WHERE b.qty > 0 AND p.active
               AND NOT EXISTS (SELECT 1 FROM sold WHERE sold.branch_id = b.branch_id AND sold.product_id = b.product_id)
            """;

    private static String soldCte(List<UUID> branchIds) {
        return "WITH sold AS (SELECT DISTINCT s.branch_id, l.product_id"
                + "  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id"
                + " WHERE s.status = 'completed' AND s.sale_date BETWEEN :since AND :today"
                + in(branchIds, "s.branch_id") + ")";
    }

    private static Map<String, Object> deadParams(List<UUID> branchIds, LocalDate since, LocalDate today) {
        Map<String, Object> params = params(branchIds);
        params.put("since", Date.valueOf(since));
        params.put("today", Date.valueOf(today));
        return params;
    }

    /** Dead stock per branch over every such item, so the totals do not depend on the row limit. */
    List<DeadTotal> deadTotals(List<UUID> branchIds, LocalDate since, LocalDate today) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(soldCte(branchIds) + " SELECT b.branch_id, count(*) AS items,"
                        + " sum(round(b.qty * p.sell_minor)) AS at_price, sum(round(b.qty * p.cost_minor)) AS at_cost"
                        + DEAD_FROM + in(branchIds, "b.branch_id") + " GROUP BY b.branch_id ORDER BY b.branch_id")
                .params(deadParams(branchIds, since, today))
                .query((rs, n) -> new DeadTotal(
                        rs.getObject("branch_id", UUID.class),
                        rs.getInt("items"),
                        rs.getLong("at_price"),
                        rs.getLong("at_cost")))
                .list();
    }

    /** The dead stock of the largest value at price, at most {@code limit} rows. Ordered by price, never by cost. */
    List<DeadRow> deadItems(List<UUID> branchIds, LocalDate since, LocalDate today, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = deadParams(branchIds, since, today);
        params.put("limit", limit);
        return jdbc.sql(soldCte(branchIds)
                        + " SELECT b.branch_id, p.id, p.code, p.description,"
                        + " (SELECT u.name FROM retail_units u WHERE u.tenant_id = p.tenant_id AND u.id = p.unit_id) AS unit,"
                        + " b.qty, round(b.qty * p.sell_minor) AS at_price, round(b.qty * p.cost_minor) AS at_cost"
                        + DEAD_FROM + in(branchIds, "b.branch_id")
                        + " ORDER BY round(b.qty * p.sell_minor) DESC, p.code, b.branch_id, p.id LIMIT :limit")
                .params(params)
                .query((rs, n) -> new DeadRow(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        rs.getBigDecimal("qty"),
                        rs.getLong("at_price"),
                        rs.getLong("at_cost")))
                .list();
    }

    record Usage(UUID branchId, String kind, int reports, long cost) {}

    List<Usage> usage(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds);
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        return jdbc.sql("SELECT branch_id, kind, count(*) AS reports, sum(cost_total_minor) AS cost"
                        + " FROM retail_usage_reports WHERE occurred_on BETWEEN :from AND :to"
                        + in(branchIds, "branch_id")
                        + " GROUP BY branch_id, kind")
                .params(params)
                .query((rs, n) -> new Usage(
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("kind"),
                        rs.getInt("reports"),
                        rs.getLong("cost")))
                .list();
    }

    record Count(UUID branchId, int shortLines, int overLines, long loss, long gain) {}

    /** Committed stock-take differences per branch, valued at the cost the ledger posted them at. */
    List<Count> stocktakes(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds);
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        return jdbc.sql("SELECT branch_id, count(*) FILTER (WHERE qty < 0) AS short_lines,"
                        + " count(*) FILTER (WHERE qty > 0) AS over_lines,"
                        + " coalesce(sum(round(abs(qty) * unit_cost_minor)) FILTER (WHERE qty < 0), 0) AS loss,"
                        + " coalesce(sum(round(abs(qty) * unit_cost_minor)) FILTER (WHERE qty > 0), 0) AS gain"
                        + " FROM retail_stock_movements WHERE kind = 'adjustment' AND source_type = 'retail.stocktake'"
                        + " AND business_date BETWEEN :from AND :to" + in(branchIds, "branch_id")
                        + " GROUP BY branch_id")
                .params(params)
                .query((rs, n) -> new Count(
                        rs.getObject("branch_id", UUID.class),
                        rs.getInt("short_lines"),
                        rs.getInt("over_lines"),
                        rs.getLong("loss"),
                        rs.getLong("gain")))
                .list();
    }
}
