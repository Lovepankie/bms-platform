package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.retail.sales.internal.SalesApi.Customer;
import com.rincoltech.bms.retail.sales.internal.SalesApi.OpenSale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Payment;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLine;
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

/** SQL for sales and credit buyers. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class SalesRepository {

    private static final String SELECT_SALE = """
            SELECT id, sale_no, branch_id, sale_date, payment_method, customer_id, buyer_name, buyer_contact,
                   due_date, status, currency, total_minor, paid_minor, cost_total_minor, historical, created_at,
                   created_by, voided_at, voided_by, void_reason, version, sale_entry_id, cost_entry_id
              FROM retail_sales
            """;

    private final JdbcClient jdbc;

    SalesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A sale header with its journal entries, for the service. */
    record Header(Sale sale, UUID saleEntryId, UUID costEntryId) {}

    /** A line to insert. */
    record NewLine(
            UUID id,
            int lineNo,
            UUID productId,
            BigDecimal qty,
            long unitPriceMinor,
            long unitCostMinor,
            long lineTotalMinor,
            long lineCostMinor) {}

    // ---- Sales ---------------------------------------------------------------------------

    Optional<Header> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT_SALE + " WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query((rs, n) -> new Header(
                        header(rs),
                        rs.getObject("sale_entry_id", UUID.class),
                        rs.getObject("cost_entry_id", UUID.class)))
                .optional()
                .map(h -> new Header(withLines(h.sale()), h.saleEntryId(), h.costEntryId()));
    }

    List<Sale> page(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT_SALE + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (from != null) {
            sql.append(" AND sale_date >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND sale_date <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (customerId != null) {
            sql.append(" AND customer_id = :customerId");
            params.put("customerId", customerId);
        }
        if (afterCreated != null) {
            sql.append(" AND (created_at, id) > (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY created_at, id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> header(rs)).list().stream()
                .map(this::withLines)
                .toList();
    }

    void insert(Sale s) {
        jdbc.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                            buyer_name, buyer_contact, due_date, currency, total_minor, cost_total_minor, paid_minor, status,
                            created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branch, :no, :date, :method, :customer,
                            :buyerName, :buyerContact, :due, :currency, :total, :cost, :paid, 'completed', :by)
                        """)
                .param("id", s.id())
                .param("branch", s.branchId())
                .param("no", s.saleNo())
                .param("date", Date.valueOf(s.saleDate()))
                .param("method", s.paymentMethod())
                .param("customer", s.customerId())
                .param("buyerName", s.buyerName())
                .param("buyerContact", s.buyerContact())
                .param("due", s.dueDate() == null ? null : Date.valueOf(s.dueDate()))
                .param("currency", s.currency())
                .param("total", s.totalMinor())
                .param("cost", s.costTotalMinor())
                .param("paid", s.paidMinor())
                .param("by", s.createdBy())
                .update();
    }

    void insertLine(UUID saleId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        l.id(),
                        saleId,
                        l.lineNo(),
                        l.productId(),
                        l.qty(),
                        l.unitPriceMinor(),
                        l.unitCostMinor(),
                        l.lineTotalMinor(),
                        l.lineCostMinor())
                .update();
    }

    void setEntries(UUID saleId, UUID saleEntryId, UUID costEntryId) {
        jdbc.sql("UPDATE retail_sales SET sale_entry_id = ?, cost_entry_id = ? WHERE id = ?")
                .params(saleEntryId, costEntryId, saleId)
                .update();
    }

    void markVoided(UUID saleId, UUID by, String reason) {
        jdbc.sql("""
                        UPDATE retail_sales SET status = 'voided', voided_at = now(), voided_by = ?, void_reason = ?,
                               updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(by, reason, saleId).update();
    }

    private Sale withLines(Sale s) {
        List<SaleLine> lines = jdbc.sql("""
                        SELECT l.id, l.line_no, l.product_id, p.code, p.description, l.qty, l.unit_price_minor,
                               l.line_total_minor, l.unit_cost_minor, l.line_cost_minor
                          FROM retail_sale_lines l JOIN retail_products p ON p.id = l.product_id
                         WHERE l.sale_id = ? ORDER BY l.line_no
                        """)
                .param(s.id())
                .query((rs, n) -> new SaleLine(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        Quantities.format(rs.getBigDecimal("qty")),
                        rs.getLong("unit_price_minor"),
                        rs.getLong("line_total_minor"),
                        rs.getLong("unit_cost_minor"),
                        rs.getLong("line_cost_minor")))
                .list();
        return new Sale(
                s.id(),
                s.saleNo(),
                s.branchId(),
                s.saleDate(),
                s.paymentMethod(),
                s.customerId(),
                s.buyerName(),
                s.buyerContact(),
                s.dueDate(),
                s.status(),
                s.currency(),
                s.totalMinor(),
                s.paidMinor(),
                s.balanceMinor(),
                s.costTotalMinor(),
                s.profitMinor(),
                lines,
                s.historical(),
                s.createdAt(),
                s.createdBy(),
                s.voidedAt(),
                s.voidedBy(),
                s.voidReason(),
                s.version());
    }

    private static Sale header(ResultSet rs) throws SQLException {
        long total = rs.getLong("total_minor");
        long paid = rs.getLong("paid_minor");
        long cost = rs.getLong("cost_total_minor");
        boolean voided = rs.getString("status").equals("voided");
        Date due = rs.getDate("due_date");
        return new Sale(
                rs.getObject("id", UUID.class),
                rs.getString("sale_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getDate("sale_date").toLocalDate(),
                rs.getString("payment_method"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("buyer_name"),
                rs.getString("buyer_contact"),
                due == null ? null : due.toLocalDate(),
                rs.getString("status"),
                rs.getString("currency"),
                total,
                paid,
                voided ? 0 : total - paid,
                cost,
                total - cost,
                List.of(),
                rs.getBoolean("historical"),
                instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class),
                instant(rs, "voided_at"),
                rs.getObject("voided_by", UUID.class),
                rs.getString("void_reason"),
                rs.getInt("version"));
    }

    // ---- Payments --------------------------------------------------------------------------

    void insertPayment(Payment p) {
        jdbc.sql("""
                        INSERT INTO retail_sale_payments (id, tenant_id, sale_id, amount_minor, currency, method, paid_on,
                            journal_entry_id, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        p.id(),
                        p.saleId(),
                        p.amountMinor(),
                        p.currency(),
                        p.method(),
                        Date.valueOf(p.paidOn()),
                        p.journalEntryId(),
                        p.createdBy())
                .update();
    }

    void addPaid(UUID saleId, long amountMinor) {
        jdbc.sql("""
                        UPDATE retail_sales SET paid_minor = paid_minor + ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(amountMinor, saleId).update();
    }

    List<Payment> payments(UUID saleId) {
        return jdbc.sql("""
                        SELECT id, sale_id, amount_minor, currency, method, paid_on, journal_entry_id, created_at, created_by
                          FROM retail_sale_payments WHERE sale_id = ? ORDER BY paid_on, created_at, id
                        """)
                .param(saleId)
                .query((rs, n) -> new Payment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sale_id", UUID.class),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        rs.getString("method"),
                        rs.getDate("paid_on").toLocalDate(),
                        rs.getObject("journal_entry_id", UUID.class),
                        instant(rs, "created_at"),
                        rs.getObject("created_by", UUID.class)))
                .list();
    }

    // ---- Customers -----------------------------------------------------------------------

    void insertCustomer(Customer c, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_customers (id, tenant_id, name, contact, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(c.id(), c.name(), c.contact(), by).update();
    }

    Optional<Customer> customer(UUID id) {
        return jdbc.sql("SELECT id, name, contact, created_at FROM retail_customers WHERE id = ?")
                .param(id)
                .query(SalesRepository::customer)
                .optional();
    }

    List<Customer> customers(String query, int limit) {
        if (query == null) {
            return jdbc.sql(
                            "SELECT id, name, contact, created_at FROM retail_customers ORDER BY lower(name), id LIMIT ?")
                    .param(limit)
                    .query(SalesRepository::customer)
                    .list();
        }
        return jdbc.sql("""
                        SELECT id, name, contact, created_at FROM retail_customers
                         WHERE name ILIKE ? ORDER BY lower(name), id LIMIT ?
                        """)
                .params("%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", limit)
                .query(SalesRepository::customer)
                .list();
    }

    /** Credit sales of a customer that still owe, in the given branches (null: every branch). */
    List<OpenSale> openSales(UUID customerId, List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customer", customerId);
        String branchFilter = "";
        if (branchIds != null) {
            branchFilter = " AND branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("""
                        SELECT id, sale_no, branch_id, sale_date, due_date, total_minor, paid_minor FROM retail_sales
                         WHERE customer_id = :customer AND payment_method = 'credit' AND status = 'completed'
                           AND paid_minor < total_minor""" + branchFilter + " ORDER BY sale_date, created_at, id")
                .params(params)
                .query((rs, n) -> {
                    Date due = rs.getDate("due_date");
                    return new OpenSale(
                            rs.getObject("id", UUID.class),
                            rs.getString("sale_no"),
                            rs.getObject("branch_id", UUID.class),
                            rs.getDate("sale_date").toLocalDate(),
                            due == null ? null : due.toLocalDate(),
                            rs.getLong("total_minor"),
                            rs.getLong("paid_minor"),
                            rs.getLong("total_minor") - rs.getLong("paid_minor"));
                })
                .list();
    }

    private static Customer customer(ResultSet rs, int n) throws SQLException {
        return new Customer(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("contact"),
                instant(rs, "created_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
