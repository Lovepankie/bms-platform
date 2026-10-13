package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Search;
import java.sql.Date;
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
 * SQL for the numbered series and the issued documents ({@code retail_document_series},
 * {@code retail_document_prefixes}, {@code retail_documents}). Row-level security supplies the tenant predicate
 * (ADR-003). A document is read with the state of its source joined in, because the document itself is never
 * updated: a voided sale, a cancelled transfer or a cancelled quotation is what makes it void.
 */
@Repository
class DocumentsRepository {

    static final String SUBJECT = "retail.document";

    private static final String SELECT = """
            SELECT retail_documents.id, retail_documents.type, retail_documents.number, retail_documents.seq_no,
                   retail_documents.branch_id, retail_documents.issue_date, retail_documents.sale_id,
                   retail_documents.transfer_id, retail_documents.quotation_id, retail_documents.source_document_id,
                   retail_documents.buyer_name, retail_documents.currency, retail_documents.total_minor,
                   retail_documents.created_at, retail_documents.created_by,
                   CASE WHEN s.status = 'voided' OR t.status IN ('voided', 'cancelled') OR q.status = 'cancelled'
                        THEN 'void' ELSE 'issued' END AS status,
                   CASE WHEN s.status = 'voided' THEN 'its sale was voided'
                        WHEN t.status IN ('voided', 'cancelled') THEN 'its stock move was cancelled'
                        WHEN q.status = 'cancelled' THEN 'the quotation was cancelled' END AS void_reason,
                   q.valid_until, q.status AS quotation_status,
                   (SELECT g.id FROM documents g
                     WHERE g.subject_type = 'retail.document' AND g.subject_id = retail_documents.id
                       AND g.doc_type = retail_documents.type
                       AND g.doc_number = CASE WHEN s.status = 'voided' OR t.status IN ('voided', 'cancelled') OR q.status = 'cancelled'
                                               THEN retail_documents.number || ' VOID' ELSE retail_documents.number END
                     ORDER BY g.created_at LIMIT 1) AS pdf_id
              FROM retail_documents
              LEFT JOIN retail_sales s ON s.id = retail_documents.sale_id
              LEFT JOIN retail_transfers t ON t.id = retail_documents.transfer_id
              LEFT JOIN retail_quotations q ON q.id = retail_documents.quotation_id
            """;

    /** The fields the list searches: the buyer's name and the document number. */
    static final Search.Fields SEARCH =
            Search.Fields.of().names("retail_documents.buyer_name").codes("retail_documents.number");

    private final JdbcClient jdbc;

    DocumentsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored document row; {@code status} and the quotation fields come from the source. */
    record Doc(
            UUID id,
            String type,
            String number,
            int seqNo,
            UUID branchId,
            LocalDate issueDate,
            UUID saleId,
            UUID transferId,
            UUID quotationId,
            UUID sourceDocumentId,
            String buyerName,
            String currency,
            Long totalMinor,
            Instant createdAt,
            UUID createdBy,
            String status,
            String voidReason,
            LocalDate validUntil,
            String quotationStatus,
            UUID pdfId) {}

    /** The counters of one series, for the series screen. */
    record SeriesRow(String type, UUID branchId, int lastNo) {}

    // ---- Prefixes and counters -------------------------------------------------------------------

