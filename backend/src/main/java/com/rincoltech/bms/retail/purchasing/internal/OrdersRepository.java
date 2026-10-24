package com.rincoltech.bms.retail.purchasing.internal;

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

/** SQL for purchase orders. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class OrdersRepository {

    private static final String SELECT_ORDER = """
            SELECT o.id, o.order_no, o.branch_seq, o.branch_id, o.supplier_id, s.name AS supplier_name, o.ordered_on,
                   o.expected_on, o.currency, o.total_minor, o.note, o.status,
                   (SELECT count(*) FROM retail_purchase_order_lines l WHERE l.tenant_id = o.tenant_id AND l.order_id = o.id) AS line_count,
                   o.created_at, o.created_by, o.updated_by, o.sent_at, o.sent_by, o.approval_id, o.approved_by, o.cancelled_at,
                   o.cancelled_by, o.cancel_reason, o.version
              FROM retail_purchase_orders o
              JOIN retail_suppliers s ON s.tenant_id = o.tenant_id AND s.id = o.supplier_id
            """;

    /** What the order list matches (ADR-039): the number and the supplier's name. */
    static final Search.Fields SEARCH = Search.Fields.of().codes("o.order_no").names("s.name");

    private final JdbcClient jdbc;

    OrdersRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record OrderRow(
            UUID id,
            String orderNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            LocalDate orderedOn,
            LocalDate expectedOn,
            String currency,
            long totalMinor,
            String note,
            String status,
            int lineCount,
            Instant createdAt,
            UUID createdBy,
            UUID updatedBy,
            Instant sentAt,
            UUID sentBy,
            UUID approvalId,
            UUID approvedBy,
            Instant cancelledAt,
            UUID cancelledBy,
            String cancelReason,
            int version) {}

    record LineRow(
            UUID id,
            int lineNo,
            UUID productId,
            String code,
            String description,
            BigDecimal qty,
            UUID unitId,
            String unit,
            BigDecimal unitQty,
            BigDecimal unitFactor,
            Long unitCostMinor,
            long costMinor,
            long lineTotalMinor,
            BigDecimal receivedQty,
            long receivedMinor,
            BigDecimal billedQty,
            long billedMinor,
            BigDecimal receivedUnitQty,
            String baseUnit) {}

    /**
     * D23, issue #554: what a receipt line received, counted in its order line's own unit ({@code rl} and {@code ol}). A
     * line in the order's pack counts its packs, whatever each held this time (ten cartons of 30 receive ten cartons
     * ordered at 24); a line in another unit counts its base units in packs of the size ordered.
     */
    static final String RECEIVED_IN_ORDER_UNIT =
            "CASE WHEN ol.unit_id IS NULL THEN rl.qty WHEN rl.unit_id = ol.unit_id THEN rl.unit_qty ELSE round(rl.qty / ol.unit_factor, 3) END";

    record NewOrder(
            UUID id,
            String orderNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            LocalDate orderedOn,
            LocalDate expectedOn,
            String currency,
            long totalMinor,
            String note,
            UUID createdBy) {}

    record NewLine(
            UUID id,
            int lineNo,
            UUID productId,
            BigDecimal qty,
            UUID unitId,
            BigDecimal unitQty,
            BigDecimal unitFactor,
            Long unitCostMinor,
            long costMinor,
            long lineTotalMinor) {}

    record ReceiptRef(UUID id, String receiptNo, LocalDate receivedOn, String status, long totalMinor) {}

    void insert(NewOrder o) {
        jdbc.sql("""
                        INSERT INTO retail_purchase_orders (id, tenant_id, order_no, branch_seq, branch_id, supplier_id, ordered_on,
                            expected_on, currency, total_minor, note, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :no, :seq, :branch, :supplier, :ordered, :expected,
                            :currency, :total, :note, :by)
                        """)
                .param("id", o.id())
                .param("no", o.orderNo())
                .param("seq", o.branchSeq())
                .param("branch", o.branchId())
                .param("supplier", o.supplierId())
                .param("ordered", Date.valueOf(o.orderedOn()))
                .param("expected", o.expectedOn() == null ? null : Date.valueOf(o.expectedOn()))
                .param("currency", o.currency())
                .param("total", o.totalMinor())
                .param("note", o.note())
                .param("by", o.createdBy())
                .update();
    }

    void insertLine(UUID orderId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_purchase_order_lines (id, tenant_id, order_id, line_no, product_id, qty, unit_id, unit_qty,
                            unit_factor, unit_cost_minor, cost_minor, line_total_minor)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :order, :no, :product, :qty, :unit, :unitQty, :factor,
                            :unitCost, :cost, :total)
                        """)
                .param("id", l.id())
                .param("order", orderId)
                .param("no", l.lineNo())
                .param("product", l.productId())
                .param("qty", l.qty())
                .param("unit", l.unitId())
                .param("unitQty", l.unitQty())
                .param("factor", l.unitFactor())
                .param("unitCost", l.unitCostMinor())
                .param("cost", l.costMinor())
                .param("total", l.lineTotalMinor())
                .update();
    }

    void deleteLines(UUID orderId) {
        jdbc.sql("DELETE FROM retail_purchase_order_lines WHERE order_id = ?")
                .param(orderId)
                .update();
    }

    /** Rows changed: 0 when the version moved since it was read. */
    int updateDraft(
            UUID id,
            UUID supplierId,
            LocalDate orderedOn,
            LocalDate expectedOn,
            long totalMinor,
            String note,
            UUID by,
            int expectedVersion) {
        return jdbc.sql("""
                        UPDATE retail_purchase_orders SET supplier_id = ?, ordered_on = ?, expected_on = ?, total_minor = ?,
                               note = ?, updated_by = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND version = ? AND status = 'draft'
                        """)
                .params(
                        supplierId,
                        Date.valueOf(orderedOn),
                        expectedOn == null ? null : Date.valueOf(expectedOn),
                        totalMinor,
                        note,
                        by,
                        id,
                        expectedVersion)
                .update();
    }

    /** Rows changed: 0 when the order is no longer a draft. */
    int markSent(UUID id, UUID by, UUID approvalId, UUID approvedBy) {
        return jdbc.sql("""
                        UPDATE retail_purchase_orders SET status = 'sent', sent_at = now(), sent_by = ?, approval_id = ?,
                               approved_by = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'draft'
                        """).params(by, approvalId, approvedBy, id).update();
    }

    int markCancelled(UUID id, UUID by, String reason) {
        return jdbc.sql("""
                        UPDATE retail_purchase_orders SET status = 'cancelled', cancelled_at = now(), cancelled_by = ?,
                               cancel_reason = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status IN ('draft', 'sent', 'partly_received')
                        """).params(by, reason, id).update();
    }

    void setStatus(UUID id, String status) {
        jdbc.sql("""
                        UPDATE retail_purchase_orders SET status = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status <> ?
                        """).params(status, id, status).update();
    }

    Optional<OrderRow> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT_ORDER + " WHERE o.id = ?" + (lock ? " FOR UPDATE OF o" : ""))
                .param(id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    List<OrderRow> page(
            List<UUID> branchIds,
            UUID supplierId,
            String status,
            LocalDate from,
            LocalDate to,
            Search.Query query,
            Instant afterCreated,
            UUID afterId,
            int limit,
            UUID createdBy) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT_ORDER + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        // ADR-055: without retail.profit.read a buyer reads only what they recorded themselves.
        if (createdBy != null) {
            sql.append(" AND o.created_by = :createdBy");
            params.put("createdBy", createdBy);
        }
        if (branchIds != null) {
            sql.append(" AND o.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (supplierId != null) {
            sql.append(" AND o.supplier_id = :supplierId");
            params.put("supplierId", supplierId);
        }
        if (status != null) {
            sql.append(" AND o.status = :status");
            params.put("status", status);
        }
        if (from != null) {
            sql.append(" AND o.ordered_on >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND o.ordered_on <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (query != null) {
            Search.Match match = query.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(" AND (o.created_at, o.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY o.created_at DESC, o.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> row(rs)).list();
    }

    /** The lines of an order with what was received and billed against each, in line order. */
    List<LineRow> lines(UUID orderId) {
        return jdbc.sql("""
                        SELECT l.id, l.line_no, l.product_id, p.code, p.description, l.qty, l.unit_id, u.name AS unit, l.unit_qty,
                               l.unit_factor, l.unit_cost_minor, l.cost_minor, l.line_total_minor, bu.name AS base_unit,
                               coalesce((SELECT sum(rl.qty) FROM retail_goods_receipt_lines rl
                                           JOIN retail_goods_receipts r ON r.tenant_id = rl.tenant_id AND r.id = rl.receipt_id
                                          WHERE rl.tenant_id = l.tenant_id AND rl.order_line_id = l.id AND r.status = 'posted'), 0) AS received_qty,
                               coalesce((SELECT sum(%s) FROM retail_goods_receipt_lines rl
                                           JOIN retail_goods_receipts r ON r.tenant_id = rl.tenant_id AND r.id = rl.receipt_id
                                           JOIN retail_purchase_order_lines ol ON ol.tenant_id = rl.tenant_id AND ol.id = rl.order_line_id
                                          WHERE rl.tenant_id = l.tenant_id AND rl.order_line_id = l.id AND r.status = 'posted'), 0) AS received_unit_qty,
                               coalesce((SELECT sum(rl.line_total_minor) FROM retail_goods_receipt_lines rl
                                           JOIN retail_goods_receipts r ON r.tenant_id = rl.tenant_id AND r.id = rl.receipt_id
                                          WHERE rl.tenant_id = l.tenant_id AND rl.order_line_id = l.id AND r.status = 'posted'), 0) AS received_minor,
                               coalesce((SELECT sum(bl.qty) FROM retail_supplier_bill_lines bl
                                           JOIN retail_supplier_bills b ON b.tenant_id = bl.tenant_id AND b.id = bl.bill_id AND b.status = 'posted'
                                           JOIN retail_goods_receipt_lines rl ON rl.tenant_id = bl.tenant_id AND rl.id = bl.receipt_line_id
                                          WHERE bl.tenant_id = l.tenant_id AND rl.order_line_id = l.id), 0) AS billed_qty,
                               coalesce((SELECT sum(bl.line_total_minor) FROM retail_supplier_bill_lines bl
                                           JOIN retail_supplier_bills b ON b.tenant_id = bl.tenant_id AND b.id = bl.bill_id AND b.status = 'posted'
                                           JOIN retail_goods_receipt_lines rl ON rl.tenant_id = bl.tenant_id AND rl.id = bl.receipt_line_id
                                          WHERE bl.tenant_id = l.tenant_id AND rl.order_line_id = l.id), 0) AS billed_minor
                          FROM retail_purchase_order_lines l
                          JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                          LEFT JOIN retail_units u ON u.tenant_id = l.tenant_id AND u.id = l.unit_id
                          LEFT JOIN retail_units bu ON bu.tenant_id = p.tenant_id AND bu.id = p.unit_id
                         WHERE l.order_id = ? ORDER BY l.line_no
                        """.formatted(RECEIVED_IN_ORDER_UNIT))
                .param(orderId)
                .query((rs, n) -> line(rs))
                .list();
    }

    /** The receipts of some orders, oldest first. */
    Map<UUID, List<ReceiptRef>> receipts(List<UUID> orderIds) {
        Map<UUID, List<ReceiptRef>> out = new HashMap<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT r.order_id, r.id, r.receipt_no, r.received_on, r.status, r.total_minor
                          FROM retail_goods_receipts r WHERE r.order_id IN (:ids) ORDER BY r.created_at, r.id
                        """).param("ids", orderIds).query(rs -> {
            out.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
                    .add(new ReceiptRef(
                            rs.getObject("id", UUID.class),
                            rs.getString("receipt_no"),
                            rs.getDate("received_on").toLocalDate(),
                            rs.getString("status"),
                            rs.getLong("total_minor")));
        });
        return out;
    }

    private static OrderRow row(ResultSet rs) throws SQLException {
        return new OrderRow(
                rs.getObject("id", UUID.class),
                rs.getString("order_no"),
                rs.getLong("branch_seq"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getDate("ordered_on").toLocalDate(),
                rs.getDate("expected_on") == null
                        ? null
                        : rs.getDate("expected_on").toLocalDate(),
                rs.getString("currency"),
                rs.getLong("total_minor"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getInt("line_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class),
                instant(rs, "sent_at"),
                rs.getObject("sent_by", UUID.class),
                rs.getObject("approval_id", UUID.class),
                rs.getObject("approved_by", UUID.class),
                instant(rs, "cancelled_at"),
                rs.getObject("cancelled_by", UUID.class),
                rs.getString("cancel_reason"),
                rs.getInt("version"));
    }

    private static LineRow line(ResultSet rs) throws SQLException {
        return new LineRow(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("product_id", UUID.class),
                rs.getString("code"),
                rs.getString("description"),
                rs.getBigDecimal("qty"),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit"),
                rs.getBigDecimal("unit_qty"),
                rs.getBigDecimal("unit_factor"),
                rs.getObject("unit_cost_minor", Long.class),
                rs.getLong("cost_minor"),
                rs.getLong("line_total_minor"),
                rs.getBigDecimal("received_qty"),
                rs.getLong("received_minor"),
                rs.getBigDecimal("billed_qty"),
                rs.getLong("billed_minor"),
                rs.getBigDecimal("received_unit_qty"),
                rs.getString("base_unit"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
