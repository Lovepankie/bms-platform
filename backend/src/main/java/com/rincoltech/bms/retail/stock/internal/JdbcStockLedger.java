package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.stock.StockLedger;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcStockLedger implements StockLedger {

    /** Kinds refused when they would take a balance below zero; there is no setting (ADR-020 decision 4). */
    static final Set<String> GUARDED = Set.of("sale", "usage", "damage", "transfer_out");

    /** Kinds that carry the transfer they belong to, the movement's source id (FR-RET-16). */
    static final Set<String> TRANSFER = Set.of("transfer_out", "transfer_in");

    private final JdbcClient jdbc;
    private final BusinessClock clock;

    JdbcStockLedger(JdbcClient jdbc, BusinessClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> record(LocalDate businessDate, List<Movement> movements) {
        UUID[] ids = new UUID[movements.size()];
        Integer[] order = new Integer[movements.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(
                order,
                Comparator.comparing((Integer i) -> movements.get(i).branchId())
                        .thenComparing(i -> movements.get(i).productId())
                        .thenComparing(i -> i));
        UUID by = CurrentPrincipal.get().map(Principal::userId).orElse(null);
        Timestamp now = Timestamp.from(clock.now());
        for (int i : order) {
            Movement m = movements.get(i);
            BigDecimal current = lockBalance(m.branchId(), m.productId());
            BigDecimal next = current.add(m.qty());
            if (GUARDED.contains(m.kind()) && m.qty().signum() < 0 && next.signum() < 0) {
                throw ApiException.rule(
                        "insufficient_stock",
                        "Product " + m.productId() + " has "
                                + current.stripTrailingZeros().toPlainString()
                                + " in stock at this branch, so this quantity cannot be taken.");
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
                                unit_cost_minor, source_type, source_id, source_line_id, reverses_movement_id, note,
                                recorded_by, transfer_id)
                            VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :businessDate, :branch, :product, :kind, :qty,
                                :cost, :sourceType, :sourceId, :sourceLineId, :reverses, :note, :by, :transfer)
                            """)
                    .param("id", id)
                    .param("at", now)
                    .param("businessDate", java.sql.Date.valueOf(businessDate))
                    .param("branch", m.branchId())
                    .param("product", m.productId())
                    .param("kind", m.kind())
                    .param("qty", m.qty())
                    .param("cost", m.unitCostMinor())
                    .param("sourceType", m.sourceType())
                    .param("sourceId", m.sourceId())
                    .param("sourceLineId", m.sourceLineId())
                    .param("reverses", m.reversesMovementId())
                    .param("note", m.note())
                    .param("by", by)
                    .param("transfer", TRANSFER.contains(m.kind()) ? m.sourceId() : null)
                    .update();
            jdbc.sql("""
                            UPDATE retail_stock_balances SET qty = qty + ?, updated_at = now()
                             WHERE branch_id = ? AND product_id = ?
                            """).params(m.qty(), m.branchId(), m.productId()).update();
            ids[i] = id;
        }
        return List.of(ids);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal lock(UUID branchId, UUID productId) {
        return lockBalance(branchId, productId);
    }

    /** Creates the balance row on first use, then takes its lock for the rest of the transaction. */
    private BigDecimal lockBalance(UUID branchId, UUID productId) {
        jdbc.sql("""
                        INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?)
                        ON CONFLICT (tenant_id, branch_id, product_id) DO NOTHING
                        """).params(branchId, productId).update();
        return jdbc.sql("SELECT qty FROM retail_stock_balances WHERE branch_id = ? AND product_id = ? FOR UPDATE")
                .params(branchId, productId)
                .query(BigDecimal.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Recorded> bySource(String sourceType, UUID sourceId) {
        return new ArrayList<>(jdbc.sql("""
                        SELECT id, branch_id, product_id, kind, qty, unit_cost_minor, source_line_id
                          FROM retail_stock_movements WHERE source_type = ? AND source_id = ?
                         ORDER BY created_at, id
                        """)
                .params(sourceType, sourceId)
                .query((rs, n) -> new Recorded(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("kind"),
                        rs.getBigDecimal("qty"),
                        rs.getLong("unit_cost_minor"),
                        rs.getObject("source_line_id", UUID.class)))
                .list());
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal balance(UUID branchId, UUID productId) {
        return jdbc.sql("SELECT qty FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branchId, productId)
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO.setScale(3));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> recordHistorical(LocalDate businessDate, List<HistoricalMovement> movements) {
        UUID[] ids = new UUID[movements.size()];
        Integer[] order = new Integer[movements.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(
                order,
                Comparator.comparing((Integer i) -> movements.get(i).branchId())
                        .thenComparing(i -> movements.get(i).productId())
                        .thenComparing(i -> i));
        for (int i : order) {
            HistoricalMovement m = movements.get(i);
            lockBalance(m.branchId(), m.productId());
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind,
                                qty, unit_cost_minor, source_type, source_id, source_line_id, historical, note)
                            VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :businessDate, :branch, :product, :kind,
                                :qty, :cost, :sourceType, :sourceId, :sourceLineId, true, :note)
                            """)
                    .param("id", id)
                    .param("at", Timestamp.from(m.occurredAt()))
                    .param("businessDate", java.sql.Date.valueOf(businessDate))
                    .param("branch", m.branchId())
                    .param("product", m.productId())
                    .param("kind", m.kind())
                    .param("qty", m.qty())
                    .param("cost", m.unitCostMinor())
                    .param("sourceType", m.sourceType())
                    .param("sourceId", m.sourceId())
                    .param("sourceLineId", m.sourceLineId())
                    .param("note", m.note())
                    .update();
            jdbc.sql("""
                            UPDATE retail_stock_balances SET qty = qty + ?, updated_at = now()
                             WHERE branch_id = ? AND product_id = ?
                            """).params(m.qty(), m.branchId(), m.productId()).update();
            ids[i] = id;
        }
        return List.of(ids);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Position> positions() {
        return jdbc.sql("""
                        SELECT b.branch_id, b.product_id, b.qty,
                               coalesce(sum(m.qty) FILTER (WHERE m.historical AND m.kind <> 'legacy_balance'), 0) AS hist,
                               bool_or(m.kind = 'legacy_balance') IS TRUE AS legacy
                          FROM retail_stock_balances b
                          LEFT JOIN retail_stock_movements m ON m.branch_id = b.branch_id AND m.product_id = b.product_id
                         GROUP BY b.branch_id, b.product_id, b.qty
                        """)
                .query((rs, n) -> new Position(
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("hist"),
                        rs.getBoolean("legacy")))
                .list();
    }
}
