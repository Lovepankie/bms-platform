package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.kernel.Money;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The expand step of issue #135 (chapter 6 section 6.9): references the {@code import-retail} command wrote in
 * {@code retail_import_refs} before V145, copied into {@code import_refs} by the migration's backfill, are recognised by a
 * re-run of the command after it: the rows they name are existing, nothing is written twice. The old command's rows are
 * stood in for by rows written into the old table as it wrote them, then the backfill the migration ran, run again for
 * them. Fabricated codes only.
 */
class RetailImportRefsBackfillIT extends IntegrationTest {

    @Autowired
    RetailImporter importer;

    @Autowired
    TestRestTemplate http;

    @Autowired
    LedgerPosting ledger;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void aReRunAfterTheMigrationRecognisesRowsTheOldCommandImported(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-refs", false, true);
        new RetailTestSupport(http, t).product("TB-1", 100, 150);
        Files.writeString(dir.resolve("sales.jsonl"), """
                {"source_ref": "S-OLD-1", "branch": "HQ", "product_code": "TB-1", "qty": "2", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01T10:00:00"}
                {"source_ref": "S-OLD-2", "branch": "HQ", "product_code": "TB-1", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-02T10:00:00"}
                {"source_ref": "S-NEW-3", "branch": "HQ", "product_code": "TB-1", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-03T10:00:00"}
                """);
        JdbcClient owner = TestDatabase.owner();
        // Two sales as the command before V145 registered them: in retail_import_refs alone.
        for (String ref : new String[] {"S-OLD-1", "S-OLD-2"}) {
            owner.sql("INSERT INTO retail_import_refs (tenant_id, source_file, source_ref, target_type, target_id,"
                            + " source_user) VALUES (?, 'sales', ?, 'retail.sale', ?, 'test.sales01')")
                    .params(t.tenantId(), ref, UUID.randomUUID())
                    .update();
        }

        Integer copied = owner.sql("SELECT bms_backfill_retail_import_refs(5000)")
                .query(Integer.class)
                .single();

        assertThat(copied).isGreaterThanOrEqualTo(2);
        assertThat(owner.sql("SELECT source_ref FROM import_refs WHERE tenant_id = ? ORDER BY source_ref")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("sales.jsonl|-|ref:S-OLD-1#by-key", "sales.jsonl|-|ref:S-OLD-2#by-key");
        assertThat(owner.sql("SELECT origin || ' ' || status FROM import_runs WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("retail_import_refs committed");

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(report.counts("sales.jsonl")).containsExactly(3, 1, 2, 0);
        assertThat(owner.sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("only the new sale is written")
                .isEqualTo(1L);
        // The new sale is registered in both tables, by its key alone, as the command knows its rows.
        assertThat(owner.sql("SELECT count(*) FROM import_refs WHERE tenant_id = ? AND target_type = 'retail.sale'"
                                + " AND source_ref = 'sales.jsonl|-|ref:S-NEW-3#by-key'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(owner.sql("SELECT count(*) FROM retail_import_refs WHERE tenant_id = ? AND source_ref = 'S-NEW-3'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        // The command's run is a run of the framework, committed, its batches recording the export's mapping.
        assertThat(owner.sql("SELECT origin || ' ' || status FROM import_runs WHERE tenant_id = ? ORDER BY origin")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("import_retail committed", "retail_import_refs committed");
        assertThat(owner.sql("""
                                SELECT b.mapping_source FROM import_batches b JOIN import_runs r ON r.id = b.run_id
                                 WHERE r.tenant_id = ? AND r.origin = 'import_retail' AND b.source_filename = 'sales.jsonl'
                                """).param(t.tenantId()).query(String.class).single())
                .isEqualTo("sales");

        // And again: nothing new.
        ImportReport again = importer.run(t.slug(), dir, false);
        assertThat(again.counts("sales.jsonl")).containsExactly(3, 0, 3, 0);
        assertThat(owner.sql("SELECT bms_backfill_retail_import_refs(5000)")
                        .query(Integer.class)
                        .single())
                .as("the dual-written rows are already in import_refs")
                .isZero();
    }

    // JUSTIFICATION-A3: the review of 97cb1be asks a test of findings 1 and 2; each is a test of its own.
    /**
     * (d) of the Lead's rule on the review of 4af7a39 (and the review of 97cb1be, finding 1): a first run writes the
     * purchases and stops before any balance (no balances file), as a run that failed before journalling does; the re-run
     * sees every purchase as existing and writes only the legacy balances: TB-1 has 10 from history and 7 in the source
     * (a legacy movement of -3), TB-2 has 4 and 4 (none). Its one journal values everything imported, 7 x 100 + 4 x 200,
     * and a third run posts no second one.
     */
    @Test
    void theOpeningJournalCountsTheHistoryAnEarlierRunWrote(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-open", false, true);
        RetailTestSupport retail = new RetailTestSupport(http, t);
        retail.product("TB-1", 100, 150);
        retail.product("TB-2", 200, 300);
        Files.writeString(dir.resolve("purchases.jsonl"), """
                {"source_ref": "P-1", "product_code": "TB-1", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 100, "unit_sell_minor": 150, "qty_by_branch": {"HQ": "10"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                {"source_ref": "P-2", "product_code": "TB-2", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 200, "unit_sell_minor": 300, "qty_by_branch": {"HQ": "4"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                """);

        ImportReport first = importer.run(t.slug(), dir, false);

        assertThat(first.failed()).as(first.render()).isFalse();
        assertThat(journalled(t)).as("no balances, no journal").isZero();

        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "7"}
                {"branch": "HQ", "product_code": "TB-2", "qty": "4"}
                """);
        ImportReport second = importer.run(t.slug(), dir, false);

        assertThat(second.failed()).as(second.render()).isFalse();
        assertThat(second.counts("purchases.jsonl")).containsExactly(2, 0, 2, 0);
        assertThat(journalled(t)).as(second.render()).isEqualTo(7 * 100 + 4 * 200);

        ImportReport third = importer.run(t.slug(), dir, false);

        assertThat(third.failed()).as(third.render()).isFalse();
        assertThat(journalled(t)).isEqualTo(7 * 100 + 4 * 200);
        assertThat(third.render()).contains("already posted, not posted again");
    }

    /**
     * Review of 97cb1be, finding 2: a command run that wrote a batch within its lease is live, and a second command is
     * refused rather than cancelling it; one left committing past the lease (a killed process) is cancelled.
     */
    @Test
    void aLiveCommandRunIsNeverCancelledByAnother(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-live", false, true);
        new RetailTestSupport(http, t).product("TB-1", 100, 150);
        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "2"}
                """);
        JdbcClient owner = TestDatabase.owner();
        UUID live = UUID.randomUUID();
        owner.sql("INSERT INTO import_runs (id, tenant_id, created_by, status, committing_at, origin)"
                        + " VALUES (?, ?, ?, 'committing', now(), 'import_retail')")
                .params(live, t.tenantId(), new UUID(0, 0))
                .update();

        assertThatThrownBy(() -> importer.run(t.slug(), dir, false)).hasMessageContaining("Another import");
        assertThat(status(live)).isEqualTo("committing");

        owner.sql("UPDATE import_runs SET updated_at = now() - interval '1 hour' WHERE id = ?")
                .param(live)
                .update();
        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(status(live)).as("left by a killed process").isEqualTo("cancelled");
        // Re-review of 0bb283a, must-fix 1: the command's balance row is keyed under its own tab, never the key a
        // screens upload of balances.jsonl derives, so a corrected row uploaded later is not taken for it.
        assertThat(owner.sql("SELECT source_ref FROM import_refs WHERE tenant_id = ?"
                                + " AND target_type = 'retail.stock_movement'")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("balances.jsonl|import-retail|line:1#by-key");
    }

    // JUSTIFICATION-A3: the review of bf87e45 asks a test of must-fix 1 and of the later-run paths it found untested.
    /**
     * Review of bf87e45, must-fix 1: the first run writes TB-1's legacy balance to 0 (its 4 purchased are absent in the
     * source) and journals nothing, so HQ is not opened. A second run sends TB-1 at 5: its legacy balance is already
     * written, so it writes nothing for it, and the opening values the books' 0, not the file's 5. A third run, after HQ
     * opened, takes the other two paths: TB-2, which the books hold with no legacy balance, differs and is listed, not
     * written; TB-3, new at the branch, gets its legacy balance and is listed as not in the opening.
     */
    @Test
    void theFirstJournalValuesWhatTheBooksHoldAndALaterRunListsItsDifferences(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-books", false, true);
        RetailTestSupport retail = new RetailTestSupport(http, t);
        retail.product("TB-1", 100, 150);
        retail.product("TB-2", 200, 300);
        retail.product("TB-3", 300, 450);
        Files.writeString(dir.resolve("purchases.jsonl"), """
                {"source_ref": "P-1", "product_code": "TB-1", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 100, "unit_sell_minor": 150, "qty_by_branch": {"HQ": "4"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                """);
        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "0"}
                """);
        ImportReport first = importer.run(t.slug(), dir, false);
        assertThat(first.failed()).as(first.render()).isFalse();
        assertThat(journalled(t)).as("nothing above zero").isZero();

        Files.writeString(dir.resolve("purchases.jsonl"), """
                {"source_ref": "P-1", "product_code": "TB-1", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 100, "unit_sell_minor": 150, "qty_by_branch": {"HQ": "4"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                {"source_ref": "P-2", "product_code": "TB-2", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 200, "unit_sell_minor": 300, "qty_by_branch": {"HQ": "2"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                """);
        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "5"}
                {"branch": "HQ", "product_code": "TB-2", "qty": "2"}
                """);
        ImportReport second = importer.run(t.slug(), dir, false);

        assertThat(second.failed()).as(second.render()).isFalse();
        assertThat(journalled(t)).as("TB-1 at the books' 0, TB-2 at 2 x 200").isEqualTo(2 * 200);
        assertThat(second.anomalies()).anyMatch(a -> a.contains("the opening values the balance"));

        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "0"}
                {"branch": "HQ", "product_code": "TB-2", "qty": "1"}
                {"branch": "HQ", "product_code": "TB-3", "qty": "3"}
                """);
        ImportReport third = importer.run(t.slug(), dir, false);

        assertThat(third.failed()).as(third.render()).isFalse();
        assertThat(journalled(t)).as("no second journal").isEqualTo(2 * 200);
        assertThat(third.anomalies())
                .anyMatch(a -> a.contains("product TB-2: the source says 1.000 and the books hold 2.000;"
                        + " differs from the opening; correct it with a stock-take"))
                .anyMatch(a -> a.contains("product TB-3 is new at the branch"));
        assertThat(TestDatabase.owner()
                        .sql("SELECT p.code || ' ' || s.qty::text FROM retail_stock_balances s"
                                + " JOIN retail_products p ON p.id = s.product_id WHERE s.tenant_id = ?"
                                + " AND s.branch_id = ? ORDER BY p.code")
                        .params(t.tenantId(), t.headOffice())
                        .query(String.class)
                        .list())
                .as("TB-2 is left as the books hold it, TB-3 gets its balance")
                .containsExactly("TB-1 0.000", "TB-2 2.000", "TB-3 3.000");
    }

    // JUSTIFICATION-A3: issue #876 (nice-to-have 5) asks this test of an old command's journal after the backfill.
    /**
     * Issue #876, nice-to-have 5: the opening journal of HQ the command posted before V145, registered in
     * {@code retail_import_refs} alone ({@code opening}, the branch's code), is copied by the backfill; a run of the command
     * after it finds HQ opened, reports "already posted" and posts no second journal.
     */
    @Test
    void anOpeningJournalTheOldCommandPostedIsAlreadyPostedAfterTheBackfill(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-old-open", false, true);
        new RetailTestSupport(http, t).product("TB-1", 100, 150);
        UUID entry = oldCommandOpening(t, 500);
        JdbcClient owner = TestDatabase.owner();
        owner.sql("INSERT INTO retail_import_refs (tenant_id, source_file, source_ref, target_type, target_id)"
                        + " VALUES (?, 'opening', 'HQ', 'core.journal_entry', ?)")
                .params(t.tenantId(), entry)
                .update();

        assertThat(owner.sql("SELECT bms_backfill_retail_import_refs(5000)")
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(1);
        assertThat(owner.sql("SELECT source_key FROM import_refs WHERE tenant_id = ? AND target_id = ?")
                        .params(t.tenantId(), entry)
                        .query(String.class)
                        .single())
                .isEqualTo("opening|inventory|legacy:branch:" + t.headOffice());

        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "7"}
                """);
        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(report.render()).contains("already posted, not posted again");
        assertThat(owner.sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_type = ?")
                        .params(t.tenantId(), RetailV1Template.OPENING_TYPE)
                        .query(Long.class)
                        .single())
                .as("no second opening journal")
                .isEqualTo(1L);
        assertThat(journalled(t)).isEqualTo(500L);
    }

    // JUSTIFICATION-A3: the test of issue #876 (nice-to-have 5) needs a journal as the old command posted it.
    /** HQ's opening stock journal as the old command posted it: inventory against opening balance equity. */
    private UUID oldCommandOpening(TestDatabase.Fixture t, long amountMinor) {
        EntryRequest opening = new EntryRequest(
                t.headOffice(),
                LocalDate.of(2026, 9, 1),
                "IMPORT-OPENING-HQ",
                null,
                "retail",
                RetailV1Template.OPENING_TYPE,
                UUID.randomUUID(),
                null,
                List.of(
                        Line.debit(t.account("inventory"), Money.of(amountMinor, "UGX")),
                        Line.credit(t.account("opening_balance_equity"), Money.of(amountMinor, "UGX"))));
        return TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactions)
                        .execute(s -> ledger.post(opening).entryId()));
    }

    /**
     * TB-2 has history and no balance row, so the one journal values TB-1 alone; the identical export run again posts no
     * second journal.
     */
    @Test
    void aHistoryOnlyItemDoesNotStopAnIdenticalReRun(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-hist", false, true);
        RetailTestSupport retail = new RetailTestSupport(http, t);
        retail.product("TB-1", 100, 150);
        retail.product("TB-2", 200, 300);
        Files.writeString(dir.resolve("purchases.jsonl"), """
                {"source_ref": "P-1", "product_code": "TB-1", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 100, "unit_sell_minor": 150, "qty_by_branch": {"HQ": "10"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                {"source_ref": "P-2", "product_code": "TB-2", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 200, "unit_sell_minor": 300, "qty_by_branch": {"HQ": "4"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                """);
        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "7"}
                """);

        ImportReport first = importer.run(t.slug(), dir, false);
        assertThat(first.failed()).as(first.render()).isFalse();
        assertThat(journalled(t)).isEqualTo(7 * 100);

        ImportReport again = importer.run(t.slug(), dir, false);
        assertThat(again.failed()).as(again.render()).isFalse();
        assertThat(journalled(t)).isEqualTo(7 * 100);
    }

    // JUSTIFICATION-A3: the Lead's rule on the review of 4af7a39 asks test (c), a real failure at a cash file.
    /**
     * (c) of the Lead's rule on the review of 4af7a39: the first run fails at a cash file after balances.jsonl committed (a
     * fabricated refusal of the daily savings, for this business only); its run ends stopped. The re-run finishes the cash
     * files, posts no second opening journal, and its run commits.
     */
    @Test
    void aRunThatFailsAtACashFileIsFinishedByARerunWithNoSecondJournal(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-cash", false, true);
        for (Path source : List.of(RetailImportIT.SAMPLE, RetailImportIT.SAMPLE.resolve("cashbook"))) {
            try (Stream<Path> files = Files.list(source)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                    Files.copy(f, dir.resolve(f.getFileName().toString()));
                }
            }
        }
        JdbcClient owner = TestDatabase.owner();
        // A refusal of this business's savings only, dropped whatever happens; the trigger's WHEN keeps every other
        // business's rows, so a test running beside this one is not refused.
        ImportReport first;
        try {
            owner.sql("""
                    CREATE FUNCTION test_refuse_savings() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'fabricated refusal of a cash file';
                    END $$
                    """).update();
            owner.sql("CREATE TRIGGER test_refuse_savings BEFORE INSERT ON retail_daily_savings FOR EACH ROW"
                            + " WHEN (NEW.tenant_id = '" + t.tenantId() + "'::uuid)"
                            + " EXECUTE FUNCTION test_refuse_savings()")
                    .update();
            first = importer.run(t.slug(), dir, false);
        } finally {
            owner.sql("DROP TRIGGER IF EXISTS test_refuse_savings ON retail_daily_savings")
                    .update();
            owner.sql("DROP FUNCTION IF EXISTS test_refuse_savings()").update();
        }
        assertThat(first.failed()).as(first.render()).isTrue();
        assertThat(first.render()).contains("FAILED: savings.jsonl");
        long journals = owner.sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"
                        + " AND source_module = 'core.imports'")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(journals).as("the stock openings of the balances file").isEqualTo(3L);

        ImportReport again = importer.run(t.slug(), dir, false);

        assertThat(again.failed()).as(again.render()).isFalse();
        assertThat(again.counts("balances.jsonl")).containsExactly(60, 0, 60, 0);
        assertThat(again.counts("savings.jsonl")[1]).as(again.render()).isPositive();
        assertThat(owner.sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"
                                + " AND source_module = 'core.imports'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("no second stock opening journal")
                .isEqualTo(3L);
        assertThat(owner.sql("SELECT status FROM import_runs WHERE tenant_id = ? AND origin = 'import_retail'"
                                + " ORDER BY created_at")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .as("the stopped run, then the one that finished")
                .containsExactly("cancelled", "committed");
    }

    private static String status(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT status FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
    }

    /** What the business's imported opening journals put on inventory, from the ledger. */
    private static long journalled(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entries e
                          JOIN journal_lines l ON l.entry_id = e.id JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND a.system_key = 'inventory'
                           AND e.id IN (SELECT r.target_id FROM import_refs r
                                         WHERE r.tenant_id = e.tenant_id AND r.target_type = 'ledger.journal_entry')
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }
}
