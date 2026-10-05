package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
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
}
