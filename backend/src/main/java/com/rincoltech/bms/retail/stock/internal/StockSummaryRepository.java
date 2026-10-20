package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.stock.internal.StockSummaryApi.Group;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One grouped query per level over the stock balances (issue #350), with the filters of the stock list (search,
 * category, negative only, stock level). Every shop and item is a row whether or not a balance row exists, as in the
 * list: an active item with no balance counts as nothing in stock. Row-level security supplies the tenant predicate.
 */
@Repository
class StockSummaryRepository {

    static final List<String> LEVELS = List.of("branch", "category");

    private final JdbcClient jdbc;

    StockSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What one level query needs; the parent selection of the chain arrives as the filters. */
    record Spec(
            String level,
            boolean withCost,
            List<UUID> branchIds,
            Search.Query query,
            UUID categoryId,
            boolean negativeOnly,
            boolean includeInactive,
            Long atMostMilli,
            int offset,
            int limit) {}

    List<Group> groups(Spec spec) {
        String level = spec.level();
        // Per branch when a group is a branch or one branch is chosen, as the single-branch list reads them; over the
        // branches together otherwise, as the all-branches list does.
        boolean perRow = level.equals("branch") || spec.branchIds().size() == 1;
        String key = level.equals("branch") ? "br.id" : "p.category_id";
        StringBuilder sql = new StringBuilder("SELECT " + key + " AS grp,");
        sql.append(" count(DISTINCT p.id) AS items_count,");
        sql.append(" count(DISTINCT p.id) FILTER (WHERE coalesce(b.qty, 0) < 0) AS negative_count,");
        sql.append(" coalesce(sum(round(coalesce(b.qty, 0) * p.sell_minor)), 0) AS value_minor,");
        sql.append(
                spec.withCost()
                        ? " coalesce(sum(round(coalesce(b.qty, 0) * p.cost_minor)), 0) AS cost_minor"
                        : " 0 AS cost_minor");
        sql.append(" FROM branches br CROSS JOIN retail_products p JOIN retail_categories c ON c.id = p.category_id");
        sql.append(" LEFT JOIN retail_stock_balances b ON b.branch_id = br.id AND b.product_id = p.id");
        sql.append(" WHERE br.id IN (:branchIds) AND (p.active OR :includeInactive)");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("branchIds", spec.branchIds());
        params.put("includeInactive", spec.includeInactive());
        Search.Match match = spec.query() == null ? null : spec.query().match(StockRepository.SEARCH);
        if (match != null) {
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (spec.categoryId() != null) {
            sql.append(" AND p.category_id = :categoryId");
            params.put("categoryId", spec.categoryId());
        }
        String held = "coalesce((SELECT sum(t.qty) FROM retail_stock_balances t WHERE t.product_id = p.id"
                + " AND t.branch_id IN (:branchIds)), 0)";
        if (spec.negativeOnly()) {
            sql.append(
                    perRow
                            ? " AND coalesce(b.qty, 0) < 0"
                            : " AND EXISTS (SELECT 1 FROM retail_stock_balances t WHERE t.product_id = p.id"
                                    + " AND t.branch_id IN (:branchIds) AND t.qty < 0)");
        }
        if (spec.atMostMilli() != null) {
            sql.append(" AND ").append(perRow ? "coalesce(b.qty, 0)" : held).append(" <= :atMost");
            params.put("atMost", BigDecimal.valueOf(spec.atMostMilli(), 3));
        }
        sql.append(" GROUP BY 1");
        String names = level.equals("branch")
                ? " LEFT JOIN branches x ON x.id = g.grp"
                : " LEFT JOIN retail_categories x ON x.id = g.grp";
        String outer = "WITH g AS (" + sql + ") SELECT g.*, x.name AS label FROM g" + names
                + " ORDER BY lower(x.name), g.grp OFFSET :offset LIMIT :limit";
        params.put("offset", spec.offset());
        params.put("limit", spec.limit());
        return jdbc.sql(outer)
                .params(params)
                .query((rs, n) -> new Group(
                        rs.getString("grp"),
                        rs.getString("label") == null ? "Unknown" : rs.getString("label"),
                        rs.getLong("items_count"),
                        rs.getLong("negative_count"),
                        rs.getLong("value_minor"),
                        spec.withCost() ? rs.getLong("cost_minor") : null))
                .list();
    }
}
