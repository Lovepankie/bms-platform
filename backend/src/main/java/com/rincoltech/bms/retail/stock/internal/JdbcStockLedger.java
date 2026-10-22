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
    static final Set<String> GUARDED = Set.of("sale", "usage", "damage", "transfer_out", "return_to_supplier");

    /** Kinds that carry the transfer they belong to, the movement's source id (FR-RET-16). */
    static final Set<String> TRANSFER = Set.of("transfer_out", "transfer_in");

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final LowStockAlerts lowStock;

    JdbcStockLedger(JdbcClient jdbc, BusinessClock clock, LowStockAlerts lowStock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.lowStock = lowStock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> record(LocalDate businessDate, List<Movement> movements) {
        return write(businessDate, movements, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Shortfall> recordAllowingShortfall(LocalDate businessDate, List<Movement> movements) {
        List<Shortfall> shortfalls = new ArrayList<>();
        write(businessDate, movements, shortfalls);
        shortfalls.sort(Comparator.comparingInt(Shortfall::index));
        return shortfalls;
    }

    /** Writes the movements; with {@code shortfalls} not null a guarded shortfall is collected there instead of refused. */
    private List<UUID> write(LocalDate businessDate, List<Movement> movements, List<Shortfall> shortfalls) {
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
            Locked locked = lockRow(m.branchId(), m.productId());
            BigDecimal current = locked.qty();
            BigDecimal next = current.add(m.qty());
            if (GUARDED.contains(m.kind())
                    && m.qty().signum() < 0
                    && next.compareTo(locked.reserved()) < 0
                    && shortfalls != null) {
                shortfalls.add(new Shortfall(i, m.branchId(), m.productId(), current, locked.reserved()));
            } else if (GUARDED.contains(m.kind()) && m.qty().signum() < 0 && next.compareTo(locked.reserved()) < 0) {
                if (next.signum() < 0 || locked.reserved().signum() == 0) {
                    throw ApiException.rule(
                            "insufficient_stock",
                            itemName(m.productId()) + " has "
                                    + current.stripTrailingZeros().toPlainString()
                                    + " in stock at this branch, so this quantity cannot be taken.");
                }
                // Issue #385, ADR-052: the units are on the shelf but kept for customer orders.
                throw ApiException.rule(
                        "insufficient_stock",
                        itemName(m.productId()) + " has "
                                + current.stripTrailingZeros().toPlainString() + " in stock at this branch, of which "
                                + locked.reserved().stripTrailingZeros().toPlainString()
                                + " are kept for customer orders, so this quantity cannot be taken. Deliver or cancel"
                                + " the order first.");
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
        // Issue #412: an item that stock left may now be low at that branch; the check never fails the movement.
        lowStock.afterOut(movements.stream()
                .filter(m -> m.qty().signum() < 0)
                .map(m -> new LowStockAlerts.Touched(m.branchId(), m.productId()))
                .toList());
        return List.of(ids);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal lock(UUID branchId, UUID productId) {
        return lockBalance(branchId, productId);
    }

    /** The balance and what customer orders keep of it, read under the row's lock. */
    private record Locked(BigDecimal qty, BigDecimal reserved) {}

    /** Creates the balance row on first use, then takes its lock for the rest of the transaction. */
    private BigDecimal lockBalance(UUID branchId, UUID productId) {
        return lockRow(branchId, productId).qty();
    }

    private Locked lockRow(UUID branchId, UUID productId) {
        jdbc.sql("""
                        INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?)
                        ON CONFLICT (tenant_id, branch_id, product_id) DO NOTHING
                        """).params(branchId, productId).update();
        return jdbc.sql(
                        "SELECT qty, reserved_qty FROM retail_stock_balances WHERE branch_id = ? AND product_id = ? FOR UPDATE")
                .params(branchId, productId)
                .query((rs, n) -> new Locked(rs.getBigDecimal("qty"), rs.getBigDecimal("reserved_qty")))
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(List<Hold> holds) {
        for (Hold h : inLockOrder(holds)) {
            Locked locked = lockRow(h.branchId(), h.productId());
            BigDecimal free = locked.qty().subtract(locked.reserved());
            if (free.compareTo(h.qty()) < 0) {
                BigDecimal shown = free.signum() < 0 ? BigDecimal.ZERO : free;
                throw ApiException.rule(
                        "insufficient_stock",
                        itemName(h.productId()) + " has "
                                + shown.stripTrailingZeros().toPlainString()
                                + " free at this branch ("
                                + locked.reserved().stripTrailingZeros().toPlainString()
                                + " kept for other orders), so this order cannot keep "
                                + h.qty().stripTrailingZeros().toPlainString() + ".");
            }
            jdbc.sql("""
                            UPDATE retail_stock_balances SET reserved_qty = reserved_qty + ?, updated_at = now()
                             WHERE branch_id = ? AND product_id = ?
                            """).params(h.qty(), h.branchId(), h.productId()).update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(List<Hold> holds) {
        for (Hold h : inLockOrder(holds)) {
            Locked locked = lockRow(h.branchId(), h.productId());
            if (locked.reserved().compareTo(h.qty()) < 0) {
                throw new IllegalStateException("releasing more than is kept for orders: " + h);
            }
            jdbc.sql("""
                            UPDATE retail_stock_balances SET reserved_qty = reserved_qty - ?, updated_at = now()
                             WHERE branch_id = ? AND product_id = ?
                            """).params(h.qty(), h.branchId(), h.productId()).update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void rehold(List<Hold> released, List<Hold> held) {
        List<Hold> net = new ArrayList<>(held);
        for (Hold h : released) {
            net.add(new Hold(h.branchId(), h.productId(), h.qty().negate()));
        }
        for (Hold h : inLockOrder(net)) {
            if (h.qty().signum() == 0) {
                continue;
            }
            Locked locked = lockRow(h.branchId(), h.productId());
            if (h.qty().signum() < 0 && locked.reserved().compareTo(h.qty().negate()) < 0) {
                throw new IllegalStateException("releasing more than is kept for orders: " + h);
            }
            BigDecimal free = locked.qty().subtract(locked.reserved());
            if (h.qty().signum() > 0 && free.compareTo(h.qty()) < 0) {
                BigDecimal shown = free.signum() < 0 ? BigDecimal.ZERO : free;
                throw ApiException.rule(
                        "insufficient_stock",
                        itemName(h.productId()) + " has "
                                + shown.stripTrailingZeros().toPlainString()
                                + " free at this branch ("
                                + locked.reserved().stripTrailingZeros().toPlainString()
                                + " kept for orders), so this order cannot keep "
                                + h.qty().stripTrailingZeros().toPlainString() + " more.");
            }
            jdbc.sql("""
                            UPDATE retail_stock_balances SET reserved_qty = reserved_qty + ?, updated_at = now()
                             WHERE branch_id = ? AND product_id = ?
                            """).params(h.qty(), h.branchId(), h.productId()).update();
        }
    }

    /**
     * The item as the seller knows it, for every {@code insufficient_stock} refusal in plain words (issue #393): its description,
     * never its id. A product deleted meanwhile (never in practice, the rows stay) reads as "This item".
     */
    private String itemName(UUID productId) {
        return jdbc.sql("SELECT description FROM retail_products WHERE id = ?")
                .param(productId)
                .query(String.class)
                .optional()
                .orElse("This item");
    }

    /** Holds of one branch and product added together, in branch and product order, so concurrent orders cannot deadlock. */
    private static List<Hold> inLockOrder(List<Hold> holds) {
        java.util.Map<List<UUID>, BigDecimal> sums = new java.util.TreeMap<>(
                Comparator.<List<UUID>, UUID>comparing(k -> k.get(0)).thenComparing(k -> k.get(1)));
        for (Hold h : holds) {
            sums.merge(List.of(h.branchId(), h.productId()), h.qty(), BigDecimal::add);
        }
        return sums.entrySet().stream()
                .map(e -> new Hold(e.getKey().get(0), e.getKey().get(1), e.getValue()))
                .toList();
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
        // Issue #412: an item that stock left may now be low at that branch; the check never fails the movement.
        lowStock.afterOut(movements.stream()
                .filter(m -> m.qty().signum() < 0)
                .map(m -> new LowStockAlerts.Touched(m.branchId(), m.productId()))
                .toList());
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
