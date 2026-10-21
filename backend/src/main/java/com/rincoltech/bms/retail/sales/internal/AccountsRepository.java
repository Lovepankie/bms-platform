package com.rincoltech.bms.retail.sales.internal;

import java.sql.Date;
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

/**
 * SQL of the customer accounts (ADR-033, issue #196). Row-level security supplies the tenant predicate (ADR-003). The
 * statement is built from sales and sale payments and the receipts of payments on account; it reads no ledger table, and
 * the tests compare it with trade debtors.
 */
@Repository
class AccountsRepository {

    private final JdbcClient jdbc;

    AccountsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What a buyer's account holds: the limit and the terms, and the version that guards them. */
    record Terms(String name, Long creditLimitMinor, Integer termsDays, int version) {}

    /** A credit sale that owes, as the allocation sees it. */
    record OpenSale(
            UUID id,
            String saleNo,
            UUID branchId,
            LocalDate saleDate,
            String currency,
            long totalMinor,
            long paidMinor,
            java.time.Instant createdAt,
            boolean historical) {
        long balanceMinor() {
            return totalMinor - paidMinor;
        }
    }

    record Receipt(
            UUID id,
            String receiptNo,
            UUID customerId,
            UUID branchId,
            long amountMinor,
            String currency,
            String method,
            LocalDate paidOn,
            String allocation,
            UUID journalEntryId,
            String status,
            Instant createdAt,
            UUID createdBy,
            Instant reversedAt,
            UUID reversedBy,
            String reverseReason) {}

    record AllocationRow(
            UUID receiptId, UUID saleId, String saleNo, UUID branchId, long amountMinor, UUID salePaymentId) {}

    record Line(
            LocalDate date,
            String kind,
            String reference,
            UUID saleId,
            UUID receiptId,
            String method,
            long debit,
            long credit) {}

    record Historical(UUID saleId, String saleNo, UUID branchId, LocalDate saleDate, long totalMinor) {}

    // ---- The account --------------------------------------------------------------------------

