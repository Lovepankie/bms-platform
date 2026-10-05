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
 * Read-only SQL over the retail tables for the reports. Row-level security supplies the tenant;
 * the branch filter comes from the caller's scope.
 */
@Repository
class ReportsRepository {

    private final JdbcClient jdbc;

    ReportsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Holding(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String unit,
            BigDecimal qty,
            long costMinor,
            long sellMinor) {}

    /**
     * Quantity per branch and product: the balance now, or the sum of movements whose business date
     * is on or before {@code asOf}, the basis the journals use (review F4). Rows of zero are left out.
     */
    List<Holding> holdings(List<UUID> branchIds, LocalDate asOf) {
        Map<String, Object> params = new LinkedHashMap<>();
        String quantities;
        if (asOf == null) {
            quantities = "SELECT branch_id, product_id, qty FROM retail_stock_balances";
        } else {
            quantities = """
                    SELECT branch_id, product_id, sum(qty) AS qty FROM retail_stock_movements
                     WHERE business_date <= :asOf
                     GROUP BY branch_id, product_id""";
            params.put("asOf", Date.valueOf(asOf));
        }
        StringBuilder sql = new StringBuilder("SELECT q.branch_id, q.product_id, p.code, p.description, u.name AS unit,"
                + " q.qty, p.cost_minor, p.sell_minor FROM (" + quantities + ") q"
                + " JOIN retail_products p ON p.id = q.product_id JOIN retail_units u ON u.id = p.unit_id"
                + " WHERE q.qty <> 0");
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND q.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        sql.append(" ORDER BY q.branch_id, p.code, p.id");
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new Holding(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        rs.getBigDecimal("qty"),
                        rs.getLong("cost_minor"),
                        rs.getLong("sell_minor")))
                .list();
    }

    record DayFigures(UUID branchId, LocalDate date, long sales, long costOfSales, long usageCost) {}

    /**
     * FR-RET-10 per branch and day: the append-only lines of completed (not voided) sales, their
     * totals and cost snapshots, by sale date (never the mutable sale header, review F10), and usage
     * and damage at their cost snapshots by the day they happened.
     */
    List<DayFigures> daily(List<UUID> branchIds, LocalDate from, LocalDate to) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        String salesFilter = "";
        String usageFilter = "";
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            salesFilter = " AND s.branch_id IN (:branchIds)";
            usageFilter = " AND branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("""
                        SELECT branch_id, day, sum(sales) AS sales, sum(cost) AS cost, sum(usage) AS usage FROM (
                            SELECT s.branch_id, s.sale_date AS day, l.line_total_minor AS sales,
                                   l.line_cost_minor AS cost, 0 AS usage
                              FROM retail_sale_lines l JOIN retail_sales s ON s.id = l.sale_id
                             WHERE s.status = 'completed' AND s.sale_date BETWEEN :from AND :to""" + salesFilter + """

                            UNION ALL
                            SELECT branch_id, occurred_on, 0, 0, cost_total_minor FROM retail_usage_reports
                             WHERE occurred_on BETWEEN :from AND :to""" + usageFilter + """

                        ) f GROUP BY branch_id, day ORDER BY day, branch_id
                        """)
                .params(params)
                .query((rs, n) -> new DayFigures(
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("day").toLocalDate(),
                        rs.getLong("sales"),
                        rs.getLong("cost"),
                        rs.getLong("usage")))
                .list();
    }
}
