package com.rincoltech.bms.retail.reports.internal;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL of the credit control report (issue #149). The open-debt predicate is the one of
 * {@code GET /retail/sales?owing=owing} (a completed credit sale whose total is above its paid amount;
 * overdue adds a due date before today). That predicate bounds the open debts, which a date range
 * would hide; the branch scope and a row limit bound the rest. Payments are bounded by their date.
 */
@Repository
class CreditRepository {

    private final JdbcClient jdbc;

    CreditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Issue #385: the delivery fee of a customer order left on the buyer's account and not yet paid is owed on the sale it came
     * with, so it is part of what the sale still owes and keeps the sale open.
     */
    private static final String FEE = "coalesce((SELECT o.delivery_fee_minor FROM retail_customer_orders o"
            + " WHERE o.sale_id = s.id AND o.fee_method = 'account' AND o.fee_paid_at IS NULL), 0)";

    private static final String OWED = "(s.total_minor - s.paid_minor + " + FEE + ")";
    private static final String DAYS = "(CAST(:today AS date) - s.due_date)";

    private static final String OPEN =
            " WHERE s.payment_method = 'credit' AND s.status = 'completed' AND (s.total_minor > s.paid_minor OR " + FEE
                    + " > 0)";

    /** The five ageing sums as SELECT items; a sale with no due date is not yet due. */
    private static final String BUCKETS = "sum(" + OWED + ") AS owed,"
            + " sum(CASE WHEN s.due_date IS NULL OR " + DAYS + " <= 0 THEN " + OWED + " ELSE 0 END) AS not_due,"
            + " sum(CASE WHEN " + DAYS + " BETWEEN 1 AND 30 THEN " + OWED + " ELSE 0 END) AS d30,"
            + " sum(CASE WHEN " + DAYS + " BETWEEN 31 AND 60 THEN " + OWED + " ELSE 0 END) AS d60,"
            + " sum(CASE WHEN " + DAYS + " BETWEEN 61 AND 90 THEN " + OWED + " ELSE 0 END) AS d90,"
            + " sum(CASE WHEN " + DAYS + " > 90 THEN " + OWED + " ELSE 0 END) AS over90";

    record Buckets(long owed, long notDue, long d30, long d60, long d90, long over90) {}

    record BuyerRow(
            UUID customerId,
            String name,
            int sales,
            Buckets buckets,
            Integer oldestDays,
            int buyers,
            Long creditLimitMinor,
            Integer termsDays) {}

    record OverdueRow(
            UUID saleId,
            String saleNo,
            UUID branchId,
            UUID customerId,
            String buyerName,
            LocalDate saleDate,
            LocalDate dueDate,
            int daysOverdue,
            long total,
            long outstanding,
            int count) {}

    record Payment(LocalDate date, String method, int count, long amount) {}

    private static Map<String, Object> params(List<UUID> branchIds, LocalDate today) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("today", Date.valueOf(today));
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        return params;
    }

    private static String branch(List<UUID> branchIds) {
        return branchIds == null ? "" : " AND s.branch_id IN (:branchIds)";
    }

    private static Buckets buckets(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Buckets(
                rs.getLong("owed"),
                rs.getLong("not_due"),
                rs.getLong("d30"),
                rs.getLong("d60"),
                rs.getLong("d90"),
                rs.getLong("over90"));
    }

    /** Everything owed in scope as one row, and how many buyers owe it. */
    BuyerRow totals(List<UUID> branchIds, LocalDate today) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new BuyerRow(null, null, 0, new Buckets(0, 0, 0, 0, 0, 0), null, 0, null, null);
        }
        return jdbc.sql("SELECT " + BUCKETS + ", count(*) AS sales, count(DISTINCT " + key() + ") AS buyers"
                        + " FROM retail_sales s" + OPEN + branch(branchIds))
                .params(params(branchIds, today))
                .query((rs, n) -> new BuyerRow(
                        null, null, rs.getInt("sales"), buckets(rs), null, rs.getInt("buyers"), null, null))
                .single();
    }

    /** A credit buyer is their record, or the name typed in with the sale (ignoring case). */
    private static String key() {
        return "coalesce(s.customer_id::text, 'n:' || lower(btrim(s.buyer_name)))";
    }

    List<BuyerRow> buyers(List<UUID> branchIds, LocalDate today, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds, today);
        params.put("limit", limit);
        return jdbc.sql("SELECT max(s.customer_id::text)::uuid AS customer_id,"
                        + " coalesce(max(c.name), min(s.buyer_name)) AS name, count(*) AS sales, " + BUCKETS + ","
                        + " max(CASE WHEN " + DAYS + " > 0 THEN " + DAYS + " END) AS oldest,"
                        + " max(c.credit_limit_minor) AS credit_limit, max(c.terms_days) AS terms_days"
                        + " FROM retail_sales s LEFT JOIN retail_customers c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id"
                        + OPEN + branch(branchIds) + " GROUP BY " + key()
                        + " ORDER BY sum(" + OWED + ") DESC, 2, 1 LIMIT :limit")
                .params(params)
                .query((rs, n) -> new BuyerRow(
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("name"),
                        rs.getInt("sales"),
                        buckets(rs),
                        (Integer) rs.getObject("oldest"),
                        0,
                        rs.getObject("credit_limit", Long.class),
                        rs.getObject("terms_days", Integer.class)))
                .list();
    }

    /** What the credit buyers with a record owe, as one row (the total row of the customer ageing). */
    BuyerRow customerTotals(List<UUID> branchIds, LocalDate today) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new BuyerRow(null, null, 0, new Buckets(0, 0, 0, 0, 0, 0), null, 0, null, null);
        }
        return jdbc.sql("SELECT " + BUCKETS + ", count(*) AS sales, count(DISTINCT s.customer_id) AS buyers"
                        + " FROM retail_sales s" + OPEN + " AND s.customer_id IS NOT NULL" + branch(branchIds))
                .params(params(branchIds, today))
                .query((rs, n) -> new BuyerRow(
                        null, null, rs.getInt("sales"), buckets(rs), null, rs.getInt("buyers"), null, null))
                .single();
    }

    /** The credit buyers with a record that owe, the most first: owed, ageing, limit and terms (customer ageing). */
    List<BuyerRow> customers(List<UUID> branchIds, LocalDate today, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds, today);
        params.put("limit", limit);
        return jdbc.sql("SELECT s.customer_id, max(c.name) AS name, count(*) AS sales, " + BUCKETS + ","
                        + " max(CASE WHEN " + DAYS + " > 0 THEN " + DAYS + " END) AS oldest,"
                        + " max(c.credit_limit_minor) AS credit_limit, max(c.terms_days) AS terms_days"
                        + " FROM retail_sales s JOIN retail_customers c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id"
                        + OPEN + branch(branchIds) + " GROUP BY s.customer_id"
                        + " ORDER BY sum(" + OWED + ") DESC, 2, 1 LIMIT :limit")
                .params(params)
                .query((rs, n) -> new BuyerRow(
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("name"),
                        rs.getInt("sales"),
                        buckets(rs),
                        (Integer) rs.getObject("oldest"),
                        0,
                        rs.getObject("credit_limit", Long.class),
                        rs.getObject("terms_days", Integer.class)))
                .list();
    }

    List<OverdueRow> overdue(List<UUID> branchIds, LocalDate today, boolean byAge, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = params(branchIds, today);
        params.put("limit", limit);
        String order = byAge ? "s.due_date, " + OWED + " DESC, s.id" : OWED + " DESC, s.due_date, s.id";
        return jdbc.sql("SELECT s.id, s.sale_no, s.branch_id, s.customer_id, coalesce(c.name, s.buyer_name) AS buyer,"
                        + " s.sale_date, s.due_date, " + DAYS + " AS days, s.total_minor, " + OWED + " AS outstanding,"
                        + " count(*) OVER () AS total"
                        + " FROM retail_sales s LEFT JOIN retail_customers c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id"
                        + OPEN + " AND s.due_date < CAST(:today AS date)" + branch(branchIds)
                        + " ORDER BY " + order + " LIMIT :limit")
                .params(params)
                .query((rs, n) -> new OverdueRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("sale_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("buyer"),
                        rs.getDate("sale_date").toLocalDate(),
                        rs.getDate("due_date").toLocalDate(),
                        rs.getInt("days"),
                        rs.getLong("total_minor"),
                        rs.getLong("outstanding"),
                        rs.getInt("total")))
                .list();
    }

    /**
     * Payments on credit sales in the range, by day and method. A payment on account (issue #196) counts in the branch that
     * received it, as the ledger and the cash book post it; any other payment in the branch of its sale. A reversed payment on
     * account is not removed from the day it was received, so a day already reported never changes: it stays on that day and
     * the reversal is its own negative row on the day it was reversed, as in the cash book. A day and method where the two
     * cancel is left out.
     */
    List<Payment> payments(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        String receivedIn = branchIds == null ? "" : " AND coalesce(ap.branch_id, s.branch_id) IN (:branchIds)";
        String reversedIn = branchIds == null ? "" : " AND ap.branch_id IN (:branchIds)";
        return jdbc.sql("SELECT m.d AS paid_on, m.method, sum(m.n) AS payments, sum(m.amt) AS amount FROM ("
                        + " SELECT p.paid_on AS d, p.method, 1 AS n, p.amount_minor AS amt"
                        + " FROM retail_sale_payments p JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id"
                        + " LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id AND al.sale_payment_id = p.id"
                        + " LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id"
                        + " WHERE p.paid_on BETWEEN :from AND :to AND s.status = 'completed'" + receivedIn
                        + " UNION ALL"
                        + " SELECT ap.reversed_on, p.method, -1, -p.amount_minor"
                        + " FROM retail_account_payments ap"
                        + " JOIN retail_account_payment_allocations al ON al.tenant_id = ap.tenant_id AND al.receipt_id = ap.id"
                        + " JOIN retail_sale_payments p ON p.tenant_id = al.tenant_id AND p.id = al.sale_payment_id"
                        + " JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id"
                        + " WHERE ap.status = 'reversed' AND ap.reversed_on BETWEEN :from AND :to AND s.status = 'completed'"
                        + reversedIn
                        + ") m GROUP BY m.d, m.method HAVING sum(m.n) <> 0 OR sum(m.amt) <> 0 ORDER BY m.d, m.method")
                .params(params)
                .query((rs, n) -> new Payment(
                        rs.getDate("paid_on").toLocalDate(),
                        rs.getString("method"),
                        rs.getInt("payments"),
                        rs.getLong("amount")))
                .list();
    }
}
