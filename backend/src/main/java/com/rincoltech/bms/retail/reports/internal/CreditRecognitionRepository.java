package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.retail.stock.CreditPeriods;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for credit sales counted when the buyer pays (ADR-043): the events that move the recognised profit of a credit
 * sale, the losses of debts written off, and what buyers still owe. Row-level security supplies the tenant; the branch filter
 * comes from the caller's scope.
 */
@Repository
class CreditRecognitionRepository {

    private final JdbcClient jdbc;
    private final CurrentTenant tenant;

    CreditRecognitionRepository(JdbcClient jdbc, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    /**
     * Every credit sale (completed, not imported) counted when the buyer pays ({@code periods}: the later of its date and the day it
     * was entered is on or after the start day) that had a payment, the reversal of
     * one or a return inside the window, with all its events up to {@code to}, grouped by day and branch.
     *
     * <p>A payment sits on the day it was RECORDED (the tenant's day of its creation), not on the date written on it, so a
     * payment dated last Monday and entered on Wednesday is Wednesday's money and no day already reported is ever rewritten. It
     * belongs to the branch that took it: the receiving branch of a payment on account, else the sale's. A reversal sits on the
     * day it was reversed, at the receiving branch; a return on its own day, at the branch the refund left from when money was
     * paid out (that till lost the cash, so it takes back the profit); a return credited to the buyer's balance (no cash out) is
     * spread by {@link CreditRecognition#deltas} over the branches that took the sale's payments. A voided return drops out.
     * One row per event, in the order they were entered within a day (the moment, then the id), never in the order of branch ids,
     * so the figures of a till or a shop do not depend on random ids.
     * The sales to read are picked first, from the payments of the window (a range of moments on an indexed column) and the
     * reversals and returns of its days, then only their events up to the end of the window are read.
     */
    List<CreditRecognition.Event> events(List<UUID> branchIds, LocalDate from, LocalDate to, CreditPeriods periods) {
        if ((branchIds != null && branchIds.isEmpty()) || periods.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        java.time.ZoneId zone = tenant.profile().timezone();
        params.put("zone", zone.getId());
        params.put("fromTs", java.sql.Timestamp.from(from.atStartOfDay(zone).toInstant()));
        params.put(
                "toTs",
                java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
        String inPeriod = periods.sql("s.sale_date", "s.created_at", params, "crpE");
        String paidDay = "CAST((p.created_at AT TIME ZONE :zone) AS date)";
        // A branch scope picks the sales to read from the events of the window that the scope reports (the branch that
        // took a
        // payment, that was reversed, that paid a refund out); every event of a picked sale is still read, since the
        // running
        // figures of a sale need them all. A return with no cash out is spread over the branches that took the sale's
        // money,
        // so it is picked whatever its own branch.
        String payScope = "";
        String revScope = "";
        String retScope = "";
        if (branchIds != null) {
            params.put("scopeBranches", branchIds);
            payScope = " AND coalesce(ap0.branch_id, s0.branch_id) IN (:scopeBranches)";
            revScope = " AND ap.branch_id IN (:scopeBranches)";
            retScope = " AND (r.refund_minor = 0 OR r.refund_branch_id IN (:scopeBranches))";
        }
        return jdbc.sql("WITH active AS ("
                        + " SELECT p.sale_id FROM retail_sale_payments p"
                        + " JOIN retail_sales s0 ON s0.tenant_id = p.tenant_id AND s0.id = p.sale_id"
                        + " LEFT JOIN retail_account_payment_allocations al0 ON al0.tenant_id = p.tenant_id AND al0.sale_payment_id = p.id"
                        + " LEFT JOIN retail_account_payments ap0 ON ap0.tenant_id = al0.tenant_id AND ap0.id = al0.receipt_id"
                        + " WHERE p.created_at >= :fromTs AND p.created_at < :toTs" + payScope
                        + " UNION SELECT al.sale_id FROM retail_account_payments ap"
                        + " JOIN retail_account_payment_allocations al ON al.tenant_id = ap.tenant_id AND al.receipt_id = ap.id"
                        + " WHERE ap.status = 'reversed' AND ap.reversed_on BETWEEN :from AND :to" + revScope
                        + " UNION SELECT r.sale_id FROM retail_sale_returns r"
                        + " WHERE r.status = 'completed' AND r.returned_on BETWEEN :from AND :to" + retScope
                        + "), e AS ("
                        + " SELECT p.sale_id, " + paidDay + " AS d, coalesce(ap.branch_id, s1.branch_id) AS br,"
                        + " p.created_at AS ev_at, p.id AS eid,"
                        + " p.amount_minor AS pay, CAST(0 AS bigint) AS rv, CAST(0 AS bigint) AS rc, CAST(0 AS bigint) AS rf"
                        + " FROM retail_sale_payments p"
                        + " JOIN retail_sales s1 ON s1.tenant_id = p.tenant_id AND s1.id = p.sale_id"
                        + " LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id AND al.sale_payment_id = p.id"
                        + " LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id"
                        + " WHERE p.sale_id IN (SELECT sale_id FROM active) AND p.created_at < :toTs"
                        + " UNION ALL"
                        + " SELECT al.sale_id, ap.reversed_on, ap.branch_id, ap.reversed_at, al.id,"
                        + " -al.amount_minor, CAST(0 AS bigint), CAST(0 AS bigint), CAST(0 AS bigint)"
                        + " FROM retail_account_payments ap"
                        + " JOIN retail_account_payment_allocations al ON al.tenant_id = ap.tenant_id AND al.receipt_id = ap.id"
                        + " WHERE ap.status = 'reversed' AND al.sale_id IN (SELECT sale_id FROM active) AND ap.reversed_on <= :to"
                        + " UNION ALL"
                        + " SELECT r.sale_id, r.returned_on, CASE WHEN r.refund_minor > 0 THEN r.refund_branch_id ELSE r.branch_id END,"
                        + " r.created_at, r.id,"
                        + " CAST(0 AS bigint), r.total_minor, r.cost_minor, r.refund_minor"
                        + " FROM retail_sale_returns r"
                        + " WHERE r.status = 'completed' AND r.sale_id IN (SELECT sale_id FROM active) AND r.returned_on <= :to"
                        + ")"
                        + " SELECT s.id AS sale_id, e.br AS branch_id, s.total_minor, s.cost_total_minor, e.d,"
                        + " e.pay AS pay, e.rv AS rv, e.rc AS rc, e.rf AS rf"
                        + " FROM e JOIN retail_sales s ON s.id = e.sale_id"
                        + " WHERE s.payment_method = 'credit' AND s.status = 'completed' AND NOT s.historical AND "
                        + inPeriod
                        + " ORDER BY s.id, e.d, e.ev_at, e.eid")
                .params(params)
                .query((rs, n) -> new CreditRecognition.Event(
                        rs.getObject("sale_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getLong("total_minor"),
                        rs.getLong("cost_total_minor"),
                        rs.getDate("d").toLocalDate(),
                        rs.getLong("pay"),
                        rs.getLong("rv"),
                        rs.getLong("rc"),
                        rs.getLong("rf")))
                .list();
    }

    /**
     * The loss of credit sales written off in the window, by the sale's branch and the day of the write-off (ADR-043), as report
     * figures with only the bad debt set. The loss was fixed when the debt was written off, by the way the sale itself was counted
     * (its counting day against the start day of the rule): the cost never carried into the profit for a sale counted when the buyer pays,
     * the whole unpaid amount for one counted when it was made. A written-off sale stays out of On credit because its paid amount
     * holds the debt that was cleared.
     */
    List<ReportsRepository.DayFigures> badDebt(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND w.branch_id IN (:branchIds)";
        }
        // Issue #385: a delivery fee written off is not a loss on the goods sold; the profit of goods never held the
        // fee, so it is
        // left out here (the fee's own income and loss are in the ledger, 4120 and bad debt loss).
        return jdbc.sql("SELECT w.branch_id, w.written_off_on AS day, sum(w.loss_minor - w.fee_minor) AS loss"
                        + "  FROM retail_sale_writeoffs w"
                        + " WHERE w.written_off_on BETWEEN :from AND :to" + branch
                        + " GROUP BY w.branch_id, w.written_off_on")
                .params(params)
                .query((rs, n) -> new ReportsRepository.DayFigures(
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("day").toLocalDate(),
                        0,
                        0,
                        0,
                        rs.getLong("loss")))
                .list();
    }

    /**
     * What buyers still owe on the credit sales counted when the buyer pays ({@code periods}; completed, not imported): total less
     * paid, where the paid amount already holds the credit given on returns. Returns {amount, number of sales}. Credit sales from
     * before the rule started were counted at the sale, so they are not listed here as uncounted, and imported sales were settled
     * outside the system.
     */
    long[] onCredit(List<UUID> branchIds, CreditPeriods periods) {
        if ((branchIds != null && branchIds.isEmpty()) || periods.isEmpty()) {
            return new long[] {0, 0};
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND s.branch_id IN (:branchIds)";
        }
        // Issue #385: a delivery fee left on the buyer's account and not yet paid is owed with the sale.
        String fee = "coalesce((SELECT o.delivery_fee_minor FROM retail_customer_orders o WHERE o.sale_id = s.id"
                + " AND o.fee_method = 'account' AND o.fee_paid_at IS NULL), 0)";
        return jdbc.sql("SELECT coalesce(sum(s.total_minor - s.paid_minor + " + fee + "), 0) AS owed, count(*) AS n"
                        + "  FROM retail_sales s"
                        + " WHERE s.payment_method = 'credit' AND s.status = 'completed' AND NOT s.historical"
                        + "   AND (s.total_minor > s.paid_minor OR " + fee + " > 0) AND "
                        + periods.sql("s.sale_date", "s.created_at", params, "crpO") + branch)
                .params(params)
                .query((rs, n) -> new long[] {rs.getLong("owed"), rs.getLong("n")})
                .single();
    }

    /**
     * Whether a credit sale dated inside the window counts when the buyer pays ({@code periods}): a backdated sale entered after the
     * start is dated before it, yet a report of those days must show it.
     */
    boolean countedSaleDatedIn(List<UUID> branchIds, LocalDate from, LocalDate to, CreditPeriods periods) {
        if ((branchIds != null && branchIds.isEmpty()) || periods.isEmpty()) {
            return false;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND s.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_sales s"
                        + " WHERE s.payment_method = 'credit' AND s.status = 'completed' AND NOT s.historical"
                        + "   AND s.sale_date BETWEEN :from AND :to AND "
                        + periods.sql("s.sale_date", "s.created_at", params, "crpC") + branch + ")")
                .params(params)
                .query(Boolean.class)
                .single();
    }

    /**
     * The money recorded as collected, apart from sales paid at the sale (ADR-043), by the day it was entered and the branch that took
     * it, by any method (cash, mobile money, bank): every payment on a credit sale, whatever the sale's date and whether or not it counts
     * when the buyer pays, imported sales included; less a payment on account when it is reversed, on the day of the reversal; less the
     * money paid back on any return, cash sale or credit sale, on the day of the return and at the refund branch, and given back on the
     * day a return is voided. A payment dated earlier than it was entered counts on the day it was entered, so a day already reported is
     * not rewritten. Voiding a cash sale does rewrite that sale's own day, as it always did (the sale leaves its day). A delivery fee
     * left on a buyer's account and paid later (issue #385) counts on the day it was paid.
     */
    List<ReportsRepository.DayFigures> collections(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        java.time.ZoneId zone = tenant.profile().timezone();
        params.put("zone", zone.getId());
        params.put("fromTs", java.sql.Timestamp.from(from.atStartOfDay(zone).toInstant()));
        params.put(
                "toTs",
                java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " WHERE x.br IN (:branchIds)";
        }
        return jdbc.sql("SELECT x.br AS branch_id, x.d AS day, sum(x.amt) AS amount FROM ("
                        + " SELECT coalesce(ap.branch_id, s.branch_id) AS br, CAST((p.created_at AT TIME ZONE :zone) AS date) AS d,"
                        + " p.amount_minor AS amt"
                        + " FROM retail_sale_payments p"
                        + " JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id"
                        + " LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id AND al.sale_payment_id = p.id"
                        + " LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id"
                        + " WHERE s.payment_method = 'credit' AND s.status = 'completed'"
                        + " AND p.created_at >= :fromTs AND p.created_at < :toTs"
                        + " UNION ALL"
                        + " SELECT ap.branch_id, ap.reversed_on, -al.amount_minor"
                        + " FROM retail_account_payments ap"
                        + " JOIN retail_account_payment_allocations al ON al.tenant_id = ap.tenant_id AND al.receipt_id = ap.id"
                        + " JOIN retail_sales s ON s.tenant_id = al.tenant_id AND s.id = al.sale_id"
                        + " WHERE ap.status = 'reversed' AND ap.reversed_on BETWEEN :from AND :to"
                        + " AND s.payment_method = 'credit' AND s.status = 'completed'"
                        + " UNION ALL"
                        + " SELECT r.refund_branch_id, r.returned_on, -r.refund_minor"
                        + " FROM retail_sale_returns r"
                        + " WHERE r.status IN ('completed', 'voided') AND r.refund_minor > 0"
                        + " AND r.returned_on BETWEEN :from AND :to"
                        + " UNION ALL"
                        + " SELECT r.refund_branch_id, r.voided_on, r.refund_minor"
                        + " FROM retail_sale_returns r"
                        + " WHERE r.status = 'voided' AND r.refund_minor > 0 AND r.voided_on BETWEEN :from AND :to"
                        // Issue #385: a delivery fee left on a buyer's account and paid later, on the day it was
                        // entered and at
                        // the order's branch, as the cash book counts it (a fee written off is no money received).
                        + " UNION ALL"
                        + " SELECT o.branch_id, CAST((o.fee_paid_at AT TIME ZONE :zone) AS date), o.delivery_fee_minor"
                        + " FROM retail_customer_orders o"
                        + " WHERE o.fee_method = 'account' AND o.fee_paid_method IN ('cash', 'mobile_money', 'bank')"
                        + " AND o.fee_paid_at >= :fromTs AND o.fee_paid_at < :toTs"
                        + ") x" + branch + " GROUP BY x.br, x.d")
                .params(params)
                .query((rs, n) -> new ReportsRepository.DayFigures(
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("day").toLocalDate(),
                        rs.getLong("amount"),
                        0,
                        0))
                .list();
    }

    /**
     * The sales paid at the sale (not credit), by the day they were entered (the tenant's day of {@code created_at}) and the branch of
     * the sale: the part of Received that is not a payment on a credit sale (ADR-043). Received is money recorded as collected on the
     * day it was entered, so a cash sale dated in the past counts on the day it was entered, like a payment dated earlier. A cash sale
     * that is cancelled (voided) is not here at all: cancelling it removes it from its own day, the one exception to "a day already
     * reported is never rewritten".
     */
    List<ReportsRepository.DayFigures> cashSalesEntered(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        java.time.ZoneId zone = tenant.profile().timezone();
        params.put("zone", zone.getId());
        params.put("fromTs", java.sql.Timestamp.from(from.atStartOfDay(zone).toInstant()));
        params.put(
                "toTs",
                java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND s.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT s.branch_id, CAST((s.created_at AT TIME ZONE :zone) AS date) AS day,"
                        + " sum(l.line_total_minor) AS amount"
                        + " FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id"
                        + " WHERE s.status = 'completed' AND s.payment_method <> 'credit'"
                        + " AND s.created_at >= :fromTs AND s.created_at < :toTs" + branch
                        + " GROUP BY 1, 2")
                .params(params)
                .query((rs, n) -> new ReportsRepository.DayFigures(
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("day").toLocalDate(),
                        rs.getLong("amount"),
                        0,
                        0))
                .list();
    }
}
