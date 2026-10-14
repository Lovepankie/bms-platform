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

/** SQL for goods received notes. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class ReceiptsRepository {

    private static final String SELECT_RECEIPT = """
            SELECT r.id, r.receipt_no, r.branch_seq, r.branch_id, r.supplier_id, s.name AS supplier_name, r.order_id,
                   o.order_no, r.received_on, r.currency, r.total_minor, r.delivery_ref, r.note, r.status,
                   (SELECT count(*) FROM retail_goods_receipt_lines l WHERE l.tenant_id = r.tenant_id AND l.receipt_id = r.id) AS line_count,
                   r.journal_entry_id, r.created_at, r.created_by, r.voided_at, r.voided_on, r.voided_by, r.void_reason,
                   r.void_entry_id,
                   EXISTS (SELECT 1 FROM retail_receipt_holds h
                            WHERE h.tenant_id = r.tenant_id AND h.receipt_id = r.id AND h.released_at IS NULL) AS held,
                   r.version
              FROM retail_goods_receipts r
              JOIN retail_suppliers s ON s.tenant_id = r.tenant_id AND s.id = r.supplier_id
              LEFT JOIN retail_purchase_orders o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
            """;

    /** What the receipt list matches (ADR-039): its number, the delivery note, the order number and the supplier's name. */
    static final Search.Fields SEARCH = Search.Fields.of()
            .codes("r.receipt_no", "r.delivery_ref", "o.order_no")
            .names("s.name");

    private final JdbcClient jdbc;

    ReceiptsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(
            UUID id,
            String receiptNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            UUID orderId,
            String orderNo,
            LocalDate receivedOn,
            String currency,
            long totalMinor,
            String deliveryRef,
            String note,
            String status,
            int lineCount,
            UUID journalEntryId,
            Instant createdAt,
            UUID createdBy,
            Instant voidedAt,
            LocalDate voidedOn,
            UUID voidedBy,
            String voidReason,
            UUID voidEntryId,
            boolean held,
            int version) {}

    record LineRow(
            UUID id,
            UUID receiptId,
            int lineNo,
            UUID orderLineId,
            UUID productId,
            String code,
            String description,
            BigDecimal qty,
            UUID unitId,
            String unit,
            BigDecimal unitQty,
            BigDecimal unitFactor,
            long enteredCostMinor,
            long costMinor,
            Long sellMinor,
            long lineTotalMinor,
            long stockedMinor,
            BigDecimal billedQty,
            long landedMinor) {}

    record NewReceipt(
            UUID id,
            String receiptNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            UUID orderId,
            LocalDate receivedOn,
            String currency,
            long totalMinor,
            String deliveryRef,
            String note,
            UUID journalEntryId,
            UUID createdBy) {}

    record NewLine(
            UUID id,
            int lineNo,
            UUID orderLineId,
            UUID productId,
            BigDecimal qty,
            UUID unitId,
            BigDecimal unitQty,
            BigDecimal unitFactor,
            long enteredCostMinor,
            long costMinor,
            Long sellMinor,
            long lineTotalMinor,
            long stockedMinor) {}

    void insert(NewReceipt r) {
        jdbc.sql("""
                        INSERT INTO retail_goods_receipts (id, tenant_id, receipt_no, branch_seq, branch_id, supplier_id, order_id,
                            received_on, currency, total_minor, delivery_ref, note, journal_entry_id, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :no, :seq, :branch, :supplier, :order, :on,
                            :currency, :total, :delivery, :note, :entry, :by)
                        """)
                .param("id", r.id())
                .param("no", r.receiptNo())
                .param("seq", r.branchSeq())
                .param("branch", r.branchId())
                .param("supplier", r.supplierId())
                .param("order", r.orderId())
                .param("on", Date.valueOf(r.receivedOn()))
                .param("currency", r.currency())
                .param("total", r.totalMinor())
                .param("delivery", r.deliveryRef())
                .param("note", r.note())
                .param("entry", r.journalEntryId())
                .param("by", r.createdBy())
                .update();
    }

    void insertLine(UUID receiptId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_goods_receipt_lines (id, tenant_id, receipt_id, line_no, order_line_id, product_id, qty,
                            unit_id, unit_qty, unit_factor, entered_cost_minor, cost_minor, sell_minor, line_total_minor,
                            stocked_minor)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :receipt, :no, :orderLine, :product, :qty, :unit,
                            :unitQty, :factor, :entered, :cost, :sell, :total, :stocked)
                        """)
                .param("id", l.id())
                .param("receipt", receiptId)
                .param("no", l.lineNo())
                .param("orderLine", l.orderLineId())
                .param("product", l.productId())
                .param("qty", l.qty())
                .param("unit", l.unitId())
                .param("unitQty", l.unitQty())
                .param("factor", l.unitFactor())
                .param("entered", l.enteredCostMinor())
                .param("cost", l.costMinor())
                .param("sell", l.sellMinor())
                .param("total", l.lineTotalMinor())
                .param("stocked", l.stockedMinor())
                .update();
    }

    /** Rows changed: 0 when the receipt was voided meanwhile. */
    int markVoided(UUID id, UUID by, LocalDate on, Instant at, String reason, UUID voidEntryId) {
        return jdbc.sql("""
                        UPDATE retail_goods_receipts SET status = 'voided', voided_at = ?, voided_on = ?, voided_by = ?,
                               void_reason = ?, void_entry_id = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'posted'
                        """)
                .params(Timestamp.from(at), Date.valueOf(on), by, reason, voidEntryId, id)
                .update();
    }

    Optional<Row> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT_RECEIPT + " WHERE r.id = ?" + (lock ? " FOR UPDATE OF r" : ""))
                .param(id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    List<Row> page(
            List<UUID> branchIds,
            UUID supplierId,
            UUID orderId,
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
        StringBuilder sql = new StringBuilder(SELECT_RECEIPT + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            sql.append(" AND r.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (supplierId != null) {
            sql.append(" AND r.supplier_id = :supplierId");
            params.put("supplierId", supplierId);
        }
        if (orderId != null) {
            sql.append(" AND r.order_id = :orderId");
            params.put("orderId", orderId);
        }
        if (status != null) {
            sql.append(" AND r.status = :status");
            params.put("status", status);
        }
        if (from != null) {
            sql.append(" AND r.received_on >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND r.received_on <= :to");
            params.put("to", Date.valueOf(to));
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

    /** The lines of some receipts in one statement, in line order, each with what stands billed against it. */
    Map<UUID, List<LineRow>> lines(List<UUID> receiptIds) {
        Map<UUID, List<LineRow>> out = new HashMap<>();
        if (receiptIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT l.id, l.receipt_id, l.line_no, l.order_line_id, l.product_id, p.code, p.description, l.qty,
                               l.unit_id, u.name AS unit, l.unit_qty, l.unit_factor, l.entered_cost_minor, l.cost_minor,
                               l.sell_minor, l.line_total_minor, l.stocked_minor,
                               coalesce((SELECT sum(bl.qty) FROM retail_supplier_bill_lines bl
                                           JOIN retail_supplier_bills b ON b.tenant_id = bl.tenant_id AND b.id = bl.bill_id AND b.status = 'posted'
                                          WHERE bl.tenant_id = l.tenant_id AND bl.receipt_line_id = l.id), 0) AS billed_qty,
                               coalesce((SELECT sum(x.allocated_minor) FROM retail_landed_cost_lines x
                                           JOIN retail_landed_costs d ON d.tenant_id = x.tenant_id AND d.id = x.landed_cost_id
                                                AND d.status = 'posted'
                                          WHERE x.tenant_id = l.tenant_id AND x.receipt_line_id = l.id), 0) AS landed_minor
                          FROM retail_goods_receipt_lines l
                          JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                          LEFT JOIN retail_units u ON u.tenant_id = l.tenant_id AND u.id = l.unit_id
                         WHERE l.receipt_id IN (:ids) ORDER BY l.receipt_id, l.line_no
                        """).param("ids", receiptIds).query(rs -> {
            out.computeIfAbsent(rs.getObject("receipt_id", UUID.class), k -> new ArrayList<>())
                    .add(new LineRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("receipt_id", UUID.class),
                            rs.getInt("line_no"),
                            rs.getObject("order_line_id", UUID.class),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("description"),
                            rs.getBigDecimal("qty"),
                            rs.getObject("unit_id", UUID.class),
                            rs.getString("unit"),
                            rs.getBigDecimal("unit_qty"),
                            rs.getBigDecimal("unit_factor"),
                            rs.getLong("entered_cost_minor"),
                            rs.getLong("cost_minor"),
                            rs.getObject("sell_minor", Long.class),
                            rs.getLong("line_total_minor"),
                            rs.getLong("stocked_minor"),
                            rs.getBigDecimal("billed_qty"),
                            rs.getLong("landed_minor")));
        });
        return out;
    }

    /** Received so far on the receipts that stand, per line of an order, in base units. */
    Map<UUID, BigDecimal> receivedByOrderLine(UUID orderId) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        jdbc.sql("""
                        SELECT l.order_line_id, sum(l.qty) AS qty FROM retail_goods_receipt_lines l
                          JOIN retail_goods_receipts r ON r.tenant_id = l.tenant_id AND r.id = l.receipt_id
                         WHERE r.order_id = ? AND r.status = 'posted' AND l.order_line_id IS NOT NULL
                         GROUP BY l.order_line_id
                        """).param(orderId).query(rs -> {
            out.put(rs.getObject("order_line_id", UUID.class), rs.getBigDecimal("qty"));
        });
        return out;
    }

    /** The value, as entered, of the receipts of an order that stand. */
    long receivedValue(UUID orderId) {
        return jdbc.sql(
                        "SELECT coalesce(sum(total_minor), 0) FROM retail_goods_receipts WHERE order_id = ? AND status = 'posted'")
                .param(orderId)
                .query(Long.class)
                .single();
    }

    // ---- Holds: a receipt held as received and not costed (issue #199) ----------------------

    void hold(UUID receiptId, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_receipt_holds (tenant_id, receipt_id, held_by)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?)
                        """).params(receiptId, by).update();
    }

    /** Rows changed: 0 when the receipt has no open hold. */
    int release(UUID receiptId, UUID by, String kind, String reason, UUID landedCostId) {
        return jdbc.sql("""
                        UPDATE retail_receipt_holds SET released_at = now(), released_by = ?, release_kind = ?,
                               release_reason = ?, landed_cost_id = ?
                         WHERE receipt_id = ? AND released_at IS NULL
                        """).params(by, kind, reason, landedCostId, receiptId).update();
    }

    /**
     * Puts the hold back on a receipt that a landed cost released, when no landed cost stands on it any more; 0 when the
     * receipt was not released by a landed cost (a hold released by hand with a reason stays released).
     */
    int rehold(UUID receiptId) {
        return jdbc.sql("""
                        UPDATE retail_receipt_holds SET released_at = NULL, released_by = NULL, release_kind = NULL,
                               release_reason = NULL, landed_cost_id = NULL
                         WHERE receipt_id = ? AND release_kind = 'landed_cost'
                        """).params(receiptId).update();
    }

    /** True when a supplier invoice that stands bills any line of the receipt. */
    boolean billed(UUID receiptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_supplier_bill_lines bl
                                         JOIN retail_supplier_bills b ON b.tenant_id = bl.tenant_id AND b.id = bl.bill_id AND b.status = 'posted'
                                         JOIN retail_goods_receipt_lines l ON l.tenant_id = bl.tenant_id AND l.id = bl.receipt_line_id
                                        WHERE l.receipt_id = ?)
                        """).param(receiptId).query(Boolean.class).single();
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("receipt_no"),
                rs.getLong("branch_seq"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getObject("order_id", UUID.class),
                rs.getString("order_no"),
                rs.getDate("received_on").toLocalDate(),
                rs.getString("currency"),
                rs.getLong("total_minor"),
                rs.getString("delivery_ref"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getInt("line_count"),
                rs.getObject("journal_entry_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                instant(rs, "voided_at"),
                rs.getDate("voided_on") == null ? null : rs.getDate("voided_on").toLocalDate(),
                rs.getObject("voided_by", UUID.class),
                rs.getString("void_reason"),
                rs.getObject("void_entry_id", UUID.class),
                rs.getBoolean("held"),
                rs.getInt("version"));
    }
}
