package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.retail.purchasing.CashPurchases;
import com.rincoltech.bms.retail.stock.Quantities;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcCashPurchases implements CashPurchases {

    private final JdbcClient jdbc;

    JdbcCashPurchases(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<LocalDate, Long> byDay(UUID branchId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> out = new TreeMap<>();
        // One movement per branch and line carries the branch's quantity and the line's cost; each is
        // valued and rounded half up as the purchase's journal entry values it.
        jdbc.sql("""
                        SELECT p.purchased_on AS day, m.qty, m.unit_cost_minor
                          FROM retail_stock_movements m
                          JOIN retail_purchases p ON p.id = m.source_id
                         WHERE m.branch_id = :branch AND m.kind = 'purchase' AND m.source_type = 'retail.purchase'
                           AND p.payment_method = 'cash' AND p.purchased_on BETWEEN :from AND :to
                        """)
                .param("branch", branchId)
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, n) -> {
                    out.merge(
                            rs.getDate("day").toLocalDate(),
                            Quantities.value(rs.getBigDecimal("qty"), rs.getLong("unit_cost_minor")),
                            Math::addExact);
                    return 0;
                })
                .list();
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Set<LocalDate> historicalDays(UUID branchId, LocalDate from, LocalDate to) {
        Set<LocalDate> out = new TreeSet<>();
        jdbc.sql("""
                        SELECT DISTINCT p.purchased_on AS day
                          FROM retail_stock_movements m
                          JOIN retail_purchases p ON p.id = m.source_id
                         WHERE m.branch_id = :branch AND m.kind = 'purchase' AND m.source_type = 'retail.purchase'
                           AND p.payment_method = 'cash' AND p.historical
                           AND p.purchased_on BETWEEN :from AND :to
                        """)
                .param("branch", branchId)
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, n) -> out.add(rs.getDate("day").toLocalDate()))
                .list();
        return out;
    }
}
