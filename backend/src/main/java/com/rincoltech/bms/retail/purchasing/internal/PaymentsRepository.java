package com.rincoltech.bms.retail.purchasing.internal;

import static com.rincoltech.bms.retail.purchasing.internal.OrdersRepository.instant;

import com.rincoltech.bms.kernel.Search;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for supplier payments and for what is owed to suppliers. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class PaymentsRepository {

    private static final String SELECT_PAYMENT = """
            SELECT p.id, p.payment_no, p.branch_seq, p.branch_id, p.supplier_id, s.name AS supplier_name, p.paid_on, p.currency,
                   p.method, p.amount_minor, p.allocation, p.reference, p.note, p.status, p.journal_entry_id, p.created_at,
                   p.created_by, p.reversed_at, p.reversed_on, p.reversed_by, p.reverse_reason, p.reversal_entry_id, p.version
              FROM retail_supplier_payments p
              JOIN retail_suppliers s ON s.tenant_id = p.tenant_id AND s.id = p.supplier_id
            """;

    /** What the payment list matches (ADR-039): our number, the reference and the supplier's name. */
    static final Search.Fields SEARCH =
            Search.Fields.of().codes("p.payment_no", "p.reference").names("s.name");

    private final JdbcClient jdbc;

    PaymentsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(
            UUID id,
            String paymentNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            LocalDate paidOn,
            String currency,
            String method,
            long amountMinor,
            String allocation,
            String reference,
            String note,
            String status,
            UUID journalEntryId,
            Instant createdAt,
            UUID createdBy,
            Instant reversedAt,
            LocalDate reversedOn,
            UUID reversedBy,
            String reverseReason,
            UUID reversalEntryId,
            int version) {}

    record AllocationRow(
            UUID paymentId,
            UUID billId,
            String billNo,
            String supplierRef,
            UUID purchaseId,
            String purchaseNo,
            UUID branchId,
            long amountMinor) {}

    /**
     * Something a supplier is owed: an invoice that stands or a credit restock, with what the payments that stand have
     * paid and the branches it belongs to (the invoice's, or every branch the restock put stock into).
     */
    record Open(
            String kind,
            UUID supplierId,
            String supplierName,
            UUID id,
            String number,
            String supplierRef,
            LocalDate date,
            LocalDate dueDate,
            long totalMinor,
            long paidMinor,
            long creditedMinor,
            Instant createdAt,
            Map<UUID, Long> shares,
            Map<UUID, Long> paidBy) {

        /** What is still to pay: the total less supplier credit notes that stand and less payments that stand. */
        long openMinor() {
            return totalMinor - creditedMinor - paidMinor;
        }

        /** The branches the item belongs to: the invoice's, or every branch whose creditors the restock credited. */
        Set<UUID> branchIds() {
            return shares.keySet();
        }

        /** What each branch still owes on the item. */
        Map<UUID, Long> owedBy() {
            Map<UUID, Long> out = new java.util.TreeMap<>();
            shares.forEach((b, s) -> out.put(b, s - paidBy.getOrDefault(b, 0L)));
            return out;
        }
    }

    record NewPayment(
            UUID id,
            String paymentNo,
            long branchSeq,
            UUID branchId,
            UUID supplierId,
            LocalDate paidOn,
            String currency,
            String method,
            long amountMinor,
            String allocation,
            String reference,
            String note,
            UUID journalEntryId,
            UUID createdBy) {}

    void insert(NewPayment p) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_payments (id, tenant_id, payment_no, branch_seq, branch_id, supplier_id, paid_on,
                            currency, method, amount_minor, allocation, reference, note, journal_entry_id, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :no, :seq, :branch, :supplier, :on, :currency, :method,
                            :amount, :allocation, :reference, :note, :entry, :by)
                        """)
                .param("id", p.id())
                .param("no", p.paymentNo())
                .param("seq", p.branchSeq())
                .param("branch", p.branchId())
                .param("supplier", p.supplierId())
                .param("on", Date.valueOf(p.paidOn()))
                .param("currency", p.currency())
                .param("method", p.method())
                .param("amount", p.amountMinor())
                .param("allocation", p.allocation())
                .param("reference", p.reference())
                .param("note", p.note())
                .param("entry", p.journalEntryId())
                .param("by", p.createdBy())
                .update();
    }

    void insertAllocation(UUID paymentId, int lineNo, UUID billId, UUID purchaseId, UUID branchId, long amountMinor) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_payment_allocations (id, tenant_id, payment_id, line_no, bill_id, purchase_id,
                                                                         branch_id, amount_minor)
                        VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                        """)
                .params(paymentId, lineNo, billId, purchaseId, branchId, amountMinor)
                .update();
    }

    /** The entries a payment posted in branches other than the paying one, kept so a reversal can reverse each. */
    void insertEntry(UUID paymentId, UUID branchId, UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_supplier_payment_entries (tenant_id, payment_id, branch_id, entry_id)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(paymentId, branchId, entryId).update();
    }

    List<UUID> entries(UUID paymentId) {
        return jdbc.sql("SELECT entry_id FROM retail_supplier_payment_entries WHERE payment_id = ? ORDER BY branch_id")
                .param(paymentId)
                .query(UUID.class)
                .list();
    }

    int markReversed(UUID id, UUID by, LocalDate on, Instant at, String reason, UUID reversalEntryId) {
        return jdbc.sql("""
                        UPDATE retail_supplier_payments SET status = 'reversed', reversed_at = ?, reversed_on = ?, reversed_by = ?,
                               reverse_reason = ?, reversal_entry_id = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'posted'
                        """)
                .params(Timestamp.from(at), Date.valueOf(on), by, reason, reversalEntryId, id)
                .update();
    }

    Optional<Row> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT_PAYMENT + " WHERE p.id = ?" + (lock ? " FOR UPDATE OF p" : ""))
                .param(id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    List<Row> page(
            List<UUID> branchIds,
            UUID supplierId,
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
        StringBuilder sql = new StringBuilder(SELECT_PAYMENT + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            sql.append(" AND p.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (supplierId != null) {
            sql.append(" AND p.supplier_id = :supplierId");
            params.put("supplierId", supplierId);
        }
        if (status != null) {
            sql.append(" AND p.status = :status");
            params.put("status", status);
        }
        if (from != null) {
            sql.append(" AND p.paid_on >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND p.paid_on <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (query != null) {
            Search.Match match = query.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(" AND (p.created_at, p.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> row(rs)).list();
    }

    Map<UUID, List<AllocationRow>> allocations(List<UUID> paymentIds) {
        Map<UUID, List<AllocationRow>> out = new HashMap<>();
        if (paymentIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT a.payment_id, a.bill_id, b.bill_no, b.supplier_ref, a.purchase_id, pu.purchase_no, a.branch_id, a.amount_minor
                          FROM retail_supplier_payment_allocations a
                          LEFT JOIN retail_supplier_bills b ON b.tenant_id = a.tenant_id AND b.id = a.bill_id
                          LEFT JOIN retail_purchases pu ON pu.tenant_id = a.tenant_id AND pu.id = a.purchase_id
                         WHERE a.payment_id IN (:ids) ORDER BY a.payment_id, a.line_no
                        """).param("ids", paymentIds).query(rs -> {
            out.computeIfAbsent(rs.getObject("payment_id", UUID.class), k -> new ArrayList<>())
                    .add(new AllocationRow(
                            rs.getObject("payment_id", UUID.class),
                            rs.getObject("bill_id", UUID.class),
                            rs.getString("bill_no"),
                            rs.getString("supplier_ref"),
                            rs.getObject("purchase_id", UUID.class),
                            rs.getString("purchase_no"),
                            rs.getObject("branch_id", UUID.class),
                            rs.getLong("amount_minor")));
        });
        return out;
    }

    private static final String PAID_BILL = """
            coalesce((SELECT sum(a.amount_minor) FROM retail_supplier_payment_allocations a
                        JOIN retail_supplier_payments p ON p.tenant_id = a.tenant_id AND p.id = a.payment_id AND p.status = 'posted'
                       WHERE a.tenant_id = b.tenant_id AND a.bill_id = b.id), 0)""";

    private static final String CREDITED_BILL = """
            coalesce((SELECT sum(c.total_minor) FROM retail_supplier_credits c
                       WHERE c.tenant_id = b.tenant_id AND c.bill_id = b.id AND c.status = 'posted'), 0)""";

    private static final String PAID_RESTOCK = """
            coalesce((SELECT sum(a.amount_minor) FROM retail_supplier_payment_allocations a
                        JOIN retail_supplier_payments p ON p.tenant_id = a.tenant_id AND p.id = a.payment_id AND p.status = 'posted'
                       WHERE a.tenant_id = pu.tenant_id AND a.purchase_id = pu.id), 0)""";

    /**
     * Every invoice that stands and every credit restock of the supplier (of every supplier when null) that still owes
     * something, with what was paid. Imported (historical) restocks have no journal entry and are not payables. Invoices come
     * first in id order, then restocks, so the caller can lock and sort as it needs.
     */
    List<Open> open(UUID supplierId) {
        List<Open> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT b.supplier_id, s.name AS supplier_name, b.id, b.bill_no, b.supplier_ref, b.bill_date, b.due_date,
                               b.total_minor, %s AS paid_minor, %s AS credited_minor, b.created_at, b.branch_id
                          FROM retail_supplier_bills b
                          JOIN retail_suppliers s ON s.tenant_id = b.tenant_id AND s.id = b.supplier_id
                         WHERE b.status = 'posted' AND (CAST(:supplier AS uuid) IS NULL OR b.supplier_id = CAST(:supplier AS uuid))
                           AND b.total_minor > %s + %s
                         ORDER BY b.id
                        """.formatted(PAID_BILL, CREDITED_BILL, PAID_BILL, CREDITED_BILL))
                .param("supplier", supplierId)
                .query(rs -> {
                    out.add(new Open(
                            "bill",
                            rs.getObject("supplier_id", UUID.class),
                            rs.getString("supplier_name"),
                            rs.getObject("id", UUID.class),
                            rs.getString("bill_no"),
                            rs.getString("supplier_ref"),
                            rs.getDate("bill_date").toLocalDate(),
                            rs.getDate("due_date").toLocalDate(),
                            rs.getLong("total_minor"),
                            rs.getLong("paid_minor"),
                            rs.getLong("credited_minor"),
                            rs.getTimestamp("created_at").toInstant(),
                            Map.of(
                                    rs.getObject("branch_id", UUID.class),
                                    rs.getLong("total_minor") - rs.getLong("credited_minor")),
                            Map.of(rs.getObject("branch_id", UUID.class), rs.getLong("paid_minor"))));
                });
        List<UUID> restocks = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        jdbc.sql("""
                        SELECT pu.supplier_id, s.name AS supplier_name, pu.id, pu.purchase_no, pu.purchased_on, pu.total_minor,
                               %s AS paid_minor, pu.created_at
                          FROM retail_purchases pu
                          JOIN retail_suppliers s ON s.tenant_id = pu.tenant_id AND s.id = pu.supplier_id
                         WHERE pu.payment_method = 'credit' AND NOT pu.historical
                           AND NOT EXISTS (SELECT 1 FROM retail_purchase_voids v WHERE v.tenant_id = pu.tenant_id AND v.purchase_id = pu.id)
                           AND (CAST(:supplier AS uuid) IS NULL OR pu.supplier_id = CAST(:supplier AS uuid))
                           AND pu.total_minor > %s
                         ORDER BY pu.id
                        """.formatted(PAID_RESTOCK, PAID_RESTOCK))
                .param("supplier", supplierId)
                .query(rs -> {
                    restocks.add(rs.getObject("id", UUID.class));
                    rows.add(new Object[] {
                        rs.getObject("supplier_id", UUID.class),
                        rs.getString("supplier_name"),
                        rs.getObject("id", UUID.class),
                        rs.getString("purchase_no"),
                        rs.getDate("purchased_on").toLocalDate(),
                        rs.getLong("total_minor"),
                        rs.getLong("paid_minor"),
                        rs.getTimestamp("created_at").toInstant()
                    });
                });
        if (restocks.isEmpty()) {
            return out;
        }
        // What each branch's trade creditors were credited by the restock (its entries), and what the payments that
        // stand
        // have paid on it per branch.
        Map<UUID, Map<UUID, Long>> credited = new HashMap<>();
        jdbc.sql("""
                        SELECT e.source_id AS purchase_id, e.branch_id, sum(l.credit - l.debit) AS credited
                          FROM journal_lines l
                          JOIN journal_entries e ON e.tenant_id = l.tenant_id AND e.id = l.entry_id
                          JOIN gl_accounts a ON a.tenant_id = l.tenant_id AND a.id = l.account_id AND a.system_key = 'trade_creditors'
                         WHERE e.source_type = 'retail.purchase' AND e.source_id IN (:ids)
                         GROUP BY e.source_id, e.branch_id
                        """).param("ids", restocks).query(rs -> {
            credited.computeIfAbsent(rs.getObject("purchase_id", UUID.class), k -> new java.util.TreeMap<>())
                    .put(rs.getObject("branch_id", UUID.class), rs.getLong("credited"));
        });
        Map<UUID, Map<UUID, Long>> paidBy = new HashMap<>();
        jdbc.sql("""
                        SELECT a.purchase_id, a.branch_id, sum(a.amount_minor) AS paid
                          FROM retail_supplier_payment_allocations a
                          JOIN retail_supplier_payments p ON p.tenant_id = a.tenant_id AND p.id = a.payment_id AND p.status = 'posted'
                         WHERE a.purchase_id IN (:ids) GROUP BY a.purchase_id, a.branch_id
                        """).param("ids", restocks).query(rs -> {
            paidBy.computeIfAbsent(rs.getObject("purchase_id", UUID.class), k -> new java.util.TreeMap<>())
                    .put(rs.getObject("branch_id", UUID.class), rs.getLong("paid"));
        });
        for (Object[] r : rows) {
            UUID id = (UUID) r[2];
            out.add(new Open(
                    "restock",
                    (UUID) r[0],
                    (String) r[1],
                    id,
                    (String) r[3],
                    null,
                    (LocalDate) r[4],
                    (LocalDate) r[4],
                    (Long) r[5],
                    (Long) r[6],
                    0L,
                    (Instant) r[7],
                    credited.getOrDefault(id, Map.of()),
                    paidBy.getOrDefault(id, Map.of())));
        }
        return out;
    }

    /** Received and not yet invoiced for one supplier: the receipt lines' values less what invoices that stand cleared. */
    record Unbilled(UUID supplierId, String supplierName, long amountMinor) {}

    List<Unbilled> unbilledBySupplier(List<UUID> branchIds) {
        List<Unbilled> out = new ArrayList<>();
        if (branchIds != null && branchIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT r.supplier_id, s.name AS supplier_name,
                               sum(l.line_total_minor - coalesce((SELECT sum(bl.cleared_minor) FROM retail_supplier_bill_lines bl
                                                                    JOIN retail_supplier_bills b ON b.tenant_id = bl.tenant_id AND b.id = bl.bill_id AND b.status = 'posted'
                                                                   WHERE bl.tenant_id = l.tenant_id AND bl.receipt_line_id = l.id), 0)) AS unbilled
                          FROM retail_goods_receipt_lines l
                          JOIN retail_goods_receipts r ON r.tenant_id = l.tenant_id AND r.id = l.receipt_id AND r.status = 'posted'
                          JOIN retail_suppliers s ON s.tenant_id = r.tenant_id AND s.id = r.supplier_id
                         WHERE (CAST(:filtered AS boolean) = false OR r.branch_id IN (:branchIds))
                         GROUP BY r.supplier_id, s.name
                        """)
                .param("filtered", branchIds != null)
                .param("branchIds", branchIds == null ? List.of(new UUID(0, 0)) : branchIds)
                .query(rs -> {
                    out.add(new Unbilled(
                            rs.getObject("supplier_id", UUID.class),
                            rs.getString("supplier_name"),
                            rs.getLong("unbilled")));
                });
        return out;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("payment_no"),
                rs.getLong("branch_seq"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getDate("paid_on").toLocalDate(),
                rs.getString("currency"),
                rs.getString("method"),
                rs.getLong("amount_minor"),
                rs.getString("allocation"),
                rs.getString("reference"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getObject("journal_entry_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                instant(rs, "reversed_at"),
                rs.getDate("reversed_on") == null
                        ? null
                        : rs.getDate("reversed_on").toLocalDate(),
                rs.getObject("reversed_by", UUID.class),
                rs.getString("reverse_reason"),
                rs.getObject("reversal_entry_id", UUID.class),
                rs.getInt("version"));
    }

    // ---- Statement (issue #557) ----------------------------------------------------------------

    /**
     * One thing that changed what a supplier is owed: {@code amount} is signed, positive when it adds to the debt.
     * {@code branchIds} are the branches it belongs to (a credit restock's are every branch its stock went into).
     */
    record Event(
            String kind,
            LocalDate day,
            Instant at,
            String number,
            String reference,
            UUID sourceId,
            String method,
            long amount,
            Set<UUID> branchIds) {}

    private static final String RESTOCK_BRANCHES = """
            ARRAY(SELECT DISTINCT m.branch_id FROM retail_stock_movements m
                   WHERE m.tenant_id = pu.tenant_id AND m.source_type = 'retail.purchase' AND m.source_id = pu.id
                     AND m.kind = 'purchase')""";

    /**
     * Every event of the supplier dated on or before {@code to}, in the order they happened: credit restocks (never
     * imported ones, which no payment can pay) and their voids, supplier invoices and credit notes and their voids, and
     * payments and their reversals, each on its own day. Within a day they come in the order they were entered, and a void or
     * reversal never before what it undoes (its time is the business clock's, the other's the database's).
     */
    List<Event> statementEvents(UUID supplierId, LocalDate to) {
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT 'purchase' AS kind, pu.purchased_on AS day, pu.created_at AS at, pu.purchase_no AS number,
                                   NULL AS reference, pu.id AS source_id, NULL AS method, pu.total_minor AS amount,
                                   %1$s AS branches
                              FROM retail_purchases pu
                             WHERE pu.supplier_id = :supplier AND pu.payment_method = 'credit' AND NOT pu.historical
                               AND pu.total_minor > 0
                            UNION ALL
                            SELECT 'purchase_void', v.voided_on, greatest(v.created_at, pu.created_at), pu.purchase_no, NULL, pu.id, NULL,
                                   -pu.total_minor, %1$s
                              FROM retail_purchase_voids v
                              JOIN retail_purchases pu ON pu.tenant_id = v.tenant_id AND pu.id = v.purchase_id
                             WHERE pu.supplier_id = :supplier AND pu.payment_method = 'credit' AND NOT pu.historical
                               AND pu.total_minor > 0
                            UNION ALL
                            SELECT 'bill', b.bill_date, b.created_at, b.bill_no, b.supplier_ref, b.id, NULL, b.total_minor,
                                   ARRAY[b.branch_id]
                              FROM retail_supplier_bills b WHERE b.supplier_id = :supplier
                            UNION ALL
                            SELECT 'bill_void', b.voided_on, greatest(b.voided_at, b.created_at), b.bill_no, b.supplier_ref, b.id, NULL, -b.total_minor,
                                   ARRAY[b.branch_id]
                              FROM retail_supplier_bills b WHERE b.supplier_id = :supplier AND b.status = 'voided'
                            UNION ALL
                            SELECT 'credit_note', c.credit_date, c.created_at, c.credit_no, c.supplier_ref, c.id, NULL,
                                   -c.total_minor, ARRAY[b.branch_id]
                              FROM retail_supplier_credits c
                              JOIN retail_supplier_bills b ON b.tenant_id = c.tenant_id AND b.id = c.bill_id
                             WHERE c.supplier_id = :supplier
                            UNION ALL
                            SELECT 'credit_note_void', c.voided_on, greatest(c.voided_at, c.created_at), c.credit_no, c.supplier_ref, c.id, NULL,
                                   c.total_minor, ARRAY[b.branch_id]
                              FROM retail_supplier_credits c
                              JOIN retail_supplier_bills b ON b.tenant_id = c.tenant_id AND b.id = c.bill_id
                             WHERE c.supplier_id = :supplier AND c.status = 'voided'
                            UNION ALL
                            SELECT 'payment', p.paid_on, p.created_at, p.payment_no, p.reference, p.id, p.method,
                                   -p.amount_minor, ARRAY[p.branch_id]
                              FROM retail_supplier_payments p WHERE p.supplier_id = :supplier
                            UNION ALL
                            SELECT 'payment_reversed', p.reversed_on, greatest(p.reversed_at, p.created_at), p.payment_no, p.reference, p.id, p.method,
                                   p.amount_minor, ARRAY[p.branch_id]
                              FROM retail_supplier_payments p WHERE p.supplier_id = :supplier AND p.status = 'reversed'
                        ) e
                         WHERE e.day <= :to
                         ORDER BY e.day, e.at, e.number, e.kind
                        """.formatted(RESTOCK_BRANCHES))
                .param("supplier", supplierId)
                .param("to", Date.valueOf(to))
                .query((rs, n) -> new Event(
                        rs.getString("kind"),
                        rs.getDate("day").toLocalDate(),
                        instant(rs, "at"),
                        rs.getString("number"),
                        rs.getString("reference"),
                        rs.getObject("source_id", UUID.class),
                        rs.getString("method"),
                        rs.getLong("amount"),
                        uuids(rs.getArray("branches"))))
                .list();
    }

    /** What each payment of the supplier paid, per item, with the branches of the item it paid. */
    record Paid(UUID paymentId, long amount, Set<UUID> itemBranchIds) {}

    List<Paid> paidBySupplier(UUID supplierId) {
        return jdbc.sql("""
                        SELECT a.payment_id, a.amount_minor,
                               CASE WHEN a.bill_id IS NOT NULL
                                    THEN ARRAY(SELECT b.branch_id FROM retail_supplier_bills b
                                                WHERE b.tenant_id = a.tenant_id AND b.id = a.bill_id)
                                    ELSE (SELECT %s FROM retail_purchases pu
                                           WHERE pu.tenant_id = a.tenant_id AND pu.id = a.purchase_id) END AS branches
                          FROM retail_supplier_payment_allocations a
                          JOIN retail_supplier_payments p ON p.tenant_id = a.tenant_id AND p.id = a.payment_id
                         WHERE p.supplier_id = :supplier
                        """.formatted(RESTOCK_BRANCHES))
                .param("supplier", supplierId)
                .query((rs, n) -> new Paid(
                        rs.getObject("payment_id", UUID.class),
                        rs.getLong("amount_minor"),
                        uuids(rs.getArray("branches"))))
                .list();
    }

    private static Set<UUID> uuids(java.sql.Array array) throws SQLException {
        Set<UUID> out = new java.util.TreeSet<>();
        if (array != null) {
            for (Object o : (Object[]) array.getArray()) {
                out.add((UUID) o);
            }
        }
        return out;
    }

    /**
     * Whether a banking that stands at the branch is dated on or after {@code day} (issue #557). The cash book carries cash not yet
     * banked forward, so a banking covers its own day and every earlier one: cash paid out on any of them would change what that
     * banking was expected to hold.
     */
    boolean cashBankedFrom(UUID branchId, LocalDate day) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_cash_bankings
                                        WHERE tenant_id = current_setting('app.tenant_id')::uuid AND branch_id = :branch
                                          AND business_date >= :day AND voided_at IS NULL)
                        """)
                .param("branch", branchId)
                .param("day", Date.valueOf(day))
                .query(Boolean.class)
                .single();
    }
}
