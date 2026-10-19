package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.Group;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.MethodFigures;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.Methods;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One grouped query per level over {@code retail_sales} (issue #328). It reads the same filters as the sales list
 * ({@link SalesRepository#appendFilters}) and the same per-sale profit (the sale's total less its cost snapshot), and
 * it ranges on {@code sale_date}, which {@code retail_sales_list} and {@code retail_sales_by_date} index. Row-level
 * security supplies the tenant predicate (ADR-003).
 */
@Repository
class SalesSummaryRepository {

    static final Set<String> UNITS = Set.of("year", "month", "day");

    /** A sale's profit, as {@code SalesRepository.header} computes it. */
    private static final String PROFIT = "total_minor - cost_total_minor";

    private static final List<String> METHODS = List.of("cash", "mobile_money", "bank", "credit");

    private final JdbcClient jdbc;

    SalesSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Group> groups(
            String unit,
            boolean withProfit,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            String paymentMethod,
            UUID productId,
            Search.Query buyer,
            String owing,
            LocalDate today,
            int limit) {
        // The unit is one of UNITS (checked by the service), never caller text.
        // sale_date is a date: truncate a timestamp (a date would go through timestamptz and the session zone).
        String start = unit.equals("day") ? "sale_date" : "date_trunc('" + unit + "', sale_date::timestamp)::date";
        StringBuilder sql = new StringBuilder("SELECT " + start + " AS period_start,");
        sql.append(" count(*) FILTER (WHERE status = 'completed') AS sales_count,");
        sql.append(" coalesce(sum(total_minor) FILTER (WHERE status = 'completed'), 0) AS total_minor,");
        sql.append(" count(*) FILTER (WHERE status = 'voided') AS voided_count,");
        for (String method : METHODS) {
            String done = "status = 'completed' AND payment_method = '" + method + "'";
            sql.append(" count(*) FILTER (WHERE ")
                    .append(done)
                    .append(") AS ")
                    .append(method)
                    .append("_count,");
            sql.append(" coalesce(sum(total_minor) FILTER (WHERE ")
                    .append(done)
                    .append("), 0) AS ")
                    .append(method)
                    .append("_total,");
        }
        sql.append(
                withProfit
                        ? " coalesce(sum(" + PROFIT + ") FILTER (WHERE status = 'completed'), 0) AS profit_minor"
                        : " 0 AS profit_minor");
        sql.append(" FROM retail_sales WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        SalesRepository.appendFilters(
                sql, params, branchIds, from, to, customerId, paymentMethod, productId, buyer, null, owing, today);
        sql.append(" GROUP BY 1 ORDER BY 1 DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> group(rs, unit, withProfit))
                .list();
    }

    private static Group group(ResultSet rs, String unit, boolean withProfit) throws SQLException {
        LocalDate start = rs.getDate("period_start").toLocalDate();
        LocalDate end;
        String key;
        switch (unit) {
            case "year" -> {
                end = start.plusYears(1).minusDays(1);
                key = String.valueOf(start.getYear());
            }
            case "month" -> {
                end = start.plusMonths(1).minusDays(1);
                key = start.toString().substring(0, 7);
            }
            default -> {
                end = start;
                key = start.toString();
            }
        }
        return new Group(
                key,
                start,
                end,
                rs.getLong("sales_count"),
                rs.getLong("total_minor"),
                withProfit ? rs.getLong("profit_minor") : null,
                rs.getLong("voided_count"),
                new Methods(method(rs, "cash"), method(rs, "mobile_money"), method(rs, "bank"), method(rs, "credit")));
    }

    private static MethodFigures method(ResultSet rs, String method) throws SQLException {
        return new MethodFigures(rs.getLong(method + "_count"), rs.getLong(method + "_total"));
    }
}
