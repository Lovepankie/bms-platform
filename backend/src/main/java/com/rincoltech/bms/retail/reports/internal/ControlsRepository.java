package com.rincoltech.bms.retail.reports.internal;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL of the Controls page (issue #357, group 1). Every number is a count or a sum over one "events"
 * statement per metric, all in the same column shape, so a card, a group and a record list always agree. The events
 * read the existing tables only: sales (voids), customer returns, credit write-offs, sale lines (changed prices, discounts,
 * lines below cost), voided restocks and stock corrections. Imported history ({@code historical}) is never an event and never a sale in a rate: it was not done
 * by staff in this system. Every statement is bounded by a date window, by the caller's branch scope and by a row
 * limit; row-level security supplies the tenant predicate (ADR-003). Nothing here returns a cost.
 */
@Repository
class ControlsRepository {

    static final List<String> METRICS = List.of(
            "voided_sales",
            "quick_cancels",
            "returns",
            "credit_writeoffs",
            "price_overrides",
            "discounts",
            "below_cost",
            "restock_voids",
            "quantity_corrections");

    /**
     * The metrics that are not about sales: a restock voided and a quantity corrected. There are no sales of a person or a
     * shop to set them against, so they have counts and values and no rate, and no pattern.
     */
    static final List<String> STOCK_METRICS = List.of("restock_voids", "quantity_corrections");

    /** The levels of the grouping chain. */
    static final List<String> LEVELS = List.of("branch", "user", "day", "reason", "approver");

    private static final String COLUMNS = " AS metric, %s AS sale_id, %s AS sale_no, %s AS ref, %s AS branch_id,"
            + " %s AS user_id, %s AS day, %s AS at, %s AS reason, %s AS value_minor, %s AS item, %s AS approver_id";

    private final JdbcClient jdbc;

    ControlsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Turns JIT compilation off for the rest of the caller's transaction (ADR-087). The union of the events is a large plan,
     * and on a tenant with a million sales PostgreSQL spent about five seconds compiling it for a year's cards that ran in a
     * fraction of that. {@code SET LOCAL} ends with the read-only transaction of {@link ControlsService}.
     */
    private void withoutJit() {
        jdbc.sql("SET LOCAL jit = off").update();
    }

    /** What every statement needs. {@code branchIds} null means every branch; an empty list is answered by the caller. */
    record Window(
            LocalDate from,
            LocalDate to,
            ZoneId zone,
            List<UUID> branchIds,
            int withinMinutes,
            UUID userId,
            UUID approverId,
            String reason) {}

    private static Map<String, Object> params(Window w) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", Date.valueOf(w.from()));
        p.put("to", Date.valueOf(w.to()));
        Instant start = w.from().atStartOfDay(w.zone()).toInstant();
        Instant end = w.to().plusDays(1).atStartOfDay(w.zone()).toInstant();
        p.put("fromAt", Timestamp.from(start));
        p.put("toAt", Timestamp.from(end));
        p.put("tz", w.zone().getId());
        p.put("within", w.withinMinutes());
        return p;
    }

    /** The statement of one metric: one row per event, the same columns for every metric. */
    static String events(String metric) {
        String saleVoid = " FROM retail_sales s WHERE s.status = 'voided' AND NOT s.historical"
                + " AND s.voided_at >= :fromAt AND s.voided_at < :toAt";
        String lines = " FROM retail_sale_lines l JOIN retail_sales s ON s.tenant_id = l.tenant_id AND s.id = l.sale_id"
                + " JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id"
                + " WHERE s.status = 'completed' AND NOT s.historical AND s.sale_date >= :from AND s.sale_date <= :to";
        return switch (metric) {
            case "voided_sales", "quick_cancels" ->
                "SELECT '" + metric + "'"
                        + COLUMNS.formatted(
                                "s.id",
                                "s.sale_no",
                                "CAST(NULL AS text)",
                                "s.branch_id",
                                "s.created_by",
                                "(s.voided_at AT TIME ZONE :tz)::date",
                                "s.voided_at",
                                "s.void_reason",
                                "s.total_minor",
                                "CAST(NULL AS text)",
                                "s.voided_by")
                        + saleVoid
                        + (metric.equals("quick_cancels")
                                ? " AND s.voided_at <= s.created_at + make_interval(mins => :within)"
                                : "");
            case "returns" ->
                "SELECT 'returns'"
                        + COLUMNS.formatted(
                                "r.sale_id",
                                "s.sale_no",
                                "r.credit_note_no",
                                "r.branch_id",
                                "s.created_by",
                                "r.returned_on",
                                "r.created_at",
                                "r.note",
                                "r.total_minor",
                                "CAST(NULL AS text)",
                                "r.created_by")
                        + " FROM retail_sale_returns r JOIN retail_sales s ON s.tenant_id = r.tenant_id AND s.id ="
                        + " r.sale_id WHERE r.status = 'completed' AND r.returned_on >= :from AND r.returned_on <="
                        + " :to";
            // A credit sale written off (ADR-087): on the business day it was written off, credited to the
            // person whose sale it was, the approver who wrote it off. The value is what the buyer still owed
            // at the sale's prices, never the loss, which can be a cost. A row is a write-off that was made:
            // the write-off path inserts it only when it runs (after the second administrator's approval when
            // one is needed) and the table is append-only (V64), so no row waits for approval. A write-off
            // whose journal entry was reversed is left out, should a reversal ever be made (found by the
            // unique (tenant_id, reverses_entry_id) of journal_entries; RetailControlsWriteOffClockIT).
            case "credit_writeoffs" ->
                "SELECT 'credit_writeoffs'"
                        + COLUMNS.formatted(
                                "w.sale_id",
                                "s.sale_no",
                                "CAST(NULL AS text)",
                                "w.branch_id",
                                "s.created_by",
                                "w.written_off_on",
                                "w.created_at",
                                "w.reason",
                                "w.unpaid_minor",
                                "CAST(NULL AS text)",
                                "w.created_by")
                        + " FROM retail_sale_writeoffs w JOIN retail_sales s ON s.tenant_id = w.tenant_id AND s.id ="
                        + " w.sale_id WHERE NOT s.historical AND w.written_off_on >= :from AND w.written_off_on <= :to"
                        + " AND NOT EXISTS (SELECT 1 FROM journal_entries r WHERE r.tenant_id = w.tenant_id AND"
                        + " r.reverses_entry_id = w.journal_entry_id)";
            case "price_overrides" ->
                "SELECT 'price_overrides'" + lineColumns("l.price_override_reason", "l.line_total_minor") + lines
                        + " AND l.price_source = 'override'";
            case "discounts" ->
                "SELECT 'discounts'"
                        + lineColumns(
                                "l.price_override_reason",
                                "round((l.resolved_unit_price_minor - coalesce(l.sale_unit_price_minor,"
                                        + " l.unit_price_minor)) * coalesce(l.sale_qty, l.qty))::bigint")
                        + lines
                        + " AND l.price_source = 'override'"
                        + " AND l.resolved_unit_price_minor > coalesce(l.sale_unit_price_minor, l.unit_price_minor)";
            case "below_cost" ->
                "SELECT 'below_cost'" + lineColumns("CAST(NULL AS text)", "l.line_total_minor") + lines
                        + " AND l.line_total_minor < l.line_cost_minor";
            // A restock voided (ADR-044): one event per restock and branch it stocked, from its purchase movements. The
            // person is
            // who entered the restock, the approver who voided it; sale_id and sale_no carry the restock's id and
            // number. The
            // value is that branch's quantity at the item's SELLING price now (never the restock's cost).
            case "restock_voids" ->
                "SELECT 'restock_voids'"
                        + COLUMNS.formatted(
                                "p.id",
                                "p.purchase_no",
                                "CAST(NULL AS text)",
                                "mb.branch_id",
                                "p.created_by",
                                "v.voided_on",
                                "v.created_at",
                                "v.reason",
                                "mb.value_minor",
                                "CAST(NULL AS text)",
                                "v.voided_by")
                        + " FROM retail_purchase_voids v JOIN retail_purchases p ON p.tenant_id = v.tenant_id AND p.id"
                        + " = v.purchase_id JOIN LATERAL (SELECT m.branch_id, round(sum(m.qty *"
                        + " pr.sell_minor))::bigint AS value_minor FROM retail_stock_movements m JOIN retail_products"
                        + " pr ON pr.tenant_id = m.tenant_id AND pr.id = m.product_id WHERE m.tenant_id = p.tenant_id"
                        + " AND m.source_type = 'retail.purchase' AND m.source_id = p.id AND m.kind = 'purchase' GROUP"
                        + " BY m.branch_id) mb ON true WHERE v.voided_on >= :from AND v.voided_on <= :to";
            // A quantity corrected on the stock list (ADR-044): the person is who corrected it, the reason the code and
            // the
            // note, the value the change at the item's selling price now (never a cost), the day the day it was made.
            case "quantity_corrections" ->
                "SELECT 'quantity_corrections'"
                        + COLUMNS.formatted(
                                "CAST(NULL AS uuid)",
                                "CAST(NULL AS text)",
                                "CAST(NULL AS text)",
                                "c.branch_id",
                                "c.created_by",
                                "(c.created_at AT TIME ZONE :tz)::date",
                                "c.created_at",
                                "CASE c.reason_code WHEN 'entry_mistake' THEN 'Entry mistake' ELSE 'Miscount' END"
                                        + " || coalesce(': ' || c.note, '')",
                                "round(abs(c.new_qty - c.old_qty) * p.sell_minor)::bigint",
                                "p.description || ' (' || trim_scale(c.old_qty)::text || ' to ' ||"
                                        + " trim_scale(c.new_qty)::text || ')'",
                                "CAST(NULL AS uuid)")
                        + " FROM retail_stock_corrections c JOIN retail_products p ON p.tenant_id = c.tenant_id"
                        + " AND p.id = c.product_id WHERE c.created_at >= :fromAt AND c.created_at < :toAt";
            default -> throw new IllegalArgumentException("metric " + metric);
        };
    }

    /**
     * The columns of a sale line event. The person is whoever made the sale, or, for a price changed when a customer order was
     * taken (issue #385), whoever changed it then: a delivered order counts that change once, never for the one who delivered.
     * The rate per 100 sales keeps the sales each person entered as its denominator: an order taker credited with a change made
     * on an order someone else delivered has it counted against the sales they entered themselves (ADR-052).
     */
    private static String lineColumns(String reason, String value) {
        return COLUMNS.formatted(
                "s.id",
                "s.sale_no",
                "CAST(NULL AS text)",
                "s.branch_id",
                "coalesce(l.price_overridden_by, s.created_by)",
                "s.sale_date",
                "s.created_at",
                reason,
                value,
                "p.description",
                "CAST(NULL AS uuid)");
    }

    /** The events of the metrics asked for, narrowed to the window's shop, person and reason. */
    private static String filtered(List<String> metrics, Window w, Map<String, Object> params) {
        StringBuilder union = new StringBuilder();
        for (String metric : metrics) {
            union.append(union.isEmpty() ? "" : " UNION ALL ").append(events(metric));
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM (" + union + ") ev WHERE true");
        if (w.branchIds() != null) {
            sql.append(" AND ev.branch_id IN (:branchIds)");
            params.put("branchIds", w.branchIds());
        }
        if (w.userId() != null) {
            sql.append(" AND ev.user_id = :userId");
            params.put("userId", w.userId());
        }
        if (w.approverId() != null) {
            sql.append(" AND ev.approver_id = :approverId");
            params.put("approverId", w.approverId());
        }
        if (w.reason() != null) {
            sql.append(" AND lower(btrim(coalesce(ev.reason, ''))) = :reason");
            params.put("reason", w.reason());
        }
        return sql.toString();
    }

    /** The sales entered in the window: how many (any status), and the total of the completed ones. */
    record Sales(long count, long totalMinor) {}

    Sales sales(Window w) {
        Map<String, Object> params = params(w);
        StringBuilder sql = new StringBuilder("SELECT count(*) AS n,"
                + " coalesce(sum(s.total_minor) FILTER (WHERE s.status = 'completed'), 0) AS v"
                + " FROM retail_sales s WHERE NOT s.historical AND s.sale_date >= :from AND s.sale_date <= :to");
        salesFilters(sql, params, w);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new Sales(rs.getLong("n"), rs.getLong("v")))
                .single();
    }

    private static void salesFilters(StringBuilder sql, Map<String, Object> params, Window w) {
        if (w.branchIds() != null) {
            sql.append(" AND s.branch_id IN (:branchIds)");
            params.put("branchIds", w.branchIds());
        }
        if (w.userId() != null) {
            sql.append(" AND s.created_by = :userId");
            params.put("userId", w.userId());
        }
    }

    record Count(String metric, long count, long salesAffected, long valueMinor) {}

    /** Count and value of each metric asked for in the window (the cards). */
    List<Count> counts(List<String> metrics, Window w) {
        withoutJit();
        Map<String, Object> params = params(w);
        String sql = "SELECT metric, count(*) FILTER (WHERE metric <> 'restock_voids')"
                + " + count(DISTINCT sale_id) FILTER (WHERE metric = 'restock_voids') AS n,"
                + " count(DISTINCT sale_id) AS a, coalesce(sum(value_minor), 0) AS v FROM ("
                + filtered(metrics, w, params) + ") x GROUP BY metric";
        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> new Count(rs.getString("metric"), rs.getLong("n"), rs.getLong("a"), rs.getLong("v")))
                .list();
    }

    /** One group of one level: the key, its events and the sales entered by the same key. */
    record Cell(
            String key,
            String label,
            String userStatus,
            LocalDate day,
            long count,
            long salesAffected,
            long valueMinor,
            long salesCount) {}

    /**
     * The groups of one level for one metric, with the sales entered by the same shop, person or day for the rate (a
     * reason has no sales of its own, so its rate is over all sales in the window; an approver has none and no rate).
     * Day is newest first and reasons come by their count; shops, people and approvers come by name, never by count,
     * so the list is not a league table.
     */
    List<Cell> groups(String metric, String level, Window w, int offset, int limit) {
        withoutJit();
        Map<String, Object> params = params(w);
        String key = switch (level) {
            case "branch" -> "coalesce(CAST(ev.branch_id AS text), '')";
            case "user" -> "coalesce(CAST(ev.user_id AS text), '')";
            case "approver" -> "coalesce(CAST(ev.approver_id AS text), '')";
            case "day" -> "CAST(ev.day AS text)";
            default -> "lower(btrim(coalesce(ev.reason, '')))";
        };
        String salesKey = switch (level) {
            case "branch" -> "CAST(s.branch_id AS text)";
            case "user" -> "CAST(s.created_by AS text)";
            case "day" -> "CAST(s.sale_date AS text)";
            default -> "'all'";
        };
        // An approver entered no sales of their own to set a rate against: the join below finds nothing.
        String salesJoin = STOCK_METRICS.contains(metric)
                ? "'-'"
                : switch (level) {
                    case "reason" -> "'all'";
                    case "approver" -> "'-'";
                    default -> "g.k";
                };
        // The sales behind a rate answer to the shop and the person above, never to the reason.
        Map<String, Object> salesParams = new LinkedHashMap<>();
        StringBuilder sales = new StringBuilder("SELECT " + salesKey + " AS k, count(*) AS n FROM retail_sales s"
                + " WHERE NOT s.historical AND s.sale_date >= :from AND s.sale_date <= :to");
        salesFilters(sales, salesParams, w);
        sales.append(" GROUP BY 1");
        params.putAll(salesParams);
        String names = switch (level) {
            case "branch" -> " LEFT JOIN branches b ON b.id = CAST(NULLIF(g.k, '') AS uuid)";
            case "user", "approver" -> " LEFT JOIN users u ON u.id = CAST(NULLIF(g.k, '') AS uuid)";
            default -> "";
        };
        String label = switch (level) {
            case "branch" -> "b.name";
            case "user", "approver" -> "u.full_name";
            case "reason" -> "g.sample";
            default -> "g.k";
        };
        String status = level.equals("user") || level.equals("approver") ? "u.status" : "CAST(NULL AS text)";
        String order = switch (level) {
            case "day" -> "g.k DESC";
            case "reason" -> "g.n DESC, g.k";
            default -> "lower(" + label + ") NULLS LAST, g.k";
        };
        String sql = "WITH g AS (SELECT " + key + " AS k, "
                + (metric.equals("restock_voids") ? "count(DISTINCT ev.sale_id)" : "count(*)")
                + " AS n, count(DISTINCT ev.sale_id) AS a,"
                + " coalesce(sum(ev.value_minor), 0) AS v,"
                + " min(btrim(ev.reason)) AS sample FROM (" + filtered(List.of(metric), w, params)
                + ") ev GROUP BY 1), s AS (" + sales + ")"
                + " SELECT g.k, " + label + " AS label, " + status + " AS user_status, g.n, g.a, g.v,"
                + " coalesce(s.n, 0) AS sales_count FROM g"
                + names
                + " LEFT JOIN s ON s.k = " + salesJoin
                + " ORDER BY " + order + " OFFSET :offset LIMIT :limit";
        params.put("offset", offset);
        params.put("limit", limit);
        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> new Cell(
                        rs.getString("k"),
                        rs.getString("label"),
                        rs.getString("user_status"),
                        level.equals("day") ? LocalDate.parse(rs.getString("k")) : null,
                        rs.getLong("n"),
                        rs.getLong("a"),
                        rs.getLong("v"),
                        rs.getLong("sales_count")))
                .list();
    }

    /** One event with who, where and why, for the list a group opens onto. */
    record Event(
            UUID saleId,
            String saleNo,
            String ref,
            Instant at,
            UUID branchId,
            String branchName,
            UUID userId,
            String userName,
            String userStatus,
            UUID approverId,
            String approverName,
            String approverStatus,
            String reason,
            String item,
            long valueMinor) {}

    List<Event> events(String metric, Window w, int offset, int limit) {
        withoutJit();
        Map<String, Object> params = params(w);
        String sql = "SELECT ev.sale_id, ev.sale_no, ev.ref, ev.at, ev.branch_id, b.name AS branch_name, ev.user_id,"
                + " u.full_name, u.status, ev.approver_id, ua.full_name AS approver_name, ua.status AS approver_status,"
                + " ev.reason, ev.item, ev.value_minor FROM ("
                + filtered(List.of(metric), w, params)
                + ") ev LEFT JOIN branches b ON b.id = ev.branch_id LEFT JOIN users u ON u.id = ev.user_id"
                + " LEFT JOIN users ua ON ua.id = ev.approver_id"
                + " ORDER BY ev.at DESC, ev.sale_id DESC, ev.item OFFSET :offset LIMIT :limit";
        params.put("offset", offset);
        params.put("limit", limit);
        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> new Event(
                        rs.getObject("sale_id", UUID.class),
                        rs.getString("sale_no"),
                        rs.getString("ref"),
                        rs.getTimestamp("at").toInstant(),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name"),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("status"),
                        rs.getObject("approver_id", UUID.class),
                        rs.getString("approver_name"),
                        rs.getString("approver_status"),
                        rs.getString("reason"),
                        rs.getString("item"),
                        rs.getLong("value_minor")))
                .list();
    }

    /** Events of one person in one shop, and the sales that person entered there. */
    record PersonCell(
            UUID branchId,
            String branchName,
            UUID userId,
            String userName,
            String userStatus,
            String metric,
            long count,
            long sales) {}

    /**
     * Per shop and person: the sales of the person with an event of each metric (a sale counts once, whatever its number of
     * lines), with the sales the person entered in that shop. The person is the one whose sale it was, never the one who
     * cancelled or took it back.
     */
    List<PersonCell> personCells(List<String> metrics, Window w) {
        withoutJit();
        Map<String, Object> params = params(w);
        StringBuilder sales =
                new StringBuilder("SELECT s.branch_id, s.created_by AS user_id, count(*) AS n FROM retail_sales s"
                        + " WHERE NOT s.historical AND s.sale_date >= :from AND s.sale_date <= :to");
        salesFilters(sales, params, w);
        sales.append(" GROUP BY 1, 2");
        String sql = "WITH e AS (SELECT metric, branch_id, user_id, count(DISTINCT sale_id) AS n FROM ("
                + filtered(metrics, w, params)
                + ") x GROUP BY 1, 2, 3), s AS (" + sales + ")"
                + " SELECT e.branch_id, b.name AS branch_name, e.user_id, u.full_name, u.status, e.metric, e.n,"
                + " coalesce(s.n, 0) AS sales FROM e LEFT JOIN s ON s.branch_id = e.branch_id AND s.user_id = e.user_id"
                + " LEFT JOIN branches b ON b.id = e.branch_id LEFT JOIN users u ON u.id = e.user_id";
        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> new PersonCell(
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name"),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("status"),
                        rs.getString("metric"),
                        rs.getLong("n"),
                        rs.getLong("sales")))
                .list();
    }

    /** Sales entered per shop: the denominator of a shop's own rate. */
    Map<UUID, Long> salesByBranch(Window w) {
        Map<String, Object> params = params(w);
        StringBuilder sql = new StringBuilder("SELECT s.branch_id, count(*) AS n FROM retail_sales s"
                + " WHERE NOT s.historical AND s.sale_date >= :from AND s.sale_date <= :to");
        salesFilters(sql, params, w);
        sql.append(" GROUP BY 1");
        Map<UUID, Long> out = new LinkedHashMap<>();
        jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    out.put(rs.getObject("branch_id", UUID.class), rs.getLong("n"));
                    return null;
                })
                .list();
        return out;
    }

    /** Events of one item in one shop, for the lines the item sold. */
    record ItemCell(
            UUID branchId, String branchName, UUID productId, String name, String metric, long count, long lines) {}

    /**
     * Per shop and item: lines of voided sales, changed prices and (when asked for) lines below cost, with the lines
     * the item sold in the shop in the window, by the date of the sale. Voids are counted by the sale's own date here
     * so that the count and the lines sold are the same population.
     */
    List<ItemCell> itemCells(boolean cost, Window w) {
        withoutJit();
        Map<String, Object> params = params(w);
        StringBuilder where =
                new StringBuilder(" WHERE NOT s.historical AND s.sale_date >= :from AND s.sale_date <= :to");
        if (w.branchIds() != null) {
            where.append(" AND s.branch_id IN (:branchIds)");
            params.put("branchIds", w.branchIds());
        }
        String sql = "SELECT s.branch_id, b.name AS branch_name, l.product_id, p.description AS name,"
                + " count(*) AS lines,"
                + " count(*) FILTER (WHERE s.status = 'voided') AS voided_sales,"
                + " count(*) FILTER (WHERE s.status = 'completed' AND l.price_source = 'override') AS price_overrides,"
                + (cost
                        ? " count(*) FILTER (WHERE s.status = 'completed' AND l.line_total_minor < l.line_cost_minor)"
                                + " AS below_cost"
                        : " 0 AS below_cost")
                + " FROM retail_sale_lines l JOIN retail_sales s ON s.tenant_id = l.tenant_id AND s.id = l.sale_id"
                + " JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id"
                + " JOIN branches b ON b.tenant_id = s.tenant_id AND b.id = s.branch_id"
                + where + " GROUP BY 1, 2, 3, 4";
        List<ItemCell> out = new java.util.ArrayList<>();
        jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> {
                    UUID branch = rs.getObject("branch_id", UUID.class);
                    UUID product = rs.getObject("product_id", UUID.class);
                    for (String metric : List.of("voided_sales", "price_overrides", "below_cost")) {
                        out.add(new ItemCell(
                                branch,
                                rs.getString("branch_name"),
                                product,
                                rs.getString("name"),
                                metric,
                                rs.getLong(metric),
                                rs.getLong("lines")));
                    }
                    return null;
                })
                .list();
        return out;
    }
}