    Map<String, String> prefixes() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT type, prefix FROM retail_document_prefixes").query(rs -> {
            out.put(rs.getString("type"), rs.getString("prefix"));
        });
        return out;
    }

    /** Sets the prefix of a type; the new value is used by documents issued from now on. Returns the one it replaced. */
    Optional<String> setPrefix(String type, String prefix, UUID by) {
        Optional<String> before = jdbc.sql("SELECT prefix FROM retail_document_prefixes WHERE type = ? FOR UPDATE")
                .param(type)
                .query(String.class)
                .optional();
        // One statement, so two first changes of a type cannot both insert: the second updates what the first wrote.
        jdbc.sql("""
                        INSERT INTO retail_document_prefixes (tenant_id, type, prefix, changed_by)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        ON CONFLICT (tenant_id, type) DO UPDATE
                           SET prefix = EXCLUDED.prefix, changed_by = EXCLUDED.changed_by, changed_at = now()
                        """).params(type, prefix, by).update();
        return before;
    }

    /**
     * Locks the counter row of a series ({@code SELECT ... FOR UPDATE}), creating it at zero first. Everyone who is
     * about to issue in this series queues here, for the length of the issuing transaction only; nothing slow runs
     * under it. Returns the last number issued.
     */
    int lockSeries(String type, UUID branchId) {
        jdbc.sql("""
                        INSERT INTO retail_document_series (tenant_id, type, branch_id, last_no)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, 0)
                        ON CONFLICT (tenant_id, type, branch_id) DO NOTHING
                        """).params(type, branchId).update();
        return jdbc.sql("SELECT last_no FROM retail_document_series WHERE type = ? AND branch_id = ? FOR UPDATE")
                .params(type, branchId)
                .query(Integer.class)
                .single();
    }

    /** Takes the next number of a series whose row this transaction has locked with {@link #lockSeries}. */
    int bump(String type, UUID branchId) {
        return jdbc.sql("""
                        UPDATE retail_document_series SET last_no = last_no + 1
                         WHERE type = ? AND branch_id = ? RETURNING last_no
                        """).params(type, branchId).query(Integer.class).single();
    }

    List<SeriesRow> series(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        String filter = branchIds == null ? "" : " WHERE branch_id IN (:branchIds)";
        var spec = jdbc.sql("SELECT type, branch_id, last_no FROM retail_document_series" + filter);
        if (branchIds != null) {
            spec = spec.param("branchIds", branchIds);
        }
        return spec.query((rs, n) -> new SeriesRow(
                        rs.getString("type"), rs.getObject("branch_id", UUID.class), rs.getInt("last_no")))
                .list();
    }

    /** The document number a stored file was recorded under, or empty for a file that is not of this module. */
    Optional<String> storedNumber(UUID storedDocumentId) {
        return jdbc.sql("SELECT doc_number FROM documents WHERE id = ? AND subject_type = 'retail.document'")
                .param(storedDocumentId)
                .query(String.class)
                .optional();
    }

    /**
     * Takes a share lock on the sale's row, so a void that is not yet committed finishes first and one that starts after
     * waits for the document to commit. Returns the status read once the lock is held.
     */
    Optional<String> lockSaleShared(UUID saleId) {
        return jdbc.sql("SELECT status FROM retail_sales WHERE id = ? FOR SHARE")
                .param(saleId)
                .query(String.class)
                .optional();
    }

    String branchCode(UUID branchId) {
        return jdbc.sql("SELECT code FROM branches WHERE id = ?")
                .param(branchId)
                .query(String.class)
                .single();
    }

    // ---- Documents ---------------------------------------------------------------------------------

    void insert(
            UUID id,
            String type,
            String number,
            int seqNo,
            UUID branchId,
            LocalDate issueDate,
            UUID saleId,
            UUID transferId,
            UUID quotationId,
            UUID sourceDocumentId,
            String buyerName,
            String currency,
            Long totalMinor,
            String snapshotJson,
            UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_documents (id, tenant_id, created_by, type, number, seq_no, branch_id, issue_date,
                            sale_id, transfer_id, quotation_id, source_document_id, buyer_name, currency, total_minor, snapshot)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :by, :type, :number, :seq, :branch, :date,
                            :sale, :transfer, :quotation, :source, :buyer, :currency, :total, CAST(:snapshot AS jsonb))
                        """)
                .param("id", id)
                .param("by", by)
                .param("type", type)
                .param("number", number)
                .param("seq", seqNo)
                .param("branch", branchId)
                .param("date", Date.valueOf(issueDate))
                .param("sale", saleId)
                .param("transfer", transferId)
                .param("quotation", quotationId)
                .param("source", sourceDocumentId)
                .param("buyer", buyerName)
                .param("currency", currency)
                .param("total", totalMinor)
                .param("snapshot", snapshotJson)
                .update();
    }

    Optional<Doc> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE retail_documents.id = ?")
                .param(id)
                .query((rs, n) -> doc(rs))
                .optional();
    }

    Optional<Doc> forSale(UUID saleId, String type) {
        return jdbc.sql(SELECT + " WHERE retail_documents.sale_id = ? AND retail_documents.type = ?")
                .params(saleId, type)
                .query((rs, n) -> doc(rs))
                .optional();
    }

    Optional<Doc> forTransfer(UUID transferId, String type) {
        return jdbc.sql(SELECT + " WHERE retail_documents.transfer_id = ? AND retail_documents.type = ?")
                .params(transferId, type)
                .query((rs, n) -> doc(rs))
                .optional();
    }

    Optional<Doc> forQuotation(UUID quotationId) {
        return jdbc.sql(SELECT + " WHERE retail_documents.quotation_id = ?")
                .param(quotationId)
                .query((rs, n) -> doc(rs))
                .optional();
    }

    List<Doc> listForSale(UUID saleId) {
        return jdbc.sql(
                        SELECT
                                + " WHERE retail_documents.sale_id = ? ORDER BY retail_documents.created_at, retail_documents.id")
                .param(saleId)
                .query((rs, n) -> doc(rs))
                .list();
    }

    /** The snapshot a document was issued with, as the JSON text stored. */
    Optional<String> snapshot(UUID id) {
        return jdbc.sql("SELECT snapshot::text FROM retail_documents WHERE id = ?")
                .param(id)
                .query(String.class)
                .optional();
    }

    List<Doc> page(
            List<UUID> branchIds,
            String type,
            LocalDate from,
            LocalDate to,
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
            sql.append(" AND retail_documents.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (type != null) {
            sql.append(" AND retail_documents.type = :type");
            params.put("type", type);
        }
        if (from != null) {
            sql.append(" AND retail_documents.issue_date >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND retail_documents.issue_date <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (search != null) {
            Search.Match match = search.match(SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (afterCreated != null) {
            sql.append(" AND (retail_documents.created_at, retail_documents.id) < (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY retail_documents.created_at DESC, retail_documents.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> doc(rs)).list();
    }

    // ---- What a snapshot is made from ------------------------------------------------------------------
    // JUSTIFICATION-A3: the reads a snapshot needs (branch, buyer, user, base units, transfer); no other class reads
    // them.

    /** A branch as a document prints it. */
    record BranchInfo(String name, String location, String code) {}

    Optional<BranchInfo> branch(UUID branchId) {
        return jdbc.sql("SELECT name, location, code FROM branches WHERE id = ?")
                .param(branchId)
                .query((rs, n) -> new BranchInfo(rs.getString("name"), rs.getString("location"), rs.getString("code")))
                .optional();
    }

    /** A credit buyer's name and contact as a document prints them. */
    record BuyerInfo(String name, String contact) {}

    Optional<BuyerInfo> customer(UUID customerId) {
        return jdbc.sql("SELECT name, contact FROM retail_customers WHERE id = ?")
                .param(customerId)
                .query((rs, n) -> new BuyerInfo(rs.getString("name"), rs.getString("contact")))
                .optional();
    }

    String userName(UUID userId) {
        if (userId == null) {
            return "the system";
        }
        return jdbc.sql("SELECT full_name FROM users WHERE id = ?")
                .param(userId)
                .query(String.class)
                .optional()
                .orElse("a former user");
    }

    /** The base unit of each product, by product id. */
    Map<UUID, String> baseUnits(List<UUID> productIds) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        jdbc.sql(
                        "SELECT p.id, u.name FROM retail_products p JOIN retail_units u ON u.id = p.unit_id WHERE p.id IN (:ids)")
                .param("ids", productIds)
                .query(rs -> {
                    out.put(rs.getObject("id", UUID.class), rs.getString("name"));
                });
        return out;
    }

    /** A stock transfer as a delivery note needs it. */
    record TransferInfo(UUID id, UUID fromBranchId, UUID toBranchId, LocalDate date, String note, String status) {}

    Optional<TransferInfo> transfer(UUID id) {
        return jdbc.sql(
                        "SELECT id, from_branch_id, to_branch_id, transfer_date, note, status FROM retail_transfers WHERE id = ?")
                .param(id)
                .query((rs, n) -> new TransferInfo(
                        rs.getObject("id", UUID.class),
                        rs.getObject("from_branch_id", UUID.class),
                        rs.getObject("to_branch_id", UUID.class),
                        rs.getDate("transfer_date").toLocalDate(),
                        rs.getString("note"),
                        rs.getString("status")))
                .optional();
    }

    record TransferLineInfo(int lineNo, String code, String description, java.math.BigDecimal qty, String unit) {}

    List<TransferLineInfo> transferLines(UUID transferId) {
        return jdbc.sql("""
                        SELECT l.line_no, p.code, p.description, l.qty, u.name AS unit
                          FROM retail_transfer_lines l
                          JOIN retail_products p ON p.id = l.product_id
                          JOIN retail_units u ON u.id = p.unit_id
                         WHERE l.transfer_id = ? ORDER BY l.line_no
                        """)
                .param(transferId)
                .query((rs, n) -> new TransferLineInfo(
                        rs.getInt("line_no"),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getBigDecimal("qty"),
                        rs.getString("unit")))
                .list();
    }

    private static Doc doc(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date valid = rs.getDate("valid_until");
        return new Doc(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("number"),
                rs.getInt("seq_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getDate("issue_date").toLocalDate(),
                rs.getObject("sale_id", UUID.class),
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("quotation_id", UUID.class),
                rs.getObject("source_document_id", UUID.class),
                rs.getString("buyer_name"),
                rs.getString("currency"),
                rs.getObject("total_minor", Long.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                rs.getString("status"),
                rs.getString("void_reason"),
                valid == null ? null : valid.toLocalDate(),
                rs.getString("quotation_status"),
                rs.getObject("pdf_id", UUID.class));
    }
}
