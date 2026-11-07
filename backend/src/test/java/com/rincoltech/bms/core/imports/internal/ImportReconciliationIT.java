package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.CommitApproval;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportReconciliation;
import com.rincoltech.bms.core.imports.ImportReconciliation.Check;
import com.rincoltech.bms.core.imports.ImportReconciliation.Item;
import com.rincoltech.bms.core.imports.ImportReconciliation.Report;
import com.rincoltech.bms.core.imports.ImportReconciliation.Section;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportReview.Resolution;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.PolicyDecision;
import com.rincoltech.bms.core.imports.ImportTemplate.Policy;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.HistoricalMovement;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The reconciliation and the customer's sign-off of issue #134 (spec sections 3.9 and 3.10, FR-IMP-08, ADR-027 decision 8)
 * on real PostgreSQL as bms_app, through the fixture template's golden files ({@code fixtures/imports/golden/}) committed by
 * a writer that follows the fixture's made-up rules: an item, a buyer and a sale are registered records, a stock take a
 * stock movement of a retail item, a sale to a named buyer a loan of the head office, a sale without one a receivable of
 * the second branch, each through one opening journal. Lending checks use this test-only template until the lending
 * template of #136, and the retail checks the retail export's until #135; the fixture's loan book is its opening journal's
 * lines.
 *
 * <p>Proven here: on the golden files every check ties and every difference is exactly the rows left out, listed by file
 * and line; the system side is read from the books and not from the commit's report; a receivable decision's effect is
 * listed as a policy item; a broken balance shows a difference that is not itemised and the sign-off is refused, by the
 * service and by the database; the sign-off needs {@code core.imports.sign_off}, every section ticked, a typed name and
 * today's date, and is audited without the name; the signed report never changes (append-only, no update path, no new
 * reconciliation after it); the report holds no personal value; another business reaches none of it. Fabricated data only.
 */
class ImportReconciliationIT extends IntegrationTest {

    static final String SHA = "d".repeat(64);
    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static final String HQ = FixtureShopTemplate.HEAD_OFFICE;
    static final String BR2 = FixtureShopTemplate.SECOND_BRANCH;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportReview review;

    @Autowired
    ImportReconciliation reconciliation;

    @Autowired
    CatalogueHistory catalogue;

    @Autowired
    StockLedger stock;

    @Autowired
    BusinessClock clock;

    @Autowired
    CurrentTenant currentTenant;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();

    /** Added to the inventory journal by the real commit only, after the dry run: a deliberately broken balance. */
    volatile long extra;

    @BeforeEach
    void setUp() {
        // Lending and retail, so the chart has inventory, trade debtors, loans receivable and opening balance equity.
        t = TestDatabase.tenant("reconcile", true, true);
        other = TestDatabase.tenant("reconcile-b", true, true);
        // The item the golden stock take counts, which the business already holds (FixtureShopTemplate.EXISTING_ITEM).
        new RetailTestSupport(http, t).product(FixtureShopTemplate.EXISTING_ITEM, 700, 900);
    }

    @AfterEach
    void reset() {
        FixtureShopTemplate.SCRIPT = null;
        FixtureShopTemplate.POLICIES = List.of();
        extra = 0;
        extraTillRows = "";
        secondTillRoll = "";
        killAfterBatch = 0;
        seams.killAfter = 0;
        FixtureShopTemplate.SLOW_BOOKS = false;
        ReconciliationService.signOffTimeout = "60s";
    }

    /** Rows a test adds to the end of the golden till roll, in its columns; none by default. */
    volatile String extraTillRows = "";

    /** The rows of a second till roll, uploaded as a batch of its own after the golden files; none by default. */
    volatile String secondTillRoll = "";

    static final String TILL_HEADER =
            "Fab Item Code,Fab Buyer No,Fab When,Fab Count,Fab Paid,Fab Entry Type,Fab Cashier\n";

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---------------------------------------------------------------------------------------------
    // The golden run, committed.
    // ---------------------------------------------------------------------------------------------

