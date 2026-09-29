package com.rincoltech.bms.core.approvals.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/** FR-APR-07: the nightly task expires every overdue pending request, tenant by tenant. */
class ApprovalExpiryJobIT extends IntegrationTest {

    @Autowired
    TenantJobs jobs;

    @Autowired
    ApprovalService approvals;

    @Autowired
    @Qualifier("approvalExpiry")
    RecurringTask<Void> task;

    UUID insert(TestDatabase.Fixture t, String expiresIn) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO approval_requests (id, tenant_id, branch_id, action_type, subject_type, subject_id, payload,"
                                + " status, requested_by, requested_at, expires_at)"
                                + " VALUES (?, ?, ?, 'test_action', 'test.subject', ?, '{}', 'pending', ?, now(), now() + CAST(? AS interval))")
                .params(id, t.tenantId(), t.headOffice(), UUID.randomUUID(), UUID.randomUUID(), expiresIn)
                .update();
        return id;
    }

    String status(UUID id) {
        return TestDatabase.owner()
                .sql("SELECT status FROM approval_requests WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    @Test
    void overdueRequestsExpireAndOthersStayPending() {
        TestDatabase.Fixture t = TestDatabase.tenant("apr-job", true);
        UUID overdue = insert(t, "-1 second");
        UUID current = insert(t, "7 days");

        jobs.forEachActiveTenant(ApprovalJobs.EXPIRY, tenantId -> approvals.expireOverdue());

        assertThat(task.getName()).isEqualTo(ApprovalJobs.EXPIRY);
        assertThat(status(overdue)).isEqualTo("expired");
        assertThat(status(current)).isEqualTo("pending");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT actor_kind FROM audit_log WHERE entity_id = ? AND action = 'core.approval.expired'")
                        .param(overdue)
                        .query(String.class)
                        .single())
                .isEqualTo("system");
    }
}
