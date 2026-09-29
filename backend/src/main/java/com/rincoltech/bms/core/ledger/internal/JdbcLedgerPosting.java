package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcLedgerPosting implements LedgerPosting {

    private final JdbcClient jdbc;
    private final Branches branches;
    private final TenantSequences sequences;

    JdbcLedgerPosting(JdbcClient jdbc, Branches branches, TenantSequences sequences) {
        this.jdbc = jdbc;
        this.branches = branches;
        this.sequences = sequences;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedEntry post(EntryRequest request) {
        EntryRules.check(request.lines());
        if (branches.findActive(request.branchId()).isEmpty()) {
            throw ApiException.rule("invalid_journal_line", "The entry's branch does not exist or is inactive.");
        }
        for (Line line : request.lines()) {
            checkAccount(line);
        }
        UUID periodId = openPeriod(request);
        String entryNo = "JE%08d".formatted(sequences.next("journal_no"));
        UUID entryId = UUID.randomUUID();
        UUID createdBy = CurrentPrincipal.get().map(Principal::userId).orElse(null);

        jdbc.sql("""
                        INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id, reference,
                                                     memo, source_module, source_type, source_id, idempotency_key, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        entryId,
                        request.branchId(),
                        entryNo,
                        request.entryDate(),
                        periodId,
                        request.reference(),
                        request.memo(),
                        request.sourceModule(),
                        request.sourceType(),
                        request.sourceId(),
                        request.idempotencyKey(),
                        createdBy)
                .update();

        List<Line> lines = request.lines();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            jdbc.sql("""
                            INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency,
                                                       subledger_type, subledger_id)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            entryId,
                            i + 1,
                            line.accountId(),
                            line.debit(),
                            line.credit(),
                            line.currency(),
                            line.subledgerType(),
                            line.subledgerId())
                    .update();
        }
        return new PostedEntry(entryId, entryNo, periodId);
    }

    private void checkAccount(Line line) {
        var account = jdbc.sql("SELECT currency, is_postable, is_active FROM gl_accounts WHERE id = ?")
                .param(line.accountId())
                .query((rs, n) -> new AccountRow(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)))
                .optional()
                .orElseThrow(
                        () -> ApiException.rule("account_not_postable", "Unknown account " + line.accountId() + "."));
        if (!account.postable() || !account.active()) {
            throw ApiException.rule("account_not_postable", "Account " + line.accountId() + " is not postable.");
        }
        if (!account.currency().equals(line.currency())) {
            throw ApiException.rule("currency_mismatch", "A line's currency must equal its account's currency.");
        }
    }

    private UUID openPeriod(EntryRequest request) {
        int year = request.entryDate().getYear();
        int month = request.entryDate().getMonthValue();
        // Periods are created on demand for the entry date's month (chapter 6 section 6.6.1).
        jdbc.sql("""
                        INSERT INTO gl_periods (id, tenant_id, year, month, status)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 'open')
                        ON CONFLICT (tenant_id, year, month) DO NOTHING
                        """).params(UUID.randomUUID(), year, month).update();
        var period = jdbc.sql("SELECT id, status FROM gl_periods WHERE year = ? AND month = ?")
                .params(year, month)
                .query((rs, n) -> new PeriodRow(rs.getObject(1, UUID.class), rs.getString(2)))
                .single();
        if (!"open".equals(period.status())) {
            throw ApiException.rule("period_closed", "The period " + year + "-" + month + " is closed.");
        }
        return period.id();
    }

    private record AccountRow(String currency, boolean postable, boolean active) {}

    private record PeriodRow(UUID id, String status) {}
}
