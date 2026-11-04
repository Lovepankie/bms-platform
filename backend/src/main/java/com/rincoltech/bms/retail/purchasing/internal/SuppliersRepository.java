package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Supplier;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierDetails;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads of a supplier's page and of the supplier list (issue #820, ADR-125): the search by name, type or phone, the last
 * delivery date and the supplier's deliveries. A delivery is a restock (Buy stock) naming the supplier; what a reader may
 * see of them follows the rule of the restock list ({@link PurchasingRepository#page}), so the last delivery date never
 * tells of a restock the list would not show.
 */
@Repository
class SuppliersRepository {

    static final String COLUMNS = "s.id, s.name, s.contact, s.active, s.created_at, s.version, s.email, s.address,"
            + " s.country, s.website, s.payment_terms, s.bank_account, s.supplier_type, s.notes";

    private final JdbcClient jdbc;

    SuppliersRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whose restocks a reader may list: {@code branches} null for every branch, otherwise the restocks with a purchase
     * movement at one of them; {@code createdBy} null, or only the restocks that person recorded (ADR-055).
     */
    record Scope(List<UUID> branches, UUID createdBy) {

        boolean none() {
            return branches != null && branches.isEmpty();
        }
    }

    /** One of a supplier's deliveries as its page lists it: no amount; the restock itself opens with them. */
    record DeliveryRow(
            UUID id, String purchaseNo, LocalDate purchasedOn, Instant createdAt, boolean voided, int lines) {}

    /** The supplier with its last delivery date in the reader's scope. */
    Optional<Supplier> supplier(UUID id, Scope scope) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        String sql = "SELECT " + COLUMNS + ", " + lastDelivery(scope, params)
                + " AS last_delivery_on FROM retail_suppliers s WHERE s.id = :id";
        return jdbc.sql(sql).params(params).query(SuppliersRepository::row).optional();
    }

    /**
     * Every supplier, or those a search finds by name, type or phone (the one search of ADR-039), each with its last
     * delivery date in the reader's scope; ordered by how well it matches, then by name.
     */
    List<Supplier> suppliers(Search.Query search, Scope scope) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + ", " + lastDelivery(scope, params)
                + " AS last_delivery_on FROM retail_suppliers s");
        if (search == null) {
            sql.append(" ORDER BY lower(s.name), s.id");
        } else {
            Search.Match match = search.match(
                    Search.Fields.of().names("s.name", "s.supplier_type").phones("s.contact"));
            params.putAll(match.params());
            sql.append(" WHERE ")
                    .append(match.where())
                    .append(" ORDER BY ")
                    .append(match.rank())
                    .append(", lower(s.name), s.id");
        }
        return jdbc.sql(sql.toString())
                .params(params)
                .query(SuppliersRepository::row)
                .list();
    }

    /** The supplier types already used, the most used first, for the form's suggestions. */
    List<String> types() {
        return jdbc.sql("""
                        SELECT min(btrim(supplier_type)) FROM retail_suppliers
                         WHERE supplier_type IS NOT NULL AND btrim(supplier_type) <> ''
                         GROUP BY lower(btrim(supplier_type))
                         ORDER BY count(*) DESC, lower(btrim(supplier_type))
                         LIMIT 50
                        """).query(String.class).list();
    }

    /**
     * A supplier's restocks in the reader's scope, the newest day first, one page after the keyset
     * {@code (purchased_on, created_at, id)}; {@code lines} counts only the lines that added stock in scope.
     */
    List<DeliveryRow> deliveries(UUID supplierId, Scope scope, DeliveryKey after, int limit) {
        if (scope.none()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("supplierId", supplierId);
        params.put("limit", limit);
        String inScope = inScope(scope, params);
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.purchase_no, p.purchased_on, p.created_at,
                       EXISTS (SELECT 1 FROM retail_purchase_voids v WHERE v.purchase_id = p.id) AS voided,
                       (SELECT count(*) FROM retail_purchase_lines l WHERE l.purchase_id = p.id
                """);
        if (scope.branches() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM retail_stock_movements m WHERE m.source_type = 'retail.purchase'"
                            + " AND m.kind = 'purchase' AND m.source_line_id = l.id")
                    .append(inScope)
                    .append(")");
        }
        sql.append(") AS lines FROM retail_purchases p WHERE p.supplier_id = :supplierId")
                .append(visible(scope, params, inScope));
        if (after != null) {
            sql.append(" AND (p.purchased_on, p.created_at, p.id) < (:afterOn, :afterCreated, :afterId)");
            params.put("afterOn", Date.valueOf(after.on()));
            params.put("afterCreated", Timestamp.from(after.createdAt()));
            params.put("afterId", after.id());
        }
        sql.append(" ORDER BY p.purchased_on DESC, p.created_at DESC, p.id DESC LIMIT :limit");
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new DeliveryRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("purchase_no"),
                        rs.getDate("purchased_on").toLocalDate(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("voided"),
                        rs.getInt("lines")))
                .list();
    }

    /** Where the next page of deliveries starts: the last row of the page before. */
    record DeliveryKey(LocalDate on, Instant createdAt, UUID id) {}

    static Supplier row(ResultSet rs, int n) throws SQLException {
        Date last = rs.getDate("last_delivery_on");
        return new Supplier(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("contact"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("version"),
                new SupplierDetails(
                        rs.getString("email"),
                        rs.getString("address"),
                        rs.getString("country"),
                        rs.getString("website"),
                        rs.getString("payment_terms"),
                        rs.getString("bank_account"),
                        rs.getString("supplier_type"),
                        rs.getString("notes")),
                last == null ? null : last.toLocalDate());
    }

    /** The day of the newest restock of {@code s} that is not voided, in the scope; null when it has none. */
    private static String lastDelivery(Scope scope, Map<String, Object> params) {
        if (scope.none()) {
            return "NULL::date";
        }
        return "(SELECT max(p.purchased_on) FROM retail_purchases p WHERE p.supplier_id = s.id"
                + " AND NOT EXISTS (SELECT 1 FROM retail_purchase_voids v WHERE v.purchase_id = p.id)"
                + visible(scope, params, inScope(scope, params)) + ")";
    }

    private static String inScope(Scope scope, Map<String, Object> params) {
        if (scope.branches() == null) {
            return "";
        }
        params.put("scopeBranches", scope.branches());
        return " AND m.branch_id IN (:scopeBranches)";
    }

    /** The conditions on {@code p} that keep a restock in the reader's scope. */
    private static String visible(Scope scope, Map<String, Object> params, String inScope) {
        StringBuilder sql = new StringBuilder();
        if (scope.createdBy() != null) {
            sql.append(" AND p.created_by = :scopeCreatedBy");
            params.put("scopeCreatedBy", scope.createdBy());
        }
        if (scope.branches() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM retail_stock_movements m WHERE m.source_type = 'retail.purchase'"
                            + " AND m.kind = 'purchase' AND m.source_id = p.id")
                    .append(inScope)
                    .append(")");
        }
        return sql.toString();
    }
}
