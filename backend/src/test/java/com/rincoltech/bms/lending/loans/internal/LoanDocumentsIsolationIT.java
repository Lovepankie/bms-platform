package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-034: every read a loan document makes (a statement is several queries) runs in one read-only
 * REPEATABLE READ transaction, so it is one snapshot and a repayment committing in between cannot
 * make the lines disagree with the balance. This proves it deterministically: the template the
 * documents use sees the same rows at its second query although another connection committed a change
 * after its first, and a plain READ COMMITTED template, as a control, sees the change.
 */
class LoanDocumentsIsolationIT extends IntegrationTest {

    @Autowired
    LoanDocuments documents;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcClient jdbc;

    private long branches() {
        return jdbc.sql("SELECT count(*) FROM branches").query(Long.class).single();
    }

    private void commitAnotherBranch(TestDatabase.Fixture tenant) {
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, ?, 'Late', false)")
                .params(
                        UUID.randomUUID(),
                        tenant.tenantId(),
                        "L" + UUID.randomUUID().toString().substring(0, 6).toUpperCase())
                .update();
    }

    @Test
    void documentReadsAreOneRepeatableReadSnapshot() {
        TransactionTemplate reading = documents.readingTemplate();
        assertThat(reading.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(reading.isReadOnly()).isTrue();
        TestDatabase.Fixture tenant = TestDatabase.tenant("iso", true);
        long[] seen = TenantContext.callAs(
                tenant.tenantId(),
                () -> reading.execute(status -> {
                    long first = branches();
                    commitAnotherBranch(tenant);
                    return new long[] {first, branches()};
                }));
        assertThat(seen[1]).as("the second query of the same read").isEqualTo(seen[0]);
    }

    @Test
    void aReadCommittedReadWouldHaveSeenTheLaterCommit() {
        TransactionTemplate plain = new TransactionTemplate(transactions);
        plain.setReadOnly(true);
        TestDatabase.Fixture tenant = TestDatabase.tenant("iso", true);
        long[] seen = TenantContext.callAs(
                tenant.tenantId(),
                () -> plain.execute(status -> {
                    long first = branches();
                    commitAnotherBranch(tenant);
                    return new long[] {first, branches()};
                }));
        assertThat(seen[1])
                .as("the control sees the change, so the test above can tell")
                .isEqualTo(seen[0] + 1);
    }
}
