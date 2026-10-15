package com.rincoltech.bms.core.tenancy;

import static com.rincoltech.bms.core.tenancy.RlsIsolationIT.asApp;
import static com.rincoltech.bms.core.tenancy.RlsIsolationIT.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Isolation of the tenant SMS settings tables and functions of migration V52 (ADR-036, NFR-ISO-01 to NFR-ISO-03), from the
 * application role's side: the operator's sender id functions refuse a transaction bound to a tenant, a tenant sees only its
 * own approved ids, and switching a tenant on drops that tenant's backlog and no one else's. The generic table checks of
 * {@link RlsIsolationIT} cover the new tenant table ({@code sms_template_overrides}) and the closed platform table.
 */
class SmsSettingsIsolationIT extends IntegrationTest {

    @Test
    void theSenderIdFunctionsOfTheOperatorRefuseATenantAndTheTenantSeesOnlyItsOwnIds() throws SQLException {
        TestDatabase.Fixture a = TestDatabase.tenant("sms-iso-a", true);
        TestDatabase.Fixture b = TestDatabase.tenant("sms-iso-b", true);
        for (String call : List.of(
                "SELECT * FROM sms_sender_ids_for_tenant(?)",
                "SELECT sms_sender_id_approve(?, 'Grabbed1', NULL, now())",
                "SELECT sms_sender_id_withdraw(?, 'Grabbed1', NULL, now())")) {
            assertThatThrownBy(() -> asApp(a.tenantId(), c -> {
                        try (PreparedStatement ps = c.prepareStatement(call)) {
                            ps.setObject(1, a.tenantId());
                            ps.execute();
                            return null;
                        }
                    }))
                    .as(call)
                    .hasStackTraceContaining("platform operator only");
        }
        String mine = "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        String theirs = "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        for (Map.Entry<UUID, String> entry : List.of(Map.entry(a.tenantId(), mine), Map.entry(b.tenantId(), theirs))) {
            TestDatabase.owner()
                    .sql("INSERT INTO platform_sms_sender_ids (id, tenant_id, sender_id, approved_at)"
                            + " VALUES (gen_random_uuid(), ?, ?, now())")
                    .params(entry.getKey(), entry.getValue())
                    .update();
        }
        List<String> seen = asApp(a.tenantId(), c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT sender_id FROM sms_sender_ids_current()");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        });
        assertThat(seen).containsExactly(mine);
        // The approval check is for the bound tenant: the other business's id is not approved for this one.
        String approvedForMe = asApp(a.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT sms_sender_id_approved(?)")) {
                ps.setString(1, theirs);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        assertThat(approvedForMe).isNull();
        String approvedForThem = asApp(b.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT sms_sender_id_approved(?)")) {
                ps.setString(1, theirs.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        assertThat(approvedForThem).as("the stored spelling comes back").isEqualTo(theirs);
    }

    /** Switching a tenant on drops that tenant's pending SMS rows and no one else's; with no tenant bound it refuses. */
    @Test
    void theBacklogDropOfASwitchOnTouchesOnlyTheBoundTenant() throws SQLException {
        TestDatabase.Fixture a = TestDatabase.tenant("sms-iso-a", true);
        TestDatabase.Fixture b = TestDatabase.tenant("sms-iso-b", true);
        String prefix = "rls.backlog:" + UUID.randomUUID() + ":";
        for (TestDatabase.Fixture tenant : List.of(a, b)) {
            TestDatabase.owner()
                    .sql(
                            "INSERT INTO notification_outbox (id, channel, recipient, template_key, params, idempotency_key,"
                                    + " expires_at, next_attempt_at, created_at, updated_at, tenant_id, category)"
                                    + " VALUES (gen_random_uuid(), 'sms', '+256700000999', 'savings.deposit', '{}'::jsonb, ?,"
                                    + " now() + interval '1 hour', now(), now() - interval '1 minute', now(), ?, 'transactional')")
                    .params(prefix + tenant.slug(), tenant.tenantId())
                    .update();
        }
        try {
            // Only A's row is counted: had the function touched B's, or any other tenant's pending SMS, it would say
            // more.
            // (Rows of other tests are made failed first so the count is exactly this test's.)
            TestDatabase.owner()
                    .sql(
                            "UPDATE notification_outbox SET status = 'failed', last_error = 'test_cleanup', failed_at = now()"
                                    + " WHERE channel = 'sms' AND status = 'pending' AND idempotency_key NOT LIKE ?")
                    .param(prefix + "%")
                    .update();
            Long dropped = asApp(a.tenantId(), c -> count(c, "SELECT sms_tenant_switched_on(now())"));
            assertThat(dropped).isEqualTo(1);
            assertThatThrownBy(() -> asApp(null, c -> count(c, "SELECT sms_tenant_switched_on(now())")))
                    .hasStackTraceContaining("a tenant must be bound");
            // The function ran in a transaction that was rolled back: nothing changed, so check B's row stayed pending
            // by running it for B and seeing exactly one row dropped there too (its own).
            Long droppedForB = asApp(b.tenantId(), c -> count(c, "SELECT sms_tenant_switched_on(now())"));
            assertThat(droppedForB).isEqualTo(1);
        } finally {
            TestDatabase.owner()
                    .sql(
                            "UPDATE notification_outbox SET status = 'failed', last_error = 'test_cleanup', failed_at = now()"
                                    + " WHERE idempotency_key LIKE ? AND status = 'pending'")
                    .param(prefix + "%")
                    .update();
        }
    }
}
