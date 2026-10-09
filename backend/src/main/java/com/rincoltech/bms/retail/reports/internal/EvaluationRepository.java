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
 * Read-only SQL of the business future evaluation (issue #149): per branch, category and item, the
 * period's sales and cost snapshots beside the stock on hand valued at today's prices. One statement,
 * bounded by the date range, the branch scope and a per category row limit; the category sums come from
 * window functions over every row, so they do not depend on the limit.
 */
@Repository
class EvaluationRepository {

    private final JdbcClient jdbc;

    EvaluationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(
            UUID branchId,
            UUID categoryId,
            String category,
            int categoryItems,
            long categorySales,
            long categoryCost,
            long categoryAtPrice,
            long categoryAtCost,
            UUID productId,
            String code,
            String description,
            String unit,
            BigDecimal qty,
            long sales,
            long cost,
            long atPrice,
            long atCost) {}

    List<Row> rows(List<UUID> branchIds, LocalDate from, LocalDate to, int perCategory) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        params.put("top", perCategory);
        String soldBranch = "";
        String stockBranch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            soldBranch = " AND s.branch_id IN (:branchIds)";
            stockBranch = " AND b.branch_id IN (:branchIds)";
        }
        return jdbc.sql("WITH sold AS (SELECT s.branch_id, l.product_id, sum(l.line_total_minor) AS sales,"
                        + " sum(l.line_cost_minor) AS cost"
                        + "  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id"
                        + " WHERE s.status = 'completed' AND s.sale_date BETWEEN :from AND :to" + soldBranch
                        + " GROUP BY s.branch_id, l.product_id),"
                        + " stock AS (SELECT b.branch_id, b.product_id, b.qty,"
                        + " round(b.qty * p.sell_minor) AS at_price, round(b.qty * p.cost_minor) AS at_cost"
                        + "  FROM retail_stock_balances b JOIN retail_products p"
                        + "    ON p.tenant_id = b.tenant_id AND p.id = b.product_id"
                        + " WHERE b.qty > 0" + stockBranch + "),"
                        + " merged AS (SELECT branch_id, product_id, coalesce(sold.sales, 0) AS sales,"
                        + " coalesce(sold.cost, 0) AS cost, coalesce(stock.qty, 0) AS qty,"
                        + " coalesce(stock.at_price, 0) AS at_price, coalesce(stock.at_cost, 0) AS at_cost"
                        + "  FROM sold FULL JOIN stock USING (branch_id, product_id)),"
                        + " ranked AS (SELECT m.*, p.code, p.description, p.category_id, c.name AS category, u.name AS unit,"
                        + " row_number() OVER (PARTITION BY m.branch_id, p.category_id"
                        + "   ORDER BY m.at_price - m.at_cost DESC, m.sales - m.cost DESC, p.code, m.product_id) AS rn,"
                        + " count(*) OVER w AS cat_items, sum(m.sales) OVER w AS cat_sales, sum(m.cost) OVER w AS cat_cost,"
                        + " sum(m.at_price) OVER w AS cat_price, sum(m.at_cost) OVER w AS cat_at_cost"
                        + "  FROM merged m"
                        + "  JOIN retail_products p ON p.tenant_id = current_setting('app.tenant_id')::uuid AND p.id = m.product_id"
                        + "  JOIN retail_categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id"
                        + "  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id"
                        + "  WINDOW w AS (PARTITION BY m.branch_id, p.category_id))"
                        + " SELECT * FROM ranked WHERE rn <= :top ORDER BY branch_id, category, category_id, rn")
                .params(params)
                .query((rs, n) -> new Row(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category"),
                        rs.getInt("cat_items"),
                        rs.getLong("cat_sales"),
                        rs.getLong("cat_cost"),
                        rs.getLong("cat_price"),
                        rs.getLong("cat_at_cost"),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        rs.getBigDecimal("qty"),
                        rs.getLong("sales"),
                        rs.getLong("cost"),
                        rs.getLong("at_price"),
                        rs.getLong("at_cost")))
                .list();
    }
}
