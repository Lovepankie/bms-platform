package com.rincoltech.bms.core.imports.internal;

import static com.rincoltech.bms.core.imports.internal.ImportValidationIT.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.internal.StoredObjects;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportPreview.BatchOutcome;
import com.rincoltech.bms.core.imports.ImportPreview.Preview;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportReview.Resolution;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.CommitOutcome;
import com.rincoltech.bms.core.imports.ImportRuns.DryRun;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Money;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.HistoricalMovement;
import com.rincoltech.bms.testsupport.Api;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The preview and the dry run of issue #132 (spec section 3.6, FR-IMP-06, ADR-027 decision 5) on real PostgreSQL as
 * bms_app, with the review of #869: the preview counts every batch's rows by outcome and predicts every kind of balance per
 * branch; the dry run is the commit's code inside one outer transaction that is rolled back, so every table keeps its row
 * count, every sequence its value and storage its files, the audit log included; its report equals the real run's on the
 * same input; the preview's predicted balances (stock quantity and value, receivables, loan principal, arrears buckets and
 * each journal, per branch) equal the books after the commit; no text of the report names a personal value of the run; a
 * stored dry run stops counting once a resolution, a mapping version, a batch or another run's commit changes; a run commits
 * only with the writer its dry run rehearsed; a dry run and a commit of the business never wait on each other; and another
 * business reaches none of it. Fabricated data only: the golden files of {@code fixtures/imports/golden/}, the fixture
 * mapping and the fixture template's made-up rules.
 */
