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
}
