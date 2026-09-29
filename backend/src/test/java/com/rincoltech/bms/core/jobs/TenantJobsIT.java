package com.rincoltech.bms.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.TenantContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Scheduled work runs one tenant per transaction with the tenant bound (chapter 5 section 5.4.4,
 * ADR-008): each tenant sees only its own rows, and suspended tenants are skipped.
 */
class TenantJobsIT extends IntegrationTest {

    @Autowired
    TenantJobs jobs;

    @Autowired
    JdbcClient jdbc;

    @Test
    void workRunsOncePerActiveTenantWithThatTenantBound() {
        TestDatabase.Fixture a = TestDatabase.tenant("jobs-a", true);
        TestDatabase.Fixture b = TestDatabase.tenant("jobs-b", true);
        TestDatabase.Fixture suspended = TestDatabase.tenant("jobs-suspended", true);
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(suspended.tenantId())
                .update();
        for (TestDatabase.Fixture t : new TestDatabase.Fixture[] {a, b, suspended}) {
            TestDatabase.owner()
                    .sql(
                            "INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status,"
                                    + " created_at, expires_at) VALUES (?, ?, 'expired-key-0001', 'POST', '/x', repeat('0', 64),"
                                    + " 'completed', now() - interval '8 days', now() - interval '1 day')")
                    .params(t.tenantId(), UUID.randomUUID())
                    .update();
        }

        Map<UUID, Long> branchesSeen = new ConcurrentHashMap<>();
        jobs.forEachActiveTenant("test.purge", tenantId -> {
            assertThat(TenantContext.require()).isEqualTo(tenantId);
            branchesSeen.put(
                    tenantId,
                    jdbc.sql("SELECT count(*) FROM branches").query(Long.class).single());
            jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at < now()").update();
        });

        assertThat(branchesSeen).containsEntry(a.tenantId(), 2L).containsEntry(b.tenantId(), 2L);
        assertThat(branchesSeen).doesNotContainKey(suspended.tenantId());
        assertThat(expiredKeys(a)).isZero();
        assertThat(expiredKeys(b)).isZero();
        assertThat(expiredKeys(suspended)).isEqualTo(1);
    }

    long expiredKeys(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND key = 'expired-key-0001'")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }
}
