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
 * Read-only SQL of the sales analysis (issue #149). Every statement is bounded by a date range, by
 * the caller's branch scope and by a row limit, and starts from {@code retail_sales_by_date}
 * (tenant, sale_date) with the lines read through {@code retail_sale_lines_by_sale} (ADR-028).
 */
@Repository
class SalesAnalysisRepository {

    private final JdbcClient jdbc;

    SalesAnalysisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    enum Dimension {
        BRANCH,
        CATEGORY,
        PRODUCT,
        SELLER
    }

    /** Sums over completed sales; {@code cost} is read here and leaves the module only for a profit reader. */
    record Agg(
            UUID id,
            String code,
            String label,
            String unit,
            BigDecimal qty,
            long sales,
            long cost,
            long saleCount,
            int total) {}

    enum Order {
        SALES,
        QTY,
        PROFIT,
        MARGIN
    }

    record Day(LocalDate day, long sales, long cost, long saleCount) {}

    private static final String BASE = """
              FROM retail_sales s
              JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
            """;

    private static final String WHERE = " WHERE s.status = 'completed' AND s.sale_date BETWEEN :from AND :to";

    private static Map<String, Object> params(List<UUID> branchIds, LocalDate from, LocalDate to) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        return params;
    }

    private static String branchClause(List<UUID> branchIds) {
        return branchIds == null ? "" : " AND s.branch_id IN (:branchIds)";
    }

    List<Day> days(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT s.sale_date AS day, sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost,"
                        + " count(DISTINCT s.id) AS sale_count" + BASE + WHERE + branchClause(branchIds)
                        + " GROUP BY s.sale_date ORDER BY s.sale_date")
                .params(params(branchIds, from, to))
                .query((rs, n) -> new Day(
                        rs.getDate("day").toLocalDate(),
                        rs.getLong("sales"),
                        rs.getLong("cost"),
                        rs.getLong("sale_count")))
                .list();
    }

    /** The whole window as one row, so totals do not depend on the row limit of the lists. */
    Agg total(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new Agg(null, null, null, null, BigDecimal.ZERO, 0, 0, 0, 0);
        }
        return jdbc.sql("SELECT coalesce(sum(l.qty), 0) AS qty, coalesce(sum(l.line_total_minor), 0) AS sales,"
                        + " coalesce(sum(l.line_cost_minor), 0) AS cost, count(DISTINCT s.id) AS sale_count"
                        + BASE + WHERE + branchClause(branchIds))
                .params(params(branchIds, from, to))
                .query((rs, n) -> new Agg(
                        null,
                        null,
                        null,
                        null,
                        rs.getBigDecimal("qty"),
                        rs.getLong("sales"),
                        rs.getLong("cost"),
                        rs.getLong("sale_count"),
                        1))
                .single();
    }

    /**
     * Sales grouped by a dimension, in the given order (largest first; margin lowest first), at most
     * {@code limit} rows. With {@code belowTargetBp} only groups whose margin is under that many basis
     * points (a group that sold for nothing at a cost counts); {@link Agg#total} is the count before the limit.
     */
    List<Agg> grouped(
            Dimension dimension,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            Order order,
            Integer belowTargetBp,
            int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        String join;
        String key;
        String code = "NULL";
        String label = "NULL";
        String unit = "NULL";
        switch (dimension) {
            case BRANCH -> {
                key = "s.branch_id";
                label = "b.name";
                join = " JOIN branches b ON b.tenant_id = s.tenant_id AND b.id = s.branch_id";
            }
            case CATEGORY -> {
                join = " JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id"
                        + " JOIN retail_categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id";
                key = "c.id";
                label = "c.name";
            }
            case PRODUCT -> {
                join = " JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id"
                        + " JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id";
                key = "p.id";
                code = "p.code";
                label = "p.description";
                unit = "u.name";
            }
            default -> {
                join = " LEFT JOIN users u ON u.tenant_id = s.tenant_id AND u.id = s.created_by";
                key = "s.created_by";
                label = "max(u.full_name)";
            }
        }
        String groupBy = switch (dimension) {
            case BRANCH -> "s.branch_id, b.name";
            case CATEGORY -> "c.id, c.name";
            case PRODUCT -> "p.id, p.code, p.description, u.name";
            default -> "s.created_by";
        };
        String orderBy = switch (order) {
            case QTY -> "sum(l.qty) DESC, sum(l.line_total_minor) DESC";
            case PROFIT -> "sum(l.line_total_minor) - sum(l.line_cost_minor) DESC, sum(l.line_total_minor) DESC";
            case MARGIN ->
                "(sum(l.line_total_minor) - sum(l.line_cost_minor))::numeric"
                        + " / nullif(sum(l.line_total_minor), 0) ASC NULLS FIRST, sum(l.line_total_minor) DESC";
            default -> "sum(l.line_total_minor) DESC, sum(l.qty) DESC";
        };
        String having = "";
        Map<String, Object> params = params(branchIds, from, to);
        if (belowTargetBp != null) {
            having = " HAVING (sum(l.line_total_minor) - sum(l.line_cost_minor))::numeric * 10000"
                    + " < :target::numeric * sum(l.line_total_minor)";
            params.put("target", belowTargetBp);
        }
        params.put("limit", limit);
        return jdbc.sql("SELECT " + key + " AS k, " + code + " AS code, " + label + " AS label, " + unit + " AS unit,"
                        + " sum(l.qty) AS qty, sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost,"
                        + " count(DISTINCT s.id) AS sale_count, count(*) OVER () AS total"
                        + BASE + join + WHERE + branchClause(branchIds)
                        + " GROUP BY " + groupBy + having + " ORDER BY " + orderBy + ", 1 LIMIT :limit")
                .params(params)
                .query((rs, n) -> new Agg(
                        rs.getObject("k", UUID.class),
                        rs.getString("code"),
                        rs.getString("label"),
                        rs.getString("unit"),
                        rs.getBigDecimal("qty"),
                        rs.getLong("sales"),
                        rs.getLong("cost"),
                        rs.getLong("sale_count"),
                        rs.getInt("total")))
                .list();
    }

    record Stocked(
            UUID productId, String code, String description, String unit, BigDecimal qty, long sellMinor, int total) {}

    /** Items with stock on hand and no completed sale from {@code since} to {@code today} in the reported branches. */
    List<Stocked> slowMovers(List<UUID> branchIds, LocalDate since, LocalDate today, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds, since, today);
        params.put("limit", limit);
        String stockBranch = branchIds == null ? "" : " AND b.branch_id IN (:branchIds)";
        return jdbc.sql("WITH sold AS (SELECT DISTINCT l.product_id" + BASE + WHERE + branchClause(branchIds) + ")"
                        + " SELECT p.id, p.code, p.description, u.name AS unit, sum(b.qty) AS qty, p.sell_minor,"
                        + " count(*) OVER () AS total"
                        + "  FROM retail_stock_balances b"
                        + "  JOIN retail_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id"
                        + "  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id"
                        + " WHERE b.qty > 0 AND p.active" + stockBranch
                        + "   AND NOT EXISTS (SELECT 1 FROM sold WHERE sold.product_id = p.id)"
                        + " GROUP BY p.id, p.code, p.description, u.name, p.sell_minor"
                        + " ORDER BY sum(b.qty) * p.sell_minor DESC, p.code, p.id LIMIT :limit")
                .params(params)
                .query(SalesAnalysisRepository::stocked)
                .list();
    }

    /** Active items with no completed sale in the window, with their stock on hand in the reported branches. */
    List<Stocked> noSales(List<UUID> branchIds, LocalDate from, LocalDate to, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds, from, to);
        params.put("limit", limit);
        String stockBranch = branchIds == null ? "" : " WHERE branch_id IN (:branchIds)";
        return jdbc.sql("WITH sold AS (SELECT DISTINCT l.product_id" + BASE + WHERE + branchClause(branchIds) + ")"
                        + " SELECT p.id, p.code, p.description, u.name AS unit, coalesce(st.qty, 0) AS qty, p.sell_minor,"
                        + " count(*) OVER () AS total"
                        + "  FROM retail_products p"
                        + "  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id"
                        + "  LEFT JOIN (SELECT product_id, sum(qty) AS qty FROM retail_stock_balances" + stockBranch
                        + "              GROUP BY product_id) st ON st.product_id = p.id"
                        + " WHERE p.active AND NOT EXISTS (SELECT 1 FROM sold WHERE sold.product_id = p.id)"
                        + " ORDER BY p.code, p.id LIMIT :limit")
                .params(params)
                .query(SalesAnalysisRepository::stocked)
                .list();
    }

    private static Stocked stocked(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Stocked(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("description"),
                rs.getString("unit"),
                rs.getBigDecimal("qty"),
                rs.getLong("sell_minor"),
                rs.getInt("total"));
    }
}
