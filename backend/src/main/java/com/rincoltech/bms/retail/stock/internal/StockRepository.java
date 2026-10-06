package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementRow;
import com.rincoltech.bms.retail.stock.internal.StockApi.StockRow;
import com.rincoltech.bms.retail.stock.internal.StockApi.Stocktake;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeLine;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the stock views and stock-takes. Movements and balances are written only by
 * {@link JdbcStockLedger}. Product columns are read from the catalogue's table for display.
 */
@Repository
class StockRepository {

    private final JdbcClient jdbc;

    StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Active products, and inactive ones still holding stock, with the branch balance; ordered by code. */
    List<StockRow> stock(
            UUID branchId,
            String query,
            UUID categoryId,
            boolean negativeOnly,
            Long atMostMilli,
            String afterCode,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.code, p.description, p.category_id, c.name AS category, u.name AS unit, p.sell_minor,
                       p.cost_minor, coalesce(b.qty, 0) AS qty
                  FROM retail_products p
                  JOIN retail_units u ON u.id = p.unit_id
                  JOIN retail_categories c ON c.id = p.category_id
                  LEFT JOIN retail_stock_balances b ON b.product_id = p.id AND b.branch_id = :branch
                 WHERE (p.active OR coalesce(b.qty, 0) <> 0)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("branch", branchId);
        if (query != null) {
            sql.append(" AND (p.code ILIKE :q OR p.description ILIKE :q OR c.name ILIKE :q)");
            params.put(
                    "q", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (categoryId != null) {
            sql.append(" AND p.category_id = :categoryId");
            params.put("categoryId", categoryId);
        }
        if (negativeOnly) {
            sql.append(" AND coalesce(b.qty, 0) < 0");
        }
        if (atMostMilli != null) {
            sql.append(" AND coalesce(b.qty, 0) <= :atMost");
            params.put("atMost", java.math.BigDecimal.valueOf(atMostMilli, 3));
        }
        if (afterCode != null) {
            sql.append(" AND (p.code, p.id) > (:afterCode, :afterId)");
            params.put("afterCode", afterCode);
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY p.code, p.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    BigDecimal qty = rs.getBigDecimal("qty");
                    return new StockRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("code"),
                            rs.getString("description"),
                            rs.getObject("category_id", UUID.class),
                            rs.getString("category"),
                            rs.getString("unit"),
                            Quantities.format(qty),
                            qty.signum() < 0,
                            rs.getLong("sell_minor"),
                            rs.getLong("cost_minor"));
                })
                .list();
    }

    record ProductTotal(
            UUID id,
            String code,
            String description,
            UUID categoryId,
            String category,
            String unit,
            long sellMinor,
            long costMinor,
            BigDecimal total,
            boolean negative) {}

