package com.rincoltech.bms.core.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportRuns.CommitOutcome;
import com.rincoltech.bms.core.imports.ImportRuns.Run;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Money;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Import runs, their states and the guards of the commit (issue #127, spec sections 3.7, 3.11, 5.4 and 10.2 to 10.4),
 * against real PostgreSQL as bms_app. Fabricated data only. The kill switch and the halt are flipped through the owner
 * connection, a separate committed transaction, as an operator's request would be while a batch runs.
 */
class ImportRunsIT extends IntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 6, 30);
    static final String SHA = "a".repeat(64);

    @Autowired
    ImportRuns runs;

    @Autowired
    LedgerPosting ledger;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestDatabase.Fixture t;
    UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("imports", true);
    }

    @AfterEach
    void switchBackOn() {
        TestDatabase.owner()
                .sql("UPDATE platform_import_settings SET commit_enabled = true WHERE id = 1")
                .update();
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    /** A run with {@code batches} pending batches, moved to ready with its dump recorded. */
    Run readyRun(int batches) {
        return as(t, () -> {
            Run run = runs.open(operator);
            for (int seq = 1; seq <= batches; seq++) {
                runs.addBatch(run.id(), seq);
            }
            runs.transition(run.id(), "uploaded");
            runs.transition(run.id(), "in_review");
            runs.transition(run.id(), "ready");
            return runs.recordPreImportDump(run.id(), "pre-import-fabricated.dump", Instant.now(), SHA);
        });
    }

    /** Posts one balanced journal for the business, the visible effect of a batch. */
    void postJournal(String sourceModule) {
        ledger.post(new EntryRequest(
                t.headOffice(),
                DAY,
                "IMP-TEST",
                null,
                sourceModule,
                "test_import",
                UUID.randomUUID(),
                null,
                List.of(
                        Line.debit(t.account("loans_receivable"), Money.of(100_000, "UGX")),
                        Line.credit(t.account("cash_on_hand"), Money.of(100_000, "UGX")))));
    }

    long journals() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    String batchStatus(UUID runId, int seq) {
        return TestDatabase.owner()
                .sql("SELECT status FROM import_batches WHERE run_id = ? AND seq = ?")
                .params(runId, seq)
                .query(String.class)
                .single();
    }

    long audits(String action) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?")
                .params(t.tenantId(), action)
                .query(Long.class)
                .single();
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---------------------------------------------------------------------------------------------
    // States.
    // ---------------------------------------------------------------------------------------------

    @Test
    void transitionsFollowTheDiagramAndIllegalOnesAreRefused() {
        Run run = as(t, () -> runs.open(operator));
        assertThat(run.status()).isEqualTo("draft");
        as(t, () -> {
            assertCode(() -> runs.transition(run.id(), "ready"), "invalid_status_transition");
            assertCode(() -> runs.transition(run.id(), "committing"), "invalid_status_transition");
            assertCode(() -> runs.transition(run.id(), "parsing"), "invalid_status_transition");
            runs.transition(run.id(), "uploaded");
            runs.transition(run.id(), "in_review");
            Run ready = runs.transition(run.id(), "ready");
            assertThat(ready.status()).isEqualTo("ready");
            assertCode(() -> runs.transition(run.id(), "committed"), "invalid_status_transition");
            assertThat(runs.transition(run.id(), "cancelled").status()).isEqualTo("cancelled");
            assertCode(() -> runs.transition(run.id(), "uploaded"), "invalid_status_transition");
            assertCode(() -> runs.addBatch(run.id(), 1), "invalid_status_transition");
            return null;
        });
        Map<String, Object> stamps = TestDatabase.owner()
                .sql("SELECT uploaded_at, ready_at, cancelled_at, committing_at FROM import_runs WHERE id = ?")
                .param(run.id())
                .query()
                .singleRow();
        assertThat(stamps.get("uploaded_at")).isNotNull();
        assertThat(stamps.get("ready_at")).isNotNull();
        assertThat(stamps.get("cancelled_at")).isNotNull();
        assertThat(stamps.get("committing_at")).isNull();
    }

    @Test
    void aCommitWritesEveryBatchInOrderAndEndsCommitted() {
        Run run = readyRun(3);
        List<Integer> order = new CopyOnWriteArrayList<>();
        CommitOutcome outcome = as(
                t,
                () -> runs.commit(run.id(), (runId, batchId, seq, checkpoint) -> {
                    order.add(seq);
                    postJournal("lending");
                    checkpoint.rowWritten();
                    return Map.of("written", 1);
                }));
        assertThat(outcome).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(order).containsExactly(1, 2, 3);
        assertThat(journals()).isEqualTo(3);
        Run done = as(t, () -> runs.get(run.id()));
        assertThat(done.status()).isEqualTo("committed");
        assertThat(done.committedAt()).isNotNull();
        String counts = TestDatabase.owner()
                .sql("SELECT counts::text FROM import_batches WHERE run_id = ? AND seq = 2")
                .param(run.id())
                .query(String.class)
                .single();
        assertThat(counts).contains("\"written\": 1");
    }

    @Test
    void noDumpNoCommit() {
        Run run = as(t, () -> {
            Run r = runs.open(operator);
            runs.transition(r.id(), "uploaded");
            runs.transition(r.id(), "in_review");
            return runs.transition(r.id(), "ready");
        });
        as(t, () -> {
            assertCode(() -> runs.commit(run.id(), (r, b, s, c) -> Map.of()), "pre_import_dump_missing");
            assertCode(
                    () -> runs.recordPreImportDump(run.id(), "x.dump", Instant.now(), "NOT-A-SHA"),
                    "invalid_pre_import_dump");
            return null;
        });
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("ready");
    }

    // ---------------------------------------------------------------------------------------------
    // One committing run per business.
    // ---------------------------------------------------------------------------------------------

    @Test
    void twoConcurrentCommitsOfOneRunExactlyOneProceeds() throws Exception {
        Run run = readyRun(1);
        CountDownLatch refused = new CountDownLatch(1);
        List<Object> results = new CopyOnWriteArrayList<>();
        BatchWriter slow = (runId, batchId, seq, checkpoint) -> {
            try {
                // Hold the batch open until the other request has been answered.
                refused.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> commitRecording(run, slow, refused, results)),
                    pool.submit(() -> commitRecording(run, slow, refused, results)));
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).containsExactlyInAnyOrder(CommitOutcome.COMMITTED, "IMPORT_IN_PROGRESS");
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committed");
    }

    private void commitRecording(Run run, BatchWriter writer, CountDownLatch refused, List<Object> results) {
        try {
            results.add(as(t, () -> runs.commit(run.id(), writer)));
        } catch (ApiException e) {
            results.add(e.code());
            refused.countDown();
        }
    }

    @Test
    void aSecondRunOfTheBusinessCannotCommitWhileOneIsCommitting() {
        Run first = readyRun(1);
        Run second = readyRun(1);
        TestDatabase.owner()
                .sql("UPDATE import_runs SET halt_requested = true WHERE id = ?")
                .param(first.id())
                .update();
        // The halt stops the first run before its batch: it stays committing.
        assertThat(as(t, () -> runs.commit(first.id(), (r, b, s, c) -> Map.of())))
                .isEqualTo(CommitOutcome.HALTED);
        assertThat(as(t, () -> runs.get(first.id())).status()).isEqualTo("committing");
        as(t, () -> {
            assertCode(() -> runs.commit(second.id(), (r, b, s, c) -> Map.of()), "IMPORT_IN_PROGRESS");
            assertCode(() -> runs.commit(first.id(), (r, b, s, c) -> Map.of()), "IMPORT_IN_PROGRESS");
            return null;
        });
        assertThat(as(t, () -> runs.get(second.id())).status()).isEqualTo("ready");
    }

    // ---------------------------------------------------------------------------------------------
    // Kill switch and halt.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theKillSwitchMidBatchRollsTheBatchBackAndTheRunResumesLater() {
        Run run = readyRun(1);
        AtomicBoolean switchedOff = new AtomicBoolean();
        BatchWriter writer = (runId, batchId, seq, checkpoint) -> {
            postJournal("lending");
            for (int row = 1; row <= 1_500; row++) {
                if (row == 500 && switchedOff.compareAndSet(false, true)) {
                    // First pass only: the operator switches commits off while the batch runs.
                    TestDatabase.owner()
                            .sql("UPDATE platform_import_settings SET commit_enabled = false WHERE id = 1")
                            .update();
                }
                checkpoint.rowWritten();
            }
            return Map.of("written", 1_500);
        };
        assertThat(as(t, () -> runs.commit(run.id(), writer))).isEqualTo(CommitOutcome.HALTED);
        assertThat(journals()).as("nothing of the halted batch is visible").isZero();
        assertThat(batchStatus(run.id(), 1)).isEqualTo("pending");
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committing");
        assertThat(audits("core.import_run.halted")).isEqualTo(1);

        as(t, () -> {
            assertCode(() -> runs.resume(run.id(), writer), "imports_disabled");
            runs.setCommitEnabled(true, Api.platformUser(Api.email("imports-op")), "Batch limits checked, go on");
            return null;
        });
        assertThat(as(t, () -> runs.resume(run.id(), writer))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(journals()).isEqualTo(1);
        assertThat(batchStatus(run.id(), 1)).isEqualTo("committed");
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committed");
    }

    @Test
    void aHaltStopsBetweenBatchesAndResumeSkipsTheCommittedOnes() {
        Run run = readyRun(2);
        List<Integer> written = new CopyOnWriteArrayList<>();
        BatchWriter writer = (runId, batchId, seq, checkpoint) -> {
            written.add(seq);
            postJournal("lending");
            if (seq == 1 && written.size() == 1) {
                TestDatabase.owner()
                        .sql("UPDATE import_runs SET halt_requested = true WHERE id = ?")
                        .param(runId)
                        .update();
            }
            return Map.of();
        };
        assertThat(as(t, () -> runs.commit(run.id(), writer))).isEqualTo(CommitOutcome.HALTED);
        assertThat(batchStatus(run.id(), 1)).isEqualTo("committed");
        assertThat(batchStatus(run.id(), 2)).isEqualTo("pending");
        assertThat(as(t, () -> runs.get(run.id())).haltRequested()).isTrue();

        assertThat(as(t, () -> runs.resume(run.id(), writer))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(written).containsExactly(1, 2);
        assertThat(journals()).isEqualTo(2);
        assertThat(as(t, () -> runs.get(run.id())).haltRequested()).isFalse();
        as(t, () -> {
            assertCode(() -> runs.requestHalt(run.id()), "invalid_status_transition");
            assertCode(() -> runs.resume(run.id(), writer), "invalid_status_transition");
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Trading tenant guard.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aTradingBusinessIsRefusedWithoutTheOverrideAndAllowedWithItBothAudited() {
        as(
                t,
                () -> new TransactionTemplate(transactionManager).execute(s -> {
                    postJournal("lending");
                    return null;
                }));
        Run run = readyRun(1);
        as(t, () -> {
            assertCode(() -> runs.commit(run.id(), (r, b, s, c) -> Map.of()), "TENANT_TRADING");
            return null;
        });
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("ready");
        assertThat(audits("core.import_run.commit_refused")).isEqualTo(1);

        UUID maker = Api.platformUser(Api.email("imports-maker"));
        UUID checker = Api.platformUser(Api.email("imports-checker"));
        as(t, () -> {
            runs.allowTradingTenant(run.id(), maker, "Top-up import of a forgotten tab");
            assertCode(() -> runs.commit(run.id(), (r, b, s, c) -> Map.of()), "TENANT_TRADING");
            assertCode(() -> runs.approveTradingTenant(run.id(), maker), "self_approval_forbidden");
            assertThat(runs.approveTradingTenant(run.id(), checker).tradingOverrideApproved())
                    .isTrue();
            return null;
        });
        assertThat(as(t, () -> runs.commit(run.id(), (r, b, s, c) -> Map.of()))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(audits("core.import_run.trading_override_set")).isEqualTo(1);
        assertThat(audits("core.import_run.trading_override_approved")).isEqualTo(1);
        long used = TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.status_changed'
                           AND data::text LIKE '%trading_override_used%'
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(used).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesAnOverrideApprovedByItsMaker() {
        Run run = readyRun(1);
        UUID maker = Api.platformUser(Api.email("imports-self"));
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("""
                                UPDATE import_runs SET allow_trading_tenant = true, allow_trading_reason = 'Fabricated',
                                       allow_trading_set_by = ?, allow_trading_approved_by = ?
                                 WHERE id = ?
                                """)
                        .params(maker, maker, run.id())
                        .update())
                .hasMessageContaining("import_runs_check");
    }

    // ---------------------------------------------------------------------------------------------
    // Isolation.
    // ---------------------------------------------------------------------------------------------

    @Test
    void anotherBusinessCannotSeeOrLockTheRun() {
        Run run = readyRun(1);
        TestDatabase.Fixture other = TestDatabase.tenant("imports-other", true);
        as(other, () -> {
            assertCode(() -> runs.get(run.id()), "not_found");
            assertCode(() -> runs.commit(run.id(), (r, b, s, c) -> Map.of()), "not_found");
            assertCode(() -> runs.requestHalt(run.id()), "not_found");
            assertCode(() -> runs.addBatch(run.id(), 2), "not_found");
            return null;
        });
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("ready");
    }
}
