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

/** SQL for landed cost documents. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class LandedCostRepository {

    private static final String SELECT_DOC = """
            SELECT d.id, d.landed_no, d.branch_seq, d.branch_id, d.supplier_id, s.name AS supplier_name, d.bill_id, b.bill_no,
                   b.supplier_ref, b.due_date, d.goods_bill_id, d.method, d.cost_date, d.currency, d.total_minor, d.note,
                   d.status, coalesce((SELECT sum(a.amount_minor) FROM retail_supplier_payment_allocations a
                                         JOIN retail_supplier_payments p ON p.tenant_id = a.tenant_id AND p.id = a.payment_id
                                              AND p.status = 'posted'
                                        WHERE a.tenant_id = d.tenant_id AND a.bill_id = d.bill_id), 0) AS paid_minor,
                   (SELECT count(*) FROM retail_landed_cost_lines l WHERE l.tenant_id = d.tenant_id AND l.landed_cost_id = d.id) AS line_count,
                   d.created_at, d.created_by, d.voided_at, d.voided_on, d.voided_by, d.void_reason, d.version
              FROM retail_landed_costs d
              JOIN retail_suppliers s ON s.tenant_id = d.tenant_id AND s.id = d.supplier_id
              JOIN retail_supplier_bills b ON b.tenant_id = d.tenant_id AND b.id = d.bill_id
            """;

    /** What the list matches (ADR-039): our number, the payee's invoice number and the payee's name. */
    static final Search.Fields SEARCH =
            Search.Fields.of().codes("d.landed_no", "b.supplier_ref").names("s.name");

    private final JdbcClient jdbc;

    LandedCostRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(
            UUID id,
            String landedNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            UUID billId,
            String billNo,
            String supplierRef,
            LocalDate dueDate,
            UUID goodsBillId,
            String method,
            LocalDate costDate,
            String currency,
            long totalMinor,
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

    record ChargeRow(int lineNo, String kind, String description, long amountMinor) {}

    record LineRow(
            UUID id,
            UUID landedCostId,
            int lineNo,
            UUID receiptLineId,
            UUID receiptId,
            String receiptNo,
            UUID branchId,
            UUID productId,
            String code,
            String description,
            BigDecimal basis,
            BigDecimal weight,
            long allocatedMinor,
            BigDecimal lineQty,
            BigDecimal onHandQty,
            long toInventoryMinor,
            long toCogsMinor,
            long costBeforeMinor,
            long costAfterMinor,
            boolean costApplied,
            String costNote) {}

    record NewDoc(
            UUID id,
            String landedNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            UUID billId,
            UUID goodsBillId,
            String method,
            LocalDate costDate,
            String currency,
            long totalMinor,
            String note,
            UUID createdBy) {}

    record NewLine(
            UUID id,
            int lineNo,
            UUID receiptLineId,
            UUID branchId,
            UUID productId,
            BigDecimal basis,
            BigDecimal weight,
            long allocatedMinor,
            BigDecimal lineQty,
            BigDecimal onHandQty,
            long toInventoryMinor,
            long toCogsMinor,
            long costBeforeMinor,
            long costAfterMinor,
            boolean costApplied,
            String costNote) {}

    void insert(NewDoc d) {
        jdbc.sql("""
                        INSERT INTO retail_landed_costs (id, tenant_id, landed_no, branch_seq, branch_id, supplier_id, bill_id,
                            goods_bill_id, method, cost_date, currency, total_minor, note, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :no, :seq, :branch, :supplier, :bill, :goodsBill,
                            :method, :costDate, :currency, :total, :note, :by)
                        """)
                .param("id", d.id())
                .param("no", d.landedNo())
                .param("seq", d.branchSeq())
                .param("branch", d.branchId())
                .param("supplier", d.supplierId())
                .param("bill", d.billId())
                .param("goodsBill", d.goodsBillId())
                .param("method", d.method())
                .param("costDate", Date.valueOf(d.costDate()))
                .param("currency", d.currency())
                .param("total", d.totalMinor())
                .param("note", d.note())
                .param("by", d.createdBy())
                .update();
    }

    void insertCharge(UUID docId, int lineNo, String kind, String description, long amountMinor) {
        jdbc.sql("""
                        INSERT INTO retail_landed_cost_charges (id, tenant_id, landed_cost_id, line_no, kind, description, amount_minor)
                        VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                        """).params(docId, lineNo, kind, description, amountMinor).update();
    }

    void insertLine(UUID docId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_landed_cost_lines (id, tenant_id, landed_cost_id, line_no, receipt_line_id, branch_id,
                            product_id, basis, weight, allocated_minor, line_qty, on_hand_qty, to_inventory_minor, to_cogs_minor,
                            cost_before_minor, cost_after_minor, cost_applied, cost_note)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :doc, :no, :receiptLine, :branch, :product, :basis,
                            :weight, :allocated, :lineQty, :onHand, :inv, :cogs, :before, :after, :applied, :note)
                        """)
                .param("id", l.id())
                .param("doc", docId)
                .param("no", l.lineNo())
                .param("receiptLine", l.receiptLineId())
                .param("branch", l.branchId())
                .param("product", l.productId())
                .param("basis", l.basis())
                .param("weight", l.weight())
                .param("allocated", l.allocatedMinor())
                .param("lineQty", l.lineQty())
                .param("onHand", l.onHandQty())
                .param("inv", l.toInventoryMinor())
                .param("cogs", l.toCogsMinor())
                .param("before", l.costBeforeMinor())
                .param("after", l.costAfterMinor())
                .param("applied", l.costApplied())
                .param("note", l.costNote())
                .update();
    }

    void insertEntry(UUID docId, UUID branchId, UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_landed_cost_entries (tenant_id, landed_cost_id, branch_id, entry_id)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(docId, branchId, entryId).update();
    }

    /** The entries a document posted, one per branch, in a stable order. */
    List<UUID> entries(UUID docId) {
        return jdbc.sql("SELECT entry_id FROM retail_landed_cost_entries WHERE landed_cost_id = ? ORDER BY branch_id")
                .param(docId)
                .query(UUID.class)
                .list();
    }

    List<UUID> entryBranches(UUID docId) {
        return jdbc.sql("SELECT branch_id FROM retail_landed_cost_entries WHERE landed_cost_id = ?")
                .param(docId)
                .query(UUID.class)
                .list();
    }

    int markVoided(UUID id, UUID by, LocalDate on, Instant at, String reason) {
        return jdbc.sql("""
                        UPDATE retail_landed_costs SET status = 'voided', voided_at = ?, voided_on = ?, voided_by = ?,
                               void_reason = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'posted'
                        """)
                .params(Timestamp.from(at), Date.valueOf(on), by, reason, id)
                .update();
    }

    Optional<Row> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT_DOC + " WHERE d.id = ?" + (lock ? " FOR UPDATE OF d" : ""))
                .param(id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    List<Row> page(
            List<UUID> branchIds,
            UUID supplierId,
            UUID receiptId,
            String status,
            LocalDate from,
            LocalDate to,
            Search.Query query,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT_DOC + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            sql.append(" AND d.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (supplierId != null) {
            sql.append(" AND d.supplier_id = :supplierId");
            params.put("supplierId", supplierId);
        }
        if (receiptId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM retail_landed_cost_lines l JOIN retail_goods_receipt_lines rl"
                    + " ON rl.tenant_id = l.tenant_id AND rl.id = l.receipt_line_id"
                    + " WHERE l.tenant_id = d.tenant_id AND l.landed_cost_id = d.id AND rl.receipt_id = :receiptId)");
            params.put("receiptId", receiptId);
        }
        if (status != null) {
            sql.append(" AND d.status = :status");
            params.put("status", status);
        }
        if (from != null) {
            sql.append(" AND d.cost_date >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND d.cost_date <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (query != null) {
            Search.Match match = query.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(" AND (d.created_at, d.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY d.created_at DESC, d.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> row(rs)).list();
    }

    Map<UUID, List<ChargeRow>> charges(List<UUID> docIds) {
        Map<UUID, List<ChargeRow>> out = new HashMap<>();
        if (docIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT landed_cost_id, line_no, kind, description, amount_minor
                          FROM retail_landed_cost_charges WHERE landed_cost_id IN (:ids) ORDER BY landed_cost_id, line_no
                        """).param("ids", docIds).query(rs -> {
            out.computeIfAbsent(rs.getObject("landed_cost_id", UUID.class), k -> new ArrayList<>())
                    .add(new ChargeRow(
                            rs.getInt("line_no"),
                            rs.getString("kind"),
                            rs.getString("description"),
                            rs.getLong("amount_minor")));
        });
        return out;
    }

    Map<UUID, List<LineRow>> lines(List<UUID> docIds) {
        Map<UUID, List<LineRow>> out = new HashMap<>();
        if (docIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT l.id, l.landed_cost_id, l.line_no, l.receipt_line_id, rl.receipt_id, r.receipt_no, l.branch_id,
                               l.product_id, p.code, p.description, l.basis, l.weight, l.allocated_minor, l.line_qty,
                               l.on_hand_qty, l.to_inventory_minor, l.to_cogs_minor, l.cost_before_minor, l.cost_after_minor,
                               l.cost_applied, l.cost_note
                          FROM retail_landed_cost_lines l
                          JOIN retail_goods_receipt_lines rl ON rl.tenant_id = l.tenant_id AND rl.id = l.receipt_line_id
                          JOIN retail_goods_receipts r ON r.tenant_id = rl.tenant_id AND r.id = rl.receipt_id
                          JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                         WHERE l.landed_cost_id IN (:ids) ORDER BY l.landed_cost_id, l.line_no
                        """).param("ids", docIds).query(rs -> {
            out.computeIfAbsent(rs.getObject("landed_cost_id", UUID.class), k -> new ArrayList<>())
                    .add(line(rs));
        });
        return out;
    }

    /** True when a landed cost that stands has a line on the receipt. */
    boolean costed(UUID receiptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_landed_cost_lines l
                                         JOIN retail_landed_costs d ON d.tenant_id = l.tenant_id AND d.id = l.landed_cost_id
                                              AND d.status = 'posted'
                                         JOIN retail_goods_receipt_lines rl ON rl.tenant_id = l.tenant_id AND rl.id = l.receipt_line_id
                                        WHERE rl.receipt_id = ?)
                        """).param(receiptId).query(Boolean.class).single();
    }

    /** True when a supplier credit that stands touches a line of the landed cost. */
    boolean credited(UUID docId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_landed_cost_lines l
                                         JOIN retail_supplier_credit_lines c ON c.tenant_id = l.tenant_id
                                              AND c.receipt_line_id = l.receipt_line_id
                                         JOIN retail_supplier_credits h ON h.tenant_id = c.tenant_id AND h.id = c.credit_id
                                              AND h.status = 'posted'
                                        WHERE l.landed_cost_id = ?)
                        """).param(docId).query(Boolean.class).single();
    }

    /** The receipts a landed cost spreads over. */
    List<UUID> receipts(UUID docId) {
        return jdbc.sql("""
                        SELECT DISTINCT rl.receipt_id FROM retail_landed_cost_lines l
                          JOIN retail_goods_receipt_lines rl ON rl.tenant_id = l.tenant_id AND rl.id = l.receipt_line_id
                         WHERE l.landed_cost_id = ?
                        """).param(docId).query(UUID.class).list();
    }

    /** True when the supplier invoice bills the receipt line. */
    Map<UUID, Boolean> billedBy(UUID goodsBillId, List<UUID> receiptLineIds) {
        Map<UUID, Boolean> out = new HashMap<>();
        receiptLineIds.forEach(id -> out.put(id, false));
        if (receiptLineIds.isEmpty()) {
            return out;
        }
        jdbc.sql(
                        "SELECT receipt_line_id FROM retail_supplier_bill_lines WHERE bill_id = :bill AND receipt_line_id IN (:ids)")
                .param("bill", goodsBillId)
                .param("ids", receiptLineIds)
                .query(rs -> {
                    out.put(rs.getObject("receipt_line_id", UUID.class), true);
                });
        return out;
    }

    private static LineRow line(ResultSet rs) throws SQLException {
        return new LineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("landed_cost_id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("receipt_line_id", UUID.class),
                rs.getObject("receipt_id", UUID.class),
                rs.getString("receipt_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("code"),
                rs.getString("description"),
                rs.getBigDecimal("basis"),
                rs.getBigDecimal("weight"),
                rs.getLong("allocated_minor"),
                rs.getBigDecimal("line_qty"),
                rs.getBigDecimal("on_hand_qty"),
                rs.getLong("to_inventory_minor"),
                rs.getLong("to_cogs_minor"),
                rs.getLong("cost_before_minor"),
                rs.getLong("cost_after_minor"),
                rs.getBoolean("cost_applied"),
                rs.getString("cost_note"));
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("landed_no"),
                rs.getLong("branch_seq"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getObject("bill_id", UUID.class),
                rs.getString("bill_no"),
                rs.getString("supplier_ref"),
                rs.getDate("due_date").toLocalDate(),
                rs.getObject("goods_bill_id", UUID.class),
                rs.getString("method"),
                rs.getDate("cost_date").toLocalDate(),
                rs.getString("currency"),
                rs.getLong("total_minor"),
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
