package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.PaymentMethods;
import com.rincoltech.bms.kernel.ApiException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PaymentMethods} on the {@code payment_methods} table (chapter 6). */
@Service
class JdbcPaymentMethods implements PaymentMethods {

    /** ADR-026: where an unmapped method posts, by the seeded account's {@code system_key}. */
    static final Map<String, String> DEFAULT_ACCOUNTS = Map.of(
            "cash", "cash_on_hand",
            "bank", "bank",
            "mtn_momo", "mobile_money_mtn",
            "airtel_money", "mobile_money_airtel");

    private final JdbcClient jdbc;
    private final LedgerAccounts accounts;

    JdbcPaymentMethods(JdbcClient jdbc, LedgerAccounts accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    record Mapping(UUID accountId, boolean active) {}

    @Override
    @Transactional(readOnly = true)
    public String accountRef(String methodKey, UUID branchId) {
        Optional<Mapping> mapping = jdbc.sql("""
                        SELECT gl_account_id, is_active FROM payment_methods
                         WHERE method_key = :key AND (branch_id = CAST(:branch AS uuid) OR branch_id IS NULL)
                         ORDER BY branch_id NULLS LAST LIMIT 1
                        """)
                .param("key", methodKey)
                .param("branch", branchId)
                .query((rs, n) -> new Mapping(rs.getObject(1, UUID.class), rs.getBoolean(2)))
                .optional();
        String ref = mapping.map(m -> m.active() ? JdbcLedgerAccounts.ACCOUNT_REF + m.accountId() : null)
                .orElseGet(() -> mapping.isPresent() ? null : DEFAULT_ACCOUNTS.get(methodKey));
        if (ref == null || accounts.byRef(ref).isEmpty()) {
            throw ApiException.rule(
                    "payment_method_unmapped", "The payment method " + methodKey + " has no active ledger account.");
        }
        return ref;
    }
}
