package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.CommitApproval;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportPurges;
import com.rincoltech.bms.core.imports.ImportPurges.Purge;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.CommitOutcome;
import com.rincoltech.bms.core.imports.ImportRuns.PolicyDecision;
import com.rincoltech.bms.core.imports.ImportTemplate.Policy;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory.Ensured;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.HistoricalMovement;
import com.rincoltech.bms.testsupport.Api;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The commit of issue #133 (spec sections 3.7, 3.8 and 10.2, FR-IMP-07, ADR-027 decisions 4 and 7) on real PostgreSQL as
 * bms_app, through the fixture template and a writer that uses the framework's side of the commit: its rows with their
 * {@code source_ref}, {@code import_refs}, the opening journals through {@code post_entry} and the run's receivable
 * decision. Proven here: batches commit in the template's dependency order, one transaction each, under a statement timeout
 * of 10 minutes; a process killed after batch k resumes to the state of an uninterrupted run, with no row or journal twice;
 * a failing batch rolls back alone; every journal the commit posts is balanced and no other journal is posted; the commit
 * is refused without the dump, its check, the approval, by the maker as checker and while a decision is missing; each
 * receivable decision gives its documented journals; a row imported before with other content is CHANGED_SINCE_IMPORT and
 * never written twice, and a re-run writes nothing; an imported record sends no message and raises no approval; and another
 * business reaches none of it. Fabricated data only: made-up item codes, buyers and amounts.
 */
class ImportCommitIT extends IntegrationTest {

    static final String SHA = "c".repeat(64);
    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static final String HQ = FixtureShopTemplate.HEAD_OFFICE;

    static final String ITEM_HEADER =
            "Fab Item Code,Fab Item Label,Fab Pack,Fab Asking Price,Fab Line Type,Fab In Use,Fab Shelf Note\r\n";

    /** Two lists of made-up items, so the items are two batches of one entity set. */
    static final String ITEMS_A = ITEM_HEADER
            + "FAB-101,Fabricated Ladle,pcs,1500,Goods,yes,\r\n"
            + "FAB-102,Fabricated Sieve,pcs,800,Goods,yes,\r\n"
            + "FAB-103,Fabricated Whisk,pcs,1200,Goods,yes,\r\n";

    static final String ITEMS_B = ITEM_HEADER + "FAB-104,Fabricated Grater,pcs,900,Goods,yes,\r\n"
            + "FAB-105,Fabricated Spatula,pcs,700,Goods,yes,\r\n";

    static final String BUYERS = "Fabricated buyer book,,,,,\r\n"
            + "Fab Buyer No,Fab Buyer,Fab Mobile,Fab Card No,Fab Since,Fab Remarks\r\n"
            + "B-101,Fabricated Buyer One,,,,\r\n"
            + "B-102,Fabricated Buyer Two,,,,\r\n";

    /** Two stock takes (14 on hand worth 9,000), two credit sales (3,900 owed) and a cash sale. */
    static final String TILL = "Fab Item Code,Fab Buyer No,Fab When,Fab Count,Fab Paid,Fab Entry Type,Fab Cashier\r\n"
            + "FAB-101,,15/09/2026 10:30,10,7000,Stock take,\r\n"
            + "FAB-102,,15/09/2026 10:40,4,2000,Stock take,\r\n"
            + "FAB-101,B-101,16/09/2026 09:00,1,1500,Sold,\r\n"
            + "FAB-103,B-102,16/09/2026 09:30,2,2400,Sold,\r\n"
            + "FAB-104,,16/09/2026 10:00,1,900,Sold,\r\n";

    static final long STOCK_VALUE = 9_000;
    static final long OWED = 3_900;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportValidation validation;

    @Autowired
    CatalogueHistory catalogue;

    @Autowired
    StockLedger stock;

    @Autowired
    Outbox outbox;

    @Autowired
    Approvals approvals;

    @Autowired
    CommitTestSeams seams;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    final UUID operator = UUID.randomUUID();

    /** The line of the till roll at which the writer fails, or 0 for none. */
    volatile int failAtLine;

