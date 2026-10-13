package com.rincoltech.bms.retail.purchasing.internal;

import static com.rincoltech.bms.retail.purchasing.internal.OrdersRepository.instant;

import com.rincoltech.bms.kernel.Search;
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

/** SQL for supplier invoices. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class BillsRepository {

    /** What a bill has been paid, by the payments that stand. */
    private static final String PAID = """
            coalesce((SELECT sum(a.amount_minor) FROM retail_supplier_payment_allocations a
                        JOIN retail_supplier_payments p ON p.tenant_id = a.tenant_id AND p.id = a.payment_id AND p.status = 'posted'
                       WHERE a.tenant_id = b.tenant_id AND a.bill_id = b.id), 0)""";

    private static final String SELECT_BILL = """
            SELECT b.id, b.bill_no, b.branch_seq, b.branch_id, b.supplier_id, s.name AS supplier_name, b.supplier_ref,
                   b.bill_date, b.due_date, b.currency, b.kind, b.total_minor, b.cleared_minor, b.variance_minor,
                   b.variance_reason, b.note, b.status, %s AS paid_minor,
                   (SELECT count(*) FROM retail_supplier_bill_lines l WHERE l.tenant_id = b.tenant_id AND l.bill_id = b.id) AS line_count,
                   b.created_at, b.created_by, b.voided_at, b.voided_on, b.voided_by, b.void_reason, b.version
              FROM retail_supplier_bills b
              JOIN retail_suppliers s ON s.tenant_id = b.tenant_id AND s.id = b.supplier_id
            """.formatted(PAID);

    /** What the bill list matches (ADR-039): our number, the supplier's invoice number and the supplier's name. */
    static final Search.Fields SEARCH =
            Search.Fields.of().codes("b.bill_no", "b.supplier_ref").names("s.name");

    private final JdbcClient jdbc;

    BillsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(
            UUID id,
            String billNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            String supplierRef,
            LocalDate billDate,
            LocalDate dueDate,
            String currency,
            String kind,
            long totalMinor,
            long clearedMinor,
            long varianceMinor,
            String varianceReason,
            String note,
            String status,
            long paidMinor,
            int lineCount,
            Instant createdAt,
            UUID createdBy,
            Instant voidedAt,
            LocalDate voidedOn,
            UUID voidedBy,
            String voidReason,
            int version) {}

    record LineRow(
            UUID id,
            UUID billId,
            int lineNo,
            UUID receiptLineId,
            UUID receiptId,
            String receiptNo,
            UUID productId,
            String code,
            String description,
            BigDecimal qty,
            UUID unitId,
            String unit,
            BigDecimal unitQty,
            long unitPriceMinor,
            long lineTotalMinor,
            long clearedMinor,
            long varianceMinor) {}

    /** A receipt line as it stands for billing: what was received, what is billed, what that cleared. */
    record Receivable(
            UUID receiptLineId,
            UUID receiptId,
            String receiptNo,
            UUID branchId,
            LocalDate receivedOn,
            String orderNo,
            UUID supplierId,
            String status,
            UUID productId,
            String code,
            String description,
            BigDecimal qty,
            UUID unitId,
            String unit,
            BigDecimal unitQty,
            BigDecimal unitFactor,
            long enteredCostMinor,
            long lineTotalMinor,
            BigDecimal billedQty,
            long clearedMinor) {}

    record NewBill(
            UUID id,
            String billNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            String supplierRef,
            LocalDate billDate,
            LocalDate dueDate,
            String currency,
            long totalMinor,
            long clearedMinor,
            long varianceMinor,
            String varianceReason,
            String note,
            UUID createdBy) {}

    record NewLine(
            UUID id,
            int lineNo,
            UUID receiptLineId,
            BigDecimal qty,
            BigDecimal unitQty,
            long unitPriceMinor,
            long lineTotalMinor,
            long clearedMinor,
            long varianceMinor) {}

    void insert(NewBill b) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_bills (id, tenant_id, bill_no, branch_seq, branch_id, supplier_id, supplier_ref,
                            bill_date, due_date, currency, total_minor, cleared_minor, variance_minor, variance_reason, note, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :no, :seq, :branch, :supplier, :ref, :billDate, :due,
                            :currency, :total, :cleared, :variance, :reason, :note, :by)
                        """)
                .param("id", b.id())
                .param("no", b.billNo())
                .param("seq", b.branchSeq())
                .param("branch", b.branchId())
                .param("supplier", b.supplierId())
                .param("ref", b.supplierRef())
                .param("billDate", Date.valueOf(b.billDate()))
                .param("due", Date.valueOf(b.dueDate()))
                .param("currency", b.currency())
                .param("total", b.totalMinor())
                .param("cleared", b.clearedMinor())
                .param("variance", b.varianceMinor())
                .param("reason", b.varianceReason())
                .param("note", b.note())
                .param("by", b.createdBy())
                .update();
    }

    void insertLine(UUID billId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_bill_lines (id, tenant_id, bill_id, line_no, receipt_line_id, qty, unit_qty,
                            unit_price_minor, line_total_minor, cleared_minor, variance_minor)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :bill, :no, :receiptLine, :qty, :unitQty, :price,
                            :total, :cleared, :variance)
                        """)
                .param("id", l.id())
                .param("bill", billId)
                .param("no", l.lineNo())
                .param("receiptLine", l.receiptLineId())
                .param("qty", l.qty())
                .param("unitQty", l.unitQty())
                .param("price", l.unitPriceMinor())
                .param("total", l.lineTotalMinor())
                .param("cleared", l.clearedMinor())
                .param("variance", l.varianceMinor())
                .update();
    }

    void insertEntry(UUID billId, UUID branchId, UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_bill_entries (tenant_id, bill_id, branch_id, entry_id)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(billId, branchId, entryId).update();
    }

    /** The entries a bill posted, one per branch, in a stable order. */
    List<UUID> entries(UUID billId) {
        return jdbc.sql("SELECT entry_id FROM retail_supplier_bill_entries WHERE bill_id = ? ORDER BY branch_id")
                .param(billId)
                .query(UUID.class)
                .list();
    }

    /** The branches a bill posted entries in. */
    List<UUID> entryBranches(UUID billId) {
        return jdbc.sql("SELECT branch_id FROM retail_supplier_bill_entries WHERE bill_id = ?")
                .param(billId)
                .query(UUID.class)
                .list();
    }

    int markVoided(UUID id, UUID by, LocalDate on, Instant at, String reason) {
        return jdbc.sql("""
                        UPDATE retail_supplier_bills SET status = 'voided', voided_at = ?, voided_on = ?, voided_by = ?,
                               void_reason = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'posted'
                        """)
                .params(Timestamp.from(at), Date.valueOf(on), by, reason, id)
                .update();
    }

    /** True when a payment that stands allocates anything to the bill. */
    boolean paid(UUID billId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_supplier_payment_allocations a
                                         JOIN retail_supplier_payments p ON p.tenant_id = a.tenant_id AND p.id = a.payment_id AND p.status = 'posted'
                                        WHERE a.bill_id = ?)
                        """).param(billId).query(Boolean.class).single();
    }

    boolean refExists(UUID supplierId, String ref) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_supplier_bills
                                        WHERE supplier_id = ? AND lower(supplier_ref) = lower(?) AND status = 'posted')
                        """).params(supplierId, ref).query(Boolean.class).single();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT_BILL + " WHERE b.id = ?")
                .param(id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    Optional<Row> lock(UUID id) {
        return jdbc.sql(SELECT_BILL + " WHERE b.id = ? FOR UPDATE OF b")
                .param(id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    List<Row> page(
            List<UUID> branchIds,
            UUID supplierId,
            String status,
            boolean openOnly,
            LocalDate from,
            LocalDate to,
            Search.Query query,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT_BILL + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            sql.append(" AND b.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (supplierId != null) {
            sql.append(" AND b.supplier_id = :supplierId");
            params.put("supplierId", supplierId);
        }
        if (status != null) {
            sql.append(" AND b.status = :status");
            params.put("status", status);
        }
        if (openOnly) {
            sql.append(" AND b.status = 'posted' AND b.total_minor > ").append(PAID);
        }
        if (from != null) {
            sql.append(" AND b.bill_date >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND b.bill_date <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (query != null) {
            Search.Match match = query.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(" AND (b.created_at, b.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY b.created_at DESC, b.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> row(rs)).list();
    }

    Map<UUID, List<LineRow>> lines(List<UUID> billIds) {
        Map<UUID, List<LineRow>> out = new HashMap<>();
        if (billIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT l.id, l.bill_id, l.line_no, l.receipt_line_id, rl.receipt_id, r.receipt_no, rl.product_id, p.code,
                               p.description, l.qty, rl.unit_id, u.name AS unit, l.unit_qty, l.unit_price_minor,
                               l.line_total_minor, l.cleared_minor, l.variance_minor
                          FROM retail_supplier_bill_lines l
                          JOIN retail_goods_receipt_lines rl ON rl.tenant_id = l.tenant_id AND rl.id = l.receipt_line_id
                          JOIN retail_goods_receipts r ON r.tenant_id = rl.tenant_id AND r.id = rl.receipt_id
                          JOIN retail_products p ON p.tenant_id = rl.tenant_id AND p.id = rl.product_id
                          LEFT JOIN retail_units u ON u.tenant_id = rl.tenant_id AND u.id = rl.unit_id
                         WHERE l.bill_id IN (:ids) ORDER BY l.bill_id, l.line_no
                        """).param("ids", billIds).query(rs -> {
            out.computeIfAbsent(rs.getObject("bill_id", UUID.class), k -> new ArrayList<>())
                    .add(new LineRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("bill_id", UUID.class),
                            rs.getInt("line_no"),
                            rs.getObject("receipt_line_id", UUID.class),
                            rs.getObject("receipt_id", UUID.class),
                            rs.getString("receipt_no"),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("description"),
                            rs.getBigDecimal("qty"),
                            rs.getObject("unit_id", UUID.class),
                            rs.getString("unit"),
                            rs.getBigDecimal("unit_qty"),
                            rs.getLong("unit_price_minor"),
                            rs.getLong("line_total_minor"),
                            rs.getLong("cleared_minor"),
                            rs.getLong("variance_minor")));
        });
        return out;
    }

    private static final String RECEIVABLE = """
            SELECT l.id AS receipt_line_id, l.receipt_id, r.receipt_no, r.branch_id, r.received_on, o.order_no, r.supplier_id,
                   r.status, l.product_id, p.code, p.description, l.qty, l.unit_id, u.name AS unit, l.unit_qty, l.unit_factor,
                   l.entered_cost_minor, l.line_total_minor,
                   coalesce((SELECT sum(bl.qty) FROM retail_supplier_bill_lines bl
                               JOIN retail_supplier_bills b ON b.tenant_id = bl.tenant_id AND b.id = bl.bill_id AND b.status = 'posted'
                              WHERE bl.tenant_id = l.tenant_id AND bl.receipt_line_id = l.id), 0) AS billed_qty,
                   coalesce((SELECT sum(bl.cleared_minor) FROM retail_supplier_bill_lines bl
                               JOIN retail_supplier_bills b ON b.tenant_id = bl.tenant_id AND b.id = bl.bill_id AND b.status = 'posted'
                              WHERE bl.tenant_id = l.tenant_id AND bl.receipt_line_id = l.id), 0) AS cleared_minor
              FROM retail_goods_receipt_lines l
              JOIN retail_goods_receipts r ON r.tenant_id = l.tenant_id AND r.id = l.receipt_id
              LEFT JOIN retail_purchase_orders o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
              JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
              LEFT JOIN retail_units u ON u.tenant_id = l.tenant_id AND u.id = l.unit_id
            """;

    /** Receipt lines by id, as they stand for billing now. Call with the supplier locked. */
    Map<UUID, Receivable> receivable(List<UUID> receiptLineIds) {
        Map<UUID, Receivable> out = new HashMap<>();
        if (receiptLineIds.isEmpty()) {
            return out;
        }
        jdbc.sql(RECEIVABLE + " WHERE l.id IN (:ids)")
                .param("ids", receiptLineIds)
                .query(rs -> {
                    Receivable x = receivable(rs);
                    out.put(x.receiptLineId(), x);
                });
        return out;
    }

    /** Every line of a supplier's posted receipts that is not billed in full, oldest first. */
    List<Receivable> unbilled(UUID supplierId) {
        return jdbc
                .sql(
                        RECEIVABLE
                                + " WHERE r.supplier_id = ? AND r.status = 'posted' ORDER BY r.received_on, r.created_at, l.line_no")
                .param(supplierId)
                .query((rs, n) -> receivable(rs))
                .list()
                .stream()
                .filter(x -> x.billedQty().compareTo(x.qty()) < 0)
                .toList();
    }

    private static Receivable receivable(ResultSet rs) throws SQLException {
        return new Receivable(
                rs.getObject("receipt_line_id", UUID.class),
                rs.getObject("receipt_id", UUID.class),
                rs.getString("receipt_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getDate("received_on").toLocalDate(),
                rs.getString("order_no"),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("status"),
                rs.getObject("product_id", UUID.class),
                rs.getString("code"),
                rs.getString("description"),
                rs.getBigDecimal("qty"),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit"),
                rs.getBigDecimal("unit_qty"),
                rs.getBigDecimal("unit_factor"),
                rs.getLong("entered_cost_minor"),
                rs.getLong("line_total_minor"),
                rs.getBigDecimal("billed_qty"),
                rs.getLong("cleared_minor"));
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("bill_no"),
                rs.getLong("branch_seq"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("supplier_ref"),
                rs.getDate("bill_date").toLocalDate(),
                rs.getDate("due_date").toLocalDate(),
                rs.getString("currency"),
                rs.getString("kind"),
                rs.getLong("total_minor"),
                rs.getLong("cleared_minor"),
                rs.getLong("variance_minor"),
                rs.getString("variance_reason"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getLong("paid_minor"),
                rs.getInt("line_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                instant(rs, "voided_at"),
                rs.getDate("voided_on") == null ? null : rs.getDate("voided_on").toLocalDate(),
                rs.getObject("voided_by", UUID.class),
                rs.getString("void_reason"),
                rs.getInt("version"));
    }
}