    /**
     * Products with their quantity summed over {@code branchIds} (the caller's scope; never empty),
     * ordered by code. An inactive product shows only while it holds a quantity in those branches.
     */
    List<ProductTotal> allBranches(
            List<UUID> branchIds,
            String query,
            UUID categoryId,
            boolean negativeOnly,
            Long atMostMilli,
            String afterCode,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.code, p.description, p.category_id, c.name AS category, u.name AS unit, p.sell_minor,
                       p.cost_minor, coalesce(t.total, 0) AS total, coalesce(t.negs, 0) > 0 AS negative
                  FROM retail_products p
                  JOIN retail_units u ON u.id = p.unit_id
                  JOIN retail_categories c ON c.id = p.category_id
                  LEFT JOIN (SELECT product_id, sum(qty) AS total, count(*) FILTER (WHERE qty < 0) AS negs,
                                    count(*) FILTER (WHERE qty <> 0) AS held
                               FROM retail_stock_balances WHERE branch_id IN (:branchIds)
                              GROUP BY product_id) t ON t.product_id = p.id
                 WHERE (p.active OR coalesce(t.held, 0) > 0)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("branchIds", branchIds);
        if (query != null) {
            sql.append(" AND (p.code ILIKE :q OR p.description ILIKE :q OR c.name ILIKE :q)");
            params.put(
                    "q", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (categoryId != null) {
            sql.append(" AND p.category_id = :categoryId");
            params.put("categoryId", categoryId);
        }
        if (negativeOnly) {
            sql.append(" AND coalesce(t.negs, 0) > 0");
        }
        if (atMostMilli != null) {
            sql.append(" AND coalesce(t.total, 0) <= :atMost");
            params.put("atMost", java.math.BigDecimal.valueOf(atMostMilli, 3));
        }
        if (afterCode != null) {
            sql.append(" AND (p.code, p.id) > (:afterCode, :afterId)");
            params.put("afterCode", afterCode);
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY p.code, p.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new ProductTotal(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category"),
                        rs.getString("unit"),
                        rs.getLong("sell_minor"),
                        rs.getLong("cost_minor"),
                        rs.getBigDecimal("total"),
                        rs.getBoolean("negative")))
                .list();
    }

    /** Balances of the given products in the given branches, keyed by product then branch. */
    Map<UUID, Map<UUID, BigDecimal>> balances(List<UUID> productIds, List<UUID> branchIds) {
        Map<UUID, Map<UUID, BigDecimal>> out = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT product_id, branch_id, qty FROM retail_stock_balances
                         WHERE product_id IN (:products) AND branch_id IN (:branchIds)
                        """)
                .param("products", productIds)
                .param("branchIds", branchIds)
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getObject("product_id", UUID.class), k -> new LinkedHashMap<>())
                            .put(rs.getObject("branch_id", UUID.class), rs.getBigDecimal("qty"));
                    return null;
                })
                .list();
        return out;
    }

    /** {@code branchIds} is already intersected with the caller's scope; null means every branch. */
    List<MovementRow> movements(
            List<UUID> branchIds,
            UUID productId,
            LocalDate from,
            LocalDate to,
            String zone,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, branch_id, product_id, kind, qty, unit_cost_minor, source_type,
                       source_id, reverses_movement_id, historical, note, recorded_by
                  FROM retail_stock_movements WHERE true
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (productId != null) {
            sql.append(" AND product_id = :productId");
            params.put("productId", productId);
        }
        if (from != null) {
            sql.append(" AND (occurred_at AT TIME ZONE :zone)::date >= :from");
            params.put("from", from);
        }
        if (to != null) {
            sql.append(" AND (occurred_at AT TIME ZONE :zone)::date <= :to");
            params.put("to", to);
        }
        if (from != null || to != null) {
            params.put("zone", zone);
        }
        if (afterCreated != null) {
            sql.append(" AND (occurred_at, id) > (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY occurred_at, id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new MovementRow(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("kind"),
                        Quantities.format(rs.getBigDecimal("qty")),
                        rs.getLong("unit_cost_minor"),
                        rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class),
                        rs.getObject("reverses_movement_id", UUID.class),
                        rs.getBoolean("historical"),
                        rs.getString("note"),
                        rs.getObject("recorded_by", UUID.class)))
                .list();
    }

    // ---- Stock-takes -------------------------------------------------------------------

    boolean productExists(UUID productId) {
        return jdbc.sql("SELECT count(*) FROM retail_products WHERE id = ?")
                        .param(productId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    void insertStocktake(UUID id, UUID branchId, String note, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_stocktakes (id, tenant_id, branch_id, status, note, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, 'draft', ?, ?)
                        """).params(id, branchId, note, by).update();
    }

    void insertStocktakeLine(UUID stocktakeId, UUID productId, BigDecimal counted, BigDecimal expected) {
        jdbc.sql("""
                        INSERT INTO retail_stocktake_lines (id, tenant_id, stocktake_id, product_id, counted_qty, expected_qty)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), stocktakeId, productId, counted, expected)
                .update();
    }

    void commitLine(UUID stocktakeId, UUID productId, BigDecimal variance, long unitCostMinor) {
        jdbc.sql("""
                        UPDATE retail_stocktake_lines SET committed_variance_qty = ?, unit_cost_minor = ?
                         WHERE stocktake_id = ? AND product_id = ?
                        """).params(variance, unitCostMinor, stocktakeId, productId).update();
    }

    void markCommitted(UUID id, UUID by, UUID entryId) {
        jdbc.sql("""
                        UPDATE retail_stocktakes SET status = 'committed', committed_by = ?, committed_at = now(),
                               adjustment_entry_id = ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(by, entryId, id).update();
    }

    Optional<Stocktake> stocktake(UUID id, boolean lock) {
        Optional<Stocktake> header = jdbc.sql("""
                        SELECT id, branch_id, status, note, created_at, created_by, committed_at, committed_by,
                               adjustment_entry_id, version
                          FROM retail_stocktakes WHERE id = ?
                        """ + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query((rs, n) -> new Stocktake(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("note"),
                        List.of(),
                        instant(rs, "created_at"),
                        rs.getObject("created_by", UUID.class),
                        instant(rs, "committed_at"),
                        rs.getObject("committed_by", UUID.class),
                        rs.getObject("adjustment_entry_id", UUID.class),
                        rs.getInt("version")))
                .optional();
        return header.map(h -> new Stocktake(
                h.id(),
                h.branchId(),
                h.status(),
                h.note(),
                lines(h.id()),
                h.createdAt(),
                h.createdBy(),
                h.committedAt(),
                h.committedBy(),
                h.adjustmentEntryId(),
                h.version()));
    }

    private List<StocktakeLine> lines(UUID stocktakeId) {
        return jdbc.sql("""
                        SELECT l.product_id, p.code, p.description, l.counted_qty, l.expected_qty,
                               l.committed_variance_qty, l.unit_cost_minor
                          FROM retail_stocktake_lines l JOIN retail_products p ON p.id = l.product_id
                         WHERE l.stocktake_id = ? ORDER BY p.code, l.product_id
                        """)
                .param(stocktakeId)
                .query((rs, n) -> {
                    BigDecimal counted = rs.getBigDecimal("counted_qty");
                    BigDecimal expected = rs.getBigDecimal("expected_qty");
                    BigDecimal committed = rs.getBigDecimal("committed_variance_qty");
                    return new StocktakeLine(
                            rs.getObject("product_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("description"),
                            Quantities.format(counted),
                            Quantities.format(expected),
                            Quantities.format(counted.subtract(expected)),
                            committed == null ? null : Quantities.format(committed),
                            rs.getObject("unit_cost_minor", Long.class));
                })
                .list();
    }

    // ---- Reconciliation ----------------------------------------------------------------

    record Mismatch(UUID branchId, UUID productId, BigDecimal balance, BigDecimal movements) {}

    /** Every branch and product whose balance differs from the sum of its movements (FR-RET-03). */
    List<Mismatch> mismatches() {
        return jdbc.sql("""
                        SELECT branch_id, product_id, coalesce(b.qty, 0) AS balance, coalesce(m.total, 0) AS movements
                          FROM retail_stock_balances b
                          FULL JOIN (SELECT branch_id, product_id, sum(qty) AS total
                                       FROM retail_stock_movements GROUP BY branch_id, product_id) m
                         USING (branch_id, product_id)
                         WHERE coalesce(b.qty, 0) <> coalesce(m.total, 0)
                         ORDER BY branch_id, product_id
                        """)
                .query((rs, n) -> new Mismatch(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("movements")))
                .list();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
