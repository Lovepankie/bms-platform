package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Sheet;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The collections reports of extras slice D3 (issue #222; chapter 14 sections 14.3 and 14.4; FR-CLN-03, FR-CLN-06,
 * FR-RPT-05): {@code lending.expected_vs_collected}, {@code lending.arrears_list}, {@code lending.disbursements} and
 * {@code lending.officer_performance}. Golden figures on a fabricated book, written as numbers computed by hand; the
 * collections equation; the cross check against the insights page for the same range; the officer and branch scope; and
 * the PDF and the workbook.
 *
 * <p>The range is 1 to 8 October 2026 and the business date the 8th (the movable clock of {@link TestReports}). Every
 * name is fabricated.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT30S",
            "bms.reports.xlsx-row-cap=500",
            "bms.reports.pdf-row-cap=200",
            "bms.reports.max-file-bytes=1048576",
            "bms.reports.cancel-poll=PT0.2S",
            "bms.reports.heartbeat=PT0.1S",
            "bms.reports.runner-enabled=false"
        })
class LendingCollectionsReportsIT extends LendingBookSupport {

    static final String EVC = "lending.expected_vs_collected";
    static final String ARREARS = "lending.arrears_list";
    static final String DISBURSEMENTS = "lending.disbursements";
    static final String OFFICERS = "lending.officer_performance";
    static final String COLLECTIONS = "lending.reports.collections";
    static final String ALL = COLLECTIONS + "," + PORTFOLIO
            + ",lending.insights.read,lending.insights.all_officers,lending.members.read,lending.members.contact";
    static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    static final Map<String, Object> RANGE = Map.of("from", "2026-10-01", "to", "2026-10-08");

    /** The fabricated book of these tests. */
    record Flows(TestDatabase.Fixture t, UUID amber, UUID birch) {}

    /**
     * Head Office, officer Amber:
     *
     * <ul>
     *   <li>CL01: item 1 due 20 Sep (100,000 principal, 10,000 interest, 5,000 penalty), item 2 due 5 Oct (100,000,
     *       10,000, 5,000 fees), item 3 due 5 Nov (100,000, 10,000). One repayment of 300,000 on 3 Oct: 5,000 penalty and
     *       110,000 to item 1 (arrears recovered), 115,000 to item 2 (due in the range), 70,000 to item 3 (prepaid).
     *   <li>WO01: written off on 1 Sep; a recovery of 30,000 on 2 Oct.
     *   <li>DS01: disbursed 1,000,000 on 2 Oct with an upfront fee of 20,000; DS03: 600,000 on 7 Oct, fee 12,000.
     * </ul>
     *
     * Test Branch Two, officer Birch:
     *
     * <ul>
     *   <li>CL02: one item due 6 Oct (200,000 principal, 20,000 interest). 50,000 paid on 28 Sep (before the range);
     *       230,000 on 7 Oct: 150,000 to the item, 80,000 overpaid; 40,000 on 4 Oct, reversed on 5 Oct. 20,000 principal
     *       is still owed: 2 days late.
     *   <li>LT01: 300,000 due 1 Aug, nothing paid that stands: 68 days late. 10,000 paid on 3 Oct was reversed on 7 Oct.
     *       A call on 1 Oct, a promise made on 2 Oct to pay 100,000 on 10 Oct (open), and an earlier promise broken on 1
     *       Oct.
     *   <li>DS02: 400,000 on 6 Oct; DS04: 500,000 on 4 Oct, reversed, so the loan is cancelled and was never paid out.
     * </ul>
     *
     * Amber also visited CL01 on 2 Oct, and CL01's promise of 25 Sep was kept on 3 Oct.
     */
    Flows flows() {
        TestDatabase.Fixture t = tenant(true);
        UUID amber = user(t, "Test Officer Amber");
        UUID birch = user(t, "Test Officer Birch");
        UUID product = product(t, amber);
        UUID ho = t.headOffice();
        UUID two = t.secondBranch();

        UUID cl01 = loanRow(t, product, ho, amber, "CL01", "active", 300_000, LocalDate.of(2026, 1, 5));
        UUID c1i1 = item(t, cl01, 1, LocalDate.of(2026, 9, 20), 100_000, 10_000, 0, 5_000, 100_000, 10_000, 0, 5_000);
        UUID c1i2 = item(t, cl01, 2, LocalDate.of(2026, 10, 5), 100_000, 10_000, 5_000, 0, 100_000, 10_000, 5_000, 0);
        UUID c1i3 = item(t, cl01, 3, LocalDate.of(2026, 11, 5), 100_000, 10_000, 0, 0, 60_000, 10_000, 0, 0);
        UUID r1 = txn(t, cl01, ho, "repayment", 300_000, LocalDate.of(2026, 10, 3), null);
        alloc(t, r1, r1, c1i1, "penalty", 5_000);
        alloc(t, r1, r1, c1i1, "interest", 10_000);
        alloc(t, r1, r1, c1i1, "principal", 100_000);
        alloc(t, r1, r1, c1i2, "fee", 5_000);
        alloc(t, r1, r1, c1i2, "interest", 10_000);
        alloc(t, r1, r1, c1i2, "principal", 100_000);
        alloc(t, r1, r1, c1i3, "interest", 10_000);
        alloc(t, r1, r1, c1i3, "principal", 60_000);

        UUID wo01 = loanRow(t, product, ho, amber, "WO01", "written_off", 200_000, LocalDate.of(2026, 1, 5));
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET written_off_on = DATE '2026-09-01' WHERE id = ?")
                .param(wo01)
                .update();
        item(t, wo01, 1, LocalDate.of(2026, 5, 1), 200_000, 0, 0, 0, 0, 0, 0, 0);
        txn(t, wo01, ho, "recovery", 30_000, LocalDate.of(2026, 10, 2), null);

        disbursed(t, product, ho, amber, "DS01", 1_000_000, 20_000, LocalDate.of(2026, 10, 2), false);
        disbursed(t, product, ho, amber, "DS03", 600_000, 12_000, LocalDate.of(2026, 10, 7), false);

        UUID cl02 = loanRow(t, product, two, birch, "CL02", "active", 200_000, LocalDate.of(2026, 1, 5));
        UUID c2i1 = item(t, cl02, 1, LocalDate.of(2026, 10, 6), 200_000, 20_000, 0, 0, 180_000, 20_000, 0, 0);
        UUID r2 = txn(t, cl02, two, "repayment", 50_000, LocalDate.of(2026, 9, 28), null);
        alloc(t, r2, r2, c2i1, "principal", 50_000);
        UUID r3 = txn(t, cl02, two, "repayment", 230_000, LocalDate.of(2026, 10, 7), null);
        alloc(t, r3, r3, c2i1, "interest", 20_000);
        alloc(t, r3, r3, c2i1, "principal", 130_000);
        alloc(t, r3, r3, null, "overpayment", 80_000);
        UUID r4 = txn(t, cl02, two, "repayment", 40_000, LocalDate.of(2026, 10, 4), null);
        alloc(t, r4, r4, c2i1, "principal", 40_000);
        UUID r4r = txn(t, cl02, two, "reversal", 40_000, LocalDate.of(2026, 10, 5), r4);
        alloc(t, r4r, r4, c2i1, "principal", -40_000);

        UUID lt01 = loanRow(t, product, two, birch, "LT01", "active", 300_000, LocalDate.of(2026, 1, 5));
        UUID l1i1 = item(t, lt01, 1, LocalDate.of(2026, 8, 1), 300_000, 0, 0, 0, 0, 0, 0, 0);
        // 10,000 paid on 3 Oct and reversed on 7 Oct: on the 6th it stood, today it does not.
        UUID r5 = txn(t, lt01, two, "repayment", 10_000, LocalDate.of(2026, 10, 3), null);
        alloc(t, r5, r5, l1i1, "principal", 10_000);
        UUID r5r = txn(t, lt01, two, "reversal", 10_000, LocalDate.of(2026, 10, 7), r5);
        alloc(t, r5r, r5, l1i1, "principal", -10_000);
        action(t, lt01, birch, "call", "2026-10-01T10:00:00+03:00", null, null, null, null);
        action(t, lt01, birch, "promise_to_pay", "2026-10-02T10:00:00+03:00", "2026-10-10", 100_000L, "pending", null);
        action(
                t,
                lt01,
                birch,
                "promise_to_pay",
                "2026-09-10T10:00:00+03:00",
                "2026-09-20",
                50_000L,
                "broken",
                "2026-10-01");
        action(
                t,
                cl01,
                amber,
                "promise_to_pay",
                "2026-09-25T10:00:00+03:00",
                "2026-10-03",
                300_000L,
                "kept",
                "2026-10-03");
        action(t, cl01, amber, "visit", "2026-10-02T15:00:00+03:00", null, null, null, null);

        disbursed(t, product, two, birch, "DS02", 400_000, 0, LocalDate.of(2026, 10, 6), false);
        disbursed(t, product, two, birch, "DS04", 500_000, 0, LocalDate.of(2026, 10, 4), true);
        return new Flows(t, amber, birch);
    }