    /**
     * The golden files uploaded, validated, every blocking row left out and every warning overridden in the review (the
     * notes fabricated), dry run with the writer, ready, approved and committed. With {@code option}, the receivable
     * decision is declared and recorded first.
     */
    UUID committedGoldenRun(String option) throws IOException {
        if (option != null) {
            FixtureShopTemplate.POLICIES = List.of(FixtureShopTemplate.RECEIVABLE);
        }
        Mapping mapping = as(t, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID runId = as(t, () -> runs.open(operator).id());
        List<UUID> batches = new ArrayList<>();
        for (GoldenRun.GoldenFile g : GoldenRun.FILES) {
            byte[] read = Files.readAllBytes(GoldenRun.DIR.resolve(g.file()));
            byte[] bytes = "till_roll".equals(g.source()) && !extraTillRows.isEmpty()
                    ? (new String(read, StandardCharsets.UTF_8) + extraTillRows).getBytes(StandardCharsets.UTF_8)
                    : read;
            Batch batch = as(
                    t,
                    () -> uploads.upload(
                            runId,
                            new Upload(
                                    g.file(),
                                    new ByteArrayInputStream(bytes),
                                    bytes.length,
                                    operator,
                                    mappings.parseOptions(mapping.id(), g.source()))));
            assertThat(batch.status()).as(g.file()).isEqualTo("parsed");
            batches.add(batch.id());
        }
        if (!secondTillRoll.isEmpty()) {
            // A second till roll after the golden files: a batch of its own (the cross-batch cases).
            byte[] bytes = (TILL_HEADER + secondTillRoll).getBytes(StandardCharsets.UTF_8);
            Batch batch = as(
                    t,
                    () -> uploads.upload(
                            runId,
                            new Upload(
                                    "fab-till-roll-2.csv",
                                    new ByteArrayInputStream(bytes),
                                    bytes.length,
                                    operator,
                                    mappings.parseOptions(mapping.id(), "till_roll"))));
            assertThat(batch.status()).isEqualTo("parsed");
            batches.add(batch.id());
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), runId)
                .update();
        as(t, () -> validation.validate(runId));
        reviewEverything(batches);
        FixtureShopTemplate.SCRIPT = writer(t);
        if (option != null) {
            as(
                    t,
                    () -> runs.recordPolicy(
                            runId,
                            Policy.RECEIVABLE,
                            new PolicyDecision(option, DAY, "Question list item 3, signed", operator, null, null)));
        }
        ImportRuns.DryRun dry = as(t, () -> runs.dryRun(runId, operator));
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        as(t, () -> runs.transition(runId, "ready"));
        as(t, () -> runs.recordPreImportDump(runId, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, t, runs, runId, SHA);
        if (killAfterBatch > 0) {
            // The process killed right after that batch committed, and the run resumed by a writer made anew, which
            // holds nothing from before (Lead on the review of 5b32682).
            seams.killAfter = killAfterBatch;
            assertThatThrownBy(() -> as(t, () -> runs.commit(runId))).isInstanceOf(CommitTestSeams.Killed.class);
            FixtureShopTemplate.SCRIPT = writer(t);
            assertThat(as(t, () -> runs.resume(runId))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);
            return runId;
        }
        assertThat(as(t, () -> runs.commit(runId))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);
        return runId;
    }

    /** The batch after which the commit is killed and then resumed; 0 for an unbroken commit. */
    volatile int killAfterBatch;

    @Autowired
    CommitTestSeams seams;

    /** Leaves out every row with an open blocking issue, then overrides every open warning, until none is open. */
    void reviewEverything(List<UUID> batches) {
        for (int pass = 0; pass < 200; pass++) {
            Issue next = null;
            for (UUID batch : batches) {
                for (Issue i : as(t, () -> validation.issues(batch))) {
                    if (!"open".equals(i.status())) {
                        continue;
                    }
                    if ("blocking".equals(i.severity()) && (next == null || !"blocking".equals(next.severity()))) {
                        next = i;
                    } else if ("warning".equals(i.severity()) && next == null) {
                        next = i;
                    }
                }
            }
            if (next == null) {
                return;
            }
            Issue issue = next;
            String kind = "blocking".equals(issue.severity()) ? "exclude_row" : "override";
            as(
                    t,
                    () -> review.resolve(
                            issue.id(),
                            new Resolution(kind, issue.version(), null, null, null, "Fabricated review note"),
                            operator));
        }
        throw new AssertionError("the review did not settle");
    }

    // JUSTIFICATION-A3: the writer a real template's loader would be, by the fixture's made-up rules (its forecasts).
    /**
     * The commit's writer for the golden files by the fixture's rules: every new row is registered in {@code import_refs};
     * a stock take is a stock movement of the retail item at the head office; without a receivable decision a sale to a
     * named buyer is a loan of the head office (one opening journal dated the loan's day) and a sale without one is owed to
     * the second branch; under the decision a credit sale is opened at the head office only under {@code open_from_list}.
     * Each opening journal posts through the framework and its balance is told to the report, as the forecast predicts.
     */
    // JUSTIFICATION-A3: ruling 4 of the Lead on the review of 5b32682, the writer's state read from the database.
    /**
     * The stock takes of the run's earlier batches that it wrote (a batch with a stock movement registered by the run,
     * other than this one): each row's item, quantity and amount, as staged, added to the maps.
     */
    void earlierStockTakes(UUID runId, UUID batchId, Map<String, BigDecimal> qty, Map<String, Long> value) {
        String sql = "SELECT r.normalised::text FROM import_rows r JOIN import_batches b ON b.id = r.batch_id"
                + " WHERE b.run_id = ? AND b.id <> ? AND r.excluded_by IS NULL AND r.normalised IS NOT NULL"
                + " AND EXISTS (SELECT 1 FROM import_refs f WHERE f.batch_id = b.id AND f.run_id = ?"
                + " AND f.target_type = 'retail.stock_movement')";
        List<String> rows =
                jdbc.sql(sql).params(runId, batchId, runId).query(String.class).list();
        tools.jackson.databind.json.JsonMapper reader =
                tools.jackson.databind.json.JsonMapper.builder().build();
        for (String json : rows) {
            JsonNode v = reader.readTree(json);
            if (v.get("kind") == null || !"adjustment".equals(v.get("kind").asString())) {
                continue;
            }
            String item = v.get("item").asString();
            JsonNode q = v.get("qty");
            JsonNode a = v.get("amount");
            qty.merge(item, q.isNumber() ? q.decimalValue() : new BigDecimal(q.asString()), BigDecimal::add);
            value.merge(item, a.isNumber() ? a.longValue() : Long.parseLong(a.asString()), Long::sum);
        }
    }

    /** What the run's registered journals hold on inventory: debits less credits. */
    long journalledOnInventory(UUID runId) {
        String sql = "SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l"
                + " JOIN gl_accounts a ON a.id = l.account_id JOIN import_refs f ON f.target_id = l.entry_id"
                + " WHERE f.run_id = ? AND f.target_type = 'ledger.journal_entry' AND a.system_key = 'inventory'";
        return jdbc.sql(sql).param(runId).query(Long.class).single();
    }

    BatchWriter writer(TestDatabase.Fixture f) {
        return (runId, batchId, seq, checkpoint) -> {
            // The run's items, over every batch: the earlier batches' from the database and this batch's as it is
            // written, never kept in memory, so a resume after a killed process journals what an unbroken run would
            // (Lead on the review of 5b32682).
            Map<String, BigDecimal> itemQty = new TreeMap<>();
            Map<String, Long> itemValue = new TreeMap<>();
            earlierStockTakes(runId, batchId, itemQty, itemValue);
            String source = jdbc.sql("SELECT mapping_source FROM import_batches WHERE id = ?")
                    .param(batchId)
                    .query(String.class)
                    .single();
            boolean decided = !FixtureShopTemplate.POLICIES.isEmpty();
            Map<String, CatalogueHistory.Product> products =
                    "till_roll".equals(source) ? catalogue.productsByCode() : Map.of();
            int read = 0;
            int written = 0;
            int existing = 0;
            BigDecimal qty = BigDecimal.ZERO;
            long value = 0;
            long owed = 0;
            long lent = 0;
            LocalDate lentOn = null;
            Map<String, long[]> buckets = new TreeMap<>();
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
                    UUID record = UUID.nameUUIDFromBytes(row.sourceRef().getBytes(StandardCharsets.UTF_8));
                    switch (source) {
                        case "item_list" -> checkpoint.register(row.sourceRef(), "test.item", record);
                        case "buyer_book" -> checkpoint.register(row.sourceRef(), "test.customer", record);
                        default -> {
                            long amount = (Long) v.get("amount");
                            Instant at = (Instant) v.get("sold_at");
                            if ("adjustment".equals(v.get("kind"))) {
                                BigDecimal counted = (BigDecimal) v.get("qty");
                                UUID product = products.get(((String) v.get("item")).toLowerCase(Locale.ROOT))
                                        .id();
                                UUID movement = stock.recordHistorical(
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
                                                        "Fabricated stock take")))
                                        .getFirst();
                                checkpoint.register(row.sourceRef(), "retail.stock_movement", movement);
                                qty = qty.add(counted);
                                value += amount;
                                itemQty.merge((String) v.get("item"), counted, BigDecimal::add);
                                itemValue.merge((String) v.get("item"), amount, Long::sum);
                            } else if (decided) {
                                boolean credit = FixtureShopTemplate.RECEIVABLE
                                        .neededBy()
                                        .test(v);
                                checkpoint.register(
                                        row.sourceRef(), credit ? "test.credit_sale" : "test.cash_sale", record);
                                if (credit
                                        && Policy.OPEN_FROM_LIST.equals(checkpoint
                                                .policy(Policy.RECEIVABLE)
                                                .orElse(null))) {
                                    owed += amount;
                                }
                            } else if (v.get("customer") != null) {
                                checkpoint.register(row.sourceRef(), "test.loan", record);
                                lent += amount;
                                LocalDate on =
                                        at.atZone(FixtureShopTemplate.ZONE).toLocalDate();
                                lentOn = lentOn == null || on.isBefore(lentOn) ? on : lentOn;
                                long[] b = buckets.computeIfAbsent(FixtureShopTemplate.bucket(at), k -> new long[2]);
                                b[0]++;
                                b[1] += amount;
                            } else {
                                checkpoint.register(row.sourceRef(), "test.cash_sale", record);
                                owed += amount;
                            }
                        }
                    }
                    written++;
                    checkpoint.rowWritten();
                }
                if (rows.size() < 1_000) {
                    break;
                }
            }
            // The opening inventory journals hold the items counted above zero, netted over the run; a negative balance
            // is listed apart. A fall since the earlier batches is a journal the other way.
            long journalled = itemQty.entrySet().stream()
                    .filter(e -> e.getValue().signum() > 0)
                    .mapToLong(e -> itemValue.get(e.getKey()))
                    .sum();
            // What the run's journals already hold on inventory, read from its own registered journals.
            long change = journalled - journalledOnInventory(runId);
            if (change != 0) {
                checkpoint.openingJournal(new ImportRefs.Opening(
                        f.headOffice(), HQ, "inventory", DAY, "inventory", change > 0, Math.abs(change) + extra));
            }
            if (value != 0 || qty.signum() != 0) {
                checkpoint.stock(HQ, qty, value);
            }
            if (lent != 0) {
                checkpoint.openingJournal(new ImportRefs.Opening(
                        f.headOffice(), HQ, "loans_receivable", lentOn, "loans_receivable", true, lent));
                checkpoint.loanPrincipal(HQ, lent);
                buckets.forEach((bucket, b) -> checkpoint.arrears(HQ, bucket, Math.toIntExact(b[0]), b[1]));
            }
            if (owed != 0) {
                UUID branch = decided ? f.headOffice() : f.secondBranch();
                String code = decided ? HQ : BR2;
                checkpoint.openingJournal(
                        new ImportRefs.Opening(branch, code, "trade_debtors", DAY, "trade_debtors", true, owed));
                checkpoint.receivable(code, owed);
            }
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("read", read);
            counts.put("written", written);
            counts.put("existing", existing);
            return counts;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the reconciliation.
    // ---------------------------------------------------------------------------------------------

    static Section section(Report r, String key) {
        return r.sections().stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no section " + key));
    }

    static Check check(Report r, String section, String key) {
        return section(r, section).checks().stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no check " + section + " " + key));
    }

    /** The rows the commit did not write because the review left them out: "file:line". */
    Set<String> excludedRows(UUID runId) {
        return new TreeSet<>(
                TestDatabase.owner().sql("""
                        SELECT b.source_filename || ':' || r.source_row_no
                          FROM import_rows r JOIN import_batches b ON b.id = r.batch_id
                         WHERE b.run_id = ? AND r.excluded_by IS NOT NULL
                        """).param(runId).query(String.class).list());
    }

    // ---------------------------------------------------------------------------------------------
    // The golden files: every check ties, every difference is a row left out.
    // ---------------------------------------------------------------------------------------------

    @Test
    void onTheGoldenFilesEveryCheckTiesAndTheDifferencesAreExactlyTheRowsLeftOut() throws IOException {
        UUID run = committedGoldenRun(null);

        // Made by the commit as it finished, from the source and the books.
        Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();

        assertThat(r.version()).isEqualTo(1);
        assertThat(r.asAt()).isEqualTo(FixtureShopTemplate.AS_AT);
        assertThat(r.sections())
                .extracting(Section::key)
                .containsExactly(
                        "counts",
                        "stock_quantity",
                        "stock_value",
                        "receivables",
                        "loan_principal",
                        "arrears",
                        "ledger",
                        "review");
        // FR-IMP-08 (Lead on the review of 4105c2a): each opening journal by its entry number, and every resolution of
        // the review with who, when, before, after and note. JUSTIFICATION-A3: the assertions the ruling asks for.
        String numbers = "SELECT e.entry_no FROM journal_entries e JOIN import_refs r ON r.target_id = e.id"
                + " WHERE r.run_id = ? AND r.target_type = 'ledger.journal_entry' ORDER BY e.entry_no";
        List<String> entryNos =
                TestDatabase.owner().sql(numbers).param(run).query(String.class).list();
        assertThat(entryNos).isNotEmpty();
        assertThat(section(r, "ledger").facts())
                .extracting(ImportReconciliation.Fact::label)
                .containsAll(
                        entryNos.stream().map(no -> "Opening journal " + no).toList());
        long resolutions = TestDatabase.owner()
                .sql("SELECT count(*) FROM import_issue_resolutions WHERE run_id = ?")
                .param(run)
                .query(Long.class)
                .single();
        assertThat(resolutions).isPositive();
        assertThat(section(r, "review").facts()).hasSize((int) resolutions).allSatisfy(fact -> {
            assertThat(fact.label()).matches("fab-[a-z-]+\\.csv line \\d+, [A-Z0-9_]+.*");
            assertThat(fact.value()).contains(" by " + operator, " at ", "; before ", "; after ");
        });
        List<Check> notTied = r.sections().stream()
                .flatMap(s -> s.checks().stream())
                .filter(c -> !c.ties())
                .toList();
        assertThat(notTied).as(r.render()).isEmpty();
        assertThat(r.signable()).isTrue();
        assertThat(r.sha256()).isEqualTo(ReconciliationService.sha256(r.render()));

        // The system side is the books, worked out by hand from the golden files: the stock take of till line 4 (1.5 of
        // FAB-900 worth a fabricated 1000), the sale of till line 2 to buyer B-001 (a loan of 90000 made on 15
        // September, 16 days before 1 October) and the sale of till line 3 without a buyer (45000 owed to the second
        // branch).
        assertThat(check(r, "stock_quantity", "HQ FAB-900").system()).isEqualTo("1.5");
        assertThat(check(r, "stock_value", "HQ").system()).isEqualTo("1000");
        assertThat(check(r, "receivables", "BR2").system()).isEqualTo("45000");
        assertThat(check(r, "loan_principal", "HQ").system()).isEqualTo("90000");
        assertThat(check(r, "arrears", "HQ 1-30 loans").system()).isEqualTo("1");
        assertThat(check(r, "arrears", "HQ 1-30 principal").system()).isEqualTo("90000");
        assertThat(check(r, "ledger", "journal HQ inventory").system()).isEqualTo("1000");
        assertThat(check(r, "ledger", "trial balance").ties()).isTrue();
        assertThat(check(r, "ledger", "journals not opening").system()).isEqualTo("0");

        // Rows: per file, the source is every row that is not blank and the system every row with a record; the
        // difference is exactly the rows the review left out, each listed by file and line.
        Set<String> left = excludedRows(run);
        assertThat(left).hasSize(14);
        Section counts = section(r, "counts");
        Set<String> listed = counts.items().stream()
                .filter(i -> i.reason().equals(ImportReconciliation.EXCLUDED)
                        && i.check().startsWith("batch "))
                .map(i -> i.file() + ":" + i.line())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(listed).isEqualTo(left);
        assertThat(check(r, "counts", "set items").source()).isEqualTo("9");
        assertThat(check(r, "counts", "set items").system()).isEqualTo("4");
        assertThat(check(r, "counts", "set customers").difference()).isEqualTo("3");
        assertThat(check(r, "counts", "set sales").source()).isEqualTo("9");
        assertThat(check(r, "counts", "set sales").system()).isEqualTo("3");
        // The warnings overridden on rows that were written are listed for the signer, without an amount.
        assertThat(counts.items())
                .filteredOn(i -> i.reason().equals(ImportReconciliation.OVERRIDDEN))
                .extracting(i -> i.file() + ":" + i.line())
                .contains("fab-item-list.csv:4", "fab-buyers.csv:7");

        // Values: every difference is explained by rows left out, each with its own part, and by nothing else.
        for (Section s : r.sections()) {
            for (Check c : s.checks()) {
                assertThat(new BigDecimal(c.difference()))
                        .as("%s %s", s.key(), c.key())
                        .isEqualByComparingTo(new BigDecimal(c.itemised()));
            }
            if (!s.key().equals("counts") && !s.key().equals("ledger")) {
                assertThat(s.items()).as(s.key()).allSatisfy(i -> {
                    assertThat(i.reason()).isEqualTo(ImportReconciliation.EXCLUDED);
                    assertThat(left).contains(i.file() + ":" + i.line());
                });
            }
        }
        // The sale of till line 9 has no valid entry type and no buyer, so the fixture's rules count it as owed to the
        // second branch: it is the whole difference there, as it was left out.
        Check owed = check(r, "receivables", "BR2");
        assertThat(owed.source()).isEqualTo("90000");
        assertThat(section(r, "receivables").items())
                .extracting(Item::file, Item::line, Item::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("fab-till-roll.csv", 9, "45000"));

        // No text of the report names a personal value of the golden files.
        String text = r.render();
        for (String personal : GoldenRun.PERSONAL) {
            assertThat(text).as("the report names %s", personal).doesNotContain(personal);
        }
        // Stored once, with the audit of its making.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action ="
                                + " 'core.import_run.reconciled'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aReceivableDecisionIsListedAsAPolicyDifferenceAndTies() throws IOException {
        UUID run = committedGoldenRun(Policy.TREAT_AS_SETTLED);

        Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();

        assertThat(r.signable()).as(r.render()).isTrue();
        // The credit sale of till line 2 (90000 to buyer B-001) is history only under treat_as_settled: no receivable
        // is opened, and the difference is the decision, listed once with the day the customer confirmed it.
        Check owed = check(r, "receivables", "HQ");
        assertThat(owed.system()).isEqualTo("0");
        List<Item> policy = section(r, "receivables").items().stream()
                .filter(i -> i.reason().equals(ImportReconciliation.POLICY))
                .toList();
        assertThat(policy).hasSize(1);
        assertThat(policy.getFirst().amount()).isEqualTo("90000");
        assertThat(policy.getFirst().line()).isZero();
        assertThat(policy.getFirst().detail()).contains("treat_as_settled", DAY.toString());
        assertThat(policy.getFirst().detail()).doesNotContain("Question list");
    }

    // ---------------------------------------------------------------------------------------------
    // A broken balance blocks the sign-off.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aBrokenBalanceShowsADifferenceThatIsNotItemisedAndTheSignOffIsRefused() throws IOException {
        // The real commit posts the inventory journal one shilling above the stock it counted; the dry run did not.
        FixtureShopTemplate.SCRIPT = null;
        UUID run = committedGoldenRunBroken();

        Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();

        assertThat(r.signable()).isFalse();
        Check value = check(r, "stock_value", "HQ");
        assertThat(value.source()).isEqualTo("1000");
        assertThat(value.system()).isEqualTo("1001");
        assertThat(value.unitemised()).isEqualTo("-1");
        assertThat(value.ties()).isFalse();
        assertThat(check(r, "ledger", "journal HQ inventory").ties()).isFalse();

        ResponseEntity<JsonNode> refused = signOff(t, run, r, allSections(r), "Fabricated Signer", today(t));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("unitemised_difference");
        assertThat(status(run)).isEqualTo("committed");
        assertThat(signoffs(run)).isZero();

        // The database refuses it too, whatever the application does.
        LocalDate day = today(t);
        String insert = """
                INSERT INTO import_signoffs (id, tenant_id, run_id, reconciliation_id, report_sha256, sections,
                                             signed_name, signed_on, signed_by)
                VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, 'Fabricated Signer', ?, ?)
                """;
        Object[] values = {
            UUID.randomUUID(),
            run,
            r.id(),
            r.sha256(),
            allSections(r).toArray(String[]::new),
            java.sql.Date.valueOf(day),
            admin
        };
        assertThatThrownBy(() -> as(
                        t,
                        () -> new TransactionTemplate(transactionManager)
                                .execute(s -> jdbc.sql(insert).params(values).update())))
                .hasStackTraceContaining("a reconciliation with a difference that is not itemised is never signed");
    }

    UUID committedGoldenRunBroken() throws IOException {
        // The dry run rehearses the writer without the extra shilling; the commit runs it with it.
        FixtureShopTemplate.SCRIPT = null;
        return committedGoldenRunWith(() -> extra = 1);
    }

    /** As {@link #committedGoldenRun}, with something done between the approval and the commit. */
    UUID committedGoldenRunWith(Runnable beforeCommit) throws IOException {
        Mapping mapping = as(t, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID runId = as(t, () -> runs.open(operator).id());
        List<UUID> batches = new ArrayList<>();
        for (GoldenRun.GoldenFile g : GoldenRun.FILES) {
            byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve(g.file()));
            batches.add(as(
                            t,
                            () -> uploads.upload(
                                    runId,
                                    new Upload(
                                            g.file(),
                                            new ByteArrayInputStream(bytes),
                                            bytes.length,
                                            operator,
                                            mappings.parseOptions(mapping.id(), g.source()))))
                    .id());
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), runId)
                .update();
        as(t, () -> validation.validate(runId));
        reviewEverything(batches);
        FixtureShopTemplate.SCRIPT = writer(t);
        ImportRuns.DryRun dry = as(t, () -> runs.dryRun(runId, operator));
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        as(t, () -> runs.transition(runId, "ready"));
        as(t, () -> runs.recordPreImportDump(runId, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, t, runs, runId, SHA);
        beforeCommit.run();
        assertThat(as(t, () -> runs.commit(runId))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);
        return runId;
    }

    // ---------------------------------------------------------------------------------------------
    // The sign-off.
    // ---------------------------------------------------------------------------------------------

    HttpHeaders headers(TestDatabase.Fixture f, UUID user, String permissions) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", f.slug());
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** The tenant administrator's permissions for these routes. */
    static final String ADMIN = "core.imports.manage,core.imports.approve_commit,core.imports.sign_off";

    ResponseEntity<JsonNode> send(
            TestDatabase.Fixture f, UUID user, String permissions, HttpMethod method, String path, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(f, user, permissions)), JsonNode.class);
    }

    ResponseEntity<JsonNode> signOff(
            TestDatabase.Fixture f, UUID run, Report r, List<String> sections, String name, LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reconciliation_id", r.id());
        body.put("sha256", r.sha256());
        body.put("sections", sections);
        body.put("name", name);
        body.put("date", date.toString());
        return send(f, admin, ADMIN, HttpMethod.POST, "/api/v1/imports/runs/" + run + "/sign-off", body);
    }

    static List<String> allSections(Report r) {
        return r.sections().stream().map(Section::key).toList();
    }

    LocalDate today(TestDatabase.Fixture f) {
        return as(f, () -> clock.today(currentTenant.profile().timezone()));
    }

    String status(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT status FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
    }

    long signoffs(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM import_signoffs WHERE run_id = ?")
                .param(run)
                .query(Long.class)
                .single();
    }

    @Test
    void theSignOffNeedsTheTenantAdminPermissionEverySectionTheNameAndTodayAndIsAudited() throws IOException {
        UUID run = committedGoldenRun(null);
        Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();
        String path = "/api/v1/imports/runs/" + run;

        // A plain move to signed off is refused: only the sign-off reaches it.
        assertCode(() -> as(t, () -> runs.transition(run, "signed_off")), "invalid_status_transition");

        // Without core.imports.sign_off (the accountant holds the other import permissions): 403 on every route.
        String accountant = "core.imports.manage,core.imports.approve_commit";
        UUID someone = UUID.randomUUID();
        assertThat(send(t, someone, accountant, HttpMethod.GET, path + "/reconciliation", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(t, someone, accountant, HttpMethod.GET, "/api/v1/imports/sign-off", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reconciliation_id", r.id());
        body.put("sha256", r.sha256());
        body.put("sections", allSections(r));
        body.put("name", "Fabricated Signer");
        body.put("date", today(t).toString());
        assertThat(send(t, someone, accountant, HttpMethod.POST, path + "/sign-off", body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // The tenant administrator sees the run to sign and its reconciliation.
        JsonNode list = send(t, admin, ADMIN, HttpMethod.GET, "/api/v1/imports/sign-off", null)
                .getBody();
        assertThat(list.get("items").get(0).get("run_id").asString()).isEqualTo(run.toString());
        assertThat(list.get("items").get(0).get("signable").asBoolean()).isTrue();
        JsonNode read = send(t, admin, ADMIN, HttpMethod.GET, path + "/reconciliation", null)
                .getBody();
        assertThat(read.get("sha256").asString()).isEqualTo(r.sha256());
        assertThat(read.get("sections").size()).isEqualTo(r.sections().size());

        // Every section ticked, a name and today's date, or nothing is signed.
        List<String> missing = new ArrayList<>(allSections(r));
        missing.removeLast();
        assertThat(code(signOff(t, run, r, missing, "Fabricated Signer", today(t))))
                .isEqualTo("sections_not_ticked");
        assertThat(code(signOff(t, run, r, allSections(r), "   ", today(t)))).isEqualTo("name_required");
        assertThat(code(signOff(t, run, r, allSections(r), "Fabricated Signer", today(t).minusDays(1))))
                .isEqualTo("sign_off_date_not_today");
        Map<String, Object> unknown = new LinkedHashMap<>(body);
        unknown.put("approved", true);
        assertThat(send(t, admin, ADMIN, HttpMethod.POST, path + "/sign-off", unknown)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(run)).isEqualTo("committed");
        assertThat(signoffs(run)).isZero();

        // A reconciliation made again since it was read: the signer reads the new one first.
        Report again = as(t, () -> reconciliation.reconcile(run, operator));
        assertThat(again.version()).isEqualTo(2);
        assertThat(code(signOff(t, run, r, allSections(r), "Fabricated Signer", today(t))))
                .isEqualTo("reconciliation_changed");

        ResponseEntity<JsonNode> signed = signOff(t, run, again, allSections(again), " Fabricated Signer ", today(t));
        assertThat(signed.getStatusCode()).as("%s", signed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("signed").get("signed_name").asString()).isEqualTo("Fabricated Signer");
        assertThat(status(run)).isEqualTo("signed_off");
        assertThat(TestDatabase.owner()
                        .sql("SELECT signed_off_at IS NOT NULL FROM import_runs WHERE id = ?")
                        .param(run)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        // Audited with the reconciliation and its hash, never the typed name.
        List<String> audits = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.signed_off'")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst()).contains(again.sha256()).doesNotContain("Fabricated Signer");

        // Signed once: no second sign-off, no new reconciliation, and the signed one is the newest for good.
        assertThat(code(signOff(t, run, again, allSections(again), "Fabricated Signer", today(t))))
                .isEqualTo("run_not_committed");
        assertCode(() -> as(t, () -> reconciliation.reconcile(run, operator)), "run_not_committed");
        assertThat(as(t, () -> reconciliation.latest(run))
                        .orElseThrow()
                        .signed()
                        .sections())
                .containsExactlyInAnyOrderElementsOf(allSections(again));
        assertThat(send(t, admin, ADMIN, HttpMethod.POST, path + "/reconciliation", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    static String code(ResponseEntity<JsonNode> response) {
        return response.getBody() == null || response.getBody().get("code") == null
                ? String.valueOf(response.getStatusCode().value())
                : response.getBody().get("code").asString();
    }

    // ---------------------------------------------------------------------------------------------
    // The review of #873: stock moved after the commit, books changed before the sign-off, a row left out that an
    // earlier run imported.
    // JUSTIFICATION-A3: three new test cases the review's must-fix points 1, 2 and 3 ask for.
    // ---------------------------------------------------------------------------------------------

    /** A stock movement of the fabricated item at the head office, recorded as the business, outside any import. */
    UUID moveStock(BigDecimal qty) {
        UUID product = as(t, () -> new TransactionTemplate(transactionManager).execute(s -> catalogue.productsByCode()))
                .get(FixtureShopTemplate.EXISTING_ITEM.toLowerCase(Locale.ROOT))
                .id();
        return as(
                t,
                () -> new TransactionTemplate(transactionManager)
                        .execute(s -> stock.recordHistorical(
                                        DAY,
                                        List.of(new HistoricalMovement(
                                                t.headOffice(),
                                                product,
                                                "legacy_balance",
                                                qty,
                                                700,
                                                Instant.parse("2026-09-29T08:00:00Z"),
                                                "test_after_import",
                                                null,
                                                null,
                                                "Fabricated stock change after the import")))
                                .getFirst()));
    }

    @Test
    void stockMovedAfterTheCommitIsNotADifferenceButBooksChangedBeforeTheSignOffAreRefused() throws IOException {
        UUID run = committedGoldenRun(null);

        // Stock the business moves after the import is not the import's: the check reads the run's own movements.
        moveStock(BigDecimal.ONE);
        Report again = as(t, () -> reconciliation.reconcile(run, operator));
        assertThat(again.signable()).as(again.render()).isTrue();
        assertThat(check(again, "stock_quantity", "HQ FAB-900").system()).isEqualTo("1.5");

        // What the run brought in changes after the admin read it (a movement registered as the run's): the sign-off
        // measures again in its own transaction and refuses the figures it was shown.
        UUID extra = moveStock(BigDecimal.ONE);
        UUID batch = TestDatabase.owner()
                .sql("SELECT id FROM import_batches WHERE run_id = ? ORDER BY seq LIMIT 1")
                .param(run)
                .query(UUID.class)
                .single();
        TestDatabase.owner()
                .sql("INSERT INTO import_refs (id, tenant_id, run_id, batch_id, source_key, source_ref, target_type,"
                        + " target_id) VALUES (?, ?, ?, ?, 'fab-extra|-|line:1', 'fab-extra|-|line:1#0', ?, ?)")
                .params(UUID.randomUUID(), t.tenantId(), run, batch, "retail.stock_movement", extra)
                .update();
        ResponseEntity<JsonNode> refused = signOff(t, run, again, allSections(again), "Fabricated Signer", today(t));
        assertThat(code(refused)).isEqualTo("reconciliation_changed");
        assertThat(status(run)).isEqualTo("committed");
        assertThat(signoffs(run)).isZero();
        // What it measured is stored as the next version, named in the refusal, so the signer reads the books as they
        // are, never the same version again (review of 1ea73e5): here the extra unit is a difference nothing explains.
        Report next = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(refused.getBody().get("reconciliation_id").asString())
                .isEqualTo(next.id().toString());
        assertThat(next.version()).isEqualTo(again.version() + 1);
        assertThat(next.sha256()).isNotEqualTo(again.sha256());
        assertThat(check(next, "stock_quantity", "HQ FAB-900").system()).isEqualTo("2.5");
        assertThat(next.signable()).isFalse();
    }

    // JUSTIFICATION-A3: the new test case must-fix 1 of the review of 391e1dc asks for.
    @Test
    void aSaleBetweenReadingAndSigningLeavesTheRunSignable() throws IOException {
        UUID run = committedGoldenRun(null);
        Report read = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(read.signable()).as(read.render()).isTrue();

        // The till sells one unit of the imported stock through the retail sale path: a stock movement and the sale's
        // revenue and cost journals, none of them the run's. The sign-off measures again and gets the same text.
        UUID product = as(t, () -> new TransactionTemplate(transactionManager).execute(s -> catalogue.productsByCode()))
                .get(FixtureShopTemplate.EXISTING_ITEM.toLowerCase(Locale.ROOT))
                .id();
        String journals =
                "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_module <> 'core.imports'";
        long before = TestDatabase.owner()
                .sql(journals)
                .param(t.tenantId())
                .query(Long.class)
                .single();
        ResponseEntity<JsonNode> sale = new RetailTestSupport(http, t).sell(t.headOffice(), "cash", product, "1");
        assertThat(sale.getStatusCode().is2xxSuccessful())
                .as(String.valueOf(sale.getBody()))
                .isTrue();
        assertThat(TestDatabase.owner()
                        .sql(journals)
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isGreaterThan(before);

        ResponseEntity<JsonNode> signed = signOff(t, run, read, allSections(read), "Fabricated Signer", today(t));
        assertThat(signed.getStatusCode()).as(String.valueOf(signed.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(status(run)).isEqualTo("signed_off");
        assertThat(signoffs(run)).isOne();
        Report after = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(after.id()).isEqualTo(read.id());
        assertThat(after.sha256()).isEqualTo(read.sha256());
    }

    @Test
    void aRunTooBigToMeasureInTimeIsRefusedPlainlyAndSignsLater() throws IOException {
        UUID run = committedGoldenRun(null);
        Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();

        // The sign-off's own limit is shortened and the books take a second to read: refused, nothing signed.
        ReconciliationService.signOffTimeout = "200ms";
        FixtureShopTemplate.SLOW_BOOKS = true;
        ResponseEntity<JsonNode> refused = signOff(t, run, r, allSections(r), "Fabricated Signer", today(t));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(code(refused)).isEqualTo("sign_off_too_big");
        assertThat(status(run)).isEqualTo("committed");
        assertThat(signoffs(run)).isZero();

        // Tried again when the books answer in time: signed.
        FixtureShopTemplate.SLOW_BOOKS = false;
        ReconciliationService.signOffTimeout = "60s";
        assertThat(signOff(t, run, r, allSections(r), "Fabricated Signer", today(t))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(status(run)).isEqualTo("signed_off");
    }

    // JUSTIFICATION-A3: the fixture row with a negative quantity the Lead's ruling 2 on the review of 391e1dc asks for.
    @Test
    void aNegativeStockBalanceIsListedApartAndTheRunStillTiesAndSigns() throws IOException {
        // A stock take of 2 below zero of a second item the business holds, at the fabricated cost of 700 the writer
        // records. The source counts its value (-1400); the opening inventory journal holds the positive balances only.
        new RetailTestSupport(http, t).product(FixtureShopTemplate.SHORT_ITEM, 700, 900);
        extraTillRows = FixtureShopTemplate.SHORT_ITEM + ",,16/09/2026 09:40,-2,-1400,Stock take,\n";
        UUID run = committedGoldenRun(null);
        Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();

        assertThat(r.signable()).as(r.render()).isTrue();
        assertThat(check(r, "stock_quantity", "HQ " + FixtureShopTemplate.SHORT_ITEM)
                        .system())
                .isEqualTo("-2");
        Check value = check(r, "stock_value", "HQ");
        assertThat(value.system()).isEqualTo("1000");
        assertThat(value.ties()).as(r.render()).isTrue();
        assertThat(value.unitemised()).isEqualTo("0");
        assertThat(section(r, "stock_value").items())
                .filteredOn(i -> i.reason().equals(ImportReconciliation.POLICY))
                .extracting(Item::amount)
                .containsExactly("-1400");
        assertThat(check(r, "ledger", "journal HQ inventory").system()).isEqualTo("1000");
        assertThat(check(r, "ledger", "journal HQ inventory").ties()).isTrue();

        ResponseEntity<JsonNode> signed = signOff(t, run, r, allSections(r), "Fabricated Signer", today(t));
        assertThat(signed.getStatusCode()).as(String.valueOf(signed.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(status(run)).isEqualTo("signed_off");
    }

    // JUSTIFICATION-A3: ruling 2 of the Lead on the review of 1ea73e5, an item counted on two rows, both orders.
    /**
     * Commits the golden run with two counts of FAB-901 (each "qty,amount", at the fabricated cost of 700), both in the
     * golden till roll or the second in a till roll of its own (a second batch), and checks what must hold either way: it
     * ties and signs, the HQ inventory journals tie with nothing to explain (their debits, a fall in a later batch being
     * a journal the other way), the stock value is the journals' net, and it explains no more than the item's net balance
     * below zero over the whole run, if it has one.
     */
    Report twoCounts(
            String first,
            String second,
            boolean twoBatches,
            String netQty,
            String journalled,
            String journalDebits,
            List<String> negative)
            throws IOException {
        new RetailTestSupport(http, t).product(FixtureShopTemplate.SHORT_ITEM, 700, 900);
        String one = FixtureShopTemplate.SHORT_ITEM + ",,16/09/2026 09:40," + first + ",Stock take,\n";
        String two = FixtureShopTemplate.SHORT_ITEM + ",,16/09/2026 09:45," + second + ",Stock take,\n";
        extraTillRows = twoBatches ? one : one + two;
        secondTillRoll = twoBatches ? two : "";
        UUID run = committedGoldenRun(null);
        Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();

        assertThat(r.signable()).as(r.render()).isTrue();
        assertThat(check(r, "stock_quantity", "HQ " + FixtureShopTemplate.SHORT_ITEM)
                        .system())
                .isEqualTo(netQty);
        Check value = check(r, "stock_value", "HQ");
        assertThat(value.system()).isEqualTo(journalled);
        assertThat(value.unitemised()).as(r.render()).isEqualTo("0");
        assertThat(section(r, "stock_value").items())
                .filteredOn(i -> i.reason().equals(ImportReconciliation.POLICY))
                .extracting(Item::amount)
                .containsExactlyElementsOf(negative);
        Check journal = check(r, "ledger", "journal HQ inventory");
        assertThat(journal.source()).as(r.render()).isEqualTo(journalDebits);
        assertThat(journal.system()).isEqualTo(journalDebits);
        assertThat(section(r, "ledger").items()).noneMatch(i -> i.check().equals("journal HQ inventory"));

        ResponseEntity<JsonNode> signed = signOff(t, run, r, allSections(r), "Fabricated Signer", today(t));
        assertThat(signed.getStatusCode()).as(String.valueOf(signed.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(status(run)).isEqualTo("signed_off");
        return r;
    }

    @Test
    void countsOfFiveAndMinusTwoOfOneItemAreJournalledAsThreeAndSign() throws IOException {
        // Net 3 on hand worth 2100, all journalled beside the golden FAB-900's 1000; nothing below zero.
        twoCounts("5,3500", "-2,-1400", false, "3", "3100", "3100", List.of());
    }

    @Test
    void countsOfMinusTwoAndOneOfOneItemAreListedApartAndSign() throws IOException {
        // Net 1 below zero worth -700: no journal holds it, so the journal is the golden FAB-900's 1000 alone.
        twoCounts("-2,-1400", "1,700", false, "-1", "1000", "1000", List.of("-700"));
    }

    @Test
    void countsOfMinusTwoAndThenOneInTwoBatchesAreNettedOverTheRunAndSign() throws IOException {
        // Batch 3 journals FAB-900's 1000 and nothing for -2; batch 4's +1 leaves the item 1 below zero, so it journals
        // nothing either. The -700 is the run's net, listed apart (Lead on the review of 4105c2a).
        twoCounts("-2,-1400", "1,700", true, "-1", "1000", "1000", List.of("-700"));
    }

    @Test
    void countsOfFiveAndThenMinusTwoInTwoBatchesAreNettedOverTheRunAndSign() throws IOException {
        // Batch 3 journals 1000 + 3500 = 4500; batch 4's -2 brings the item to 3, worth 2100, so it journals the fall
        // of 1400 the other way: the journals' debits are 5900 and their net on inventory is 3100.
        twoCounts("5,3500", "-2,-1400", true, "3", "3100", "5900", List.of());
    }

    // JUSTIFICATION-A3: ruling 2 of the Lead on the review of 5b32682, the review section failing closed.
    @Test
    void aResolutionOnABatchNotCommittedOrOnAFieldTheSetDoesNotKnowShowsNoValue() throws IOException {
        UUID run = committedGoldenRun(null);
        // A batch the run never committed (an upload of another run, as a replaced upload would be), and a committed
        // batch with a field its set does not know: resolutions whose values the run no longer holds anywhere.
        Mapping mapping = as(t, () -> mappings.latest("fab-shop").orElseThrow());
        UUID other = as(t, () -> runs.open(operator).id());
        byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve("fab-buyers.csv"));
        UUID notCommitted = as(
                        t,
                        () -> uploads.upload(
                                other,
                                new Upload(
                                        "fab-buyers.csv",
                                        new ByteArrayInputStream(bytes),
                                        bytes.length,
                                        operator,
                                        mappings.parseOptions(mapping.id(), "buyer_book"))))
                .id();
        UUID committed = TestDatabase.owner()
                .sql("SELECT id FROM import_batches WHERE run_id = ? AND mapping_source = 'till_roll'")
                .param(run)
                .query(UUID.class)
                .single();
        String insert = "INSERT INTO import_issue_resolutions (id, tenant_id, run_id, batch_id, issue_id, row_key,"
                + " raw_sha256, source_row_no, code, field, resolution, before_value, after_value, note, resolved_by)"
                + " VALUES (?, ?, ?, ?, ?, 'fabricated-row', ?, ?, 'FABRICATED_CODE', ?, 'edit_value',"
                + " CAST(? AS jsonb), CAST(? AS jsonb), 'Fabricated note', ?)";
        TestDatabase.owner()
                .sql(insert)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        run,
                        notCommitted,
                        UUID.randomUUID(),
                        "e".repeat(64),
                        77,
                        "name",
                        "{\"value\": \"Fabricated Gone Person\"}",
                        "{\"value\": \"Fabricated Gone Persona\"}",
                        operator)
                .update();
        TestDatabase.owner()
                .sql(insert)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        run,
                        committed,
                        UUID.randomUUID(),
                        "f".repeat(64),
                        78,
                        "nickname",
                        "{\"value\": \"Fabricated Odd Value\"}",
                        "{\"value\": \"Fabricated Odd Valued\"}",
                        operator)
                .update();

        Report r = as(t, () -> reconciliation.reconcile(run, operator));
        List<ImportReconciliation.Fact> added = section(r, "review").facts().stream()
                .filter(fact -> fact.label().contains("FABRICATED_CODE"))
                .toList();
        assertThat(added)
                .hasSize(2)
                .allSatisfy(fact -> assertThat(fact.value())
                        .contains("; before a personal value, not shown; after a personal value, not shown"));
        assertThat(r.render()).doesNotContain("Fabricated Gone", "Fabricated Odd");
    }

    @Test
    void killedAfterTheBatchOfFiveAndResumedWithMinusTwoTheRunStillTiesAndSigns() throws IOException {
        // Killed after batch 3 (the golden till roll with +5) committed, resumed with batch 4 (-2) by a writer that
        // holds nothing: it reads the 4500 already journalled from the run's own journals and credits the 1400 fall,
        // as the unbroken run does (Lead on the review of 5b32682).
        killAfterBatch = 3;
        twoCounts("5,3500", "-2,-1400", true, "3", "3100", "5900", List.of());
    }

    @Test
    void aRowLeftOutThatAnEarlierRunImportedIsNotADifference() throws IOException {
        UUID first = committedGoldenRun(null);
        assertThat(status(first)).isEqualTo("committed");

        // A second run of the same item list: every row the first run wrote is already imported; line 4 (a twin of line
        // 2, written by the first run under an overridden warning) is now left out in the review.
        Mapping mapping = as(t, () -> mappings.latest("fab-shop").orElseThrow());
        UUID second = as(t, () -> runs.open(operator).id());
        byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve("fab-item-list.csv"));
        UUID batch = as(
                        t,
                        () -> uploads.upload(
                                second,
                                new Upload(
                                        "fab-item-list.csv",
                                        new ByteArrayInputStream(bytes),
                                        bytes.length,
                                        operator,
                                        mappings.parseOptions(mapping.id(), "item_list"))))
                .id();
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), second)
                .update();
        as(t, () -> validation.validate(second));
        Issue twin = as(t, () -> validation.issues(batch)).stream()
                .filter(i -> i.line() == 4 && "CODE_DUPLICATE".equals(i.code()))
                .findFirst()
                .orElseThrow();
        as(
                t,
                () -> review.resolve(
                        twin.id(),
                        new Resolution("exclude_row", twin.version(), null, null, null, "Fabricated twin left out"),
                        operator));
        reviewEverything(List.of(batch));
        FixtureShopTemplate.SCRIPT = writer(t);
        as(t, () -> runs.dryRun(second, operator));
        as(t, () -> runs.transition(second, "ready"));
        as(t, () -> runs.recordPreImportDump(second, "pre-import-fabricated-2.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, t, runs, second, SHA);
        assertThat(as(t, () -> runs.commit(second))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);

        Report r = as(t, () -> reconciliation.latest(second)).orElseThrow();

        assertThat(r.signable()).as(r.render()).isTrue();
        // A row is counted once, left out decided first (Lead on #873): nine rows that are not blank, three on the
        // system side (written by the first run and recognised now), and the six left out, line 4 among them although
        // the first run holds it, are the difference, each once.
        Check rows = check(r, "counts", "set items");
        assertThat(List.of(rows.source(), rows.system(), rows.difference(), rows.itemised(), rows.unitemised()))
                .containsExactly("9", "3", "6", "6", "0");
        assertThat(section(r, "counts").items())
                .filteredOn(i -> i.reason().equals(ImportReconciliation.EXCLUDED)
                        && i.check().equals("set items"))
                .extracting(Item::line)
                .containsExactlyInAnyOrder(4, 5, 6, 7, 8, 10);
    }

    @Test
    void theSignedReportNeverChangesAndAnotherBusinessReachesNoneOfIt() throws IOException {
        UUID run = committedGoldenRun(null);
        Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(signOff(t, run, r, allSections(r), "Fabricated Signer", today(t))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // No update path: bms_app may not change or delete either table, and nor may the owner (append-only).
        for (String table : List.of("import_reconciliations", "import_signoffs")) {
            assertThatThrownBy(() -> as(
                            t,
                            () -> new TransactionTemplate(transactionManager)
                                    .execute(s -> jdbc.sql("UPDATE " + table + " SET created_at = now()")
                                            .update())))
                    .as(table)
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> TestDatabase.owner()
                            .sql("DELETE FROM " + table + " WHERE run_id = ?")
                            .param(run)
                            .update())
                    .as(table)
                    .hasStackTraceContaining("append-only");
            assertThat(TestDatabase.owner()
                            .sql("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = ?")
                            .param(table)
                            .query(Boolean.class)
                            .single())
                    .as(table)
                    .isTrue();
        }
        // No new reconciliation of a signed run, even written by hand.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("""
                                INSERT INTO import_reconciliations (id, tenant_id, run_id, version, as_at, report, sha256,
                                                                    signable)
                                VALUES (?, ?, ?, 9, DATE '2026-10-01', '{}', ?, true)
                                """)
                        .params(UUID.randomUUID(), t.tenantId(), run, "e".repeat(64))
                        .update())
                .hasStackTraceContaining("the reconciliation of a signed off import never changes");
        Report stored = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(stored.sha256()).isEqualTo(r.sha256());
        assertThat(ReconciliationService.sha256(stored.render())).isEqualTo(r.sha256());

        // Another business: 404 through the service and the routes, and nothing seen as bms_app under forced RLS.
        as(other, () -> {
            assertCode(() -> reconciliation.latest(run), "not_found");
            assertCode(() -> reconciliation.reconcile(run, operator), "not_found");
            assertThat(reconciliation.forSignOff()).isEmpty();
            return null;
        });
        assertThat(send(other, admin, ADMIN, HttpMethod.GET, "/api/v1/imports/runs/" + run + "/reconciliation", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(signOff(other, run, r, allSections(r), "Fabricated Signer", today(other))))
                .isEqualTo("not_found");
        List<Long> seen = as(
                other,
                () -> new TransactionTemplate(transactionManager)
                        .execute(s -> List.of(
                                jdbc.sql("SELECT count(*) FROM import_reconciliations")
                                        .query(Long.class)
                                        .single(),
                                jdbc.sql("SELECT count(*) FROM import_signoffs")
                                        .query(Long.class)
                                        .single())));
        assertThat(seen).containsExactly(0L, 0L);
    }
}
