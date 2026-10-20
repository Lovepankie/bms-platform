package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.LineBranch;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Purchase;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchaseLine;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Supplier;
import com.rincoltech.bms.retail.stock.Quantities;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for suppliers and purchases. Branch quantities are read back from the purchase movements. */
@Repository
class PurchasingRepository {

    private final JdbcClient jdbc;

    PurchasingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record NewLine(
            UUID id,
            int lineNo,
            UUID productId,
            long costMinor,
            Long sellMinor,
            BigDecimal qtyTotal,
            long lineTotalMinor,
            UUID unitId,
            BigDecimal unitFactor,
            BigDecimal unitQtyTotal,
            Long unitCostMinor) {}

    // ---- Suppliers -----------------------------------------------------------------------

    void insertSupplier(Supplier s, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_suppliers (id, tenant_id, name, contact, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(s.id(), s.name(), s.contact(), by).update();
    }

    /** Rows changed: 0 when the version moved since it was read (the predicate is the optimistic lock). */
    int updateSupplier(UUID id, String name, String contact, boolean active, int expectedVersion) {
        return jdbc.sql(
                        "UPDATE retail_suppliers SET name = ?, contact = ?, active = ?, updated_at = now(), version = version + 1 WHERE id = ? AND version = ?")
                .params(name, contact, active, id, expectedVersion)
                .update();
    }

    Optional<Supplier> supplier(UUID id) {
        return jdbc.sql("SELECT id, name, contact, active, created_at, version FROM retail_suppliers WHERE id = ?")
                .param(id)
                .query(PurchasingRepository::supplier)
                .optional();
    }

    /**
     * Takes the supplier's row lock for the rest of the transaction: the one lock that serialises billing, paying,
     * voiding receipts and voiding bills for a supplier (ADR-033, issue #198), so two requests cannot both spend what
     * is left of a receipt or of a bill. It is FOR NO KEY UPDATE, which does not block the key share that inserting a row
     * with a foreign key to the supplier takes (the lesson of the catalogue's product lock). Every writer takes it first, then
     * the order, then the products and balances. False when there is no such supplier.
     */
    boolean lockSupplier(UUID id) {
        return jdbc.sql("SELECT id FROM retail_suppliers WHERE id = ? FOR NO KEY UPDATE")
                .param(id)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    List<Supplier> suppliers() {
        return jdbc.sql(
                        "SELECT id, name, contact, active, created_at, version FROM retail_suppliers ORDER BY lower(name), id")
                .query(PurchasingRepository::supplier)
                .list();
    }

    private static Supplier supplier(ResultSet rs, int n) throws SQLException {
        return new Supplier(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("contact"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("version"));
    }

    // ---- Purchases -----------------------------------------------------------------------

    void insertPurchase(Purchase p) {
        jdbc.sql("""
                        INSERT INTO retail_purchases (id, tenant_id, purchase_no, supplier_id, purchased_on, payment_method,
                            currency, total_minor, note, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        p.id(),
                        p.purchaseNo(),
                        p.supplierId(),
                        Date.valueOf(p.purchasedOn()),
                        p.paymentMethod(),
                        p.currency(),
                        p.totalMinor(),
                        p.note(),
                        p.createdBy())
                .update();
    }

    void insertLine(UUID purchaseId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_purchase_lines (id, tenant_id, purchase_id, line_no, product_id, cost_minor,
                            sell_minor, qty_total, line_total_minor, unit_id, unit_factor, unit_qty_total, unit_cost_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        l.id(),
                        purchaseId,
                        l.lineNo(),
                        l.productId(),
                        l.costMinor(),
                        l.sellMinor(),
                        l.qtyTotal(),
                        l.lineTotalMinor(),
                        l.unitId(),
                        l.unitFactor(),
                        l.unitQtyTotal(),
                        l.unitCostMinor())
                .update();
    }

    /** The columns of a restock and, when it was voided (issue #351), its void; the void is a row of its own. */
    private static final String HEADER = """
            SELECT p.id, p.purchase_no, p.supplier_id, p.purchased_on, p.payment_method, p.currency, p.total_minor,
                   p.note, p.created_at, p.created_by, v.created_at AS voided_at, v.voided_by, v.voided_on, v.reason AS void_reason
              FROM retail_purchases p
              LEFT JOIN retail_purchase_voids v ON v.tenant_id = p.tenant_id AND v.purchase_id = p.id
            """;

    Optional<Purchase> find(UUID id) {
        return find(id, null);
    }

    /** As above, with the quantities of the scoped branches only when {@code branchIds} is given (a restock with none in scope is absent). */
    Optional<Purchase> find(UUID id, List<UUID> branchIds) {
        return jdbc.sql(HEADER + " WHERE p.id = ?")
                .param(id)
                .query((rs, n) -> header(rs))
                .optional()
                .map(p -> withLines(p, branchIds))
                .filter(p -> branchIds == null || !p.lines().isEmpty());
    }

    /** Serialises two people voiding the same restock; held to the end of the transaction. */
    void lockPurchase(UUID purchaseId) {
        jdbc.sql(
                        "SELECT pg_advisory_xact_lock(hashtextextended(current_setting('app.tenant_id') || ':retail.purchase_void:' || CAST(:id AS text), 0))")
                .param("id", purchaseId)
                .query(rs -> {});
    }

    /** The void of a restock: who, when and why, or empty while it stands. */
    Optional<UUID> voidOf(UUID purchaseId) {
        return jdbc.sql("SELECT id FROM retail_purchase_voids WHERE purchase_id = ?")
                .param(purchaseId)
                .query(UUID.class)
                .optional();
    }

    void insertVoid(UUID purchaseId, LocalDate on, String reason, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_purchase_voids (id, tenant_id, purchase_id, voided_on, reason, voided_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), purchaseId, Date.valueOf(on), reason, by)
                .update();
    }

    /** Imported restocks have stock movements flagged historical and no journal entry; they are not voided here. */
    boolean historical(UUID purchaseId) {
        return jdbc.sql("SELECT historical FROM retail_purchases WHERE id = ?")
                .param(purchaseId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /** True when a supplier payment that stands has been allocated to this credit restock. */
    boolean paidAgainst(UUID purchaseId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_supplier_payment_allocations a
                                         JOIN retail_supplier_payments p ON p.tenant_id = a.tenant_id AND p.id = a.payment_id
                                        WHERE a.purchase_id = ? AND p.status = 'posted')
                        """).param(purchaseId).query(Boolean.class).single();
    }

    /**
     * True when a restock or goods receipt other than this one put stock of the product in after this restock and still
     * stands (not voided): a later cost event, so the voided restock's prices are not the product's to give back. The
     * product's price history may hold no row for it (a later restock at the same prices writes none).
     */
    boolean laterPurchase(UUID productId, UUID purchaseId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                                 SELECT 1 FROM retail_stock_movements m
                                  WHERE m.product_id = :product AND m.kind = 'purchase' AND NOT m.historical
                                    AND NOT (m.source_type = 'retail.purchase' AND m.source_id = :purchase)
                                    AND NOT EXISTS (SELECT 1 FROM retail_stock_movements v
                                                     WHERE v.tenant_id = m.tenant_id AND v.reverses_movement_id = m.id)
                                    AND (m.created_at, m.id) > (SELECT o.created_at, o.id FROM retail_stock_movements o
                                                                 WHERE o.product_id = :product AND o.source_type = 'retail.purchase'
                                                                   AND o.source_id = :purchase
                                                                 ORDER BY o.created_at DESC, o.id DESC LIMIT 1))
                        """)
                .param("product", productId)
                .param("purchase", purchaseId)
                .query(Boolean.class)
                .single();
    }

    /** The journal entries the restock posted, one per branch (the reversals the ledger wrote carry the same source). */
    List<UUID> entriesOf(UUID purchaseId) {
        return jdbc.sql("""
                        SELECT id FROM journal_entries
                         WHERE source_type = 'retail.purchase' AND source_id = ? AND reverses_entry_id IS NULL
                         ORDER BY entry_no
                        """).param(purchaseId).query(UUID.class).list();
    }

    /**
     * {@code branchIds} null means every branch; otherwise purchases that moved stock into one of them. {@code status} is
     * posted or voided, or null for both; {@code newestFirst} reverses the order and the cursor (issue #351).
     */
    List<Purchase> page(
            List<UUID> branchIds,
            List<UUID> listedBranches,
            LocalDate from,
            LocalDate to,
            UUID supplierId,
            UUID categoryId,
            UUID productId,
            String status,
            boolean newestFirst,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder(HEADER + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (listedBranches != null && listedBranches.isEmpty()) {
            return List.of();
        }
        // Which restocks are listed (issue #350): one movement row must match the branch, the category and the item
        // asked
        // for together, as the grouped summary counts them, so a scoped caller cannot probe for what went to branches
        // outside their scope. What each restock then shows is scoped by the caller's own branches (branchIds) alone.
        if (listedBranches != null || categoryId != null || productId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM retail_stock_movements m");
            if (categoryId != null) {
                sql.append(" JOIN retail_products cp ON cp.id = m.product_id");
            }
            sql.append(" WHERE m.source_type = 'retail.purchase' AND m.kind = 'purchase' AND m.source_id = p.id");
            if (listedBranches != null) {
                sql.append(" AND m.branch_id IN (:branchIds)");
                params.put("branchIds", listedBranches);
            }
            if (categoryId != null) {
                sql.append(" AND cp.category_id = :categoryId");
                params.put("categoryId", categoryId);
            }
            if (productId != null) {
                // Issue #384: the id of an item with sizes and colours finds the restocks of any of its variants.
                sql.append(" AND ").append(PurchasesSummaryRepository.productOrItem("m.product_id", "productId"));
                params.put("productId", productId);
            }
            sql.append(")");
        }
        if (from != null) {
            sql.append(" AND p.purchased_on >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND p.purchased_on <= :to");
            params.put("to", Date.valueOf(to));
        }
        PurchasesSummaryRepository.appendSupplier(sql, params, supplierId);
        if ("voided".equals(status)) {
            sql.append(" AND v.id IS NOT NULL");
        } else if ("posted".equals(status)) {
            sql.append(" AND v.id IS NULL");
        }
        if (afterCreated != null) {
            sql.append(
                    newestFirst
                            ? " AND (p.created_at, p.id) < (:afterCreated, :afterId)"
                            : " AND (p.created_at, p.id) > (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(
                newestFirst
                        ? " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit"
                        : " ORDER BY p.created_at, p.id LIMIT :limit");
        params.put("limit", limit);
        return withLines(
                jdbc.sql(sql.toString())
                        .params(params)
                        .query((rs, n) -> header(rs))
                        .list(),
                branchIds);
    }

    /**
     * The purchase with its lines. For a branch-scoped caller ({@code branchIds} not null) only the
     * scoped branches' quantities are shown, and each line's quantity and total and the purchase
     * total are recomputed from them, rounded per branch as the purchase was (review F11); a line
     * with no quantity in scope is left out.
     */
    private Purchase withLines(Purchase p, List<UUID> branchIds) {
        return withLines(List.of(p), branchIds).getFirst();
    }

    /**
     * As above for a page: two statements in all, the lines of every purchase and their branch
     * movements, instead of one per purchase and one per line (issue #107). A purchase line's
     * movements carry the purchase as their source (PurchasingService and JdbcPurchaseHistory write
     * both), so they are read through the source index and grouped by line here.
     */
    private List<Purchase> withLines(List<Purchase> purchases, List<UUID> branchIds) {
        if (purchases.isEmpty()) {
            return purchases;
        }
        List<UUID> ids = purchases.stream().map(Purchase::id).toList();
        Map<UUID, List<LineBranch>> byLine = new HashMap<>();
        jdbc.sql("""
                        SELECT source_line_id, branch_id, qty FROM retail_stock_movements
                         WHERE source_type = 'retail.purchase' AND source_id IN (:ids)
                         ORDER BY created_at, id
                        """).param("ids", ids).query(rs -> {
            byLine.computeIfAbsent(rs.getObject("source_line_id", UUID.class), k -> new ArrayList<>())
                    .add(new LineBranch(
                            rs.getObject("branch_id", UUID.class), Quantities.format(rs.getBigDecimal("qty")), null));
        });
        Map<UUID, List<PurchaseLine>> byPurchase = new HashMap<>();
        jdbc.sql("""
                        SELECT l.purchase_id, l.id, l.line_no, l.product_id, pr.code, pr.description, l.cost_minor,
                               l.sell_minor, l.qty_total, l.line_total_minor, l.unit_id, u.name AS unit, l.unit_factor,
                               l.unit_qty_total, l.unit_cost_minor, bu.name AS base_unit
                          FROM retail_purchase_lines l JOIN retail_products pr ON pr.id = l.product_id
                          JOIN retail_units bu ON bu.id = pr.unit_id
                          LEFT JOIN retail_units u ON u.id = l.unit_id
                         WHERE l.purchase_id IN (:ids) ORDER BY l.purchase_id, l.line_no
                        """).param("ids", ids).query(rs -> {
            UUID lineId = rs.getObject("id", UUID.class);
            byPurchase
                    .computeIfAbsent(rs.getObject("purchase_id", UUID.class), k -> new ArrayList<>())
                    .add(new PurchaseLine(
                            lineId,
                            rs.getInt("line_no"),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("description"),
                            rs.getLong("cost_minor"),
                            rs.getObject("sell_minor", Long.class),
                            Quantities.format(rs.getBigDecimal("qty_total")),
                            rs.getLong("line_total_minor"),
                            inUnit(byLine.getOrDefault(lineId, new ArrayList<>()), rs.getBigDecimal("unit_factor")),
                            rs.getObject("unit_id", UUID.class),
                            rs.getString("unit"),
                            decimal(rs.getBigDecimal("unit_factor")),
                            decimal(rs.getBigDecimal("unit_qty_total")),
                            rs.getObject("unit_cost_minor", Long.class),
                            rs.getString("base_unit")));
        });
        return purchases.stream()
                .map(p -> withLines(p, byPurchase.getOrDefault(p.id(), List.of()), branchIds))
                .toList();
    }

    private static Purchase withLines(Purchase p, List<PurchaseLine> all, List<UUID> branchIds) {
        List<PurchaseLine> lines = all.stream()
                .map(l -> branchIds == null ? l : scoped(l, branchIds))
                .filter(l -> !l.qtyByBranch().isEmpty())
                .toList();
        long total = branchIds == null
                ? p.totalMinor()
                : lines.stream().mapToLong(PurchaseLine::lineTotalMinor).reduce(0, Math::addExact);
        // A scoped caller sees only their branches' quantities; the screen must not offer a void the server will
        // refuse.
        boolean hidden = branchIds != null
                && all.stream()
                        .anyMatch(l -> l.qtyByBranch().stream().anyMatch(b -> !branchIds.contains(b.branchId())));
        return p.withLinesAndTotal(lines, total, hidden);
    }

    private static String decimal(BigDecimal d) {
        return d == null ? null : Quantities.format(d);
    }

    /** A line entered in a pack unit shows each branch's quantity in that unit too; the division is exact. */
    private static List<LineBranch> inUnit(List<LineBranch> branches, BigDecimal factor) {
        if (factor == null) {
            return branches;
        }
        return branches.stream()
                .map(b -> new LineBranch(
                        b.branchId(), b.qty(), Quantities.format(Quantities.fromBase(new BigDecimal(b.qty()), factor))))
                .toList();
    }

    private static PurchaseLine scoped(PurchaseLine l, List<UUID> branchIds) {
        List<LineBranch> inScope = l.qtyByBranch().stream()
                .filter(b -> branchIds.contains(b.branchId()))
                .toList();
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal unitQty = BigDecimal.ZERO;
        long value = 0;
        for (LineBranch b : inScope) {
            qty = qty.add(new BigDecimal(b.qty()));
            if (l.unitId() == null) {
                value = Math.addExact(value, Quantities.value(new BigDecimal(b.qty()), l.costMinor()));
            } else {
                // Rounded per branch as the purchase was, in the unit it was entered in.
                BigDecimal entered = new BigDecimal(b.unitQty());
                unitQty = unitQty.add(entered);
                value = Math.addExact(value, Quantities.value(entered, l.unitCostMinor()));
            }
        }
        return new PurchaseLine(
                l.id(),
                l.lineNo(),
                l.productId(),
                l.code(),
                l.description(),
                l.costMinor(),
                l.sellMinor(),
                Quantities.format(qty),
                value,
                inScope,
                l.unitId(),
                l.unit(),
                l.unitFactor(),
                l.unitId() == null ? null : Quantities.format(unitQty),
                l.unitCostMinor(),
                l.baseUnit());
    }

    private static Purchase header(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp voided = rs.getTimestamp("voided_at");
        return new Purchase(
                rs.getObject("id", UUID.class),
                rs.getString("purchase_no"),
                rs.getObject("supplier_id", UUID.class),
                rs.getDate("purchased_on").toLocalDate(),
                rs.getString("payment_method"),
                rs.getString("currency"),
                rs.getLong("total_minor"),
                rs.getString("note"),
                List.of(),
                created == null ? null : created.toInstant(),
                rs.getObject("created_by", UUID.class),
                voided == null ? "posted" : "voided",
                voided == null ? null : voided.toInstant(),
                rs.getObject("voided_by", UUID.class),
                rs.getDate("voided_on") == null ? null : rs.getDate("voided_on").toLocalDate(),
                rs.getString("void_reason"),
                null);
    }
}
