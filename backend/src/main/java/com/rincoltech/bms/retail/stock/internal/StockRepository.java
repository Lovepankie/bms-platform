package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.stock.LowStockRule;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.internal.StockApi.Correction;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementCorrection;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementRow;
import com.rincoltech.bms.retail.stock.internal.StockApi.StandardPack;
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

    /** What a stock search matches (ADR-039): the code, the description and the category name. */
    static final Search.Fields SEARCH =
            Search.Fields.of().names("p.description", "c.name").codes("p.code");

    /**
     * Active products (inactive ones too when {@code includeInactive}, issue #342), with the branch balance; ordered by code,
     * or by match rank and then code when {@code query} is given.
     */
    List<Search.Ranked<StockRow>> stock(
            UUID branchId,
            Search.Query query,
            UUID categoryId,
            boolean negativeOnly,
            boolean includeInactive,
            String level,
            int afterRank,
            String afterCode,
            UUID afterId,
            int limit) {
        Search.Match match = query == null ? null : query.match(SEARCH);
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.code, p.description, p.category_id, c.name AS category, u.name AS unit, p.sell_minor,
                       coalesce(b.avg_cost_minor, p.cost_minor) AS cost_minor, coalesce(b.qty, 0) AS qty,
                       coalesce(b.reserved_qty, 0) AS reserved_qty, sp.pack_unit, sp.pack_factor
                  FROM retail_products p
                  JOIN retail_units u ON u.id = p.unit_id
                  JOIN retail_categories c ON c.id = p.category_id
                  LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.product_id = p.id
                       AND b.branch_id = :branch
                """ + STANDARD_PACK + """
                 WHERE (p.active OR :includeInactive)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("branch", branchId);
        params.put("includeInactive", includeInactive);
        if (match != null) {
            sql.insert("SELECT ".length(), match.rank() + " AS search_rank, ");
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (categoryId != null) {
            sql.append(" AND p.category_id = :categoryId");
            params.put("categoryId", categoryId);
        }
        if (negativeOnly) {
            sql.append(" AND coalesce(b.qty, 0) < 0");
        }
        if (level != null) {
            sql.append(" AND ")
                    .append(LowStockRule.atBranchSql(
                            level.equals(StockService.LEVEL_LOW),
                            "p.tenant_id",
                            "p.id",
                            ":branch",
                            "coalesce(b.qty, 0)",
                            ":low"));
            params.put("low", StockService.lowStock());
        }
        String statement;
        if (match == null) {
            if (afterCode != null) {
                sql.append(" AND (p.code, p.id) > (:afterCode, :afterId)");
                params.put("afterCode", afterCode);
                params.put("afterId", afterId);
            }
            statement = sql + " ORDER BY p.code, p.id LIMIT :limit";
        } else {
            String keyset = null;
            if (afterCode != null) {
                keyset = "(search_rank, code, id) > (:afterRank, :afterCode, :afterId)";
                params.put("afterRank", afterRank);
                params.put("afterCode", afterCode);
                params.put("afterId", afterId);
            }
            statement = Search.page(sql.toString(), keyset, "search_rank, code, id");
        }
        params.put("limit", limit);
        return jdbc.sql(statement)
                .params(params)
                .query(Search.ranked(
                        (rs, n) -> {
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
                                    rs.getLong("cost_minor"),
                                    rs.getBigDecimal("reserved_qty").signum() == 0
                                            ? null
                                            : Quantities.format(rs.getBigDecimal("reserved_qty")),
                                    standardPack(rs));
                        },
                        match != null))
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
            boolean negative,
            StandardPack standardPack) {}

    /**
     * Issue #650: the pack a product's stock is shown in, its standard pack, by the catalogue's one rule (ADR-103); the
     * service leaves it out while the units capability is off.
     */
    static final String STANDARD_PACK =
            "  LEFT JOIN LATERAL (" + RetailCatalogue.standardPackSql("p") + ") sp ON true\n";

    private static StandardPack standardPack(ResultSet rs) throws SQLException {
        String unit = rs.getString("pack_unit");
        return unit == null ? null : new StandardPack(unit, Quantities.format(rs.getBigDecimal("pack_factor")));
    }

    /**
     * Products with their quantity summed over {@code branchIds} (the caller's scope; never empty),
     * ordered by code. An inactive product is left out unless {@code includeInactive} (issue #342); the stock-by-location
     * report passes {@code keepHeldInactive} so it still shows an inactive product while it holds a quantity there, or
     * while some of it is on its way from or to one of those branches (issue #239).
     */
    List<Search.Ranked<ProductTotal>> allBranches(
            List<UUID> branchIds,
            Search.Query query,
            UUID categoryId,
            boolean negativeOnly,
            boolean includeInactive,
            boolean keepHeldInactive,
            String level,
            int afterRank,
            String afterCode,
            UUID afterId,
            int limit) {
        Search.Match match = query == null ? null : query.match(SEARCH);
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.code, p.description, p.category_id, c.name AS category, u.name AS unit, p.sell_minor,
                       coalesce(round(t.held_value / nullif(t.held_qty, 0)), p.cost_minor)::bigint AS cost_minor,
                       coalesce(t.total, 0) AS total, coalesce(t.negs, 0) > 0 AS negative, sp.pack_unit, sp.pack_factor
                  FROM retail_products p
                  JOIN retail_units u ON u.id = p.unit_id
                  JOIN retail_categories c ON c.id = p.category_id
                  LEFT JOIN (SELECT product_id, sum(qty) AS total, count(*) FILTER (WHERE qty < 0) AS negs,
                                    count(*) FILTER (WHERE qty <> 0) AS held,
                                    sum(qty * avg_cost_minor) FILTER (WHERE qty > 0) AS held_value,
                                    sum(qty) FILTER (WHERE qty > 0) AS held_qty
                               FROM retail_stock_balances WHERE branch_id IN (:branchIds)
                              GROUP BY product_id) t ON t.product_id = p.id
                """ + STANDARD_PACK + """
                 WHERE (p.active OR :includeInactive OR (:keepHeld AND (coalesce(t.held, 0) > 0
                        OR EXISTS (SELECT 1 FROM retail_transfer_lines tl JOIN retail_transfers tt ON tt.id = tl.transfer_id
                                    WHERE tl.product_id = p.id AND tt.status = 'in_transit'
                                      AND (tt.from_branch_id IN (:branchIds) OR tt.to_branch_id IN (:branchIds))))))
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("branchIds", branchIds);
        params.put("includeInactive", includeInactive);
        params.put("keepHeld", keepHeldInactive);
        if (match != null) {
            sql.insert("SELECT ".length(), match.rank() + " AS search_rank, ");
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (categoryId != null) {
            sql.append(" AND p.category_id = :categoryId");
            params.put("categoryId", categoryId);
        }
        if (negativeOnly) {
            sql.append(" AND coalesce(t.negs, 0) > 0");
        }
        if (level != null) {
            // Over the listed branches together: out on the summed balance, low at any one of them (issue #490).
            sql.append(" AND ")
                    .append(LowStockRule.overBranchesSql(
                            level.equals(StockService.LEVEL_LOW),
                            "p.tenant_id",
                            "p.id",
                            "lb.id IN (:branchIds)",
                            "coalesce(t.total, 0)",
                            ":low"));
            params.put("low", StockService.lowStock());
        }
        String statement;
        if (match == null) {
            if (afterCode != null) {
                sql.append(" AND (p.code, p.id) > (:afterCode, :afterId)");
                params.put("afterCode", afterCode);
                params.put("afterId", afterId);
            }
            statement = sql + " ORDER BY p.code, p.id LIMIT :limit";
        } else {
            String keyset = null;
            if (afterCode != null) {
                keyset = "(search_rank, code, id) > (:afterRank, :afterCode, :afterId)";
                params.put("afterRank", afterRank);
                params.put("afterCode", afterCode);
                params.put("afterId", afterId);
            }
            statement = Search.page(sql.toString(), keyset, "search_rank, code, id");
        }
        params.put("limit", limit);
        return jdbc.sql(statement)
                .params(params)
                .query(Search.ranked(
                        (rs, n) -> new ProductTotal(
                                rs.getObject("id", UUID.class),
                                rs.getString("code"),
                                rs.getString("description"),
                                rs.getObject("category_id", UUID.class),
                                rs.getString("category"),
                                rs.getString("unit"),
                                rs.getLong("sell_minor"),
                                rs.getLong("cost_minor"),
                                rs.getBigDecimal("total"),
                                rs.getBoolean("negative"),
                                standardPack(rs)),
                        match != null))
                .list();
    }

    /** The candidate branches holding a non-zero balance of anything; the caller passes its inactive branches in scope. */
    java.util.Set<UUID> branchesHoldingBalance(List<UUID> candidates) {
        if (candidates.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(jdbc.sql(
                        "SELECT DISTINCT branch_id FROM retail_stock_balances WHERE qty <> 0 AND branch_id = ANY(:ids)")
                .param("ids", candidates.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
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

    /** ADR-079: each branch's average cost per base unit of the products, by product and branch. */
    Map<UUID, Map<UUID, Long>> averages(List<UUID> productIds, List<UUID> branchIds) {
        Map<UUID, Map<UUID, Long>> out = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT product_id, branch_id, avg_cost_minor FROM retail_stock_balances
                         WHERE product_id IN (:products) AND branch_id IN (:branchIds)
                        """)
                .param("products", productIds)
                .param("branchIds", branchIds)
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getObject("product_id", UUID.class), k -> new LinkedHashMap<>())
                            .put(rs.getObject("branch_id", UUID.class), rs.getLong("avg_cost_minor"));
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
            boolean newestFirst,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, branch_id, product_id, kind, qty, unit_cost_minor, source_type,
                       source_id, reverses_movement_id, historical, note, recorded_by,
                       (SELECT c.reason_code FROM retail_stock_corrections c WHERE c.movement_id = retail_stock_movements.id) AS correction_reason,
                       (SELECT c.old_qty FROM retail_stock_corrections c WHERE c.movement_id = retail_stock_movements.id) AS correction_old,
                       (SELECT c.new_qty FROM retail_stock_corrections c WHERE c.movement_id = retail_stock_movements.id) AS correction_new
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
        // The local-date test decides; the instant range around it, a day wider than any zone offset,
        // only lets the (tenant_id, occurred_at, id) index narrow the scan (issue #107).
        if (from != null) {
            sql.append(" AND (occurred_at AT TIME ZONE :zone)::date >= :from"
                    + " AND occurred_at >= CAST(CAST(:from AS date) - 1 AS timestamp) AT TIME ZONE :zone");
            params.put("from", from);
        }
        if (to != null) {
            sql.append(" AND (occurred_at AT TIME ZONE :zone)::date <= :to"
                    + " AND occurred_at < CAST(CAST(:to AS date) + 2 AS timestamp) AT TIME ZONE :zone");
            params.put("to", to);
        }
        if (from != null || to != null) {
            params.put("zone", zone);
        }
        if (afterCreated != null) {
            sql.append(
                    newestFirst
                            ? " AND (occurred_at, id) < (:afterCreated, :afterId)"
                            : " AND (occurred_at, id) > (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(
                newestFirst
                        ? " ORDER BY occurred_at DESC, id DESC LIMIT :limit"
                        : " ORDER BY occurred_at, id LIMIT :limit");
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
                        rs.getObject("recorded_by", UUID.class),
                        rs.getString("correction_reason") == null
                                ? null
                                : new MovementCorrection(
                                        rs.getString("correction_reason"),
                                        Quantities.format(rs.getBigDecimal("correction_old")),
                                        Quantities.format(rs.getBigDecimal("correction_new")))))
                .list();
    }

    // ---- Quantity corrections (issue #351, ADR-044) -----------------------------------------

    /** True when a stock-take of the branch that is not committed yet counts the product (its variance is measured from the shelf now). */
    boolean draftStocktakeCovers(UUID branchId, UUID productId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_stocktakes s
                                         JOIN retail_stocktake_lines l ON l.tenant_id = s.tenant_id AND l.stocktake_id = s.id
                                        WHERE s.branch_id = ? AND s.status = 'draft' AND l.product_id = ?)
                        """).params(branchId, productId).query(Boolean.class).single();
    }

    void insertCorrection(
            UUID id,
            UUID branchId,
            UUID productId,
            UUID movementId,
            BigDecimal oldQty,
            BigDecimal newQty,
            String reasonCode,
            String note,
            long unitCostMinor,
            UUID journalEntryId,
            UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_stock_corrections (id, tenant_id, branch_id, product_id, movement_id, old_qty, new_qty,
                            reason_code, note, unit_cost_minor, journal_entry_id, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        branchId,
                        productId,
                        movementId,
                        oldQty,
                        newQty,
                        reasonCode,
                        note,
                        unitCostMinor,
                        journalEntryId,
                        by)
                .update();
    }

    Optional<Correction> correction(UUID id) {
        return jdbc.sql("""
                        SELECT c.id, c.branch_id, c.product_id, p.code, p.description, c.old_qty, c.new_qty, c.reason_code,
                               c.note, c.movement_id, c.created_at, c.created_by
                          FROM retail_stock_corrections c JOIN retail_products p ON p.id = c.product_id
                         WHERE c.id = ?
                        """)
                .param(id)
                .query((rs, n) -> new Correction(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        Quantities.format(rs.getBigDecimal("old_qty")),
                        Quantities.format(rs.getBigDecimal("new_qty")),
                        rs.getString("reason_code"),
                        rs.getString("note"),
                        rs.getObject("movement_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("created_by", UUID.class)))
                .optional();
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

    /** Issue #656: ends a draft count without writing stock. */
    void markDiscarded(UUID id, UUID by) {
        jdbc.sql("""
                        UPDATE retail_stocktakes SET status = 'discarded', discarded_by = ?, discarded_at = now(),
                               updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'draft'
                        """).params(by, id).update();
    }

    /** Issue #681: the ids of the draft counts a person made at a branch, newest first. */
    List<UUID> draftStocktakesBy(UUID branchId, UUID by, int limit) {
        return jdbc.sql("""
                        SELECT id FROM retail_stocktakes
                         WHERE branch_id = ? AND created_by = ? AND status = 'draft'
                         ORDER BY created_at DESC, id LIMIT ?
                        """).params(branchId, by, limit).query(UUID.class).list();
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
