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
            int total,
            BigDecimal minQty) {}

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
                        rs.getInt("total"),
                        null))
                .list();
    }

    /**
     * Issue #412, ADR-057: items low by either rule of {@code LowStockRule}, whichever trips first. An item is below its minimum
     * at the branch (the branch's own level, else the item's level for every branch), or it sold in the window and its stock
     * times the window is under lead days times units sold. An item with no minimum is judged by the pace alone, one that did
     * not sell by its minimum alone. The most urgent first: the smaller share of its minimum or of the lead days that is left.
     */
    List<Pace> reorder(
            List<UUID> branchIds, LocalDate since, LocalDate today, int windowDays, int leadDays, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds);
        params.put("since", Date.valueOf(since));
        params.put("today", Date.valueOf(today));
        params.put("window", windowDays);
        params.put("lead", leadDays);
        params.put("limit", limit);
        String share = "least(CASE WHEN r.min_qty IS NOT NULL AND r.qty < r.min_qty"
                + " THEN greatest(r.qty, 0) / r.min_qty END,"
                + " CASE WHEN r.sold > 0 THEN greatest(r.qty, 0) * :window / (:lead * r.sold) END)";
        return jdbc.sql("WITH sold AS (SELECT s.branch_id, l.product_id, sum(l.qty) AS sold"
                        + "  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id"
                        + " WHERE s.status = 'completed' AND s.sale_date BETWEEN :since AND :today"
                        + in(branchIds, "s.branch_id") + " GROUP BY s.branch_id, l.product_id),"
                        + " lvl AS (SELECT b.branch_id, b.product_id, b.qty,"
                        + " coalesce(bm.min_qty, im.min_qty) AS min_qty"
                        + "  FROM retail_stock_balances b"
                        + "  LEFT JOIN retail_stock_minimums bm ON bm.tenant_id = b.tenant_id"
                        + "       AND bm.product_id = b.product_id AND bm.branch_id = b.branch_id"
                        + "  LEFT JOIN retail_stock_minimums im ON im.tenant_id = b.tenant_id"
                        + "       AND im.product_id = b.product_id AND im.branch_id IS NULL"
                        + " WHERE (bm.min_qty IS NOT NULL OR im.min_qty IS NOT NULL)"
                        + in(branchIds, "b.branch_id") + "),"
                        + " r AS (SELECT coalesce(l.branch_id, s.branch_id) AS branch_id,"
                        + " coalesce(l.product_id, s.product_id) AS product_id,"
                        + " coalesce(l.qty, b.qty, 0) AS qty, coalesce(s.sold, 0) AS sold, l.min_qty"
                        + "  FROM lvl l FULL JOIN sold s ON s.branch_id = l.branch_id AND s.product_id = l.product_id"
                        + "  LEFT JOIN retail_stock_balances b ON b.tenant_id = current_setting('app.tenant_id')::uuid"
                        + "       AND b.branch_id = s.branch_id AND b.product_id = s.product_id)"
                        + " SELECT r.branch_id, p.id, p.code, p.description, u.name AS unit, r.qty, r.sold, r.min_qty,"
                        + " count(*) OVER () AS total"
                        + "  FROM r"
                        + "  JOIN retail_products p ON p.tenant_id = current_setting('app.tenant_id')::uuid AND p.id = r.product_id"
                        + "  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id"
                        + " WHERE p.active AND ((r.min_qty IS NOT NULL AND r.qty < r.min_qty)"
                        + " OR (r.sold > 0 AND greatest(r.qty, 0) * :window < :lead * r.sold))"
                        + " ORDER BY " + share + ", p.code, r.branch_id, p.id LIMIT :limit")
                .params(params)
                .query((rs, n) -> new Pace(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("sold"),
                        rs.getInt("total"),
                        rs.getBigDecimal("min_qty")))
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
                        + " sum(round(b.qty * p.sell_minor)) AS at_price, sum(round(b.qty * b.avg_cost_minor)) AS at_cost"
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
                        + " b.qty, round(b.qty * p.sell_minor) AS at_price, round(b.qty * b.avg_cost_minor) AS at_cost"
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

    record SlowRow(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String unit,
            BigDecimal qty,
            BigDecimal sold,
            LocalDate lastSold,
            int days,
            long atCost,
            int total,
            long totalAtCost) {}

    private static String soldWithLastCte(List<UUID> branchIds) {
        return "WITH sold AS (SELECT s.branch_id, l.product_id, sum(l.qty) AS sold, max(s.sale_date) AS last_sold,"
                + " min(s.sale_date) AS first_sold"
                + "  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id"
                + " WHERE s.status = 'completed' AND s.sale_date BETWEEN :since AND :today"
                + in(branchIds, "s.branch_id") + " GROUP BY s.branch_id, l.product_id)";
    }

    /**
     * The days of the pace window for an item in a branch (issue #414): the window, or fewer for an item that is
     * newer in the branch than the window, counted from its first stock movement there (its first receipt or
     * opening) or its first sale in the window, whichever is earlier, today included. {@code t}, {@code br},
     * {@code pr} and {@code first} are the SQL of the tenant, branch, product and first sale columns.
     */
    private static String ageDays(String t, String br, String pr, String first) {
        return "least(:window, CAST(:today AS date) - least(" + first
                + ", (SELECT min(m.business_date) FROM retail_stock_movements m WHERE m.tenant_id = " + t
                + " AND m.branch_id = " + br + " AND m.product_id = " + pr + ")) + 1)";
    }

    /**
     * Slow movers (issue #414): stock on hand of an item that did sell in the branch in the window (so it is not
     * dead stock), where the stock lasts more than {@code months} months at that pace, judged exactly as stock
     * times the item's days over {@code months} times 30 times units sold, or where fewer than {@code minSold}
     * units sold ({@code minSold} 0 is no such rule). The item's days are the window, or fewer for an item newer
     * in the branch ({@link #ageDays}), so a new item is judged on the days it has been there. The longest to sell
     * first, never ordered by cost; the count and the value at cost are over every such row, so they do not depend
     * on the row limit. Sales are counted gross of customer returns, as in the cover list and the sales analysis.
     */
    List<SlowRow> slow(
            List<UUID> branchIds,
            LocalDate since,
            LocalDate today,
            int windowDays,
            int months,
            int minSold,
            int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = deadParams(branchIds, since, today);
        params.put("window", windowDays);
        params.put("months", months);
        params.put("minSold", minSold);
        params.put("limit", limit);
        return jdbc.sql(soldWithLastCte(branchIds)
                        + " SELECT sold.branch_id, p.id, p.code, p.description,"
                        + " (SELECT u.name FROM retail_units u WHERE u.tenant_id = p.tenant_id AND u.id = p.unit_id) AS unit,"
                        + " b.qty, sold.sold, sold.last_sold, age.days, round(b.qty * b.avg_cost_minor) AS at_cost,"
                        + " count(*) OVER () AS total, sum(round(b.qty * b.avg_cost_minor)) OVER () AS total_at_cost"
                        + "  FROM sold"
                        + "  JOIN retail_products p ON p.tenant_id = current_setting('app.tenant_id')::uuid AND p.id = sold.product_id"
                        + "  JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id"
                        + "       AND b.branch_id = sold.branch_id AND b.product_id = sold.product_id"
                        + "  CROSS JOIN LATERAL (SELECT "
                        + ageDays("p.tenant_id", "sold.branch_id", "sold.product_id", "sold.first_sold")
                        + " AS days) age"
                        + " WHERE p.active AND b.qty > 0 AND sold.sold > 0"
                        + "   AND (b.qty * age.days > :months * 30 * sold.sold OR sold.sold < :minSold)"
                        + " ORDER BY b.qty * age.days / sold.sold DESC, p.code, sold.branch_id, p.id LIMIT :limit")
                .params(params)
                .query((rs, n) -> new SlowRow(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("sold"),
                        rs.getObject("last_sold", LocalDate.class),
                        rs.getInt("days"),
                        rs.getLong("at_cost"),
                        rs.getInt("total"),
                        rs.getLong("total_at_cost")))
                .list();
    }

    record BranchPace(UUID branchId, UUID productId, BigDecimal qty, BigDecimal sold, int days) {}

    /**
     * The pace of the given items in every branch of {@code branchIds} (null for all) where they sold in the
     * window, with the stock there (none counts as zero) and the item's days there ({@link #ageDays}); the
     * candidates of "sells better at" (issue #414). Only an active branch that stocks goods can take stock, so a
     * closed branch or one that keeps no goods is never a candidate.
     */
    List<BranchPace> paceOf(
            List<UUID> branchIds, List<UUID> productIds, LocalDate since, LocalDate today, int windowDays) {
        if ((branchIds != null && branchIds.isEmpty()) || productIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = deadParams(branchIds, since, today);
        params.put("productIds", productIds);
        params.put("window", windowDays);
        return jdbc.sql("SELECT x.branch_id, x.product_id, x.sold,"
                        + " (SELECT coalesce(max(b.qty), 0) FROM retail_stock_balances b WHERE b.tenant_id = x.tenant_id"
                        + "   AND b.branch_id = x.branch_id AND b.product_id = x.product_id) AS qty, "
                        + ageDays("x.tenant_id", "x.branch_id", "x.product_id", "x.first_sold") + " AS days"
                        + " FROM (SELECT s.tenant_id, s.branch_id, l.product_id, sum(l.qty) AS sold,"
                        + " min(s.sale_date) AS first_sold"
                        + "  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id"
                        + "  JOIN branches br ON br.tenant_id = s.tenant_id AND br.id = s.branch_id"
                        + " WHERE s.status = 'completed' AND s.sale_date BETWEEN :since AND :today"
                        + "   AND br.status = 'active' AND br.stocks_goods"
                        + "   AND l.product_id IN (:productIds)" + in(branchIds, "s.branch_id")
                        + " GROUP BY s.tenant_id, s.branch_id, l.product_id) x")
                .params(params)
                .query((rs, n) -> new BranchPace(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("sold"),
                        rs.getInt("days")))
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
