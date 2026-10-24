package com.rincoltech.bms.retail.stock.internal;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for the stock-by-location report (docs/specs/trade-capabilities.md section 8).
 * Row-level security supplies the tenant; the branch lists come from the caller's scope.
 */
@Repository
class LocationRepository {

    private final JdbcClient jdbc;

    LocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What is dispatched and not yet received for one product: quantity and value at the dispatch cost. */
    record OnTheWay(BigDecimal qty, long valueMinor) {}

    /** A non-zero balance with the product's current cost. */
    record Holding(UUID branchId, UUID productId, BigDecimal qty, long costMinor) {}

    /**
     * Quantity and dispatch-cost value on the way per product, over the open dispatches (status
     * {@code in_transit}) whose source or destination is one of {@code branchIds} (never empty).
     * The stock is in neither branch's balance while it is on the way, so this is its own column.
     */
    Map<UUID, OnTheWay> onTheWay(List<UUID> productIds, List<UUID> branchIds) {
        Map<UUID, OnTheWay> out = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT l.product_id, sum(l.qty) AS qty, sum(l.line_cost_minor) AS value
                          FROM retail_transfer_lines l JOIN retail_transfers t ON t.id = l.transfer_id
                         WHERE t.status = 'in_transit' AND l.product_id IN (:products)
                           AND (t.from_branch_id IN (:branchIds) OR t.to_branch_id IN (:branchIds))
                         GROUP BY l.product_id
                        """)
                .param("products", productIds)
                .param("branchIds", branchIds)
                .query((rs, n) -> out.put(
                        rs.getObject("product_id", UUID.class),
                        new OnTheWay(rs.getBigDecimal("qty"), rs.getLong("value"))))
                .list();
        return out;
    }

    /** The value of every open dispatch from or to one of {@code branchIds} (never empty). */
    long onTheWayValue(List<UUID> branchIds) {
        return jdbc.sql("""
                        SELECT coalesce(sum(cost_total_minor), 0) FROM retail_transfers
                         WHERE status = 'in_transit' AND (from_branch_id IN (:branchIds) OR to_branch_id IN (:branchIds))
                        """).param("branchIds", branchIds).query(Long.class).single();
    }

    /** Every non-zero balance of the given branches with the product's current cost (never empty). */
    List<Holding> holdings(List<UUID> branchIds) {
        return jdbc.sql("""
                        SELECT b.branch_id, b.product_id, b.qty, b.avg_cost_minor AS cost_minor
                          FROM retail_stock_balances b JOIN retail_products p ON p.id = b.product_id
                         WHERE b.qty <> 0 AND b.branch_id IN (:branchIds)
                        """)
                .param("branchIds", branchIds)
                .query((rs, n) -> new Holding(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getLong("cost_minor")))
                .list();
    }
}
