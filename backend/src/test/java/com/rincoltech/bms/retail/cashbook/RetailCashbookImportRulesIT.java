package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.imports.internal.ImportReport;
import com.rincoltech.bms.retail.imports.internal.RetailImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The importer rules of the cash book review (#147; ADR-022 decision 18; chapter 13 section 13.13.1): history
 * is dated before the first live day, every outstanding advance is opened on the ledger exactly once, an
 * overdrawn bank is carried, advances are numbered in natural source order and a short explanation is skipped.
 * Minimal exports, fabricated names and round amounts.
 */
class RetailCashbookImportRulesIT extends IntegrationTest {

    @Autowired
    RetailImporter importer;

    @Autowired
    BusinessClock clock;

    @Autowired
    TestRestTemplate http;

    @Autowired
    CashBookHistory history;

    @Autowired
    TenantJobs tenants;

    @Autowired
    TransactionTemplate transactions;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    JdbcClient owner;
    LocalDate today;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-rules", false, true);
        cb = new CashbookTestSupport(http, t);
        owner = TestDatabase.owner();
        today = clock.today(BusinessClock.DEFAULT_ZONE);
    }

    static void write(Path dir, String file, String... lines) throws IOException {
        Files.writeString(dir.resolve(file), String.join("\n", lines) + "\n");
    }

    long count(String sql, Object... params) {
        Object[] all = new Object[params.length + 1];
        all[0] = t.tenantId();
        System.arraycopy(params, 0, all, 1, params.length);
        return owner.sql(sql).params(all).query(Long.class).single();
    }

    static String savings(String ref, LocalDate date) {
        return "{\"source_ref\": \"" + ref + "\", \"branch\": \"HQ\", \"business_date\": \"" + date
                + "\", \"amount_minor\": 1000}";
    }

    static final String PARTIES = "{\"name\": \"Test Owner 01\", \"kind\": \"owner\"}";

    static String advance(String ref, String branch, LocalDate date, long principal) {
        return "{\"source_ref\": \"" + ref + "\", \"branch\": \"" + branch
                + "\", \"party\": \"Test Owner 01\", \"principal_minor\": " + principal + ", \"business_date\": \""
                + date + "\"}";
    }

    @Test
    void aCashBookRowOnOrAfterTheFirstLiveDayIsSkippedWithTheDefaultAndWithAnEarlierDate(@TempDir Path dir)
            throws IOException {
        write(
                dir,
                "savings.jsonl",
                savings("S-1", today.minusDays(2)),
                savings("S-2", today),
                savings("S-3", today.minusDays(1)));
        write(
                dir,
                "withdrawals.jsonl",
                "{\"source_ref\": \"W-1\", \"business_date\": \"" + today + "\", " + "\"amount_minor\": 500}");
        write(
                dir,
                "banking.jsonl",
                "{\"source_ref\": \"B-1\", \"branch\": \"HQ\", \"business_date\": \"" + today.minusDays(3)
                        + "\", \"amount_minor\": 500, \"banked_at\": \"" + today + "T09:00:00+03:00\"}");

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.counts("savings.jsonl")).containsExactly(3, 2, 0, 1);
        assertThat(report.counts("withdrawals.jsonl")).containsExactly(1, 0, 0, 1);
        assertThat(report.counts("banking.jsonl")).containsExactly(1, 0, 0, 1);
        assertThat(report.anomalies()).anyMatch(a -> a.contains("on or after the first live day " + today));

        // A named first live day earlier than the history refuses the rows from that day on (S-2 stays skipped; S-3
        // is already imported by the first run and is not touched).
        LocalDate first = today.minusDays(1);
        ImportReport named = importer.run(t.slug(), dir, false, first);
        assertThat(named.counts("savings.jsonl")).containsExactly(3, 0, 2, 1);
        assertThat(count(
                        "SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ? AND business_date >= ?",
                        java.sql.Date.valueOf(first)))
                .isEqualTo(1);
    }

    @Test
    void everyOutstandingAdvanceIsOpenedOnceEvenWithoutACashBalanceRowOrAfterTheOpeningWasPosted(@TempDir Path dir)
            throws IOException {
        LocalDate first = today.minusDays(1);
        write(dir, "cash_parties.jsonl", PARTIES);
        write(dir, "advances.jsonl", advance("ADV-1", "HQ", today.minusDays(10), 100_000));
        write(dir, "cash_balances.jsonl", "{\"branch\": \"BR2\", \"cash_on_hand_minor\": 1000}");

        // HQ has an advance but no cash balance row: it is reported and opened by its own entry.
        ImportReport one = importer.run(t.slug(), dir, false, first);
        assertThat(one.failed()).as(one.render()).isFalse();
        assertThat(one.anomalies())
                .anyMatch(a -> a.contains("branch HQ has outstanding advances but no cash balance row"));
        assertThat(ownerAdvancesDebit()).isEqualTo(100_000);
        long entries = count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?");

        // A second run: a new advance and the HQ row now present; the first advance is not opened twice.
        write(
                dir,
                "advances.jsonl",
                advance("ADV-1", "HQ", today.minusDays(10), 100_000),
                advance("ADV-2", "HQ", today.minusDays(9), 40_000));
        write(dir, "cash_balances.jsonl", "{\"branch\": \"HQ\", \"cash_on_hand_minor\": 5000}");
        ImportReport two = importer.run(t.slug(), dir, false, first);
        assertThat(two.failed()).as(two.render()).isFalse();
        assertThat(ownerAdvancesDebit()).isEqualTo(140_000);
        // The HQ opening carries ADV-2 (not yet opened); ADV-1 keeps its own entry.
        assertThat(count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(entries + 1);

        // An identical re-run adds nothing.
        long before = count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?");
        ImportReport three = importer.run(t.slug(), dir, false, first);
        assertThat(three.failed()).as(three.render()).isFalse();
        assertThat(count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(before);
        assertThat(ownerAdvancesDebit()).isEqualTo(140_000);
    }

    long ownerAdvancesDebit() {
        return count("""
                SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                  JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                 WHERE e.tenant_id = ? AND a.system_key = 'owner_advances'""");
    }

    @Test
    void anOverdrawnBankIsPostedAsACreditLineAndTheOtherLinesAreKept(@TempDir Path dir) throws IOException {
        write(
                dir,
                "cash_balances.jsonl",
                "{\"branch\": \"HQ\", \"cash_on_hand_minor\": 50000, \"bank_minor\": -20000, "
                        + "\"savings_reserve_minor\": 10000}");

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(report.counts("cash_balances.jsonl")).containsExactly(1, 1, 0, 0);
        assertThat(bankMovement()).isEqualTo(-20_000);
        assertThat(count("""
                SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                  JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                 WHERE e.tenant_id = ? AND a.system_key = 'cash_on_hand'""")).isEqualTo(50_000);
        assertThat(count("""
                SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                  JOIN journal_entries e ON e.id = l.entry_id WHERE e.tenant_id = ?""")).isZero();
    }

    long bankMovement() {
        return count("""
                SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                  JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                 WHERE e.tenant_id = ? AND a.system_key = 'bank'""");
    }

    @Test
    void advancesOfOneDayAreNumberedInNaturalSourceOrder(@TempDir Path dir) throws IOException {
        LocalDate day = today.minusDays(5);
        write(dir, "cash_parties.jsonl", PARTIES);
        write(
                dir,
                "advances.jsonl",
                advance("ADV-10", "HQ", day, 3_000),
                advance("ADV-9", "HQ", day, 2_000),
                advance("ADV-2", "HQ", day, 1_000));

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        List<Long> principals = owner.sql(
                        "SELECT principal_minor FROM retail_advances WHERE tenant_id = ? ORDER BY advance_no")
                .param(t.tenantId())
                .query(Long.class)
                .list();
        assertThat(principals).containsExactly(1_000L, 2_000L, 3_000L);
    }

    @Test
    void anExplanationShorterThanTheLiveMinimumSkipsTheRowWithAReason(@TempDir Path dir) throws IOException {
        write(dir, "expense_categories.jsonl", "{\"category\": \"Test Category A\", \"item\": \"Test Item 1\"}");
        LocalDate day = today.minusDays(4);
        write(
                dir,
                "expenses.jsonl",
                "{\"source_ref\": \"E-1\", \"branch\": \"HQ\", \"business_date\": \"" + day
                        + "\", \"category\": \"Test Category A\", \"item\": \"Test Item 1\", \"amount_minor\": 700, "
                        + "\"explanation\": \"x\"}",
                "{\"source_ref\": \"E-2\", \"branch\": \"HQ\", \"business_date\": \"" + day
                        + "\", \"category\": \"Test Category A\", \"item\": \"Test Item 1\", \"amount_minor\": 800, "
                        + "\"explanation\": \"Fuel for the van\"}");

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.counts("expenses.jsonl")).containsExactly(2, 1, 0, 1);
        assertThat(report.anomalies()).anyMatch(a -> a.contains("explanation is shorter than 3 characters"));
        assertThat(count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?"))
                .isEqualTo(1);
    }

    String openingDates() {
        return owner.sql("""
                        SELECT string_agg(DISTINCT entry_date::text, ',') FROM journal_entries
                         WHERE tenant_id = ? AND source_type IN ('retail.cash_opening', 'retail.advance_opening')""").param(t.tenantId()).query(String.class).single();
    }

    @Test
    void aRerunReadsTheFirstLiveDayFromTheOpeningAndRefusesADifferentOne(@TempDir Path dir) throws IOException {
        LocalDate first = today.minusDays(5);
        write(dir, "cash_parties.jsonl", PARTIES);
        write(dir, "savings.jsonl", savings("S-1", today.minusDays(9)));
        write(dir, "advances.jsonl", advance("ADV-1", "HQ", today.minusDays(10), 100_000));
        write(dir, "cash_balances.jsonl", "{\"branch\": \"HQ\", \"cash_on_hand_minor\": 5000}");
        assertThat(importer.run(t.slug(), dir, false, first).failed()).isFalse();
        assertThat(openingDates()).isEqualTo(first.minusDays(1).toString());

        // The old sheet keeps getting rows for the live days; the operator re-runs without the flag.
        write(
                dir,
                "savings.jsonl",
                savings("S-1", today.minusDays(9)),
                savings("S-2", first),
                savings("S-3", first.plusDays(2)));
        write(
                dir,
                "advances.jsonl",
                advance("ADV-1", "HQ", today.minusDays(10), 100_000),
                advance("ADV-2", "HQ", today.minusDays(8), 40_000));
        ImportReport again = importer.run(t.slug(), dir, false);

        assertThat(again.failed()).as(again.render()).isFalse();
        assertThat(again.counts("savings.jsonl")).containsExactly(3, 0, 1, 2);
        assertThat(count("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ? AND business_date >= " + "'"
                        + first + "'"))
                .isZero();
        assertThat(openingDates()).isEqualTo(first.minusDays(1).toString());

        // A different named first live day is refused before anything is written.
        long entries = count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?");
        assertThatThrownBy(() -> importer.run(t.slug(), dir, false, first.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differs from the first live day " + first);
        assertThat(count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(entries);
        // The same date named again is accepted.
        assertThat(importer.run(t.slug(), dir, false, first).failed()).isFalse();
    }

    @Test
    void aLiveRepaymentDoesNotStopAnAdvanceFromBeingOpened(@TempDir Path dir) throws IOException {
        LocalDate first = today.minusDays(1);
        write(dir, "cash_parties.jsonl", PARTIES);
        // A run that committed the advances file and then failed on a later file: the advance is on the record
        // with no opening line, as advanceOpenings never ran.
        UUID party = cb.party("Test Owner 01", "owner");
        tenants.callAsTenant(
                t.slug(),
                "retail",
                () -> transactions.execute(status -> history.importAdvance(new CashBookHistory.Advance(
                        t.headOffice(),
                        today.minusDays(10),
                        party,
                        null,
                        100_000,
                        "Test purpose",
                        "Imported ADV-1",
                        today.minusDays(10)
                                .atStartOfDay(BusinessClock.DEFAULT_ZONE)
                                .toInstant()))));
        assertThat(ownerAdvancesDebit()).isZero();

        // Staff record a live repayment on the imported advance.
        String advanceId = owner.sql("SELECT id::text FROM retail_advances WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(String.class)
                .single();
        var paid = cb.post(
                "/advances/" + advanceId + "/repayments",
                java.util.Map.of("amount_minor", 30_000, "method", "cash", "paid_on", today.toString()),
                ALL);
        assertThat(paid.getStatusCode().is2xxSuccessful())
                .as(String.valueOf(paid.getBody()))
                .isTrue();
        assertThat(ownerAdvancesDebit()).isEqualTo(-30_000);

        write(dir, "cash_balances.jsonl", "{\"branch\": \"HQ\", \"cash_on_hand_minor\": 5000}");
        ImportReport rerun = importer.run(t.slug(), dir, false, first);

        assertThat(rerun.failed()).as(rerun.render()).isFalse();
        assertThat(rerun.anomalies()).noneMatch(a -> a.contains("whose opening line"));
        // The opening carries the whole principal; the live repayment's credit leaves the right balance.
        assertThat(ownerAdvancesDebit()).isEqualTo(70_000);
    }

    @Test
    void anImportedRepaymentAgainstAnAlreadyOpenedAdvanceIsBalancedOnceAndReported(@TempDir Path dir)
            throws IOException {
        LocalDate first = today.minusDays(1);
        write(dir, "cash_parties.jsonl", PARTIES);
        write(dir, "advances.jsonl", advance("ADV-1", "HQ", today.minusDays(10), 100_000));
        write(dir, "cash_balances.jsonl", "{\"branch\": \"HQ\", \"cash_on_hand_minor\": 5000}");
        assertThat(importer.run(t.slug(), dir, false, first).failed()).isFalse();
        assertThat(ownerAdvancesDebit()).isEqualTo(100_000);

        write(
                dir,
                "advance_payments.jsonl",
                "{\"source_ref\": \"P-1\", \"advance_ref\": \"ADV-1\", \"amount_minor\": 25000, "
                        + "\"method\": \"cash\", \"paid_on\": \"" + today.minusDays(3) + "\"}");
        ImportReport one = importer.run(t.slug(), dir, false, first);

        assertThat(one.failed()).as(one.render()).isFalse();
        assertThat(one.anomalies()).anyMatch(a -> a.contains("payment P-1") && a.contains("balancing entry"));
        assertThat(ownerAdvancesDebit()).isEqualTo(75_000);
        assertThat(count("SELECT repaid_minor FROM retail_advances WHERE tenant_id = ?"))
                .isEqualTo(25_000);

        ImportReport two = importer.run(t.slug(), dir, false, first);
        assertThat(two.failed()).as(two.render()).isFalse();
        assertThat(ownerAdvancesDebit()).isEqualTo(75_000);
    }
}
