package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.CancelledOrder;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.OrderEvent;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.OrderLine;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.ReportRow;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The customer order tables of V70 (issue #385, ADR-052). Every query runs in the caller's transaction. */
@Repository
class CustomerOrdersRepository {

    static final String OPEN = "('ordered', 'packed', 'out_for_delivery')";

    static final Search.Fields SEARCH = Search.Fields.of()
            .names("retail_customer_orders.customer_name")
            .codes("retail_customer_orders.order_no")
            .phones("retail_customer_orders.customer_phone");

    private static final String SELECT = """
            SELECT retail_customer_orders.*, s.sale_no, s.status AS sale_status
              FROM retail_customer_orders
              LEFT JOIN retail_sales s ON s.id = retail_customer_orders.sale_id
            """;

    private final JdbcClient jdbc;

    CustomerOrdersRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One order's row as stored. */
    record Header(
            UUID id,
            String orderNo,
            UUID branchId,
            LocalDate orderedOn,
            String source,
            UUID customerId,
            String customerName,
            String customerPhone,
            String deliveryPlace,
            LocalDate deliveryDate,
            long deliveryFeeMinor,
            String currency,
            long itemsTotalMinor,
            String notes,
            String status,
            String deliveredBy,
            Instant packedAt,
            Instant sentAt,
            Instant deliveredAt,
            LocalDate deliveredOn,
            UUID saleId,
            String saleNo,
            String feeMethod,
            Instant cancelledAt,
            String cancelReason,
            LocalDate feePaidOn,
            String feePaidMethod,
            String saleStatus,
            Instant createdAt,
            UUID createdBy,
            int version,
            int itemsRevision) {

        boolean open() {
            return status.equals("ordered") || status.equals("packed") || status.equals("out_for_delivery");
        }
    }

    /** A stored line with what the sale and the release need. */
    record StoredLine(
            int lineNo,
            UUID productId,
            String code,
            String description,
            BigDecimal qty,
            UUID unitId,
            String unit,
            long unitPriceMinor,
            long lineTotalMinor,
            BigDecimal baseQty,
            String priceSource,
            String overrideReason,
            UUID overriddenBy,
            UUID priceListId,
            BigDecimal breakMinQty,
            Long resolvedUnitPriceMinor) {

        /** How the price was chosen, as stored (null on a line priced without price lists). */
        SalesRepository.LinePricing pricing() {
            return priceSource == null
                    ? null
                    : new SalesRepository.LinePricing(
                            priceSource,
                            priceListId,
                            breakMinQty,
                            resolvedUnitPriceMinor,
                            overrideReason,
                            overriddenBy);
        }

        OrderLine view() {
            return new OrderLine(
                    lineNo,
                    productId,
                    code,
                    description,
                    DocumentModels.plain(qty.toPlainString()),
                    unitId,
                    unit,
                    unitPriceMinor,
                    lineTotalMinor,
                    DocumentModels.plain(baseQty.toPlainString()));
        }
    }

    void insert(
            UUID id,
            String orderNo,
            UUID branchId,
            LocalDate orderedOn,
            String source,
            UUID customerId,
            String customerName,
            String customerPhone,
            String deliveryPlace,
            LocalDate deliveryDate,
            long deliveryFeeMinor,
            String currency,
            long itemsTotalMinor,
            String notes,
            UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_customer_orders (id, tenant_id, created_by, order_no, branch_id, ordered_on, source,
                            customer_id, customer_name, customer_phone, delivery_place, delivery_date, delivery_fee_minor,
                            currency, items_total_minor, notes)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :by, :no, :branch, :on, :source, :customer, :name,
                            :phone, :place, :date, :fee, :currency, :items, :notes)
                        """)
                .param("id", id)
                .param("by", by)
                .param("no", orderNo)
                .param("branch", branchId)
                .param("on", Date.valueOf(orderedOn))
                .param("source", source)
                .param("customer", customerId)
                .param("name", customerName)
                .param("phone", customerPhone)
                .param("place", deliveryPlace)
                .param("date", deliveryDate == null ? null : Date.valueOf(deliveryDate))
                .param("fee", deliveryFeeMinor)
                .param("currency", currency)
                .param("items", itemsTotalMinor)
                .param("notes", notes)
                .update();
    }

    void insertLine(
            UUID orderId,
            int revision,
            int lineNo,
            UUID productId,
            UUID unitId,
            BigDecimal qty,
            BigDecimal baseQty,
            long unitPriceMinor,
            long lineTotalMinor,
            SalesRepository.LinePricing pricing,
            UUID by) {
        boolean override = pricing != null && "override".equals(pricing.source());
        jdbc.sql("""
                        INSERT INTO retail_customer_order_lines (id, tenant_id, order_id, revision, line_no, product_id, unit_id, qty,
                            base_qty, unit_price_minor, line_total_minor, price_source, price_list_id, price_break_min_qty,
                            resolved_unit_price_minor, price_override_reason, overridden_by)
                        VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?)
                        """)
                .params(
                        orderId,
                        revision,
                        lineNo,
                        productId,
                        unitId,
                        qty,
                        baseQty,
                        unitPriceMinor,
                        lineTotalMinor,
                        pricing == null ? null : pricing.source(),
                        pricing == null ? null : pricing.priceListId(),
                        pricing == null ? null : pricing.breakMinQty(),
                        pricing == null ? null : pricing.resolvedUnitPriceMinor(),
                        override ? pricing.overrideReason() : null,
                        override ? by : null)
                .update();
    }

    void event(UUID orderId, String step, String note, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_customer_order_events (id, tenant_id, created_at, order_id, step, note, by_user)
                        VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, clock_timestamp(), ?, ?, ?, ?)
                        """).params(orderId, step, note, by).update();
    }

    Optional<Header> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT + " WHERE retail_customer_orders.id = ?"
                        + (lock ? " FOR UPDATE OF retail_customer_orders" : ""))
                .param(id)
                .query((rs, n) -> header(rs))
                .optional();
    }

    /** The order's current lines: those of its items revision (V77, issue #393). */
    List<StoredLine> lines(UUID orderId) {
        return jdbc.sql("""
                        SELECT l.line_no, l.product_id, p.code, p.description, l.qty, l.unit_id, coalesce(u.name, bu.name) AS unit,
                               l.unit_price_minor, l.line_total_minor, l.base_qty, l.price_source, l.price_override_reason,
                               l.overridden_by, l.price_list_id, l.price_break_min_qty, l.resolved_unit_price_minor
                          FROM retail_customer_order_lines l
                          JOIN retail_customer_orders o ON o.id = l.order_id AND o.items_revision = l.revision
                          JOIN retail_products p ON p.id = l.product_id
                          JOIN retail_units bu ON bu.id = p.unit_id
                          LEFT JOIN retail_units u ON u.id = l.unit_id
                         WHERE l.order_id = ?
                         ORDER BY l.line_no
                        """)
                .param(orderId)
                .query((rs, n) -> new StoredLine(
                        rs.getInt("line_no"),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getBigDecimal("qty"),
                        rs.getObject("unit_id", UUID.class),
                        rs.getString("unit"),
                        rs.getLong("unit_price_minor"),
                        rs.getLong("line_total_minor"),
                        rs.getBigDecimal("base_qty"),
                        rs.getString("price_source"),
                        rs.getString("price_override_reason"),
                        rs.getObject("overridden_by", UUID.class),
                        rs.getObject("price_list_id", UUID.class),
                        rs.getBigDecimal("price_break_min_qty"),
                        rs.getObject("resolved_unit_price_minor", Long.class)))
                .list();
    }

    /** ADR-055: of these sales, the ones the user made, in one query for a page of orders. */
    java.util.Set<UUID> salesMadeBy(List<UUID> saleIds, UUID user) {
        if (saleIds.isEmpty()) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(jdbc.sql("SELECT id FROM retail_sales WHERE id IN (:ids) AND created_by = :user")
                .param("ids", saleIds)
                .param("user", user)
                .query(UUID.class)
                .list());
    }

    List<OrderEvent> events(UUID orderId) {
        return jdbc.sql("""
                        SELECT e.step, e.note, e.by_user, u.full_name, e.created_at
                          FROM retail_customer_order_events e
                          LEFT JOIN users u ON u.id = e.by_user
                         WHERE e.order_id = ?
                         ORDER BY e.created_at, e.id
                        """)
                .param(orderId)
                .query((rs, n) -> new OrderEvent(
                        rs.getString("step"),
                        rs.getString("note"),
                        rs.getObject("by_user", UUID.class),
                        rs.getString("full_name"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * A page of orders, newest first. {@code status} is a step, or {@code open} for the three open steps; {@code due} is
     * {@code today} (open, to deliver today) or {@code overdue} (open, the delivery day passed).
     */
    List<Header> page(
            List<UUID> branchIds,
            String status,
            String due,
            LocalDate today,
            String source,
            Search.Query search,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            sql.append(" AND retail_customer_orders.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if ("open".equals(status)) {
            sql.append(" AND retail_customer_orders.status IN " + OPEN);
        } else if (status != null) {
            sql.append(" AND retail_customer_orders.status = :status");
            params.put("status", status);
        }
        if (due != null) {
            sql.append(" AND retail_customer_orders.status IN " + OPEN);
            sql.append(
                    due.equals("today")
                            ? " AND retail_customer_orders.delivery_date = :today"
                            : " AND retail_customer_orders.delivery_date < :today");
            params.put("today", Date.valueOf(today));
        }
        if (source != null) {
            sql.append(" AND retail_customer_orders.source = :source");
            params.put("source", source);
        }
        if (search != null) {
            Search.Match match = search.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(
                    " AND (retail_customer_orders.created_at, retail_customer_orders.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY retail_customer_orders.created_at DESC, retail_customer_orders.id DESC LIMIT :limit");
        params.put("limit", limit);
        return new ArrayList<>(jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> header(rs))
                .list());
    }

    /** The counts of the board, in the branches of the filter (null for every branch). */
    long[] board(List<UUID> branchIds, LocalDate today) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new long[6];
        }
        String where = branchIds == null ? "" : " AND branch_id IN (:branchIds)";
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        params.put("today", Date.valueOf(today));
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'ordered') AS ordered,
                               count(*) FILTER (WHERE status = 'packed') AS packed,
                               count(*) FILTER (WHERE status = 'out_for_delivery') AS out_for_delivery,
                               count(*) FILTER (WHERE status IN %1$s AND delivery_date = :today) AS due_today,
                               count(*) FILTER (WHERE status IN %1$s AND delivery_date < :today) AS overdue,
                               count(*) FILTER (WHERE status = 'delivered' AND delivered_on = :today) AS delivered_today
                          FROM retail_customer_orders
                         WHERE (status IN %1$s OR (status = 'delivered' AND delivered_on = :today))%2$s
                        """.formatted(OPEN, where))
                .params(params)
                .query((rs, n) -> new long[] {
                    rs.getLong("ordered"),
                    rs.getLong("packed"),
                    rs.getLong("out_for_delivery"),
                    rs.getLong("due_today"),
                    rs.getLong("overdue"),
                    rs.getLong("delivered_today")
                })
                .single();
    }

    /**
     * How a phone number is matched: on its last nine digits when it has that many (a Ugandan number without the country code
     * or the leading zero, so +256 700 000 011 and 0700 000 011 are one number), otherwise on all its digits.
     */
    static String phoneMatch(String column, String digits) {
        return digits.length() >= PHONE_KEY
                ? "right(bms_digits(" + column + "), " + PHONE_KEY + ") = :digits"
                : "bms_digits(" + column + ") = :digits";
    }

    static final int PHONE_KEY = 9;

    static String phoneKey(String digits) {
        return digits.length() >= PHONE_KEY ? digits.substring(digits.length() - PHONE_KEY) : digits;
    }

    /** The newest order of a phone number (see {@link #phoneMatch}) in the branches of the filter, and how many it made. */
    Optional<Map<String, Object>> lastByPhone(String digits, List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return Optional.empty();
        }
        String where = branchIds == null ? "" : " AND branch_id IN (:branchIds)";
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        params.put("digits", phoneKey(digits));
        return jdbc.sql("""
                        SELECT customer_id, customer_name, customer_phone, delivery_place, delivery_fee_minor,
                               count(*) OVER () AS orders
                          FROM retail_customer_orders
                          WHERE customer_phone IS NOT NULL AND %s%s
                         ORDER BY created_at DESC, id DESC
                         LIMIT 1
                        """.formatted(phoneMatch("customer_phone", digits), where))
                .params(params)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("customer_id", rs.getObject("customer_id", UUID.class));
                    m.put("customer_name", rs.getString("customer_name"));
                    m.put("customer_phone", rs.getString("customer_phone"));
                    m.put("delivery_place", rs.getString("delivery_place"));
                    m.put("delivery_fee_minor", rs.getLong("delivery_fee_minor"));
                    m.put("orders", rs.getLong("orders"));
                    return m;
                })
                .optional();
    }

    /** A credit buyer whose contact is this number (see {@link #phoneMatch}), the oldest first (the one most likely set up on purpose). */
    Optional<UUID> customerByPhone(String digits) {
        return jdbc.sql("""
                        SELECT id FROM retail_customers
                         WHERE contact IS NOT NULL AND %s
                         ORDER BY created_at, id
                         LIMIT 1
                        """.formatted(phoneMatch("contact", digits)))
                .param("digits", phoneKey(digits))
                .query(UUID.class)
                .optional();
    }

    int markChanged(
            UUID id, int version, String name, String phone, String place, LocalDate date, long fee, String notes) {
        return jdbc.sql("""
                        UPDATE retail_customer_orders SET customer_name = ?, customer_phone = ?, delivery_place = ?,
                               delivery_date = ?, delivery_fee_minor = ?, notes = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND version = ?
                        """)
                .params(name, phone, place, date == null ? null : Date.valueOf(date), fee, notes, id, version)
                .update();
    }

    /** A change of items (issue #393): the next revision of lines and their total, the step unchanged. */
    int markItemsChanged(UUID id, int version, int itemsRevision, long itemsTotalMinor) {
        return jdbc.sql("""
                        UPDATE retail_customer_orders SET items_revision = ?, items_total_minor = ?, updated_at = now(),
                               version = version + 1
                         WHERE id = ? AND version = ? AND items_revision = ? - 1 AND status IN ('ordered', 'packed')
                        """)
                .params(itemsRevision, itemsTotalMinor, id, version, itemsRevision)
                .update();
    }

    int markPacked(UUID id, int version, UUID by) {
        return jdbc.sql("""
                        UPDATE retail_customer_orders SET status = 'packed', packed_at = now(), packed_by = ?, updated_at = now(),
                               version = version + 1
                         WHERE id = ? AND version = ? AND status = 'ordered'
                        """).params(by, id, version).update();
    }

    int markSent(UUID id, int version, UUID by, String deliveredBy) {
        return jdbc.sql("""
                        UPDATE retail_customer_orders SET status = 'out_for_delivery', sent_at = now(), sent_by = ?,
                               delivered_by = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND version = ? AND status IN ('ordered', 'packed')
                        """).params(by, deliveredBy, id, version).update();
    }

    int markDelivered(UUID id, int version, UUID by, LocalDate on, UUID saleId, String feeMethod, UUID feeEntryId) {
        return jdbc.sql("""
                        UPDATE retail_customer_orders SET status = 'delivered', delivered_at = now(), delivered_on = ?,
                               delivered_by_user = ?, sale_id = ?, fee_method = ?, fee_entry_id = ?, updated_at = now(),
                               version = version + 1
                         WHERE id = ? AND version = ? AND status IN %s
                        """.formatted(OPEN))
                .params(Date.valueOf(on), by, saleId, feeMethod, feeEntryId, id, version)
                .update();
    }

    int markFeePaid(UUID id, int version, UUID by, LocalDate on, String method, UUID entryId) {
        return jdbc.sql("""
                        UPDATE retail_customer_orders SET fee_paid_at = now(), fee_paid_on = ?, fee_paid_method = ?, fee_paid_by = ?,
                               fee_payment_entry_id = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND version = ? AND status = 'delivered' AND fee_method = 'account' AND fee_paid_at IS NULL
                        """)
                .params(Date.valueOf(on), method, by, entryId, id, version)
                .update();
    }

    int markCancelled(UUID id, int version, UUID by, LocalDate on, String reason) {
        return jdbc.sql("""
                        UPDATE retail_customer_orders SET status = 'cancelled', cancelled_at = now(), cancelled_on = ?,
                               cancelled_by = ?, cancel_reason = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND version = ? AND status IN %s
                        """.formatted(OPEN))
                .params(Date.valueOf(on), by, reason, id, version)
                .update();
    }

    /**
     * Delivered orders by day, source or rider in the window, in the branches of the filter. An order whose sale was voided is
     * left out: its fee was reversed with the sale.
     */
    List<ReportRow> fees(List<UUID> branchIds, LocalDate from, LocalDate to, String groupBy) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        String key = switch (groupBy) {
            case "source" -> "source";
            case "rider" -> "coalesce(nullif(btrim(delivered_by), ''), '')";
            default -> "to_char(delivered_on, 'YYYY-MM-DD')";
        };
        String where = branchIds == null ? "" : " AND branch_id IN (:branchIds)";
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        return jdbc.sql("""
                        SELECT %1$s AS key, count(*) AS orders, coalesce(sum(items_total_minor), 0) AS items,
                               coalesce(sum(delivery_fee_minor), 0) AS fees
                          FROM retail_customer_orders
                         WHERE status = 'delivered' AND delivered_on BETWEEN :from AND :to%2$s
                           AND EXISTS (SELECT 1 FROM retail_sales s
                                        WHERE s.id = retail_customer_orders.sale_id AND s.status = 'completed')
                         GROUP BY 1
                         ORDER BY 1
                        """.formatted(key, where))
                .params(params)
                .query((rs, n) -> new ReportRow(
                        rs.getString("key"),
                        rs.getString("key"),
                        rs.getLong("orders"),
                        rs.getLong("items"),
                        rs.getLong("fees")))
                .list();
    }

    /** Cancelled orders by source in the window. */
    List<ReportRow> cancelledBySource(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        String where = branchIds == null ? "" : " AND branch_id IN (:branchIds)";
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        return jdbc.sql("""
                        SELECT source AS key, count(*) AS orders, coalesce(sum(items_total_minor), 0) AS items
                          FROM retail_customer_orders
                         WHERE status = 'cancelled' AND cancelled_on BETWEEN :from AND :to%s
                         GROUP BY 1
                         ORDER BY 1
                        """.formatted(where))
                .params(params)
                .query((rs, n) -> new ReportRow(
                        rs.getString("key"), rs.getString("key"), rs.getLong("orders"), rs.getLong("items"), 0))
                .list();
    }

    /** The cancelled orders of the window, newest first, at most {@code limit}. */
    List<CancelledOrder> cancelled(List<UUID> branchIds, LocalDate from, LocalDate to, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        String where = branchIds == null ? "" : " AND branch_id IN (:branchIds)";
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        params.put("limit", limit);
        return jdbc.sql("""
                        SELECT id, order_no, branch_id, cancelled_on, source, customer_name, items_total_minor, cancel_reason,
                               cancelled_by
                          FROM retail_customer_orders
                         WHERE status = 'cancelled' AND cancelled_on BETWEEN :from AND :to%s
                         ORDER BY cancelled_at DESC, id DESC
                         LIMIT :limit
                        """.formatted(where))
                .params(params)
                .query((rs, n) -> new CancelledOrder(
                        rs.getObject("id", UUID.class),
                        rs.getString("order_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("cancelled_on").toLocalDate(),
                        rs.getString("source"),
                        rs.getString("customer_name"),
                        rs.getLong("items_total_minor"),
                        rs.getString("cancel_reason"),
                        rs.getObject("cancelled_by", UUID.class)))
                .list();
    }

    private static Header header(ResultSet rs) throws SQLException {
        return new Header(
                rs.getObject("id", UUID.class),
                rs.getString("order_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getDate("ordered_on").toLocalDate(),
                rs.getString("source"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getString("customer_phone"),
                rs.getString("delivery_place"),
                localDate(rs, "delivery_date"),
                rs.getLong("delivery_fee_minor"),
                rs.getString("currency"),
                rs.getLong("items_total_minor"),
                rs.getString("notes"),
                rs.getString("status"),
                rs.getString("delivered_by"),
                instant(rs, "packed_at"),
                instant(rs, "sent_at"),
                instant(rs, "delivered_at"),
                localDate(rs, "delivered_on"),
                rs.getObject("sale_id", UUID.class),
                rs.getString("sale_no"),
                rs.getString("fee_method"),
                instant(rs, "cancelled_at"),
                rs.getString("cancel_reason"),
                localDate(rs, "fee_paid_on"),
                rs.getString("fee_paid_method"),
                rs.getString("sale_status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                rs.getInt("version"),
                rs.getInt("items_revision"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}
