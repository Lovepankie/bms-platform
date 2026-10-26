package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.retail.purchasing.internal.PurchasesSummaryApi.Group;
import java.math.BigDecimal;
import java.sql.Date;
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
 * One grouped query per level over the restocks and the stock movements they wrote (issue #350). Every level reads
 * the purchase movements of {@code retail_purchases}, so a caller scoped to some branches sees only the stock that
 * went into them, as the restocks list does. A restock with a void row is counted apart and left out of every other
 * figure. Row-level security supplies the tenant predicate (ADR-003).
 */
@Repository
class PurchasesSummaryRepository {

    static final List<String> TIME_LEVELS = List.of("year", "month", "day");

    /** Every level, in the default order of the grouping chain. */
    static final List<String> LEVELS =
            List.of("branch", "year", "month", "supplier", "day", "category", "item", "variant");

    /** Issue #384: a product id, or the id of an item with sizes and colours standing for all its variants. */
    static String productOrItem(String column, String param) {
        return column + " IN (SELECT fp.id FROM retail_products fp WHERE fp.id = :" + param + " OR fp.family_id = :"
                + param + ")";
    }

    /** Whether the tenant has an item with sizes and colours: only then is the variant level offered. */
    boolean anyItemWithVariants() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_product_families)")
                .query(Boolean.class)
                .single();
    }

    /** The key of the restocks that name no supplier. */
    static final UUID NO_SUPPLIER = new UUID(0L, 0L);

    private final JdbcClient jdbc;

    PurchasesSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What one level query needs; the parent selection of the chain arrives as the filters. */
    record Spec(
            String level,
            boolean withCost,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID supplierId,
            UUID categoryId,
            UUID itemId,
            String status,
            int offset,
            int limit,
            UUID createdBy) {}

    List<Group> groups(Spec spec) {
        String level = spec.level();
        boolean time = TIME_LEVELS.contains(level);
        // The level is one of LEVELS (checked by the service), never caller text.
        String key = switch (level) {
            case "branch" -> "m.branch_id";
            case "supplier" -> "coalesce(p.supplier_id, '" + NO_SUPPLIER + "'::uuid)";
            case "category" -> "pr.category_id";
            // Issue #384: an item with sizes and colours is one group of all its variants; an item without is its own.
            case "item" -> "coalesce(pr.family_id, m.product_id)";
            case "variant" -> "m.product_id";
            case "day" -> "p.purchased_on";
            default -> "date_trunc('" + level + "', p.purchased_on::timestamp)::date";
        };
        String live = "v.id IS NULL";
        StringBuilder sql = new StringBuilder("SELECT " + key + " AS grp,");
        sql.append(" count(DISTINCT p.id) FILTER (WHERE " + live + ") AS purchases_count,");
        sql.append(" count(DISTINCT p.id) FILTER (WHERE v.id IS NOT NULL) AS voided_count,");
        // What the card shows: a line entered in a pack is its pack quantity (rounded to three places, per branch)
        // at the pack cost, any other line the base quantity at the cost per base unit.
        String value = "CASE WHEN l.unit_id IS NULL THEN round(m.qty * l.cost_minor)"
                + " ELSE round(round(m.qty / l.unit_factor, 3) * l.unit_cost_minor) END";
        sql.append(
                spec.withCost()
                        ? " coalesce(sum(" + value + ") FILTER (WHERE " + live + "), 0) AS total_minor,"
                        : " 0 AS total_minor,");
        sql.append(
                level.equals("item") || level.equals("variant")
                        ? " coalesce(sum(m.qty) FILTER (WHERE " + live + "), 0) AS quantity"
                        : " NULL::numeric AS quantity");
        sql.append(" FROM retail_purchases p");
        sql.append(" JOIN retail_stock_movements m ON m.tenant_id = p.tenant_id AND m.source_id = p.id"
                + " AND m.source_type = 'retail.purchase' AND m.kind = 'purchase'");
        sql.append(" JOIN retail_purchase_lines l ON l.tenant_id = m.tenant_id AND l.id = m.source_line_id");
        sql.append(" LEFT JOIN retail_purchase_voids v ON v.tenant_id = p.tenant_id AND v.purchase_id = p.id");
        if (level.equals("category") || level.equals("item") || spec.categoryId() != null) {
            sql.append(" JOIN retail_products pr ON pr.tenant_id = m.tenant_id AND pr.id = m.product_id");
        }
        sql.append(" WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (spec.branchIds() != null) {
            sql.append(" AND m.branch_id IN (:branchIds)");
            params.put("branchIds", spec.branchIds());
        }
        // ADR-055: without retail.profit.read a buyer adds up only the restocks they recorded.
        if (spec.createdBy() != null) {
            sql.append(" AND p.created_by = :createdBy");
            params.put("createdBy", spec.createdBy());
        }
        if (spec.from() != null) {
            sql.append(" AND p.purchased_on >= :from");
            params.put("from", Date.valueOf(spec.from()));
        }
        if (spec.to() != null) {
            sql.append(" AND p.purchased_on <= :to");
            params.put("to", Date.valueOf(spec.to()));
        }
        appendSupplier(sql, params, spec.supplierId());
        if ("voided".equals(spec.status())) {
            sql.append(" AND v.id IS NOT NULL");
        } else if ("posted".equals(spec.status())) {
            sql.append(" AND v.id IS NULL");
        }
        if (spec.categoryId() != null) {
            sql.append(" AND pr.category_id = :categoryId");
            params.put("categoryId", spec.categoryId());
        }
        if (spec.itemId() != null) {
            sql.append(" AND ").append(productOrItem("m.product_id", "itemId"));
            params.put("itemId", spec.itemId());
        }
        sql.append(" GROUP BY 1");
        String names = switch (level) {
            case "branch" -> " LEFT JOIN branches x ON x.id = g.grp";
            case "supplier" -> " LEFT JOIN retail_suppliers x ON x.id = g.grp";
            case "category" -> " LEFT JOIN retail_categories x ON x.id = g.grp";
            case "item" ->
                " LEFT JOIN retail_products x ON x.id = g.grp LEFT JOIN retail_product_families f ON f.id = g.grp"
                        + " LEFT JOIN retail_units u ON u.id = coalesce(f.unit_id, x.unit_id)";
            case "variant" ->
                " LEFT JOIN retail_products x ON x.id = g.grp LEFT JOIN retail_units u ON u.id = x.unit_id";
            default -> "";
        };
        String label = switch (level) {
            case "item" -> "coalesce(f.name, x.description)";
            case "variant" -> "x.description";
            case "branch", "supplier", "category" -> "x.name";
            default -> "NULL";
        };
        String order = time ? "g.grp DESC" : "g.purchases_count DESC, g.grp";
        String outer = "WITH g AS (" + sql + ") SELECT g.*, " + label + " AS label, "
                + (level.equals("item") || level.equals("variant") ? "u.name" : "NULL") + " AS unit FROM g" + names
                + " ORDER BY " + order
                + " OFFSET :offset LIMIT :limit";
        params.put("offset", spec.offset());
        params.put("limit", spec.limit());
        return jdbc.sql(outer)
                .params(params)
                .query((rs, n) -> group(rs, level, spec.withCost()))
                .list();
    }

    /** The nil UUID stands for the restocks with no supplier. */
    static void appendSupplier(StringBuilder sql, Map<String, Object> params, UUID supplierId) {
        if (supplierId == null) {
            return;
        }
        if (NO_SUPPLIER.equals(supplierId)) {
            sql.append(" AND p.supplier_id IS NULL");
        } else {
            sql.append(" AND p.supplier_id = :supplierId");
            params.put("supplierId", supplierId);
        }
    }

    private static Group group(ResultSet rs, String level, boolean withCost) throws SQLException {
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
                label = NO_SUPPLIER.toString().equals(key) ? "No supplier" : "Unknown";
            }
        }
        BigDecimal quantity = rs.getBigDecimal("quantity");
        return new Group(
                key,
                label,
                period,
                from,
                to,
                rs.getLong("purchases_count"),
                rs.getLong("voided_count"),
                withCost ? rs.getLong("total_minor") : null,
                quantity == null ? null : quantity.stripTrailingZeros().toPlainString(),
                rs.getString("unit"));
    }
}
