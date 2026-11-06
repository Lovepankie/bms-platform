package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.internal.StoredObjects;
import com.rincoltech.bms.core.imports.CommitApproval;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.DryRun;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.stock.StockLedger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dry run of issue #132 over the retail export (ADR-027 decisions 1 and 5): the framework's dry run runs
 * {@code import-retail}'s own real path ({@link RetailImporter#exportWriter}) inside one outer transaction that is rolled
 * back. It writes nothing (every table keeps its row count, the audit log included), and its stored report equals the report
 * of a real {@code import-retail} run on the same export, which stays the retail path until #135. Its report names no
 * buyer, and another business cannot read it. The export is the fabricated {@code fixtures/retail/import-sample/}.
 */
class RetailDryRunIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    RetailImporter importer;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    StockLedger stock;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ApplicationContext context;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-dry", false, true);
        other = TestDatabase.tenant("retail-dry-b", false, true);
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    @AfterEach
    void sample() {
        RetailExportTestTemplate.DIR = RetailImportIT.SAMPLE;
        RetailExportTestTemplate.FORECAST = null;
    }

    @Autowired
    TestRestTemplate http;

    // JUSTIFICATION-A3: the Lead's ruling 5 on #872: the export committed through the framework's own commit path.
    /**
     * The retail export committed through the framework (Lead ruling 5 on #872): the commit path of #133, the request and a
     * second person's approval, the verified dump, one batch transaction with the no-side-effect guard and the check that
     * every journal it posts is a registered opening journal, runs {@code import-retail}'s own logic as the batch's writer
     * and gives what a real {@code import-retail} run gives. The forecast is a stand-in set from the first dry run until
     * the retail template of #135 has its own, so this proves the commit path, not the retail preview.
     */
    @Test
    void theExportCommitsThroughTheFrameworkWithImportRetailsOwnLogic() {
        UUID run = inReview(t);
        DryRun first = dryRun(run);
        assertThat(first.report().failure()).as(first.report().render()).isNull();
        RetailExportTestTemplate.FORECAST = first.report().balances();
        DryRun dry = dryRun(run);
        assertThat(dry.previewMatches()).isTrue();
        as(t, () -> runs.transition(run, "ready"));
        String sha = "e".repeat(64);
        as(t, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", java.time.Instant.now(), sha));
        CommitApproval.readyToCommit(http, t, runs, run, sha);

        assertThat(as(t, () -> runs.commit(run))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);

        CommitReport committed = as(t, () -> runs.commitReport(run));
        assertThat(committed.failure()).isNull();
        assertThat(committed.sameAs(dry.report()))
                .as("real:%n%s%ndry:%n%s", committed.render(), dry.report().render())
                .isTrue();
        assertThat(committed.journals())
                .extracting(CommitReport.Journal::branch, CommitReport.Journal::debitMinor)
                .containsExactlyInAnyOrder(RetailImportIT.OPENING.entrySet().stream()
                        .map(e -> tuple(e.getKey(), e.getValue()))
                        .toArray(Tuple[]::new));
        stockAndJournalsAreTheBooks(committed);
        // import-retail's own references, and every journal the batch posted registered as an opening journal.
        long journals = TestDatabase.owner()
                .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(journals).isPositive();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM import_refs WHERE tenant_id = ?"
                                + " AND target_type = 'ledger.journal_entry'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(journals);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_import_refs WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isPositive();
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committed");
    }

    /**
     * A run in review with one batch, a sheet naming the export, read with a mapping of the test-only template whose loader
     * is the export's writer (until #135 gives each file its own batch). The commit and the dry run resolve the writer from
     * that template (Lead ruling 4 on #869).
     */
    UUID inReview(TestDatabase.Fixture f) {
        byte[] sheet = "Export\nfabricated export\n".getBytes(StandardCharsets.UTF_8);
        return as(f, () -> {
            UUID mapping = mappings.latest("retail-export")
                    .orElseGet(() -> mappings.save("retail-export", RetailExportTestTemplate.mapping(), operator, null))
                    .id();
            UUID run = runs.open(operator).id();
            uploads.upload(
                    run,
                    new Upload(
                            "fab-export.csv",
                            new ByteArrayInputStream(sheet),
                            sheet.length,
                            operator,
                            mappings.parseOptions(mapping, "export")));
            runs.transition(run, "in_review");
            return run;
        });
    }

    DryRun dryRun(UUID run) {
        return as(t, () -> runs.dryRun(run, operator));
    }

    @Test
    void theDryRunWritesNothingAndItsReportEqualsARealImportRetailRun() {
        UUID run = inReview(t);
        Map<String, Long> before = TestDatabase.rowCounts(t.tenantId());
        List<String> files = StoredObjects.keys(context, "tenants/" + t.tenantId() + "/");

        DryRun dry = dryRun(run);

        assertThat(TestDatabase.rowCounts(t.tenantId()))
                .as("every table and sequence, the audit log included")
                .isEqualTo(before);
        assertThat(StoredObjects.keys(context, "tenants/" + t.tenantId() + "/")).isEqualTo(files);
        // Every opening journal as the ledger held it inside the dry run: balanced, the credit read from its lines.
        assertThat(dry.report().journals())
                .allSatisfy(j -> assertThat(j.creditMinor()).isEqualTo(j.debitMinor()));
        CommitReport report = dry.report();
        assertThat(report.failure()).as(report.render()).isNull();
        assertThat(report.checksum()).contains("match=yes");
        // read, written, existing, skipped: the golden figures of RetailImportIT.
        assertThat(report.sources())
                .contains(
                        new CommitReport.Source("branches.jsonl", 3, 3, 0, 0),
                        new CommitReport.Source("products.jsonl", 21, 20, 0, 1),
                        new CommitReport.Source("purchases.jsonl", 34, 34, 0, 0),
                        new CommitReport.Source("sales.jsonl", 221, 220, 0, 1),
                        new CommitReport.Source("usage.jsonl", 15, 15, 0, 0),
                        new CommitReport.Source("balances.jsonl", 60, 60, 0, 0));
        assertThat(report.journals())
                .extracting(CommitReport.Journal::branch, CommitReport.Journal::debitMinor)
                .containsExactlyInAnyOrder(RetailImportIT.OPENING.entrySet().stream()
                        .map(e -> tuple(e.getKey(), e.getValue()))
                        .toArray(Tuple[]::new));
        assertThat(report.anomalies())
                .anyMatch(a -> a.file().equals("sales.jsonl")
                        && a.line() == 221
                        && a.message().contains("TP-404"));

        // The real path for now: import-retail itself, on the same export.
        ImportReport real = importer.run(t.slug(), RetailImportIT.SAMPLE, false);
        assertThat(real.failed()).as(real.render()).isFalse();
        CommitReport committed = importer.toCommitReport(t.slug(), real);
        assertThat(committed.mode()).isEqualTo(CommitReport.COMMIT);
        assertThat(report.sameAs(committed))
                .as("dry run:%n%s%nreal run:%n%s", report.render(), committed.render())
                .isTrue();
        assertThat(committed.render().replace("committed, one transaction per batch", "x"))
                .isEqualTo(report.render().replace("dry run, every write rolled back", "x"));
        stockAndJournalsAreTheBooks(report);
    }

    // JUSTIFICATION-A3: the Lead's ruling 2 on #869: stock per branch and each journal checked against the books.
    /**
     * Lead ruling 2 on #869: the dry run's stock per branch is the stock after the real run, on every branch the export has
     * (three), read from the stock ledger and the inventory account, not from a report; and each journal's debit and
     * credit are what its lines in the ledger hold, balanced.
     */
    void stockAndJournalsAreTheBooks(CommitReport report) {
        Map<UUID, String> codes = new HashMap<>();
        TestDatabase.owner()
                .sql("SELECT id, code FROM branches WHERE tenant_id = ?")
                .param(t.tenantId())
                .query((rs, n) -> codes.put(rs.getObject("id", UUID.class), rs.getString("code")))
                .list();
        Map<String, BigDecimal> onHand = new TreeMap<>();
        as(
                t,
                () -> new TransactionTemplate(transactionManager).execute(s -> {
                    stock.positions().forEach(p -> onHand.merge(codes.get(p.branchId()), p.balance(), BigDecimal::add));
                    return null;
                }));
        assertThat(report.stock()).hasSizeGreaterThanOrEqualTo(2);
        for (ImportForecast.Stock s : report.stock()) {
            assertThat(onHand.getOrDefault(s.branch(), BigDecimal.ZERO))
                    .as("stock on hand at %s", s.branch())
                    .isEqualByComparingTo(s.qty());
            assertThat(TestDatabase.owner()
                            .sql("""
                                    SELECT coalesce(sum(l.debit - l.credit), 0)
                                      FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                                      JOIN branches b ON b.id = e.branch_id JOIN gl_accounts a ON a.id = l.account_id
                                     WHERE l.tenant_id = ? AND a.system_key = 'inventory' AND b.code = ?
                                    """)
                            .params(t.tenantId(), s.branch())
                            .query(Long.class)
                            .single())
                    .as("inventory at %s", s.branch())
                    .isEqualTo(s.valueMinor());
        }
        for (CommitReport.Journal j : report.journals()) {
            Map<String, Object> lines = TestDatabase.owner()
                    .sql("""
                            SELECT sum(l.debit) AS debit, sum(l.credit) AS credit
                              FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                             WHERE e.tenant_id = ? AND e.entry_no = ?
                            """)
                    .params(t.tenantId(), j.entryNo())
                    .query()
                    .singleRow();
            assertThat(((Number) lines.get("debit")).longValue()).isEqualTo(j.debitMinor());
            assertThat(((Number) lines.get("credit")).longValue()).isEqualTo(j.creditMinor());
            assertThat(j.debitMinor()).isEqualTo(j.creditMinor());
        }
    }

    // JUSTIFICATION-A3: the review of #869 asks that a changed export is not the export rehearsed; a test case.
    @Test
    void theExportsContentIsPartOfTheWritersIdentity(@TempDir Path copy) throws IOException {
        for (String name : RetailImporter.FILES) {
            Path file = RetailImportIT.SAMPLE.resolve(name);
            if (Files.isRegularFile(file)) {
                Files.copy(file, copy.resolve(name));
            }
        }
        String sample =
                as(t, () -> importer.exportWriter(RetailImportIT.SAMPLE, null).identity());
        assertThat(as(t, () -> importer.exportWriter(copy, null).identity()))
                .as("the same files")
                .isEqualTo(sample);
        UUID run = inReview(t);
        RetailExportTestTemplate.DIR = copy;
        assertThat(dryRun(run).writer()).endsWith(" export " + sample + "\n");
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().current()).isTrue();

        // A file changed after the dry run: the writer is not the one rehearsed, so the dry run no longer counts.
        Path balances = copy.resolve("balances.jsonl");
        Files.writeString(balances, Files.readString(balances) + "\n");
        assertThat(as(t, () -> importer.exportWriter(copy, null).identity())).isNotEqualTo(sample);
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().current()).isFalse();
        // No first live day given is the business's today, named as that day: a commit another day is another writer.
        LocalDate today = LocalDate.parse(sample.substring(sample.indexOf(" first-live:") + " first-live:".length()));
        Path sampleDir = RetailImportIT.SAMPLE;
        assertThat(as(t, () -> importer.exportWriter(sampleDir, today).identity()))
                .isEqualTo(sample);
        LocalDate tomorrow = today.plusDays(1);
        assertThat(as(t, () -> importer.exportWriter(sampleDir, tomorrow).identity()))
                .as("another first live day")
                .isNotEqualTo(sample);
        assertThat(RetailImporter.atLeastThreePlaces(new BigDecimal("1.23456")))
                .as("a quantity of more places is never rounded")
                .isEqualTo(new BigDecimal("1.23456"));
        assertThat(RetailImporter.atLeastThreePlaces(new BigDecimal("2"))).isEqualTo(new BigDecimal("2.000"));
    }

    @Test
    void theReportNamesNoBuyer() throws IOException {
        UUID run = inReview(t);

        DryRun dry = dryRun(run);

        String text = dry.report().render();
        String stored = TestDatabase.owner()
                .sql("SELECT dry_run_report::text FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
        List<String> personal = personalValues();
        assertThat(personal).isNotEmpty();
        for (String value : personal) {
            assertThat(text).as("the report names %s", value).doesNotContain(value);
            assertThat(stored).as("the stored report names %s", value).doesNotContain(value);
        }
    }

    @Test
    void anotherBusinessCannotReadOrRunTheDryRun() {
        UUID run = inReview(t);
        dryRun(run);

        as(other, () -> {
            for (Runnable call : List.<Runnable>of(() -> runs.dryRunReport(run), () -> runs.dryRun(run, operator))) {
                try {
                    call.run();
                    throw new AssertionError("another business reached the run");
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("not_found");
                }
            }
            return null;
        });
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().current()).isTrue();
    }

    /** Every buyer's name and contact the export holds, of four characters or more. */
    static List<String> personalValues() throws IOException {
        List<String> out = new ArrayList<>();
        for (String[] fileAndFields : List.of(
                new String[] {"customers.jsonl", "name", "contact"},
                new String[] {"sales.jsonl", "buyer", "buyer_contact"})) {
            for (String line :
                    Files.readAllLines(RetailImportIT.SAMPLE.resolve(fileAndFields[0]), StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                for (int i = 1; i < fileAndFields.length; i++) {
                    JsonNode v = node.get(fileAndFields[i]);
                    if (v != null && v.isString() && v.asString().strip().length() >= 4) {
                        out.add(v.asString().strip());
                    }
                }
            }
        }
        return out.stream().distinct().toList();
    }
}
