package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.retail.purchasing.PurchaseHistory;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailHistory;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.HistoricalMovement;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcPurchaseHistory implements PurchaseHistory {

    private final JdbcClient jdbc;
    private final StockLedger stock;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;

    JdbcPurchaseHistory(JdbcClient jdbc, StockLedger stock, TenantSequences sequences, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.sequences = sequences;
        this.tenant = tenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureSupplier(String name) {
        UUID existing = jdbc.sql("SELECT id FROM retail_suppliers WHERE lower(name) = lower(?)")
                .param(name)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            return new Ensured(existing, false);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_suppliers (id, tenant_id, name, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                        """).params(id, name, RetailHistory.IMPORT_ACTOR).update();
        return new Ensured(id, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID importPurchase(
            UUID supplierId,
            UUID productId,
            long costMinor,
            Long sellMinor,
            Map<UUID, BigDecimal> qtyByBranch,
            LocalDate purchasedOn,
            Instant purchasedAt,
            String note) {
        UUID id = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        BigDecimal qtyTotal = BigDecimal.ZERO;
        long total = 0;
        List<HistoricalMovement> movements = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> b : qtyByBranch.entrySet()) {
            qtyTotal = qtyTotal.add(b.getValue());
            total = Math.addExact(total, Quantities.value(b.getValue(), costMinor));
            movements.add(new HistoricalMovement(
                    b.getKey(),
                    productId,
                    "purchase",
                    b.getValue(),
                    costMinor,
                    purchasedAt,
                    PurchasingService.PURCHASE,
                    id,
                    lineId,
                    null));
        }
        jdbc.sql("""
                        INSERT INTO retail_purchases (id, tenant_id, created_at, purchase_no, supplier_id, purchased_on,
                            payment_method, currency, total_minor, historical, note, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, 'cash', ?, ?, true, ?, ?)
                        """)
                .params(
                        id,
                        Timestamp.from(purchasedAt),
                        "RP%08d".formatted(sequences.next("retail_purchase_no")),
                        supplierId,
                        Date.valueOf(purchasedOn),
                        tenant.profile().currency(),
                        total,
                        note,
                        RetailHistory.IMPORT_ACTOR)
                .update();
        jdbc.sql("""
                        INSERT INTO retail_purchase_lines (id, tenant_id, purchase_id, line_no, product_id, cost_minor,
                            sell_minor, qty_total, line_total_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, 1, ?, ?, ?, ?, ?)
                        """)
                .params(lineId, id, productId, costMinor, sellMinor, qtyTotal, total)
                .update();
        stock.recordHistorical(purchasedOn, movements);
        return id;
    }

    // JUSTIFICATION-A3: issue #881 needs the restocks earlier runs imported, a page of items per query.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ImportedRestock> importedRestocks(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        // Each product's lines through its own index lookup (V104), as sameName does (issue #880): OFFSET 0 keeps the
        // planner from a scan of every line; never product_id = ANY(?), which row-level security keeps from its index.
        return jdbc.sql("""
                        SELECT p.id, l.product_id, p.created_at, p.note, l.cost_minor, l.sell_minor
                          FROM unnest(CAST(? AS uuid[])) AS asked(id)
                         CROSS JOIN LATERAL (SELECT l.purchase_id, l.product_id, l.cost_minor, l.sell_minor
                                               FROM retail_purchase_lines l WHERE l.product_id = asked.id OFFSET 0) l
                          JOIN retail_purchases p ON p.id = l.purchase_id AND p.historical
                        """)
                .param(productIds.toArray(UUID[]::new))
                .query((rs, n) -> new ImportedRestock(
                        rs.getObject("id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("note"),
                        rs.getLong("cost_minor"),
                        rs.getObject("sell_minor", Long.class)))
                .list();
    }
}
