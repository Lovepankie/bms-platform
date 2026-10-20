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
            UUID categoryId,
            String category,
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
        StringBuilder sql = new StringBuilder(
                "SELECT q.branch_id, q.product_id, p.code, p.description, p.category_id, c.name AS category,"
                        + " u.name AS unit, q.qty, p.cost_minor, p.sell_minor FROM (" + quantities + ") q"
                        + " JOIN retail_products p ON p.id = q.product_id JOIN retail_units u ON u.id = p.unit_id"
                        + " JOIN retail_categories c ON c.id = p.category_id"
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
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category"),
                        rs.getString("unit"),
                        rs.getBigDecimal("qty"),
                        rs.getLong("cost_minor"),
                        rs.getLong("sell_minor")))
                .list();
    }

    /** {@code badDebt} is the loss of credit sales written off on the day (ADR-043); the other figures are as ever. */
    record DayFigures(UUID branchId, LocalDate date, long sales, long costOfSales, long usageCost, long badDebt) {

        DayFigures(UUID branchId, LocalDate date, long sales, long costOfSales, long usageCost) {
            this(branchId, date, sales, costOfSales, usageCost, 0);
        }
    }

    /**
     * FR-RET-10 per branch and day: the append-only lines of completed (not voided) sales, their
     * totals and cost snapshots, by sale date (never the mutable sale header, review F10), and usage
     * and damage at their cost snapshots by the day they happened. Customer returns (issue #201) that were not voided
     * take their value and their cost off by the day of the return, and the cost of the goods they wrote off as damaged
     * counts as usage, so the profit falls by the margin of goods that went back on the shelf and by the whole credit of
     * goods that did not, and a day already reported keeps its sales. The part of a landed cost that belongs to goods
     * already sold (issue #199) is a cost of sales on the day of the charge, and the same part of a supplier price credit
     * takes it off on the day of the credit; a voided document drops out, as a voided sale does. The price variance of a
     * supplier invoice or credit note is not in this report, as it never was.
     */
    List<DayFigures> daily(List<UUID> branchIds, LocalDate from, LocalDate to) {
        return daily(branchIds, from, to, CreditPeriods.NONE);
    }

    /**
     * The same figures; a credit sale that is not an imported one and counts from the start day of {@code periods} on (the later
     * of its date and the day it was entered, so a backdated sale entered after the start too; counted when the buyer pays,
     * ADR-043) leaves the sales leg, and so do the sales and cost of its returns, since its sales and
     * cost reach the report through {@link CreditRecognition} on the days the money comes in. What a return writes off as
     * damaged stays a usage cost on the day of the return: the goods are lost whether or not the buyer paid. Credit sales
     * entered and dated before the start day count at the sale, as they always did.
     */
    List<DayFigures> daily(List<UUID> branchIds, LocalDate from, LocalDate to, CreditPeriods periods) {
        return daily(branchIds, from, to, periods, false);
    }

    private List<DayFigures> daily(
            List<UUID> branchIds, LocalDate from, LocalDate to, CreditPeriods periods, boolean cashOnly) {
        Map<String, Object> periodParams = new LinkedHashMap<>();
        String deferredSale = cashOnly
                ? " AND s.payment_method <> 'credit'"
                : periods.isEmpty()
                        ? ""
                        : " AND (s.payment_method <> 'credit' OR s.historical OR NOT "
                                + periods.sql("s.sale_date", "s.created_at", periodParams, "crpS") + ")";
        String deferredReturn = cashOnly
                ? "true"
                : periods.isEmpty()
                        ? "false"
                        : "EXISTS (SELECT 1 FROM retail_sales rs WHERE rs.tenant_id = r.tenant_id AND rs.id = r.sale_id"
                                + " AND rs.payment_method = 'credit' AND NOT rs.historical AND "
                                + periods.sql("rs.sale_date", "rs.created_at", periodParams, "crpR") + ")";
        // Spelled outside the text blocks below, which strip the space at the end of a line.
        String returnSales = " CASE WHEN " + deferredReturn + " THEN 0 ELSE -r.total_minor END,";
        String returnCost = " CASE WHEN " + deferredReturn + " THEN 0 ELSE -r.cost_minor END,";
        Map<String, Object> params = new LinkedHashMap<>(periodParams);
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        String salesFilter = "";
        String usageFilter = "";
        String returnsFilter = "";
        String landedFilter = "";
        String landedFilter2 = "";
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            salesFilter = " AND s.branch_id IN (:branchIds)";
            usageFilter = " AND branch_id IN (:branchIds)";
            returnsFilter = " AND r.branch_id IN (:branchIds)";
            landedFilter = " AND l.branch_id IN (:branchIds)";
            landedFilter2 = " AND l.branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("""
                        SELECT branch_id, day, sum(sales) AS sales, sum(cost) AS cost, sum(usage) AS usage FROM (
                            SELECT s.branch_id, s.sale_date AS day, l.line_total_minor AS sales,
                                   l.line_cost_minor AS cost, 0 AS usage
                              FROM retail_sale_lines l JOIN retail_sales s ON s.id = l.sale_id
                             WHERE s.status = 'completed' AND s.sale_date BETWEEN :from AND :to""" + deferredSale + salesFilter + """

                            UNION ALL
                            SELECT branch_id, occurred_on, 0, 0, cost_total_minor FROM retail_usage_reports
                             WHERE occurred_on BETWEEN :from AND :to""" + usageFilter + """

                            UNION ALL
                            SELECT r.branch_id, r.returned_on,""" + returnSales + returnCost + """

                                   coalesce((SELECT sum(l.line_cost_minor) FROM retail_sale_return_lines l
                                              WHERE l.return_id = r.id AND l.disposition = 'write_off'), 0)
                              FROM retail_sale_returns r
                             WHERE r.status = 'completed' AND r.returned_on BETWEEN :from AND :to"""
                        + returnsFilter + """

                            UNION ALL
                            SELECT l.branch_id, d.cost_date, 0, l.to_cogs_minor, 0
                              FROM retail_landed_cost_lines l JOIN retail_landed_costs d ON d.id = l.landed_cost_id
                             WHERE d.status = 'posted' AND d.cost_date BETWEEN :from AND :to""" + landedFilter + """

                            UNION ALL
                            SELECT l.branch_id, c.credit_date, 0, -l.to_cogs_minor, 0
                              FROM retail_supplier_credit_lines l JOIN retail_supplier_credits c ON c.id = l.credit_id
                             WHERE c.status = 'posted' AND l.to_cogs_minor > 0 AND c.credit_date BETWEEN :from AND :to"""
                        + landedFilter2 + """

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

    record Difference(UUID branchId, LocalDate date, BigDecimal qty, long costMinor) {}

    /**
     * ADR-020 and #121: the committed stock-take adjustments, one row per line, at the cost the
     * ledger posted them at, on the business date the stock-take was committed. A negative qty is a
     * shortage (a loss), a positive one a surplus (a gain).
     */
    List<Difference> stocktakeDifferences(List<UUID> branchIds, LocalDate from, LocalDate to) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        String filter = "";
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            filter = " AND branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("SELECT branch_id, business_date, qty, unit_cost_minor FROM retail_stock_movements"
                        + " WHERE kind = 'adjustment' AND source_type = 'retail.stocktake'"
                        + " AND business_date BETWEEN :from AND :to" + filter)
                .params(params)
                .query((rs, n) -> new Difference(
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("business_date").toLocalDate(),
                        rs.getBigDecimal("qty"),
                        rs.getLong("unit_cost_minor")))
                .list();
    }
}
