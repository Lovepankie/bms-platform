package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.stock.Quantities;
import java.math.BigDecimal;
import java.sql.Date;
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

/** SQL for customer returns. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class ReturnsRepository {

    private static final String SELECT_RETURN = """
            SELECT r.id, r.credit_note_no, r.sale_id, s.sale_no, r.branch_id, r.customer_id, c.name AS customer_name,
                   r.returned_on, r.currency, r.total_minor, r.cost_minor, r.account_credit_minor, r.refund_minor,
                   r.refund_method, r.refund_branch_id, r.refund_choice, r.note, r.status, r.journal_entry_id, r.cost_entry_id, r.refund_entry_id, r.approval_id,
                   r.created_at, r.created_by, r.approved_by, r.voided_at, r.voided_on, r.voided_by, r.void_reason,
                   r.void_entry_id, r.version
              FROM retail_sale_returns r
              JOIN retail_sales s ON s.tenant_id = r.tenant_id AND s.id = r.sale_id
              LEFT JOIN retail_customers c ON c.tenant_id = r.tenant_id AND c.id = r.customer_id
            """;

    /** What the credit note list matches (ADR-039): its number, the sale's number and the customer's name. */
    static final Search.Fields SEARCH =
            Search.Fields.of().codes("r.credit_note_no", "s.sale_no").names("c.name");

    private final JdbcClient jdbc;

    ReturnsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A return as stored, with the ledger entries a void has to reverse. */
    record Row(
            UUID id,
            String creditNoteNo,
            UUID saleId,
            String saleNo,
            UUID branchId,
            UUID customerId,
            String customerName,
            LocalDate returnedOn,
            String currency,
            long totalMinor,
            long costMinor,
            long accountCreditMinor,
            long refundMinor,
            String refundMethod,
            UUID refundBranchId,
            String refundChoice,
            String note,
            String status,
            UUID journalEntryId,
            UUID costEntryId,
            UUID refundEntryId,
            UUID approvalId,
            Instant createdAt,
            UUID createdBy,
            UUID approvedBy,
            Instant voidedAt,
            LocalDate voidedOn,
            UUID voidedBy,
            String voidReason,
            UUID voidEntryId,
            int version) {}

    record LineRow(
            UUID id,
            UUID returnId,
            int lineNo,
            UUID saleLineId,
            UUID productId,
            String code,
            String description,
            BigDecimal qty,
            UUID unitId,
            String unit,
            BigDecimal unitQty,
            long unitCostMinor,
            long lineTotalMinor,
            long lineCostMinor,
            String reason,
            String disposition,
            UUID restockBranchId,
            UUID stockEntryId) {}

    /** What has already come back of a sale line, from the returns that were not voided. */
    record Done(UUID saleLineId, BigDecimal qty, long totalMinor, long costMinor) {}

    record NewReturn(
            UUID id,
            String creditNoteNo,
            long branchSeq,
            UUID saleId,
            UUID branchId,
            UUID customerId,
            LocalDate returnedOn,
            String currency,
            long totalMinor,
            long costMinor,
            long accountCreditMinor,
            long refundMinor,
            String refundMethod,
            UUID refundBranchId,
            String refundChoice,
            String note,
            UUID journalEntryId,
            UUID costEntryId,
            UUID refundEntryId,
            UUID approvalId,
            UUID createdBy,
            UUID approvedBy) {}

    record NewLine(
            UUID id,
            int lineNo,
            UUID saleLineId,
            UUID productId,
            BigDecimal qty,
            UUID unitId,
            BigDecimal unitQty,
            BigDecimal unitFactor,
            long unitCostMinor,
            long lineTotalMinor,
            long lineCostMinor,
            String reason,
            String disposition,
            UUID restockBranchId,
            UUID stockEntryId) {}

    // ---- Reads ------------------------------------------------------------------------------

    /** Per sale line, the quantity and the money already returned, from completed returns only. */
    List<Done> done(UUID saleId) {
        return jdbc.sql("""
                        SELECT l.sale_line_id, sum(l.qty) AS qty, sum(l.line_total_minor) AS total,
                               sum(l.line_cost_minor) AS cost
                          FROM retail_sale_return_lines l
                          JOIN retail_sale_returns r ON r.tenant_id = l.tenant_id AND r.id = l.return_id
                         WHERE r.sale_id = ? AND r.status = 'completed'
                         GROUP BY l.sale_line_id
                        """)
                .param(saleId)
                .query((rs, n) -> new Done(
                        rs.getObject("sale_line_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getLong("total"),
                        rs.getLong("cost")))
                .list();
    }

    /** What the returns of the sale that were not voided came to: the base of the tenant's approval threshold, which counts a sale's returns together. */
    long returnedTotal(UUID saleId) {
        return jdbc.sql(
                        "SELECT coalesce(sum(total_minor), 0) FROM retail_sale_returns WHERE sale_id = ? AND status = 'completed'")
                .param(saleId)
                .query(Long.class)
                .single();
    }

    /** Money paid out so far on the sale by returns that were not voided. */
    long moneyRefunded(UUID saleId) {
        return jdbc.sql("SELECT coalesce(sum(refund_minor), 0) FROM retail_sale_returns"
                        + " WHERE sale_id = ? AND status = 'completed'")
                .param(saleId)
                .query(Long.class)
                .single();
    }

    /** Account credit given so far on the sale by returns that were not voided. */
    long accountCredited(UUID saleId) {
        return jdbc.sql("SELECT coalesce(sum(account_credit_minor), 0) FROM retail_sale_returns"
                        + " WHERE sale_id = ? AND status = 'completed'")
                .param(saleId)
                .query(Long.class)
                .single();
    }

    boolean hasCompleted(UUID saleId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_sale_returns WHERE sale_id = ? AND status = 'completed')")
                .param(saleId)
                .query(Boolean.class)
                .single();
    }

    /** True when a sale of the receipt has a return that paid money out and was not voided. */
    boolean hasRefundedMoney(List<UUID> saleIds) {
        if (saleIds.isEmpty()) {
            return false;
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_sale_returns WHERE sale_id IN (:ids)"
                        + " AND status = 'completed' AND refund_minor > 0)")
                .param("ids", saleIds)
                .query(Boolean.class)
                .single();
    }

    Optional<Row> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT_RETURN + " WHERE r.id = ?" + (lock ? " FOR UPDATE OF r" : ""))
                .param(id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    List<Row> forSale(UUID saleId) {
        return jdbc.sql(SELECT_RETURN + " WHERE r.sale_id = ? ORDER BY r.created_at, r.id")
                .param(saleId)
                .query((rs, n) -> row(rs))
                .list();
    }

    List<Row> page(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            UUID saleId,
            Search.Query query,
            String status,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT_RETURN + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            sql.append(" AND r.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (from != null) {
            sql.append(" AND r.returned_on >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND r.returned_on <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (customerId != null) {
            sql.append(" AND r.customer_id = :customerId");
            params.put("customerId", customerId);
        }
        if (saleId != null) {
            sql.append(" AND r.sale_id = :saleId");
            params.put("saleId", saleId);
        }
        if (status != null) {
            sql.append(" AND r.status = :status");
            params.put("status", status);
        }
        if (query != null) {
            Search.Match match = query.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(" AND (r.created_at, r.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> row(rs)).list();
    }

    /** The lines of some returns in one statement, in line order. */
    Map<UUID, List<LineRow>> lines(List<UUID> returnIds) {
        Map<UUID, List<LineRow>> out = new HashMap<>();
        if (returnIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT l.id, l.return_id, l.line_no, l.sale_line_id, l.product_id, p.code, p.description, l.qty,
                               l.unit_id, u.name AS unit, l.unit_qty, l.unit_cost_minor, l.line_total_minor,
                               l.line_cost_minor, l.reason, l.disposition, l.restock_branch_id, l.stock_entry_id
                          FROM retail_sale_return_lines l
                          JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                          LEFT JOIN retail_units u ON u.tenant_id = l.tenant_id AND u.id = l.unit_id
                         WHERE l.return_id IN (:ids) ORDER BY l.return_id, l.line_no
                        """).param("ids", returnIds).query(rs -> {
            out.computeIfAbsent(rs.getObject("return_id", UUID.class), k -> new ArrayList<>())
                    .add(new LineRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("return_id", UUID.class),
                            rs.getInt("line_no"),
                            rs.getObject("sale_line_id", UUID.class),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("description"),
                            rs.getBigDecimal("qty"),
                            rs.getObject("unit_id", UUID.class),
                            rs.getString("unit"),
                            rs.getBigDecimal("unit_qty"),
                            rs.getLong("unit_cost_minor"),
                            rs.getLong("line_total_minor"),
                            rs.getLong("line_cost_minor"),
                            rs.getString("reason"),
                            rs.getString("disposition"),
                            rs.getObject("restock_branch_id", UUID.class),
                            rs.getObject("stock_entry_id", UUID.class)));
        });
        return out;
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date voidedOn = rs.getDate("voided_on");
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("credit_note_no"),
                rs.getObject("sale_id", UUID.class),
                rs.getString("sale_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getDate("returned_on").toLocalDate(),
                rs.getString("currency"),
                rs.getLong("total_minor"),
                rs.getLong("cost_minor"),
                rs.getLong("account_credit_minor"),
                rs.getLong("refund_minor"),
                rs.getString("refund_method"),
                rs.getObject("refund_branch_id", UUID.class),
                rs.getString("refund_choice"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getObject("journal_entry_id", UUID.class),
                rs.getObject("cost_entry_id", UUID.class),
                rs.getObject("refund_entry_id", UUID.class),
                rs.getObject("approval_id", UUID.class),
                SalesRepository.instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class),
                SalesRepository.instant(rs, "voided_at"),
                voidedOn == null ? null : voidedOn.toLocalDate(),
                rs.getObject("voided_by", UUID.class),
                rs.getString("void_reason"),
                rs.getObject("void_entry_id", UUID.class),
                rs.getInt("version"));
    }

    // ---- Writes -----------------------------------------------------------------------------

    void insert(NewReturn r) {
        jdbc.sql("""
                        INSERT INTO retail_sale_returns (id, tenant_id, credit_note_no, branch_seq, sale_id, branch_id,
                            customer_id, returned_on, currency, total_minor, cost_minor, account_credit_minor, refund_minor,
                            refund_method, refund_branch_id, refund_choice, note, journal_entry_id, cost_entry_id, refund_entry_id,
                            approval_id, created_by, approved_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :no, :seq, :sale, :branch, :customer, :on,
                            :currency, :total, :cost, :credit, :refund, :method, :refundBranch, :choice, :note, :entry, :costEntry,
                            :refundEntry, :approval, :by, :approvedBy)
                        """)
                .param("id", r.id())
                .param("no", r.creditNoteNo())
                .param("seq", r.branchSeq())
                .param("sale", r.saleId())
                .param("branch", r.branchId())
                .param("customer", r.customerId())
                .param("on", Date.valueOf(r.returnedOn()))
                .param("currency", r.currency())
                .param("total", r.totalMinor())
                .param("cost", r.costMinor())
                .param("credit", r.accountCreditMinor())
                .param("refund", r.refundMinor())
                .param("method", r.refundMethod())
                .param("refundBranch", r.refundBranchId())
                .param("refundEntry", r.refundEntryId())
                .param("choice", r.refundChoice())
                .param("note", r.note())
                .param("entry", r.journalEntryId())
                .param("costEntry", r.costEntryId())
                .param("approval", r.approvalId())
                .param("by", r.createdBy())
                .param("approvedBy", r.approvedBy())
                .update();
    }

    void insertLine(UUID returnId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_sale_return_lines (id, tenant_id, return_id, line_no, sale_line_id, product_id, qty,
                            unit_id, unit_qty, unit_factor, unit_cost_minor, line_total_minor, line_cost_minor, reason,
                            disposition, restock_branch_id, stock_entry_id)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :return, :no, :saleLine, :product, :qty, :unit,
                            :unitQty, :factor, :unitCost, :total, :cost, :reason, :disposition, :restock, :stockEntry)
                        """)
                .param("id", l.id())
                .param("return", returnId)
                .param("no", l.lineNo())
                .param("saleLine", l.saleLineId())
                .param("product", l.productId())
                .param("qty", l.qty())
                .param("unit", l.unitId())
                .param("unitQty", l.unitQty())
                .param("factor", l.unitFactor())
                .param("unitCost", l.unitCostMinor())
                .param("total", l.lineTotalMinor())
                .param("cost", l.lineCostMinor())
                .param("reason", l.reason())
                .param("disposition", l.disposition())
                .param("restock", l.restockBranchId())
                .param("stockEntry", l.stockEntryId())
                .update();
    }

    /** Rows changed: 0 when the return was voided meanwhile. */
    int markVoided(UUID id, UUID by, LocalDate on, Instant at, String reason, UUID voidEntryId) {
        return jdbc.sql("""
                        UPDATE retail_sale_returns SET status = 'voided', voided_at = ?, voided_on = ?, voided_by = ?,
                               void_reason = ?, void_entry_id = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'completed'
                        """)
                .params(Timestamp.from(at), Date.valueOf(on), by, reason, voidEntryId, id)
                .update();
    }

    /** Makes a pending approval of another return of the same sale stale, and keeps the optimistic version honest. */
    void touchSale(UUID saleId) {
        jdbc.sql("UPDATE retail_sales SET updated_at = now(), version = version + 1 WHERE id = ?")
                .param(saleId)
                .update();
    }

    static String formatted(BigDecimal qty) {
        return qty == null ? null : Quantities.format(qty);
    }
}
