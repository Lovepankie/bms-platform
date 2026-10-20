package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.Group;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.MethodFigures;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.Methods;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One grouped query per level over {@code retail_sales} (issues #328 and #341). It reads the same filters as the sales
 * list ({@link SalesRepository#appendFilters}) and the same per-sale profit (the sale's total less its cost snapshot),
 * and it ranges on {@code sale_date}, which {@code retail_sales_list} and {@code retail_sales_by_date} index. A level
 * under a category or an item (or the category and item levels themselves) is summed over the sale lines, so the
 * figures of a category are the figures of its own lines; every other level is summed over whole sales, exactly as
 * the list shows them. Row-level security supplies the tenant predicate (ADR-003).
 */
@Repository
class SalesSummaryRepository {

    /** The time levels, whose groups are bounded by a date and continued with {@code to}. */
    static final List<String> TIME_LEVELS = List.of("year", "month", "day");

    /** Every level, in the default order of the grouping chain. */
    static final List<String> LEVELS = List.of("branch", "year", "month", "seller", "day", "category", "item");

    private static final List<String> METHODS = List.of("cash", "mobile_money", "bank", "credit");

    private final JdbcClient jdbc;

    SalesSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What one level query needs; the parent selection of the chain arrives as the filters. */
    record Spec(
            String level,
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
            UUID sellerId,
            UUID categoryId,
            UUID itemId,
            int offset,
            int limit) {

        /** True when the figures are summed over sale lines: a category or an item is the level or was chosen above. */
        boolean overLines() {
            return level.equals("category") || level.equals("item") || categoryId != null || itemId != null;
        }
    }

    List<Group> groups(Spec spec) {
        boolean lines = spec.overLines();
        String level = spec.level();
        boolean time = TIME_LEVELS.contains(level);
        // The level is one of LEVELS (checked by the service), never caller text.
        String key = switch (level) {
            case "branch" -> "retail_sales.branch_id";
            case "seller" -> "retail_sales.created_by";
            case "category" -> "pr.category_id";
            case "item" -> "sl.product_id";
            case "day" -> "retail_sales.sale_date";
            default -> "date_trunc('" + level + "', retail_sales.sale_date::timestamp)::date";
        };
        String done = "retail_sales.status = 'completed'";
        String total = lines ? "sl.line_total_minor" : "retail_sales.total_minor";
        String profit = lines
                ? "sl.line_total_minor - sl.line_cost_minor"
                : "retail_sales.total_minor - retail_sales.cost_total_minor";
        String sales = lines ? "count(DISTINCT retail_sales.id)" : "count(*)";
        StringBuilder sql = new StringBuilder("SELECT " + key + " AS grp,");
        sql.append(" " + sales + " FILTER (WHERE " + done + ") AS sales_count,");
        sql.append(" coalesce(sum(" + total + ") FILTER (WHERE " + done + "), 0) AS total_minor,");
        sql.append(" " + sales + " FILTER (WHERE retail_sales.status = 'voided') AS voided_count,");
        for (String method : METHODS) {
            String mine = done + " AND retail_sales.payment_method = '" + method + "'";
            sql.append(" " + sales + " FILTER (WHERE " + mine + ") AS " + method + "_count,");
            sql.append(" coalesce(sum(" + total + ") FILTER (WHERE " + mine + "), 0) AS " + method + "_total,");
        }
        // Profit is computed only for a caller who may see it (the #332 way), not summed and then dropped.
        sql.append(
                spec.withProfit()
                        ? " coalesce(sum(" + profit + ") FILTER (WHERE " + done + "), 0) AS profit_minor,"
                        : " 0 AS profit_minor,");
        sql.append(
                level.equals("item")
                        ? " coalesce(sum(sl.qty) FILTER (WHERE " + done + "), 0) AS quantity"
                        : " NULL::numeric AS quantity");
        sql.append(
                lines
                        ? " FROM retail_sale_lines sl JOIN retail_sales ON retail_sales.tenant_id = sl.tenant_id"
                                + " AND retail_sales.id = sl.sale_id"
                        : " FROM retail_sales");
        if (lines && (level.equals("category") || spec.categoryId() != null)) {
            sql.append(" JOIN retail_products pr ON pr.tenant_id = sl.tenant_id AND pr.id = sl.product_id");
        }
        sql.append(" WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        SalesRepository.appendFilters(
                sql,
                params,
                spec.branchIds(),
                spec.from(),
                spec.to(),
                spec.customerId(),
                spec.paymentMethod(),
                // Over lines a product filter keeps that product's lines, not every line of the sales that hold it.
                lines ? null : spec.productId(),
                spec.buyer(),
                null,
                spec.owing(),
                spec.today(),
                spec.sellerId(),
                null);
        if (lines && spec.productId() != null) {
            sql.append(" AND sl.product_id = :productId");
            params.put("productId", spec.productId());
        }
        if (spec.categoryId() != null) {
            sql.append(" AND pr.category_id = :categoryId");
            params.put("categoryId", spec.categoryId());
        }
        if (spec.itemId() != null) {
            sql.append(" AND sl.product_id = :itemId");
            params.put("itemId", spec.itemId());
        }
        sql.append(" GROUP BY 1");
        String names = switch (level) {
            case "branch" -> " LEFT JOIN branches x ON x.id = g.grp";
            case "seller" -> " LEFT JOIN users x ON x.id = g.grp";
            case "category" -> " LEFT JOIN retail_categories x ON x.id = g.grp";
            case "item" -> " LEFT JOIN retail_products x ON x.id = g.grp";
            default -> "";
        };
        String label = switch (level) {
            case "seller" -> "x.full_name";
            case "item" -> "x.description";
            case "branch", "category" -> "x.name";
            default -> "NULL";
        };
        String order = time ? "g.grp DESC" : "g.total_minor DESC, g.grp";
        String outer = "WITH g AS (" + sql + ") SELECT g.*, " + label + " AS label FROM g" + names + " ORDER BY "
                + order + " OFFSET :offset LIMIT :limit";
        params.put("offset", spec.offset());
        params.put("limit", spec.limit());
        return jdbc.sql(outer)
                .params(params)
                .query((rs, n) -> group(rs, level, spec.withProfit()))
                .list();
    }

    private static Group group(ResultSet rs, String level, boolean withProfit) throws SQLException {
        String key;
        String label;
        String period = null;
        LocalDate from = null;
        LocalDate to = null;
        if (TIME_LEVELS.contains(level)) {
            LocalDate start = rs.getDate("grp").toLocalDate();
            switch (level) {
                case "year" -> {
                    to = start.plusYears(1).minusDays(1);
                    key = String.valueOf(start.getYear());
                }
                case "month" -> {
                    to = start.plusMonths(1).minusDays(1);
                    key = start.toString().substring(0, 7);
                }
                default -> {
                    to = start;
                    key = start.toString();
                }
            }
            from = start;
            period = key;
            label = key;
        } else {
            key = rs.getString("grp");
            label = rs.getString("label");
            if (label == null) {
                label = "Unknown";
            }
        }
        BigDecimal quantity = rs.getBigDecimal("quantity");
        return new Group(
                key,
                label,
                period,
                from,
                to,
                rs.getLong("sales_count"),
                rs.getLong("total_minor"),
                withProfit ? rs.getLong("profit_minor") : null,
                rs.getLong("voided_count"),
                quantity == null ? null : quantity.stripTrailingZeros().toPlainString(),
                new Methods(method(rs, "cash"), method(rs, "mobile_money"), method(rs, "bank"), method(rs, "credit")));
    }

    private static MethodFigures method(ResultSet rs, String method) throws SQLException {
        return new MethodFigures(rs.getLong(method + "_count"), rs.getLong(method + "_total"));
    }
}