    /** The statement timeout and the import setting a batch's transaction had, as the writer read them. */
    final Map<String, String> settings = new TreeMap<>();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("commit", true, true);
        FixtureShopTemplate.POLICIES = List.of(FixtureShopTemplate.RECEIVABLE);
    }

    @AfterEach
    void reset() {
        FixtureShopTemplate.SCRIPT = null;
        FixtureShopTemplate.POLICIES = List.of();
        seams.killAfter = 0;
        failAtLine = 0;
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---------------------------------------------------------------------------------------------
    // The run, its files and the writer.
    // ---------------------------------------------------------------------------------------------

    /** Uploads one file of the run through the quarantine with the fixture mapping's source. */
    Batch upload(TestDatabase.Fixture f, UUID runId, String name, String source, String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        Batch batch = as(f, () -> {
            UUID mapping = mappings.latest("fab-shop")
                    .orElseGet(() -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null))
                    .id();
            return uploads.upload(
                    runId,
                    new Upload(
                            name,
                            new ByteArrayInputStream(bytes),
                            bytes.length,
                            operator,
                            mappings.parseOptions(mapping, source)));
        });
        assertThat(batch.status()).as(name).isEqualTo("parsed");
        return batch;
    }

    /**
     * A run of the business with the till roll uploaded first (batch 1), then the second item list (2), the buyers (3) and
     * the first item list (4), validated: the commit's dependency order is items (2, 4), buyers (3), sales (1).
     */
    UUID fullRun(TestDatabase.Fixture f) {
        UUID runId = as(f, () -> runs.open(operator).id());
        upload(f, runId, "fab-till-roll.csv", "till_roll", TILL);
        upload(f, runId, "fab-item-list-b.csv", "item_list", ITEMS_B);
        upload(f, runId, "fab-buyers.csv", "buyer_book", BUYERS);
        upload(f, runId, "fab-item-list.csv", "item_list", ITEMS_A);
        as(f, () -> validation.validate(runId));
        return runId;
    }

    PolicyDecision decision(String option) {
        return Policy.DEFER.equals(option)
                ? new PolicyDecision(
                        option,
                        DAY,
                        "Question list item 3, signed",
                        operator,
                        Api.platformUser(Api.email("dev-lead")),
                        DAY.plusDays(30))
                : new PolicyDecision(option, DAY, "Question list item 3, signed", operator, null, null);
    }

    /** Records the decision, dry runs the run with the writer and moves it to ready with a checked dump and an approval. */
    void ready(TestDatabase.Fixture f, UUID runId, String option) {
        FixtureShopTemplate.SCRIPT = writer(f);
        if (option != null) {
            as(f, () -> runs.recordPolicy(runId, Policy.RECEIVABLE, decision(option)));
        }
        ImportRuns.DryRun dry = as(f, () -> runs.dryRun(runId, operator));
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        as(f, () -> runs.transition(runId, "ready"));
        as(f, () -> runs.recordPreImportDump(runId, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, f, runs, runId, SHA);
        wrote.clear();
    }

    /** The batch numbers the writer finished since the run was made ready, in the order it wrote them. */
    final List<Integer> wrote = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The batches of the run the writer wrote to the end, in order, checked against the batches committed. */
    List<Integer> committedOrder(UUID runId) {
        List<Integer> committed = TestDatabase.owner()
                .sql("SELECT seq FROM import_batches WHERE run_id = ? AND status = 'committed'")
                .param(runId)
                .query(Integer.class)
                .list();
        assertThat(wrote).containsExactlyInAnyOrderElementsOf(committed);
        return List.copyOf(wrote);
    }

    // JUSTIFICATION-A3: the writer a real template's loader would be, by the fixture's made-up rules (its forecast).
    /**
     * The commit's writer for the fixture by its made-up rules with the receivable decision (those of
     * {@link FixtureShopTemplate#forecast(Map)}): an item becomes a retail product; a stock take a stock movement at the head
     * office; a credit sale and a cash sale are history, registered only; then one {@code inventory} opening journal for the
     * stock's value, and under {@code open_from_list} one {@code trade_debtors} journal for what the credit sales left owed.
     * Every created record is registered in {@code import_refs}; a row already imported is counted existing and nothing is
     * written for it. It records the batch's statement timeout and import setting, and fails at {@link #failAtLine}.
     */
    BatchWriter writer(TestDatabase.Fixture f) {
        return (runId, batchId, seq, checkpoint) -> {
            settings.put(
                    "statement_timeout",
                    jdbc.sql("SELECT current_setting('statement_timeout')")
                            .query(String.class)
                            .single());
            settings.put(
                    "app.import_commit",
                    jdbc.sql("SELECT current_setting('app.import_commit', true)")
                            .query(String.class)
                            .single());
            String source = jdbc.sql("SELECT mapping_source FROM import_batches WHERE id = ?")
                    .param(batchId)
                    .query(String.class)
                    .single();
            int read = 0;
            int written = 0;
            int existing = 0;
            BigDecimal qty = BigDecimal.ZERO;
            long value = 0;
            long owed = 0;
            int credit = 0;
            Ensured category = "item_list".equals(source) ? catalogue.ensureCategory("Fabricated imports") : null;
            Ensured unit = "item_list".equals(source) ? catalogue.ensureUnit("piece") : null;
            Map<String, CatalogueHistory.Product> products =
                    "till_roll".equals(source) ? catalogue.productsByCode() : Map.of();
            int after = 0;
            while (true) {
                List<ImportRefs.StagedRow> rows = checkpoint.rows(after, 1_000);
                for (ImportRefs.StagedRow row : rows) {
                    after = row.line();
                    read++;
                    if (row.status() == ImportRefs.Status.EXISTING) {
                        existing++;
                        checkpoint.rowWritten();
                        continue;
                    }
                    Map<String, Object> v = row.values();
                    switch (source) {
                        case "item_list" -> {
                            Long price = (Long) v.get("price");
                            Ensured p = catalogue.ensureProduct(
                                    (String) v.get("code"),
                                    (String) v.get("name"),
                                    category.id(),
                                    unit.id(),
                                    0,
                                    price == null ? 0 : price,
                                    true);
                            checkpoint.register(row.sourceRef(), "retail.product", p.id());
                            written++;
                        }
                        case "till_roll" -> {
                            if (row.line() == failAtLine) {
                                throw new IllegalStateException("fabricated failure of the writer");
                            }
                            long amount = (Long) v.get("amount");
                            BigDecimal count = (BigDecimal) v.get("qty");
                            if ("adjustment".equals(v.get("kind"))) {
                                UUID product = products.get(((String) v.get("item")).toLowerCase(Locale.ROOT))
                                        .id();
                                UUID movement = stock.recordHistorical(
                                                DAY,
                                                List.of(new HistoricalMovement(
                                                        f.headOffice(),
                                                        product,
                                                        "legacy_balance",
                                                        count,
                                                        amount / count.longValueExact(),
                                                        (Instant) v.get("sold_at"),
                                                        "test_import",
                                                        null,
                                                        null,
                                                        "Fabricated stock take")))
                                        .getFirst();
                                checkpoint.register(row.sourceRef(), "retail.stock_movement", movement);
                                qty = qty.add(count);
                                value += amount;
                            } else {
                                boolean onCredit = FixtureShopTemplate.RECEIVABLE
                                        .neededBy()
                                        .test(v);
                                checkpoint.register(
                                        row.sourceRef(),
                                        onCredit ? "test.credit_sale" : "test.cash_sale",
                                        UUID.nameUUIDFromBytes(row.sourceRef().getBytes(StandardCharsets.UTF_8)));
                                if (onCredit) {
                                    owed += amount;
                                    credit++;
                                }
                            }
                            written++;
                        }
                        default -> written++;
                    }
                    checkpoint.rowWritten();
                }
                if (rows.size() < 1_000) {
                    break;
                }
            }
            if (value != 0) {
                checkpoint.openingJournal(
                        new ImportRefs.Opening(f.headOffice(), HQ, "inventory", DAY, "inventory", true, value));
                checkpoint.stock(HQ, qty, value);
            }
            String option = checkpoint.policy(Policy.RECEIVABLE).orElse(null);
            if (Policy.OPEN_FROM_LIST.equals(option) && owed != 0) {
                checkpoint.openingJournal(
                        new ImportRefs.Opening(f.headOffice(), HQ, "trade_debtors", DAY, "trade_debtors", true, owed));
                checkpoint.receivable(HQ, owed);
            }
            if (Policy.DEFER.equals(option) && credit > 0) {
                checkpoint.note(credit + " credit sales deferred to the dated follow-up; no receivable opened");
            }
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("read", read);
            counts.put("written", written);
            counts.put("existing", existing);
            wrote.add(seq);
            return counts;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // What the business holds.
    // ---------------------------------------------------------------------------------------------

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    /**
     * What the business holds after an import, by counts and totals only (ids differ between businesses): products, stock
     * movements and the stock on hand, journals and their totals, and import_refs by target type.
     */
    Map<String, Object> state(TestDatabase.Fixture f) {
        UUID tenant = f.tenantId();
        Map<String, Object> s = new TreeMap<>();
        s.put(
                "products",
                count("SELECT count(*) FROM retail_products WHERE tenant_id = ? AND code LIKE 'FAB-1%'", tenant));
        s.put(
                "movements",
                count(
                        "SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ?"
                                + " AND source_type = 'test_import'",
                        tenant));
        s.put(
                "on_hand",
                TestDatabase.owner()
                        .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE tenant_id = ?")
                        .param(tenant)
                        .query(BigDecimal.class)
                        .single()
                        .stripTrailingZeros()
                        .toPlainString());
        s.put("journals", count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?", tenant));
        s.put("debits", count("SELECT coalesce(sum(debit), 0) FROM journal_lines WHERE tenant_id = ?", tenant));
        s.put("credits", count("SELECT coalesce(sum(credit), 0) FROM journal_lines WHERE tenant_id = ?", tenant));
        TestDatabase.owner()
                .sql("SELECT target_type, count(*) FROM import_refs WHERE tenant_id = ? GROUP BY target_type")
                .param(tenant)
                .query((rs, n) -> s.put("refs " + rs.getString(1), rs.getLong(2)))
                .list();
        return s;
    }

    /** Every journal of the business is balanced and is an opening journal the import registered; none other exists. */
    void onlyBalancedOpeningJournals(TestDatabase.Fixture f) {
        UUID tenant = f.tenantId();
        assertThat(count(
                        "SELECT count(*) FROM journal_entries e WHERE e.tenant_id = ?"
                                + " AND (SELECT sum(debit) FROM journal_lines l WHERE l.entry_id = e.id)"
                                + " <> (SELECT sum(credit) FROM journal_lines l WHERE l.entry_id = e.id)",
                        tenant))
                .as("unbalanced journals")
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM journal_entries e WHERE e.tenant_id = ?"
                                + " AND (e.source_module <> 'core.imports' OR e.source_type <> 'opening_balance'"
                                + " OR NOT EXISTS (SELECT 1 FROM import_refs r"
                                + " WHERE r.target_id = e.id AND r.target_type = 'ledger.journal_entry'))",
                        tenant))
                .as("journals other than the import's registered opening journals")
                .isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // The commit, its order and its journals.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aRunCommitsItsBatchesInDependencyOrderOncePerBatchWithBalancedOpeningJournalsOnly() {
        UUID run = fullRun(t);
        ready(t, run, Policy.OPEN_FROM_LIST);
        // The dry run keeps its own time limit (15 minutes, not trading) for its statements, not the commit's.
        assertThat(settings).containsEntry("app.import_commit", "on");
        assertThat(settings.get("statement_timeout")).isEqualTo("15min");

        assertThat(as(t, () -> runs.commit(run))).isEqualTo(CommitOutcome.COMMITTED);

        // Items (batches 2 and 4) before the buyers (3) before the sales (1), whatever the upload order.
        assertThat(committedOrder(run)).containsExactly(2, 4, 3, 1);
        assertThat(settings).containsEntry("statement_timeout", "10min").containsEntry("app.import_commit", "on");
        assertThat(state(t))
                .containsEntry("products", 5L)
                .containsEntry("movements", 2L)
                .containsEntry("on_hand", "14")
                .containsEntry("journals", 2L)
                .containsEntry("debits", STOCK_VALUE + OWED)
                .containsEntry("credits", STOCK_VALUE + OWED)
                .containsEntry("refs retail.product", 5L)
                .containsEntry("refs retail.stock_movement", 2L)
                .containsEntry("refs test.credit_sale", 2L)
                .containsEntry("refs test.cash_sale", 1L)
                .containsEntry("refs ledger.journal_entry", 2L);
        onlyBalancedOpeningJournals(t);
        // The real run's report is the dry run's (spec section 3.6), journals read back from the ledger.
        CommitReport real = as(t, () -> runs.commitReport(run));
        assertThat(real.journals())
                .extracting(CommitReport.Journal::kind, CommitReport.Journal::debitMinor, CommitReport.Journal::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("inventory", STOCK_VALUE, "posted"),
                        org.assertj.core.groups.Tuple.tuple("trade_debtors", OWED, "posted"));
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().report().sameAs(real))
                .as("dry run and real run")
                .isTrue();
        // The import's opening journals are not trading: the ledger's probe does not count them.
        assertThat(count(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_module = 'core.imports'",
                        t.tenantId()))
                .isEqualTo(2);
    }

    @Test
    void killingTheProcessAfterBatchKAndResumingGivesTheUninterruptedStateWithNothingTwice() {
        // The uninterrupted run, in a business of its own.
        TestDatabase.Fixture whole = TestDatabase.tenant("commit-whole", true, true);
        UUID straight = fullRun(whole);
        ready(whole, straight, Policy.OPEN_FROM_LIST);
        assertThat(as(whole, () -> runs.commit(straight))).isEqualTo(CommitOutcome.COMMITTED);
        Map<String, Object> expected = state(whole);

        // The same run here, the process killed right after its second batch (batch 4) committed.
        UUID run = fullRun(t);
        ready(t, run, Policy.OPEN_FROM_LIST);
        seams.killAfter = 4;
        assertThatThrownBy(() -> as(t, () -> runs.commit(run))).isInstanceOf(CommitTestSeams.Killed.class);
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");
        assertThat(committedOrder(run)).containsExactly(2, 4);
        assertThat(state(t))
                .containsEntry("products", 5L)
                .containsEntry("movements", 0L)
                .containsEntry("journals", 0L);

        // The resume goes on from the first batch not committed (the buyers, then the sales) and ends committed.
        assertThat(as(t, () -> runs.resume(run))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(committedOrder(run)).containsExactly(2, 4, 3, 1);
        assertThat(state(t)).isEqualTo(expected);
        onlyBalancedOpeningJournals(t);
        assertThat(count(
                        "SELECT count(*) FROM (SELECT source_ref, target_type FROM import_refs WHERE tenant_id = ?"
                                + " GROUP BY 1, 2 HAVING count(*) > 1) d",
                        t.tenantId()))
                .as("a record registered twice")
                .isZero();
        // A committed run neither resumes nor commits again.
        assertCode(() -> as(t, () -> runs.resume(run)), "invalid_status_transition");
        assertThat(state(t)).isEqualTo(expected);
    }

    @Autowired
    ImportRunService service;

    // JUSTIFICATION-A3: the Lead's ruling 8 on #872: each refusal of a resume tested on its own, then the resume.
    @Test
    void aResumeIsRefusedWithoutTheVerifiedDumpTheApprovalOrTheRehearsedBuild() {
        UUID run = fullRun(t);
        ready(t, run, Policy.OPEN_FROM_LIST);
        seams.killAfter = 4;
        assertThatThrownBy(() -> as(t, () -> runs.commit(run))).isInstanceOf(CommitTestSeams.Killed.class);
        seams.killAfter = 0;
        Map<String, Object> left = state(t);

        // No verified dump: the check is cleared, the resume refused, the check put back.
        Map<String, Object> dump = TestDatabase.owner()
                .sql("SELECT pre_import_dump_verified_at, pre_import_dump_entries FROM import_runs WHERE id = ?")
                .param(run)
                .query()
                .singleRow();
        TestDatabase.owner()
                .sql("UPDATE import_runs SET pre_import_dump_verified_at = NULL, pre_import_dump_entries = NULL"
                        + " WHERE id = ?")
                .param(run)
                .update();
        assertCode(() -> as(t, () -> runs.resume(run)), "pre_import_dump_unverified");
        TestDatabase.owner()
                .sql("UPDATE import_runs SET pre_import_dump_verified_at = ?, pre_import_dump_entries = ? WHERE id = ?")
                .params(dump.get("pre_import_dump_verified_at"), dump.get("pre_import_dump_entries"), run)
                .update();

        // No approval: the approval is cleared, the resume refused, the approval put back.
        Map<String, Object> approval = TestDatabase.owner()
                .sql("SELECT commit_approval_id, commit_approved_by, commit_approved_at, commit_approved_version"
                        + " FROM import_runs WHERE id = ?")
                .param(run)
                .query()
                .singleRow();
        TestDatabase.owner()
                .sql("UPDATE import_runs SET commit_approval_id = NULL, commit_approved_by = NULL,"
                        + " commit_approved_at = NULL, commit_approved_version = NULL WHERE id = ?")
                .param(run)
                .update();
        assertCode(() -> as(t, () -> runs.resume(run)), "commit_not_approved");
        TestDatabase.owner()
                .sql("UPDATE import_runs SET commit_approval_id = ?, commit_approved_by = ?, commit_approved_at = ?,"
                        + " commit_approved_version = ? WHERE id = ?")
                .params(
                        approval.get("commit_approval_id"),
                        approval.get("commit_approved_by"),
                        approval.get("commit_approved_at"),
                        approval.get("commit_approved_version"),
                        run)
                .update();

        // Another build than the one the dry run rehearsed.
        String had = service.buildIdentity("other-fabricated-build");
        try {
            assertCode(() -> as(t, () -> runs.resume(run)), "dry_run_writer_differs");
        } finally {
            service.buildIdentity(had);
        }

        // Nothing was written by a refused resume; with all three back it goes on and ends committed.
        assertThat(state(t)).isEqualTo(left);
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");
        assertThat(as(t, () -> runs.resume(run))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(committedOrder(run)).containsExactly(2, 4, 3, 1);
    }

    @Test
    void aFailingBatchIsRolledBackAloneAndTheRunGoesOnFromIt() {
        UUID run = fullRun(t);
        ready(t, run, Policy.OPEN_FROM_LIST);
        // The till roll's fourth row fails after two stock takes and a credit sale were written in its transaction.
        failAtLine = 5;

        assertThatThrownBy(() -> as(t, () -> runs.commit(run))).isInstanceOf(IllegalStateException.class);

        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");
        assertThat(committedOrder(run)).containsExactly(2, 4, 3);
        assertThat(state(t))
                .containsEntry("products", 5L)
                .containsEntry("movements", 0L)
                .containsEntry("on_hand", "0")
                .containsEntry("journals", 0L)
                .containsEntry("refs retail.product", 5L)
                .doesNotContainKey("refs retail.stock_movement")
                .doesNotContainKey("refs test.credit_sale")
                .doesNotContainKey("refs ledger.journal_entry");
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.batch_failed'",
                        t.tenantId()))
                .isEqualTo(1);

        failAtLine = 0;
        assertThat(as(t, () -> runs.resume(run))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(state(t))
                .containsEntry("movements", 2L)
                .containsEntry("journals", 2L)
                .containsEntry("refs retail.stock_movement", 2L)
                .containsEntry("refs ledger.journal_entry", 2L);
        onlyBalancedOpeningJournals(t);
    }

    // ---------------------------------------------------------------------------------------------
    // The receivable decision (spec section 3.8).
    // ---------------------------------------------------------------------------------------------

    @Test
    void eachReceivableDecisionGivesItsDocumentedJournals() {
        Map<String, Map<String, Long>> journals = new TreeMap<>();
        Map<String, List<String>> notes = new TreeMap<>();
        for (String option : List.of(Policy.OPEN_FROM_LIST, Policy.TREAT_AS_SETTLED, Policy.DEFER)) {
            TestDatabase.Fixture f = TestDatabase.tenant("commit-" + option.replace('_', '-'), true, true);
            UUID run = fullRun(f);
            ready(f, run, option);
            assertThat(as(f, () -> runs.commit(run))).isEqualTo(CommitOutcome.COMMITTED);
            onlyBalancedOpeningJournals(f);
            Map<String, Long> byKind = new TreeMap<>();
            CommitReport report = as(f, () -> runs.commitReport(run));
            report.journals().forEach(j -> byKind.put(j.kind(), j.debitMinor()));
            journals.put(option, byKind);
            notes.put(option, report.notes());
            assertThat(state(f)).containsEntry("journals", (long) byKind.size());
        }
        // Open from the customer's list: debit receivables, credit opening balance equity, beside the stock.
        assertThat(journals.get(Policy.OPEN_FROM_LIST))
                .containsExactlyInAnyOrderEntriesOf(Map.of("inventory", STOCK_VALUE, "trade_debtors", OWED));
        // Treat as settled and defer: no receivable at all; the credit sales are history only.
        assertThat(journals.get(Policy.TREAT_AS_SETTLED))
                .containsExactlyInAnyOrderEntriesOf(Map.of("inventory", STOCK_VALUE));
        assertThat(journals.get(Policy.DEFER)).containsExactlyInAnyOrderEntriesOf(Map.of("inventory", STOCK_VALUE));
        assertThat(notes.get(Policy.DEFER))
                .containsExactly("2 credit sales deferred to the dated follow-up; no receivable opened");
        assertThat(notes.get(Policy.TREAT_AS_SETTLED)).isEmpty();
    }

    @Test
    void aDecisionIsOneOfTheTemplatesOptionsConfirmedAndDeferNeedsTheDevLeadsException() {
        UUID run = fullRun(t);
        assertCode(
                () -> as(t, () -> runs.recordPolicy(run, "loan_disbursed", decision(Policy.TREAT_AS_SETTLED))),
                "policy_unknown");
        assertCode(
                () -> as(t, () -> runs.recordPolicy(run, Policy.RECEIVABLE, decision("write_it_off"))),
                "policy_option_unknown");
        assertCode(
                () -> as(
                        t,
                        () -> runs.recordPolicy(
                                run,
                                Policy.RECEIVABLE,
                                new PolicyDecision(Policy.TREAT_AS_SETTLED, DAY, "", operator, null, null))),
                "policy_confirmation_required");
        // No confirmation at all is the same refusal, never a NullPointerException (review of #886, issue #887).
        assertCode(
                () -> as(
                        t,
                        () -> runs.recordPolicy(
                                run,
                                Policy.RECEIVABLE,
                                new PolicyDecision(Policy.TREAT_AS_SETTLED, DAY, null, operator, null, null))),
                "policy_confirmation_required");
        // Defer without the exception, or with an id that is no platform operator's.
        assertCode(
                () -> as(
                        t,
                        () -> runs.recordPolicy(
                                run,
                                Policy.RECEIVABLE,
                                new PolicyDecision(Policy.DEFER, DAY, "Question list item 3", operator, null, DAY))),
                "policy_exception_required");
        assertCode(
                () -> as(
                        t,
                        () -> runs.recordPolicy(
                                run,
                                Policy.RECEIVABLE,
                                new PolicyDecision(
                                        Policy.DEFER, DAY, "Question list item 3", operator, UUID.randomUUID(), DAY))),
                "policy_exception_required");
        ImportRuns.Run recorded = as(t, () -> runs.recordPolicy(run, Policy.RECEIVABLE, decision(Policy.DEFER)));
        assertThat(recorded.status()).isEqualTo("in_review");
        assertThat(TestDatabase.owner()
                        .sql("SELECT policy -> 'receivable' ->> 'option' FROM import_runs WHERE id = ?")
                        .param(run)
                        .query(String.class)
                        .single())
                .isEqualTo(Policy.DEFER);
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.policy_recorded'",
                        t.tenantId()))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // The refusals (acceptance criterion 4).
    // ---------------------------------------------------------------------------------------------

    @Autowired
    ImportPurges purges;

    /** The application's notice to the checker after the file count (ImportRunService.requestCommit), word for word. */
    static final String NOTICE_TAIL = " checked files of customer data into the business's records and opening"
            + " balances. It cannot be undone except by restoring the dump taken before it.";

    // JUSTIFICATION-A3: the Lead's ruling on the review of #907 asks for this case through the real services.
    /**
     * Lead on the review of #907: an import_commit request made by the real maker's request and still pending when its
     * run is purged is cancelled in the purge's transaction (V150), and the checker's decision after it is refused, so
     * no note is ever typed on a purged run. Its payload, the one kept readable, holds no value of the run.
     */
    @Test
    void aPendingCommitRequestIsCancelledByThePurgeAndCannotBeDecidedAfter() {
        UUID run = fullRun(t);
        FixtureShopTemplate.SCRIPT = writer(t);
        as(t, () -> runs.recordPolicy(run, Policy.RECEIVABLE, decision(Policy.TREAT_AS_SETTLED)));
        as(t, () -> runs.dryRun(run, operator));
        as(t, () -> runs.transition(run, "ready"));
        as(t, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.verifyDump(t, runs, run, SHA);
        UUID request = CommitApproval.request(t, runs, run, UUID.randomUUID());
        assertThat(count("SELECT count(*) FROM approval_requests WHERE id = ? AND status = 'pending'", request))
                .isEqualTo(1);
        // Signed off as the owner, standing in for the commit and the sign-off, so the operator may purge it.
        TestDatabase.owner()
                .sql("UPDATE import_runs SET status = 'signed_off', signed_off_at = now() WHERE id = ?")
                .param(run)
                .update();
        String operatorAt = Api.email("purge-operator");
        String witnessAt = Api.email("purge-witness");
        Api.platformUser(operatorAt);
        Api.platformUser(witnessAt);
        Purge purge = new Purge(operatorAt, witnessAt, "pre-import-fabricated.dump", SHA);
        as(t, () -> purges.purge(run, purge));

        // The payload the purge keeps readable is proven value-free (review of #907, round 2): exactly the run's id,
        // the file count as a number and the application's fixed notice, nothing else.
        String cancelled = "SELECT count(*) FROM approval_requests WHERE id = ? AND status = 'cancelled'"
                + " AND decision_note IS NULL AND execution_error IS NULL"
                + " AND (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(payload) k)"
                + " = ARRAY['batches', 'checker_notice', 'run_id']"
                + " AND jsonb_typeof(payload -> 'batches') = 'number' AND payload ->> 'run_id' = ?"
                + " AND payload ->> 'checker_notice' = 'This writes ' || (payload ->> 'batches') || ?";
        assertThat(count(cancelled, request, run.toString(), NOTICE_TAIL)).isEqualTo(1);
        ResponseEntity<JsonNode> late = CommitApproval.approve(http, t, request, UUID.randomUUID());
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
        assertThat(count(cancelled, request, run.toString(), NOTICE_TAIL))
                .as("still cancelled, with no note")
                .isEqualTo(1);
    }

    // JUSTIFICATION-A3: one test walks every refusal of acceptance criterion 4 on one run, as an operator meets them.
    @Test
    void theCommitIsRefusedWithoutTheDumpTheApprovalOrADecisionAndByTheMakerAsChecker() {
        UUID run = fullRun(t);
        FixtureShopTemplate.SCRIPT = writer(t);
        as(t, () -> runs.dryRun(run, operator));
        // A run with credit sales is not ready without the receivable decision.
        assertCode(() -> as(t, () -> runs.transition(run, "ready")), "CREDIT_DECISION_MISSING");
        as(t, () -> runs.recordPolicy(run, Policy.RECEIVABLE, decision(Policy.TREAT_AS_SETTLED)));
        as(t, () -> runs.dryRun(run, operator));
        as(t, () -> runs.transition(run, "ready"));

        // No dump, then a dump not checked, then a check of another dump.
        assertCode(() -> as(t, () -> runs.commit(run)), "pre_import_dump_missing");
        assertCode(() -> CommitApproval.request(t, runs, run, UUID.randomUUID()), "pre_import_dump_missing");
        as(t, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", Instant.now(), SHA));
        assertCode(() -> as(t, () -> runs.commit(run)), "pre_import_dump_unverified");
        assertCode(() -> as(t, () -> runs.verifyPreImportDump(run, "d".repeat(64), 42)), "pre_import_dump_differs");
        assertCode(() -> as(t, () -> runs.verifyPreImportDump(run, SHA, 0)), "invalid_pre_import_dump");
        CommitApproval.verifyDump(t, runs, run, SHA);

        // No approval.
        assertCode(() -> as(t, () -> runs.commit(run)), "commit_not_approved");
        // Asked by someone without core.imports.manage.
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> as(t, () -> {
                    CurrentPrincipal.set(
                            Principal.uniform(stranger, "staff", Set.of("core.approvals.read"), true, Set.of()));
                    try {
                        return runs.requestCommit(run);
                    } finally {
                        CurrentPrincipal.clear();
                    }
                }))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));

        // The maker as checker: the approvals refuse it, and so does the database on the run itself.
        UUID maker = UUID.randomUUID();
        UUID approval = CommitApproval.request(t, runs, run, maker);
        ResponseEntity<JsonNode> self = CommitApproval.approve(http, t, approval, maker);
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(self.getBody().get("code").asString()).isEqualTo("self_approval_forbidden");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_runs SET commit_approval_id = ?, commit_approved_by = commit_requested_by,"
                                + " commit_approved_at = now(), commit_approved_version = version WHERE id = ?")
                        .params(approval, run)
                        .update())
                .hasMessageContaining("import_runs_commit_checker_not_maker");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE approval_requests SET status = 'approved', decided_by = requested_by,"
                                + " decided_at = now() WHERE id = ?")
                        .param(approval)
                        .update())
                .hasMessageContaining("approval_checker_is_not_maker");
        assertCode(() -> as(t, () -> runs.commit(run)), "commit_not_approved");

        // A different user approves; a change to the run after it (a new dump) needs a new approval.
        assertThat(CommitApproval.approve(http, t, approval, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(as(t, () -> runs.get(run)).commitApproved()).isTrue();
        as(t, () -> runs.recordPreImportDump(run, "pre-import-fabricated-2.dump", Instant.now(), SHA));
        CommitApproval.verifyDump(t, runs, run, SHA);
        assertCode(() -> as(t, () -> runs.commit(run)), "commit_not_approved");
        CommitApproval.approved(http, t, runs, run);

        // A decision missing at the commit itself: the template now needs one this run never recorded.
        FixtureShopTemplate.POLICIES = List.of(
                FixtureShopTemplate.RECEIVABLE,
                new Policy(
                        "fabricated_decision",
                        List.of("yes", "no"),
                        Set.of(),
                        "CREDIT_DECISION_MISSING",
                        "sales",
                        values -> true));
        assertCode(() -> as(t, () -> runs.commit(run)), "CREDIT_DECISION_MISSING");
        FixtureShopTemplate.POLICIES = List.of(FixtureShopTemplate.RECEIVABLE);

        assertThat(state(t)).containsEntry("journals", 0L).containsEntry("products", 0L);
        assertThat(as(t, () -> runs.commit(run))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action IN"
                                + " ('core.import_run.commit_requested', 'core.import_run.commit_approved',"
                                + " 'core.import_run.dump_verified')",
                        t.tenantId()))
                .isEqualTo(6);
    }

    // ---------------------------------------------------------------------------------------------
    // import_refs: CHANGED_SINCE_IMPORT and a re-run (spec sections 3.7 and 10.1).
    // ---------------------------------------------------------------------------------------------

    /** A run of one item list, validated, committed with no decision (it holds no sale). */
    UUID itemsRun(TestDatabase.Fixture f, String csv) {
        UUID runId = as(f, () -> runs.open(operator).id());
        upload(f, runId, "fab-item-list.csv", "item_list", csv);
        as(f, () -> validation.validate(runId));
        return runId;
    }

    @Test
    void aRowImportedBeforeWithOtherContentIsChangedSinceImportAndNotWrittenTwice() {
        UUID first = itemsRun(t, ITEMS_A);
        ready(t, first, null);
        assertThat(as(t, () -> runs.commit(first))).isEqualTo(CommitOutcome.COMMITTED);

        // The customer's sheet again: FAB-101's price changed, FAB-102 and FAB-103 as before, FAB-106 new.
        String edited = ITEM_HEADER
                + "FAB-101,Fabricated Ladle,pcs,1600,Goods,yes,\r\n"
                + "FAB-102,Fabricated Sieve,pcs,800,Goods,yes,\r\n"
                + "FAB-103,Fabricated Whisk,pcs,1200,Goods,yes,\r\n"
                + "FAB-106,Fabricated Funnel,pcs,500,Goods,yes,\r\n";
        UUID second = itemsRun(t, edited);
        UUID batch = TestDatabase.owner()
                .sql("SELECT id FROM import_batches WHERE run_id = ?")
                .param(second)
                .query(UUID.class)
                .single();
        List<String> changed = as(t, () -> validation.issues(batch)).stream()
                .filter(i -> "CHANGED_SINCE_IMPORT".equals(i.code()))
                .map(i -> i.line() + " " + i.severity() + " " + i.status())
                .toList();
        // Line 2 (FAB-101) only, blocking and open: it is never written.
        assertThat(changed).containsExactly("2 blocking open");
        assertThat(as(t, () -> validation.issues(batch)))
                .extracting(ImportValidation.Issue::code)
                .contains("CHANGED_SINCE_IMPORT");
        assertThat(as(t, () -> validation.issues(batch)))
                .filteredOn(i -> "CHANGED_SINCE_IMPORT".equals(i.code()))
                .allSatisfy(i -> assertThat(i.message()).doesNotContain("1600").doesNotContain("Ladle"));

        ready(t, second, null);
        assertThat(as(t, () -> runs.commit(second))).isEqualTo(CommitOutcome.COMMITTED);
        CommitReport report = as(t, () -> runs.commitReport(second));
        // Three rows to write (FAB-101 is held back): two already imported, one new.
        assertThat(report.sources()).singleElement().satisfies(s -> {
            assertThat(s.read()).isEqualTo(3);
            assertThat(s.written()).isEqualTo(1);
            assertThat(s.existing()).isEqualTo(2);
        });
        assertThat(state(t)).containsEntry("products", 4L).containsEntry("refs retail.product", 4L);
        assertThat(TestDatabase.owner()
                        .sql("SELECT sell_minor FROM retail_products WHERE tenant_id = ? AND code = 'FAB-101'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("the imported item is not changed by a later import")
                .isEqualTo(1500);

        // A re-run of the same rows writes nothing: every row is already imported, and its preview predicts nothing.
        UUID third = itemsRun(t, ITEMS_A + "FAB-106,Fabricated Funnel,pcs,500,Goods,yes,\r\n");
        ready(t, third, null);
        Map<String, Object> before = state(t);
        assertThat(as(t, () -> runs.commit(third))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(state(t)).isEqualTo(before);
        assertThat(as(t, () -> runs.commitReport(third)).sources())
                .singleElement()
                .satisfies(s -> assertThat(s.existing()).isEqualTo(4));
    }

    @Test
    void theSourceRefIsOneFunctionOfFileTabRowAndContent() {
        String hash = ImportRefs.contentHash(Map.of("code", "FAB-101", "price", 1500L, "qty", new BigDecimal("1.10")));
        // A number in any form hashes alike: the validation's values and the same values read back from the database.
        assertThat(ImportRefs.contentHash(Map.of("qty", new BigDecimal("1.1"), "price", 1500, "code", "FAB-101")))
                .isEqualTo(hash);
        assertThat(ImportRefs.contentHash(Map.of("code", "FAB-101", "price", 1600L, "qty", new BigDecimal("1.1"))))
                .isNotEqualTo(hash);
        String ref = ImportRefs.sourceRef("fab-shop-book.xlsx", "Fab Till Roll", "line:7", hash);
        assertThat(ref).isEqualTo("fab-shop-book.xlsx|Fab Till Roll|line:7#" + hash);
        assertThat(ImportRefs.keyOf(ref))
                .isEqualTo(ImportRefs.sourceKey("fab-shop-book.xlsx", "Fab Till Roll", "line:7"));
        assertThat(ImportRefs.sourceRef("fab-item-list.csv", null, "ref:FAB-101", hash))
                .startsWith("fab-item-list.csv|-|ref:FAB-101#");
        assertThat(RefService.row("col:item_list:FAB-101", "item_list", 3)).isEqualTo("ref:FAB-101");
        assertThat(RefService.row("row:buyer_book:4:0123456789abcdef", "buyer_book", 4))
                .isEqualTo("line:4");
        assertThat(ImportRefs.sourceKey("x".repeat(400), null, "line:1")).startsWith("sha256:");
    }

    // ---------------------------------------------------------------------------------------------
    // The review of f3be0a6 on #872.
    // JUSTIFICATION-A3: the reviewer's must-fix 1 and 2 and items 7 and 11, each proven by a test of its own.
    // ---------------------------------------------------------------------------------------------

    /**
     * A second till roll whose lines 2 and 3 hold other content: a stock take of the head office (3 on hand worth 1,500),
     * so the run has two stock files for one branch, and a cash sale.
     */
    static final String TILL_TWO =
            "Fab Item Code,Fab Buyer No,Fab When,Fab Count,Fab Paid,Fab Entry Type,Fab Cashier\r\n"
                    + "FAB-103,,17/09/2026 10:30,3,1500,Stock take,\r\n"
                    + "FAB-102,,17/09/2026 10:40,1,800,Sold,\r\n";

    @Test
    void twoTillRollsOfOneRunAndNextMonthsRollNeverShareALineKeyAndPostAJournalEach() {
        // Two till rolls in one run, each with a line 2 and a line 3: keyed by their upload, they never collide, and
        // the two stock files of the head office post an inventory journal each (Lead rulings 1 and 2 on #872).
        UUID run = fullRun(t);
        Batch second = upload(t, run, "fab-till-roll-week-2.csv", "till_roll", TILL_TWO);
        as(t, () -> validation.validate(run));
        assertThat(as(t, () -> validation.issues(second.id())))
                .extracting(ImportValidation.Issue::code)
                .doesNotContain("CHANGED_SINCE_IMPORT");
        ready(t, run, Policy.OPEN_FROM_LIST);
        assertThat(as(t, () -> runs.commit(run))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(state(t))
                .containsEntry("refs retail.stock_movement", 3L)
                .containsEntry("refs test.cash_sale", 2L)
                .containsEntry("on_hand", "17")
                .containsEntry("journals", 3L)
                .containsEntry("debits", STOCK_VALUE + 1_500 + OWED);
        assertThat(as(t, () -> runs.commitReport(run)).journals())
                .extracting(CommitReport.Journal::kind, CommitReport.Journal::debitMinor)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("inventory", STOCK_VALUE),
                        org.assertj.core.groups.Tuple.tuple("inventory", 1_500L),
                        org.assertj.core.groups.Tuple.tuple("trade_debtors", OWED));
        onlyBalancedOpeningJournals(t);
        assertThat(count(
                        "SELECT count(DISTINCT source_key) FROM import_refs WHERE tenant_id = ? AND source_key LIKE"
                                + " 'upload:%|line:2'",
                        t.tenantId()))
                .isEqualTo(2);

        // Next month's roll in a new run, with the item list again (every item already imported, so written no more):
        // the same lines with other content, never reported as changed since import, and committed (Lead's note on
        // the review of 8e570b0, #872), its stock take posting an inventory journal of its own.
        UUID next = as(t, () -> runs.open(operator).id());
        Batch month =
                upload(t, next, "fab-till-roll-october.csv", "till_roll", TILL_TWO.replace("17/09/2026", "17/10/2026"));
        upload(t, next, "fab-item-list.csv", "item_list", ITEMS_A);
        as(t, () -> validation.validate(next));
        assertThat(as(t, () -> validation.issues(month.id())))
                .extracting(ImportValidation.Issue::code)
                .doesNotContain("CHANGED_SINCE_IMPORT");
        ready(t, next, Policy.OPEN_FROM_LIST);
        assertThat(as(t, () -> runs.commit(next))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(state(t))
                .containsEntry("products", 5L)
                .containsEntry("refs retail.stock_movement", 4L)
                .containsEntry("refs test.cash_sale", 3L)
                .containsEntry("journals", 4L);
        onlyBalancedOpeningJournals(t);
        assertThat(count(
                        "SELECT count(DISTINCT source_key) FROM import_refs WHERE tenant_id = ? AND source_key LIKE"
                                + " 'upload:%|line:2'",
                        t.tenantId()))
                .isEqualTo(3);
    }

    @Test
    void twoBatchesThatHoldOneBranchsStockPostAnOpeningJournalEach() {
        UUID run = fullRun(t);
        List<UUID> batches = TestDatabase.owner()
                .sql("SELECT id FROM import_batches WHERE run_id = ? ORDER BY seq")
                .param(run)
                .query(UUID.class)
                .list();
        List<String> statuses = as(
                t,
                () -> new TransactionTemplate(transactionManager).execute(s -> {
                    s.setRollbackOnly();
                    ImportRefs.Opening stock =
                            new ImportRefs.Opening(t.headOffice(), HQ, "inventory", DAY, "inventory", true, 700);
                    List<String> out = new ArrayList<>();
                    out.add(refs.openingJournal(run, batches.get(0), stock).status());
                    out.add(refs.openingJournal(run, batches.get(1), stock).status());
                    out.add(refs.openingJournal(run, batches.get(0), stock).status());
                    out.add(String.valueOf(
                            jdbc.sql("SELECT count(*) FROM import_refs WHERE target_type = 'ledger.journal_entry'")
                                    .query(Long.class)
                                    .single()));
                    return out;
                }));
        // A journal each; the first batch's again is the one it posted, never a refusal that would stop the run.
        assertThat(statuses).containsExactly("posted", "posted", "already posted", "2");
    }

    @Test
    void aDecisionChangedAfterTheDryRunMakesItStopCounting() {
        UUID run = fullRun(t);
        FixtureShopTemplate.SCRIPT = writer(t);
        as(t, () -> runs.recordPolicy(run, Policy.RECEIVABLE, decision(Policy.TREAT_AS_SETTLED)));
        as(t, () -> runs.dryRun(run, operator));
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().current()).isTrue();

        // The stamp holds the policy, not the run's version (Lead's note on the review of 8e570b0, #872): the version
        // alone moved leaves the dry run counting, and the option alone changed, the version kept, makes it stop.
        TestDatabase.owner()
                .sql("UPDATE import_runs SET version = version + 1 WHERE id = ?")
                .param(run)
                .update();
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().current()).isTrue();
        int version = as(t, () -> runs.get(run)).version();
        TestDatabase.owner()
                .sql("UPDATE import_runs SET policy = jsonb_set(policy, '{receivable,option}', '\"defer\"')"
                        + " WHERE id = ?")
                .param(run)
                .update();
        assertThat(as(t, () -> runs.get(run)).version()).isEqualTo(version);
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().current()).isFalse();

        // And through the service, which moves the version too.
        as(t, () -> runs.recordPolicy(run, Policy.RECEIVABLE, decision(Policy.OPEN_FROM_LIST)));

        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().current()).isFalse();
        assertCode(() -> as(t, () -> runs.transition(run, "ready")), "dry_run_required");
    }

    @Test
    void aRowHashesAlikeAsTheValidationTypesItAndAsTheCommitReadsItBack() {
        // The values the validation gives a row of every kind (RowValidator: dates and instants as text) and the same
        // values written as JSON and read back as the commit reads them.
        Map<String, Object> typed = new LinkedHashMap<>();
        typed.put("item", "FAB-101");
        typed.put("sold_at", Instant.parse("2026-09-15T07:30:00Z").toString());
        typed.put("joined_on", LocalDate.of(2025, 1, 15).toString());
        typed.put("qty", new BigDecimal("1.500"));
        typed.put("amount", 7000L);
        typed.put("active", Boolean.TRUE);
        typed.put("customer", null);
        JsonMapper json = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
        String stored = TestDatabase.owner()
                .sql("SELECT CAST(? AS jsonb)::text")
                .param(json.writeValueAsString(typed))
                .query(String.class)
                .single();
        Map<String, Object> readBack = json.readValue(stored, new TypeReference<>() {});

        assertThat(ImportRefs.contentHash(readBack)).isEqualTo(ImportRefs.contentHash(typed));

        // A writer that hands the hash a date or an instant as an object, and numbers of another scale or kind, gets
        // the same canonical JSON (Lead ruling 11 on #872); a changed value does not.
        Map<String, Object> objects = new LinkedHashMap<>(typed);
        objects.put("sold_at", Instant.parse("2026-09-15T07:30:00Z"));
        objects.put("joined_on", LocalDate.of(2025, 1, 15));
        objects.put("qty", new BigDecimal("1.5"));
        objects.put("amount", new BigDecimal("7000.00"));
        assertThat(ImportRefs.contentHash(objects)).isEqualTo(ImportRefs.contentHash(typed));
        objects.put("joined_on", LocalDate.of(2025, 1, 16));
        assertThat(ImportRefs.contentHash(objects)).isNotEqualTo(ImportRefs.contentHash(typed));
    }

    @Autowired
    RefService refs;

    // JUSTIFICATION-A3: the review of 12e9d33 on #898 asks registerAll proven against register on each refusal.
    /**
     * A page's registration (issue #880, {@code registerAll}) does what registering each record in turn does: the same
     * rows, and the same refusal for a key held with another reference (before, or earlier in the list), a journal, and a
     * record registered twice. Each case runs both ways in a transaction that is rolled back.
     */
    @Test
    void registeringAPageIsRegisteringEachRecordInTurn() {
        UUID run = itemsRun(t, ITEMS_A);
        UUID batch = as(
                t,
                () -> new TransactionTemplate(transactionManager)
                        .execute(s -> jdbc.sql("SELECT id FROM import_batches WHERE run_id = ? LIMIT 1")
                                .param(run)
                                .query(UUID.class)
                                .single()));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        BatchWriter.Registration first = new BatchWriter.Registration("fab|-|line:2#h1", "retail.product", a);
        BatchWriter.Registration sameRowOtherType = new BatchWriter.Registration("fab|-|line:2#h1", "retail.unit", b);
        BatchWriter.Registration next = new BatchWriter.Registration("fab|-|line:3#h2", "retail.product", b);
        BatchWriter.Registration sameKeyOtherRef = new BatchWriter.Registration("fab|-|line:2#h9", "retail.product", b);
        BatchWriter.Registration journal =
                new BatchWriter.Registration("fab|-|line:4#h3", RefService.JOURNAL, UUID.randomUUID());

        List<List<BatchWriter.Registration>> cases = List.of(
                List.of(first, sameRowOtherType, next),
                List.of(first, sameKeyOtherRef),
                List.of(next, journal),
                List.of(first, first));
        for (List<BatchWriter.Registration> records : cases) {
            String inTurn = registered(run, batch, List.of(), records, false);
            assertThat(registered(run, batch, List.of(), records, true))
                    .as("the page %s", records)
                    .isEqualTo(inTurn);
        }
        // A key held before with another reference is refused both ways, with the operator's message.
        String held = registered(run, batch, List.of(first), List.of(next, sameKeyOtherRef), false);
        assertThat(held).startsWith("CHANGED_SINCE_IMPORT ");
        assertThat(registered(run, batch, List.of(first), List.of(next, sameKeyOtherRef), true))
                .isEqualTo(held);
        assertThat(registered(run, batch, List.of(), List.of(first, sameRowOtherType, next), true))
                .contains("fab|-|line:2#h1 retail.product", "fab|-|line:2#h1 retail.unit", "fab|-|line:3#h2");
        assertThat(registered(run, batch, List.of(), List.of(first, first), true))
                .isEqualTo("refused by the unique key");
    }

    /**
     * Registers {@code before} in turn, then {@code records} one at a time or as a page, in a transaction rolled back:
     * the run's rows as {@code key ref type target}, or the refusal (its code and message, or the unique key's).
     */
    private String registered(
            UUID run,
            UUID batch,
            List<BatchWriter.Registration> before,
            List<BatchWriter.Registration> records,
            boolean page) {
        return as(
                t,
                () -> new TransactionTemplate(transactionManager).execute(s -> {
                    s.setRollbackOnly();
                    try {
                        before.forEach(r -> refs.register(run, batch, r.sourceRef(), r.targetType(), r.targetId()));
                        if (page) {
                            refs.registerAll(run, batch, records);
                        } else {
                            records.forEach(
                                    r -> refs.register(run, batch, r.sourceRef(), r.targetType(), r.targetId()));
                        }
                    } catch (ApiException e) {
                        return e.code() + " " + e.getMessage();
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        return "refused by the unique key";
                    } catch (IllegalArgumentException e) {
                        return "refused: " + e.getMessage();
                    }
                    return jdbc.sql("SELECT string_agg(source_key || ' ' || source_ref || ' ' || target_type || ' '"
                                    + " || target_id, ',' ORDER BY source_ref, target_type, target_id)"
                                    + " FROM import_refs WHERE run_id = ?")
                            .param(run)
                            .query(String.class)
                            .single();
                }));
    }

    // ---------------------------------------------------------------------------------------------
    // No side effects, and isolation.
    // ---------------------------------------------------------------------------------------------

    @Test
    void anImportedRecordSendsNoMessageStartsNoTaskAndRaisesNoApproval() {
        UUID run = itemsRun(t, ITEMS_A);
        ready(t, run, null);
        long outboxBefore = count("SELECT count(*) FROM notification_outbox");
        // A writer that would email someone about each item.
        FixtureShopTemplate.SCRIPT = (runId, batchId, seq, checkpoint) -> {
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    "fabricated.reader@example.invalid",
                    "onboarding.verify_email",
                    Map.of("reference", "AP-1", "link", "https://example.test/x"),
                    "fabricated-import-notice:" + runId));
            return Map.of();
        };
        assertThatThrownBy(() -> as(t, () -> runs.commit(run)))
                .hasStackTraceContaining("imported records send no message");
        assertThat(count("SELECT count(*) FROM notification_outbox")).isEqualTo(outboxBefore);

        // A writer that would raise an approval request for each item.
        FixtureShopTemplate.SCRIPT = (runId, batchId, seq, checkpoint) -> {
            CurrentPrincipal.set(Principal.uniform(operator, "staff", Set.of("core.imports.manage"), true, Set.of()));
            try {
                approvals.request(new Approvals.ActionRequest(
                        ImportCommitAction.TYPE, t.headOffice(), runId, 0, null, null, Map.of()));
            } finally {
                CurrentPrincipal.clear();
            }
            return Map.of();
        };
        assertThatThrownBy(() -> as(t, () -> runs.resume(run))).hasStackTraceContaining("raise no approval");
        assertThat(count(
                        "SELECT count(*) FROM approval_requests WHERE tenant_id = ? AND status = 'pending'",
                        t.tenantId()))
                .isZero();
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");
        assertThat(count("SELECT count(*) FROM import_refs WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    @Test
    void anotherBusinessReadsNoImportRefsAndCannotRequestTheCommit() {
        UUID run = itemsRun(t, ITEMS_A);
        ready(t, run, null);
        assertThat(as(t, () -> runs.commit(run))).isEqualTo(CommitOutcome.COMMITTED);
        TestDatabase.Fixture other = TestDatabase.tenant("commit-other", true, true);

        List<Long> seen = as(
                other,
                () -> new TransactionTemplate(transactionManager)
                        .execute(s -> List.of(
                                jdbc.sql("SELECT count(*) FROM import_refs")
                                        .query(Long.class)
                                        .single(),
                                jdbc.sql("SELECT count(*) FROM import_runs WHERE commit_approved_by IS NOT NULL")
                                        .query(Long.class)
                                        .single())));
        assertThat(seen).containsExactly(0L, 0L);
        assertThat(count("SELECT count(*) FROM import_refs WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(3);
        assertCode(() -> CommitApproval.request(other, runs, run, UUID.randomUUID()), "not_found");
        assertCode(() -> as(other, () -> runs.verifyPreImportDump(run, SHA, 1)), "not_found");
        assertCode(
                () -> as(other, () -> runs.recordPolicy(run, Policy.RECEIVABLE, decision(Policy.TREAT_AS_SETTLED))),
                "not_found");
        // Nor writes a reference into the business: the row-level security's check refuses another tenant's row.
        assertThatThrownBy(() -> as(
                        other,
                        () -> new TransactionTemplate(transactionManager)
                                .execute(s -> jdbc.sql("INSERT INTO import_refs (id, tenant_id, run_id, batch_id,"
                                                + " source_key, source_ref, target_type, target_id)"
                                                + " VALUES (?, ?, ?, ?, 'k', 'k#h', 'test.record', ?)")
                                        .params(
                                                UUID.randomUUID(),
                                                t.tenantId(),
                                                run,
                                                UUID.randomUUID(),
                                                UUID.randomUUID())
                                        .update())))
                .hasStackTraceContaining("row-level security");
        assertThat(TestDatabase.owner()
                        .sql("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class"
                                + " WHERE relname = 'import_refs'")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Autowired
    javax.sql.DataSource dataSource;

    // JUSTIFICATION-A3: issue #141's portal start, end to end; no existing case drives the commit from the portal.
    /**
     * The operator portal's start (issue #141, review of 1c9568b on #895): refused at once while another commit loop of the
     * business is alive; otherwise the run is committing when the 202 answers, its move audited with the operator as
     * actor, a second start meanwhile is 409; a background commit that dies after a batch is resumed from the portal once
     * its loop is gone, the resume audited with the operator, and the run ends committed with every batch once.
     */
    @Test
    void thePortalStartsTheCommitInTheRequestAndItsBatchesGoOnToCommitted() throws Exception {
        UUID run = fullRun(t);
        ready(t, run, Policy.OPEN_FROM_LIST);
        Api platform = Api.platform(http);
        String email = Api.email("portal-start");
        UUID operatorId = Api.platformUser(email);
        String token = platform.signIn(operatorId, email, Api.PASSWORD, null).accessToken();
        String base = "/api/v1/platform/imports/businesses/" + t.slug() + "/runs/" + run;

        // A live loop of the business (here held by the test's own session) refuses both at once, and nothing moves.
        // The lock is a session lock on a pooled connection, so it is let go on that same connection before the
        // connection goes back to the pool (review of e3f95b2 on #895).
        String key = "core.imports.loop:" + t.tenantId();
        try (java.sql.Connection held = dataSource.getConnection()) {
            try (java.sql.PreparedStatement lock =
                    held.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
                lock.setString(1, key);
                lock.execute();
            }
            try {
                for (String action : List.of("/start", "/resume")) {
                    ResponseEntity<JsonNode> refused = platform.post(base + action, null, token);
                    assertThat(refused.getStatusCode()).as(action).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refused.getBody().get("code").asString()).isEqualTo("IMPORT_IN_PROGRESS");
                }
            } finally {
                try (java.sql.PreparedStatement unlock =
                        held.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
                    unlock.setString(1, key);
                    unlock.execute();
                }
            }
        }
        assertThat(status(run)).isEqualTo("ready");

        // The background commit dies right after its second batch (batch 4), as a redeploy would end it.
        seams.killAfter = 4;
        ResponseEntity<JsonNode> started = platform.post(base + "/start", null, token);
        assertThat(started.getStatusCode()).as("%s", started.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(started.getBody().get("run").get("status").asString()).isEqualTo("committing");
        // A second start while it writes, or after it stopped, is refused; never a second writer.
        assertThat(platform.post(base + "/start", null, token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Once the dead loop gave its claim back, the portal's resume goes on from the first batch not committed. While
        // the loop is still alive the resume is 409 IMPORT_IN_PROGRESS, so it is asked until it is taken.
        long until = System.currentTimeMillis() + 120_000;
        ResponseEntity<JsonNode> resumed = platform.post(base + "/resume", null, token);
        while (resumed.getStatusCode() != HttpStatus.ACCEPTED && System.currentTimeMillis() < until) {
            assertThat(resumed.getBody().get("code").asString()).isEqualTo("IMPORT_IN_PROGRESS");
            Thread.sleep(250);
            resumed = platform.post(base + "/resume", null, token);
        }
        assertThat(resumed.getStatusCode()).as("%s", resumed.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        while (!"committed".equals(status(run)) && System.currentTimeMillis() < until) {
            Thread.sleep(250);
        }
        assertThat(status(run)).isEqualTo("committed");
        assertThat(committedOrder(run)).containsExactly(2, 4, 3, 1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT actor_kind || '|' || actor_user_id FROM audit_log WHERE tenant_id = ?"
                                + " AND entity_id = ? AND action = 'core.import_run.resumed'")
                        .params(t.tenantId(), run)
                        .query(String.class)
                        .list())
                .as("the resume names the operator who pressed it")
                .containsExactly("platform|" + operatorId);
        assertThat(TestDatabase.owner()
                        .sql("SELECT actor_kind || '|' || actor_user_id FROM audit_log WHERE tenant_id = ?"
                                + " AND entity_id = ? AND action = 'core.import_run.status_changed'"
                                + " AND data -> 'after' ->> 'status' = 'committing'")
                        .params(t.tenantId(), run)
                        .query(String.class)
                        .list())
                .as("the move to committing names the operator who pressed start")
                .containsExactly("platform|" + operatorId);
    }

    static String status(UUID runId) {
        return TestDatabase.owner()
                .sql("SELECT status FROM import_runs WHERE id = ?")
                .param(runId)
                .query(String.class)
                .single();
    }
}
