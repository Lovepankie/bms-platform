package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.retail.stock.internal.UsageSummaryService.Group;
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
 * One grouped query per level over the usage and damage movements (issue #816), with the filters of the Past usage and
 * damage list: the branches, the days on the business's clock and the item. A record's cost is its quantity without
 * the sign at the unit cost it carries, rounded half up, exactly as each row of the list shows it, so a group's cost is
 * the sum of its rows. Row-level security supplies the tenant predicate (ADR-003).
 */
@Repository
class UsageSummaryRepository {

    /** Every level, in the order of the old app's grouping. */
    static final List<String> LEVELS = List.of("branch", "month", "category", "item");

    private final JdbcClient jdbc;

    UsageSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What one level query needs; the parent selection of the chain arrives as the filters. */
    record Spec(
            String level,
            boolean withCost,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID categoryId,
            UUID itemId,
            String zone,
            int offset,
            int limit) {}

    List<Group> groups(Spec spec) {
        String level = spec.level();
        boolean item = level.equals("item");
        String day = "(m.occurred_at AT TIME ZONE :zone)::date";
        // The level is one of LEVELS (checked by the service), never caller text.
        String key = switch (level) {
            case "branch" -> "m.branch_id";
            case "category" -> "pr.category_id";
            case "item" -> "m.product_id";
            default -> "date_trunc('month', " + day + ")::date";
        };
        StringBuilder sql = new StringBuilder("SELECT " + key + " AS grp, count(*) AS records_count,");
        sql.append(" count(*) FILTER (WHERE m.kind = 'usage') AS used_count,");
        sql.append(" count(*) FILTER (WHERE m.kind = 'damage') AS damaged_count,");
        sql.append(
                spec.withCost()
                        ? " coalesce(sum(round(abs(m.qty) * coalesce(m.unit_cost_minor, 0))), 0) AS cost_minor,"
                        : " 0 AS cost_minor,");
        sql.append(item ? " sum(abs(m.qty)) AS quantity" : " NULL::numeric AS quantity");
        sql.append(" FROM retail_stock_movements m");
        if (level.equals("category") || spec.categoryId() != null) {
            sql.append(" JOIN retail_products pr ON pr.tenant_id = m.tenant_id AND pr.id = m.product_id");
        }
        sql.append(" WHERE m.kind IN ('usage', 'damage')");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("zone", spec.zone());
        if (spec.branchIds() != null) {
            sql.append(" AND m.branch_id IN (:branchIds)");
            params.put("branchIds", spec.branchIds());
        }
        // As the list reads its days (issue #107): the local date decides, the instant range only narrows the scan.
        if (spec.from() != null) {
            sql.append(" AND " + day + " >= :from"
                    + " AND m.occurred_at >= CAST(CAST(:from AS date) - 1 AS timestamp) AT TIME ZONE :zone");
            params.put("from", spec.from());
        }
        if (spec.to() != null) {
            sql.append(" AND " + day + " <= :to"
                    + " AND m.occurred_at < CAST(CAST(:to AS date) + 2 AS timestamp) AT TIME ZONE :zone");
            params.put("to", spec.to());
        }
        if (spec.categoryId() != null) {
            sql.append(" AND pr.category_id = :categoryId");
            params.put("categoryId", spec.categoryId());
        }
        if (spec.itemId() != null) {
            sql.append(" AND m.product_id = :itemId");
            params.put("itemId", spec.itemId());
        }
        sql.append(" GROUP BY 1");
        String names = switch (level) {
            case "branch" -> " LEFT JOIN branches x ON x.id = g.grp";
            case "category" -> " LEFT JOIN retail_categories x ON x.id = g.grp";
            case "item" -> " LEFT JOIN retail_products x ON x.id = g.grp LEFT JOIN retail_units u ON u.id = x.unit_id";
            default -> "";
        };
        String label = switch (level) {
            case "item" -> "x.description";
            case "branch", "category" -> "x.name";
            default -> "NULL";
        };
        String order = level.equals("month") ? "g.grp DESC" : "lower(" + label + "), g.grp";
        String outer = "WITH g AS (" + sql + ") SELECT g.*, " + label + " AS label, " + (item ? "u.name" : "NULL")
                + " AS unit FROM g" + names + " ORDER BY " + order + " OFFSET :offset LIMIT :limit";
        params.put("offset", spec.offset());
        params.put("limit", spec.limit());
        return jdbc.sql(outer)
                .params(params)
                .query((rs, n) -> group(rs, level, spec.withCost()))
                .list();
    }

    private static Group group(ResultSet rs, String level, boolean withCost) throws SQLException {
        String key;
        String label;
        LocalDate from = null;
        LocalDate to = null;
        if (level.equals("month")) {
            from = rs.getDate("grp").toLocalDate();
            to = from.plusMonths(1).minusDays(1);
            key = from.toString().substring(0, 7);
            label = key;
        } else {
            // Never null: a movement names its branch and product, and a product its category (NOT NULL, V10).
            key = rs.getString("grp");
            label = rs.getString("label") == null ? "Unknown" : rs.getString("label");
        }
        BigDecimal quantity = rs.getBigDecimal("quantity");
        return new Group(
                key,
                label,
                from,
                to,
                rs.getLong("records_count"),
                rs.getLong("used_count"),
                rs.getLong("damaged_count"),
                withCost ? rs.getLong("cost_minor") : null,
                quantity == null ? null : quantity.stripTrailingZeros().toPlainString(),
                rs.getString("unit"));
    }
}
