package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.StockLedger;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    /** The source type of the entry that posts what an average does not carry (ADR-079). */
    static final String AVERAGE_COST = "retail.average_cost";

    /** The kinds {@link #averaged} is told for a change of value alone ({@link #revalue}): a document, or its void. */
    private static final String REVALUE = "revalue";

    private static final String REVALUE_VOID = "revalue_void";

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final LowStockAlerts lowStock;
    private final RetailBooks books;

    JdbcStockLedger(JdbcClient jdbc, BusinessClock clock, LowStockAlerts lowStock, RetailBooks books) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.lowStock = lowStock;
        this.books = books;
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
        Remainders remainders = new Remainders();
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
            long average = locked.average();
            if (m.unitCostMinor() != average) {
                Quantities.Averaged a = averaged(
                        m.productId(), m.kind(), locked, m.qty(), Quantities.value(m.qty(), m.unitCostMinor()));
                average = a.averageMinor();
                remainders.add(m, id, a.remainderMinor());
            }
            jdbc.sql("""
                            UPDATE retail_stock_balances SET qty = qty + ?, avg_cost_minor = ?, updated_at = now()
                             WHERE branch_id = ? AND product_id = ?
                            """).params(m.qty(), average, m.branchId(), m.productId()).update();
            ids[i] = id;
        }
        remainders.post(businessDate);
        // Issue #412: an item that stock left may now be low at that branch; the check never fails the movement.
        lowStock.afterOut(movements.stream()
                .filter(m -> m.qty().signum() < 0)
                .map(m -> new LowStockAlerts.Touched(m.branchId(), m.productId()))
                .toList());
        return List.of(ids);
    }

    /** The new average, refused when it would fall below zero or above what a cost may be. */
    private Quantities.Averaged averaged(UUID productId, String kind, Locked locked, BigDecimal qty, long valueMinor) {
        Quantities.Averaged a = Quantities.average(locked.qty(), locked.average(), qty, valueMinor);
        if (a.averageMinor() < 0 && REVALUE.equals(kind)) {
            // A supplier price credit takes more off this branch's stock than it is carried at now.
            throw ApiException.rule(
                    "average_cost_negative",
                    itemName(productId) + ": this credit would take more off the stock at this branch than it is worth"
                            + " now, because the stock there is carried at a lower cost. Record a smaller price credit,"
                            + " or send the goods back to the supplier instead.");
        }
        if (a.averageMinor() < 0 && REVALUE_VOID.equals(kind)) {
            // The void of a landed cost or a price credit takes back the part this branch took, from what it holds now.
            throw ApiException.rule(
                    "average_cost_negative",
                    itemName(productId) + ": taking this cost back out would leave the rest of the stock at this branch"
                            + " at a cost below zero, because the goods it was added to have been sold there since. It"
                            + " cannot be voided now.");
        }
        if (a.averageMinor() < 0 && "return_to_supplier".equals(kind)) {
            // Goods sent back leave at their receipt's carrying cost (#199), above what the rest is worth now.
            throw ApiException.rule(
                    "average_cost_negative",
                    itemName(productId) + ": sending this many back at what they were bought for would leave the rest"
                            + " of the stock at this branch at a cost below zero, because it was sold at a higher average"
                            + " since. Send back fewer, or ask the supplier for a price credit instead.");
        }
        if (a.averageMinor() < 0) {
            throw ApiException.rule(
                    "average_cost_negative",
                    itemName(productId) + ": this would leave the rest of the stock at this branch at a cost below"
                            + " zero, because some of it was sold or moved at a higher average since. It cannot be done"
                            + " here. Use Correct the quantity on the stock list, or count the stock.");
        }
        if (a.averageMinor() > RetailCatalogue.MAX_AMOUNT_MINOR) {
            throw ApiException.rule(
                    "amount_out_of_range", itemName(productId) + ": the average buying price is too large to compute.");
        }
        return a;
    }

    /**
     * What the averages did not carry, by branch (ADR-079): one entry per branch, dated as the movements, between inventory and
     * purchase rounding. It is keyed by the first movement it covers, so it is posted once, and has its own source type, so a
     * void that reverses a document's entries never reverses it (the void's own movements re-average and post their own).
     */
    private final class Remainders {
        private final Map<UUID, long[]> amounts = new TreeMap<>();
        private final Map<UUID, Movement> first = new HashMap<>();
        private final Map<UUID, UUID> firstId = new HashMap<>();
        private final Set<UUID> closedOk = new HashSet<>();

        void add(Movement m, UUID movementId, long remainder) {
            if (remainder == 0) {
                return;
            }
            long[] sum = amounts.computeIfAbsent(m.branchId(), k -> new long[1]);
            sum[0] = Math.addExact(sum[0], remainder);
            first.putIfAbsent(m.branchId(), m);
            firstId.putIfAbsent(m.branchId(), movementId);
            if ("transfer_in".equals(m.kind())) {
                // The receipt of goods already on the road to a branch that has closed since (ADR-041).
                closedOk.add(m.branchId());
            }
        }

        void post(LocalDate businessDate) {
            amounts.forEach((branch, sum) -> {
                Movement m = first.get(branch);
                postRemainder(
                        branch,
                        businessDate,
                        sum[0],
                        m.sourceType(),
                        m.sourceId(),
                        firstId.get(branch).toString(),
                        closedOk.contains(branch));
            });
        }
    }

    private void postRemainder(
            UUID branch,
            LocalDate date,
            long remainder,
            String sourceType,
            UUID sourceId,
            String key,
            boolean closedBranchOk) {
        if (remainder == 0) {
            return;
        }
        // Positive: the new average carries less than came in, so purchase rounding takes the rest; negative: more.
        List<Leg> legs = remainder > 0
                ? List.of(Leg.debit("purchase_rounding", remainder), Leg.credit("inventory", remainder))
                : List.of(Leg.debit("inventory", -remainder), Leg.credit("purchase_rounding", -remainder));
        books.post(new Posting(
                branch,
                date,
                "Average cost",
                "Average cost rounding (" + sourceType + ")",
                AVERAGE_COST,
                sourceId,
                AVERAGE_COST + ":" + key,
                legs,
                closedBranchOk));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long averageCost(UUID branchId, UUID productId) {
        return jdbc.sql("""
                        SELECT coalesce(b.avg_cost_minor, p.cost_minor)
                          FROM retail_products p
                          LEFT JOIN retail_stock_balances b ON b.product_id = p.id AND b.branch_id = ?
                         WHERE p.id = ?
                        """).params(branchId, productId).query(Long.class).single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revalue(
            LocalDate businessDate,
            UUID branchId,
            UUID productId,
            long valueMinor,
            String sourceType,
            UUID sourceId,
            boolean reversal) {
        if (valueMinor == 0) {
            return;
        }
        Locked locked = lockRow(branchId, productId);
        Quantities.Averaged a =
                averaged(productId, reversal ? REVALUE_VOID : REVALUE, locked, BigDecimal.ZERO, valueMinor);
        jdbc.sql("""
                        UPDATE retail_stock_balances SET avg_cost_minor = ?, updated_at = now()
                         WHERE branch_id = ? AND product_id = ?
                        """).params(a.averageMinor(), branchId, productId).update();
        postRemainder(
                branchId,
                businessDate,
                a.remainderMinor(),
                sourceType,
                sourceId,
                // A document may revalue one branch and product more than once (two lines of one product), and its
                // request is already idempotent as a whole, so each revaluation's entry has a key of its own.
                UUID.randomUUID().toString(),
                false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal lock(UUID branchId, UUID productId) {
        return lockBalance(branchId, productId);
    }

    /** The balance, what customer orders keep of it and the average cost per base unit, read under the row's lock. */
    private record Locked(BigDecimal qty, BigDecimal reserved, long average) {}

    /** Creates the balance row on first use, then takes its lock for the rest of the transaction. */
    private BigDecimal lockBalance(UUID branchId, UUID productId) {
        return lockRow(branchId, productId).qty();
    }

    private Locked lockRow(UUID branchId, UUID productId) {
        // A branch's first row starts at the product's last paid cost, the average of nothing yet (ADR-079).
        jdbc.sql("""
                        INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id, avg_cost_minor)
                        SELECT current_setting('app.tenant_id')::uuid, ?, p.id, p.cost_minor FROM retail_products p WHERE p.id = ?
                        ON CONFLICT (tenant_id, branch_id, product_id) DO NOTHING
                        """).params(branchId, productId).update();
        return jdbc.sql("""
                        SELECT qty, reserved_qty, avg_cost_minor FROM retail_stock_balances
                         WHERE branch_id = ? AND product_id = ? FOR UPDATE
                        """)
                .params(branchId, productId)
                .query((rs, n) -> new Locked(
                        rs.getBigDecimal("qty"), rs.getBigDecimal("reserved_qty"), rs.getLong("avg_cost_minor")))
                .optional()
                // No row was made: the product does not exist in this business (callers check it first).
                .orElseThrow(() -> ApiException.rule("unknown_product", "No such product."));
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
            Locked locked = lockRow(m.branchId(), m.productId());
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
                            UPDATE retail_stock_balances SET qty = qty + ?, avg_cost_minor = ?, updated_at = now()
                             WHERE branch_id = ? AND product_id = ?
                            """)
                    .params(m.qty(), historicalAverage(locked, m), m.branchId(), m.productId())
                    .update();
            ids[i] = id;
        }
        // Issue #412: an item that stock left may now be low at that branch; the check never fails the movement.
        lowStock.afterOut(movements.stream()
                .filter(m -> m.qty().signum() < 0)
                .map(m -> new LowStockAlerts.Touched(m.branchId(), m.productId()))
                .toList());
        return List.of(ids);
    }

    /**
     * ADR-079: history posts nothing, so its movements leave the average alone, apart from the opening balance of an import
     * ({@code legacy_balance}), which the opening journal values at the cost it carries: into a branch that holds nothing it is
     * the average; added to stock already held it re-averages with it (never below zero); taking stock away leaves it.
     */
    private static long historicalAverage(Locked locked, HistoricalMovement m) {
        if (!"legacy_balance".equals(m.kind())) {
            return locked.average();
        }
        if (locked.qty().signum() <= 0) {
            return m.unitCostMinor();
        }
        if (m.qty().signum() <= 0) {
            return locked.average();
        }
        long value = Quantities.value(m.qty(), m.unitCostMinor());
        return Math.max(
                0,
                Quantities.average(locked.qty(), locked.average(), m.qty(), value)
                        .averageMinor());
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