    /** The account of a buyer; with {@code lock} the row is locked until the end of the transaction. */
    Optional<Terms> terms(UUID id, boolean lock) {
        return jdbc.sql("SELECT name, credit_limit_minor, terms_days, version FROM retail_customers WHERE id = ?"
                        + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query((rs, n) -> new Terms(
                        rs.getString("name"),
                        rs.getObject("credit_limit_minor", Long.class),
                        rs.getObject("terms_days", Integer.class),
                        rs.getInt("version")))
                .optional();
    }

    /** What a buyer owes on completed credit sales in every branch, imported ones included: exposure. */
    long exposure(UUID customerId) {
        // Issue #385: a delivery fee left on the buyer's account and not yet paid is owed as well, while its sale
        // stands.
        return jdbc.sql("""
                        SELECT coalesce((SELECT sum(total_minor - paid_minor) FROM retail_sales
                                          WHERE customer_id = :customer AND payment_method = 'credit' AND status = 'completed'), 0)
                             + coalesce((SELECT sum(o.delivery_fee_minor) FROM retail_customer_orders o
                                           JOIN retail_sales s ON s.id = o.sale_id
                                          WHERE o.customer_id = :customer AND o.fee_method = 'account' AND o.fee_paid_at IS NULL
                                            AND s.status = 'completed'), 0)
                        """).param("customer", customerId).query(Long.class).single();
    }

    /** Rows changed: 0 when the version moved since it was read (the predicate is the optimistic lock). */
    int updateAccount(UUID id, Long creditLimitMinor, Integer termsDays, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE retail_customers SET credit_limit_minor = ?, terms_days = ?, updated_at = now(),
                               version = version + 1
                         WHERE id = ? AND version = ?
                        """)
                .params(creditLimitMinor, termsDays, id, expectedVersion)
                .update();
    }

    // ---- Allocation ----------------------------------------------------------------------------

    private static OpenSale openSale(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OpenSale(
                rs.getObject("id", UUID.class),
                rs.getString("sale_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getDate("sale_date").toLocalDate(),
                rs.getString("currency"),
                rs.getLong("total_minor"),
                rs.getLong("paid_minor"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("historical"));
    }

    /**
     * The completed credit sales of a buyer that owe, oldest first, locked. Historical imports are left out (they stay
     * outside the ledger until issue #96 is decided), and so are the sales outside {@code branchIds} (null: every branch)
     * and the sales dated after the payment.
     */
    List<OpenSale> allocatable(UUID customerId, List<UUID> branchIds, LocalDate paidOn) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customer", customerId);
        params.put("paidOn", Date.valueOf(paidOn));
        String branch = "";
        if (branchIds != null) {
            branch = " AND branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("""
                        SELECT id, sale_no, branch_id, sale_date, currency, total_minor, paid_minor, created_at, historical FROM retail_sales
                         WHERE customer_id = :customer AND payment_method = 'credit' AND status = 'completed'
                           AND NOT historical AND paid_minor < total_minor AND sale_date <= :paidOn""" + branch + " ORDER BY sale_date, created_at, id FOR UPDATE")
                .params(params)
                .query((rs, n) -> openSale(rs))
                .list();
    }

    /** The named sales of a buyer, locked in a fixed order so two payments cannot deadlock. */
    List<OpenSale> lockSales(UUID customerId, List<UUID> ids) {
        return jdbc.sql("""
                        SELECT id, sale_no, branch_id, sale_date, currency, total_minor, paid_minor, created_at, historical FROM retail_sales
                         WHERE customer_id = :customer AND payment_method = 'credit' AND status = 'completed'
                           AND id IN (:ids) ORDER BY sale_date, created_at, id FOR UPDATE
                        """)
                .param("customer", customerId)
                .param("ids", ids)
                .query((rs, n) -> openSale(rs))
                .list();
    }

    /** The sales among these whose unpaid part was written off (ADR-043): a payment that paid one cannot be reversed. */
    List<UUID> writtenOffAmong(List<UUID> ids) {
        return jdbc.sql("SELECT sale_id FROM retail_sale_writeoffs WHERE sale_id IN (:ids)")
                .param("ids", ids)
                .query(UUID.class)
                .list();
    }

    /** Whether a sale is an imported (historical) one: the manual allocation refuses those too. */
    List<UUID> historicalAmong(List<UUID> ids) {
        return jdbc.sql("SELECT id FROM retail_sales WHERE id IN (:ids) AND historical")
                .param("ids", ids)
                .query(UUID.class)
                .list();
    }

    void insertReceipt(Receipt r) {
        jdbc.sql("""
                        INSERT INTO retail_account_payments (id, tenant_id, receipt_no, customer_id, branch_id, amount_minor,
                            currency, method, paid_on, allocation, journal_entry_id, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        r.id(),
                        r.receiptNo(),
                        r.customerId(),
                        r.branchId(),
                        r.amountMinor(),
                        r.currency(),
                        r.method(),
                        Date.valueOf(r.paidOn()),
                        r.allocation(),
                        r.journalEntryId(),
                        r.createdBy())
                .update();
    }

    void insertAllocation(UUID id, UUID receiptId, UUID saleId, UUID salePaymentId, long amountMinor) {
        jdbc.sql("""
                        INSERT INTO retail_account_payment_allocations (id, tenant_id, receipt_id, sale_id, sale_payment_id,
                            amount_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(id, receiptId, saleId, salePaymentId, amountMinor).update();
    }

    // ---- Receipts -------------------------------------------------------------------------------

    private static final String SELECT_RECEIPT = """
            SELECT id, receipt_no, customer_id, branch_id, amount_minor, currency, method, paid_on, allocation,
                   journal_entry_id, status, created_at, created_by, reversed_at, reversed_by, reverse_reason
              FROM retail_account_payments
            """;

    private static Receipt receipt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Receipt(
                rs.getObject("id", UUID.class),
                rs.getString("receipt_no"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("method"),
                rs.getDate("paid_on").toLocalDate(),
                rs.getString("allocation"),
                rs.getObject("journal_entry_id", UUID.class),
                rs.getString("status"),
                SalesRepository.instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class),
                SalesRepository.instant(rs, "reversed_at"),
                rs.getObject("reversed_by", UUID.class),
                rs.getString("reverse_reason"));
    }

    Optional<Receipt> receipt(UUID id, UUID customerId, boolean lock) {
        return jdbc.sql(SELECT_RECEIPT + " WHERE id = ? AND customer_id = ?" + (lock ? " FOR UPDATE" : ""))
                .params(id, customerId)
                .query((rs, n) -> receipt(rs))
                .optional();
    }

    /**
     * Newest first. With {@code branchIds} (null: every branch) a receipt is listed when it paid a sale of one of those
     * branches, and then {@code amount_minor} is the part of it that paid those sales, so the list agrees with the
     * statement, which counts a payment in the branch of the sale it paid. The receiving branch is only the place the
     * money was banked; the ledger and the cash book use it, this view does not.
     */
    List<Receipt> receipts(UUID customerId, List<UUID> branchIds, Instant afterCreated, UUID afterId, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        String inScope = "(SELECT sum(a.amount_minor) FROM retail_account_payment_allocations a"
                + " JOIN retail_sales s ON s.tenant_id = a.tenant_id AND s.id = a.sale_id"
                + " WHERE a.tenant_id = p.tenant_id AND a.receipt_id = p.id AND s.branch_id IN (:branchIds))";
        StringBuilder sql = new StringBuilder(
                branchIds == null
                        ? SELECT_RECEIPT.replace("FROM retail_account_payments", "FROM retail_account_payments p")
                        : SELECT_RECEIPT
                                .replace("amount_minor,", inScope + " AS amount_minor,")
                                .replace("FROM retail_account_payments", "FROM retail_account_payments p"));
        sql.append(" WHERE p.customer_id = :customer");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customer", customerId);
        if (branchIds != null) {
            sql.append(" AND ").append(inScope).append(" IS NOT NULL");
            params.put("branchIds", branchIds);
        }
        if (afterCreated != null) {
            sql.append(" AND (p.created_at, p.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> receipt(rs))
                .list();
    }

    /** The allocations of some receipts; those of sales outside {@code branchIds} (null: every branch) are left out. */
    List<AllocationRow> allocations(List<UUID> receiptIds, List<UUID> branchIds) {
        if (receiptIds.isEmpty() || branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ids", receiptIds);
        String branch = "";
        if (branchIds != null) {
            branch = " AND s.branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("""
                        SELECT a.receipt_id, a.sale_id, s.sale_no, s.branch_id, a.amount_minor, a.sale_payment_id
                          FROM retail_account_payment_allocations a JOIN retail_sales s ON s.id = a.sale_id
                         WHERE a.receipt_id IN (:ids)""" + branch + " ORDER BY a.receipt_id, s.sale_date, s.created_at, s.id")
                .params(params)
                .query((rs, n) -> new AllocationRow(
                        rs.getObject("receipt_id", UUID.class),
                        rs.getObject("sale_id", UUID.class),
                        rs.getString("sale_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getLong("amount_minor"),
                        rs.getObject("sale_payment_id", UUID.class)))
                .list();
    }

    /** Every allocation of a receipt, whatever the caller's scope: a reversal undoes the receipt as a whole. */
    List<AllocationRow> allAllocations(UUID receiptId) {
        return allocations(List.of(receiptId), null);
    }

    /** Rows changed: 0 when the receipt was reversed meanwhile. */
    int markReversed(UUID id, UUID by, LocalDate on, Instant at, String reason, UUID reversalEntryId) {
        return jdbc.sql("""
                        UPDATE retail_account_payments SET status = 'reversed', reversed_at = ?, reversed_on = ?, reversed_by = ?,
                               reverse_reason = ?, reversal_entry_id = ?, updated_at = now()
                         WHERE id = ? AND status = 'posted'
                        """)
                .params(Timestamp.from(at), Date.valueOf(on), by, reason, reversalEntryId, id)
                .update();
    }

    // ---- The statement --------------------------------------------------------------------------

    /**
     * Every movement on a buyer's credit sales, as a debit or a credit, in the branches of the sales (null: every branch).
     * A sale and its void are a debit and a credit; a payment, or all of a receipt's allocations in scope, a credit; the
     * reversal of a receipt a debit; the account credit of a return (issue #201) a credit on the day it was returned, and a
     * voided return a debit on the day it was voided (a return that paid money out only is not listed: the ledger's trade
     * debtors do not change for money that went back to a buyer who had paid, and the statement is of what is owed). A customer
     * order's delivery fee left on the account (issue #385) is a debit on the day of delivery, a credit when it is paid, and a
     * credit on the day its sale is voided (the fee is reversed with the sale). Imported sales (historical) have no debit here, because the ledger never held one;
     * a payment that was recorded against one is still a credit, because the ledger did hold that.
     */
    private static String movements(boolean scoped) {
        String sb = scoped ? " AND s.branch_id IN (:branchIds)" : "";
        return """
                SELECT s.sale_date AS d, 1 AS ord, s.created_at AS at, s.id AS key, 'sale' AS kind, s.sale_no AS ref,
                       s.id AS sale_id, CAST(NULL AS uuid) AS receipt_id, CAST(NULL AS text) AS method,
                       s.total_minor AS debit, CAST(0 AS bigint) AS credit
                  FROM retail_sales s
                 WHERE s.customer_id = :customer AND s.payment_method = 'credit' AND NOT s.historical""" + sb + """

                UNION ALL
                SELECT p.paid_on, 2, min(p.created_at), coalesce(r.id, p.id),
                       CASE WHEN r.id IS NULL THEN 'payment' ELSE 'account_payment' END,
                       coalesce(r.receipt_no, min(s.sale_no)),
                       CASE WHEN r.id IS NULL THEN CAST(min(CAST(s.id AS text)) AS uuid) END, r.id, p.method, CAST(0 AS bigint), sum(p.amount_minor)
                  FROM retail_sale_payments p
                  JOIN retail_sales s ON s.id = p.sale_id
                  LEFT JOIN retail_account_payment_allocations a ON a.sale_payment_id = p.id
                  LEFT JOIN retail_account_payments r ON r.id = a.receipt_id
                 WHERE s.customer_id = :customer AND s.payment_method = 'credit'""" + sb + """

                 GROUP BY p.paid_on, coalesce(r.id, p.id), r.id, r.receipt_no, p.method
                UNION ALL
                SELECT r.reversed_on, 3, r.reversed_at, r.id, 'reversal', r.receipt_no, CAST(NULL AS uuid), r.id, r.method,
                       sum(a.amount_minor), CAST(0 AS bigint)
                  FROM retail_account_payments r
                  JOIN retail_account_payment_allocations a ON a.receipt_id = r.id
                  JOIN retail_sales s ON s.id = a.sale_id
                 WHERE r.customer_id = :customer AND r.status = 'reversed'""" + sb + """

                 GROUP BY r.id, r.reversed_on, r.reversed_at, r.receipt_no, r.method
                UNION ALL
                SELECT CAST((s.voided_at AT TIME ZONE :zone) AS date), 4, s.voided_at, s.id, 'void', s.sale_no, s.id,
                       CAST(NULL AS uuid), CAST(NULL AS text), CAST(0 AS bigint), s.total_minor
                  FROM retail_sales s
                 WHERE s.customer_id = :customer AND s.payment_method = 'credit' AND s.status = 'voided'
                   AND NOT s.historical""" + sb + """

                UNION ALL
                SELECT r.returned_on, 5, r.created_at, r.id, 'return', r.credit_note_no, s.id, CAST(NULL AS uuid),
                       CAST(NULL AS text), CAST(0 AS bigint), r.account_credit_minor
                  FROM retail_sale_returns r JOIN retail_sales s ON s.id = r.sale_id
                 WHERE s.customer_id = :customer AND s.payment_method = 'credit' AND NOT s.historical
                   AND r.account_credit_minor > 0""" + sb + """

                UNION ALL
                SELECT r.voided_on, 6, r.voided_at, r.id, 'return_void', r.credit_note_no, s.id, CAST(NULL AS uuid),
                       CAST(NULL AS text), r.account_credit_minor, CAST(0 AS bigint)
                  FROM retail_sale_returns r JOIN retail_sales s ON s.id = r.sale_id
                 WHERE s.customer_id = :customer AND s.payment_method = 'credit' AND NOT s.historical
                   AND r.status = 'voided' AND r.account_credit_minor > 0""" + sb + """

                UNION ALL
                SELECT w.written_off_on, 7, w.created_at, w.id, 'write_off', s.sale_no, s.id, CAST(NULL AS uuid),
                       CAST(NULL AS text), CAST(0 AS bigint), w.unpaid_minor
                  FROM retail_sale_writeoffs w JOIN retail_sales s ON s.id = w.sale_id
                 WHERE s.customer_id = :customer AND s.payment_method = 'credit' AND NOT s.historical""" + sb + """

                UNION ALL
                SELECT o.delivered_on, 8, o.delivered_at, o.id, 'delivery_fee', o.order_no, s.id, CAST(NULL AS uuid),
                       CAST(NULL AS text), o.delivery_fee_minor, CAST(0 AS bigint)
                  FROM retail_customer_orders o JOIN retail_sales s ON s.id = o.sale_id
                 WHERE s.customer_id = :customer AND o.fee_method = 'account'""" + sb + """

                UNION ALL
                SELECT o.fee_paid_on, 9, o.fee_paid_at, o.id, 'delivery_fee_paid', o.order_no, s.id, CAST(NULL AS uuid),
                       o.fee_paid_method, CAST(0 AS bigint), o.delivery_fee_minor
                  FROM retail_customer_orders o JOIN retail_sales s ON s.id = o.sale_id
                 WHERE s.customer_id = :customer AND o.fee_method = 'account' AND o.fee_paid_at IS NOT NULL
                   AND o.fee_paid_method <> 'written_off'""" + sb + """

                UNION ALL
                SELECT CAST((s.voided_at AT TIME ZONE :zone) AS date), 10, s.voided_at, o.id, 'delivery_fee_void', o.order_no,
                       s.id, CAST(NULL AS uuid), CAST(NULL AS text), CAST(0 AS bigint), o.delivery_fee_minor
                  FROM retail_customer_orders o JOIN retail_sales s ON s.id = o.sale_id
                 WHERE s.customer_id = :customer AND o.fee_method = 'account' AND o.fee_paid_at IS NULL
                   AND s.status = 'voided'"""
                + sb + "\n";
    }

    private static Map<String, Object> statementParams(UUID customerId, List<UUID> branchIds, String zone) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customer", customerId);
        params.put("zone", zone);
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        return params;
    }

    /** What the movements before {@code from} come to (debits less credits). */
    long opening(UUID customerId, List<UUID> branchIds, String zone, LocalDate from) {
        if (branchIds != null && branchIds.isEmpty()) {
            return 0;
        }
        Map<String, Object> params = statementParams(customerId, branchIds, zone);
        params.put("from", Date.valueOf(from));
        return jdbc.sql("SELECT coalesce(sum(debit - credit), 0) FROM (" + movements(branchIds != null)
                        + ") m WHERE m.d < :from")
                .params(params)
                .query(Long.class)
                .single();
    }

    /** The movements from {@code from} to {@code to}, in order; at most {@code limit}. */
    List<Line> lines(UUID customerId, List<UUID> branchIds, String zone, LocalDate from, LocalDate to, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = statementParams(customerId, branchIds, zone);
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        params.put("limit", limit);
        return jdbc.sql("SELECT d, kind, ref, sale_id, receipt_id, method, debit, credit FROM ("
                        + movements(branchIds != null)
                        + ") m WHERE m.d >= :from AND m.d <= :to ORDER BY m.d, m.ord, m.at, m.key LIMIT :limit")
                .params(params)
                .query((rs, n) -> new Line(
                        rs.getDate("d").toLocalDate(),
                        rs.getString("kind"),
                        rs.getString("ref"),
                        rs.getObject("sale_id", UUID.class),
                        rs.getObject("receipt_id", UUID.class),
                        rs.getString("method"),
                        rs.getLong("debit"),
                        rs.getLong("credit")))
                .list();
    }

    /** Imported credit sales that still stand (not voided), dated on or before {@code to}. */
    List<Historical> historical(UUID customerId, List<UUID> branchIds, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customer", customerId);
        params.put("to", Date.valueOf(to));
        String branch = "";
        if (branchIds != null) {
            branch = " AND branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("""
                        SELECT id, sale_no, branch_id, sale_date, total_minor FROM retail_sales
                         WHERE customer_id = :customer AND payment_method = 'credit' AND historical
                           AND status = 'completed' AND sale_date <= :to""" + branch + " ORDER BY sale_date, created_at, id")
                .params(params)
                .query((rs, n) -> new Historical(
                        rs.getObject("id", UUID.class),
                        rs.getString("sale_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("sale_date").toLocalDate(),
                        rs.getLong("total_minor")))
                .list();
    }
}
