package com.rincoltech.bms.retail.reports.internal;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The price change impact of the margin report (issue #149): products whose sell price changed in a
 * range, with what they sold before and after, from the sale line snapshots. The history is read by
 * its tenant and date; it holds one row per price event, far fewer than sale lines (ADR-028).
 */
@Repository
class MarginRepository {

    private final JdbcClient jdbc;

    MarginRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Impact(
            UUID productId,
            String code,
            String description,
            String unit,
            LocalDate changedOn,
            long oldSell,
            long newSell,
            BigDecimal qtyBefore,
            long salesBefore,
            long costBefore,
            BigDecimal qtyAfter,
            long salesAfter,
            long costAfter,
            int total) {}

    List<Impact> priceChanges(
            List<UUID> branchIds, LocalDate from, LocalDate to, String zone, Instant start, Instant end, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        params.put("zone", zone);
        params.put("start", Timestamp.from(start));
        params.put("end", Timestamp.from(end));
        params.put("limit", limit);
        String branch = "";
        if (branchIds != null) {
            branch = " AND s.branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("""
                        WITH changed AS (
                            SELECT h.product_id,
                                   (array_agg(h.old_sell_minor ORDER BY h.created_at, h.id))[1] AS old_sell,
                                   (array_agg(h.new_sell_minor ORDER BY h.created_at DESC, h.id DESC))[1] AS new_sell,
                                   (max(h.created_at) AT TIME ZONE :zone)::date AS changed_on
                              FROM retail_price_history h
                             WHERE h.created_at >= :start AND h.created_at < :end
                               AND h.source <> 'initial' AND h.old_sell_minor IS DISTINCT FROM h.new_sell_minor
                             GROUP BY h.product_id
                            HAVING (array_agg(h.old_sell_minor ORDER BY h.created_at, h.id))[1]
                                IS DISTINCT FROM (array_agg(h.new_sell_minor ORDER BY h.created_at DESC, h.id DESC))[1]
                        ), sold AS (
                            SELECT l.product_id,
                                   coalesce(sum(l.qty) FILTER (WHERE s.sale_date < c.changed_on), 0) AS qty_before,
                                   coalesce(sum(l.line_total_minor) FILTER (WHERE s.sale_date < c.changed_on), 0) AS sales_before,
                                   coalesce(sum(l.line_cost_minor) FILTER (WHERE s.sale_date < c.changed_on), 0) AS cost_before,
                                   coalesce(sum(l.qty) FILTER (WHERE s.sale_date >= c.changed_on), 0) AS qty_after,
                                   coalesce(sum(l.line_total_minor) FILTER (WHERE s.sale_date >= c.changed_on), 0) AS sales_after,
                                   coalesce(sum(l.line_cost_minor) FILTER (WHERE s.sale_date >= c.changed_on), 0) AS cost_after
                              FROM retail_sales s
                              JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
                              JOIN changed c ON c.product_id = l.product_id
                             WHERE s.status = 'completed' AND s.sale_date BETWEEN :from AND :to""" + branch + """

                             GROUP BY l.product_id
                        )
                        SELECT c.product_id, p.code, p.description, u.name AS unit, c.changed_on, c.old_sell, c.new_sell,
                               coalesce(d.qty_before, 0) AS qty_before, coalesce(d.sales_before, 0) AS sales_before,
                               coalesce(d.cost_before, 0) AS cost_before, coalesce(d.qty_after, 0) AS qty_after,
                               coalesce(d.sales_after, 0) AS sales_after, coalesce(d.cost_after, 0) AS cost_after,
                               count(*) OVER () AS total
                          FROM changed c
                          JOIN retail_products p ON p.tenant_id = current_setting('app.tenant_id')::uuid AND p.id = c.product_id
                          JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
                          LEFT JOIN sold d ON d.product_id = c.product_id
                         ORDER BY c.changed_on DESC, p.code, c.product_id LIMIT :limit
                        """)
                .params(params)
                .query((rs, n) -> new Impact(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        rs.getDate("changed_on").toLocalDate(),
                        rs.getLong("old_sell"),
                        rs.getLong("new_sell"),
                        rs.getBigDecimal("qty_before"),
                        rs.getLong("sales_before"),
                        rs.getLong("cost_before"),
                        rs.getBigDecimal("qty_after"),
                        rs.getLong("sales_after"),
                        rs.getLong("cost_after"),
                        rs.getInt("total")))
                .list();
    }
}
