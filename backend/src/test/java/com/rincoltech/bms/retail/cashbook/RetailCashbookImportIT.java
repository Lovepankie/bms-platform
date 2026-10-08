package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.imports.internal.ImportReport;
import com.rincoltech.bms.retail.imports.internal.RetailImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The cash book tabs of the retail import (#147; ADR-022 decision 18; FR-RET-30; chapter 13 section
 * 13.13.1) on real PostgreSQL, the importer connected as bms_app. The export is the fabricated retail
 * sample plus {@code fixtures/retail/import-sample/cashbook/}; every expected figure below was worked
 * out from those files, not from the importer.
 */
class RetailCashbookImportIT extends IntegrationTest {

    static final Path SAMPLE = Path.of("../fixtures/retail/import-sample");

    static final List<String> TABLES = List.of(
            "retail_daily_savings",
            "retail_expenses",
            "retail_cash_bankings",
            "retail_cash_withdrawals",
            "retail_advances",
            "retail_advance_repayments");

    @Autowired
    RetailImporter importer;

    @Autowired
    TestRestTemplate http;

    @Autowired
    BusinessClock clock;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    JdbcClient owner;
    LocalDate today;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-imp", false, true);
        cb = new CashbookTestSupport(http, t);
        owner = TestDatabase.owner();
        today = clock.today(BusinessClock.DEFAULT_ZONE);
    }

    /** The retail sample and the cash book sample in one export directory. */
    static Path export(Path dir) throws IOException {
        for (Path source : List.of(SAMPLE, SAMPLE.resolve("cashbook"))) {
            try (Stream<Path> files = Files.list(source)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                    Files.copy(f, dir.resolve(f.getFileName().toString()));
                }
            }
        }
        return dir;
    }

    long count(String sql, Object... params) {
        Object[] all = new Object[params.length + 1];
        all[0] = t.tenantId();
        System.arraycopy(params, 0, all, 1, params.length);
        return owner.sql(sql).params(all).query(Long.class).single();
    }

    UUID branch(String code) {
        return owner.sql("SELECT id FROM branches WHERE tenant_id = ? AND code = ?")
                .params(t.tenantId(), code)
                .query(UUID.class)
                .single();
    }

    long everything() {
        long n = count("SELECT count(*) FROM retail_import_refs WHERE tenant_id = ?")
                + count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                + count("SELECT count(*) FROM retail_expense_categories WHERE tenant_id = ?")
                + count("SELECT count(*) FROM retail_expense_items WHERE tenant_id = ?")
                + count("SELECT count(*) FROM retail_cash_parties WHERE tenant_id = ?");
        for (String table : TABLES) {
            n += count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?");
        }
        return n;
    }

    /** Taken in cash by an imported sale of the branch on the day, worked from the sales table alone. */
    long cashTakings(String branch, String day) {
        return count("""
                        SELECT coalesce(sum(total_minor), 0) FROM retail_sales
                         WHERE tenant_id = ? AND branch_id = ? AND sale_date = ?::date AND payment_method = 'cash'""", branch(branch), day);
    }

    @Test
    void goldenImportOfTheCashBookTabs(@TempDir Path dir) throws IOException {
        ImportReport report = importer.run(t.slug(), export(dir), false);
        String text = report.render();

        assertThat(report.failed()).as(text).isFalse();
        // read, written, existing, skipped; the report lists the files in the data dictionary's order.
        assertThat(report.counts("cash_parties.jsonl")).containsExactly(5, 4, 0, 1);
        assertThat(report.counts("expense_categories.jsonl")).containsExactly(3, 3, 0, 0);
        assertThat(report.counts("savings.jsonl")).containsExactly(6, 3, 0, 3);
        assertThat(report.counts("expenses.jsonl")).containsExactly(6, 4, 0, 2);
        assertThat(report.counts("banking.jsonl")).containsExactly(4, 3, 0, 1);
        assertThat(report.counts("withdrawals.jsonl")).containsExactly(2, 2, 0, 0);
        assertThat(report.counts("advances.jsonl")).containsExactly(6, 3, 0, 3);
        assertThat(report.counts("advance_payments.jsonl")).containsExactly(6, 4, 0, 2);
        assertThat(report.counts("cash_balances.jsonl")).containsExactly(4, 3, 0, 1);
        assertThat(text.indexOf("cash_parties.jsonl"))
                .isLessThan(text.indexOf("savings.jsonl"))
                .isLessThan(text.indexOf("banking.jsonl"));
        assertThat(text.indexOf("banking.jsonl")).isLessThan(text.indexOf("advances.jsonl"));
        assertThat(text.indexOf("advance_payments.jsonl")).isLessThan(text.indexOf("cash_balances.jsonl"));

        // Counts and sums of the history rows, all flagged historical, by the import actor, never voided.
        assertThat(count("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ? AND historical"))
                .isEqualTo(3);
        assertThat(count("SELECT sum(amount_minor) FROM retail_daily_savings WHERE tenant_id = ?"))
                .isEqualTo(80_000);
        assertThat(count("SELECT sum(total_sold_minor) FROM retail_daily_savings WHERE tenant_id = ?"))
                .isEqualTo(300_200);
        assertThat(count("""
                        SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ? AND (suggested_minor IS NOT NULL
                           OR overwritten OR created_at <> occurred_at OR voided_at IS NOT NULL
                           OR recorded_by <> '00000000-0000-0000-0000-000000000000')""")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM retail_daily_savings
                         WHERE tenant_id = ? AND branch_id = ? AND business_date = '2026-09-15' AND amount_minor = 50000""", branch("KLA"))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ? AND historical"))
                .isEqualTo(4);
        assertThat(count("SELECT sum(amount_minor) FROM retail_expenses WHERE tenant_id = ?"))
                .isEqualTo(29_000);
        assertThat(count("SELECT sum(amount_minor) FROM retail_cash_withdrawals WHERE tenant_id = ? AND historical"))
                .isEqualTo(130_000);
        assertThat(count("SELECT sum(amount_minor) FROM retail_cash_bankings WHERE tenant_id = ? AND historical"))
                .isEqualTo(320_000);
        assertThat(count("SELECT sum(principal_minor) FROM retail_advances WHERE tenant_id = ? AND historical"))
                .isEqualTo(350_000);
        assertThat(count("SELECT sum(amount_minor) FROM retail_advance_repayments WHERE tenant_id = ? AND historical"))
                .isEqualTo(210_000);
        for (String table : TABLES) {
            assertThat(count("SELECT count(*) FROM " + table
                            + " WHERE tenant_id = ? AND (NOT historical OR voided_at IS NOT NULL"
                            + " OR journal_entry_id IS NOT NULL)"))
                    .as(table)
                    .isZero();
        }
        // A withdrawal with no shop is the head office's.
        assertThat(count(
                        "SELECT count(*) FROM retail_cash_withdrawals WHERE tenant_id = ? AND branch_id = ? AND amount_minor = 100000",
                        t.headOffice()))
                .isEqualTo(1);

        // Names the lists did not carry were created as written and reported; the company never mapped.
        assertThat(count("SELECT count(*) FROM retail_expense_categories WHERE tenant_id = ?"))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM retail_expense_items WHERE tenant_id = ?"))
                .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM retail_cash_parties WHERE tenant_id = ?"))
                .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM retail_cash_parties WHERE tenant_id = ? AND name = 'Test Company 01'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM retail_cash_parties WHERE tenant_id = ? AND kind = 'other'"))
                .isEqualTo(1);
        assertThat(count("""
                        SELECT count(*) FROM retail_expenses WHERE tenant_id = ? AND category_name = 'Test Category A'
                           AND item_name = 'others' AND explanation = 'Test explanation'""")).isEqualTo(1);
        assertThat(report.anomalies())
                .anyMatch(
                        a -> a.startsWith(
                                "expenses.jsonl:3: category Test Category C is not in expense_categories.jsonl; created as written"))
                .anyMatch(a -> a.startsWith("expenses.jsonl:3: item Test Item 3 of category Test Category C is not in"))
                .anyMatch(a -> a.startsWith("expenses.jsonl:4: item Test Item 9 of category Test Category A is not in"))
                .anyMatch(
                        a -> a.startsWith(
                                "expenses.jsonl:4: beneficiary Test Walkin 01 is not in cash_parties.jsonl; created as kind other"));
        assertThat(text).contains("1 categories, 2 items, 1 parties");

        // The expected amount to bank is worked from the imported history of the day, whatever the
        // order of the files: takings less savings, expenses, advances paid out, plus cash repayments.
        assertThat(expected("BNK-001")).isEqualTo(cashTakings("KLA", "2026-09-15") - 50_000 - 12_000 - 8_000);
        assertThat(expected("BNK-002")).isEqualTo(cashTakings("KLA", "2026-09-17") - 10_000 - 5_000);
        assertThat(expected("BNK-003")).isEqualTo(cashTakings("ENT", "2026-09-10") - 20_000 - 4_000);
        assertThat(expected("BNK-003")).isNegative();

        // Advances: the next live numbers in business date then source id order; the pilot's id only
        // in the import reference and the note.
        assertThat(advanceNo("ADV-002")).isEqualTo("RA00000001");
        assertThat(advanceNo("ADV-009")).isEqualTo("RA00000002");
        assertThat(advanceNo("ADV-003")).isEqualTo("RA00000003");
        assertThat(count("SELECT count(*) FROM retail_advances WHERE tenant_id = ? AND advance_no LIKE '%ADV%'"))
                .isZero();
        assertThat(owner.sql("SELECT note FROM retail_advances WHERE tenant_id = ? AND advance_no = 'RA00000002'")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("Imported ADV-009; processing fee 5000 minor units, not modelled");
        assertThat(report.anomalies())
                .anyMatch(a -> a.startsWith(
                        "advances.jsonl:1: processing fee 5000 is not modelled; kept in the note of RA00000002"));
        assertThat(text).contains("advance processing fees are not modelled").contains("total 5000 minor units");

        // repaid_minor equals the sum of the payments, for every advance.
        assertThat(count("""
                        SELECT count(*) FROM retail_advances a WHERE a.tenant_id = ? AND a.repaid_minor <>
                           (SELECT coalesce(sum(r.amount_minor), 0) FROM retail_advance_repayments r WHERE r.advance_id = a.id)""")).isZero();
        assertThat(repaid("RA00000001")).isEqualTo(100_000);
        assertThat(repaid("RA00000002")).isEqualTo(100_000);
        assertThat(repaid("RA00000003")).isEqualTo(10_000);

        // History posts no journal: the only entries are the three stock openings and three cash openings.
        assertThat(count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(6);
        assertThat(count("""
                        SELECT count(*) FROM journal_entries WHERE tenant_id = ?
                           AND source_type NOT IN ('retail.import_opening', 'retail.cash_opening')""")).isZero();

        // One balanced opening entry per branch with a row, dated the day before the first live day.
        assertThat(
                        count(
                                "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_type = 'retail.cash_opening'"))
                .isEqualTo(3);
        assertOpening(
                "KLA",
                branch("KLA"),
                Map.of("cash_on_hand", 500_000L, "bank", 1_200_000L, "savings_reserve", 60_000L),
                1,
                100_000);
        assertOpening("ENT", branch("ENT"), Map.of("cash_on_hand", 300_000L), 1, 40_000);
        assertOpening("HQ", t.headOffice(), Map.of("cash_on_hand", 10_000L), 0, 0);
        assertThat(count(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND branch_id = ? AND source_type = 'retail.cash_opening'",
                        branch("JJA")))
                .isZero();
        assertThat(text).contains("cash book opening journals").contains("dated " + today.minusDays(1));

        // Skipped rows are listed with their file and line, never dropped silently.
        assertThat(report.anomalies())
                .anyMatch(a -> a.startsWith("cash_parties.jsonl:5: party Test Company 01 is marked as the company"))
                .anyMatch(
                        a -> a.startsWith(
                                "savings.jsonl:4: branch KLA already has a savings record for 2026-09-15; a second one is never merged"))
                .anyMatch(a -> a.startsWith("savings.jsonl:5: business_date 2099-01-01 is in the future"))
                .anyMatch(a -> a.startsWith("savings.jsonl:6: business_date is not an ISO 8601 date or date-time"))
                .anyMatch(a -> a.startsWith("expenses.jsonl:5: item others requires an explanation"))
                .anyMatch(a -> a.startsWith("expenses.jsonl:6: unknown branch code ZZZ"))
                .anyMatch(a -> a.startsWith("banking.jsonl:4: amount_minor must be a whole number of minor units"))
                .anyMatch(a -> a.startsWith("advances.jsonl:4: party Test Company 01 is marked as the company"))
                .anyMatch(a -> a.startsWith("advances.jsonl:5: party Test Supplier 01 is of kind supplier"))
                .anyMatch(a -> a.startsWith("advances.jsonl:6: unknown party Test Nobody 01"))
                .anyMatch(a -> a.startsWith(
                        "advance_payments.jsonl:4: amount 60000 is above the remaining principal of advance ADV-003"))
                .anyMatch(a -> a.startsWith("advance_payments.jsonl:5: unknown advance_ref ADV-777"))
                .anyMatch(a -> a.startsWith("cash_balances.jsonl:4: a second cash balance row for branch KLA"));
        assertThat(
                        count(
                                "SELECT count(*) FROM retail_advance_repayments WHERE tenant_id = ? AND method = 'mobile_money'"))
                .isZero();
    }

    long expected(String ref) {
        return count("""
                        SELECT b.expected_minor FROM retail_cash_bankings b JOIN retail_import_refs r ON r.target_id = b.id
                         WHERE b.tenant_id = ? AND r.source_file = 'banking' AND r.source_ref = ?""", ref);
    }

    String advanceNo(String ref) {
        return owner.sql("""
                        SELECT a.advance_no FROM retail_advances a JOIN retail_import_refs r ON r.target_id = a.id
                         WHERE a.tenant_id = ? AND r.source_file = 'advances' AND r.source_ref = ?""").params(t.tenantId(), ref).query(String.class).single();
    }

    long repaid(String advanceNo) {
        return count("SELECT repaid_minor FROM retail_advances WHERE tenant_id = ? AND advance_no = ?", advanceNo);
    }

    /** The branch's cash opening entry balances against opening balance equity, line by line. */
    void assertOpening(String code, UUID branch, Map<String, Long> debits, int advances, long advancesMinor) {
        UUID entry = owner.sql("""
                        SELECT r.target_id FROM retail_import_refs r
                         WHERE r.tenant_id = ? AND r.source_file = 'cash_opening' AND r.source_ref = ?""")
                .params(t.tenantId(), branch.toString())
                .query(UUID.class)
                .single();
        assertThat(owner.sql("SELECT entry_date FROM journal_entries WHERE id = ?")
                        .param(entry)
                        .query(LocalDate.class)
                        .single())
                .as(code + " dated the day before the first live day")
                .isEqualTo(today.minusDays(1));
        assertThat(owner.sql("SELECT branch_id FROM journal_entries WHERE id = ?")
                        .param(entry)
                        .query(UUID.class)
                        .single())
                .isEqualTo(branch);
        assertThat(owner.sql("SELECT idempotency_key FROM journal_entries WHERE id = ?")
                        .param(entry)
                        .query(String.class)
                        .single())
                .isEqualTo("retail.cash_opening:" + branch);
        Map<String, Long> byKey = new LinkedHashMap<>();
        owner.sql("""
                        SELECT a.system_key, sum(l.debit), sum(l.credit), count(*) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id WHERE l.entry_id = ? GROUP BY a.system_key
                        """)
                .param(entry)
                .query((rs, n) -> {
                    byKey.put(rs.getString(1) + ".debit", rs.getLong(2));
                    byKey.put(rs.getString(1) + ".credit", rs.getLong(3));
                    byKey.put(rs.getString(1) + ".lines", rs.getLong(4));
                    return 1;
                })
                .list();
        long total = advancesMinor
                + debits.values().stream().mapToLong(Long::longValue).sum();
        debits.forEach((key, amount) ->
                assertThat(byKey.get(key + ".debit")).as(code + " " + key).isEqualTo(amount));
        assertThat(byKey.get("opening_balance_equity.credit")).as(code).isEqualTo(total);
        assertThat(byKey.get("opening_balance_equity.lines")).isEqualTo(1);
        assertThat(byKey.getOrDefault("owner_advances.debit", 0L)).isEqualTo(advancesMinor);
        assertThat(byKey.getOrDefault("owner_advances.lines", 0L)).isEqualTo(advances);
        assertThat(count("""
                        SELECT count(*) FROM journal_lines l WHERE l.tenant_id = ? AND l.entry_id = ?
                           AND l.subledger_type = 'retail.advance' AND l.subledger_id IN
                               (SELECT id FROM retail_advances WHERE repaid_minor < principal_minor AND branch_id = ?)""", entry, branch)).isEqualTo(advances);
        assertThat(count(
                        "SELECT coalesce(sum(debit) - sum(credit), 0) FROM journal_lines WHERE tenant_id = ? AND entry_id = ?",
                        entry))
                .isZero();
    }

    @Test
    void theFirstLiveDayOpensWithTheCarriedBalanceAndLaterAdvancesTakeLaterNumbers(@TempDir Path dir)
            throws IOException {
        assertThat(importer.run(t.slug(), export(dir), false).failed()).isFalse();
        UUID kla = branch("KLA");

        // The imported days have no ledger basis; the live day that follows opens with the carried cash.
        UUID category = owner.sql(
                        "SELECT id FROM retail_expense_categories WHERE tenant_id = ? AND name = 'Test Category A'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        UUID item = owner.sql("SELECT id FROM retail_expense_items WHERE tenant_id = ? AND name = 'Test Item 1'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        assertThat(cb.expense(kla, category, item, 7_000, today).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode day = daily(kla, today, today).get("items").get(0);
        assertThat(day.get("opening_minor").asLong()).isEqualTo(500_000);
        assertThat(day.get("other_movements_minor").asLong()).isZero();
        assertThat(day.get("closing_minor").asLong()).isEqualTo(493_000);
        assertThat(cb.cashOnHand(kla, today.minusDays(1))).isEqualTo(500_000);
        assertThat(cb.cashOnHand(kla, today)).isEqualTo(493_000);

        JsonNode imported = daily(kla, LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-15"))
                .get("items")
                .get(0);
        assertThat(imported.get("historical").asBoolean()).isTrue();
        assertThat(imported.get("ledger_basis").asBoolean()).isFalse();
        assertThat(imported.has("opening_minor")).isFalse();

        // A live advance takes a number above every imported one; no two advances share a number.
        UUID owner01 = owner.sql("SELECT id FROM retail_cash_parties WHERE tenant_id = ? AND name = 'Test Owner 01'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", kla);
        body.put("party_id", owner01);
        body.put("principal_minor", 10_000);
        body.put("business_date", today.toString());
        ResponseEntity<JsonNode> advance = cb.post("/advances", body, ALL);
        assertThat(advance.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(advance.getBody().get("advance_no").asString()).isEqualTo("RA00000004");
        assertThat(count("SELECT count(DISTINCT advance_no) FROM retail_advances WHERE tenant_id = ?"))
                .isEqualTo(count("SELECT count(*) FROM retail_advances WHERE tenant_id = ?"))
                .isEqualTo(4);

        // The banking report lists the imported days apart and carries nothing from them into the live day.
        JsonNode report = cb.get("/reports/cash/banking?branch_id=" + kla + "&from=2026-09-01&to=" + today, ALL)
                .getBody();
        JsonNode live = null;
        int importedDays = 0;
        for (JsonNode row : report.get("items")) {
            if (row.get("historical").asBoolean()) {
                importedDays++;
                assertThat(row.has("unbanked_running_minor")
                                && !row.get("unbanked_running_minor").isNull())
                        .as(row.get("business_date").asString())
                        .isFalse();
            } else if (row.get("business_date").asString().equals(today.toString())) {
                live = row;
            }
        }
        assertThat(importedDays).isGreaterThanOrEqualTo(3);
        assertThat(live).isNotNull();
        // The live day's running total starts at the first live day with nothing carried in: its own
        // expected amount (the expense and the advance paid out) less nothing banked.
        assertThat(live.get("unbanked_running_minor").asLong()).isEqualTo(-17_000);
        assertThat(live.get("expected_minor").asLong()).isEqualTo(-17_000);
    }

    JsonNode daily(UUID branch, LocalDate from, LocalDate to) {
        ResponseEntity<JsonNode> r =
                cb.get("/reports/cash/daily?branch_id=" + branch + "&from=" + from + "&to=" + to, ALL);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void aSecondRunAddsNothingAndANamedFirstLiveDateDatesTheOpening(@TempDir Path dir) throws IOException {
        Path export = export(dir);
        LocalDate first = LocalDate.parse("2026-10-01");

        ImportReport one = importer.run(t.slug(), export, false, first);
        assertThat(one.failed()).as(one.render()).isFalse();
        long after = everything();
        assertThat(owner.sql(
                                "SELECT DISTINCT entry_date FROM journal_entries WHERE tenant_id = ? AND source_type = 'retail.cash_opening'")
                        .param(t.tenantId())
                        .query(LocalDate.class)
                        .list())
                .containsExactly(first.minusDays(1));

        ImportReport two = importer.run(t.slug(), export, false, first);

        assertThat(two.failed()).as(two.render()).isFalse();
        assertThat(everything()).isEqualTo(after);
        assertThat(two.counts("savings.jsonl")).containsExactly(6, 0, 3, 3);
        assertThat(two.counts("expenses.jsonl")).containsExactly(6, 0, 4, 2);
        assertThat(two.counts("banking.jsonl")).containsExactly(4, 0, 3, 1);
        assertThat(two.counts("withdrawals.jsonl")).containsExactly(2, 0, 2, 0);
        assertThat(two.counts("advances.jsonl")).containsExactly(6, 0, 3, 3);
        assertThat(two.counts("advance_payments.jsonl")).containsExactly(6, 0, 4, 2);
        assertThat(two.counts("cash_balances.jsonl")).containsExactly(4, 0, 3, 1);
        assertThat(two.counts("cash_parties.jsonl")).containsExactly(5, 0, 4, 1);
        assertThat(two.render()).contains("already posted, not posted again");
        // A re-run takes no advance number.
        assertThat(count("SELECT count(*) FROM retail_advances WHERE tenant_id = ?"))
                .isEqualTo(3);
    }

    @Test
    void aDryRunRollsEverythingBackAndGivesTheNumbersBack(@TempDir Path dir) throws IOException {
        Path export = export(dir);
        long before = everything();

        ImportReport dry = importer.run(t.slug(), export, true);

        assertThat(dry.failed()).as(dry.render()).isFalse();
        assertThat(dry.counts("advances.jsonl")).containsExactly(6, 3, 0, 3);
        assertThat(everything()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM retail_advances WHERE tenant_id = ?"))
                .isZero();

        ImportReport real = importer.run(t.slug(), export, false);
        assertThat(real.render().replace("committed, one transaction per file", "x"))
                .isEqualTo(dry.render().replace("dry run, every write rolled back", "x"));
        assertThat(advanceNo("ADV-002")).isEqualTo("RA00000001");
    }

    @Test
    void anExportWithoutTheCashBookFilesImportsAsBefore() {
        ImportReport report = importer.run(t.slug(), SAMPLE, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(report.counts("savings.jsonl")).containsExactly(0, 0, 0, 0);
        assertThat(report.render()).doesNotContain("savings.jsonl").doesNotContain("cash book");
        assertThat(report.anomalies()).noneMatch(a -> a.startsWith("savings.jsonl") || a.startsWith("cash_"));
        assertThat(everything()
                        - count("SELECT count(*) FROM retail_import_refs WHERE tenant_id = ?")
                        - count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isZero();
    }

    @Test
    void aSecondShopRowForOneBranchAndAnExistingSavingsRecordAreNeverMerged(@TempDir Path dir) throws IOException {
        // A live record of the day already exists; the imported row for the same shop and day is skipped.
        UUID hq = t.headOffice();
        UUID product = cb.api.product("CABLE-2MM", 1_000, 1_500);
        cb.api.stockUp(hq, product, "50");
        LocalDate day = today.minusDays(2);
        cb.sale(hq, "cash", product, "4", day);
        Map<String, Object> saving = new LinkedHashMap<>();
        saving.put("branch_id", hq);
        saving.put("business_date", day.toString());
        saving.put(
                "suggestion_token",
                cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + day, ALL)
                        .getBody()
                        .get("suggestion_token")
                        .asString());
        assertThat(cb.post("/savings", saving, ALL).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Files.writeString(
                dir.resolve("savings.jsonl"),
                "{\"source_ref\": \"S-1\", \"branch\": \"HQ\", \"business_date\": \"" + day
                        + "\", \"amount_minor\": 1000}\n");

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.counts("savings.jsonl")).containsExactly(1, 0, 0, 1);
        assertThat(count("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ? AND historical"))
                .isZero();
    }

    String historicalId(String table) {
        return owner.sql("SELECT id::text FROM " + table + " WHERE tenant_id = ? AND historical LIMIT 1")
                .param(t.tenantId())
                .query(String.class)
                .single();
    }

    void assertRefusedAsHistorical(String path) {
        ResponseEntity<JsonNode> r = cb.voidIt(path, "Entered by mistake", ALL);
        assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("historical_record");
    }

    @Test
    void anImportedRecordOfEveryKindCannotBeVoidedAndTheLedgerAndSummaryAreLeftAlone(@TempDir Path dir)
            throws IOException {
        assertThat(importer.run(t.slug(), export(dir), false).failed()).isFalse();
        long refs = everything();
        String advance = historicalId("retail_advances");
        Map<String, Object> repayment = owner.sql(
                        "SELECT id::text AS id, advance_id::text AS advance_id FROM retail_advance_repayments"
                                + " WHERE tenant_id = ? AND historical ORDER BY id LIMIT 1")
                .param(t.tenantId())
                .query()
                .singleRow();
        String repaymentAdvance = (String) repayment.get("advance_id");

        assertRefusedAsHistorical("/savings/" + historicalId("retail_daily_savings"));
        assertRefusedAsHistorical("/bankings/" + historicalId("retail_cash_bankings"));
        assertRefusedAsHistorical("/withdrawals/" + historicalId("retail_cash_withdrawals"));
        assertRefusedAsHistorical("/expenses/" + historicalId("retail_expenses"));
        assertRefusedAsHistorical("/advances/" + advance);
        assertRefusedAsHistorical("/advances/" + repaymentAdvance + "/repayments/" + repayment.get("id"));

        assertThat(everything()).isEqualTo(refs);
        assertThat(count("SELECT count(*) FROM retail_advances WHERE tenant_id = ? AND voided_at IS NOT NULL"))
                .isZero();
        assertThat(count("SELECT coalesce(sum(repaid_minor), 0) FROM retail_advances WHERE tenant_id = ?"))
                .isEqualTo(count(
                        "SELECT coalesce(sum(amount_minor), 0) FROM retail_advance_repayments WHERE tenant_id = ?"));
    }

    @Test
    void aDayWithOnlyAnImportedCashPurchaseIsHistoricalWithoutLedgerFigures(@TempDir Path dir) throws IOException {
        assertThat(importer.run(t.slug(), export(dir), false).failed()).isFalse();
        UUID kla = branch("KLA");
        // A KLA restock date on which the shop has no imported sale, saving, expense or banking: the cash
        // purchase is the only thing that marks the day historical.
        LocalDate day = owner.sql("""
                        SELECT min(p.purchased_on) FROM retail_purchases p
                          JOIN retail_stock_movements m ON m.source_id = p.id AND m.kind = 'purchase'
                         WHERE p.tenant_id = ? AND m.branch_id = ? AND p.historical AND p.payment_method = 'cash'
                           AND NOT EXISTS (SELECT 1 FROM retail_sales x WHERE x.branch_id = m.branch_id AND x.sale_date = p.purchased_on)
                           AND NOT EXISTS (SELECT 1 FROM retail_daily_savings x WHERE x.branch_id = m.branch_id AND x.business_date = p.purchased_on)
                           AND NOT EXISTS (SELECT 1 FROM retail_expenses x WHERE x.branch_id = m.branch_id AND x.business_date = p.purchased_on)
                           AND NOT EXISTS (SELECT 1 FROM retail_cash_bankings x WHERE x.branch_id = m.branch_id AND x.business_date = p.purchased_on)
                        """)
                .params(t.tenantId(), kla)
                .query(java.sql.Date.class)
                .single()
                .toLocalDate();

        JsonNode row = daily(kla, day, day).get("items").get(0);

        assertThat(row.get("historical").asBoolean()).isTrue();
        assertThat(row.get("ledger_basis").asBoolean()).isFalse();
        assertThat(row.has("other_movements_minor")).isFalse();
    }
}
