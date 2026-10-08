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
        // Cash payments on credit sales by the day they were received.
        jdbc.sql("""
                        SELECT p.paid_on AS day, sum(p.amount_minor) AS cash, bool_or(p.historical) AS hist
                          FROM retail_sale_payments p JOIN retail_sales s ON s.id = p.sale_id
                         WHERE s.branch_id = :branch AND p.method = 'cash' AND p.paid_on BETWEEN :from AND :to
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
        Map<LocalDate, Day> out = new TreeMap<>();
        acc.forEach((d, a) -> out.put(d, new Day(a.takings(), a.voids(), a.sold(), a.historical())));
        return out;
    }
}
