package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * The single place the tenant is bound to the database (ADR-003).
 *
 * <p>{@code prepareTransactionalConnection} runs inside {@code doBegin}, on the connection the new
 * transaction will use, after auto-commit is switched off and before any statement of the
 * transaction runs. Binding here, rather than in each service, means no code path can open a
 * transaction and forget the tenant: every transaction managed by Spring starts with
 * {@code set_config('app.tenant_id', <tenant>, true)}. The {@code true} makes the setting
 * transaction-local, so a pooled connection never carries it into the next transaction.
 *
 * <p>With no tenant on the thread nothing is bound, and every query on a tenant-owned table
 * raises (the RLS policy calls {@code current_setting('app.tenant_id')} without a default). A
 * statement run outside a transaction is never bound either, so it fails the same way. Both
 * failures are loud and neither returns rows.
 */
class TenantBindingTransactionManager extends JdbcTransactionManager {

    static final String BIND_SQL = "SELECT set_config('app.tenant_id', ?, true)";

    TenantBindingTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        Optional<UUID> tenant = TenantContext.current();
        if (tenant.isPresent()) {
            try (PreparedStatement ps = con.prepareStatement(BIND_SQL)) {
                ps.setString(1, tenant.get().toString());
                ps.execute();
            }
        }
    }
}
