package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.CommitApproval;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportReconciliation;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.DryRun;
import com.rincoltech.bms.core.imports.ImportRuns.PolicyDecision;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.Policy;
import com.rincoltech.bms.core.imports.ImportTemplate.Severity;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code retail_v1} on the framework (issue #135, ADR-027 decision 1): the production template, its loader the
 * {@code import-retail} command's own code, run by the framework's upload, validation, review, preview, dry run, maker
 * and checker commit, reconciliation and sign-off. On the fabricated {@code fixtures/retail/import-sample/} with the
 * export's own mapping, the dry run writes nothing and predicts the commit, the commit gives the tenant state the command
 * gives, and the run reconciles and is signed off. A fabricated spreadsheet-shaped book in another shape
 * ({@code fixtures/retail/import-shapes/sheet/}) read with a mapping ({@code fixtures/imports/retail_v1.shop_book.mapping.json})
 * gives the state the same data as a JSON Lines export ({@code fixtures/retail/import-shapes/export/}) gives through the
 * command. No real data: every name, code and amount is made up.
 */
class RetailV1TemplateIT extends IntegrationTest {

    static final Path SHAPES = Path.of("../fixtures/retail/import-shapes");

    static final Path SHEET_MAPPING = Path.of("../fixtures/imports/retail_v1.shop_book.mapping.json");

    static final Path EDGE_CASES = Path.of("../fixtures/imports/retail/fab-edge-cases");

    static final Path EDGE_EXPECTED = Path.of("../fixtures/imports/retail/fab-edge-cases.expected-codes.txt");

    // JUSTIFICATION-A3: the review of 74bfe3e (must-fix 2) asks the golden to cover the catalogue.
    /**
     * The row codes of the {@code retail_v1} catalogue that a validation of an export cannot carry, with where each is
     * produced: the mapping's checks, the parse, the review, the commit, and the shared rules no retail set uses.
     */
    static final Map<String, String> OTHER_STAGES = Map.ofEntries(
            Map.entry("COLUMN_UNMAPPED", "the mapping's check of a file's headers (MappingValidatorTest)"),
            Map.entry("REQUIRED_FIELD_UNMAPPED", "the mapping's check (MappingValidatorTest)"),
            Map.entry("FIELD_MAPPED_TWICE", "the mapping's check (MappingValidatorTest)"),
            Map.entry("COLUMN_DUPLICATE", "the mapping's check (MappingValidatorTest)"),
            Map.entry("MAP_FIELD_UNMAPPED", "the mapping's check (MappingValidatorTest)"),
            Map.entry("SOURCE_REF_COLUMN_UNKNOWN", "the mapping's check (MappingValidatorTest)"),
            Map.entry("SOURCE_REF_COLUMN_PERSONAL", "the mapping's check (MappingValidatorTest)"),
            Map.entry("ZONE_UNKNOWN", "the mapping's check (MappingValidatorTest)"),
            Map.entry("REQUIRED_COLUMN_MISSING", "the mapping's check of a file's headers (MappingValidatorTest)"),
            Map.entry("ENCODING_CONVERTED", "a parse warning of the batch (SourceParserTest, ImportMalformedFilesIT)"),
            Map.entry("ROW_CHANGED_SINCE_REVIEW", "a new upload after a review (ImportReviewIT)"),
            Map.entry("CHANGED_SINCE_IMPORT", "a row a framework run imported with other content (ImportCommitIT)"),
            Map.entry("NIN_DUPLICATE", "no retail_v1 field is a NIN (the lending golden)"),
            Map.entry("SUBHEADER_ROW", "a row rule no retail_v1 set offers (the lending golden)"),
            Map.entry("PLACEHOLDER_ROW", "a row rule no retail_v1 set offers (the lending golden)"));

    static final String SHA = "e".repeat(64);

    /** The tenant administrator's permissions for the sign-off route. */
    static final String ADMIN = "core.imports.manage,core.imports.approve_commit,core.imports.sign_off";

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
    ImportValidation validation;

    @Autowired
    ImportReview review;

    @Autowired
    ImportReconciliation reconciliation;

    @Autowired
    ImportTemplates templates;

    @Autowired
    BusinessClock clock;

    @Autowired
    CurrentTenant currentTenant;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-v1", false, true);
        other = TestDatabase.tenant("retail-v1-b", false, true);
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    @Test
    void theSampleCommitsThroughTheFrameworkAsTheCommandWritesItReconcilesAndIsSignedOff() throws IOException {
        UUID run = reviewed(t, "import-retail-export", exportMapping(), RetailImportIT.SAMPLE, exportFiles());
        Map<String, Long> before = TestDatabase.rowCounts(t.tenantId());

        DryRun dry = as(t, () -> runs.dryRun(run, operator));

        assertThat(TestDatabase.rowCounts(t.tenantId()))
                .as("the dry run writes nothing, the audit log included")
                .isEqualTo(before);
        CommitReport predicted = dry.report();
        assertThat(predicted.failure()).as(predicted.render()).isNull();
        assertThat(dry.previewMatches()).as(predicted.render()).isTrue();
        assertThat(predicted.checksum()).contains("match=yes");
        assertThat(predicted.journals())
                .extracting(CommitReport.Journal::branch, CommitReport.Journal::debitMinor)
                .containsExactlyInAnyOrder(RetailImportIT.OPENING.entrySet().stream()
                        .map(e -> tuple(e.getKey(), e.getValue()))
                        .toArray(Tuple[]::new));
        assertThat(predicted.journals())
                .allSatisfy(j -> assertThat(j.creditMinor()).isEqualTo(j.debitMinor()));

        committed(t, run);

        CommitReport real = as(t, () -> runs.commitReport(run));
        assertThat(real.sameAs(predicted))
                .as("real:%n%s%ndry:%n%s", real.render(), predicted.render())
                .isTrue();
        // The opening stock journals are the framework's, of retail's own ledger type, and registered.
        assertThat(owner("""
                        SELECT count(*) FROM journal_entries e WHERE e.tenant_id = ?
                           AND (e.source_module <> 'core.imports' OR e.source_type <> 'retail.import_opening'
                                OR NOT EXISTS (SELECT 1 FROM import_refs r WHERE r.target_id = e.id))
                        """, t)).isZero();
        assertThat(owner("SELECT count(*) FROM journal_entries WHERE tenant_id = ?", t))
                .isEqualTo(3L);

        // The same export through the command, into another business: the same books.
        ImportReport command = importer.run(other.slug(), RetailImportIT.SAMPLE, false);
        assertThat(command.failed()).as(command.render()).isFalse();
        assertThat(state(t)).isEqualTo(state(other));

        // The reconciliation the commit made ties: every difference is itemised (the row left out, the negative
        // balance listed separately), and the tenant administrator signs it.
        ImportReconciliation.Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(r.signable()).as(r.render()).isTrue();
        assertThat(r.render()).contains("negative balance of TP-007 listed separately");
        ResponseEntity<JsonNode> signed = signOff(t, run, r);
        assertThat(signed.getStatusCode()).as("%s", signed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("signed_off");
    }

    // JUSTIFICATION-A3: issue #137 asks a golden test per template (spec section 5.1), retail_v1's here.
    /**
     * The golden report of {@code retail_v1} (issue #137, spec section 5.1): the fabricated edge cases of
     * {@code fixtures/imports/retail/fab-edge-cases/}, read with the export's mapping, give every line of
     * {@code fab-edge-cases.expected-codes.txt}, which holds every code of the template's own on one fixture row each. Then,
     * reviewed, the dry run writes nothing, the commit reports what it predicted, the run reconciles, and another business
     * is untouched.
     */
    @Test
    void theGoldenEdgeCasesGiveEveryRetailCodeOnItsLineAndCommitAsTheDryRunPredicts(@TempDir Path seed)
            throws IOException {
        // GE-EX1 already imported, with other prices.
        Files.writeString(seed.resolve("categories.jsonl"), "{\"name\": \"Test Category\"}\n");
        Files.writeString(seed.resolve("units.jsonl"), "{\"name\": \"piece\"}\n");
        Files.writeString(
                seed.resolve("products.jsonl"),
                "{\"code\": \"GE-EX1\", \"description\": \"Test golden existing item\", \"category\": \"Test Category\","
                        + " \"unit\": \"piece\", \"cost_minor\": 500, \"sell_minor\": 800}\n");
        ImportReport seeded = importer.run(t.slug(), seed, false);
        assertThat(seeded.failed()).as(seeded.render()).isFalse();

        UUID run = uploaded(t, "golden-edge-cases", exportMapping(), EDGE_CASES, exportFiles());
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(Instant.parse("2026-09-30T21:00:00Z")), run)
                .update();
        as(t, () -> validation.validate(run));
        List<String> found = new ArrayList<>();
        for (ImportUploads.Batch b : as(t, () -> uploads.batches(run))) {
            for (Issue i : as(t, () -> validation.issues(b.id()))) {
                found.add(b.fileName() + "|" + i.line() + "|" + i.code());
            }
        }
        List<String> expected = Files.readAllLines(EDGE_EXPECTED).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
        // Exactly the golden report, as a multiset: no line missing and none added (review of 74bfe3e).
        assertThat(found)
                .as(
                        "the run's report:%n%s",
                        String.join("\n", found.stream().sorted().toList()))
                .containsExactlyInAnyOrderElementsOf(expected);
        // Every code of the catalogue a row can carry is on a line of it, but those another stage produces.
        Set<String> rowCodes = templates.catalogue(RetailV1Template.KEY, RetailV1Template.VERSION).stream()
                .filter(c -> !c.retired())
                .filter(c -> List.of(Severity.INFO, Severity.WARNING, Severity.BLOCKING)
                        .contains(c.severity()))
                .map(IssueCode::code)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(rowCodes).containsAll(OTHER_STAGES.keySet());
        Set<String> golden = expected.stream()
                .map(l -> l.substring(l.lastIndexOf('|') + 1))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(golden).doesNotContainAnyElementsOf(OTHER_STAGES.keySet());
        rowCodes.removeAll(OTHER_STAGES.keySet());
        assertThat(golden).as("every row code of retail_v1 on a golden line").containsAll(rowCodes);

        settle(t, run);
        Map<String, Long> before = TestDatabase.rowCounts(t.tenantId());
        Map<String, Long> untouched = TestDatabase.rowCounts(other.tenantId());
        DryRun dry = as(t, () -> runs.dryRun(run, operator));
        assertThat(TestDatabase.rowCounts(t.tenantId()))
                .as("the dry run writes nothing")
                .isEqualTo(before);
        committed(t, run);
        CommitReport real = as(t, () -> runs.commitReport(run));
        assertThat(real.sameAs(dry.report()))
                .as("real:%n%s%ndry:%n%s", real.render(), dry.report().render())
                .isTrue();
        ImportReconciliation.Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(r.signable()).as(r.render()).isTrue();
        assertThat(TestDatabase.rowCounts(other.tenantId()))
                .as("another business")
                .isEqualTo(untouched);
    }

    @Test
    void aSpreadsheetShapedBookWithAMappingGivesTheStateOfTheSameDataAsAJsonLinesExport() throws IOException {
        Map<String, Object> mapping = JSON.readValue(Files.readString(SHEET_MAPPING), new TypeReference<>() {});
        Map<String, String> files = new LinkedHashMap<>();
        files.put("shops", "fab-shops.csv");
        files.put("categories", "fab-categories.csv");
        files.put("units", "fab-units.csv");
        files.put("product_master", "fab-product-master.csv");
        files.put("suppliers", "fab-suppliers.csv");
        files.put("buyers", "fab-buyers.csv");
        files.put("restock_log", "fab-restock-log.csv");
        files.put("sales_log", "fab-sales-log.csv");
        files.put("usage_log", "fab-usage-log.csv");
        files.put("stock_on_hand", "fab-stock-on-hand.csv");
        UUID run = reviewed(t, "fab-shop-book", mapping, SHAPES.resolve("sheet"), files);
        DryRun dry = as(t, () -> runs.dryRun(run, operator));
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        committed(t, run);

        ImportReport command = importer.run(other.slug(), SHAPES.resolve("export"), false);
        assertThat(command.failed()).as(command.render()).isFalse();

        assertThat(state(t)).isEqualTo(state(other));
        // Fabricated figures worked out by hand: KLA 12.5 x 700 + 1.5 x 2500, ENT 5 x 700 + 2 x 4000; ENT's -1 of
        // TS-002 is listed for the first stock-take, never journalled.
        assertThat(state(t)).contains("journal|ENT|inventory|11500|0", "journal|KLA|inventory|12500|0");
        ImportReconciliation.Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(r.signable()).as(r.render()).isTrue();
    }

    // JUSTIFICATION-A3: the Lead's steer for round 4 asks these two integration tests (must-fix B and C).
    /**
     * Must-fix C of the review of 51cfb70 (Lead's steer): on a business the command imported, the same export uploaded
     * through the screens previews no journal, its dry run matches the preview, and its commit posts no second journal
     * and writes nothing twice: the books are the command's.
     */
    @Test
    void theSameExportThroughTheScreensAfterTheCommandPostsNoSecondJournalAndWritesNoDuplicates() throws IOException {
        ImportReport command = importer.run(t.slug(), RetailImportIT.SAMPLE, false);
        assertThat(command.failed()).as(command.render()).isFalse();
        List<String> before = state(t);
        assertThat(owner("SELECT count(*) FROM journal_entries WHERE tenant_id = ?", t))
                .isEqualTo(3L);

        UUID run = reviewed(t, "retail-export-upload", exportMapping(), RetailImportIT.SAMPLE, exportFiles());
        DryRun dry = as(t, () -> runs.dryRun(run, operator));

        assertThat(dry.report().failure()).as(dry.report().render()).isNull();
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        assertThat(dry.report().journals()).as(dry.report().render()).isEmpty();
        committed(t, run);

        assertThat(owner("SELECT count(*) FROM journal_entries WHERE tenant_id = ?", t))
                .as("no second journal")
                .isEqualTo(3L);
        assertThat(state(t)).as("no duplicates: the books are the command's").isEqualTo(before);
    }

    /**
     * Must-fix B of the review of 51cfb70 (Lead's steer): a credit sale whose buyer is not in customers.jsonl. The loader
     * creates no customer for it (a sales row only looks the buyer up, {@code RetailImporter.sales}); the sale is written
     * and registered, and the reconciliation, which finds every record the run wrote, ties.
     */
    @Test
    void aCreditSaleNamingABuyerMissingFromTheCustomersFileCreatesNoUnregisteredRecord(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("categories.jsonl"), "{\"name\": \"Test Category\"}\n");
        Files.writeString(dir.resolve("units.jsonl"), "{\"name\": \"piece\"}\n");
        Files.writeString(dir.resolve("products.jsonl"), """
                {"code": "TB-7", "description": "Test cup seven", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                """);
        Files.writeString(dir.resolve("sales.jsonl"), """
                {"source_ref": "S-77", "branch": "HQ", "product_code": "TB-7", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "credit", "buyer": "Test Buyer 77", "sold_at": "2026-09-01T10:00:00"}
                """);
        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-7", "qty": "0"}
                """);
        UUID run = reviewed(t, "retail-export-buyer", exportMapping(), dir, exportFiles());
        committed(t, run);

        String report = as(t, () -> runs.commitReport(run)).render();
        assertThat(report).contains("credit buyer is not in customers.jsonl; kept as entered");
        assertThat(owner("SELECT count(*) FROM retail_customers WHERE tenant_id = ?", t))
                .as("no customer is created by a sales row")
                .isZero();
        assertThat(owner("SELECT count(*) FROM retail_sales WHERE tenant_id = ?", t))
                .isEqualTo(1L);
        assertThat(owner("""
                        SELECT count(*) FROM retail_sales s WHERE s.tenant_id = ?
                           AND NOT EXISTS (SELECT 1 FROM import_refs r WHERE r.target_id = s.id)
                        """, t)).as("the sale is registered").isZero();
        ImportReconciliation.Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(r.signable()).as(r.render()).isTrue();
    }

    // JUSTIFICATION-A3: the Lead's rule on the review of 4af7a39 asks tests (a) and (b) through the screens.
    /**
     * (a) of the Lead's rule on the review of 4af7a39 (one opening journal per branch, ever): after the command imported
     * the sample, a balances file with one KLA row, its quantity changed, goes through the screens. It commits, posts no
     * journal, and lists the difference for a stock-take.
     */
    @Test
    void aOneRowBalancesFileAfterTheCommandCommitsWithNoJournalAndListsTheDifference(@TempDir Path dir)
            throws IOException {
        ImportReport command = importer.run(t.slug(), RetailImportIT.SAMPLE, false);
        assertThat(command.failed()).as(command.render()).isFalse();
        Files.writeString(dir.resolve("balances.jsonl"), changedKlaRow() + "\n");

        UUID run = reviewed(t, "retail-one-row", exportMapping(), dir, exportFiles());
        committed(t, run);

        assertThat(owner("SELECT count(*) FROM journal_entries WHERE tenant_id = ?", t))
                .as("no second opening journal")
                .isEqualTo(3L);
        assertThat(as(t, () -> runs.commitReport(run)).render())
                .contains("differs from the opening")
                .contains("correct it with a stock-take");
    }

    /**
     * (b) of the Lead's rule: the whole export again through the screens after the command, one KLA row changed. It
     * commits, posts no journal, and lists the one difference for a stock-take.
     */
    @Test
    void aFullReUploadWithOneChangedRowCommitsWithNoJournalAndListsTheDifference(@TempDir Path dir) throws IOException {
        ImportReport command = importer.run(t.slug(), RetailImportIT.SAMPLE, false);
        assertThat(command.failed()).as(command.render()).isFalse();
        try (java.util.stream.Stream<Path> files = Files.list(RetailImportIT.SAMPLE)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        String first = Files.readAllLines(RetailImportIT.SAMPLE.resolve("balances.jsonl")).stream()
                .filter(l -> l.contains("\"KLA\""))
                .findFirst()
                .orElseThrow();
        Files.writeString(
                dir.resolve("balances.jsonl"),
                Files.readString(dir.resolve("balances.jsonl")).replace(first, changedKlaRow()));

        UUID run = reviewed(t, "retail-changed-row", exportMapping(), dir, exportFiles());
        committed(t, run);

        assertThat(owner("SELECT count(*) FROM journal_entries WHERE tenant_id = ?", t))
                .as("no second opening journal")
                .isEqualTo(3L);
        assertThat(as(t, () -> runs.commitReport(run)).render())
                .contains("differs from the opening")
                .contains("correct it with a stock-take");
    }

    /** The sample's first KLA balance row with its quantity changed to 1. */
    private static String changedKlaRow() throws IOException {
        return Files.readAllLines(RetailImportIT.SAMPLE.resolve("balances.jsonl")).stream()
                .filter(l -> l.contains("\"KLA\""))
                .findFirst()
                .orElseThrow()
                .replaceFirst("\"qty\": \"[^\"]*\"", "\"qty\": \"1\"");
    }

    @Test
    void theReceivableDecisionOffersNoListOfOutstandingAmounts() throws IOException {
        UUID run = uploaded(t, "import-retail-export", exportMapping(), RetailImportIT.SAMPLE, exportFiles());
        try {
            as(
                    t,
                    () -> runs.recordPolicy(
                            run,
                            Policy.RECEIVABLE,
                            new PolicyDecision(
                                    Policy.OPEN_FROM_LIST,
                                    LocalDate.now(),
                                    "Question list item 1",
                                    operator,
                                    null,
                                    null)));
            throw new AssertionError("open_from_list was recorded");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("policy_option_unknown");
        }
    }

    @Test
    void anotherBusinessCannotReadOrRunTheDryRun() throws IOException {
        UUID run = reviewed(t, "import-retail-export", exportMapping(), RetailImportIT.SAMPLE, exportFiles());
        as(t, () -> runs.dryRun(run, operator));
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
        String stored = TestDatabase.owner()
                .sql("SELECT dry_run_report::text FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
        for (String value : List.of("Test Buyer 01", "+256700000001")) {
            assertThat(stored).as("the stored report names %s", value).doesNotContain(value);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The run.
    // ---------------------------------------------------------------------------------------------

    static Map<String, Object> exportMapping() {
        return RetailV1Template.exportMapping(ZoneId.of("Africa/Kampala"));
    }

    /** The export's files by the source of the export's mapping: each entity set's file of the same name. */
    static Map<String, String> exportFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        RetailV1Template.SETS.forEach(set -> files.put(set, set + ".jsonl"));
        return files;
    }

    /** A run in review with one batch per file, read with the named mapping, validated. */
    UUID uploaded(
            TestDatabase.Fixture f, String name, Map<String, Object> document, Path dir, Map<String, String> files)
            throws IOException {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path file = dir.resolve(e.getValue());
            if (Files.isRegularFile(file)) {
                bytes.put(e.getKey(), Files.readAllBytes(file));
            }
        }
        return as(f, () -> {
            UUID mapping = mappings.save(name, document, operator, null).id();
            UUID run = runs.open(operator).id();
            bytes.forEach((source, content) -> {
                ImportUploads.Batch batch = uploads.upload(
                        run,
                        new Upload(
                                files.get(source),
                                new ByteArrayInputStream(content),
                                content.length,
                                operator,
                                mappings.parseOptions(mapping, source)));
                assertThat(batch.status()).as(source).isEqualTo("parsed");
            });
            validation.validate(run);
            return run;
        });
    }

    /**
     * The run uploaded and reviewed as an operator would: a row with an open blocking issue, or one whose code repeats an
     * earlier row's ({@code CODE_DUPLICATE}, which the loader would skip), is left out; every other warning (a negative
     * balance for the first stock-take) is overridden with a note; and the receivable decision is recorded.
     */
    UUID reviewed(
            TestDatabase.Fixture f, String name, Map<String, Object> document, Path dir, Map<String, String> files)
            throws IOException {
        UUID run = uploaded(f, name, document, dir, files);
        settle(f, run);
        return run;
    }

    /** The review of {@link #reviewed} on a run already uploaded and validated. */
    void settle(TestDatabase.Fixture f, UUID run) {
        for (int pass = 0; pass < 500; pass++) {
            Issue next = as(f, () -> openIssue(run));
            if (next == null) {
                as(
                        f,
                        () -> runs.recordPolicy(
                                run,
                                Policy.RECEIVABLE,
                                new PolicyDecision(
                                        Policy.TREAT_AS_SETTLED,
                                        LocalDate.now(),
                                        "Question list item 1, signed",
                                        operator,
                                        null,
                                        null)));
                return;
            }
            String kind = "blocking".equals(next.severity()) || "CODE_DUPLICATE".equals(next.code())
                    ? "exclude_row"
                    : "override";
            as(
                    f,
                    () -> review.resolve(
                            next.id(),
                            new ImportReview.Resolution(
                                    kind, next.version(), null, null, null, "Fabricated review note"),
                            operator));
        }
        throw new AssertionError("the review did not settle");
    }

    /** An open blocking issue first, then an open warning; null when none is open. */
    Issue openIssue(UUID run) {
        Issue warning = null;
        for (ImportUploads.Batch b : uploads.batches(run)) {
            for (Issue i : validation.issues(b.id())) {
                if (!"open".equals(i.status())) {
                    continue;
                }
                if ("blocking".equals(i.severity())) {
                    return i;
                }
                if ("warning".equals(i.severity()) && warning == null) {
                    warning = i;
                }
            }
        }
        return warning;
    }

    /** From a dry run that predicts the preview to the commit: ready, the verified dump, maker and checker. */
    void committed(TestDatabase.Fixture f, UUID run) {
        DryRun dry = as(f, () -> runs.dryRun(run, operator));
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        as(f, () -> runs.transition(run, "ready"));
        as(f, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, f, runs, run, SHA);
        assertThat(as(f, () -> runs.commit(run))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);
    }

    ResponseEntity<JsonNode> signOff(TestDatabase.Fixture f, UUID run, ImportReconciliation.Report r) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", f.slug());
        h.add("X-Dev-User-Id", admin.toString());
        h.add("X-Dev-Permissions", ADMIN);
        h.add("X-Dev-Branch-Ids", "*");
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reconciliation_id", r.id());
        body.put("sha256", r.sha256());
        body.put(
                "sections",
                r.sections().stream().map(ImportReconciliation.Section::key).toList());
        body.put("name", "Fabricated Signer");
        body.put(
                "date",
                as(f, () -> clock.today(currentTenant.profile().timezone())).toString());
        return http.exchange(
                "/api/v1/imports/runs/" + run + "/sign-off",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                JsonNode.class);
    }

    // ---------------------------------------------------------------------------------------------
    // The tenant's state, as the books hold it: no ids, no times of writing.
    // ---------------------------------------------------------------------------------------------

    /**
     * What a business holds after an import, as sorted lines: its branches, categories, units, items with their prices,
     * suppliers, credit buyers, price history by source, every stock movement by kind, branch, item, quantity, cost and
     * business day, the stock balances, the sales, purchases and usage reports, and the journals per branch and account.
     */
    static List<String> state(TestDatabase.Fixture f) {
        List<String> out = new ArrayList<>();
        UUID tenant = f.tenantId();
        lines(out, "branch", "SELECT code FROM branches WHERE tenant_id = ?", tenant);
        lines(out, "category", "SELECT name FROM retail_categories WHERE tenant_id = ?", tenant);
        lines(out, "unit", "SELECT name FROM retail_units WHERE tenant_id = ?", tenant);
        lines(
                out,
                "product",
                "SELECT code || '|' || cost_minor || '|' || sell_minor || '|' || active FROM retail_products"
                        + " WHERE tenant_id = ?",
                tenant);
        lines(out, "supplier", "SELECT name FROM retail_suppliers WHERE tenant_id = ?", tenant);
        lines(out, "customer", "SELECT name FROM retail_customers WHERE tenant_id = ?", tenant);
        lines(
                out,
                "price_history",
                "SELECT h.source || '|' || p.code || '|' || count(*) FROM retail_price_history h"
                        + " JOIN retail_products p ON p.id = h.product_id WHERE h.tenant_id = ?"
                        + " GROUP BY h.source, p.code",
                tenant);
        lines(
                out,
                "movement",
                "SELECT m.kind || '|' || b.code || '|' || p.code || '|' || m.qty || '|' || m.unit_cost_minor || '|'"
                        + " || m.business_date || '|' || m.historical FROM retail_stock_movements m"
                        + " JOIN branches b ON b.id = m.branch_id JOIN retail_products p ON p.id = m.product_id"
                        + " WHERE m.tenant_id = ?",
                tenant);
        lines(
                out,
                "balance",
                "SELECT b.code || '|' || p.code || '|' || s.qty FROM retail_stock_balances s"
                        + " JOIN branches b ON b.id = s.branch_id JOIN retail_products p ON p.id = s.product_id"
                        + " WHERE s.tenant_id = ?",
                tenant);
        lines(
                out,
                "sale",
                "SELECT payment_method || '|' || historical || '|' || count(*) FROM retail_sales WHERE tenant_id = ?"
                        + " GROUP BY payment_method, historical",
                tenant);
        lines(
                out,
                "purchase",
                "SELECT historical || '|' || count(*) FROM retail_purchases WHERE tenant_id = ? GROUP BY historical",
                tenant);
        lines(
                out,
                "usage",
                "SELECT historical || '|' || count(*) FROM retail_usage_reports WHERE tenant_id = ? GROUP BY historical",
                tenant);
        lines(
                out,
                "journal",
                "SELECT b.code || '|' || a.system_key || '|' || sum(l.debit) || '|' || sum(l.credit)"
                        + " FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id"
                        + " JOIN gl_accounts a ON a.id = l.account_id JOIN branches b ON b.id = e.branch_id"
                        + " WHERE e.tenant_id = ? GROUP BY b.code, a.system_key",
                tenant);
        return out.stream().sorted().toList();
    }

    private static void lines(List<String> out, String kind, String sql, UUID tenant) {
        TestDatabase.owner()
                .sql(sql)
                .param(tenant)
                .query(String.class)
                .list()
                .forEach(line -> out.add(kind + "|" + line));
    }

    private static long owner(String sql, TestDatabase.Fixture f) {
        return TestDatabase.owner()
                .sql(sql)
                .param(f.tenantId())
                .query(Long.class)
                .single();
    }
}
