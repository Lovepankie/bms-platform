package com.rincoltech.bms.core.payments.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's payments system user (migration V83, ADR-035 decision 16): a user of kind {@code system} with no credentials,
 * no role and no session, created the first time the platform has to raise a request itself. It is the maker of the reversal
 * requests raised when the provider reports a booked payment reversed, so the maker is never the checker and a person
 * always decides. Every staff query filters on kind {@code staff}, so it is listed nowhere and can sign in nowhere.
 */
@Component
class SystemUser {

    static final String EMAIL = "payments-system@system.invalid";

    private final JdbcClient jdbc;

    SystemUser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The user's id; created on first use. Safe to call from two transactions at once (a unique index decides). */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID id() {
        jdbc.sql("""
                        INSERT INTO users (id, tenant_id, kind, full_name, email, status)
                        VALUES (?, current_setting('app.tenant_id')::uuid, 'system', 'Payments system', ?, 'active')
                        ON CONFLICT (tenant_id, lower(email)) WHERE kind = 'system' DO NOTHING
                        """).params(UUID.randomUUID(), EMAIL).update();
        return jdbc.sql("SELECT id FROM users WHERE kind = 'system' AND lower(email) = ?")
                .param(EMAIL)
                .query(UUID.class)
                .single();
    }
}