    // ---- expected against collected -------------------------------------------------------------------

    @Test
    void expectedAgainstCollectedIsTheHandComputedSumAndTheTotalCollectedEqualsItsParts() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        JsonNode file = file(f, user, ALL, run(f, user, ALL, EVC, with(RANGE, "group_by", "branch"), "json"));

        assertThat(file.get("parameters").get("from").asString()).isEqualTo("2026-10-01");
        assertThat(file.get("parameters").get("to").asString()).isEqualTo("2026-10-08");
        assertThat(file.get("as_of").asString()).isEqualTo("2026-10-08");
        // Group, expected, collected on due, rate, paid in range on due, arrears recovered, prepayments, penalties,
        // overpayments, recoveries, total collected.
        // Head Office: due 115,000 (CL01 item 2), all paid; 110,000 of item 1, 70,000 of item 3, 5,000 penalty and the
        // 30,000 recovery: 330,000. Test Branch Two: due 220,000; 50,000 before the range and 150,000 in it are on due
        // (200,000, 90.9 percent); 80,000 overpaid; 230,000 collected (the reversed 40,000 is not).
        assertThat(rows(table(file, "collections")))
                .containsExactly(
                        "[\"Head Office\",115000,115000,10000,115000,110000,70000,5000,0,30000,330000]",
                        "[\"Test Branch Two\",220000,200000,9091,150000,0,0,0,80000,0,230000]",
                        "[\"Total\",335000,315000,9403,265000,110000,70000,5000,80000,30000,560000]");
        // The collections equation of chapter 14 section 14.4: the parts add up to the total, nothing unexplained.
        assertThat(rows(table(file, "equation")))
                .containsExactly(
                        "[\"Paid in the range on items due in the range\",265000]",
                        "[\"Plus arrears recovered (items due before the range)\",110000]",
                        "[\"Plus prepayments (items due after the range)\",70000]",
                        "[\"Plus penalties collected\",5000]",
                        "[\"Plus overpayments\",80000]",
                        "[\"Plus recoveries on loans written off\",30000]",
                        "[\"Equals total collected\",560000]");
        assertThat(265_000 + 110_000 + 70_000 + 5_000 + 80_000 + 30_000).isEqualTo(560_000);
    }

    @Test
    void theTotalsEqualTheInsightsPageForTheSameRangeInEveryGrouping() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        JsonNode page = insights(f, user, ALL, null);
        for (String by : List.of("none", "branch", "product", "officer")) {
            JsonNode file = file(f, user, ALL, run(f, user, ALL, EVC, with(RANGE, "group_by", by), "json"));
            JsonNode total = last(table(file, "collections"));
            assertThat(total.get(1).asLong()).as(by).isEqualTo(metric(page, "portfolio.expected"));
            assertThat(total.get(2).asLong()).as(by).isEqualTo(metric(page, "portfolio.collected_on_due"));
            assertThat(total.get(3).asLong()).as(by).isEqualTo(metric(page, "portfolio.collection_rate"));
            assertThat(total.get(10).asLong()).as(by).isEqualTo(metric(page, "portfolio.collected"));
        }
        JsonNode file = file(f, user, ALL, run(f, user, ALL, DISBURSEMENTS, RANGE, "json"));
        JsonNode total = last(table(file, "summary"));
        assertThat(total.get(1).asLong()).isEqualTo(metric(page, "portfolio.disbursed_count"));
        assertThat(total.get(2).asLong()).isEqualTo(metric(page, "portfolio.disbursed"));
        assertThat(total.get(4).asLong()).isEqualTo(metric(page, "portfolio.average_loan_size"));
        assertThat(metric(page, "portfolio.expected")).isEqualTo(335_000);
        assertThat(metric(page, "portfolio.collected")).isEqualTo(560_000);
    }

    // ---- disbursements --------------------------------------------------------------------------------

    @Test
    void disbursementsListEachLoanAndSummariseCountFeesAverageAndMedian() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        JsonNode file = file(f, user, ALL, run(f, user, ALL, DISBURSEMENTS, with(RANGE, "group_by", "branch"), "json"));
        // The reversed DS04 is not listed. Loan, date, member, branch, product, officer, principal, upfront fee.
        assertThat(rows(table(file, "loans")))
                .containsExactly(
                        "[\"DS01\",\"2026-10-02\",\"MDS01\",\"Head Office\",\"Test Product\",\"Test Officer Amber\",1000000,20000]",
                        "[\"DS02\",\"2026-10-06\",\"MDS02\",\"Test Branch Two\",\"Test Product\",\"Test Officer Birch\",400000,0]",
                        "[\"DS03\",\"2026-10-07\",\"MDS03\",\"Head Office\",\"Test Product\",\"Test Officer Amber\",600000,12000]",
                        "[\"Total, 3 loans\",null,null,null,null,null,2000000,32000]");
        // Group, loans, principal, fees, average, median: 2,000,000 over 3 is 666,666.67, so 666,667; the median is the
        // middle loan, 600,000; Head Office's two loans have a median of their mean, 800,000.
        assertThat(rows(table(file, "summary")))
                .containsExactly(
                        "[\"Head Office\",2,1600000,32000,800000,800000]",
                        "[\"Test Branch Two\",1,400000,0,400000,400000]",
                        "[\"Total\",3,2000000,32000,666667,600000]");
    }

    // ---- arrears list ---------------------------------------------------------------------------------

    @Test
    void theArrearsListHasTheLateLoansByBucketWithTheirLastPaymentFollowUpAndOpenPromise() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        JsonNode file = file(f, user, ALL, run(f, user, ALL, ARREARS, Map.of(), "json"));
        // How late, loan, member, name, phone, officer, days late, arrears, penalties, principal outstanding, last
        // repayment (the reversed 4 Oct one does not count), last follow up and its date, promised date and amount.
        assertThat(rows(table(file, "arrears")))
                .containsExactly(
                        "[\"1 to 30 days late\",\"CL02\",\"MCL02\",\"Test Borrower CL02\",\"+256700000001\",\"Test Officer Birch\",2,20000,0,20000,\"2026-10-07\",null,null,null,null]",
                        "[\"61 to 90 days late\",\"LT01\",\"MLT01\",\"Test Borrower LT01\",\"+256700000001\",\"Test Officer Birch\",68,300000,0,300000,null,\"Promise to pay\",\"2026-10-02\",\"2026-10-10\",100000]",
                        "[\"Total, 2 loans\",null,null,null,null,null,null,320000,0,320000,null,null,null,null,null]");

        // One bucket only.
        JsonNode one = file(f, user, ALL, run(f, user, ALL, ARREARS, Map.of("bucket", "61_90"), "json"));
        assertThat(rows(table(one, "arrears"))).hasSize(2).first().asString().contains("\"LT01\"");
    }

    @Test
    void withoutContactThePhoneIsMaskedAndWithoutMemberAccessTheNameIsNotPrinted() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        String perms = COLLECTIONS + ",lending.insights.read,lending.insights.all_officers";
        JsonNode file = file(f, user, perms, run(f, user, perms, ARREARS, Map.of(), "json"));
        JsonNode table = table(file, "arrears");
        assertThat(table.get("columns").toString()).doesNotContain("member_name");
        assertThat(rows(table).get(0)).contains("\"+256*****0001\"").doesNotContain("Test Borrower");
    }

    // ---- officer performance --------------------------------------------------------------------------

    @Test
    void officerPerformanceIsOneHandComputedRowPerOfficer() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        JsonNode file = file(f, user, ALL, run(f, user, ALL, OFFICERS, RANGE, "json"));
        // Officer, active loans, principal outstanding, PAR 30, loans disbursed, disbursed, expected, collected on due,
        // rate, actions, kept, broken. Amber: CL01 40,000, DS01, DS03. Birch: CL02 20,000, DS02 400,000, LT01
        // 300,000 (68 days late): PAR 30 = 300,000 / 720,000 = 41.67 percent; in total 300,000 / 2,360,000 = 12.71.
        assertThat(rows(table(file, "officers")))
                .containsExactly(
                        "[\"Test Officer Amber\",3,1640000,0,2,1600000,115000,115000,10000,1,1,0]",
                        "[\"Test Officer Birch\",3,720000,4167,1,400000,220000,200000,9091,2,0,1]",
                        "[\"Total\",6,2360000,1271,3,2000000,335000,315000,9403,3,1,1]");
    }

    // ---- scope ----------------------------------------------------------------------------------------

    @Test
    void aLoanOfficerWithoutAllOfficersGetsOnlyTheirOwnLoansInEveryReport() throws Exception {
        Flows f = flows();
        String perms = COLLECTIONS + "," + PORTFOLIO + ",lending.insights.read,lending.members.read";
        UUID birch = f.birch();
        JsonNode evc = file(f, birch, perms, run(f, birch, perms, EVC, RANGE, "json"));
        assertThat(rows(table(evc, "collections")))
                .containsExactly("[\"All loans\",220000,200000,9091,150000,0,0,0,80000,0,230000]");
        JsonNode officers = file(f, birch, perms, run(f, birch, perms, OFFICERS, RANGE, "json"));
        assertThat(rows(table(officers, "officers")))
                .containsExactly(
                        "[\"Test Officer Birch\",3,720000,4167,1,400000,220000,200000,9091,2,0,1]",
                        "[\"Total\",3,720000,4167,1,400000,220000,200000,9091,2,0,1]");
        JsonNode arrears = file(f, birch, perms, run(f, birch, perms, ARREARS, Map.of(), "json"));
        assertThat(rows(table(arrears, "arrears"))).hasSize(3);
        JsonNode amberArrears = file(f, f.amber(), perms, run(f, f.amber(), perms, ARREARS, Map.of(), "json"));
        assertThat(rows(table(amberArrears, "arrears")))
                .containsExactly("[\"Total, 0 loans\",null,null,null,null,null,null,0,0,0,null,null,null,null,null]");
        JsonNode out = file(f, birch, perms, run(f, birch, perms, DISBURSEMENTS, RANGE, "json"));
        assertThat(rows(table(out, "loans"))).hasSize(2).first().asString().contains("DS02");
        // And it equals Birch's own insights page.
        JsonNode page = insights(f, birch, perms, null);
        assertThat(last(table(evc, "collections")).get(1).asLong()).isEqualTo(metric(page, "portfolio.expected"));
        assertThat(last(table(evc, "collections")).get(10).asLong()).isEqualTo(metric(page, "portfolio.collected"));
    }

    @Test
    void aBranchLimitedCallerGetsTheirBranchWhateverTheyAsk() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        var h = headers(f.t(), user, ALL);
        h.set("X-Dev-Branch-Ids", f.t().headOffice().toString());
        var posted = http.exchange(
                "/api/v1/report-runs", HttpMethod.POST, new HttpEntity<>(body(EVC, RANGE, "json"), h), JsonNode.class);
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        JsonNode run = run(f.t(), user, ALL, posted.getBody().get("id").asString());
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        JsonNode file = mapper.readTree(
                download(f.t(), user, ALL, run.get("document_id").asString()));
        assertThat(rows(table(file, "collections")))
                .containsExactly("[\"All loans\",115000,115000,10000,115000,110000,70000,5000,0,30000,330000]");
    }

    // JUSTIFICATION-A3: the review of #750 asked for the refusal, per-branch name and snapshot paths to be proven.

    @Test
    void aBranchLimitedCallerAskingForAnotherBranchGetsNothingFromIt() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        String ho = f.t().headOffice().toString();
        Map<String, Object> other =
                with(RANGE, "branch_id", List.of(f.t().secondBranch().toString()));
        JsonNode evc = fileOf(f, user, ALL, ho, null, ready(f, user, ALL, ho, null, EVC, other));
        assertThat(rows(table(evc, "collections"))).containsExactly("[\"All loans\",0,0,null,0,0,0,0,0,0,0]");
        JsonNode arrears = fileOf(
                f,
                user,
                ALL,
                ho,
                null,
                ready(
                        f,
                        user,
                        ALL,
                        ho,
                        null,
                        ARREARS,
                        Map.of("branch_id", List.of(f.t().secondBranch().toString()))));
        assertThat(rows(table(arrears, "arrears")))
                .containsExactly("[\"Total, 0 loans\",null,null,null,null,null,null,0,0,0,null,null,null,null,null]");
        JsonNode officers = fileOf(f, user, ALL, ho, null, ready(f, user, ALL, ho, null, OFFICERS, other));
        assertThat(rows(table(officers, "officers"))).containsExactly("[\"Total\",0,0,null,0,0,0,0,null,0,0,0]");
    }

    @Test
    void namesAndPhonesFollowTheBranchesOfTheirOwnPermissions() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        // Insights and the report everywhere; members only at the head office; contact only at Test Branch Two. Both
        // late loans are Test Branch Two's: no name, a full phone.
        String scopes =
                "lending.members.read=" + f.t().headOffice() + ";lending.members.contact=" + f.t().secondBranch();
        JsonNode file = fileOf(f, user, ALL, "*", scopes, ready(f, user, ALL, "*", scopes, ARREARS, Map.of()));
        JsonNode table = table(file, "arrears");
        assertThat(table.get("columns").toString()).contains("member_name");
        assertThat(rows(table).get(0))
                .startsWith("[\"1 to 30 days late\",\"CL02\",\"MCL02\",null,\"+256700000001\"")
                .doesNotContain("Test Borrower");
        assertThat(rows(table).get(1)).startsWith("[\"61 to 90 days late\",\"LT01\",\"MLT01\",null,\"+256700000001\"");

        // Contact at the head office only: Test Branch Two's phones are masked.
        String headOnly = "lending.members.contact=" + f.t().headOffice();
        JsonNode masked = fileOf(f, user, ALL, "*", headOnly, ready(f, user, ALL, "*", headOnly, ARREARS, Map.of()));
        assertThat(rows(table(masked, "arrears")).get(0)).contains("\"+256*****0001\"");
    }

    @Test
    void aFileIsRefusedToARequesterWhoHasSinceLostTheNameOrThePhoneAccessItWasMadeWith() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        String doc = ready(f, user, ALL, "*", null, ARREARS, Map.of())
                .get("document_id")
                .asString();
        assertThat(downloadUrl(f, user, ALL, "*", null, doc).getStatusCode().value())
                .isEqualTo(200);
        String noNames = COLLECTIONS + ",lending.insights.read,lending.insights.all_officers,lending.members.contact";
        assertThat(downloadUrl(f, user, noNames, "*", null, doc).getStatusCode().value())
                .isEqualTo(404);
        String noContact = COLLECTIONS + ",lending.insights.read,lending.insights.all_officers,lending.members.read";
        assertThat(downloadUrl(f, user, noContact, "*", null, doc)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        // Names now in one branch only, where the file was made with every branch: refused too.
        String narrowed = "lending.members.read=" + f.t().headOffice();
        assertThat(downloadUrl(f, user, ALL, "*", narrowed, doc).getStatusCode().value())
                .isEqualTo(404);
    }

    /** The nightly snapshot of 6 Oct, written as the job does. */
    void snapshotOf6October(Flows f) {
        tenantJobs.callAs(
                f.t().tenantId(),
                () -> new TransactionTemplate(transactions)
                        .execute(x -> snapshots.backfill(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6))));
    }

    @Test
    void onAPastDateTheArrearsListReadsTheSnapshotLeavesPenaltiesBlankAndKeepsALaterReversedRepayment()
            throws Exception {
        Flows f = flows();
        snapshotOf6October(f);
        UUID user = UUID.randomUUID();
        JsonNode file = file(f, user, ALL, run(f, user, ALL, ARREARS, Map.of("as_at", "2026-10-06"), "json"));
        // On 6 Oct CL02 was not yet late (its item fell due that day), and LT01 owed 290,000: the 10,000 of 3 Oct was
        // reversed only on the 7th, so it is that date's last repayment. Penalties are blank, and so is their total.
        assertThat(rows(table(file, "arrears")))
                .containsExactly(
                        "[\"61 to 90 days late\",\"LT01\",\"MLT01\",\"Test Borrower LT01\",\"+256700000001\",\"Test Officer Birch\",66,290000,null,290000,\"2026-10-03\",\"Promise to pay\",\"2026-10-02\",\"2026-10-10\",100000]",
                        "[\"Total, 1 loan\",null,null,null,null,null,null,290000,null,290000,null,null,null,null,null]");
        assertThat(file.get("notes").toString())
                .contains(
                        "Penalties outstanding are not shown: the nightly snapshot the figures were read from does not"
                                + " hold them.");
        // Today the reversal stands, so LT01 has no last repayment.
        JsonNode today = file(f, user, ALL, run(f, user, ALL, ARREARS, Map.of(), "json"));
        assertThat(rows(table(today, "arrears")).get(1)).contains("\"Test Officer Birch\",68,300000,0,300000,null,");
    }

    @Test
    void onAPastRangeEndOfficerPerformanceReadsThePositionsFromTheSnapshot() throws Exception {
        Flows f = flows();
        snapshotOf6October(f);
        UUID user = UUID.randomUUID();
        JsonNode file = file(
                f, user, ALL, run(f, user, ALL, OFFICERS, Map.of("from", "2026-10-01", "to", "2026-10-06"), "json"));
        // On 6 Oct: Amber CL01 40,000 and DS01 1,000,000 (DS03 came on the 7th); Birch CL02 150,000 (due that day, not
        // late), DS02 400,000 and LT01 290,000, 66 days late: PAR 30 = 290,000 / 840,000 = 34.52 percent; in total
        // 290,000 / 1,880,000 = 15.43 percent. Birch's 220,000 due had 50,000 paid by the 6th: 22.73 percent.
        assertThat(rows(table(file, "officers")))
                .containsExactly(
                        "[\"Test Officer Amber\",2,1040000,0,1,1000000,115000,115000,10000,1,1,0]",
                        "[\"Test Officer Birch\",3,840000,3452,1,400000,220000,50000,2273,2,0,1]",
                        "[\"Total\",5,1880000,1543,2,1400000,335000,165000,4925,3,1,1]");
        assertThat(file.get("notes").toString()).contains("nightly snapshot of 6 Oct 2026");
    }

    // JUSTIFICATION-A3: review round 2 of #750 asked for the multi-disbursement case and an officer with no user row.

    @Test
    void aLoanHasOneDisbursementSoALoanAndItsFeeCountOnceOnTheReportAndThePage() throws Exception {
        Flows f = flows();
        // A second tranche, the same day or later, and a payout entered again after a reversal are all refused by the
        // database (V29's lending_loan_transactions_one_disbursement): a loan has one disbursement row.
        for (String loan : List.of("DS01", "DS04")) {
            for (LocalDate on : List.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 6))) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                                .sql("""
                                        INSERT INTO lending_loan_transactions (id, tenant_id, branch_id, loan_id, txn_type,
                                            amount_minor, currency, value_date, source, is_historic)
                                        SELECT ?, l.tenant_id, l.branch_id, l.id, 'disbursement', 500000, 'UGX', ?, 'staff', true
                                          FROM lending_loans l WHERE l.tenant_id = ? AND l.loan_no = ?
                                        """)
                                .params(UUID.randomUUID(), java.sql.Date.valueOf(on), f.t().tenantId(), loan)
                                .update())
                        .as("%s %s", loan, on)
                        .hasMessageContaining("lending_loan_transactions_one_disbursement");
            }
        }
        // So the loans, their principal, fees, average and median are each a loan's once, and the page agrees.
        UUID user = UUID.randomUUID();
        JsonNode page = insights(f, user, ALL, null);
        JsonNode summary = last(table(file(f, user, ALL, run(f, user, ALL, DISBURSEMENTS, RANGE, "json")), "summary"));
        assertThat(summary.toString()).isEqualTo("[\"All loans\",3,2000000,32000,666667,600000]");
        assertThat(summary.get(1).asLong()).isEqualTo(metric(page, "portfolio.disbursed_count"));
        assertThat(summary.get(2).asLong()).isEqualTo(metric(page, "portfolio.disbursed"));
        assertThat(summary.get(4).asLong()).isEqualTo(metric(page, "portfolio.average_loan_size"));
    }

    @Test
    void anOfficerWhoIsNoLongerAUserIsLabelledAndCounted() throws Exception {
        Flows f = flows();
        // A loan whose responsible officer has no user row (removed, or brought in by an import): 50,000 paid out on 3
        // Oct and due on 4 Oct, nothing paid back, so 4 days late.
        UUID gone = UUID.randomUUID();
        UUID loan = loanRow(
                f.t(), product(f.t()), f.t().headOffice(), gone, "UO01", "active", 50_000, LocalDate.of(2026, 10, 3));
        item(f.t(), loan, 1, LocalDate.of(2026, 10, 4), 50_000, 0, 0, 0, 0, 0, 0, 0);
        txn(f.t(), loan, f.t().headOffice(), "disbursement", 50_000, LocalDate.of(2026, 10, 3), null);
        UUID user = UUID.randomUUID();
        String label = "Staff " + gone.toString().substring(32);
        JsonNode officers = file(f, user, ALL, run(f, user, ALL, OFFICERS, RANGE, "json"));
        assertThat(rows(table(officers, "officers")))
                .contains("[\"" + label + "\",1,50000,0,1,50000,50000,0,0,0,0,0]")
                .contains("[\"Total\",7,2410000,1245,4,2050000,385000,315000,8182,3,1,1]");
        JsonNode byOfficer = file(f, user, ALL, run(f, user, ALL, EVC, with(RANGE, "group_by", "officer"), "json"));
        assertThat(rows(table(byOfficer, "collections"))).contains("[\"" + label + "\",50000,0,0,0,0,0,0,0,0,0]");
        JsonNode arrears = file(f, user, ALL, run(f, user, ALL, ARREARS, Map.of(), "json"));
        assertThat(rows(table(arrears, "arrears")).get(0))
                .isEqualTo("[\"1 to 30 days late\",\"UO01\",\"MUO01\",\"Test Borrower UO01\",\"+256700000001\",\""
                        + label + "\",4,50000,0,50000,null,null,null,null,null]");
        JsonNode paidOut = file(f, user, ALL, run(f, user, ALL, DISBURSEMENTS, RANGE, "json"));
        assertThat(rows(table(paidOut, "loans")))
                .contains("[\"UO01\",\"2026-10-03\",\"MUO01\",\"Head Office\",\"Test Product\",\"" + label
                        + "\",50000,0]");
    }

    @Test
    void aLoanAlwaysHasAResponsibleOfficerSoNoReportMeetsAMissingOne() {
        Flows f = flows();
        // officer_user_id is NOT NULL on lending_loans (V8) and on lending_loan_daily_snapshots (V33).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE lending_loans SET officer_user_id = NULL WHERE tenant_id = ? AND loan_no = 'LT01'")
                        .param(f.t().tenantId())
                        .update())
                .hasMessageContaining("officer_user_id");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        TestDatabase.owner().sql("""
                                INSERT INTO lending_loan_daily_snapshots (tenant_id, business_date, loan_id, branch_id,
                                    officer_user_id, product_id, principal_outstanding_minor, interest_outstanding_minor,
                                    arrears_minor, days_past_due, par_bucket)
                                SELECT l.tenant_id, DATE '2026-10-06', l.id, l.branch_id, NULL, v.product_id, 1, 0, 0, 0, 'current'
                                  FROM lending_loans l JOIN lending_loan_product_versions v ON v.id = l.product_version_id
                                 WHERE l.tenant_id = ? AND l.loan_no = 'LT01'
                                """).param(f.t().tenantId()).update())
                .hasMessageContaining("officer_user_id");
    }

    @Test
    void aRangeOrDateAfterTodayIsRefusedForTheOfficerAndArrearsReports() {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        assertThat(post(f.t(), user, ALL, OFFICERS, Map.of("from", "2026-10-01", "to", "2026-10-09"), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        assertThat(post(f.t(), user, ALL, ARREARS, Map.of("as_at", "2026-10-09"), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    /** The product of the fabricated book. */
    UUID product(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT id FROM lending_loan_products WHERE tenant_id = ? AND code = 'PAR'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
    }

    /** Queues a report with these branches and per-permission scopes, drains the queue, and returns the ready run. */
    JsonNode ready(
            Flows f, UUID user, String perms, String branches, String scopes, String key, Map<String, Object> params)
            throws Exception {
        var posted = http.exchange(
                "/api/v1/report-runs",
                HttpMethod.POST,
                new HttpEntity<>(body(key, params, "json"), headersOf(f, user, perms, branches, scopes)),
                JsonNode.class);
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        JsonNode run = run(f.t(), user, perms, posted.getBody().get("id").asString());
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        return run;
    }

    JsonNode fileOf(Flows f, UUID user, String perms, String branches, String scopes, JsonNode run) throws Exception {
        var url = downloadUrl(
                f, user, perms, branches, scopes, run.get("document_id").asString());
        assertThat(url.getStatusCode().value()).as("%s", url.getBody()).isEqualTo(200);
        var h = new org.springframework.http.HttpHeaders();
        h.add("X-Tenant", f.t().slug());
        return mapper.readTree(http.exchange(
                        java.net.URI.create(
                                http.getRootUri() + url.getBody().get("url").asString()),
                        HttpMethod.GET,
                        new HttpEntity<>(h),
                        String.class)
                .getBody());
    }

    org.springframework.http.ResponseEntity<JsonNode> downloadUrl(
            Flows f, UUID user, String perms, String branches, String scopes, String documentId) {
        return http.exchange(
                "/api/v1/documents/" + documentId + "/download-url",
                HttpMethod.POST,
                new HttpEntity<>(headersOf(f, user, perms, branches, scopes)),
                JsonNode.class);
    }

    org.springframework.http.HttpHeaders headersOf(Flows f, UUID user, String perms, String branches, String scopes) {
        var h = headers(f.t(), user, perms);
        h.set("X-Dev-Branch-Ids", branches);
        if (scopes != null) {
            h.set("X-Dev-Scopes", scopes);
        }
        return h;
    }

    @Test
    void theCatalogueOffersTheFourReportsInEveryFormatUnderTheirPermissions() {
        Flows f = flows();
        JsonNode all = call(HttpMethod.GET, "/api/v1/reports", f.t(), UUID.randomUUID(), ALL)
                .getBody();
        assertThat(all.toString())
                .contains(EVC)
                .contains(ARREARS)
                .contains(DISBURSEMENTS)
                .contains(OFFICERS);
        for (JsonNode e : all) {
            String key = e.get("key").asString();
            if (List.of(EVC, ARREARS, DISBURSEMENTS, OFFICERS).contains(key)) {
                assertThat(e.get("formats"))
                        .extracting(JsonNode::asString)
                        .containsExactly("csv", "json", "pdf", "xlsx");
                assertThat(e.get("permission").asString())
                        .isEqualTo(key.equals(DISBURSEMENTS) ? PORTFOLIO : COLLECTIONS);
            }
        }
        // Portfolio permission only: the disbursements, not the collections reports.
        JsonNode portfolio = call(
                        HttpMethod.GET,
                        "/api/v1/reports",
                        f.t(),
                        UUID.randomUUID(),
                        PORTFOLIO + ",lending.insights.read")
                .getBody();
        assertThat(portfolio.toString())
                .contains(DISBURSEMENTS)
                .doesNotContain(EVC)
                .doesNotContain(ARREARS)
                .doesNotContain(OFFICERS);
    }

    // ---- PDF and workbook -----------------------------------------------------------------------------

    @Test
    void thePdfPrintsTheEquationAndTheWorkbookHoldsTheSameNumbers() throws Exception {
        Flows f = flows();
        UUID user = UUID.randomUUID();
        JsonNode pdfRun = run(f, user, ALL, EVC, with(RANGE, "group_by", "branch"), "pdf");
        assertThat(pdfRun.get("status").asString()).as("%s", pdfRun).isEqualTo("ready");
        byte[] bytes = downloadBytes(f.t(), user, ALL, pdfRun.get("document_id").asString());
        ReportFormatsIT.sample("expected-vs-collected.pdf", bytes);
        PdfProbe pdf = new PdfProbe(bytes);
        assertThat(pdf.allFontsEmbedded()).isTrue();
        assertThat(pdf.pageSize(0)[0]).as("eleven columns go landscape").isGreaterThan(800f);
        assertThat(pdf.text())
                .contains("EXPECTED AND COLLECTED")
                .contains("Currency UGX")
                .contains("Head Office 115,000 115,000 100.0% 115,000 110,000 70,000 5,000 0 30,000 330,000")
                .contains("Test Branch Two 220,000 200,000 90.9% 150,000 0 0 0 80,000 0 230,000")
                .contains("Total 335,000 315,000 94.0% 265,000 110,000 70,000 5,000 80,000 30,000 560,000")
                .contains("Equals total collected 560,000");

        JsonNode xlsxRun = run(f, user, ALL, EVC, with(RANGE, "group_by", "branch"), "xlsx");
        byte[] x = downloadBytes(f.t(), user, ALL, xlsxRun.get("document_id").asString());
        ReportFormatsIT.sample("expected-vs-collected.xlsx", x);
        Sheet sh = XlsxReader.read(x).sheet("Expected and collected");
        assertThat(sh.text("A4")).isEqualTo("Total");
        assertThat(List.of("B4", "C4", "E4", "K4").stream().map(sh::text).toList())
                .containsExactly("335000", "315000", "265000", "560000");
        Sheet eq = XlsxReader.read(x).sheet("How the total collected adds up");
        assertThat(eq.text("A8")).isEqualTo("Equals total collected");
        assertThat(eq.text("B8")).isEqualTo("560000");

        for (String key : List.of(ARREARS, DISBURSEMENTS, OFFICERS)) {
            Map<String, Object> params = key.equals(ARREARS) ? Map.of() : RANGE;
            for (String format : List.of("pdf", "xlsx")) {
                JsonNode r = run(f, user, ALL, key, params, format);
                assertThat(r.get("status").asString())
                        .as("%s %s %s", key, format, r)
                        .isEqualTo("ready");
                ReportFormatsIT.sample(
                        key.substring("lending.".length()) + "." + format,
                        downloadBytes(f.t(), user, ALL, r.get("document_id").asString()));
            }
        }
        byte[] officers = downloadBytes(
                f.t(),
                user,
                ALL,
                run(f, user, ALL, OFFICERS, RANGE, "pdf").get("document_id").asString());
        assertThat(new PdfProbe(officers).text())
                .contains("Test Officer Birch 3 720,000 41.7% 1 400,000 220,000 200,000 90.9% 2 0 1")
                .contains("Total 6 2,360,000 12.7% 3 2,000,000 335,000 315,000 94.0% 3 1 1");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    JsonNode run(Flows f, UUID user, String perms, String key, Map<String, Object> params, String format)
            throws Exception {
        return runReport(new Book(f.t(), f.amber(), f.birch(), null), user, perms, key, params, format);
    }

    JsonNode file(Flows f, UUID user, String perms, JsonNode run) throws Exception {
        return file(new Book(f.t(), f.amber(), f.birch(), null), user, perms, run);
    }

    JsonNode insights(Flows f, UUID user, String perms, String filter) {
        return http.exchange(
                        "/api/v1/lending/insights/portfolio?from=" + FROM + "&to=" + TODAY
                                + (filter == null ? "" : filter),
                        HttpMethod.GET,
                        new HttpEntity<>(headers(f.t(), user, perms)),
                        JsonNode.class)
                .getBody();
    }

    static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(base);
        out.put(key, value);
        return out;
    }

    static JsonNode last(JsonNode table) {
        JsonNode rows = table.get("rows");
        return rows.get(rows.size() - 1);
    }

    UUID user(TestDatabase.Fixture t, String name) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?, 'active')")
                .params(id, t.tenantId(), name, id + "@example.test")
                .update();
        return id;
    }

    /** A loan without a schedule; its member is {@code "M" + no}, "Test Borrower " + no. */
    UUID loanRow(
            TestDatabase.Fixture t,
            UUID product,
            UUID branch,
            UUID officer,
            String no,
            String status,
            long principal,
            LocalDate disbursedOn) {
        UUID member = UUID.randomUUID();
        String nin = "CMTEST" + String.format("%08d", NIN.incrementAndGet());
        var owner = TestDatabase.owner();
        owner.sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                                     national_id, currency, kyc_status, status, source)
                        VALUES (?, ?, ?, ?, ?, '+256700000001', 'nin', ?, 'UGX', 'incomplete', 'active', 'staff')
                        """)
                .params(member, t.tenantId(), branch, "M" + no, "Test Borrower " + no, nin)
                .update();
        UUID loan = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                            officer_user_id, status, channel, purpose_category, currency, requested_principal_minor,
                            requested_term_count, term_unit, interest_method, interest_rate_bp, rate_unit, repayment_pattern,
                            disbursed_on, principal_disbursed_minor, principal_outstanding_minor, maturity_date)
                        SELECT ?, ?, ?, ?, ?, v.id, ?, ?, 'staff', 'business', 'UGX', ?, 1, 'month', 'flat', 1000, 'per_term',
                               'bullet', ?, ?, ?, DATE '2026-12-31'
                          FROM lending_loan_product_versions v WHERE v.product_id = ?
                        """)
                .params(
                        loan,
                        t.tenantId(),
                        branch,
                        no,
                        member,
                        officer,
                        status,
                        principal,
                        Date.valueOf(disbursedOn),
                        principal,
                        principal,
                        product)
                .update();
        return loan;
    }

    UUID item(
            TestDatabase.Fixture t,
            UUID loan,
            int no,
            LocalDate due,
            long principal,
            long interest,
            long fees,
            long penalties,
            long principalPaid,
            long interestPaid,
            long feesPaid,
            long penaltiesPaid) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_schedule_items (id, tenant_id, loan_id, item_no, due_date, principal_due_minor,
                            interest_due_minor, fees_due_minor, penalties_due_minor, principal_paid_minor, interest_paid_minor,
                            fees_paid_minor, penalties_paid_minor, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'due')
                        """)
                .params(
                        id,
                        t.tenantId(),
                        loan,
                        no,
                        Date.valueOf(due),
                        principal,
                        interest,
                        fees,
                        penalties,
                        principalPaid,
                        interestPaid,
                        feesPaid,
                        penaltiesPaid)
                .update();
        return id;
    }

    /** A historic entry (no journal), so a figure needs no ledger. */
    UUID txn(TestDatabase.Fixture t, UUID loan, UUID branch, String type, long amount, LocalDate on, UUID reverses) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_loan_transactions (id, tenant_id, branch_id, loan_id, txn_type, amount_minor,
                            currency, value_date, reverses_txn_id, source, is_historic)
                        VALUES (?, ?, ?, ?, ?, ?, 'UGX', ?, ?, 'staff', true)
                        """)
                .params(id, t.tenantId(), branch, loan, type, amount, Date.valueOf(on), reverses)
                .update();
        return id;
    }

    void alloc(TestDatabase.Fixture t, UUID txn, UUID appliesTo, UUID item, String component, long amount) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_repayment_allocations (id, tenant_id, transaction_id, applies_to_txn_id,
                            schedule_item_id, component, amount_minor)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), txn, appliesTo, item, component, amount)
                .update();
    }

    /** A loan disbursed on {@code on} with one item due 30 November, nothing paid; reversed when {@code reversed}. */
    void disbursed(
            TestDatabase.Fixture t,
            UUID product,
            UUID branch,
            UUID officer,
            String no,
            long principal,
            long fee,
            LocalDate on,
            boolean reversed) {
        UUID loan = loanRow(t, product, branch, officer, no, reversed ? "cancelled" : "active", principal, on);
        item(t, loan, 1, LocalDate.of(2026, 11, 30), principal, 0, 0, 0, 0, 0, 0, 0);
        UUID d = txn(t, loan, branch, "disbursement", principal, on, null);
        if (fee > 0) {
            txn(t, loan, branch, "fee_upfront", fee, on, null);
        }
        if (reversed) {
            txn(t, loan, branch, "reversal", principal, on, d);
            // A cancelled loan whose disbursement was reversed was never paid out: no disbursement date.
            TestDatabase.owner()
                    .sql("UPDATE lending_loans SET disbursed_on = NULL WHERE id = ?")
                    .param(loan)
                    .update();
        }
    }

    void action(
            TestDatabase.Fixture t,
            UUID loan,
            UUID by,
            String type,
            String at,
            String promisedDate,
            Long promisedAmount,
            String status,
            String settledOn) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_collection_actions (id, tenant_id, loan_id, action_type, promised_date,
                            promised_amount_minor, promise_status, promise_settled_on, performed_by, performed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        loan,
                        type,
                        promisedDate == null ? null : Date.valueOf(promisedDate),
                        promisedAmount,
                        status,
                        settledOn == null ? null : Date.valueOf(settledOn),
                        by,
                        Timestamp.from(OffsetDateTime.parse(at).toInstant()))
                .update();
    }
}
