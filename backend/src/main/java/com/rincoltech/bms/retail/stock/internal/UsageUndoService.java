package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.StockLedger.Recorded;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UndoRequest;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageUndone;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Undo a wrong "used in the shop" or damage entry (issue #818, FR-RET-88, ADR-123), the way a sale is voided
 * (FR-RET-04): the same permission ({@code retail.sale.void} at the entry's branch), a reason, every stock movement of
 * the entry put back by a {@code return} movement at the same unit cost, the shrinkage entry reversed, both on today's
 * business date so a closed day keeps its figures, and the audit entry {@code retail.usage.undone}. The entry itself is
 * append-only and never changes: one row of {@code retail_usage_undos} marks it undone with who, when and why, at most
 * once (409 {@code usage_undone}).
 */
@Service
class UsageUndoService {

    static final String USAGE_UNDO = "retail.usage_undo";
    static final String PERMISSION = "retail.sale.void";

    private final JdbcClient jdbc;
    private final StockLedger stock;
    private final RetailBooks books;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    UsageUndoService(
            JdbcClient jdbc,
            StockLedger stock,
            RetailBooks books,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.books = books;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    private record Report(UUID id, UUID branchId, String kind, boolean historical, UUID journalEntryId) {}

    @Transactional
    UsageUndone undo(UUID reportId, UndoRequest r) {
        Principal principal = CurrentPrincipal.require();
        // An entry outside the caller's branches is not found, as a sale outside them is.
        Report report = jdbc.sql("""
                        SELECT id, branch_id, kind, historical, journal_entry_id FROM retail_usage_reports WHERE id = ?
                        """)
                .param(reportId)
                .query((rs, n) -> new Report(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("kind"),
                        rs.getBoolean("historical"),
                        rs.getObject("journal_entry_id", UUID.class)))
                .optional()
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (report.historical()) {
            throw ApiException.rule(
                    "usage_imported",
                    "This entry was brought in from your old records, so it cannot be undone here. Correct the"
                            + " quantity on the stock list instead.");
        }
        String reason = r.reason().trim();
        UUID by = principal.userId();
        LocalDate today = clock.today(tenant.profile().timezone());
        UUID id = UUID.randomUUID();
        // The unique report key decides between two undos at once: the second waits for the first and inserts nothing.
        int inserted = jdbc.sql("""
                        INSERT INTO retail_usage_undos (id, tenant_id, report_id, undone_on, reason, undone_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, report_id) DO NOTHING
                        """)
                .params(id, reportId, Date.valueOf(today), reason, by)
                .update();
        if (inserted == 0) {
            throw undoneAlready();
        }
        List<Recorded> moved = stock.bySource(UsageService.USAGE, reportId);
        stock.record(
                today,
                moved.stream()
                        .map(m -> new Movement(
                                m.branchId(),
                                m.productId(),
                                "return",
                                m.qty().negate(),
                                m.unitCostMinor(),
                                USAGE_UNDO,
                                reportId,
                                m.sourceLineId(),
                                m.id(),
                                reason))
                        .toList());
        // The reversal names the entry it reverses (journal_entries.reverses_entry_id), so the undo row needs no link.
        if (report.journalEntryId() != null) {
            books.reverse(
                    report.journalEntryId(),
                    today,
                    "Undo usage " + reportId.toString().substring(0, 8),
                    "retail.usage_undo:" + reportId);
        }
        audit.record(new AuditLog.Entry(
                "retail.usage.undone",
                UsageService.USAGE,
                reportId,
                report.branchId(),
                Map.of("status", "recorded"),
                Map.of("status", "undone", "reason", reason)));
        return jdbc.sql("""
                        SELECT created_at FROM retail_usage_undos WHERE id = ?
                        """)
                .param(id)
                .query((rs, n) -> new UsageUndone(
                        reportId,
                        report.branchId(),
                        report.kind(),
                        moved.size(),
                        today,
                        rs.getTimestamp("created_at").toInstant(),
                        by,
                        reason))
                .single();
    }

    static ApiException undoneAlready() {
        return new ApiException(
                HttpStatus.CONFLICT, "usage_undone", "Entry undone", "This entry has been undone already.");
    }
}
