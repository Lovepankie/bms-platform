package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the transaction hook that binds the tenant (ADR-003; chapter 15 section 15.4 items 3
 * and 4). Uses ONE physical connection for every transaction, like a pooled connection handed
 * from request to request, connected as bms_app.
 */
class TenantBindingIT {

    SingleConnectionDataSource connection;
    JdbcClient jdbc;
    TransactionTemplate tx;
    TestDatabase.Fixture tenant;

    @BeforeEach
    void setUp() {
        tenant = TestDatabase.tenant("binding", true);
        connection = new SingleConnectionDataSource(TestDatabase.url(), "bms_app", TestDatabase.APP_PASSWORD, true);
        jdbc = JdbcClient.create(connection);
        tx = new TransactionTemplate(new TenantBindingTransactionManager(connection));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        connection.destroy();
    }

    @Test
    void everyTransactionStartsBoundToTheContextTenant() {
        String bound = TenantContext.callAs(
                tenant.tenantId(),
                () -> tx.execute(s -> jdbc.sql("SELECT current_setting('app.tenant_id')")
                        .query(String.class)
                        .single()));
        assertThat(bound).isEqualTo(tenant.tenantId().toString());

        long branches = TenantContext.callAs(
                tenant.tenantId(),
                () -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM branches")
                        .query(Long.class)
                        .single()));
        assertThat(branches).isEqualTo(2);
    }

    @Test
    void theBindingDoesNotLeakIntoTheNextTransactionOnTheSameConnection() {
        TenantContext.callAs(
                tenant.tenantId(),
                () -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM branches")
                        .query(Long.class)
                        .single()));

        // Same physical connection, next transaction, no tenant on the thread: the setting was
        // transaction-local, so it is gone, and the policy raises instead of returning rows.
        String leftover = tx.execute(s -> jdbc.sql("SELECT current_setting('app.tenant_id', true)")
                .query(String.class)
                .single());
        assertThat(leftover).isEmpty();
        assertThatThrownBy(() -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM branches")
                        .query(Long.class)
                        .single()))
                .hasStackTraceContaining("invalid input syntax for type uuid");
    }

    @Test
    void aFreshSessionWithNoTenantFailsClosed() {
        assertThatThrownBy(() -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM branches")
                        .query(Long.class)
                        .single()))
                .hasStackTraceContaining("unrecognized configuration parameter \"app.tenant_id\"");
    }

    @Test
    void aStatementOutsideATransactionIsNeverBound() {
        TenantContext.bind(tenant.tenantId());
        assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM branches")
                        .query(Long.class)
                        .single())
                .isInstanceOf(Exception.class);
    }

    @Test
    void writesInheritTheBoundTenantAndCannotTargetAnother() {
        TestDatabase.Fixture other = TestDatabase.tenant("binding-other", true);
        assertThatThrownBy(() -> TenantContext.callAs(
                        tenant.tenantId(),
                        () -> tx.execute(s -> jdbc.sql(
                                        "INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'XX', 'Smuggled')")
                                .params(UUID.randomUUID(), other.tenantId())
                                .update())))
                .hasStackTraceContaining("violates row-level security policy");
    }
}
