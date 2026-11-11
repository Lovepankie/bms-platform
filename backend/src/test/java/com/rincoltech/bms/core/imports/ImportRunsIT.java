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
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

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
    ImportMappings mappings;

    @Autowired
    LedgerPosting ledger;

    /** The application's client: inside a batch, it writes in the batch's own transaction. */
    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestRestTemplate http;

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
        ScriptedTemplate.SCRIPT = null;
    }

    /** A writer with nothing to write. */
    static final BatchWriter NOTHING = (r, b, s, c) -> Map.of();

    // JUSTIFICATION-A3: the Lead's ruling 4 on #869: a test's writer reaches the commit only through a test template.
    /**
     * The run, after setting the writer the test-only template's loader runs (Lead ruling 4 on #869): the commit resolves
     * every batch's writer from its template, so a test's writer reaches it only that way.
     */
    static UUID script(UUID runId, BatchWriter writer) {
        ScriptedTemplate.SCRIPT = writer;
        return runId;
    }

    /**
     * Binds the run's batches to a mapping of the test-only template, as an upload with a mapping would. Called inside
     * the business's context.
     */
    void scripted(UUID runId) {
        UUID mapping = mappings.latest("scripted")
                .orElseGet(() -> mappings.save("scripted", ScriptedTemplate.mapping(), operator, null))
                .id();
        TestDatabase.owner()
                .sql("""
                        UPDATE import_batches SET mapping_id = ?, mapping_source = 'rows', template_key = ?,
                               template_version = 1
                         WHERE run_id = ?
                        """)
                .params(mapping, ScriptedTemplate.KEY, runId)
                .update();
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    /**
     * A run with {@code batches} pending batches, moved to ready with its dump recorded and checked, and its commit approved
     * by a second person (issue #133).
     */
    Run readyRun(int batches) {
        Run run = unapprovedRun(batches);
        CommitApproval.readyToCommit(http, t, runs, run.id(), SHA);
        return as(t, () -> runs.get(run.id()));
    }

    /** A run as {@link #readyRun} without the dump's check and the commit's approval. */
    Run unapprovedRun(int batches) {
        return as(t, () -> {
            Run run = runs.open(operator);
            for (int seq = 1; seq <= batches; seq++) {
                runs.addBatch(run.id(), seq);
            }
            scripted(run.id());
            runs.transition(run.id(), "uploaded");
            runs.transition(run.id(), "in_review");
            // Ready needs a stored dry run since #132, with the writers the commit resolves.
            runs.dryRun(run.id(), operator);
            runs.transition(run.id(), "ready");
            return runs.recordPreImportDump(run.id(), "pre-import-fabricated.dump", Instant.now(), SHA);
        });
    }

    /**
     * Posts the batch's balanced journal, the visible effect of a batch, as the batch's opening journal: since issue #133
     * a batch posts no journal but its opening journals (Lead ruling 6 and the review of 8e570b0 on #872).
     */
    void postJournal(UUID batchId, BatchWriter.Checkpoint checkpoint) {
        checkpoint.openingJournal(
                new ImportRefs.Opening(t.headOffice(), null, "test_batch", DAY, "loans_receivable", true, 100_000));
    }

    /** Posts one balanced journal for the business. */
    UUID postJournal(String sourceModule) {
        return postJournal(sourceModule, "test_import");
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
            assertCode(() -> runs.transition(run.id(), "ready"), "dry_run_required");
            runs.dryRun(run.id(), operator);
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
                () -> runs.commit(script(run.id(), (runId, batchId, seq, checkpoint) -> {
                    order.add(seq);
                    postJournal(batchId, checkpoint);
                    checkpoint.rowWritten();
                    return Map.of("written", 1);
                })));
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
            runs.dryRun(r.id(), operator);
            return runs.transition(r.id(), "ready");
        });
        as(t, () -> {
            assertCode(() -> runs.commit(script(run.id(), NOTHING)), "pre_import_dump_missing");
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
            results.add(as(t, () -> runs.commit(script(run.id(), writer))));
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
        assertThat(as(t, () -> runs.commit(script(first.id(), NOTHING)))).isEqualTo(CommitOutcome.HALTED);
        assertThat(as(t, () -> runs.get(first.id())).status()).isEqualTo("committing");
        as(t, () -> {
            assertCode(() -> runs.commit(script(second.id(), NOTHING)), "IMPORT_IN_PROGRESS");
            assertCode(() -> runs.commit(script(first.id(), NOTHING)), "IMPORT_IN_PROGRESS");
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
            postJournal(batchId, checkpoint);
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
        assertThat(as(t, () -> runs.commit(script(run.id(), writer)))).isEqualTo(CommitOutcome.HALTED);
        assertThat(journals()).as("nothing of the halted batch is visible").isZero();
        assertThat(batchStatus(run.id(), 1)).isEqualTo("pending");
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committing");
        assertThat(audits("core.import_run.halted")).isEqualTo(1);

        as(t, () -> {
            assertCode(() -> runs.resume(script(run.id(), writer)), "imports_disabled");
            runs.setCommitEnabled(true, Api.platformUser(Api.email("imports-op")), "Batch limits checked, go on");
            return null;
        });
        assertThat(as(t, () -> runs.resume(script(run.id(), writer)))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(journals()).isEqualTo(1);
        assertThat(batchStatus(run.id(), 1)).isEqualTo("committed");
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committed");
    }

    // JUSTIFICATION-A3: issue #897 items 3 and 6, each a case of its own beside the kill switch case above.
    /**
     * The kill switch refuses a resume for every caller, not only through the portal's controller (issue #897): the
     * service's resume, the portal's startResume called directly (its background never handed the work) and the portal's
     * route are each 409 {@code imports_disabled}, nothing is written, and none keeps the business's loop.
     */
    @Test
    void theKillSwitchRefusesAResumeForEveryCaller() {
        Run run = readyRun(1);
        BatchWriter writer = (runId, batchId, seq, checkpoint) -> {
            postJournal(batchId, checkpoint);
            for (int row = 1; row <= 1_500; row++) {
                if (row == 500) {
                    TestDatabase.owner()
                            .sql("UPDATE platform_import_settings SET commit_enabled = false WHERE id = 1")
                            .update();
                }
                checkpoint.rowWritten();
            }
            return Map.of("written", 1_500);
        };
        assertThat(as(t, () -> runs.commit(script(run.id(), writer)))).isEqualTo(CommitOutcome.HALTED);
        String email = Api.email("switch-off-resume");
        UUID operatorId = Api.platformUser(email);
        AtomicBoolean handed = new AtomicBoolean();
        as(t, () -> {
            assertCode(() -> runs.resume(run.id()), "imports_disabled");
            assertCode(() -> runs.startResume(run.id(), operatorId, work -> handed.set(true)), "imports_disabled");
            return null;
        });
        assertThat(handed).as("the refused resume handed no work on").isFalse();
        Api platform = Api.platform(http);
        String token = platform.signIn(operatorId, email, Api.PASSWORD, null).accessToken();
        ResponseEntity<JsonNode> refused = platform.post(
                "/api/v1/platform/imports/businesses/" + t.slug() + "/runs/" + run.id() + "/resume", null, token);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("imports_disabled");
        assertThat(journals()).as("nothing written by a refused resume").isZero();
        assertThat(batchStatus(run.id(), 1)).isEqualTo("pending");

        // Switched on again, the loop is free: the resume goes on and ends committed.
        TestDatabase.owner()
                .sql("UPDATE platform_import_settings SET commit_enabled = true WHERE id = 1")
                .update();
        assertThat(as(t, () -> runs.resume(script(run.id(), NOTHING)))).isEqualTo(CommitOutcome.COMMITTED);
    }

    /**
     * A start or a resume whose background never runs the work (it throws, its thread cannot start: an Error, or it closes
     * the work unrun) gives the business's loop back at once (issue #897), so the next resume claims it.
     */
    @Test
    void aStartOrResumeWhoseWorkNeverRunsGivesTheLoopBack() {
        Run run = readyRun(1);
        UUID operatorId = Api.platformUser(Api.email("work-never-runs"));
        IllegalStateException noThread = new IllegalStateException("fabricated: the thread did not start");
        assertThatThrownBy(() -> as(t, () -> {
                    runs.startCommit(script(run.id(), NOTHING), operatorId, work -> {
                        throw noThread;
                    });
                    return null;
                }))
                .isSameAs(noThread);
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committing");
        // The portal's way when its thread fails before the work: the work is closed and never run.
        as(t, () -> {
            runs.startResume(run.id(), operatorId, ImportRuns.Work::close);
            return null;
        });
        OutOfMemoryError noMemory = new OutOfMemoryError("fabricated: no memory for a thread");
        assertThatThrownBy(() -> as(t, () -> {
                    runs.startResume(run.id(), operatorId, work -> {
                        throw noMemory;
                    });
                    return null;
                }))
                .isSameAs(noMemory);
        assertThat(as(t, () -> runs.resume(run.id()))).isEqualTo(CommitOutcome.COMMITTED);
    }

    @Test
    void aHaltStopsBetweenBatchesAndResumeSkipsTheCommittedOnes() {
        Run run = readyRun(2);
        List<Integer> written = new CopyOnWriteArrayList<>();
        BatchWriter writer = (runId, batchId, seq, checkpoint) -> {
            written.add(seq);
            postJournal(batchId, checkpoint);
            if (seq == 1 && written.size() == 1) {
                TestDatabase.owner()
                        .sql("UPDATE import_runs SET halt_requested = true WHERE id = ?")
                        .param(runId)
                        .update();
            }
            return Map.of();
        };
        assertThat(as(t, () -> runs.commit(script(run.id(), writer)))).isEqualTo(CommitOutcome.HALTED);
        assertThat(batchStatus(run.id(), 1)).isEqualTo("committed");
        assertThat(batchStatus(run.id(), 2)).isEqualTo("pending");
        assertThat(as(t, () -> runs.get(run.id())).haltRequested()).isTrue();

        assertThat(as(t, () -> runs.resume(script(run.id(), writer)))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(written).containsExactly(1, 2);
        assertThat(journals()).isEqualTo(2);
        assertThat(as(t, () -> runs.get(run.id())).haltRequested()).isFalse();
        as(t, () -> {
            assertCode(() -> runs.requestHalt(run.id()), "invalid_status_transition");
            assertCode(() -> runs.resume(script(run.id(), writer)), "invalid_status_transition");
            return null;
        });
    }

    // JUSTIFICATION-A3: the review of #736 asks for these cases (a failing writer, a late halt, a resume mid batch).
    // They are tests, not mechanism.
    @Test
    void aWriterThatFailsMidBatchRollsTheBatchBackIsAuditedAndTheRunGoesOnLater() {
        Run run = readyRun(1);
        AtomicBoolean failed = new AtomicBoolean();
        BatchWriter writer = (runId, batchId, seq, checkpoint) -> {
            postJournal(batchId, checkpoint);
            for (int row = 1; row <= 1_000; row++) {
                if (row == 700 && failed.compareAndSet(false, true)) {
                    // First pass only: a row the business's tables refuse, as a constraint would.
                    throw new IllegalStateException("fabricated row 700 refused");
                }
                checkpoint.rowWritten();
            }
            return Map.of("written", 1_000);
        };
        assertThatThrownBy(() -> as(t, () -> runs.commit(script(run.id(), writer))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(journals()).as("nothing of the failed batch is visible").isZero();
        assertThat(batchStatus(run.id(), 1)).isEqualTo("pending");
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committing");
        assertThat(audits("core.import_run.batch_failed")).isEqualTo(1);

        assertThat(as(t, () -> runs.resume(script(run.id(), writer)))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(journals()).isEqualTo(1);
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committed");
    }

    /** Posts a journal of the given source, debit loans and credit cash: no line on opening balance equity. */
    UUID postJournal(String sourceModule, String sourceType) {
        return ledger.post(new EntryRequest(
                        t.headOffice(),
                        DAY,
                        "IMP-TEST",
                        null,
                        sourceModule,
                        sourceType,
                        UUID.randomUUID(),
                        null,
                        List.of(
                                Line.debit(t.account("loans_receivable"), Money.of(100_000, "UGX")),
                                Line.credit(t.account("cash_on_hand"), Money.of(100_000, "UGX")))))
                .entryId();
    }

    /** Registers a journal in import_refs by the writer's own SQL, past the checkpoint, as a careless writer could. */
    void registerBehindTheCheckpoint(UUID runId, UUID batchId, UUID entry) {
        String ref = ImportRefs.sourceRef("test-journal", null, "batch:" + batchId, "0");
        jdbc.sql("""
                        INSERT INTO import_refs (id, tenant_id, run_id, batch_id, source_key, source_ref, target_type,
                                                 target_id)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, 'ledger.journal_entry', ?)
                        """)
                .params(UUID.randomUUID(), runId, batchId, ImportRefs.keyOf(ref), ref, entry)
                .update();
    }

    // JUSTIFICATION-A3: the review of 8e570b0 and the Lead's note on #872: each journal a batch may not post, refused.
    @Test
    void aBatchThatPostsAnyJournalButItsOpeningJournalsFailsAndRollsBack() {
        Run run = readyRun(1);
        // A disbursement-like journal (debit loans, credit cash), left unregistered: the end-of-batch check refuses it.
        BatchWriter unregistered = (runId, batchId, seq, checkpoint) -> {
            postJournal("lending");
            return Map.of();
        };
        assertThatThrownBy(() -> as(t, () -> runs.commit(script(run.id(), unregistered))))
                .hasStackTraceContaining("not its opening journals");
        // The same journal offered to the checkpoint as a record: a writer never registers a journal.
        BatchWriter asRecord = (runId, batchId, seq, checkpoint) -> {
            checkpoint.register(
                    ImportRefs.sourceRef("test-journal", null, "batch:" + batchId, "0"),
                    "ledger.journal_entry",
                    postJournal("lending"));
            return Map.of();
        };
        assertThatThrownBy(() -> as(t, () -> runs.resume(script(run.id(), asRecord))))
                .hasStackTraceContaining("registered only as an opening journal");
        // The same journal registered by the writer's own SQL: registered, but not an opening journal by the ledger.
        BatchWriter registered = (runId, batchId, seq, checkpoint) -> {
            registerBehindTheCheckpoint(runId, batchId, postJournal("lending"));
            return Map.of();
        };
        assertThatThrownBy(() -> as(t, () -> runs.resume(script(run.id(), registered))))
                .hasStackTraceContaining("not its opening journals");
        // Named as the framework's opening journal, registered, but with no line on opening balance equity.
        BatchWriter misnamed = (runId, batchId, seq, checkpoint) -> {
            registerBehindTheCheckpoint(runId, batchId, postJournal("core.imports", "opening_balance"));
            return Map.of();
        };
        assertThatThrownBy(() -> as(t, () -> runs.resume(script(run.id(), misnamed))))
                .hasStackTraceContaining("not its opening journals");
        // Named as the import-retail command's opening journal, with no line on opening balance equity.
        BatchWriter posing = (runId, batchId, seq, checkpoint) -> {
            postJournal("retail", "retail.cash_opening");
            return Map.of();
        };
        assertThatThrownBy(() -> as(t, () -> runs.resume(script(run.id(), posing))))
                .hasStackTraceContaining("not its opening journals");
        // Every refused batch rolled back whole: no journal, no reference, the batch still to write.
        assertThat(journals()).isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM import_refs WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(batchStatus(run.id(), 1)).isEqualTo("pending");
        assertThat(audits("core.import_run.batch_failed")).isEqualTo(5);

        // The batch's own opening journal goes through.
        BatchWriter opening = (runId, batchId, seq, checkpoint) -> {
            postJournal(batchId, checkpoint);
            return Map.of();
        };
        assertThat(as(t, () -> runs.resume(script(run.id(), opening)))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(journals()).isEqualTo(1);
    }

    @Test
    void aHaltPressedDuringTheLastBatchIsClearedWhenTheRunEnds() {
        Run run = readyRun(1);
        BatchWriter writer = (runId, batchId, seq, checkpoint) -> {
            postJournal(batchId, checkpoint);
            // The halt lands after the batch's last checkpoint: fewer than 1,000 rows follow it.
            TestDatabase.owner()
                    .sql("UPDATE import_runs SET halt_requested = true WHERE id = ?")
                    .param(runId)
                    .update();
            for (int row = 1; row <= 10; row++) {
                checkpoint.rowWritten();
            }
            return Map.of("written", 10);
        };
        assertThat(as(t, () -> runs.commit(script(run.id(), writer)))).isEqualTo(CommitOutcome.COMMITTED);
        Run done = as(t, () -> runs.get(run.id()));
        assertThat(done.status()).isEqualTo("committed");
        assertThat(done.haltRequested()).as("a finished run is not stopping").isFalse();
    }

    @Test
    void aResumeWhileABatchIsBeingWrittenIsRefusedAndTheHaltHolds() throws Exception {
        Run run = readyRun(1);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        BatchWriter slow = (runId, batchId, seq, checkpoint) -> {
            writing.countDown();
            try {
                answered.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // The operator's halt set while the batch was held is still there at the next checkpoint.
            for (int row = 1; row <= 1_000; row++) {
                checkpoint.rowWritten();
            }
            return Map.of();
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CommitOutcome> commit = pool.submit(() -> as(t, () -> runs.commit(script(run.id(), slow))));
            assertThat(writing.await(20, TimeUnit.SECONDS)).isTrue();
            TestDatabase.owner()
                    .sql("UPDATE import_runs SET halt_requested = true WHERE id = ?")
                    .param(run.id())
                    .update();
            try {
                as(t, () -> {
                    assertCode(() -> runs.resume(script(run.id(), slow)), "IMPORT_IN_PROGRESS");
                    return null;
                });
                assertThat(as(t, () -> runs.get(run.id())).haltRequested())
                        .as("the refused resume leaves the halt set")
                        .isTrue();
            } finally {
                answered.countDown();
            }
            assertThat(commit.get(60, TimeUnit.SECONDS)).isEqualTo(CommitOutcome.HALTED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(batchStatus(run.id(), 1)).isEqualTo("pending");
        assertThat(audits("core.import_run.resumed")).isZero();
    }

    @Test
    void aResumeInTheGapBetweenTwoBatchesIsRefusedAndTheHaltIsHonoured() throws Exception {
        // JUSTIFICATION-A3: the test issue #779 asks for, a halt and a resume in the gap between two batches.
        Run run = readyRun(3);
        CountDownLatch inGap = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        List<Integer> written = new CopyOnWriteArrayList<>();
        BatchWriter writer = (runId, batchId, seq, checkpoint) -> {
            written.add(seq);
            postJournal(batchId, checkpoint);
            if (seq == 1) {
                // Runs once batch 1 has committed and its business lock is gone: the loop waits here, before batch 2.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        inGap.countDown();
                        try {
                            answered.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
            return Map.of();
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CommitOutcome> commit = pool.submit(() -> as(t, () -> runs.commit(script(run.id(), writer))));
            assertThat(inGap.await(20, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(batchStatus(run.id(), 1)).isEqualTo("committed");
                as(t, () -> {
                    runs.requestHalt(run.id());
                    assertCode(() -> runs.resume(script(run.id(), writer)), "IMPORT_IN_PROGRESS");
                    return null;
                });
                assertThat(as(t, () -> runs.get(run.id())).haltRequested())
                        .as("the refused resume leaves the halt set")
                        .isTrue();
            } finally {
                answered.countDown();
            }
            assertThat(commit.get(60, TimeUnit.SECONDS)).isEqualTo(CommitOutcome.HALTED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(written).as("the halt stopped the loop before batch 2").containsExactly(1);
        assertThat(batchStatus(run.id(), 2)).isEqualTo("pending");
        assertThat(batchStatus(run.id(), 3)).isEqualTo("pending");
        assertThat(audits("core.import_run.halted")).isEqualTo(1);
        assertThat(audits("core.import_run.resumed")).isZero();

        // The loop has ended and its lock with it, so the operator's resume now goes on.
        assertThat(as(t, () -> runs.resume(script(run.id(), writer)))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(written).containsExactly(1, 2, 3);
        assertThat(journals()).isEqualTo(3);
    }

    @Test
    void aLoopWhoseClaimConnectionDiesStopsBeforeTheNextBatchAndAResumeGoesOn() {
        // JUSTIFICATION-A3: the test issue #789 asks for, the claim connection killed mid run.
        Run run = readyRun(3);
        List<Integer> written = new CopyOnWriteArrayList<>();
        AtomicBoolean killed = new AtomicBoolean();
        BatchWriter writer = (runId, batchId, seq, checkpoint) -> {
            written.add(seq);
            postJournal(batchId, checkpoint);
            if (seq == 1 && killed.compareAndSet(false, true)) {
                // As bms_app, the role of the loop's own connections: end the session that holds the loop key.
                List<Boolean> ended = JdbcClient.create(TestDatabase.appDataSource())
                        .sql("""
                                SELECT pg_terminate_backend(pid) FROM pg_locks
                                 WHERE locktype = 'advisory' AND granted AND objsubid = 1
                                   AND ((classid::bigint << 32) | objid::bigint)
                                       = hashtextextended('core.imports.loop:' || ?, 0)
                                """)
                        .param(t.tenantId().toString())
                        .query(Boolean.class)
                        .list();
                assertThat(ended).containsExactly(true);
            }
            return Map.of();
        };

        assertThat(as(t, () -> runs.commit(script(run.id(), writer)))).isEqualTo(CommitOutcome.HALTED);
        assertThat(written).as("the lost claim stopped the loop before batch 2").containsExactly(1);
        assertThat(batchStatus(run.id(), 1)).isEqualTo("committed");
        assertThat(batchStatus(run.id(), 2)).isEqualTo("pending");
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committing");
        assertThat(audits("core.import_run.claim_lost")).isEqualTo(1);
        String reason = TestDatabase.owner()
                .sql("""
                        SELECT data -> 'after' ->> 'reason' FROM audit_log
                         WHERE tenant_id = ? AND action = 'core.import_run.claim_lost'
                        """)
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(reason).isEqualTo("claim_connection_lost");

        assertThat(as(t, () -> runs.resume(script(run.id(), writer)))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(written).containsExactly(1, 2, 3);
        assertThat(journals()).isEqualTo(3);
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("committed");
    }

    @Test
    void aHaltOfAnImportNotBeingImportedIsRefusedInPlainWords() {
        as(t, () -> {
            Run run = runs.open(operator);
            runs.transition(run.id(), "uploaded");
            runs.transition(run.id(), "in_review");
            assertThatThrownBy(() -> runs.requestHalt(run.id()))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.getMessage())
                                    .isEqualTo("Only an import being imported can be stopped; this one is in review.")
                                    .doesNotContain("in_review", "committing"));
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Trading tenant guard.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aTradingBusinessIsRefusedWithoutTheOverrideAndAllowedWithItBothAudited() {
        // The business starts trading after the run's dry run: a dry run into a trading business needs the approved
        // override itself (ImportPreviewIT), so the commit's own guard is what this test reaches.
        Run run = readyRun(1);
        as(
                t,
                () -> new TransactionTemplate(transactionManager).execute(s -> {
                    postJournal("lending");
                    return null;
                }));
        as(t, () -> {
            assertCode(() -> runs.commit(script(run.id(), NOTHING)), "TENANT_TRADING");
            return null;
        });
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("ready");
        assertThat(audits("core.import_run.commit_refused")).isEqualTo(1);

        UUID maker = Api.platformUser(Api.email("imports-maker"));
        UUID checker = Api.platformUser(Api.email("imports-checker"));
        as(t, () -> {
            runs.allowTradingTenant(run.id(), maker, "Top-up import of a forgotten tab");
            assertCode(() -> runs.commit(script(run.id(), NOTHING)), "TENANT_TRADING");
            assertCode(() -> runs.approveTradingTenant(run.id(), maker), "self_approval_forbidden");
            assertThat(runs.approveTradingTenant(run.id(), checker).tradingOverrideApproved())
                    .isTrue();
            // The override changed the run after its commit was approved (issue #133): the approval is asked again.
            assertCode(() -> runs.commit(script(run.id(), NOTHING)), "commit_not_approved");
            return null;
        });
        CommitApproval.approved(http, t, runs, run.id());
        assertThat(as(t, () -> runs.commit(script(run.id(), NOTHING)))).isEqualTo(CommitOutcome.COMMITTED);
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
                .hasMessageContaining("import_runs_checker_not_maker");
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
            assertCode(() -> runs.commit(script(run.id(), NOTHING)), "not_found");
            assertCode(() -> runs.requestHalt(run.id()), "not_found");
            assertCode(() -> runs.addBatch(run.id(), 2), "not_found");
            return null;
        });
        assertThat(as(t, () -> runs.get(run.id())).status()).isEqualTo("ready");
    }
}
