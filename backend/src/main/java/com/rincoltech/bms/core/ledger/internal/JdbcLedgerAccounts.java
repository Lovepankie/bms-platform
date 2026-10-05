package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
    public Map<UUID, Long> balanceByBranch(String systemKey, LocalDate asOf) {
        Map<UUID, Long> balances = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT e.branch_id, sum(l.debit - l.credit) AS balance
                          FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE a.system_key = ? AND e.entry_date <= ?
                         GROUP BY e.branch_id
                        """)
                .params(systemKey, Date.valueOf(asOf))
                .query((rs, n) -> balances.put(rs.getObject(1, UUID.class), rs.getLong(2)))
                .list();
        return balances;
    }
}
