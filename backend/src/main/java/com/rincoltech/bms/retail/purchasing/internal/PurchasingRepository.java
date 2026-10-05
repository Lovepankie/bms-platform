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
            long lineTotalMinor) {}

    // ---- Suppliers -----------------------------------------------------------------------

    void insertSupplier(Supplier s, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_suppliers (id, tenant_id, name, contact, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(s.id(), s.name(), s.contact(), by).update();
    }

    Optional<Supplier> supplier(UUID id) {
        return jdbc.sql("SELECT id, name, contact, active, created_at FROM retail_suppliers WHERE id = ?")
                .param(id)
                .query(PurchasingRepository::supplier)
                .optional();
    }

    List<Supplier> suppliers() {
        return jdbc.sql("SELECT id, name, contact, active, created_at FROM retail_suppliers ORDER BY lower(name), id")
                .query(PurchasingRepository::supplier)
                .list();
    }

    private static Supplier supplier(ResultSet rs, int n) throws SQLException {
        return new Supplier(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("contact"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
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
                            sell_minor, qty_total, line_total_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        l.id(),
                        purchaseId,
                        l.lineNo(),
                        l.productId(),
                        l.costMinor(),
                        l.sellMinor(),
                        l.qtyTotal(),
                        l.lineTotalMinor())
                .update();
    }

    Optional<Purchase> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, purchase_no, supplier_id, purchased_on, payment_method, currency, total_minor, note,
                               created_at, created_by
                          FROM retail_purchases WHERE id = ?
                        """).param(id).query((rs, n) -> header(rs)).optional().map(p -> withLines(p, null));
    }

    /** {@code branchIds} null means every branch; otherwise purchases that moved stock into one of them. */
    List<Purchase> page(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID supplierId,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.purchase_no, p.supplier_id, p.purchased_on, p.payment_method, p.currency, p.total_minor,
                       p.note, p.created_at, p.created_by
                  FROM retail_purchases p WHERE true
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND EXISTS (SELECT 1 FROM retail_stock_movements m WHERE m.source_type = 'retail.purchase'"
                    + " AND m.source_id = p.id AND m.branch_id IN (:branchIds))");
            params.put("branchIds", branchIds);
        }
        if (from != null) {
            sql.append(" AND p.purchased_on >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND p.purchased_on <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (supplierId != null) {
            sql.append(" AND p.supplier_id = :supplierId");
            params.put("supplierId", supplierId);
        }
        if (afterCreated != null) {
            sql.append(" AND (p.created_at, p.id) > (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY p.created_at, p.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> header(rs)).list().stream()
                .map(p -> withLines(p, branchIds))
                .toList();
    }

    /**
     * The purchase with its lines. For a branch-scoped caller ({@code branchIds} not null) only the
     * scoped branches' quantities are shown, and each line's quantity and total and the purchase
     * total are recomputed from them, rounded per branch as the purchase was (review F11); a line
     * with no quantity in scope is left out.
     */
    private Purchase withLines(Purchase p, List<UUID> branchIds) {
        List<PurchaseLine> lines = jdbc
                .sql("""
                        SELECT l.id, l.line_no, l.product_id, pr.code, pr.description, l.cost_minor, l.sell_minor,
                               l.qty_total, l.line_total_minor
                          FROM retail_purchase_lines l JOIN retail_products pr ON pr.id = l.product_id
                         WHERE l.purchase_id = ? ORDER BY l.line_no
                        """)
                .param(p.id())
                .query((rs, n) -> new PurchaseLine(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getLong("cost_minor"),
                        rs.getObject("sell_minor", Long.class),
                        Quantities.format(rs.getBigDecimal("qty_total")),
                        rs.getLong("line_total_minor"),
                        List.of()))
                .list()
                .stream()
                .map(l -> new PurchaseLine(
                        l.id(),
                        l.lineNo(),
                        l.productId(),
                        l.code(),
                        l.description(),
                        l.costMinor(),
                        l.sellMinor(),
                        l.qtyTotal(),
                        l.lineTotalMinor(),
                        jdbc.sql("""
                                        SELECT branch_id, qty FROM retail_stock_movements
                                         WHERE source_type = 'retail.purchase' AND source_line_id = ?
                                         ORDER BY created_at, id
                                        """)
                                .param(l.id())
                                .query((rs, n) -> new LineBranch(
                                        rs.getObject("branch_id", UUID.class),
                                        Quantities.format(rs.getBigDecimal("qty"))))
                                .list()))
                .map(l -> branchIds == null ? l : scoped(l, branchIds))
                .filter(l -> !l.qtyByBranch().isEmpty())
                .toList();
        long total = branchIds == null
                ? p.totalMinor()
                : lines.stream().mapToLong(PurchaseLine::lineTotalMinor).reduce(0, Math::addExact);
        return new Purchase(
                p.id(),
                p.purchaseNo(),
                p.supplierId(),
                p.purchasedOn(),
                p.paymentMethod(),
                p.currency(),
                total,
                p.note(),
                lines,
                p.createdAt(),
                p.createdBy());
    }

    private static PurchaseLine scoped(PurchaseLine l, List<UUID> branchIds) {
        List<LineBranch> inScope = l.qtyByBranch().stream()
                .filter(b -> branchIds.contains(b.branchId()))
                .toList();
        BigDecimal qty = BigDecimal.ZERO;
        long value = 0;
        for (LineBranch b : inScope) {
            qty = qty.add(new BigDecimal(b.qty()));
            value = Math.addExact(value, Quantities.value(new BigDecimal(b.qty()), l.costMinor()));
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
                inScope);
    }

    private static Purchase header(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
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
                rs.getObject("created_by", UUID.class));
    }
}
