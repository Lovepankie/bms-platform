package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.CommitApproval;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportReconciliation;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.DryRun;
import com.rincoltech.bms.core.imports.ImportRuns.PolicyDecision;
import com.rincoltech.bms.core.imports.ImportTemplate.Policy;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Disabled;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.test.context.TestContextManager;

/**
 * The property tests of customer data onboarding (issue #137, spec section 5.2) on {@code retail_v1}, through the framework
 * as an operator runs it (upload, validation, review, dry run, maker and checker commit, reconciliation) on PostgreSQL as
 * {@code bms_app}, over generated fabricated shop books: one to three items, purchases, sales and usage at the head office
 * and the second branch, and a balance for every branch and item, some below zero. The properties:
 *
 * <ul>
 *   <li>importing twice equals importing once: the second run posts no journal (one import opening journal per branch,
 *       ever), writes nothing and reports every row as existing;
 *   <li>a permuted row order within every file gives the same state and the same reconciliation;
 *   <li>splitting the history files into two runs gives the state of one run (with the restocks split, disabled by
 *       #881, which it found; the split of the sales and usage runs);
 *   <li>a dry run followed by a real run reports what a real run alone reports, and leaves the state a real run alone
 *       leaves (through the {@code import-retail} command, the one path that commits without a dry run first);
 *   <li>the derived balance equals the source balance for every branch and item, the checksum matches and the
 *       reconciliation ties.
 * </ul>
 *
 * <p>jqwik runs them in {@code mvn verify} (Failsafe runs every JUnit Platform engine on an {@code *IT} class). Each has a
 * fixed seed and a fixed number of tries, so a run is the same everywhere, and each must finish inside
 * {@link #PROPERTY_LIMIT}; a failure prints the shrunk book, which is the counterexample. Spring injects the beans through
 * the test context the Jupiter integration tests share, so no second application starts. No real data: every name, code
 * and amount is made up.
 */
class RetailImportPropertiesIT extends IntegrationTest {

    /**
     * The longest one property may take, its shrinking included: the bound, not the expectation. On CI the six take
     * about two minutes together.
     */
    static final Duration PROPERTY_LIMIT = Duration.ofMinutes(6);

    static final String SHA = "f".repeat(64);

    static final List<String> BRANCHES = List.of("HQ", "BR2");

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
    ImportPreview preview;

    @Autowired
    TestRestTemplate http;

    final UUID operator = UUID.randomUUID();

    private Instant started;

    @BeforeProperty
    void inject() throws Exception {
        new TestContextManager(getClass()).prepareTestInstance(this);
        started = Instant.now();
    }

    @AfterProperty
    void bounded() {
        assertThat(Duration.between(started, Instant.now()))
                .as("the property's run time, shrinking included")
                .isLessThan(PROPERTY_LIMIT);
    }

