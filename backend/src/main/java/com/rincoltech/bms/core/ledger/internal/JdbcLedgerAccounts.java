package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcLedgerAccounts implements LedgerAccounts {

    private final JdbcClient jdbc;

    JdbcLedgerAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> bySystemKey(String systemKey) {
        return jdbc.sql("""
                        SELECT id, code, currency FROM gl_accounts
                         WHERE system_key = ? AND is_postable AND is_active
                        """)
                .param(systemKey)
                .query((rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> byRef(String ref) {
        if (!ref.startsWith(ACCOUNT_REF)) {
            return bySystemKey(ref);
        }
        return jdbc.sql("SELECT id, code, currency FROM gl_accounts WHERE id = ? AND is_postable AND is_active")
                .param(UUID.fromString(ref.substring(ACCOUNT_REF.length())))
                .query((rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional();
    }

    static final String ACCOUNT_REF = "gl:";

    @Override
    @Transactional(readOnly = true)
    public Optional<String> debitedAccountRef(UUID entryId) {
        java.util.List<UUID> debited = jdbc.sql("SELECT account_id FROM journal_lines WHERE entry_id = ? AND debit > 0")
                .param(entryId)
                .query(UUID.class)
                .list();
        return debited.size() == 1 ? Optional.of(ACCOUNT_REF + debited.getFirst()) : Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> balanceByBranch(String ref, LocalDate asOf) {
        Map<UUID, Long> balances = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT e.branch_id, sum(l.debit - l.credit) AS balance
                          FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE (a.system_key = ? OR a.id = ?) AND e.entry_date <= ?
                         GROUP BY e.branch_id
                        """)
                .params(refKey(ref), refId(ref), Date.valueOf(asOf))
                .query((rs, n) -> balances.put(rs.getObject(1, UUID.class), rs.getLong(2)))
                .list();
        return balances;
    }

    @Override
    @Transactional(readOnly = true)
    public long balanceBySource(String systemKey, String sourceModule, String sourceType) {
        return jdbc.sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0)
                          FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE a.system_key = ? AND e.source_module = ? AND e.source_type = ?
                        """)
                .params(systemKey, sourceModule, sourceType)
                .query(Long.class)
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, Long> movementByDay(String ref, UUID branchId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> movement = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT e.entry_date, sum(l.debit - l.credit) AS movement
                          FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE (a.system_key = ? OR a.id = ?) AND e.branch_id = ? AND e.entry_date BETWEEN ? AND ?
                         GROUP BY e.entry_date ORDER BY e.entry_date
                        """)
                .params(refKey(ref), refId(ref), branchId, Date.valueOf(from), Date.valueOf(to))
                .query((rs, n) -> movement.put(rs.getDate(1).toLocalDate(), rs.getLong(2)))
                .list();
        return movement;
    }

    // JUSTIFICATION-A3: issue #134, the two reads of the ledger the import reconciliation checks against.
    @Override
    @Transactional(readOnly = true)
    public List<EntryLine> lines(Collection<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT e.id, e.branch_id, e.entry_date, e.source_module, e.source_type, a.system_key, l.debit,
                               l.credit, e.entry_no
                          FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.entry_id = ANY(?)
                         ORDER BY e.entry_no, l.line_no
                        """)
                .param(entryIds.toArray(UUID[]::new))
                .query((rs, n) -> new EntryLine(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getDate(3).toLocalDate(),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getLong(7),
                        rs.getLong(8),
                        rs.getString(9)))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public TrialBalanceCheck trialBalanceCheck() {
        return jdbc.sql("""
                        SELECT coalesce(sum(t.debit), 0), coalesce(sum(t.credit), 0),
                               count(*) FILTER (WHERE t.debit <> t.credit)
                          FROM (SELECT l.entry_id, sum(l.debit) AS debit, sum(l.credit) AS credit
                                  FROM journal_lines l GROUP BY l.entry_id) t
                        """)
                .query((rs, n) -> new TrialBalanceCheck(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single();
    }

    /** A {@link #byRef} reference split into its {@code system_key} or its account id; the other is null. */
    private static String refKey(String ref) {
        return ref.startsWith(ACCOUNT_REF) ? null : ref;
    }

    private static UUID refId(String ref) {
        return ref.startsWith(ACCOUNT_REF) ? UUID.fromString(ref.substring(ACCOUNT_REF.length())) : null;
    }
}
