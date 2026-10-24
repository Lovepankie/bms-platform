package com.rincoltech.bms.retail.purchasing.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the lot a received line is: what it cost, what landed cost and supplier credits have since added to or taken
 * from that cost, how much of it went back to the supplier, and the stock facts the first-in first-out reading of
 * ADR-033, issue #199 amendment needs (the later stock that came in, and whether a newer cost event has set the product's cost).
 * Row-level security supplies the tenant predicate (ADR-003).
 */
@Repository
class LotRepository {

    private final JdbcClient jdbc;

    LotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A received line as a lot.
     *
     * @param qty received, in base units
     * @param stockedMinor the value the stock movement carried: base quantity times the cost of one base unit
     * @param landedMinor what standing landed cost documents allocated to the line
     * @param priceCreditMinor what standing supplier price credits took off the line's cost (not the part that corrected the invoice's variance)
     * @param returnedQty what standing supplier credits for goods sent back took out of the line, in base units
     * @param returnedInventoryMinor what those credits took out of inventory
     */
    record Lot(
            UUID receiptLineId,
            UUID receiptId,
            String receiptNo,
            int lineNo,
            UUID branchId,
            UUID supplierId,
            LocalDate receivedOn,
            String status,
            UUID productId,
            String code,
            String description,
            BigDecimal qty,
            UUID unitId,
            String unit,
            BigDecimal unitFactor,
            long costMinor,
            long stockedMinor,
            long lineTotalMinor,
            long landedMinor,
            long priceCreditMinor,
            BigDecimal returnedQty,
            long returnedInventoryMinor) {

        /** What the lot is carried at in inventory per the books: the stocked value, plus landed cost, less price credits. */
        long lotCostMinor() {
            return stockedMinor + landedMinor - priceCreditMinor;
        }
    }

    /**
     * Where a receipt line's stock movement sits in time: the transaction it was written in, then its line number on the
     * receipt (two lines of one product on one receipt are written together, the later line is the later layer), then its id.
     */
    record MovementKey(UUID id, Timestamp createdAt, int lineNo) {}

    /** The order of movements: later by time, then by receipt line number, then by id. {@code m} is the movement, {@code ln} its receipt line. */
    private static final String AFTER = """
            (m.created_at > :at OR (m.created_at = :at AND coalesce(ln.line_no, 0) > :lineNo)
                OR (m.created_at = :at AND coalesce(ln.line_no, 0) = :lineNo AND m.id > :id))""";

    private static final String MOVEMENT_LINE = """
            LEFT JOIN retail_goods_receipt_lines ln ON ln.tenant_id = m.tenant_id AND ln.id = m.source_line_id
                 AND m.source_type = 'retail.goods_receipt'""";

    private static final String LOT = """
            SELECT l.id AS receipt_line_id, l.receipt_id, r.receipt_no, l.line_no, r.branch_id, r.supplier_id, r.received_on,
                   r.status, l.product_id, p.code, p.description, l.qty, l.unit_id, u.name AS unit, l.unit_factor, l.cost_minor,
                   l.stocked_minor, l.line_total_minor,
                   coalesce((SELECT sum(x.allocated_minor) FROM retail_landed_cost_lines x
                               JOIN retail_landed_costs d ON d.tenant_id = x.tenant_id AND d.id = x.landed_cost_id AND d.status = 'posted'
                              WHERE x.tenant_id = l.tenant_id AND x.receipt_line_id = l.id), 0) AS landed_minor,
                   coalesce((SELECT sum(c.to_inventory_minor + c.to_cogs_minor) FROM retail_supplier_credit_lines c
                               JOIN retail_supplier_credits h ON h.tenant_id = c.tenant_id AND h.id = c.credit_id
                                    AND h.status = 'posted' AND h.kind = 'price'
                              WHERE c.tenant_id = l.tenant_id AND c.receipt_line_id = l.id), 0) AS price_credit_minor,
                   coalesce((SELECT sum(c.qty) FROM retail_supplier_credit_lines c
                               JOIN retail_supplier_credits h ON h.tenant_id = c.tenant_id AND h.id = c.credit_id
                                    AND h.status = 'posted' AND h.kind = 'goods'
                              WHERE c.tenant_id = l.tenant_id AND c.receipt_line_id = l.id), 0) AS returned_qty,
                   coalesce((SELECT sum(c.to_inventory_minor) FROM retail_supplier_credit_lines c
                               JOIN retail_supplier_credits h ON h.tenant_id = c.tenant_id AND h.id = c.credit_id
                                    AND h.status = 'posted' AND h.kind = 'goods'
                              WHERE c.tenant_id = l.tenant_id AND c.receipt_line_id = l.id), 0) AS returned_inventory_minor
              FROM retail_goods_receipt_lines l
              JOIN retail_goods_receipts r ON r.tenant_id = l.tenant_id AND r.id = l.receipt_id
              JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
              LEFT JOIN retail_units u ON u.tenant_id = l.tenant_id AND u.id = l.unit_id
            """;

    /** The lots of the given receipt lines, by receipt line id. */
    Map<UUID, Lot> lots(List<UUID> receiptLineIds) {
        Map<UUID, Lot> out = new HashMap<>();
        if (receiptLineIds.isEmpty()) {
            return out;
        }
        jdbc.sql(LOT + " WHERE l.id IN (:ids)").param("ids", receiptLineIds).query(rs -> {
            Lot x = new Lot(
                    rs.getObject("receipt_line_id", UUID.class),
                    rs.getObject("receipt_id", UUID.class),
                    rs.getString("receipt_no"),
                    rs.getInt("line_no"),
                    rs.getObject("branch_id", UUID.class),
                    rs.getObject("supplier_id", UUID.class),
                    rs.getDate("received_on").toLocalDate(),
                    rs.getString("status"),
                    rs.getObject("product_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("description"),
                    rs.getBigDecimal("qty"),
                    rs.getObject("unit_id", UUID.class),
                    rs.getString("unit"),
                    rs.getBigDecimal("unit_factor"),
                    rs.getLong("cost_minor"),
                    rs.getLong("stocked_minor"),
                    rs.getLong("line_total_minor"),
                    rs.getLong("landed_minor"),
                    rs.getLong("price_credit_minor"),
                    rs.getBigDecimal("returned_qty"),
                    rs.getLong("returned_inventory_minor"));
            out.put(x.receiptLineId(), x);
        });
        return out;
    }

    /** The movement that put a receipt line's stock in, if the line still has one. */
    Optional<MovementKey> receiptMovement(UUID receiptLineId) {
        return jdbc.sql("""
                        SELECT m.id, m.created_at, coalesce(ln.line_no, 0) AS line_no FROM retail_stock_movements m
                          %s
                         WHERE m.source_type = 'retail.goods_receipt' AND m.source_line_id = ? AND m.kind = 'purchase'
                         ORDER BY m.created_at, m.id LIMIT 1
                        """.formatted(MOVEMENT_LINE))
                .param(receiptLineId)
                .query((rs, n) -> new MovementKey(
                        rs.getObject("id", UUID.class), rs.getTimestamp("created_at"), rs.getInt("line_no")))
                .optional();
    }

    /**
     * The stock that came in after the receipt line's own movement and is still counted, in one branch or (a null branch) in
     * every branch: positive movements that are not themselves the undoing of another movement and have not been undone, that
     * are not a transfer in (the same units arriving, not a later layer) nor a customer return (goods that went out of the
     * shelf coming back), less what standing supplier credit notes sent back out of those later receipts (a unit sent back
     * never reached the shelf). First in, first out reads the balance less this as what is left of the receipt line. Read
     * branch by branch, so that each read is a range of the (tenant, branch, product) index.
     */
    BigDecimal laterInflow(UUID branchIdOrNull, UUID productId, MovementKey after) {
        return jdbc.sql("""
                        SELECT coalesce(sum(later.qty), 0) FROM branches br
                          CROSS JOIN LATERAL (
                            SELECT m.qty - coalesce((SELECT sum(c.qty) FROM retail_supplier_credit_lines c
                                                       JOIN retail_supplier_credits h ON h.tenant_id = c.tenant_id
                                                            AND h.id = c.credit_id AND h.status = 'posted' AND h.kind = 'goods'
                                                      WHERE c.tenant_id = m.tenant_id AND c.receipt_line_id = m.source_line_id
                                                        AND m.source_type = 'retail.goods_receipt'), 0) AS qty
                              FROM retail_stock_movements m
                              %s
                             WHERE m.branch_id = br.id AND m.product_id = :product AND m.qty > 0
                               AND m.reverses_movement_id IS NULL AND m.kind NOT IN ('transfer_in', 'return')
                               AND %s
                               AND NOT EXISTS (SELECT 1 FROM retail_stock_movements v
                                                WHERE v.tenant_id = m.tenant_id AND v.reverses_movement_id = m.id)) later
                         WHERE (CAST(:branch AS uuid) IS NULL OR br.id = CAST(:branch AS uuid))
                        """.formatted(MOVEMENT_LINE, AFTER))
                .param("branch", branchIdOrNull)
                .param("product", productId)
                .param("at", after.createdAt())
                .param("lineNo", after.lineNo())
                .param("id", after.id())
                .query(BigDecimal.class)
                .single();
    }

    /** What each branch holds of the product now (branches holding none are left out), in branch order. */
    Map<UUID, BigDecimal> balances(UUID productId) {
        Map<UUID, BigDecimal> out = new java.util.TreeMap<>();
        jdbc.sql("SELECT branch_id, qty FROM retail_stock_balances WHERE product_id = ? AND qty > 0")
                .param(productId)
                .query(rs -> {
                    out.put(rs.getObject("branch_id", UUID.class), rs.getBigDecimal("qty"));
                });
        return out;
    }

    /**
     * ADR-079: records the part of a landed cost or price credit line that went into each branch's inventory, and so into that
     * branch's average, for its void to take back from the same branches.
     */
    void insertSpread(UUID lineId, UUID productId, Map<UUID, Long> byBranch) {
        // Only positive parts are recorded, as the column's check requires.
        // spread() makes no other part (ReceiptCostingTest), so a negative one is a bug, refused.
        byBranch.forEach((branch, part) -> {
            if (part < 0) {
                throw new IllegalStateException("a negative part of a cost spread: " + part);
            }
            if (part > 0) {
                jdbc.sql("""
                        INSERT INTO retail_cost_spreads (tenant_id, line_id, branch_id, product_id, inventory_minor)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(lineId, branch, productId, part).update();
            }
        });
    }

    /** The parts {@link #insertSpread} recorded for a line, by branch in branch order; empty for a line recorded before V94. */
    Map<UUID, Long> spread(UUID lineId) {
        Map<UUID, Long> out = new java.util.TreeMap<>();
        jdbc.sql("SELECT branch_id, inventory_minor FROM retail_cost_spreads WHERE line_id = ?")
                .param(lineId)
                .query(rs -> {
                    out.put(rs.getObject("branch_id", UUID.class), rs.getLong("inventory_minor"));
                });
        return out;
    }

    /** A line of a receipt held as received and not costed that stands. */
    record HeldLine(String receiptNo, UUID receiptLineId, UUID productId) {}

    /** The lines of receipts held as not costed that stand in the branch for any of the products, oldest first. */
    List<HeldLine> heldLines(UUID branchId, java.util.Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT r.receipt_no, l.id, l.product_id FROM retail_receipt_holds h
                          JOIN retail_goods_receipts r ON r.tenant_id = h.tenant_id AND r.id = h.receipt_id AND r.status = 'posted'
                          JOIN retail_goods_receipt_lines l ON l.tenant_id = r.tenant_id AND l.receipt_id = r.id
                         WHERE h.released_at IS NULL AND r.branch_id = :branch AND l.product_id IN (:products)
                         ORDER BY r.received_on, r.created_at, l.line_no
                        """)
                .param("branch", branchId)
                .param("products", productIds)
                .query((rs, n) ->
                        new HeldLine(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)))
                .list();
    }

    /**
     * True when a cost event newer than the receipt line's movement has set the product's cost: a later receipt or restock
     * (a purchase movement that stands), or a later change of the cost in the price history that is not one of our own
     * landed cost and credit documents (or the document being posted now). The cost update guard of
     * trade-capabilities.md section 7.2. The movements are read branch by branch so that each read is a range of the
     * (tenant, branch, product) index and not a scan of the tenant's movements.
     */
    boolean newerCostEvent(UUID productId, MovementKey after, UUID ownDocumentId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                                 SELECT 1 FROM branches br
                                  CROSS JOIN LATERAL (
                                    SELECT 1 FROM retail_stock_movements m
                                      %s
                                     WHERE m.branch_id = br.id AND m.product_id = :product AND m.kind = 'purchase' AND %s
                                       AND NOT EXISTS (SELECT 1 FROM retail_stock_movements v
                                                        WHERE v.tenant_id = m.tenant_id AND v.reverses_movement_id = m.id)
                                     LIMIT 1) later)
                            OR EXISTS (
                                 SELECT 1 FROM retail_price_history h
                                  WHERE h.product_id = :product AND h.created_at > :at AND h.old_cost_minor IS NOT NULL
                                    AND h.old_cost_minor <> h.new_cost_minor
                                    AND (CAST(:own AS uuid) IS NULL OR h.source_id IS NULL OR h.source_id <> CAST(:own AS uuid))
                                    AND NOT (h.source = 'purchase' AND h.source_id IN
                                             (SELECT d.id FROM retail_landed_costs d
                                              UNION ALL SELECT c.id FROM retail_supplier_credits c)))
                        """.formatted(MOVEMENT_LINE, AFTER))
                .param("product", productId)
                .param("at", after.createdAt())
                .param("lineNo", after.lineNo())
                .param("id", after.id())
                .param("own", ownDocumentId)
                .query(Boolean.class)
                .single();
    }
}
