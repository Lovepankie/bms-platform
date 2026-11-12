package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.CommitApproval;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMetrics;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportReconciliation;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.DryRun;
import com.rincoltech.bms.core.imports.ImportRuns.PolicyDecision;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.core.imports.internal.CommitTestSeams;
import com.rincoltech.bms.core.imports.internal.EngineRun;
import com.rincoltech.bms.core.imports.internal.MetricsCheck;
import com.rincoltech.bms.core.imports.internal.TestFiles;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code lending_loans_v1} on the framework (issue #136, chapter 13 sections 13.10 to 13.12, ADR-027): the fabricated pilot
 * register at the head office and a fabricated register of a second branch, read with the pilot register's fixture mapping,
 * through the upload, validation, review (the standard resolutions of 13.12), the disbursement decision, the preview, the dry
 * run, the maker and checker commit and the reconciliation, on PostgreSQL as {@code bms_app}. The preview's loan principal
 * per branch and its loans and principal per days-past-due bucket at the cutover day are proven against the loan book after
 * the commit, read from the lending tables and the {@code loans_receivable} lines, never from the report, and each opening
 * journal's debit and credit against its {@code journal_lines} (the Lead's notes on #136). The same register as a workbook
 * gives the same books; a re-run and a resume post no second journal and write nothing twice; a business that already has
 * members and loans keeps them and has its borrowers linked to them. Fabricated values only.
 */
class LendingLoansV1TemplateIT extends IntegrationTest {

    static final Path PILOT = Path.of("../fixtures/pilot_loan_register_sample.csv");

    static final Path BRANCH_TWO = Path.of("../fixtures/imports/lending/fab-register-branch-two.csv");

    static final Path OVERPAID = Path.of("../fixtures/imports/lending/fab-register-overpaid.csv");

    static final Path MAPPING = Path.of("../fixtures/imports/lending_loans_v1.pilot_register.mapping.json");

    static final String SHA = "d".repeat(64);

    /** The batch business date of 13.12, 30 June 2026, as the run's cutover moment (21:00 in Kampala). */
    static final Instant CUTOVER = Instant.parse("2026-06-30T18:00:00Z");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Every table the loader writes a row of, each row registered in {@code import_refs} (rule 4 of retail_v1). */
    static final List<String> WRITTEN_TABLES = List.of(
            "lending_members",
            "lending_next_of_kin",
            "lending_collateral_items",
            "lending_loans",
            "lending_loan_status_history",
            "lending_schedule_items",
            "lending_loan_transactions",
            "lending_repayment_allocations",
            "lending_loan_collateral",
            "lending_collection_actions");

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
    ImportPreview preview;

    @Autowired
    ImportReconciliation reconciliation;

    @Autowired
    ImportMetrics metrics;

    @Autowired
    CommitTestSeams seams;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    UUID officer;
    UUID otherOfficer;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("lending-v1", true);
        other = TestDatabase.tenant("lending-v1-b", true);
        officer = Api.staff(t, Api.email("loans-officer"));
        otherOfficer = Api.staff(other, Api.email("loans-officer-b"));
        product(t, officer);
        product(other, otherOfficer);
    }

    @AfterEach
    void tearDown() {
        seams.killAfter = 0;
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    // ---------------------------------------------------------------------------------------------
    // The golden run of 13.12, at two branches.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theGoldenRegisterAtTwoBranchesCommitsAsSection1312SaysAndTheBooksEqualThePreview() throws IOException {
        UUID run = reviewed(t, officer, csvFiles());
        ImportPreview.Preview predicted = as(t, () -> preview.preview(run));
        Map<String, Long> before = TestDatabase.rowCounts(t.tenantId());

        DryRun dry = as(t, () -> runs.dryRun(run, operator));

        assertThat(TestDatabase.rowCounts(t.tenantId()))
                .as("the dry run writes nothing")
                .isEqualTo(before);
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        committed(t, run);

        // 13.12: 6 members (rows 3 and 12 one), 6 next of kin (2 linked), 7 loans (6 active, 1 closed), 7 items, 2
        // historic repayments with no journal; and the second branch's 3 of each, 2 loans active.
        assertThat(count(t, "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND source = 'import'"))
                .isEqualTo(9);
        assertThat(count(t, "SELECT count(*) FROM lending_next_of_kin WHERE tenant_id = ?"))
                .isEqualTo(9);
        assertThat(count(
                        t,
                        "SELECT count(*) FROM lending_next_of_kin WHERE tenant_id = ? AND link_status = 'confirmed'"
                                + " AND link_method = 'nin'"))
                .isEqualTo(2);
        assertThat(lines(
                        t,
                        "SELECT b.code || '|' || l.status || '|' || count(*) FROM lending_loans l JOIN branches b ON"
                                + " b.id = l.branch_id WHERE l.tenant_id = ? AND l.channel = 'import' GROUP BY b.code,"
                                + " l.status"))
                .containsExactlyInAnyOrder("HQ|active|6", "HQ|closed|1", "BR2|active|2", "BR2|closed|1");
        assertThat(count(t, "SELECT count(*) FROM lending_collateral_items WHERE tenant_id = ?"))
                .isEqualTo(10);
        assertThat(count(
                        t,
                        "SELECT count(*) FROM lending_loan_transactions WHERE tenant_id = ? AND txn_type = 'repayment'"
                                + " AND is_historic AND journal_entry_id IS NULL"))
                .isEqualTo(4);
        assertThat(lines(
                        t,
                        "SELECT l.approved_principal_minor || '|' || l.requested_principal_minor || '|'"
                                + " || l.principal_disbursed_minor FROM lending_loans l WHERE l.tenant_id = ?"))
                .allSatisfy(s -> assertThat(s.split("\\|")).containsOnly(s.split("\\|")[0]));

        // The days past due of 13.12 as at 30 June 2026, in the loan book.
        assertThat(lines(
                        t,
                        "SELECT l.principal_outstanding_minor || '|' || l.days_past_due FROM lending_loans l"
                                + " JOIN branches b ON b.id = l.branch_id WHERE l.tenant_id = ? AND b.code = 'HQ'"
                                + " AND l.status = 'active'"))
                .containsExactlyInAnyOrder(
                        "500000|75", "300000|86", "200000|88", "1000000|67", "560000|16", "250000|54");

        // One opening journal per branch: debit loans_receivable one line per open loan, credit equity the total.
        assertThat(lines(t, """
                        SELECT b.code || '|' || a.system_key || '|' || l.debit || '|' || l.credit FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                          JOIN branches b ON b.id = e.branch_id
                         WHERE l.tenant_id = ?
                        """))
                .containsExactlyInAnyOrder(
                        "HQ|loans_receivable|500000|0",
                        "HQ|loans_receivable|300000|0",
                        "HQ|loans_receivable|200000|0",
                        "HQ|loans_receivable|1000000|0",
                        "HQ|loans_receivable|560000|0",
                        "HQ|loans_receivable|250000|0",
                        "HQ|opening_balance_equity|0|2810000",
                        "BR2|loans_receivable|400000|0",
                        "BR2|loans_receivable|260000|0",
                        "BR2|opening_balance_equity|0|660000");
        assertThat(count(
                        t,
                        "SELECT count(*) FROM journal_lines l WHERE l.tenant_id = ? AND l.debit > 0"
                                + " AND (l.subledger_type <> 'lending.loan' OR l.subledger_id IS NULL"
                                + "      OR NOT EXISTS (SELECT 1 FROM lending_loans x WHERE x.id = l.subledger_id))"))
                .as("each debit line names its loan")
                .isZero();

        // The Lead's notes on #136: the preview against the loan book, never the report.
        assertThat(sumByBranch(predicted.balances().loanPrincipal()))
                .isEqualTo(Map.of("BR2", 660_000L, "HQ", 2_810_000L))
                .isEqualTo(bookPrincipal(t))
                .isEqualTo(receivableLines(t));
        assertThat(buckets(predicted.balances().arrears()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "HQ|61-90", "4|2000000",
                        "HQ|31-60", "1|250000",
                        "HQ|1-30", "1|560000",
                        "BR2|1-30", "1|400000",
                        "BR2|current", "1|260000"))
                .isEqualTo(bookBuckets(t));
        Map<String, String> journalLines = journalTotals(t);
        assertThat(journalLines).isEqualTo(Map.of("HQ", "2810000|2810000", "BR2", "660000|660000"));
        CommitReport real = as(t, () -> runs.commitReport(run));
        for (CommitReport.Journal j : real.journals()) {
            assertThat(j.debitMinor() + "|" + j.creditMinor()).isEqualTo(journalLines.get(j.branch()));
        }
        for (ImportForecast.Journal j : predicted.balances().journals()) {
            assertThat(j.debitMinor() + "|" + j.creditMinor()).isEqualTo(journalLines.get(j.branch()));
        }
        assertThat(real.sameAs(dry.report()))
                .as("real:%n%s%ndry:%n%s", real.render(), dry.report().render())
                .isTrue();
        // The run's metrics (issue #140), from its stored data: against its tables and the commit's report.
        MetricsCheck.assertFromStoredData(as(t, () -> metrics.metrics(run)), run, real);

        // Every record the run wrote is in import_refs, a loan's own rows too; the reconciliation ties; another
        // business has nothing. The tables are those the commit made grow, read from the database, not a list alone.
        Map<String, Long> after = TestDatabase.rowCounts(t.tenantId());
        assertThat(after.keySet().stream()
                        .filter(k -> k.startsWith("lending_") && after.get(k) > before.getOrDefault(k, 0L)))
                .as("the lending tables the commit wrote")
                .containsExactlyInAnyOrderElementsOf(WRITTEN_TABLES);
        for (String table : WRITTEN_TABLES) {
            assertThat(count(t, "SELECT count(*) FROM " + table + " WHERE tenant_id = ?"))
                    .as("the run wrote %s", table)
                    .isPositive();
            assertThat(count(
                            t,
                            "SELECT count(*) FROM " + table + " x WHERE x.tenant_id = ?"
                                    + " AND NOT EXISTS (SELECT 1 FROM import_refs r WHERE r.target_id = x.id)"))
                    .as("every row of %s is in import_refs", table)
                    .isZero();
        }
        // A repaid loan's item is released with its pledge, so it holds no reference back (13.12: one closed loan at
        // each branch).
        assertThat(count(
                        t,
                        "SELECT count(*) FROM lending_collateral_items WHERE tenant_id = ? AND custody_status"
                                + " = 'released'"))
                .isEqualTo(2);
        assertThat(count(
                        t,
                        "SELECT count(*) FROM lending_loan_collateral WHERE tenant_id = ? AND released_at IS NOT NULL"))
                .isEqualTo(2);
        ImportReconciliation.Report r = as(t, () -> reconciliation.latest(run)).orElseThrow();
        assertThat(r.signable()).as(r.render()).isTrue();
        assertThat(count(other, "SELECT count(*) FROM lending_members WHERE tenant_id = ?"))
                .isZero();
        String stored = TestDatabase.owner()
                .sql("SELECT dry_run_report::text FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
        for (String value : List.of("Test Borrower 01", "CMTEST0000001A", "700000001")) {
            assertThat(stored).as("the stored report names %s", value).doesNotContain(value);
        }
    }

    @Test
    void theRegisterAsAWorkbookGivesTheBooksTheCsvGives() throws IOException {
        Map<String, byte[]> xlsx = new LinkedHashMap<>();
        xlsx.put("register|pilot_loan_register_sample.xlsx", workbook(PILOT));
        UUID run = reviewed(t, officer, xlsx);
        committed(t, run);
        Map<String, byte[]> csv = new LinkedHashMap<>();
        csv.put("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT));
        UUID otherRun = reviewed(other, otherOfficer, csv);
        committed(other, otherRun);

        assertThat(state(t)).isNotEmpty().isEqualTo(state(other));
        assertThat(state(t))
                .contains("journal|HQ|loans_receivable|2810000|0", "journal|HQ|opening_balance_equity|0|2810000");
    }

    // ---------------------------------------------------------------------------------------------
    // A re-run, a resume, a business that already has members and loans, and the run's decision.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aReRunOfTheSameRegistersPostsNoSecondJournalAndWritesNothingTwice() throws IOException {
        committed(t, reviewed(t, officer, csvFiles()));
        List<String> before = state(t);

        UUID again = reviewed(t, officer, csvFiles());
        DryRun dry = as(t, () -> runs.dryRun(again, operator));
        assertThat(dry.report().journals()).as(dry.report().render()).isEmpty();
        committed(t, again);

        assertThat(state(t)).as("no duplicates and no second journal").isEqualTo(before);
        assertThat(count(t, "SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(2);
    }

    // JUSTIFICATION-A3: three new tests the review of a4c6bd9 asks for (must-fix 2 and 3), not new mechanism.
    @Test
    void aLaterRunAddingLoansToAnOpenedBranchPostsNoJournalAndAsksForACorrectingEntry() throws IOException {
        Map<String, byte[]> first = new LinkedHashMap<>();
        first.put("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT));
        committed(t, reviewed(t, officer, first));
        // New loans for the head office: the second branch's register, read by the head office's source.
        Map<String, byte[]> later = new LinkedHashMap<>();
        later.put("register|fab-register-branch-two.csv", Files.readAllBytes(BRANCH_TWO));
        UUID run = reviewed(t, officer, later);

        ImportPreview.Preview predicted = as(t, () -> preview.preview(run));
        assertThat(predicted.balances().journals())
                .as("the forecast predicts no second journal")
                .isEmpty();
        DryRun dry = as(t, () -> runs.dryRun(run, operator));
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        assertThat(dry.report().journals()).as(dry.report().render()).isEmpty();
        assertThat(dry.report().notes())
                .as(dry.report().render())
                .anySatisfy(n -> assertThat(n).contains("Branch HQ already has its import opening journal"));
        committed(t, run);

        assertThat(count(t, "SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(journalTotals(t)).isEqualTo(Map.of("HQ", "2810000|2810000"));
        // The book holds the new loans; the journal does not, which is what the correcting entry is for.
        assertThat(bookPrincipal(t)).isEqualTo(Map.of("HQ", 3_470_000L));
        assertThat(as(t, () -> runs.commitReport(run)).sameAs(dry.report())).isTrue();
    }

    @Test
    void anotherBusinessesMembersAreNeverMatched() throws IOException {
        // The other business holds the NIN of row 3 and the phone of row 5 (fabricated); this business holds neither.
        existingMemberWithLoan(other, "CMTEST0000001A", "+256700000001");
        member(other, "CFTEST0000099A", "+256700000003", "Test Other Business Member");
        UUID run = uploaded(t, officer, Map.of("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT)));

        assertThat(as(t, () -> codesOn(run, 3))).doesNotContain("MEMBER_MATCHED_EXISTING");
        assertThat(as(t, () -> codesOn(run, 5))).doesNotContain("MEMBER_PHONE_CONFLICT", "MEMBER_MATCHED_EXISTING");
        settle(t, run);
        committed(t, run);

        assertThat(count(t, "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND source = 'import'"))
                .as("rows 3 and 12 are one new member of this business")
                .isEqualTo(6);
        assertThat(count(
                        t,
                        "SELECT count(*) FROM lending_next_of_kin k WHERE k.tenant_id = ? AND k.linked_member_id IS"
                                + " NOT NULL AND NOT EXISTS (SELECT 1 FROM lending_members m WHERE m.id ="
                                + " k.linked_member_id AND m.tenant_id = k.tenant_id)"))
                .isZero();
        assertThat(count(other, "SELECT count(*) FROM lending_members WHERE tenant_id = ?"))
                .isEqualTo(2);
        assertThat(count(other, "SELECT count(*) FROM lending_loans WHERE tenant_id = ?"))
                .isEqualTo(1);
    }

    @Test
    void aPhoneAnotherMemberOfTheBusinessHoldsIsAConflict() throws IOException {
        // Row 5's phone belongs to a member of this business with another NIN (fabricated).
        UUID held = member(t, "CFTEST0000099A", "+256700000003", "Test Other Member");
        UUID run = uploaded(t, officer, Map.of("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT)));

        assertThat(as(t, () -> codesOn(run, 5)))
                .contains("MEMBER_PHONE_CONFLICT")
                .doesNotContain("MEMBER_MATCHED_EXISTING");
        assertThat(as(t, () -> codesOn(run, 3))).doesNotContain("MEMBER_PHONE_CONFLICT");

        // Issue #878 item 3: the conflict overridden (keep them apart) makes row 5's borrower a member of their own.
        settle(t, run);
        committed(t, run);
        assertThat(count(t, "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND member_id = '" + held + "'"))
                .isZero();
        String onPhone = "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND phone_e164 = '+256700000003'";
        assertThat(count(t, onPhone))
                .as("the member the business had, and row 5's new member")
                .isEqualTo(2);
        String ofRowFive = "SELECT count(*) FROM lending_loans l JOIN lending_members m ON m.id = l.member_id"
                + " WHERE l.tenant_id = ? AND m.national_id = 'CFTEST0000003A' AND m.source = 'import'";
        assertThat(count(t, ofRowFive)).isEqualTo(1);
        assertThat(count(t, "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND source = 'import'"))
                .isEqualTo(6);
    }

    // JUSTIFICATION-A3: the two tests round 2 of the review asks for (must-fix 1 and the Lead's must-fix 2).
    @Test
    void aBorrowerTheReviewLinksToAMemberHasAllTheirLoansOnThatMember() throws IOException {
        // Row 3's phone is a member of this business with another NIN (fabricated): a conflict the review links.
        // Row 12 is the same borrower as row 3 (13.12), not linked by the review.
        UUID linked = member(t, "CMTEST0000099A", "+256700000001", "Test Linked Member");
        UUID run = uploaded(t, officer, Map.of("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT)));
        Issue conflict = as(t, () -> issueOn(run, 3, "MEMBER_PHONE_CONFLICT"));
        assertThat(conflict).isNotNull();
        as(
                t,
                () -> review.resolve(
                        conflict.id(),
                        new ImportReview.Resolution(
                                "link_member", conflict.version(), null, null, linked, "Fabricated: confirmed"),
                        operator));
        settle(t, run);
        committed(t, run);

        assertThat(count(t, "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND member_id = '" + linked + "'"))
                .as("rows 3 and 12")
                .isEqualTo(2);
        assertThat(count(t, "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND source = 'import'"))
                .as("no new member for the linked borrower")
                .isEqualTo(5);
        assertThat(count(
                        t, "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND phone_e164 = '+256700000001'"))
                .isEqualTo(1);
    }

    // JUSTIFICATION-A3: the tests of issue #878 (items 1, 2, 5, 6 and 9), on PostgreSQL.
    /** Item 1: each file's next of kin is written as the review showed it, linked only to a borrower already a member. */
    @Test
    void aNextOfKinIsWrittenLinkedOnlyToABorrowerOfItsOwnOrAnEarlierFile() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("register|fab-kin-hq.csv", csv(LendingLoansV1TemplateTest.KIN_ACROSS_HQ));
        files.put("register_br2|fab-kin-br2.csv", csv(LendingLoansV1TemplateTest.KIN_ACROSS_BR2));
        UUID run = uploaded(t, officer, files);
        assertThat(as(t, () -> codesIn(run, "fab-kin-hq.csv", 2))).doesNotContain("KIN_IS_MEMBER", "KIN_PHONE_MATCH");
        assertThat(as(t, () -> codesIn(run, "fab-kin-br2.csv", 2))).contains("KIN_IS_MEMBER");
        settle(t, run);
        committed(t, run);

        assertThat(lines(
                        t,
                        "SELECT m.national_id || '|' || coalesce(l.national_id, '-') || '|' || k.link_status"
                                + " FROM lending_next_of_kin k JOIN lending_members m ON m.id = k.member_id"
                                + " LEFT JOIN lending_members l ON l.id = k.linked_member_id WHERE k.tenant_id = ?"))
                .containsExactlyInAnyOrder("CMTEST0000031A|-|none", "CFTEST0000032A|CMTEST0000031A|confirmed");
    }

    /**
     * Item 2: a link the review made in an earlier file carries to the same borrower (by NIN) in a later file, which the
     * review shows as that earlier line's borrower; so all three loans of borrower 01 are on the linked member.
     */
    @Test
    void aLinkOfAnEarlierFileCarriesToTheSameBorrowerInALaterFile() throws IOException {
        UUID linked = member(t, "CMTEST0000099A", "+256700000001", "Test Linked Member");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT));
        files.put("register_br2|fab-borrower-01.csv", csv(BORROWER_01_AT_BR2));
        UUID run = uploaded(t, officer, files);
        assertThat(as(t, () -> codesIn(run, "fab-borrower-01.csv", 2)))
                .contains("DUPLICATE_BORROWER")
                .doesNotContain("MEMBER_PHONE_CONFLICT");
        Issue conflict = as(t, () -> issueOn(run, 3, "MEMBER_PHONE_CONFLICT"));
        as(
                t,
                () -> review.resolve(
                        conflict.id(),
                        new ImportReview.Resolution(
                                "link_member", conflict.version(), null, null, linked, "Fabricated: confirmed"),
                        operator));
        settle(t, run);
        // The dry run carries the link as the commit does (Lead on #911): it predicts the preview and reports alike.
        DryRun dry = as(t, () -> runs.dryRun(run, operator));
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        committed(t, run);
        assertThat(as(t, () -> runs.commitReport(run)).sameAs(dry.report())).isTrue();

        assertThat(count(t, "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND member_id = '" + linked + "'"))
                .as("rows 3 and 12 of the head office, row 2 of the second branch")
                .isEqualTo(3);
        assertThat(count(t, "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND source = 'import'"))
                .isEqualTo(5);
    }

    /** Borrower 01 of the pilot register, borrowing at the second branch too (fabricated). */
    static final String BORROWER_01_AT_BR2 = "2026-05-10,Test Borrower 01,Test Village A,700000001,CMTEST0000001A,"
            + "MARRIED,Test Kin 01,700000051,CFTEST0000051A,WIFE,Test Village A,TRADER,POULTRY,STOCK,400000,0.2,1 Month,"
            + "2026-06-10,480000,LAND,,,480000,ACTIVE,";

    /**
     * Item 5: the staff lookup names the business, so the user of another business who opened a run is not its staff: the
     * remark is not kept as a collection note, and the reports of the dry run and the commit say so.
     */
    @Test
    void aRunOpenedByAUserOfAnotherBusinessKeepsNoRemarkAndSaysSo() throws IOException {
        Map<String, byte[]> files = Map.of("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT));
        UUID run = reviewed(t, otherOfficer, files);
        committed(t, run);

        assertThat(count(t, "SELECT count(*) FROM lending_collection_actions WHERE tenant_id = ?"))
                .isZero();
        assertThat(as(t, () -> runs.commitReport(run)).notes())
                .anySatisfy(n -> assertThat(n).startsWith("1 remark(s) of this file are not kept as collection notes"));
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().report().notes())
                .anySatisfy(n -> assertThat(n).startsWith("1 remark(s) of this file are not kept as collection notes"));
        assertThat(count(other, "SELECT count(*) FROM lending_collection_actions WHERE tenant_id = ?"))
                .isZero();

        // The positive control (Lead on #911): a staff user of the run's own business keeps the remark.
        UUID own = reviewed(other, otherOfficer, files);
        committed(other, own);
        assertThat(count(other, "SELECT count(*) FROM lending_collection_actions WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(as(other, () -> runs.commitReport(own)).notes())
                .noneSatisfy(n -> assertThat(n).contains("are not kept as collection notes"));
    }

    /**
     * Item 6: an overridden pay date before the loan's date is applied as at the cutover day by the one function
     * ({@code BringIn.apply}), and the journal holds what the book holds; a pay date after the cutover day is blocking, so
     * its row is left out and no payment after the cutover is ever written as historic.
     */
    @Test
    void anOverriddenPayDateIsAppliedAsAtTheCutoverAndOneAfterTheCutoverIsNeverWritten() throws IOException {
        UUID run = uploaded(
                t, officer, Map.of("register|fab-pay-dates.csv", csv(PAID_BEFORE_THE_LOAN, PAID_AFTER_THE_CUTOVER)));
        assertThat(as(t, () -> codesOn(run, 2))).contains("PAY_DATE_OUT_OF_RANGE");
        assertThat(as(t, () -> codesOn(run, 3))).contains("DATE_AFTER_CUTOVER").doesNotContain("PAY_DATE_OUT_OF_RANGE");
        settle(t, run);
        assertThat(as(t, () -> issueOn(run, 2, "PAY_DATE_OUT_OF_RANGE")).resolution())
                .isEqualTo("override");
        committed(t, run);

        // Interest first: 20,000 of interest and 30,000 of principal paid, 70,000 of principal owed at the cutover.
        assertThat(bookPrincipal(t)).isEqualTo(Map.of("HQ", 70_000L));
        assertThat(receivableLines(t)).isEqualTo(Map.of("HQ", 70_000L));
        assertThat(journalTotals(t)).isEqualTo(Map.of("HQ", "70000|70000"));
        assertThat(lines(t, TXN_STATE))
                .containsExactlyInAnyOrder("disbursement|100000|2026-03-16|true", "repayment|50000|2026-03-01|true");
        assertThat(count(
                        t,
                        "SELECT count(*) FROM lending_loan_transactions WHERE tenant_id = ?"
                                + " AND value_date > DATE '2026-06-30'"))
                .isZero();
    }

    /** Paid 50,000 on a date before the loan's date (fabricated). */
    static final String PAID_BEFORE_THE_LOAN = "2026-03-16,Test Borrower 41,Test Village V,700000041,CMTEST0000041A,"
            + "SINGLE,Test Kin 41,700000081,CFTEST0000081A,SISTER,Test Village V,TAILOR,NONE,STOCK,100000,0.2,1 Month,"
            + "2026-04-16,120000,ID,50000,2026-03-01,70000,ACTIVE,";

    /** Paid 100,000 after the cutover day (fabricated). */
    static final String PAID_AFTER_THE_CUTOVER = "2026-05-10,Test Borrower 42,Test Village W,700000042,CMTEST0000042A,"
            + "SINGLE,Test Kin 42,700000082,CFTEST0000082A,SISTER,Test Village W,FARMER,NONE,SEEDS,400000,0.2,1 Month,"
            + "2026-06-10,480000,ID,100000,2026-07-10,380000,ACTIVE,";

    /**
     * Item 9: a link_member made on a row is cleared from {@code import_rows} once the row changes and is resolved again
     * otherwise, and the commit writes no loan on that member; a run that is ready takes no file.
     */
    @Test
    void aLinkIsClearedWhenItsRowIsResolvedAgainAndAReadyRunTakesNoFile() throws IOException {
        UUID linked = member(t, "CMTEST0000099A", "+256700000001", "Test Linked Member");
        UUID run = uploaded(t, officer, Map.of("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT)));
        Issue conflict = as(t, () -> issueOn(run, 3, "MEMBER_PHONE_CONFLICT"));
        as(
                t,
                () -> review.resolve(
                        conflict.id(),
                        new ImportReview.Resolution(
                                "link_member", conflict.version(), null, null, linked, "Fabricated: confirmed"),
                        operator));
        assertThat(linkedMemberOn(run, 3)).isEqualTo(linked.toString());

        // JUSTIFICATION-A3: must-fix 1 of the review of #911, the same issue resolved again on the same cells.
        // The same issue cannot be resolved again on the same cells (409 issue_not_open), so no override can sit
        // beside a link it does not clear: the link stands until the row's cells change.
        Issue linkedIssue = as(t, () -> issueOn(run, 3, "MEMBER_PHONE_CONFLICT"));
        assertThat(linkedIssue.status()).isEqualTo("resolved");
        ApiException twice = null;
        try {
            as(
                    t,
                    () -> review.resolve(
                            linkedIssue.id(),
                            new ImportReview.Resolution(
                                    "override", linkedIssue.version(), null, null, null, "Fabricated: changed mind"),
                            operator));
        } catch (ApiException e) {
            twice = e;
        }
        assertThat(twice).isNotNull();
        assertThat(twice.code()).isEqualTo("issue_not_open");
        assertThat(linkedMemberOn(run, 3)).isEqualTo(linked.toString());

        // The customer sends the register again with line 3 changed: the link was made on other cells.
        String original = Files.readString(PILOT, StandardCharsets.UTF_8);
        String changed = original.replace(
                "2026-03-16,Test Borrower 01,Test Village A,", "2026-03-16,Test Borrower 01,Test Village Z,");
        assertThat(changed).isNotEqualTo(original);
        UUID first = as(t, () -> uploads.batches(run).getFirst().id());
        as(t, () -> uploads.cancelBatch(first));
        UUID mapping =
                as(t, () -> mappings.latest("pilot-register").orElseThrow().id());
        byte[] sentAgain = changed.getBytes(StandardCharsets.UTF_8);
        as(t, () -> upload(run, mapping, "register|pilot_loan_register_sample.csv", sentAgain));
        as(t, () -> validation.validate(run));
        assertThat(linkedMemberOn(run, 3)).isEqualTo("-");

        // Resolved again, the other way: the row as it now is, and the conflict overridden (keep them apart).
        Issue rowChanged = as(t, () -> issueOn(run, 3, "ROW_CHANGED_SINCE_REVIEW"));
        as(
                t,
                () -> review.resolve(
                        rowChanged.id(),
                        new ImportReview.Resolution(
                                "accept_suggestion", rowChanged.version(), null, null, null, "Fabricated: as sent"),
                        operator));
        Issue again = as(t, () -> issueOn(run, 3, "MEMBER_PHONE_CONFLICT"));
        assertThat(again.status()).isEqualTo("open");
        as(
                t,
                () -> review.resolve(
                        again.id(),
                        new ImportReview.Resolution(
                                "override", again.version(), null, null, null, "Fabricated: different people"),
                        operator));
        assertThat(linkedMemberOn(run, 3)).isEqualTo("-");
        settle(t, run);

        DryRun dry = as(t, () -> runs.dryRun(run, operator));
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        as(t, () -> runs.transition(run, "ready"));
        ApiException refused = null;
        byte[] late = csv(BORROWER_01_AT_BR2);
        try {
            as(t, () -> upload(run, mapping, "register|fab-late.csv", late));
        } catch (ApiException e) {
            refused = e;
        }
        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo("invalid_status_transition");
        assertThat(as(t, () -> uploads.batches(run)).stream().map(ImportUploads.Batch::fileName))
                .doesNotContain("fab-late.csv");

        as(t, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, t, runs, run, SHA);
        assertThat(as(t, () -> runs.commit(run))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);
        assertThat(count(t, "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND member_id = '" + linked + "'"))
                .isZero();
    }

    @Test
    void aRunWithTwoFilesOfOneBranchIsNotReadyUntilTheLaterFileIsLeftOut() throws IOException {
        // Both registers read by the head office's source: one branch in two files of one run.
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT));
        files.put("register|fab-register-branch-two.csv", Files.readAllBytes(BRANCH_TWO));
        UUID run = uploaded(t, officer, files);
        assertThat(count(
                        t,
                        "SELECT count(*) FROM import_issues WHERE tenant_id = ? AND code = 'BRANCH_IN_TWO_FILES'"
                                + " AND status = 'open' AND severity = 'blocking'"))
                .as("every row of the later file")
                .isEqualTo(3);
        settleExcept(t, run, "BRANCH_IN_TWO_FILES");
        decide(t, run);
        DryRun dry = as(t, () -> runs.dryRun(run, operator));
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();

        ApiException refused = null;
        try {
            as(t, () -> runs.transition(run, "ready"));
        } catch (ApiException e) {
            refused = e;
        }
        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo("BRANCH_IN_TWO_FILES");

        // Leaving the later file's rows out is the decision that makes it ready; the book and the journal then agree.
        settleWithoutDecision(t, run);
        committed(t, run);
        assertThat(journalTotals(t)).isEqualTo(Map.of("HQ", "2810000|2810000"));
        assertThat(bookPrincipal(t)).isEqualTo(Map.of("HQ", 2_810_000L));
    }

    // JUSTIFICATION-A3: the Lead's nice-to-have 1 on the review of a4c6bd9, the member credit journal end to end.
    @Test
    void anOverpaidLoanGivesItsBranchAMemberCreditJournal() throws IOException {
        // Row 2 paid 400,000 on a return of 360,000: a credit of 40,000; row 3 still owes its 200,000.
        UUID run = reviewed(t, officer, Map.of("register|fab-register-overpaid.csv", Files.readAllBytes(OVERPAID)));
        ImportPreview.Preview predicted = as(t, () -> preview.preview(run));
        committed(t, run);

        assertThat(lines(t, JOURNAL_STATE))
                .containsExactlyInAnyOrder(
                        "HQ|loans_receivable|200000|0",
                        "HQ|member_overpayments|0|40000",
                        "HQ|opening_balance_equity|40000|200000");
        assertThat(lines(
                        t,
                        "SELECT l.status || '|' || l.principal_outstanding_minor || '|' || l.credit_balance_minor"
                                + " FROM lending_loans l WHERE l.tenant_id = ?"))
                .containsExactlyInAnyOrder("closed|0|40000", "active|200000|0");
        Map<String, String> byKind = new TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT r.source_key, sum(l.debit), sum(l.credit) FROM import_refs r
                          JOIN journal_lines l ON l.entry_id = r.target_id
                         WHERE r.tenant_id = ? AND r.target_type = 'ledger.journal_entry'
                         GROUP BY r.source_key
                        """)
                .param(t.tenantId())
                .query((rs, n) -> byKind.put(
                        rs.getString(1).contains("member_credit") ? "member_credit" : "loans_receivable",
                        rs.getLong(2) + "|" + rs.getLong(3)))
                .list();
        assertThat(byKind).isEqualTo(Map.of("loans_receivable", "200000|200000", "member_credit", "40000|40000"));
        CommitReport real = as(t, () -> runs.commitReport(run));
        assertThat(real.journals()).hasSize(2);
        for (CommitReport.Journal j : real.journals()) {
            assertThat(j.debitMinor() + "|" + j.creditMinor()).isEqualTo(byKind.get(j.kind()));
        }
        assertThat(predicted.balances().journals()).hasSize(2);
        for (ImportForecast.Journal j : predicted.balances().journals()) {
            assertThat(j.debitMinor() + "|" + j.creditMinor()).isEqualTo(byKind.get(j.kind()));
        }
    }

    @Test
    void aRunKilledAfterItsFirstBatchResumesWithNoDuplicateAndNoSecondJournal() throws IOException {
        UUID run = reviewed(t, officer, csvFiles());
        DryRun dry = as(t, () -> runs.dryRun(run, operator));
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        as(t, () -> runs.transition(run, "ready"));
        as(t, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, t, runs, run, SHA);
        seams.killAfter = 1;
        assertThatThrownBy(() -> as(t, () -> runs.commit(run))).isInstanceOf(CommitTestSeams.Killed.class);

        assertThat(as(t, () -> runs.get(run)).status()).isEqualTo("committing");
        assertThat(count(t, "SELECT count(*) FROM lending_loans WHERE tenant_id = ?"))
                .isEqualTo(7);
        assertThat(count(t, "SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(1);

        assertThat(as(t, () -> runs.resume(run))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);

        assertThat(count(t, "SELECT count(*) FROM lending_loans WHERE tenant_id = ?"))
                .isEqualTo(10);
        assertThat(count(t, "SELECT count(*) FROM lending_members WHERE tenant_id = ?"))
                .isEqualTo(9);
        assertThat(journalTotals(t)).isEqualTo(Map.of("HQ", "2810000|2810000", "BR2", "660000|660000"));
        assertThat(count(
                        t,
                        "SELECT count(*) - count(DISTINCT (source_ref, target_type, target_id)) FROM import_refs"
                                + " WHERE tenant_id = ?"))
                .isZero();
        assertThat(as(t, () -> runs.commitReport(run)).sameAs(dry.report())).isTrue();
    }

    @Test
    void aBusinessWithMembersAndLoansKeepsThemAndHasItsBorrowersLinked() throws IOException {
        UUID existing = existingMemberWithLoan(t, "CMTEST0000001A", "+256700000001");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT));
        UUID run = uploaded(t, officer, files);
        assertThat(as(t, () -> codesOn(run, 3))).contains("MEMBER_MATCHED_EXISTING");
        assertThat(as(t, () -> codesOn(run, 4))).contains("KIN_IS_MEMBER");
        settle(t, run);
        committed(t, run);

        assertThat(count(t, "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND source = 'import'"))
                .as("rows 3 and 12 are the member the business has")
                .isEqualTo(5);
        assertThat(TestDatabase.owner()
                        .sql("SELECT full_name FROM lending_members WHERE id = ?")
                        .param(existing)
                        .query(String.class)
                        .single())
                .as("an existing member's details are never changed")
                .isEqualTo("Test Existing Member");
        assertThat(count(
                        t, "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND member_id = '" + existing + "'"))
                .isEqualTo(3);
        assertThat(count(
                        t,
                        "SELECT count(*) FROM lending_next_of_kin WHERE tenant_id = ? AND linked_member_id = '"
                                + existing + "'"))
                .isEqualTo(1);
        // The business's own loan is not in the opening journal: only the imported loans are.
        assertThat(journalTotals(t)).isEqualTo(Map.of("HQ", "2810000|2810000"));
        assertThat(count(
                        t,
                        "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND loan_no = 'LIVE-1'"
                                + " AND principal_outstanding_minor = 700000"))
                .isEqualTo(1);
    }

    @Test
    void theRunIsNotReadyWithoutTheDecisionThatTheRequestedPrincipalWasDisbursed() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT));
        UUID run = uploaded(t, officer, files);
        settleWithoutDecision(t, run);
        DryRun dry = as(t, () -> runs.dryRun(run, operator));
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();

        ApiException refused = null;
        try {
            as(t, () -> runs.transition(run, "ready"));
        } catch (ApiException e) {
            refused = e;
        }
        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo("DISBURSEMENT_DECISION_MISSING");
    }

    // ---------------------------------------------------------------------------------------------
    // The run.
    // ---------------------------------------------------------------------------------------------

    /** The pilot register at the head office and the second branch's register, by mapping source and file name. */
    static Map<String, byte[]> csvFiles() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("register|pilot_loan_register_sample.csv", Files.readAllBytes(PILOT));
        files.put("register_br2|fab-register-branch-two.csv", Files.readAllBytes(BRANCH_TWO));
        return files;
    }

    /** A run opened by the loans officer, its cutover the 13.12 business date, every file uploaded and validated. */
    UUID uploaded(TestDatabase.Fixture f, UUID opener, Map<String, byte[]> files) throws IOException {
        Map<String, Object> document = JSON.readValue(Files.readString(MAPPING), new TypeReference<>() {});
        return as(f, () -> {
            UUID mapping = mappings.latest("pilot-register")
                    .orElseGet(() -> mappings.save("pilot-register", document, operator, null))
                    .id();
            UUID run = runs.open(opener).id();
            TestDatabase.owner()
                    .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                    .params(Timestamp.from(CUTOVER), run)
                    .update();
            files.forEach((key, content) -> {
                ImportUploads.Batch batch = upload(run, mapping, key, content);
                assertThat(batch.status()).as(key).isEqualTo("parsed");
            });
            validation.validate(run);
            return run;
        });
    }

    // JUSTIFICATION-A3: issue #878's tests need a file uploaded on its own, inline registers and per-file codes.
    /** One file into the run, by {@code source|file name}, read with the mapping's source, in the caller's tenant. */
    ImportUploads.Batch upload(UUID run, UUID mapping, String key, byte[] content) {
        String source = key.substring(0, key.indexOf('|'));
        String name = key.substring(key.indexOf('|') + 1);
        return uploads.upload(
                run,
                new Upload(
                        name,
                        new ByteArrayInputStream(content),
                        content.length,
                        operator,
                        mappings.parseOptions(mapping, source)));
    }

    /** A register of the pilot register's columns from data lines (fabricated). */
    static byte[] csv(String... lines) throws IOException {
        String header = Files.readAllLines(PILOT, StandardCharsets.UTF_8).getFirst();
        return (header + "\n" + String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The codes of a line of one file of the run. */
    List<String> codesIn(UUID run, String file, int line) {
        List<String> out = new ArrayList<>();
        for (ImportUploads.Batch b : uploads.batches(run)) {
            if (file.equals(b.fileName())) {
                validation.issues(b.id()).stream().filter(i -> i.line() == line).forEach(i -> out.add(i.code()));
            }
        }
        return out;
    }

    /** The member the review linked a line of the run's live batches to, from {@code import_rows}, or {@code -}. */
    static String linkedMemberOn(UUID run, int line) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(r.linked_member_id::text, '-') FROM import_rows r
                          JOIN import_batches b ON b.id = r.batch_id
                         WHERE b.run_id = ? AND b.status <> 'cancelled' AND r.source_row_no = ?
                        """)
                .params(run, line)
                .query(String.class)
                .single();
    }

    /** Uploaded and reviewed with the standard resolutions of 13.12, the decision recorded. */
    UUID reviewed(TestDatabase.Fixture f, UUID opener, Map<String, byte[]> files) throws IOException {
        UUID run = uploaded(f, opener, files);
        settle(f, run);
        return run;
    }

    void settle(TestDatabase.Fixture f, UUID run) {
        settleWithoutDecision(f, run);
        decide(f, run);
    }

    /** The decision of 13.8 row 15, recorded with the customer's confirmation (fabricated). */
    void decide(TestDatabase.Fixture f, UUID run) {
        as(
                f,
                () -> runs.recordPolicy(
                        run,
                        LendingLoansV1Template.DISBURSED,
                        new PolicyDecision(
                                LendingLoansV1Template.REQUESTED_AS_DISBURSED,
                                LocalDate.now(),
                                "Question list item 1, signed",
                                operator,
                                null,
                                null)));
    }

    /**
     * The standard resolutions of 13.12: a suggestion is accepted (rows 4, 6 and 11), a blocking issue without one leaves
     * its row out (rows 7, 9 and 13), any other warning is overridden with a note.
     */
    void settleWithoutDecision(TestDatabase.Fixture f, UUID run) {
        settleExcept(f, run, null);
    }

    /** The standard resolutions, leaving the issues of one code open. */
    void settleExcept(TestDatabase.Fixture f, UUID run, String kept) {
        for (int pass = 0; pass < 100; pass++) {
            Issue next = as(f, () -> openIssue(run, kept));
            if (next == null) {
                return;
            }
            boolean suggested = next.suggestion() != null && next.suggestion().containsKey("value");
            String kind =
                    suggested ? "accept_suggestion" : "blocking".equals(next.severity()) ? "exclude_row" : "override";
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

    Issue openIssue(UUID run, String kept) {
        for (ImportUploads.Batch b : uploads.batches(run)) {
            if ("cancelled".equals(b.status())) {
                continue;
            }
            for (Issue i : validation.issues(b.id())) {
                if ("open".equals(i.status()) && !i.code().equals(kept)) {
                    return i;
                }
            }
        }
        return null;
    }

    Issue issueOn(UUID run, int line, String code) {
        for (ImportUploads.Batch b : uploads.batches(run)) {
            if ("cancelled".equals(b.status())) {
                continue;
            }
            for (Issue i : validation.issues(b.id())) {
                if (i.line() == line && i.code().equals(code)) {
                    return i;
                }
            }
        }
        return null;
    }

    List<String> codesOn(UUID run, int line) {
        List<String> out = new ArrayList<>();
        for (ImportUploads.Batch b : uploads.batches(run)) {
            for (Issue i : validation.issues(b.id())) {
                if (i.line() == line) {
                    out.add(i.code());
                }
            }
        }
        return out;
    }

    /** From a dry run that predicts the preview to the commit: ready, the verified dump, maker and checker. */
    void committed(TestDatabase.Fixture f, UUID run) {
        DryRun dry = as(f, () -> runs.dryRun(run, operator));
        assertThat(dry.report().failure()).as(dry.report().render()).isNull();
        assertThat(dry.previewMatches()).as(dry.report().render()).isTrue();
        as(f, () -> runs.transition(run, "ready"));
        as(f, () -> runs.recordPreImportDump(run, "pre-import-fabricated.dump", Instant.now(), SHA));
        CommitApproval.readyToCommit(http, f, runs, run, SHA);
        assertThat(as(f, () -> runs.commit(run))).isEqualTo(ImportRuns.CommitOutcome.COMMITTED);
    }

    // ---------------------------------------------------------------------------------------------
    // The business and its books, read from the tables.
    // ---------------------------------------------------------------------------------------------

    /** The fabricated loan product TEST-REG the mapping names: flat, per term, bullet, interest before principal. */
    static void product(TestDatabase.Fixture f, UUID user) {
        UUID product = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO lending_loan_products (id, tenant_id, code, name, status)"
                        + " VALUES (?, ?, 'TEST-REG', 'Test Register Product', 'active')")
                .params(product, f.tenantId())
                .update();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_loan_product_versions (id, tenant_id, product_id, version_no, currency,
                            interest_method, interest_rate_bp, rate_unit, term_unit, min_term_count, max_term_count,
                            default_term_count, repayment_pattern, min_principal_minor, max_principal_minor, created_by)
                        VALUES (?, ?, ?, 1, 'UGX', 'flat', 2000, 'per_term', 'month', 1, 12, 1, 'bullet', 1000, 100000000, ?)
                        """)
                .params(version, f.tenantId(), product, user)
                .update();
        TestDatabase.owner()
                .sql("UPDATE lending_loan_products SET current_version_id = ? WHERE id = ?")
                .params(version, product)
                .update();
    }

    /** A member the business already has (fabricated), with no loan. */
    static UUID member(TestDatabase.Fixture f, String nin, String phone, String name) {
        UUID member = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                                     national_id, currency, kyc_status, status, source)
                        VALUES (?, ?, ?, 'LIVE-M2', ?, ?, 'nin', ?, 'UGX', 'incomplete', 'active', 'staff')
                        """)
                .params(member, f.tenantId(), f.headOffice(), name, phone, nin)
                .update();
        return member;
    }

    /** A member the business already has (fabricated) and one loan of theirs with no transaction yet. */
    static UUID existingMemberWithLoan(TestDatabase.Fixture f, String nin, String phone) {
        UUID member = UUID.randomUUID();
        var owner = TestDatabase.owner();
        owner.sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                                     national_id, currency, kyc_status, status, source)
                        VALUES (?, ?, ?, 'LIVE-M1', 'Test Existing Member', ?, 'nin', ?, 'UGX', 'incomplete', 'active',
                                'staff')
                        """).params(member, f.tenantId(), f.headOffice(), phone, nin).update();
        owner.sql("""
                        INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                            officer_user_id, status, channel, purpose_category, currency, requested_principal_minor,
                            requested_term_count, term_unit, interest_method, interest_rate_bp, rate_unit, repayment_pattern,
                            disbursed_on, principal_disbursed_minor, principal_outstanding_minor, maturity_date)
                        SELECT ?, ?, ?, 'LIVE-1', ?, p.current_version_id, ?, 'active', 'staff', 'business', 'UGX', 700000,
                               1, 'month', 'flat', 2000, 'per_term', 'bullet', DATE '2026-05-02', 700000, 700000,
                               DATE '2026-06-02'
                          FROM lending_loan_products p WHERE p.tenant_id = ? AND p.code = 'TEST-REG'
                        """)
                .params(UUID.randomUUID(), f.tenantId(), f.headOffice(), member, UUID.randomUUID(), f.tenantId())
                .update();
        return member;
    }

    /** Principal outstanding of the business's imported loans per branch, from {@code lending_loans}. */
    static Map<String, Long> bookPrincipal(TestDatabase.Fixture f) {
        Map<String, Long> out = new TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT b.code, sum(l.principal_outstanding_minor) FROM lending_loans l
                          JOIN branches b ON b.id = l.branch_id
                         WHERE l.tenant_id = ? AND l.channel = 'import' GROUP BY b.code
                        """)
                .param(f.tenantId())
                .query((rs, n) -> out.put(rs.getString(1), rs.getLong(2)))
                .list();
        return out;
    }

    /** The debit lines on {@code loans_receivable} per branch, from {@code journal_lines}. */
    static Map<String, Long> receivableLines(TestDatabase.Fixture f) {
        Map<String, Long> out = new TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT b.code, sum(l.debit) - sum(l.credit) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN branches b ON b.id = e.branch_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'loans_receivable' GROUP BY b.code
                        """)
                .param(f.tenantId())
                .query((rs, n) -> out.put(rs.getString(1), rs.getLong(2)))
                .list();
        return out;
    }

    /** Each branch's journal debit and credit totals, from {@code journal_lines}. */
    static Map<String, String> journalTotals(TestDatabase.Fixture f) {
        Map<String, String> out = new TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT b.code, sum(l.debit), sum(l.credit) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN branches b ON b.id = e.branch_id
                         WHERE l.tenant_id = ? GROUP BY b.code
                        """)
                .param(f.tenantId())
                .query((rs, n) -> out.put(rs.getString(1), rs.getLong(2) + "|" + rs.getLong(3)))
                .list();
        return out;
    }

    /** Loans and principal per branch and days-past-due bucket as at the cutover day, from {@code lending_loans}. */
    static Map<String, String> bookBuckets(TestDatabase.Fixture f) {
        Map<String, long[]> sums = new TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT b.code, l.days_past_due, l.principal_outstanding_minor FROM lending_loans l
                          JOIN branches b ON b.id = l.branch_id
                         WHERE l.tenant_id = ? AND l.channel = 'import' AND l.principal_outstanding_minor > 0
                        """)
                .param(f.tenantId())
                .query((rs, n) -> {
                    long[] s = sums.computeIfAbsent(
                            rs.getString(1) + "|" + LoanRegister.bucket(rs.getInt(2)), k -> new long[2]);
                    s[0]++;
                    s[1] += rs.getLong(3);
                    return null;
                })
                .list();
        Map<String, String> out = new TreeMap<>();
        sums.forEach((k, s) -> out.put(k, s[0] + "|" + s[1]));
        return out;
    }

    static Map<String, Long> sumByBranch(List<ImportForecast.Amount> amounts) {
        Map<String, Long> out = new TreeMap<>();
        amounts.forEach(a -> out.merge(a.branch(), a.amountMinor(), Long::sum));
        return out;
    }

    static Map<String, String> buckets(List<ImportForecast.ArrearsBucket> arrears) {
        Map<String, String> out = new TreeMap<>();
        arrears.forEach(a -> out.put(a.branch() + "|" + a.bucket(), a.loans() + "|" + a.principalMinor()));
        return out;
    }

    /** A register as a workbook (13.12): ISO dates as date cells, plain numbers as number cells, the rest as text. */
    static byte[] workbook(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        List<List<Object>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            List<Object> cells = new ArrayList<>();
            for (String c : EngineRun.cells(line)) {
                if (c.isEmpty()) {
                    cells.add(null);
                } else if (c.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                    cells.add(LocalDate.parse(c));
                } else if (c.matches("^(0|[1-9]\\d*)(\\.\\d+)?$")) {
                    cells.add(new BigDecimal(c));
                } else {
                    cells.add(c);
                }
            }
            rows.add(cells);
        }
        return TestFiles.xlsx(EngineRun.cells(lines.getFirst()), rows);
    }

    /** What the business holds after an import, as sorted lines without ids or times of writing. */
    static List<String> state(TestDatabase.Fixture f) {
        List<String> out = new ArrayList<>();
        add(out, f, "member", MEMBER_STATE);
        add(out, f, "kin", KIN_STATE);
        add(out, f, "collateral", COLLATERAL_STATE);
        add(out, f, "loan", LOAN_STATE);
        add(out, f, "txn", TXN_STATE);
        add(out, f, "journal", JOURNAL_STATE);
        return out.stream().sorted().toList();
    }

    private static void add(List<String> out, TestDatabase.Fixture f, String kind, String sql) {
        lines(f, sql).forEach(l -> out.add(kind + "|" + l));
    }

    static List<String> lines(TestDatabase.Fixture f, String sql) {
        return TestDatabase.owner()
                .sql(sql)
                .param(f.tenantId())
                .query(String.class)
                .list();
    }

    static long count(TestDatabase.Fixture f, String sql) {
        return TestDatabase.owner()
                .sql(sql)
                .param(f.tenantId())
                .query(Long.class)
                .single();
    }

    // JUSTIFICATION-A3: the state queries of the workbook test, as text blocks the formatter keeps as written.
    private static final String MEMBER_STATE = """
            SELECT member_no || '|' || full_name || '|' || phone_e164 || '|' || id_type || '|'
                   || coalesce(national_id, '-') || '|' || marital_status
              FROM lending_members WHERE tenant_id = ?
            """;

    private static final String KIN_STATE = """
            SELECT full_name || '|' || coalesce(phone_e164, '-') || '|' || relationship || '|' || link_status
              FROM lending_next_of_kin WHERE tenant_id = ?
            """;

    private static final String COLLATERAL_STATE = """
            SELECT collateral_type || '|' || coalesce(reference_no_normalised, '-')
              FROM lending_collateral_items WHERE tenant_id = ?
            """;

    private static final String LOAN_STATE = """
            SELECT l.loan_no || '|' || b.code || '|' || l.status || '|' || l.requested_principal_minor || '|'
                   || l.interest_rate_bp || '|' || l.approved_term_count || l.term_unit || '|' || l.disbursed_on
                   || '|' || l.maturity_date || '|' || l.principal_outstanding_minor || '|'
                   || l.interest_outstanding_minor || '|' || l.days_past_due || '|' || l.credit_balance_minor
              FROM lending_loans l JOIN branches b ON b.id = l.branch_id WHERE l.tenant_id = ?
            """;

    private static final String TXN_STATE = """
            SELECT t.txn_type || '|' || t.amount_minor || '|' || t.value_date || '|' || t.is_historic
              FROM lending_loan_transactions t WHERE t.tenant_id = ?
            """;

    private static final String JOURNAL_STATE = """
            SELECT b.code || '|' || a.system_key || '|' || sum(l.debit) || '|' || sum(l.credit)
              FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
              JOIN gl_accounts a ON a.id = l.account_id JOIN branches b ON b.id = e.branch_id
             WHERE l.tenant_id = ? GROUP BY b.code, a.system_key
            """;
}
