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

/** SQL for supplier credit notes. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class CreditsRepository {

    private static final String SELECT_CREDIT = """
            SELECT c.id, c.credit_no, c.branch_seq, c.branch_id, c.supplier_id, s.name AS supplier_name, c.bill_id, b.bill_no,
                   b.supplier_ref AS bill_supplier_ref, c.supplier_ref, c.credit_date, c.currency, c.kind, c.total_minor,
                   c.reason, c.note, c.status,
                   (SELECT count(*) FROM retail_supplier_credit_lines l WHERE l.tenant_id = c.tenant_id AND l.credit_id = c.id) AS line_count,
                   c.created_at, c.created_by, c.voided_at, c.voided_on, c.voided_by, c.void_reason, c.version
              FROM retail_supplier_credits c
              JOIN retail_suppliers s ON s.tenant_id = c.tenant_id AND s.id = c.supplier_id
              JOIN retail_supplier_bills b ON b.tenant_id = c.tenant_id AND b.id = c.bill_id
            """;

    /** What the list matches (ADR-039): our number, the supplier's credit note number, the invoice number and the supplier's name. */
    static final Search.Fields SEARCH = Search.Fields.of()
            .codes("c.credit_no", "c.supplier_ref", "b.supplier_ref", "b.bill_no")
            .names("s.name");

    private final JdbcClient jdbc;

    CreditsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(
            UUID id,
            String creditNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            UUID billId,
            String billNo,
            String billSupplierRef,
            String supplierRef,
            LocalDate creditDate,
            String currency,
            String kind,
            long totalMinor,
            String reason,
            String note,
            String status,
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
            UUID creditId,
            int lineNo,
            UUID billLineId,
            UUID receiptLineId,
            String receiptNo,
            UUID branchId,
            UUID productId,
            String code,
            String description,
            BigDecimal qty,
            UUID unitId,
            String unit,
            BigDecimal unitQty,
            Long unitCreditMinor,
            long lineTotalMinor,
            long toInventoryMinor,
            long toCogsMinor,
            long varianceMinor,
            BigDecimal onHandQty,
            long costBeforeMinor,
            long costAfterMinor,
            boolean costApplied,
            String costNote) {}

    record NewCredit(
            UUID id,
            String creditNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            UUID billId,
            String supplierRef,
            LocalDate creditDate,
            String currency,
            String kind,
            long totalMinor,
            String reason,
            String note,
            UUID createdBy) {}

    record NewLine(
            UUID id,
            int lineNo,
            UUID billLineId,
            UUID receiptLineId,
            UUID branchId,
            UUID productId,
            BigDecimal qty,
            BigDecimal unitQty,
            Long unitCreditMinor,
            long lineTotalMinor,
            long toInventoryMinor,
            long toCogsMinor,
            long varianceMinor,
            BigDecimal onHandQty,
            long costBeforeMinor,
            long costAfterMinor,
            boolean costApplied,
            String costNote) {}

    /** What standing credit notes have already taken off one line of an invoice. */
    record Credited(BigDecimal qty, long totalMinor, long varianceMinor, long priceMinor) {}

    void insert(NewCredit c) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_credits (id, tenant_id, credit_no, branch_seq, branch_id, supplier_id, bill_id,
                            supplier_ref, credit_date, currency, kind, total_minor, reason, note, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :no, :seq, :branch, :supplier, :bill, :ref, :on,
                            :currency, :kind, :total, :reason, :note, :by)
                        """)
                .param("id", c.id())
                .param("no", c.creditNo())
                .param("seq", c.branchSeq())
                .param("branch", c.branchId())
                .param("supplier", c.supplierId())
                .param("bill", c.billId())
                .param("ref", c.supplierRef())
                .param("on", Date.valueOf(c.creditDate()))
                .param("currency", c.currency())
                .param("kind", c.kind())
                .param("total", c.totalMinor())
                .param("reason", c.reason())
                .param("note", c.note())
                .param("by", c.createdBy())
                .update();
    }

    void insertLine(UUID creditId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_credit_lines (id, tenant_id, credit_id, line_no, bill_line_id, receipt_line_id,
                            branch_id, product_id, qty, unit_qty, unit_credit_minor, line_total_minor, to_inventory_minor,
                            to_cogs_minor, variance_minor, on_hand_qty, cost_before_minor, cost_after_minor, cost_applied, cost_note)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :credit, :no, :billLine, :receiptLine, :branch,
                            :product, :qty, :unitQty, :unitCredit, :total, :inv, :cogs, :variance, :onHand, :before, :after,
                            :applied, :note)
                        """)
                .param("id", l.id())
                .param("credit", creditId)
                .param("no", l.lineNo())
                .param("billLine", l.billLineId())
                .param("receiptLine", l.receiptLineId())
                .param("branch", l.branchId())
                .param("product", l.productId())
                .param("qty", l.qty())
                .param("unitQty", l.unitQty())
                .param("unitCredit", l.unitCreditMinor())
                .param("total", l.lineTotalMinor())
                .param("inv", l.toInventoryMinor())
                .param("cogs", l.toCogsMinor())
                .param("variance", l.varianceMinor())
                .param("onHand", l.onHandQty())
                .param("before", l.costBeforeMinor())
                .param("after", l.costAfterMinor())
                .param("applied", l.costApplied())
                .param("note", l.costNote())
                .update();
    }

    void insertEntry(UUID creditId, UUID branchId, UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_credit_entries (tenant_id, credit_id, branch_id, entry_id)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(creditId, branchId, entryId).update();
    }

    List<UUID> entries(UUID creditId) {
        return jdbc.sql("SELECT entry_id FROM retail_supplier_credit_entries WHERE credit_id = ? ORDER BY branch_id")
                .param(creditId)
                .query(UUID.class)
                .list();
    }

    List<UUID> entryBranches(UUID creditId) {
        return jdbc.sql("SELECT branch_id FROM retail_supplier_credit_entries WHERE credit_id = ?")
                .param(creditId)
                .query(UUID.class)
                .list();
    }

    int markVoided(UUID id, UUID by, LocalDate on, Instant at, String reason) {
        return jdbc.sql("""
                        UPDATE retail_supplier_credits SET status = 'voided', voided_at = ?, voided_on = ?, voided_by = ?,
                               void_reason = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'posted'
                        """)
                .params(Timestamp.from(at), Date.valueOf(on), by, reason, id)
                .update();
    }

    boolean refExists(UUID supplierId, String ref) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_supplier_credits
                                        WHERE supplier_id = ? AND lower(supplier_ref) = lower(?) AND status = 'posted')
                        """).params(supplierId, ref).query(Boolean.class).single();
    }

    /** What standing credit notes took off each line of an invoice, by bill line id. */
    Map<UUID, Credited> creditedByBillLine(UUID billId) {
        Map<UUID, Credited> out = new HashMap<>();
        jdbc.sql("""
                        SELECT l.bill_line_id, coalesce(sum(l.qty), 0) AS qty, sum(l.line_total_minor) AS total,
                               sum(l.variance_minor) AS variance,
                               coalesce(sum(l.line_total_minor) FILTER (WHERE c.kind = 'price'), 0) AS price
                          FROM retail_supplier_credit_lines l
                          JOIN retail_supplier_credits c ON c.tenant_id = l.tenant_id AND c.id = l.credit_id AND c.status = 'posted'
                         WHERE c.bill_id = ? GROUP BY l.bill_line_id
                        """).param(billId).query(rs -> {
            out.put(
                    rs.getObject("bill_line_id", UUID.class),
                    new Credited(
                            rs.getBigDecimal("qty"), rs.getLong("total"), rs.getLong("variance"), rs.getLong("price")));
        });
        return out;
    }

    Optional<Row> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT_CREDIT + " WHERE c.id = ?" + (lock ? " FOR UPDATE OF c" : ""))
                .param(id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    List<Row> page(
            List<UUID> branchIds,
            UUID supplierId,
            UUID billId,
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
        StringBuilder sql = new StringBuilder(SELECT_CREDIT + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            sql.append(" AND c.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (supplierId != null) {
            sql.append(" AND c.supplier_id = :supplierId");
            params.put("supplierId", supplierId);
        }
        if (billId != null) {
            sql.append(" AND c.bill_id = :billId");
            params.put("billId", billId);
        }
        if (status != null) {
            sql.append(" AND c.status = :status");
            params.put("status", status);
        }
        if (from != null) {
            sql.append(" AND c.credit_date >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND c.credit_date <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (query != null) {
            Search.Match match = query.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(" AND (c.created_at, c.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY c.created_at DESC, c.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> row(rs)).list();
    }

    Map<UUID, List<LineRow>> lines(List<UUID> creditIds) {
        Map<UUID, List<LineRow>> out = new HashMap<>();
        if (creditIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT l.id, l.credit_id, l.line_no, l.bill_line_id, l.receipt_line_id, r.receipt_no, l.branch_id, l.product_id,
                               p.code, p.description, l.qty, rl.unit_id, u.name AS unit, l.unit_qty, l.unit_credit_minor,
                               l.line_total_minor, l.to_inventory_minor, l.to_cogs_minor, l.variance_minor, l.on_hand_qty,
                               l.cost_before_minor, l.cost_after_minor, l.cost_applied, l.cost_note
                          FROM retail_supplier_credit_lines l
                          JOIN retail_goods_receipt_lines rl ON rl.tenant_id = l.tenant_id AND rl.id = l.receipt_line_id
                          JOIN retail_goods_receipts r ON r.tenant_id = rl.tenant_id AND r.id = rl.receipt_id
                          JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                          LEFT JOIN retail_units u ON u.tenant_id = rl.tenant_id AND u.id = rl.unit_id
                         WHERE l.credit_id IN (:ids) ORDER BY l.credit_id, l.line_no
                        """).param("ids", creditIds).query(rs -> {
            out.computeIfAbsent(rs.getObject("credit_id", UUID.class), k -> new ArrayList<>())
                    .add(new LineRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("credit_id", UUID.class),
                            rs.getInt("line_no"),
                            rs.getObject("bill_line_id", UUID.class),
                            rs.getObject("receipt_line_id", UUID.class),
                            rs.getString("receipt_no"),
                            rs.getObject("branch_id", UUID.class),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("description"),
                            rs.getBigDecimal("qty"),
                            rs.getObject("unit_id", UUID.class),
                            rs.getString("unit"),
                            rs.getBigDecimal("unit_qty"),
                            rs.getObject("unit_credit_minor", Long.class),
                            rs.getLong("line_total_minor"),
                            rs.getLong("to_inventory_minor"),
                            rs.getLong("to_cogs_minor"),
                            rs.getLong("variance_minor"),
                            rs.getBigDecimal("on_hand_qty"),
                            rs.getLong("cost_before_minor"),
                            rs.getLong("cost_after_minor"),
                            rs.getBoolean("cost_applied"),
                            rs.getString("cost_note")));
        });
        return out;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("credit_no"),
                rs.getLong("branch_seq"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getObject("bill_id", UUID.class),
                rs.getString("bill_no"),
                rs.getString("bill_supplier_ref"),
                rs.getString("supplier_ref"),
                rs.getDate("credit_date").toLocalDate(),
                rs.getString("currency"),
                rs.getString("kind"),
                rs.getLong("total_minor"),
                rs.getString("reason"),
                rs.getString("note"),
                rs.getString("status"),
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
