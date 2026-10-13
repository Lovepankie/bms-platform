package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.NewLine;
import java.math.BigDecimal;
import java.sql.Date;
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

/** SQL for {@code retail_quotations} and their lines. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class QuotationsRepository {

    private static final String SELECT = """
            SELECT retail_quotations.id, retail_quotations.branch_id, retail_quotations.quote_date,
                   retail_quotations.valid_until, retail_quotations.customer_id, retail_quotations.buyer_name,
                   retail_quotations.buyer_contact, retail_quotations.price_list_id, retail_quotations.currency,
                   retail_quotations.total_minor, retail_quotations.notes, retail_quotations.status,
                   retail_quotations.converted_sale_id, retail_quotations.cancel_reason, retail_quotations.created_at,
                   retail_quotations.version,
                   (SELECT c.name FROM retail_customers c WHERE c.id = retail_quotations.customer_id) AS customer_name,
                   (SELECT c.contact FROM retail_customers c WHERE c.id = retail_quotations.customer_id) AS customer_contact,
                   (SELECT l.name FROM retail_price_lists l WHERE l.id = retail_quotations.price_list_id) AS price_list,
                   (SELECT s.sale_no FROM retail_sales s WHERE s.id = retail_quotations.converted_sale_id) AS sale_no,
                   (SELECT d.id FROM retail_documents d WHERE d.quotation_id = retail_quotations.id) AS document_id,
                   (SELECT d.number FROM retail_documents d WHERE d.quotation_id = retail_quotations.id) AS number
              FROM retail_quotations
            """;

    static final Search.Fields SEARCH = Search.Fields.of()
            .names("retail_quotations.buyer_name")
            .related(
                    "retail_quotations.customer_id",
                    "retail_customers c",
                    "c.id",
                    Search.Fields.of().names("c.name").phones("c.contact"))
            .related(
                    "retail_quotations.id",
                    "retail_documents d",
                    "d.quotation_id",
                    Search.Fields.of().codes("d.number"));

    private final JdbcClient jdbc;

    QuotationsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Header(
            UUID id,
            UUID branchId,
            LocalDate quoteDate,
            LocalDate validUntil,
            UUID customerId,
            String buyerName,
            String buyerContact,
            UUID priceListId,
            String priceList,
            String currency,
            long totalMinor,
            String notes,
            String status,
            UUID convertedSaleId,
            String saleNo,
            String cancelReason,
            Instant createdAt,
            int version,
            UUID documentId,
            String number) {}

    /** A stored line, as quoted: the quantity and price in the unit the line was entered in. */
    record StoredLine(
            int lineNo,
            UUID productId,
            String code,
            String description,
            String baseUnit,
            BigDecimal qty,
            long unitPriceMinor,
            long lineTotalMinor,
            UUID saleUnitId,
            String saleUnit,
            BigDecimal saleQty,
            Long saleUnitPriceMinor,
            BigDecimal unitFactor) {}

    void insert(
            UUID id,
            UUID branchId,
            LocalDate quoteDate,
            LocalDate validUntil,
            UUID customerId,
            String buyerName,
            String buyerContact,
            UUID priceListId,
            String currency,
            long totalMinor,
            String notes,
            UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_quotations (id, tenant_id, created_by, branch_id, quote_date, valid_until, customer_id,
                            buyer_name, buyer_contact, price_list_id, currency, total_minor, notes)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        by,
                        branchId,
                        Date.valueOf(quoteDate),
                        Date.valueOf(validUntil),
                        customerId,
                        buyerName,
                        buyerContact,
                        priceListId,
                        currency,
                        totalMinor,
                        notes)
                .update();
    }

    void insertLine(UUID quotationId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_quotation_lines (id, tenant_id, quotation_id, line_no, product_id, qty, unit_price_minor,
                            line_total_minor, sale_unit_id, sale_qty, sale_unit_price_minor, unit_factor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        l.id(),
                        quotationId,
                        l.lineNo(),
                        l.productId(),
                        l.qty(),
                        l.unitPriceMinor(),
                        l.lineTotalMinor(),
                        l.saleUnitId(),
                        l.saleQty(),
                        l.saleUnitPriceMinor(),
                        l.unitFactor())
                .update();
    }

    Optional<Header> find(UUID id, boolean lock) {
        // The row is locked on its own, then read with the subselects of the shared SELECT.
        if (lock) {
            jdbc.sql("SELECT id FROM retail_quotations WHERE id = ? FOR UPDATE")
                    .param(id)
                    .query(UUID.class)
                    .optional();
        }
        return jdbc.sql(SELECT + " WHERE retail_quotations.id = ?")
                .param(id)
                .query((rs, n) -> header(rs))
                .optional();
    }

    List<StoredLine> lines(UUID quotationId) {
        return jdbc.sql("""
                        SELECT l.line_no, l.product_id, p.code, p.description, bu.name AS base_unit, l.qty, l.unit_price_minor,
                               l.line_total_minor, l.sale_unit_id, su.name AS sale_unit, l.sale_qty, l.sale_unit_price_minor,
                               l.unit_factor
                          FROM retail_quotation_lines l
                          JOIN retail_products p ON p.id = l.product_id
                          JOIN retail_units bu ON bu.id = p.unit_id
                          LEFT JOIN retail_units su ON su.id = l.sale_unit_id
                         WHERE l.quotation_id = ? ORDER BY l.line_no
                        """)
                .param(quotationId)
                .query((rs, n) -> new StoredLine(
                        rs.getInt("line_no"),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("base_unit"),
                        rs.getBigDecimal("qty"),
                        rs.getLong("unit_price_minor"),
                        rs.getLong("line_total_minor"),
                        rs.getObject("sale_unit_id", UUID.class),
                        rs.getString("sale_unit"),
                        rs.getBigDecimal("sale_qty"),
                        rs.getObject("sale_unit_price_minor", Long.class),
                        rs.getBigDecimal("unit_factor")))
                .list();
    }

    List<Header> page(
            List<UUID> branchIds, String status, Search.Query search, Instant afterCreated, UUID afterId, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            sql.append(" AND retail_quotations.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (status != null) {
            sql.append(" AND retail_quotations.status = :status");
            params.put("status", status);
        }
        if (search != null) {
            Search.Match match = search.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(" AND (retail_quotations.created_at, retail_quotations.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY retail_quotations.created_at DESC, retail_quotations.id DESC LIMIT :limit");
        params.put("limit", limit);
        return new ArrayList<>(jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> header(rs))
                .list());
    }

    int markConverted(UUID id, UUID saleId, UUID by) {
        return jdbc.sql("""
                        UPDATE retail_quotations SET status = 'converted', converted_sale_id = ?, converted_at = now(),
                               converted_by = ?, version = version + 1
                         WHERE id = ? AND status = 'open'
                        """).params(saleId, by, id).update();
    }

    int markCancelled(UUID id, String reason, UUID by) {
        return jdbc.sql("""
                        UPDATE retail_quotations SET status = 'cancelled', cancelled_at = now(), cancelled_by = ?,
                               cancel_reason = ?, version = version + 1
                         WHERE id = ? AND status = 'open'
                        """).params(by, reason, id).update();
    }

    private static Header header(java.sql.ResultSet rs) throws java.sql.SQLException {
        String customerName = rs.getString("customer_name");
        String buyerName = rs.getString("buyer_name");
        return new Header(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getDate("quote_date").toLocalDate(),
                rs.getDate("valid_until").toLocalDate(),
                rs.getObject("customer_id", UUID.class),
                customerName != null ? customerName : buyerName,
                rs.getObject("customer_id", UUID.class) != null
                        ? rs.getString("customer_contact")
                        : rs.getString("buyer_contact"),
                rs.getObject("price_list_id", UUID.class),
                rs.getString("price_list"),
                rs.getString("currency"),
                rs.getLong("total_minor"),
                rs.getString("notes"),
                rs.getString("status"),
                rs.getObject("converted_sale_id", UUID.class),
                rs.getString("sale_no"),
                rs.getString("cancel_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("version"),
                rs.getObject("document_id", UUID.class),
                rs.getString("number"));
    }
}