class ImportPreviewIT extends IntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 6, 30);
    static final String SHA = "b".repeat(64);
    static final FixtureShopTemplate TEMPLATE = new FixtureShopTemplate();

    /** A writer with nothing to write: for a run whose batches hold no rows. */
    static final BatchWriter NOTHING = (r, b, s, c) -> Map.of();

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Autowired
    ImportPreview previews;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportRunService service;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportReview review;

    @Autowired
    LedgerPosting ledger;

    @Autowired
    StockLedger stock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ApplicationContext context;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    /** The retail item the fixture's stock takes are put on hand as. */
    UUID item;

    @BeforeEach
    void setUp() {
        // Lending and retail, so the chart has inventory, trade debtors, loans receivable and opening balance equity.
        t = TestDatabase.tenant("preview", true, true);
        other = TestDatabase.tenant("preview-b", true, true);
        item = new RetailTestSupport(http, t).product("FAB-900", 700, 900);
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    record Run(UUID runId, Map<String, Batch> batches) {}

    /** A run of the business with the three golden files uploaded through the quarantine and validated. */
    Run validated(TestDatabase.Fixture f) throws IOException {
        Mapping mapping = as(f, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID runId = as(f, () -> runs.open(operator).id());
        Map<String, Batch> batches = new LinkedHashMap<>();
        for (GoldenRun.GoldenFile g : GoldenRun.FILES) {
            byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve(g.file()));
            Batch batch = as(
                    f,
                    () -> uploads.upload(
                            runId,
                            new Upload(
                                    g.file(),
                                    new ByteArrayInputStream(bytes),
                                    bytes.length,
                                    operator,
                                    mappings.parseOptions(mapping.id(), g.source()))));
            assertThat(batch.status()).as(g.file()).isEqualTo("parsed");
            batches.put(g.file(), batch);
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), runId)
                .update();
        as(f, () -> validation.validate(runId));
        return new Run(runId, batches);
    }

    /** A run of the business in review with one batch that holds no row, bound to the fixture mapping's item list. */
    UUID emptyRun(TestDatabase.Fixture f) {
        UUID mapping = as(
                f,
                () -> mappings.latest("fab-shop")
                        .orElseGet(() -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null))
                        .id());
        UUID run = as(f, () -> {
            UUID id = runs.open(operator).id();
            runs.addBatch(id, 1);
            return id;
        });
        TestDatabase.owner()
                .sql("""
                        UPDATE import_batches SET mapping_id = ?, mapping_source = 'item_list', template_key = ?,
                               template_version = 1
                         WHERE run_id = ?
                        """)
                .params(mapping, FixtureShopTemplate.KEY, run)
                .update();
        as(f, () -> runs.transition(run, "uploaded"));
        as(f, () -> runs.transition(run, "in_review"));
        return run;
    }

    /**
     * The run, after setting the writer the fixture template's loader runs (Lead ruling 4 on #869): the commit and the dry
     * run resolve every batch's writer from its template, so a test's writer reaches them only that way.
     */
    static UUID scripted(UUID runId, BatchWriter writer) {
        FixtureShopTemplate.SCRIPT = writer;
        return runId;
    }

    @AfterEach
    void unscript() {
        FixtureShopTemplate.SCRIPT = null;
    }

    /** The rows of a batch the commit would write, with their lines and typed values. */
    List<Map.Entry<Integer, Map<String, Object>>> rowsToWrite(UUID batchId, EntitySet set) {
        String sql = "SELECT r.source_row_no, r.normalised::text FROM import_rows r WHERE r.batch_id = ? AND "
                + PreviewService.WRITE + " ORDER BY r.source_row_no";
        return jdbc.sql(sql)
                .param(batchId)
                .query((rs, n) ->
                        Map.entry(rs.getInt(1), PreviewService.typed(set, JSON.readValue(rs.getString(2), OBJECT))))
                .list();
    }

    // JUSTIFICATION-A3: the review of #869 asks for every kind of balance written and read back, per branch.
    /**
     * The commit's writer for the fixture's run, by the fixture template's made-up rules (those of its forecast): every
     * row to write is counted; each new buyer is reported twice, by its number and by its name (a personal value the report
     * must hide); a stock take puts its quantity on hand at the head office as a stock movement of the fabricated item; and
     * the sales post three opening journals, each reported with the totals its lines hold: the head office's
     * {@code inventory}, its {@code loans_receivable} dated the loan's day, and the second branch's {@code trade_debtors}.
     * {@code extra} is added to the stock's value, to make the dry run differ from the preview.
     */
    BatchWriter writer(TestDatabase.Fixture f, long extra) {
        UUID inventory = f.account("inventory");
        UUID debtors = f.account("trade_debtors");
        UUID loans = f.account("loans_receivable");
        UUID equity = f.account("opening_balance_equity");
        UUID product = item;
        return (runId, batchId, seq, checkpoint) -> {
            String source = jdbc.sql("SELECT mapping_source FROM import_batches WHERE id = ?")
                    .param(batchId)
                    .query(String.class)
                    .single();
            String set = switch (source) {
                case "item_list" -> "items";
                case "buyer_book" -> "customers";
                default -> "sales";
            };
            EntitySet entities = TEMPLATE.entitySets().stream()
                    .filter(e -> e.key().equals(set))
                    .findFirst()
                    .orElseThrow();
            List<Map.Entry<Integer, Map<String, Object>>> rows = rowsToWrite(batchId, entities);
            BigDecimal qty = BigDecimal.ZERO;
            long value = 0;
            long owed = 0;
            long lent = 0;
            LocalDate lentOn = null;
            Map<String, long[]> buckets = new TreeMap<>();
            for (Map.Entry<Integer, Map<String, Object>> row : rows) {
                Map<String, Object> values = row.getValue();
                if ("customers".equals(set)) {
                    checkpoint.anomaly(row.getKey(), "buyer " + values.get("ref_no") + " is new");
                    checkpoint.anomaly(row.getKey(), "buyer " + values.get("name") + " is new");
                } else if ("sales".equals(set)) {
                    long amount = (Long) values.get("amount");
                    Instant at = (Instant) values.get("sold_at");
                    if ("adjustment".equals(values.get("kind"))) {
                        BigDecimal counted = (BigDecimal) values.get("qty");
                        stock.recordHistorical(
                                DAY,
                                List.of(new HistoricalMovement(
                                        f.headOffice(),
                                        product,
                                        "legacy_balance",
                                        counted,
                                        700,
                                        at,
                                        "test_import",
                                        null,
                                        null,
                                        "Fabricated stock take")));
                        qty = qty.add(counted);
                        value += amount;
                    } else if (values.get("customer") != null) {
                        lent += amount;
                        LocalDate on = at.atZone(FixtureShopTemplate.ZONE).toLocalDate();
                        lentOn = lentOn == null || on.isBefore(lentOn) ? on : lentOn;
                        long[] b = buckets.computeIfAbsent(FixtureShopTemplate.bucket(at), k -> new long[2]);
                        b[0]++;
                        b[1] += amount;
                    } else {
                        owed += amount;
                    }
                }
                checkpoint.rowWritten();
            }
            if ("sales".equals(set)) {
                value += extra;
                String hq = FixtureShopTemplate.HEAD_OFFICE;
                String br2 = FixtureShopTemplate.SECOND_BRANCH;
                if (value != 0) {
                    report(checkpoint, hq, "inventory", post(f.headOffice(), DAY, inventory, equity, value));
                    checkpoint.stock(hq, qty, value);
                }
                if (lent != 0) {
                    report(checkpoint, hq, "loans_receivable", post(f.headOffice(), lentOn, loans, equity, lent));
                    checkpoint.loanPrincipal(hq, lent);
                    buckets.forEach((bucket, b) -> checkpoint.arrears(hq, bucket, Math.toIntExact(b[0]), b[1]));
                }
                if (owed != 0) {
                    report(checkpoint, br2, "trade_debtors", post(f.secondBranch(), DAY, debtors, equity, owed));
                    checkpoint.receivable(br2, owed);
                }
                checkpoint.note(
                        "stock takes on hand: " + qty.stripTrailingZeros().toPlainString());
                checkpoint.checksum("balances checksum: rows=1 total_qty="
                        + qty.stripTrailingZeros().toPlainString() + " match=yes");
            }
            return Map.of("written", rows.size());
        };
    }

    /** Posts one opening journal: debit the account, credit opening balance equity; answers its entry number. */
    String post(UUID branch, LocalDate day, UUID debit, UUID equity, long amount) {
        return ledger.post(new EntryRequest(
                        branch,
                        day,
                        "IMP-OPEN",
                        "Opening balance from the fabricated import",
                        "retail",
                        "test_import",
                        null,
                        null,
                        List.of(
                                Line.debit(debit, Money.of(amount, "UGX")),
                                Line.credit(equity, Money.of(amount, "UGX")))))
                .entryNo();
    }

    /** Reports a posted journal with the debit and credit totals its lines hold, read back from the ledger. */
    void report(BatchWriter.Checkpoint checkpoint, String branch, String kind, String entryNo) {
        long[] totals = jdbc.sql("""
                        SELECT coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0)
                          FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id WHERE e.entry_no = ?
                        """)
                .param(entryNo)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
        checkpoint.journal(branch, kind, totals[0], totals[1], "posted", entryNo);
    }

    /** Every trace a run can leave: every table's rows, every sequence, and the business's files in storage. */
    Map<String, Object> traces(TestDatabase.Fixture f) {
        Map<String, Object> out = new TreeMap<>(TestDatabase.rowCounts(f.tenantId()));
        out.put("storage", StoredObjects.keys(context, "tenants/" + f.tenantId() + "/"));
        return out;
    }

    /** Debits less credits on one account at one branch of the business, from the ledger. */
    long balance(TestDatabase.Fixture f, String systemKey, UUID branch) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0)
                          FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE l.tenant_id = ? AND l.account_id = ? AND e.branch_id = ?
                        """)
                .params(f.tenantId(), f.account(systemKey), branch)
                .query(Long.class)
                .single();
    }

    /** One batch's rows: read, to write, excluded, blank and blocked. */
    static List<Integer> outcome(Preview p, String file) {
        BatchOutcome b = p.batches().stream()
                .filter(o -> file.equals(o.file()))
                .findFirst()
                .orElseThrow();
        return List.of(b.rows(), b.write(), b.excluded(), b.blank(), b.blocked());
    }

    /** Whether the run's stored dry run still counts. */
    boolean current(UUID runId) {
        return as(t, () -> runs.dryRunReport(runId)).orElseThrow().current();
    }

    /** The predicted balances of the golden files by the fixture's rules, worked out by hand. */
    static final ImportForecast.Balances GOLDEN = new ImportForecast.Balances(
            // The stock take of till line 4: 1.5 at a fabricated 1000.
            List.of(new ImportForecast.Stock("HQ", new BigDecimal("1.5"), 1_000)),
            // The sale of till line 3, without a buyer: 45000 owed to the second branch.
            List.of(new ImportForecast.Amount("BR2", 45_000)),
            // The sale of till line 2 to buyer B-001: a loan of 90000 made on 15 September, 16 days before 1 October.
            List.of(new ImportForecast.Amount("HQ", 90_000)),
            List.of(new ImportForecast.ArrearsBucket("HQ", "1-30", 1, 90_000)),
            List.of(
                    new ImportForecast.Journal("HQ", "inventory", 1_000, 1_000),
                    new ImportForecast.Journal("HQ", "loans_receivable", 90_000, 90_000),
                    new ImportForecast.Journal("BR2", "trade_debtors", 45_000, 45_000)));

    // ---------------------------------------------------------------------------------------------
    // The preview.
    // ---------------------------------------------------------------------------------------------

    @Test
    void thePreviewCountsRowsByOutcomeAndRecordsByTypeAndPredictsTheBalances() throws IOException {
        Run run = validated(t);
        Map<String, Object> before = traces(t);

        Preview p = as(t, () -> previews.preview(run.runId()));

        assertThat(traces(t)).as("a preview writes nothing").isEqualTo(before);
        assertThat(p.templateKey()).isEqualTo(FixtureShopTemplate.KEY);
        // Worked out from the golden files and fixtures/imports/golden/expected-issues.txt: a row with an open blocking
        // issue is held back, a warning is not, the blank line is blank.
        assertThat(p.recordsByType()).containsExactly(entry("items", 4), entry("customers", 3), entry("sales", 3));
        assertThat(outcome(p, "fab-item-list.csv")).containsExactly(9, 4, 0, 0, 5);
        assertThat(outcome(p, "fab-buyers.csv")).containsExactly(6, 3, 0, 0, 3);
        assertThat(outcome(p, "fab-till-roll.csv")).containsExactly(10, 3, 0, 1, 6);
        assertThat(p.batches()).extracting(BatchOutcome::entitySet).containsExactly("items", "customers", "sales");
        ImportForecast.Balances b = p.balances();
        assertThat(b.stock()).hasSize(1);
        assertThat(b.stock().getFirst().qty()).isEqualByComparingTo("1.5");
        assertThat(ImportRunService.sameBalances(b, GOLDEN)).as("%s", b).isTrue();
        assertThat(b.receivables()).isEqualTo(GOLDEN.receivables());
        assertThat(b.loanPrincipal()).isEqualTo(GOLDEN.loanPrincipal());
        assertThat(b.arrears()).isEqualTo(GOLDEN.arrears());
        assertThat(b.journals()).isEqualTo(GOLDEN.journals());

        // A row the review leaves out moves from write to excluded, and its records with it.
        Batch items = run.batches().get("fab-item-list.csv");
        Issue twin = as(t, () -> validation.issues(items.id())).stream()
                .filter(i -> i.line() == 4 && "CODE_DUPLICATE".equals(i.code()))
                .findFirst()
                .orElseThrow();
        as(
                t,
                () -> review.resolve(
                        twin.id(),
                        new Resolution("exclude_row", twin.version(), null, null, null, "Fabricated twin left out"),
                        operator));
        Preview after = as(t, () -> previews.preview(run.runId()));
        assertThat(outcome(after, "fab-item-list.csv")).containsExactly(9, 3, 1, 0, 5);
        assertThat(after.recordsByType()).containsEntry("items", 3);
    }

    @Test
    void theBalancesComparePerBranchAndKindEachJournalOnItsOwnAndAnyDifferenceCounts() {
        ImportForecast.Balances shuffled = new ImportForecast.Balances(
                List.of(
                        new ImportForecast.Stock("HQ", new BigDecimal("1.000"), 400),
                        new ImportForecast.Stock("HQ", new BigDecimal("0.5"), 600)),
                GOLDEN.receivables(),
                GOLDEN.loanPrincipal(),
                GOLDEN.arrears(),
                List.of(
                        GOLDEN.journals().get(2),
                        GOLDEN.journals().get(0),
                        GOLDEN.journals().get(1)));
        assertThat(ImportRunService.sameBalances(GOLDEN, shuffled)).isTrue();
        ImportForecast.Balances otherQty = new ImportForecast.Balances(
                List.of(new ImportForecast.Stock("HQ", new BigDecimal("2"), 1_000)),
                GOLDEN.receivables(),
                GOLDEN.loanPrincipal(),
                GOLDEN.arrears(),
                GOLDEN.journals());
        ImportForecast.Balances otherBucket = new ImportForecast.Balances(
                GOLDEN.stock(),
                GOLDEN.receivables(),
                GOLDEN.loanPrincipal(),
                List.of(new ImportForecast.ArrearsBucket("HQ", "31-60", 1, 90_000)),
                GOLDEN.journals());
        ImportForecast.Balances otherBranch = new ImportForecast.Balances(
                GOLDEN.stock(),
                List.of(new ImportForecast.Amount("HQ", 45_000)),
                GOLDEN.loanPrincipal(),
                GOLDEN.arrears(),
                GOLDEN.journals());
        ImportForecast.Balances unbalanced = new ImportForecast.Balances(
                GOLDEN.stock(),
                GOLDEN.receivables(),
                GOLDEN.loanPrincipal(),
                GOLDEN.arrears(),
                List.of(
                        new ImportForecast.Journal("HQ", "inventory", 1_000, 999),
                        GOLDEN.journals().get(1),
                        GOLDEN.journals().get(2)));
        // Each journal on its own (review of d8d1e5b): two of 500 are not the one of 1,000.
        ImportForecast.Balances split = new ImportForecast.Balances(
                GOLDEN.stock(),
                GOLDEN.receivables(),
                GOLDEN.loanPrincipal(),
                GOLDEN.arrears(),
                List.of(
                        new ImportForecast.Journal("HQ", "inventory", 500, 500),
                        new ImportForecast.Journal("HQ", "inventory", 500, 500),
                        GOLDEN.journals().get(1),
                        GOLDEN.journals().get(2)));
        for (ImportForecast.Balances differs : List.of(otherQty, otherBucket, otherBranch, unbalanced, split)) {
            assertThat(ImportRunService.sameBalances(GOLDEN, differs))
                    .as("%s", differs)
                    .isFalse();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The dry run.
    // ---------------------------------------------------------------------------------------------

    // JUSTIFICATION-A3: the review of #869 asks for sequences, files and every balance read back from the books.
    @Test
    void theDryRunLeavesNoTraceItsReportEqualsTheRealRunAndThePreviewEqualsTheBooksAfterTheCommit() throws IOException {
        Run run = validated(t);
        Preview preview = as(t, () -> previews.preview(run.runId()));
        BatchWriter writer = writer(t, 0);
        Map<String, Object> before = traces(t);

        DryRun dry = as(t, () -> runs.dryRun(scripted(run.runId(), writer), operator));

        // Every table's rows, every sequence and the business's files are as they were, the audit log and the ledger
        // included; only the run row's columns change.
        assertThat(traces(t)).isEqualTo(before);
        assertThat(dry.current()).isTrue();
        assertThat(dry.previewMatches()).isTrue();
        assertThat(dry.by()).isEqualTo(operator);
        // The build, then the writers: the template loaders, one line per batch, never the script a test hands them.
        String template = FixtureShopTemplate.class.getName();
        assertThat(dry.writer().lines().findFirst().orElseThrow()).startsWith("build ");
        assertThat(dry.writer().lines().skip(1))
                .containsExactly(
                        "1 fixture_shop_v1 " + template + " items " + template + "$$Lambda",
                        "2 fixture_shop_v1 " + template + " customers " + template + "$$Lambda",
                        "3 fixture_shop_v1 " + template + " sales " + template + "$$Lambda");
        CommitReport report = dry.report();
        assertThat(report.mode()).isEqualTo(CommitReport.DRY_RUN);
        assertThat(report.failure()).isNull();
        assertThat(report.sources())
                .containsExactly(
                        new CommitReport.Source("fab-item-list.csv", 9, 4, 0, 0),
                        new CommitReport.Source("fab-buyers.csv", 6, 3, 0, 0),
                        new CommitReport.Source("fab-till-roll.csv", 10, 3, 0, 0));
        assertThat(ImportRunService.sameBalances(report.balances(), GOLDEN))
                .as("%s", report.balances())
                .isTrue();
        assertThat(report.checksum()).contains("match=yes");
        assertThat(report.render()).contains("dry run, every write rolled back").contains("fab-till-roll.csv");
        assertThat(as(t, () -> runs.dryRunReport(run.runId())).orElseThrow().report())
                .isEqualTo(report);

        // The real run on the same input, with the same writer.
        as(t, () -> runs.transition(run.runId(), "ready"));
        as(t, () -> runs.recordPreImportDump(run.runId(), "pre-import-fabricated.dump", Instant.now(), SHA));
        assertThat(as(t, () -> runs.commit(run.runId()))).isEqualTo(CommitOutcome.COMMITTED);
        CommitReport real = as(t, () -> runs.commitReport(run.runId()));

        assertThat(real.mode()).isEqualTo(CommitReport.COMMIT);
        assertThat(real.sameAs(report)).as(real.render()).isTrue();
        assertThat(real.render().replace("committed, one transaction per batch", "x"))
                .isEqualTo(report.render().replace("dry run, every write rolled back", "x"));

        // FR-IMP-06: the preview's predicted balances are the books after the commit, per branch, read from the ledger
        // and the stock ledger, not from the report.
        ImportForecast.Balances predicted = preview.balances();
        BigDecimal onHand = as(t, () -> new TransactionTemplate(transactionManager).execute(s -> onHand()));
        ImportForecast.Balances books = new ImportForecast.Balances(
                List.of(new ImportForecast.Stock("HQ", onHand, balance(t, "inventory", t.headOffice()))),
                List.of(new ImportForecast.Amount("BR2", balance(t, "trade_debtors", t.secondBranch()))),
                List.of(new ImportForecast.Amount("HQ", balance(t, "loans_receivable", t.headOffice()))),
                arrearsFromTheBooks(),
                journalsFromTheBooks(real));
        assertThat(ImportRunService.sameBalances(predicted, books))
                .as("books %s", books)
                .isTrue();
        assertThat(balance(t, "inventory", t.secondBranch())).isZero();
        assertThat(balance(t, "trade_debtors", t.headOffice())).isZero();
        assertThat(balance(t, "loans_receivable", t.secondBranch())).isZero();
    }

    /** The fabricated item on hand at the head office, from the stock ledger, inside the caller's transaction. */
    BigDecimal onHand() {
        return stock.positions().stream()
                .filter(p ->
                        p.branchId().equals(t.headOffice()) && p.productId().equals(item))
                .map(StockLedger.Position::balance)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /** The loans receivable the head office's journals hold, aged by each entry's day to the fixture's day. */
    List<ImportForecast.ArrearsBucket> arrearsFromTheBooks() {
        Map<String, long[]> buckets = new TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT e.entry_date, sum(l.debit - l.credit) AS principal
                          FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE l.tenant_id = ? AND l.account_id = ? AND e.branch_id = ?
                         GROUP BY e.id, e.entry_date
                        """)
                .params(t.tenantId(), t.account("loans_receivable"), t.headOffice())
                .query((rs, n) -> {
                    LocalDate day = rs.getObject("entry_date", Date.class).toLocalDate();
                    long[] b = buckets.computeIfAbsent(FixtureShopTemplate.bucket(day), k -> new long[2]);
                    b[0]++;
                    b[1] += rs.getLong("principal");
                    return null;
                })
                .list();
        List<ImportForecast.ArrearsBucket> out = new ArrayList<>();
        buckets.forEach((bucket, b) -> out.add(new ImportForecast.ArrearsBucket("HQ", bucket, (int) b[0], b[1])));
        return out;
    }

    /** Each journal the report names, as its lines in the ledger hold it: branch, kind, debit and credit totals. */
    List<ImportForecast.Journal> journalsFromTheBooks(CommitReport report) {
        List<ImportForecast.Journal> out = new ArrayList<>();
        for (CommitReport.Journal j : report.journals()) {
            Map<String, Object> row = TestDatabase.owner()
                    .sql("""
                            SELECT b.code, sum(l.debit) AS debit, sum(l.credit) AS credit
                              FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                              JOIN branches b ON b.id = e.branch_id
                             WHERE e.tenant_id = ? AND e.entry_no = ? GROUP BY b.code
                            """)
                    .params(t.tenantId(), j.entryNo())
                    .query()
                    .singleRow();
            long debit = ((Number) row.get("debit")).longValue();
            long credit = ((Number) row.get("credit")).longValue();
            assertThat(debit).as("%s is balanced in the ledger", j.entryNo()).isEqualTo(credit);
            assertThat(List.of(debit, credit))
                    .as("%s as reported", j.entryNo())
                    .containsExactly(j.debitMinor(), j.creditMinor());
            out.add(new ImportForecast.Journal((String) row.get("code"), j.kind(), debit, credit));
        }
        return out;
    }

    @Test
    void noTextOfTheReportNamesAPersonalValueOfTheRun() throws IOException {
        Run run = validated(t);
        BatchWriter good = writer(t, 0);
        // A careless loader: names in a note, an anomaly on another file and on no line, a buyer of another line and of
        // another batch, a phone in the checksum and a name as a source.
        BatchWriter careless = (runId, batchId, seq, checkpoint) -> {
            Map<String, Object> counts = good.write(runId, batchId, seq, checkpoint);
            if (seq == 2) {
                checkpoint.note("duplicate of Fabricated Buyer Alpha");
                checkpoint.anomaly("fab-other.csv", 0, "buyer Fabricated Buyer Gamma is a twin");
                checkpoint.anomaly(3, "line 3 is like the buyer of line 4, Fabricated Buyer Beta");
                checkpoint.counts("Fabricated Buyer Delta.csv", 1, 1, 0, 0);
                checkpoint.note("buyers checked: 6");
                checkpoint.note("DUPLICATE OF FABRICATED BUYER ZETA");
            }
            if (seq == 3) {
                checkpoint.anomaly(2, "sold to Fabricated Buyer Epsilon");
                checkpoint.checksum("balances checksum for +256700000102 match=yes");
            }
            return counts;
        };

        DryRun dry = as(t, () -> runs.dryRun(scripted(run.runId(), careless), operator));

        String text = dry.report().render();
        String stored = TestDatabase.owner()
                .sql("SELECT dry_run_report::text FROM import_runs WHERE id = ?")
                .param(run.runId())
                .query(String.class)
                .single();
        for (String personal : GoldenRun.PERSONAL) {
            assertThat(text).as("the report names %s", personal).doesNotContain(personal);
            assertThat(stored).as("the stored report names %s", personal).doesNotContain(personal);
        }
        CommitReport report = dry.report();
        // A buyer's number is not personal and is kept; a buyer's name is, and so is every careless text.
        assertThat(report.anomalies())
                .contains(
                        new CommitReport.Anomaly("fab-buyers.csv", 3, "buyer B-001 is new"),
                        new CommitReport.Anomaly("fab-buyers.csv", 3, CommitReport.HIDDEN),
                        new CommitReport.Anomaly("fab-buyers.csv", 4, CommitReport.HIDDEN),
                        new CommitReport.Anomaly("fab-buyers.csv", 7, CommitReport.HIDDEN),
                        new CommitReport.Anomaly("fab-other.csv", 0, CommitReport.HIDDEN),
                        new CommitReport.Anomaly("fab-till-roll.csv", 2, CommitReport.HIDDEN));
        assertThat(report.notes()).contains(CommitReport.HIDDEN, "buyers checked: 6");
        // In any case (review of d8d1e5b): a name in capitals is the name.
        assertThat(text.toUpperCase(Locale.ROOT)).doesNotContain("FABRICATED BUYER ZETA");
        assertThat(report.sources()).extracting(CommitReport.Source::name).contains(CommitReport.HIDDEN);
        assertThat(report.checksum()).isEqualTo(CommitReport.HIDDEN);
        assertThat(text).contains("fab-buyers.csv:3: buyer B-001 is new");
    }

    // JUSTIFICATION-A3: the review of #869 asks for the invalidations a dry run must see, one case each.
    @Test
    void aStoredDryRunStopsCountingOnceAResolutionAMappingABatchOrAnotherRunsCommitChanges() throws IOException {
        Run run = validated(t);
        BatchWriter writer = writer(t, 0);
        as(t, () -> runs.dryRun(scripted(run.runId(), writer), operator));
        assertThat(current(run.runId())).isTrue();

        // A resolution: an override of the warning on line 4 of the item list.
        Batch items = run.batches().get("fab-item-list.csv");
        Issue twin = as(t, () -> validation.issues(items.id())).stream()
                .filter(i -> i.line() == 4 && "CODE_DUPLICATE".equals(i.code()))
                .findFirst()
                .orElseThrow();
        String note = "The twin is the same fabricated item";
        as(
                t,
                () -> review.resolve(
                        twin.id(), new Resolution("override", twin.version(), null, null, null, note), operator));
        assertThat(current(run.runId())).isFalse();
        assertCode(() -> as(t, () -> runs.transition(run.runId(), "ready")), "dry_run_required");
        as(t, () -> runs.dryRun(scripted(run.runId(), writer), operator));
        assertThat(current(run.runId())).isTrue();

        // A mapping version: a value map from the review moves the run's batches to version 2.
        Batch till = run.batches().get("fab-till-roll.csv");
        Issue returned = as(t, () -> validation.issues(till.id())).stream()
                .filter(i -> i.line() == 9 && "TYPE_INVALID".equals(i.code()) && "kind".equals(i.field()))
                .findFirst()
                .orElseThrow();
        Resolution map = new Resolution("map_value", returned.version(), "adjustment", null, null, null);
        as(t, () -> review.resolve(returned.id(), map, operator));
        assertThat(current(run.runId())).isFalse();
        as(t, () -> runs.dryRun(scripted(run.runId(), writer), operator));
        assertThat(current(run.runId())).isTrue();

        // Another run's commit into the business: the business's records are not those the dry run saw.
        UUID second = emptyRun(t);
        as(t, () -> runs.dryRun(scripted(second, NOTHING), operator));
        as(t, () -> runs.transition(second, "ready"));
        as(t, () -> runs.recordPreImportDump(second, "pre-import-fabricated.dump", Instant.now(), SHA));
        assertThat(as(t, () -> runs.commit(second))).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(current(run.runId())).isFalse();
        as(t, () -> runs.dryRun(scripted(run.runId(), writer), operator));
        assertThat(current(run.runId())).isTrue();

        // A batch: the buyer book cancelled.
        as(t, () -> uploads.cancelBatch(run.batches().get("fab-buyers.csv").id()));
        assertThat(current(run.runId())).isFalse();
        assertCode(() -> as(t, () -> runs.transition(run.runId(), "ready")), "dry_run_required");

        // A dry run of the run as it is now counts again, and the run may be made ready.
        as(t, () -> runs.dryRun(scripted(run.runId(), writer), operator));
        assertThat(as(t, () -> runs.transition(run.runId(), "ready")).status()).isEqualTo("ready");
    }

    // JUSTIFICATION-A3: the Lead's ruling 4 on #869: the writers are the templates', and a change of them is refused.
    @Test
    void theWritersAreTheTemplatesAndARunWhoseWritersChangedIsNotCommitted() throws IOException {
        Run run = validated(t);
        DryRun dry = as(t, () -> runs.dryRun(scripted(run.runId(), writer(t, 0)), operator));
        // The build, then a line per batch: its number, the template and version, the template's class, the entity set,
        // the loader.
        String loader = FixtureShopTemplate.class.getName() + "$$Lambda";
        assertThat(dry.writer().split("\n")[0]).startsWith("build ").doesNotContain("build null");
        assertThat(Arrays.copyOfRange(dry.writer().split("\n"), 1, 4))
                .containsExactly(
                        "1 fixture_shop_v1 " + FixtureShopTemplate.class.getName() + " items " + loader,
                        "2 fixture_shop_v1 " + FixtureShopTemplate.class.getName() + " customers " + loader,
                        "3 fixture_shop_v1 " + FixtureShopTemplate.class.getName() + " sales " + loader);
        as(t, () -> runs.transition(run.runId(), "ready"));
        as(t, () -> runs.recordPreImportDump(run.runId(), "pre-import-fabricated.dump", Instant.now(), SHA));
        // As if the dry run had rehearsed another template's loader: the commit refuses before it writes anything.
        TestDatabase.owner()
                .sql("UPDATE import_runs SET dry_run_writer = replace(dry_run_writer, ' sales ', ' fabricated_set ')"
                        + " WHERE id = ?")
                .param(run.runId())
                .update();
        Map<String, Object> before = traces(t);

        assertCode(() -> as(t, () -> runs.commit(run.runId())), "dry_run_writer_differs");

        assertThat(as(t, () -> runs.get(run.runId())).status()).isEqualTo("ready");
        assertThat(traces(t)).isEqualTo(before);
        // A caller cannot pass a writer at all; a test's reaches the commit only through a test-only template.
        assertThat(ImportRuns.class.getMethods())
                .filteredOn(m -> List.of("commit", "resume", "dryRun").contains(m.getName()))
                .allSatisfy(m -> assertThat(m.getParameterTypes()).doesNotContain(BatchWriter.class));
    }

    // JUSTIFICATION-A3: the review of d8d1e5b on #869: the build is what a dry run rehearsed, at a resume too.
    @Test
    void aRunRehearsedOnOneBuildDoesNotResumeOnAnotherAndAnUnknownBuildIsNoBuild() {
        UUID run = emptyRun(t);
        as(t, () -> runs.dryRun(scripted(run, NOTHING), operator));
        as(t, () -> runs.transition(run, "ready"));
        as(t, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", Instant.now(), SHA));
        // The commit stops at its only batch, so the run is committing with that batch left to write.
        BatchWriter failing = (r, b, s, c) -> {
            throw new IllegalStateException("a fabricated failure");
        };
        assertThatThrownBy(() -> as(t, () -> runs.commit(scripted(run, failing))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");
        String rehearsed = TestDatabase.owner()
                .sql("SELECT dry_run_writer FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
        // As if the dry run had run on another build of the same classes and loaders: the resume refuses to go on.
        TestDatabase.owner()
                .sql("UPDATE import_runs SET dry_run_writer = ? WHERE id = ?")
                .params("build another-fabricated-build" + rehearsed.substring(rehearsed.indexOf('\n')), run)
                .update();
        assertCode(() -> as(t, () -> runs.resume(scripted(run, NOTHING))), "dry_run_writer_differs");
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");

        // A resume after a new deploy needs a new dry run (Lead on #869): one of the batches left, on this build. One
        // whose balances the preview did not predict keeps the run from going on, as it keeps a run from ready (review
        // of 1c607ea).
        BatchWriter differs = (r, b, s, c) -> {
            c.stock(FixtureShopTemplate.HEAD_OFFICE, BigDecimal.ONE, 100);
            return Map.of();
        };
        assertThat(as(t, () -> runs.dryRun(scripted(run, differs), operator)).previewMatches())
                .isFalse();
        assertCode(() -> as(t, () -> runs.resume(scripted(run, NOTHING))), "preview_differs");
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");
        DryRun again = as(t, () -> runs.dryRun(scripted(run, NOTHING), operator));
        assertThat(again.writer()).isEqualTo(rehearsed);
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");
        assertThat(as(t, () -> runs.resume(scripted(run, NOTHING)))).isEqualTo(CommitOutcome.COMMITTED);

        // A server that knows neither its build nor its commit has no build to tell apart, so it refuses both.
        assertThat(ImportRunService.codeVersion(null, null, "")).isNull();
        assertThat(ImportRunService.codeVersion(null, null, " ")).isNull();
        Properties build = new Properties();
        build.setProperty("version", "0.0.1-fabricated");
        build.setProperty("time", "2026-10-01T00:00:00Z");
        assertThat(ImportRunService.codeVersion(new BuildProperties(build), null, ""))
                .startsWith("0.0.1-fabricated@")
                .endsWith("|-");
        Properties git = new Properties();
        git.setProperty("commit.id", "f".repeat(40));
        assertThat(ImportRunService.codeVersion(null, new GitProperties(git), ""))
                .isEqualTo("-|" + "f".repeat(40));
        assertThat(ImportRunService.codeVersion(null, new GitProperties(git), "fab1234"))
                .isEqualTo("-|fab1234");
    }

    // JUSTIFICATION-A3: the Lead's ruling 2 on the FAIL of d8d1e5b asks for a test with both identities missing.
    @Test
    void aServerWithNeitherBuildInfoNorACommitShaRefusesTheDryRunTheCommitAndAResume() {
        UUID review = emptyRun(t);
        UUID ready = emptyRun(t);
        as(t, () -> runs.dryRun(scripted(ready, NOTHING), operator));
        as(t, () -> runs.transition(ready, "ready"));
        as(t, () -> runs.recordPreImportDump(ready, "pre-import-fabricated.dump", Instant.now(), SHA));
        // As a server built without build-info and started without BMS_GIT_SHA: no identity at all.
        String had = service.buildIdentity(ImportRunService.codeVersion(null, null, ""));
        try {
            Map<String, Object> before = traces(t);
            assertCode(() -> as(t, () -> runs.dryRun(scripted(review, NOTHING), operator)), "build_unknown");
            assertCode(() -> as(t, () -> runs.commit(ready)), "build_unknown");
            assertCode(() -> as(t, () -> runs.resume(ready)), "build_unknown");
            assertThat(traces(t)).isEqualTo(before);
        } finally {
            service.buildIdentity(had);
        }
        assertThat(as(t, () -> runs.get(ready)).status()).isEqualTo("ready");
        assertThat(as(t, () -> runs.commit(ready))).isEqualTo(CommitOutcome.COMMITTED);
    }

    // JUSTIFICATION-A3: the review of d8d1e5b on #869: a dry run holds the business's rows, so the guard and a limit.
    @Test
    void aDryRunIntoATradingBusinessNeedsTheApprovedOverrideAndStopsAtItsTimeLimit() {
        UUID run = emptyRun(t);
        as(
                t,
                () -> new TransactionTemplate(transactionManager)
                        .execute(s -> ledger.post(new EntryRequest(
                                t.headOffice(),
                                DAY,
                                "IMP-TEST",
                                null,
                                "lending",
                                "test_import",
                                UUID.randomUUID(),
                                null,
                                List.of(
                                        Line.debit(t.account("loans_receivable"), Money.of(100_000, "UGX")),
                                        Line.credit(t.account("opening_balance_equity"), Money.of(100_000, "UGX")))))));
        Map<String, Object> before = traces(t);

        assertCode(() -> as(t, () -> runs.dryRun(scripted(run, NOTHING), operator)), "TENANT_TRADING");

        assertThat(traces(t))
                .as("refused before any row is held, with nothing audited")
                .isEqualTo(before);
        assertThat(as(t, () -> runs.dryRunReport(run))).isEmpty();

        UUID maker = Api.platformUser(Api.email("dry-run-maker"));
        UUID checker = Api.platformUser(Api.email("dry-run-checker"));
        as(t, () -> {
            runs.allowTradingTenant(run, maker, "Top-up import of a fabricated tab");
            return runs.approveTradingTenant(run, checker);
        });
        // Under the override, a dry run past its limit is stopped: every statement after it is refused.
        BatchWriter slow = (r, b, s, c) -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return Map.of();
        };
        service.tradingDryRunLimit(Duration.ofSeconds(1));
        try {
            DryRun stopped = as(t, () -> runs.dryRun(scripted(run, slow), operator));
            assertThat(stopped.report().failure())
                    .isEqualTo("batch 1 stopped at the time limit of a dry run into a trading business (1s)");
        } finally {
            service.tradingDryRunLimit(Duration.ofSeconds(60));
        }
        DryRun dry = as(t, () -> runs.dryRun(scripted(run, NOTHING), operator));
        assertThat(dry.report().failure()).isNull();
        assertThat(dry.current()).isTrue();
    }

    // JUSTIFICATION-A3: the Lead's ruling 5 on #869: a dry run and a commit are answered at once, never kept waiting.
    @Test
    void aDryRunAndACommitOfTheBusinessNeverWaitOnEachOther() throws Exception {
        UUID ready = emptyRun(t);
        as(t, () -> runs.dryRun(scripted(ready, NOTHING), operator));
        as(t, () -> runs.transition(ready, "ready"));
        as(t, () -> runs.recordPreImportDump(ready, "pre-import-fabricated.dump", Instant.now(), SHA));
        UUID reviewed = emptyRun(t);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // A batch of the business is being written: the dry run finds the import key taken and is answered at once.
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> as(
                    t,
                    () -> new TransactionTemplate(transactionManager).execute(s -> {
                        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('core.imports:'"
                                        + " || current_setting('app.tenant_id'), 0))")
                                .query()
                                .singleRow();
                        holding.countDown();
                        try {
                            release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    })));
            assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            try {
                assertCode(() -> as(t, () -> runs.dryRun(reviewed, operator)), "IMPORT_IN_PROGRESS");
            } finally {
                release.countDown();
            }
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                    .isLessThan(10);
            holder.get(60, TimeUnit.SECONDS);
            assertThat(as(t, () -> runs.dryRunReport(reviewed)))
                    .as("nothing stored")
                    .isEmpty();

            // A dry run is running: a commit and another dry run of the business are answered at once.
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            FixtureShopTemplate.SCRIPT = (runId, batchId, seq, checkpoint) -> {
                inside.countDown();
                try {
                    done.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Map.of();
            };
            Future<DryRun> dry = pool.submit(() -> as(t, () -> runs.dryRun(reviewed, operator)));
            assertThat(inside.await(20, TimeUnit.SECONDS)).isTrue();
            long again = System.nanoTime();
            try {
                // Said plainly (Lead on #869): the business's loop is held, by a commit or, here, a dry run.
                for (Runnable call : List.<Runnable>of(
                        () -> as(t, () -> runs.commit(ready)), () -> as(t, () -> runs.dryRun(ready, operator)))) {
                    assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo("IMPORT_IN_PROGRESS");
                        assertThat(e.getMessage())
                                .isEqualTo("Another import of this business is being committed or dry run; try again"
                                        + " when it ends.");
                    });
                }
            } finally {
                done.countDown();
            }
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - again))
                    .isLessThan(10);
            assertThat(dry.get(60, TimeUnit.SECONDS).current()).isTrue();
        } finally {
            pool.shutdownNow();
        }
        FixtureShopTemplate.SCRIPT = NOTHING;
        assertThat(as(t, () -> runs.get(ready)).status()).isEqualTo("ready");
        assertThat(as(t, () -> runs.commit(ready))).isEqualTo(CommitOutcome.COMMITTED);
    }

    @Test
    void aFailedDryRunIsStoredLeavesNoTraceAndTheRunIsNotReady() throws IOException {
        Run run = validated(t);
        BatchWriter good = writer(t, 0);
        BatchWriter failing = (runId, batchId, seq, checkpoint) -> {
            if (seq == 3) {
                throw new IllegalStateException("a fabricated failure quoting Fabricated Buyer Alpha");
            }
            return good.write(runId, batchId, seq, checkpoint);
        };
        Map<String, Object> before = traces(t);

        DryRun dry = as(t, () -> runs.dryRun(scripted(run.runId(), failing), operator));

        assertThat(traces(t)).isEqualTo(before);
        assertThat(dry.report().failure()).isEqualTo("batch 3 failed (IllegalStateException)");
        assertThat(dry.report().render()).contains("FAILED: batch 3").doesNotContain("Fabricated Buyer Alpha");
        assertThat(dry.report().sources()).hasSize(2);
        assertCode(() -> as(t, () -> runs.transition(run.runId(), "ready")), "dry_run_failed");
    }

    @Test
    void aDryRunThatDiffersFromThePreviewKeepsTheRunFromBeingReady() throws IOException {
        Run run = validated(t);

        DryRun dry = as(t, () -> runs.dryRun(scripted(run.runId(), writer(t, 1)), operator));

        assertThat(dry.previewMatches()).isFalse();
        assertCode(() -> as(t, () -> runs.transition(run.runId(), "ready")), "preview_differs");
    }

    @Test
    void aDryRunIsRefusedOutsideReviewAndWhileAFileIsNotRead() {
        UUID draft = as(t, () -> runs.open(operator).id());
        assertCode(() -> as(t, () -> runs.dryRun(draft, operator)), "run_not_open");
        UUID failed = as(t, () -> runs.open(operator).id());
        byte[] unread = TestFiles.csv("Name\nFAB\n");
        as(
                t,
                () -> uploads.upload(
                        failed,
                        new Upload(
                                "fab-unread.csv",
                                new ByteArrayInputStream(unread),
                                unread.length,
                                operator,
                                new ImportUploads.ParseOptions(null, Set.of("code"), Set.of()))));
        as(t, () -> runs.transition(failed, "in_review"));
        assertCode(() -> as(t, () -> runs.dryRun(failed, operator)), "batch_not_parsed");
    }

    // ---------------------------------------------------------------------------------------------
    // Isolation.
    // ---------------------------------------------------------------------------------------------

    @Test
    void anotherBusinessCanNeitherPreviewNorDryRunNorReadTheStoredReports() throws IOException {
        Run run = validated(t);
        BatchWriter writer = writer(t, 0);
        as(t, () -> runs.dryRun(scripted(run.runId(), writer), operator));
        as(t, () -> runs.transition(run.runId(), "ready"));
        as(t, () -> runs.recordPreImportDump(run.runId(), "pre-import-fabricated.dump", Instant.now(), SHA));
        as(t, () -> runs.commit(run.runId()));

        as(other, () -> {
            assertCode(() -> previews.preview(run.runId()), "not_found");
            assertCode(() -> runs.dryRun(run.runId(), operator), "not_found");
            assertCode(() -> runs.dryRunReport(run.runId()), "not_found");
            assertCode(() -> runs.commitReport(run.runId()), "not_found");
            return null;
        });
        // bms_app bound to the other business reads neither stored report: import_runs and import_batches are under
        // forced RLS.
        List<Long> seen = as(
                other,
                () -> new TransactionTemplate(transactionManager)
                        .execute(s -> List.of(
                                jdbc.sql("SELECT count(*) FROM import_runs WHERE dry_run_report IS NOT NULL")
                                        .query(Long.class)
                                        .single(),
                                jdbc.sql("SELECT count(*) FROM import_batches WHERE report IS NOT NULL")
                                        .query(Long.class)
                                        .single())));
        assertThat(seen).containsExactly(0L, 0L);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM import_batches WHERE run_id = ? AND report IS NOT NULL")
                        .param(run.runId())
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
        assertThat(TestDatabase.owner()
                        .sql("SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class"
                                + " WHERE relname IN ('import_runs', 'import_batches')")
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(as(t, () -> runs.commitReport(run.runId())).journals()).hasSize(3);
    }
}
