package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.retail.stock.internal.TransferApi.Transfer;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferLine;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code retail_transfers} and their lines (FR-RET-16). Row-level security supplies the tenant. */
@Repository
class TransferRepository {

    private static final String SELECT = """
            SELECT id, from_branch_id, to_branch_id, transfer_date, note, status, currency, cost_total_minor,
                   out_entry_id, in_entry_id, created_at, created_by, voided_at, voided_by, void_reason
              FROM retail_transfers""";

    private final JdbcClient jdbc;

    TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A transfer with its journal entry ids, which the API does not show. */
    record Header(Transfer transfer, UUID outEntryId, UUID inEntryId) {}

    record NewLine(UUID id, int lineNo, UUID productId, BigDecimal qty, long unitCostMinor, long lineCostMinor) {}

    void insert(
            UUID id,
            UUID from,
            UUID to,
            LocalDate on,
            String note,
            String currency,
            long costTotal,
            UUID outEntry,
            UUID inEntry,
            UUID by,
            List<NewLine> lines) {
        jdbc.sql("""
                        INSERT INTO retail_transfers (id, tenant_id, from_branch_id, to_branch_id, transfer_date, note, currency,
                            cost_total_minor, out_entry_id, in_entry_id, status, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, 'completed', ?)
                        """)
                .params(id, from, to, Date.valueOf(on), note, currency, costTotal, outEntry, inEntry, by)
                .update();
        for (NewLine l : lines) {
            jdbc.sql("""
                            INSERT INTO retail_transfer_lines (id, tenant_id, transfer_id, line_no, product_id, qty,
                                unit_cost_minor, line_cost_minor)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(l.id(), id, l.lineNo(), l.productId(), l.qty(), l.unitCostMinor(), l.lineCostMinor())
                    .update();
        }
    }

    /** @param lock take the transfer row's lock for the rest of the transaction (a void) */
    Optional<Header> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT + " WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query((rs, n) -> new Header(
                        header(rs), rs.getObject("out_entry_id", UUID.class), rs.getObject("in_entry_id", UUID.class)))
                .optional()
                .map(h -> new Header(withLines(h.transfer()), h.outEntryId(), h.inEntryId()));
    }

    /**
     * Newest first. {@code branchIds} null means every branch; otherwise a transfer is listed when
     * either its source or its destination is one of them.
     */
    List<Transfer> page(
            List<UUID> branchIds,
            UUID productId,
            LocalDate from,
            LocalDate to,
            Instant beforeCreated,
            UUID beforeId,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT + " t WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND (from_branch_id IN (:branchIds) OR to_branch_id IN (:branchIds))");
            params.put("branchIds", branchIds);
        }
        if (productId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM retail_transfer_lines l"
                    + " WHERE l.transfer_id = t.id AND l.product_id = :productId)");
            params.put("productId", productId);
        }
        if (from != null) {
            sql.append(" AND transfer_date >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND transfer_date <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (beforeCreated != null) {
            sql.append(" AND (created_at, id) < (:beforeCreated, :beforeId)");
            params.put("beforeCreated", Timestamp.from(beforeCreated));
            params.put("beforeId", beforeId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        params.put("limit", limit);
        return withLines(jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> header(rs))
                .list());
    }

    void markVoided(UUID id, UUID by, String reason) {
        jdbc.sql("""
                        UPDATE retail_transfers SET status = 'voided', voided_at = now(), voided_by = ?, void_reason = ?
                         WHERE id = ?
                        """).params(by, reason, id).update();
    }

    private Transfer withLines(Transfer t) {
        return withLines(List.of(t)).getFirst();
    }

    /** The lines of every transfer of a page in one statement, not one per transfer (issue #107). */
    private List<Transfer> withLines(List<Transfer> transfers) {
        if (transfers.isEmpty()) {
            return transfers;
        }
        Map<UUID, List<TransferLine>> byTransfer = new HashMap<>();
        jdbc.sql("""
                        SELECT l.transfer_id, l.line_no, l.product_id, p.code, p.description, l.qty, l.unit_cost_minor,
                               l.line_cost_minor
                          FROM retail_transfer_lines l JOIN retail_products p ON p.id = l.product_id
                         WHERE l.transfer_id IN (:ids) ORDER BY l.transfer_id, l.line_no
                        """)
                .param("ids", transfers.stream().map(Transfer::id).toList())
                .query(rs -> {
                    byTransfer
                            .computeIfAbsent(rs.getObject("transfer_id", UUID.class), k -> new ArrayList<>())
                            .add(new TransferLine(
                                    rs.getInt("line_no"),
                                    rs.getObject("product_id", UUID.class),
                                    rs.getString("code"),
                                    rs.getString("description"),
                                    rs.getBigDecimal("qty").toPlainString(),
                                    rs.getLong("unit_cost_minor"),
                                    rs.getLong("line_cost_minor")));
                });
        return transfers.stream()
                .map(t -> withLines(t, byTransfer.getOrDefault(t.id(), new ArrayList<>())))
                .toList();
    }

    private static Transfer withLines(Transfer t, List<TransferLine> lines) {
        return new Transfer(
                t.id(),
                t.fromBranchId(),
                t.toBranchId(),
                t.transferDate(),
                t.note(),
                t.status(),
                t.currency(),
                t.costTotalMinor(),
                lines,
                t.createdAt(),
                t.createdBy(),
                t.voidedAt(),
                t.voidedBy(),
                t.voidReason());
    }

    private static Transfer header(ResultSet rs) throws SQLException {
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getObject("from_branch_id", UUID.class),
                rs.getObject("to_branch_id", UUID.class),
                rs.getDate("transfer_date").toLocalDate(),
                rs.getString("note"),
                rs.getString("status"),
                rs.getString("currency"),
                rs.getLong("cost_total_minor"),
                List.of(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                voidedAt == null ? null : voidedAt.toInstant(),
                rs.getObject("voided_by", UUID.class),
                rs.getString("void_reason"));
    }
}