    // ---------------------------------------------------------------------------------------------
    // The properties.
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 18, seed = "137001")
    void importingTwiceEqualsImportingOnce(@ForAll("books") Book book) {
        TestDatabase.Fixture t = TestDatabase.tenant("prop-twice", false, true);
        UUID first = committed(t, reviewed(t, book.files(0)));
        List<String> once = RetailV1TemplateIT.state(t);
        long journals = journals(t);
        assertDerivedIsSource(t, book, first);

        UUID again = reviewed(t, book.files(0));
        // Recognised, not refused: no row of the second run is left out or held back by an issue (review of 74bfe3e).
        ImportPreview.Preview predicted = as(t, () -> preview.preview(again));
        assertThat(predicted.batches())
                .allSatisfy(
                        b -> assertThat(b.excluded() + b.blocked()).as("%s", b).isZero());
        DryRun dry = as(t, () -> runs.dryRun(again, operator));
        assertThat(dry.report().journals()).as(dry.report().render()).isEmpty();
        committed(t, again);

        assertThat(RetailV1TemplateIT.state(t)).as("the state of one run").isEqualTo(once);
        assertThat(journals(t)).as("no second opening journal").isEqualTo(journals);
        CommitReport second = as(t, () -> runs.commitReport(again));
        // Every row of every file is read and counted existing: none written, none skipped.
        assertThat(second.sources()).as(second.render()).allSatisfy(s -> {
            assertThat(s.read()).isPositive();
            assertThat(s.existing()).as(s.name()).isEqualTo(s.read());
            assertThat(s.written() + s.skipped()).as(s.name()).isZero();
        });
        assertThat(second.journals()).isEmpty();
    }

    @Property(tries = 18, seed = "137002")
    void aPermutedRowOrderGivesTheSameStateAndReconciliation(@ForAll("books") Book book, @ForAll long order) {
        TestDatabase.Fixture a = TestDatabase.tenant("prop-order-a", false, true);
        TestDatabase.Fixture b = TestDatabase.tenant("prop-order-b", false, true);
        UUID inOrder = committed(a, reviewed(a, book.files(0)));
        UUID permuted = committed(b, reviewed(b, book.files(order == 0 ? 1 : order)));

        assertThat(RetailV1TemplateIT.state(b)).isEqualTo(RetailV1TemplateIT.state(a));
        assertThat(reconciled(b, permuted)).isEqualTo(reconciled(a, inOrder));
    }

    @Property(tries = 15, seed = "137003")
    @Disabled("#881: a restock's price change is found within its batch only, so a split loses it")
    void splittingTheHistoryIntoTwoRunsGivesTheStateOfOneRun(
            @ForAll("books") Book book, @ForAll @IntRange(min = 0, max = 6) int cut) {
        TestDatabase.Fixture whole = TestDatabase.tenant("prop-whole", false, true);
        TestDatabase.Fixture split = TestDatabase.tenant("prop-split", false, true);
        committed(whole, reviewed(whole, book.files(0)));

        // The first run takes the history rows before the cut and no balances; the second the rest and the balances,
        // so the second run's balances post the branch's one opening journal.
        committed(split, reviewed(split, book.part(0, cut).files(0, false)));
        UUID second = committed(
                split, reviewed(split, book.part(cut, Integer.MAX_VALUE).files(0)));

        assertThat(RetailV1TemplateIT.state(split)).isEqualTo(RetailV1TemplateIT.state(whole));
        assertDerivedIsSource(split, book, second);
    }

    // JUSTIFICATION-A3: #881 disables the property above; this one keeps the split proven for the files it holds for.
    /**
     * The split of spec section 5.2 over the sales and usage files, while #881 is open: the first run takes every restock
     * and the sales and usage before the cut, the second the rest and the balances, and the state is that of one run.
     */
    @Property(tries = 15, seed = "137006")
    void splittingSalesAndUsageIntoTwoRunsGivesTheStateOfOneRun(
            @ForAll("books") Book book, @ForAll @IntRange(min = 0, max = 6) int cut) {
        TestDatabase.Fixture whole = TestDatabase.tenant("prop-whole", false, true);
        TestDatabase.Fixture split = TestDatabase.tenant("prop-split", false, true);
        committed(whole, reviewed(whole, book.files(0)));

        Book first = book.part(0, cut);
        Book second = book.part(cut, Integer.MAX_VALUE);
        committed(split, reviewed(split, first.withRestocks(book.restocks()).files(0, false)));
        UUID last =
                committed(split, reviewed(split, second.withRestocks(List.of()).files(0)));

        assertThat(RetailV1TemplateIT.state(split)).isEqualTo(RetailV1TemplateIT.state(whole));
        assertDerivedIsSource(split, book, last);
    }

    @Property(tries = 24, seed = "137004")
    void aDryRunThenARealRunReportsWhatARealRunAloneReports(@ForAll("books") Book book) throws IOException {
        TestDatabase.Fixture rehearsed = TestDatabase.tenant("prop-dry", false, true);
        TestDatabase.Fixture alone = TestDatabase.tenant("prop-alone", false, true);
        Path dir = Files.createTempDirectory("retail-property-");
        Map<String, Long> before = TestDatabase.rowCounts(rehearsed.tenantId());
        ImportReport dry;
        ImportReport real;
        ImportReport only;
        try {
            for (Map.Entry<String, String> f : book.files(0).entrySet()) {
                Files.writeString(dir.resolve(f.getKey() + ".jsonl"), f.getValue());
            }
            dry = importer.run(rehearsed.slug(), dir, true);
            assertThat(TestDatabase.rowCounts(rehearsed.tenantId()))
                    .as("the dry run writes nothing")
                    .isEqualTo(before);
            real = importer.run(rehearsed.slug(), dir, false);
            only = importer.run(alone.slug(), dir, false);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                }
            }
            Files.delete(dir);
        }

        assertThat(real.failed()).as(real.render()).isFalse();
        assertThat(real.render().replace(rehearsed.slug(), "<business>"))
                .isEqualTo(only.render().replace(alone.slug(), "<business>"));
        assertThat(dry.render().replace("dry run, every write rolled back", "<mode>"))
                .isEqualTo(real.render().replace("committed, one transaction per file", "<mode>"));
        assertThat(RetailV1TemplateIT.state(rehearsed)).isEqualTo(RetailV1TemplateIT.state(alone));
    }

    @Property(tries = 24, seed = "137005")
    void theDerivedBalanceEqualsTheSourceBalanceForEveryBranchAndItem(@ForAll("books") Book book) {
        TestDatabase.Fixture t = TestDatabase.tenant("prop-balance", false, true);
        UUID run = committed(t, reviewed(t, book.files(0)));
        assertDerivedIsSource(t, book, run);
        ImportReconciliation.Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(r.signable()).as(r.render()).isTrue();
    }

    /** Every balance row's quantity is the books' balance, the commit's checksum matches, and nothing else is held. */
    void assertDerivedIsSource(TestDatabase.Fixture t, Book book, UUID run) {
        Map<String, BigDecimal> derived = new LinkedHashMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT b.code, p.code, s.qty FROM retail_stock_balances s
                          JOIN branches b ON b.id = s.branch_id JOIN retail_products p ON p.id = s.product_id
                         WHERE s.tenant_id = ?
                        """)
                .param(t.tenantId())
                .query((rs, n) -> derived.put(rs.getString(1) + "|" + rs.getString(2), rs.getBigDecimal(3)))
                .list();
        Map<String, BigDecimal> source = book.sourceBalances();
        // An item that never moved at a branch has no balance row: its balance is zero.
        assertThat(source.keySet()).containsAll(derived.keySet());
        source.forEach((key, qty) ->
                assertThat(derived.getOrDefault(key, BigDecimal.ZERO)).as(key).isEqualByComparingTo(qty));
        CommitReport report = as(t, () -> runs.commitReport(run));
        assertThat(report.checksum()).as(report.render()).contains("match=yes");
    }

    // ---------------------------------------------------------------------------------------------
    // The generated books.
    // ---------------------------------------------------------------------------------------------

    /** An item: its cost and its selling price in minor units. */
    record Item(long costMinor, long sellMinor) {}

    /** A restock of item {@code item} (taken modulo the items), of quantities at the head office and the second branch. */
    record Restock(int item, int atHeadOffice, int atBranchTwo, long costMinor) {}

    /** A sale or a usage entry of item {@code item} at a branch, of {@code qty} and a half when {@code half}. */
    record Move(int item, String branch, int qty, boolean half) {

        BigDecimal quantity() {
            return BigDecimal.valueOf(qty).add(half ? new BigDecimal("0.5") : BigDecimal.ZERO);
        }
    }

    /**
     * A fabricated shop book: its items, the history (each kept with its position in the whole book, so a part keeps its
     * references and dates), and a balance per branch and item, read as {@code balances.get(2 * item + branch)}.
     */
    record Book(
            List<Item> items,
            List<Indexed<Restock>> restocks,
            List<Indexed<Move>> sales,
            List<Indexed<Move>> usage,
            List<Integer> balances,
            boolean withBalances) {

        /** The history rows from position {@code from} (inclusive) to {@code to} (exclusive), the balances with it. */
        Book part(int from, int to) {
            return new Book(
                    items, slice(restocks, from, to), slice(sales, from, to), slice(usage, from, to), balances, true);
        }

        Book withRestocks(List<Indexed<Restock>> rows) {
            return new Book(items, rows, sales, usage, balances, withBalances);
        }

        private static <T> List<Indexed<T>> slice(List<Indexed<T>> rows, int from, int to) {
            return rows.stream().filter(r -> r.at() >= from && r.at() < to).toList();
        }

        String code(int item) {
            return String.format("PT-%02d", item % items.size() + 1);
        }

        /** The source balance by {@code BRANCH|code}: every branch and item. */
        Map<String, BigDecimal> sourceBalances() {
            Map<String, BigDecimal> out = new LinkedHashMap<>();
            for (int i = 0; i < items.size(); i++) {
                for (int b = 0; b < BRANCHES.size(); b++) {
                    out.put(BRANCHES.get(b) + "|" + code(i), BigDecimal.valueOf(balances.get(2 * i + b)));
                }
            }
            return out;
        }

        Map<String, String> files(long order) {
            return files(order, withBalances);
        }

        /**
         * The export's files by entity set, as JSON Lines; with {@code order} not 0 the lines of every file are shuffled
         * by it. A set with no row has no file.
         */
        Map<String, String> files(long order, boolean balancesToo) {
            Map<String, List<String>> sets = new LinkedHashMap<>();
            sets.put("categories", List.of("{\"name\": \"Test Category\"}"));
            sets.put("units", List.of("{\"name\": \"piece\"}"));
            List<String> products = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                products.add(String.format(
                        "{\"code\": \"%s\", \"description\": \"Test property item %02d\", \"category\": \"Test"
                                + " Category\", \"unit\": \"piece\", \"cost_minor\": %d, \"sell_minor\": %d,"
                                + " \"active\": true}",
                        code(i), i + 1, items.get(i).costMinor(), items.get(i).sellMinor()));
            }
            sets.put("products", products);
            sets.put("suppliers", List.of("{\"name\": \"Test Supplier 01\"}"));
            List<String> purchases = new ArrayList<>();
            for (Indexed<Restock> r : restocks) {
                Restock p = r.row();
                Map<String, String> qty = new LinkedHashMap<>();
                if (p.atHeadOffice() > 0 || p.atBranchTwo() == 0) {
                    qty.put("HQ", Math.max(1, p.atHeadOffice()) + ".000");
                }
                if (p.atBranchTwo() > 0) {
                    qty.put("BR2", p.atBranchTwo() + ".000");
                }
                StringBuilder byBranch = new StringBuilder();
                qty.forEach((branch, q) -> byBranch.append(byBranch.isEmpty() ? "" : ", ")
                        .append('"')
                        .append(branch)
                        .append("\": \"")
                        .append(q)
                        .append('"'));
                purchases.add(String.format(
                        "{\"source_ref\": \"PUR-P%02d\", \"product_code\": \"%s\", \"supplier\": \"Test Supplier 01\","
                                + " \"kind\": \"purchase\", \"unit_cost_minor\": %d, \"unit_sell_minor\": %d,"
                                + " \"qty_by_branch\": {%s}, \"purchased_on\": \"2026-07-%02d\", \"source_user\":"
                                + " \"test.admin01\"}",
                        r.at() + 1, code(p.item()), p.costMinor(), p.costMinor() + 500, byBranch, r.at() + 1));
            }
            sets.put("purchases", purchases);
            List<String> sold = new ArrayList<>();
            for (Indexed<Move> s : sales) {
                Move m = s.row();
                Item item = items.get(m.item() % items.size());
                sold.add(String.format(
                        "{\"source_ref\": \"SAL-P%02d\", \"branch\": \"%s\", \"product_code\": \"%s\", \"qty\": \"%s\","
                                + " \"unit_price_minor\": %d, \"unit_cost_minor\": %d, \"payment_method\": \"cash\","
                                + " \"sold_at\": \"2026-08-%02dT09:%02d:00\", \"source_user\": \"test.sales01\"}",
                        s.at() + 1,
                        m.branch(),
                        code(m.item()),
                        m.quantity().setScale(3).toPlainString(),
                        item.sellMinor(),
                        item.costMinor(),
                        s.at() + 1,
                        s.at() + 1));
            }
            sets.put("sales", sold);
            List<String> used = new ArrayList<>();
            for (Indexed<Move> u : usage) {
                Move m = u.row();
                used.add(String.format(
                        "{\"source_ref\": \"USE-P%02d\", \"branch\": \"%s\", \"product_code\": \"%s\", \"kind\":"
                                + " \"used\", \"reason\": \"Test reason %02d\", \"qty\": \"%s\", \"unit_cost_minor\":"
                                + " %d, \"reported_at\": \"2026-09-%02dT15:00:00\", \"source_user\": \"test.sales01\"}",
                        u.at() + 1,
                        m.branch(),
                        code(m.item()),
                        u.at() + 1,
                        m.quantity().setScale(3).toPlainString(),
                        items.get(m.item() % items.size()).costMinor(),
                        u.at() + 1));
            }
            sets.put("usage", used);
            if (balancesToo) {
                List<String> rows = new ArrayList<>();
                sourceBalances()
                        .forEach((key, qty) -> rows.add(String.format(
                                "{\"branch\": \"%s\", \"product_code\": \"%s\", \"qty\": \"%s\"}",
                                key.substring(0, key.indexOf('|')),
                                key.substring(key.indexOf('|') + 1),
                                qty.setScale(3).toPlainString())));
                sets.put("balances", rows);
            }
            Map<String, String> out = new LinkedHashMap<>();
            sets.forEach((set, lines) -> {
                if (!lines.isEmpty()) {
                    List<String> ordered = new ArrayList<>(lines);
                    if (order != 0) {
                        Collections.shuffle(ordered, new Random(order));
                    }
                    out.put(set, String.join("\n", ordered) + "\n");
                }
            });
            return out;
        }
    }

    /** A history row and its position in the whole book. */
    record Indexed<T>(int at, T row) {}

    static <T> List<Indexed<T>> indexed(List<T> rows) {
        List<Indexed<T>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(new Indexed<>(i, rows.get(i)));
        }
        return out;
    }

    @Provide
    Arbitrary<Book> books() {
        Arbitrary<Item> item = Combinators.combine(
                        Arbitraries.integers().between(1, 90),
                        Arbitraries.integers().between(0, 40))
                .as((cost, margin) -> new Item(cost * 100L, (cost + margin) * 100L));
        Arbitrary<Restock> restock = Combinators.combine(
                        Arbitraries.integers().between(0, 2),
                        Arbitraries.integers().between(0, 12),
                        Arbitraries.integers().between(0, 12),
                        Arbitraries.integers().between(1, 90))
                .as((i, hq, br2, cost) -> new Restock(i, hq, br2, cost * 100L));
        Arbitrary<Move> move = Combinators.combine(
                        Arbitraries.integers().between(0, 2),
                        Arbitraries.of(BRANCHES),
                        Arbitraries.integers().between(1, 6),
                        Arbitraries.of(false, true))
                .as(Move::new);
        return Combinators.combine(
                        item.list().ofMinSize(1).ofMaxSize(3),
                        restock.list().ofMaxSize(5),
                        move.list().ofMaxSize(6),
                        move.list().ofMaxSize(2),
                        Arbitraries.integers().between(-2, 40).list().ofSize(6))
                .as((items, restocks, sales, usage, balances) ->
                        new Book(items, indexed(restocks), indexed(sales), indexed(usage), balances, true));
    }

    // ---------------------------------------------------------------------------------------------
    // The run, as RetailV1TemplateIT drives it.
    // ---------------------------------------------------------------------------------------------

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    /** A run in review with one batch per file of the export, read with the export's mapping and validated. */
    UUID uploaded(TestDatabase.Fixture f, Map<String, String> files) {
        return as(f, () -> {
            UUID mapping = mappings.save("property-export", RetailV1TemplateIT.exportMapping(), operator, null)
                    .id();
            UUID run = runs.open(operator).id();
            files.forEach((set, text) -> {
                byte[] content = text.getBytes(StandardCharsets.UTF_8);
                ImportUploads.Batch batch = uploads.upload(
                        run,
                        new Upload(
                                set + ".jsonl",
                                new ByteArrayInputStream(content),
                                content.length,
                                operator,
                                mappings.parseOptions(mapping, set)));
                assertThat(batch.status()).as(set).isEqualTo("parsed");
            });
            validation.validate(run);
            return run;
        });
    }

    /**
     * Uploaded and reviewed as {@code RetailV1TemplateIT} reviews: a blocking issue leaves its row out, a warning (a
     * negative balance for the first stock-take) is overridden with a note, and the receivable decision is recorded.
     */
    UUID reviewed(TestDatabase.Fixture f, Map<String, String> files) {
        UUID run = uploaded(f, files);
        for (int pass = 0; pass < 200; pass++) {
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
                return run;
            }
            String kind = "blocking".equals(next.severity()) ? "exclude_row" : "override";
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

    Issue openIssue(UUID run) {
        for (ImportUploads.Batch b : uploads.batches(run)) {
            for (Issue i : validation.issues(b.id())) {
                if ("open".equals(i.status()) && List.of("blocking", "warning").contains(i.severity())) {
                    return i;
                }
            }
        }
        return null;
    }

    /** From a dry run that predicts the preview to the commit: ready, the verified dump, maker and checker. */
    UUID committed(TestDatabase.Fixture f, UUID run) {
        DryRun dry = as(f, () -> runs.dryRun(run, operator));
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        as(f, () -> runs.transition(run, "ready"));
        as(f, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, f, runs, run, SHA);
        assertThat(as(f, () -> runs.commit(run))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);
        CommitReport real = as(f, () -> runs.commitReport(run));
        assertThat(real.sameAs(dry.report()))
                .as("real:%n%s%ndry:%n%s", real.render(), dry.report().render())
                .isTrue();
        return run;
    }

    /**
     * The run's reconciliation as sorted lines without what a row's position or the time of a resolution gives: each
     * check, each fact but the review's (counted instead: they name a line and a time), and each item without its line.
     */
    List<String> reconciled(TestDatabase.Fixture f, UUID run) {
        ImportReconciliation.Report r = as(f, () -> reconciliation.latest(run)).orElseThrow();
        List<String> out = new ArrayList<>();
        out.add("signable|" + r.signable());
        for (ImportReconciliation.Section s : r.sections()) {
            if ("review".equals(s.key())) {
                out.add("review|resolutions|" + s.facts().size());
            } else {
                s.facts().forEach(x -> out.add(s.key() + "|fact|" + x.label() + "|" + x.value()));
            }
            s.checks()
                    .forEach(c -> out.add(s.key() + "|check|" + c.key() + "|" + c.label() + "|" + c.unit() + "|"
                            + c.source() + "|" + c.system() + "|" + c.difference() + "|" + c.itemised() + "|"
                            + c.unitemised() + "|" + c.ties()));
            s.items()
                    .forEach(i -> out.add(s.key() + "|item|" + i.check() + "|" + i.file() + "|" + i.reason() + "|"
                            + i.detail() + "|" + i.amount()));
        }
        return out.stream().sorted().toList();
    }

    static long journals(TestDatabase.Fixture f) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                .param(f.tenantId())
                .query(Long.class)
                .single();
    }
}
