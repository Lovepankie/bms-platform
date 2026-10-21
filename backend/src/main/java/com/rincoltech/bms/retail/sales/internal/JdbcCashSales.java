package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.retail.sales.CashSales;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcCashSales implements CashSales {

    private final JdbcClient jdbc;

    JdbcCashSales(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Acc(long takings, long voids, long sold, boolean historical) {

        Acc plus(long t, long v, long s, boolean h) {
            return new Acc(takings + t, voids + v, sold + s, historical || h);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<LocalDate, Day> days(UUID branchId, LocalDate from, LocalDate to, ZoneId zone) {
        Map<LocalDate, Acc> acc = new TreeMap<>();
        // Cash sales by sale date, voided later or not; and the completed total of every method.
        jdbc.sql("""
                        SELECT sale_date AS day,
                               coalesce(sum(total_minor) FILTER (WHERE payment_method = 'cash'), 0) AS cash,
                               coalesce(sum(total_minor) FILTER (WHERE status = 'completed'), 0) AS sold,
                               coalesce(bool_or(historical), false) AS hist
                          FROM retail_sales
                         WHERE branch_id = :branch AND sale_date BETWEEN :from AND :to
                         GROUP BY sale_date
                        """)
                .param("branch", branchId)
                .param("from", java.sql.Date.valueOf(from))
                .param("to", java.sql.Date.valueOf(to))
                .query((rs, n) -> {
                    acc.merge(
                            rs.getDate("day").toLocalDate(),
                            new Acc(rs.getLong("cash"), 0, rs.getLong("sold"), rs.getBoolean("hist")),
                            (a, b) -> a.plus(b.takings(), b.voids(), b.sold(), b.historical()));
                    return 0;
                })
                .list();
        // Cash payments on credit sales by the day they were received. A payment on account (issue #196) was received
        // in
        // the branch named on its receipt, which may not be the branch of the sale it paid; any other payment is the
        // sale's branch's.
        jdbc.sql("""
                        SELECT p.paid_on AS day, sum(p.amount_minor) AS cash, bool_or(p.historical) AS hist
                          FROM retail_sale_payments p JOIN retail_sales s ON s.id = p.sale_id
                          LEFT JOIN retail_account_payment_allocations a ON a.sale_payment_id = p.id
                          LEFT JOIN retail_account_payments r ON r.id = a.receipt_id
                         WHERE coalesce(r.branch_id, s.branch_id) = :branch AND p.method = 'cash'
                           AND p.paid_on BETWEEN :from AND :to
                         GROUP BY p.paid_on
                        """)
                .param("branch", branchId)
                .param("from", java.sql.Date.valueOf(from))
                .param("to", java.sql.Date.valueOf(to))
                .query((rs, n) -> {
                    acc.merge(
                            rs.getDate("day").toLocalDate(),
                            new Acc(rs.getLong("cash"), 0, 0, rs.getBoolean("hist")),
                            (a, b) -> a.plus(b.takings(), b.voids(), b.sold(), b.historical()));
                    return 0;
                })
                .list();
        // Cash sales voided in the window, by the day of the void in the tenant's zone.
        Timestamp start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        jdbc.sql("""
                        SELECT (voided_at AT TIME ZONE :zone)::date AS day, sum(total_minor) AS voided
                          FROM retail_sales
                         WHERE branch_id = :branch AND payment_method = 'cash' AND voided_at >= :start AND voided_at < :end
                         GROUP BY 1
                        """)
                .param("zone", zone.getId())
                .param("branch", branchId)
                .param("start", start)
                .param("end", end)
                .query((rs, n) -> {
                    acc.merge(
                            rs.getDate("day").toLocalDate(),
                            new Acc(0, rs.getLong("voided"), 0, false),
                            (a, b) -> a.plus(b.takings(), b.voids(), b.sold(), b.historical()));
                    return 0;
                })
                .list();
        // Payments on account reversed in the window, by the day of the reversal: like a voided cash sale, the payment
        // stays on the day it was received and the reversal takes the money out on the day it was undone.
        jdbc.sql("""
                        SELECT reversed_on AS day, sum(amount_minor) AS reversed FROM retail_account_payments
                         WHERE branch_id = :branch AND method = 'cash' AND status = 'reversed'
                           AND reversed_on BETWEEN :from AND :to
                         GROUP BY reversed_on
                        """)
                .param("branch", branchId)
                .param("from", java.sql.Date.valueOf(from))
                .param("to", java.sql.Date.valueOf(to))
                .query((rs, n) -> {
                    acc.merge(
                            rs.getDate("day").toLocalDate(),
                            new Acc(0, rs.getLong("reversed"), 0, false),
                            (a, b) -> a.plus(b.takings(), b.voids(), b.sold(), b.historical()));
                    return 0;
                })
                .list();
        // Cash refunded on customer returns (issue #201) leaves the till on the day it was paid, the return's own day
        // and
        // never the sale's: it counts with the day's cash sale voids. A return voided later gives the money back on the
        // day of
        // the void, like a voided cash sale takes it out, so the day it was paid is never rewritten.
        jdbc.sql("""
                        SELECT returned_on AS day, sum(refund_minor) AS refunded FROM retail_sale_returns
                         WHERE refund_branch_id = :branch AND refund_method = 'cash' AND refund_minor > 0
                           AND returned_on BETWEEN :from AND :to
                         GROUP BY returned_on
                        """)
                .param("branch", branchId)
                .param("from", java.sql.Date.valueOf(from))
                .param("to", java.sql.Date.valueOf(to))
                .query((rs, n) -> {
                    acc.merge(
                            rs.getDate("day").toLocalDate(),
                            new Acc(0, rs.getLong("refunded"), 0, false),
                            (a, b) -> a.plus(b.takings(), b.voids(), b.sold(), b.historical()));
                    return 0;
                })
                .list();
        jdbc.sql("""
                        SELECT voided_on AS day, sum(refund_minor) AS returned FROM retail_sale_returns
                         WHERE refund_branch_id = :branch AND refund_method = 'cash' AND refund_minor > 0 AND status = 'voided'
                           AND voided_on BETWEEN :from AND :to
                         GROUP BY voided_on
                        """)
                .param("branch", branchId)
                .param("from", java.sql.Date.valueOf(from))
                .param("to", java.sql.Date.valueOf(to))
                .query((rs, n) -> {
                    acc.merge(
                            rs.getDate("day").toLocalDate(),
                            new Acc(rs.getLong("returned"), 0, 0, false),
                            (a, b) -> a.plus(b.takings(), b.voids(), b.sold(), b.historical()));
                    return 0;
                })
                .list();
        // Delivery fees of customer orders (issue #385, ADR-052) paid in cash at the door come into the till on the day
        // of the
        // delivery, and a fee left on a buyer's account and paid later in cash on the day it was paid.
        jdbc.sql("""
                        SELECT day, sum(fee) AS fees FROM (
                            SELECT delivered_on AS day, delivery_fee_minor AS fee FROM retail_customer_orders
                             WHERE branch_id = :branch AND status = 'delivered' AND fee_method = 'cash' AND fee_entry_id IS NOT NULL
                               AND delivered_on BETWEEN :from AND :to
                            UNION ALL
                            SELECT fee_paid_on, delivery_fee_minor FROM retail_customer_orders
                             WHERE branch_id = :branch AND fee_paid_method = 'cash' AND fee_paid_on BETWEEN :from AND :to) f
                         GROUP BY day
                        """)
                .param("branch", branchId)
                .param("from", java.sql.Date.valueOf(from))
                .param("to", java.sql.Date.valueOf(to))
                .query((rs, n) -> {
                    acc.merge(
                            rs.getDate("day").toLocalDate(),
                            new Acc(rs.getLong("fees"), 0, 0, false),
                            (a, b) -> a.plus(b.takings(), b.voids(), b.sold(), b.historical()));
                    return 0;
                })
                .list();
        // The sale of a delivered order voided: its fee was reversed with it, so a fee taken in cash at the door leaves
        // the
        // till on the day of the void, like the voided cash sale itself; the day of the delivery is never rewritten.
        jdbc.sql("""
                        SELECT (s.voided_at AT TIME ZONE :zone)::date AS day, sum(o.delivery_fee_minor) AS fees
                          FROM retail_customer_orders o JOIN retail_sales s ON s.id = o.sale_id
                         WHERE o.branch_id = :branch AND o.fee_method = 'cash' AND o.fee_entry_id IS NOT NULL
                           AND s.status = 'voided' AND s.voided_at >= :start AND s.voided_at < :end
                         GROUP BY 1
                        """)
                .param("zone", zone.getId())
                .param("branch", branchId)
                .param("start", start)
                .param("end", end)
                .query((rs, n) -> {
                    acc.merge(
                            rs.getDate("day").toLocalDate(),
                            new Acc(0, rs.getLong("fees"), 0, false),
                            (a, b) -> a.plus(b.takings(), b.voids(), b.sold(), b.historical()));
                    return 0;
                })
                .list();
        Map<LocalDate, Day> out = new TreeMap<>();
        acc.forEach((d, a) -> out.put(d, new Day(a.takings(), a.voids(), a.sold(), a.historical())));
        return out;
    }
}
