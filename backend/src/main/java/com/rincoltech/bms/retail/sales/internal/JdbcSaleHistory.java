package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.retail.sales.SaleHistory;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailHistory;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.HistoricalMovement;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcSaleHistory implements SaleHistory {

    private final JdbcClient jdbc;
    private final StockLedger stock;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;

    JdbcSaleHistory(JdbcClient jdbc, StockLedger stock, TenantSequences sequences, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.sequences = sequences;
        this.tenant = tenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureCustomer(String name, String contact) {
        Optional<UUID> existing = findCustomer(name);
        if (existing.isPresent()) {
            return new Ensured(existing.get(), false);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_customers (id, tenant_id, name, contact, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(id, name, contact, RetailHistory.IMPORT_ACTOR).update();
        return new Ensured(id, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> findCustomer(String name) {
        return jdbc.sql("SELECT id FROM retail_customers WHERE lower(name) = lower(?) ORDER BY created_at, id LIMIT 1")
                .param(name)
                .query(UUID.class)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID importSale(Sale s) {
        UUID id = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        boolean credit = s.paymentMethod().equals("credit");
        long total = Quantities.value(s.qty(), s.unitPriceMinor());
        long cost = Quantities.value(s.qty(), s.unitCostMinor());
        jdbc.sql("""
                        INSERT INTO retail_sales (id, tenant_id, created_at, branch_id, sale_no, sale_date, payment_method,
                            customer_id, buyer_name, buyer_contact, due_date, currency, total_minor, cost_total_minor,
                            paid_minor, status, historical, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :branch, :no, :date, :method, :customer,
                            :buyerName, :buyerContact, :due, :currency, :total, :cost, :paid, 'completed', true, :by)
                        """)
                .param("id", id)
                .param("at", Timestamp.from(s.soldAt()))
                .param("branch", s.branchId())
                .param("no", "RS%08d".formatted(sequences.next("retail_sale_no")))
                .param("date", Date.valueOf(s.saleDate()))
                .param("method", s.paymentMethod())
                .param("customer", s.customerId())
                .param("buyerName", s.buyerName())
                .param("buyerContact", s.buyerContact())
                .param("due", credit && s.dueDate() != null ? Date.valueOf(s.dueDate()) : null)
                .param("currency", tenant.profile().currency())
                .param("total", total)
                .param("cost", cost)
                .param("paid", credit ? 0 : total)
                .param("by", RetailHistory.IMPORT_ACTOR)
                .update();
        jdbc.sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, 1, ?, ?, ?, ?, ?, ?)
                        """)
                .params(lineId, id, s.productId(), s.qty(), s.unitPriceMinor(), s.unitCostMinor(), total, cost)
                .update();
        stock.recordHistorical(
                s.saleDate(),
                List.of(new HistoricalMovement(
                        s.branchId(),
                        s.productId(),
                        "sale",
                        s.qty().negate(),
                        s.unitCostMinor(),
                        s.soldAt(),
                        SalesService.SALE,
                        id,
                        lineId,
                        null)));
        return id;
    }
}
