package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Sheet;
import com.rincoltech.bms.lending.insights.RegisterFigures;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The collateral register and the regulator data summary (extras slice D4, issue #225; chapter 14 sections 14.5 and
 * 14.7; FR-COL-06, FR-RPT-05): the register's flags against a hand-computed fabricated dataset, its branch scope, the
 * PDF row cap with the pointer to the workbook, and the summary's figures, with its PAR 30 and PAR 90 equal to
 * {@code lending.par} and the insights page (section 14.3) and its cover note. Every name is fabricated; the business
 * date is the movable clock's 8 October 2026.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT30S",
            "bms.reports.xlsx-row-cap=500",
            "bms.reports.pdf-row-cap=200",
            "bms.reports.max-file-bytes=2097152",
            "bms.reports.cancel-poll=PT0.2S",
            "bms.reports.heartbeat=PT0.1S",
            "bms.reports.runner-enabled=false"
        })
class LendingRegistersReportsIT extends LendingBookSupport {

    static final String REGISTER = "lending.collateral_register";
    static final String SUMMARY = "lending.regulatory_summary";
    static final String MEMBERS = "lending.reports.members,lending.members.read,lending.insights.all_officers";
    /** The register without member access: no owner details, every officer's items. */
    static final String NO_MEMBERS = "lending.reports.members,lending.insights.all_officers";

    static final String COMPLIANCE = "lending.reports.compliance,lending.insights.read,lending.insights.all_officers";
    static final Map<String, Object> PERIOD = Map.of("from", "2026-10-01", "to", "2026-10-08");

    @org.springframework.beans.factory.annotation.Autowired
    RegisterFigures registers;

    // ---- collateral register --------------------------------------------------------------------------

    /**
     * Five items on the golden book's members.
     *
     * <ul>
     *   <li>Head Office, MLN0001: a land title in our custody in the strong room, securing LN0001 (active), valued
     *       5,000,000 on 15 Sep. No flag.
     *   <li>Head Office, MLN0002: a vehicle in the yard, securing only LN0009, which is closed. Release overdue, and no
     *       valuation.
     *   <li>Head Office, MLN0003: an item brought in by an import and noted by staff since. No valuation only.
     *   <li>Test Branch Two, MLN0004: a national ID brought in by an import, untouched since. Imported and not verified, and
     *       no valuation.
     *   <li>Test Branch Two, MLN0005: a household item, released, valued 300,000. No flag.
     * </ul>
     */
    Book registerBook() {
        Book b = goldenBook();
        TestDatabase.Fixture t = b.t();
        UUID ho = t.headOffice();
        UUID two = t.secondBranch();
        UUID c1 = item(
                t,
                ho,
                "MLN0001",
                "land_title",
                "Plot in a fabricated village",
                null,
                "in_custody",
                "Strong room",
                6_000_000L);
        valuation(t, c1, LocalDate.of(2026, 9, 15), 5_000_000);
        pledge(t, "LN0001", c1);
        event(t, c1, "registered", null);

        loanOfMember(t, b.product(), ho, b.officerA(), "LN0009", "MLN0002", 100_000);
        TestDatabase.owner().sql("""
                        UPDATE lending_loans SET status = 'closed', closed_on = DATE '2026-09-30', principal_outstanding_minor = 0
                         WHERE tenant_id = ? AND loan_no = 'LN0009'
                        """).param(t.tenantId()).update();
        UUID c2 = item(t, ho, "MLN0002", "vehicle", "Fabricated pickup", "UAA000A", "in_custody", "Yard", null);
        pledge(t, "LN0009", c2);
        event(t, c2, "registered", null);

        UUID c5 = item(t, ho, "MLN0003", "other", "Fabricated sewing machine", null, "pledged", null, null);
        event(t, c5, "registered", "imported; verify custody and value");
        event(t, c5, "note", "Seen at the borrower's shop");

        UUID c3 = item(t, two, "MLN0004", "national_id", "National ID card", "CMTEST00000000", "pledged", null, null);
        event(t, c3, "registered", "imported; verify custody and value");

        UUID c4 = item(t, two, "MLN0005", "household_item", "Fabricated fridge", null, "released", null, 400_000L);
        valuation(t, c4, LocalDate.of(2026, 8, 1), 300_000);
        pledge(t, "LN0005", c4);
        event(t, c4, "registered", null);
        return b;
    }

    @Test
    void theFlagsMatchTheHandComputedDataset() throws Exception {
        Book b = registerBook();
        UUID user = UUID.randomUUID();
        JsonNode file = file(b, user, MEMBERS, runReport(b, user, MEMBERS, REGISTER, Map.of(), "json"));

        assertThat(file.get("as_of").asString()).isEqualTo("2026-10-08");
        assertThat(rows(table(file, "flags")))
                .containsExactly(
                        "[\"Release overdue\",1]",
                        "[\"Imported, not verified\",1]",
                        "[\"No valuation\",3]",
                        "[\"Items in the register\",5]");
        // Branch, member, name, type, description, reference, estimated value, latest valuation, valued on, custody,
        // kept at, loans, flags.
        assertThat(rows(table(file, "items")))
                .containsExactly(
                        "[\"Head Office\",\"MLN0001\",\"Test Borrower LN0001\",\"Land title\",\"Plot in a fabricated village\",null,6000000,5000000,\"2026-09-15\",\"In our custody\",\"Strong room\",\"LN0001 (active)\",null]",
                        "[\"Head Office\",\"MLN0002\",\"Test Borrower LN0002\",\"Vehicle\",\"Fabricated pickup\",\"UAA000A\",null,null,null,\"In our custody\",\"Yard\",\"LN0009 (closed)\",\"Release overdue; No valuation\"]",
                        "[\"Head Office\",\"MLN0003\",\"Test Borrower LN0003\",\"Other\",\"Fabricated sewing machine\",null,null,null,null,\"Pledged\",null,null,\"No valuation\"]",
                        "[\"Test Branch Two\",\"MLN0004\",\"Test Borrower LN0004\",\"National ID\",\"National ID card\",\"CMTEST00000000\",null,null,null,\"Pledged\",null,null,\"Imported, not verified; No valuation\"]",
                        "[\"Test Branch Two\",\"MLN0005\",\"Test Borrower LN0005\",\"Household item\",\"Fabricated fridge\",null,400000,300000,\"2026-08-01\",\"Released\",null,\"LN0005 (active)\",null]");

        // The filters: one type, one custody status.
        JsonNode custody = file(
                b,
                user,
                MEMBERS,
                runReport(b, user, MEMBERS, REGISTER, Map.of("custody_status", "in_custody"), "json"));
        assertThat(rows(table(custody, "items"))).hasSize(2);
        JsonNode type = file(
                b, user, MEMBERS, runReport(b, user, MEMBERS, REGISTER, Map.of("collateral_type", "vehicle"), "json"));
        assertThat(rows(table(type, "items"))).hasSize(1).first().asString().contains("UAA000A");
    }

    @Test
    void aBranchLimitedCallerGetsTheirBranchAndWithoutMemberAccessNoNames() throws Exception {
        Book b = registerBook();
        UUID user = UUID.randomUUID();
        var h = headers(b.t(), user, NO_MEMBERS);
        h.set("X-Dev-Branch-Ids", b.t().secondBranch().toString());
        var posted = http.exchange(
                "/api/v1/report-runs",
                HttpMethod.POST,
                new HttpEntity<>(body(REGISTER, Map.of(), "json"), h),
                JsonNode.class);
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        JsonNode run = run(b.t(), user, NO_MEMBERS, posted.getBody().get("id").asString());
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        var url = http.exchange(
                "/api/v1/documents/" + run.get("document_id").asString() + "/download-url",
                HttpMethod.POST,
                new HttpEntity<>(h),
                JsonNode.class);
        assertThat(url.getStatusCode().value()).as("%s", url.getBody()).isEqualTo(200);
        var plain = new org.springframework.http.HttpHeaders();
        plain.add("X-Tenant", b.t().slug());
        JsonNode file = mapper.readTree(http.exchange(
                        java.net.URI.create(
                                http.getRootUri() + url.getBody().get("url").asString()),
                        HttpMethod.GET,
                        new HttpEntity<>(plain),
                        String.class)
                .getBody());
        JsonNode items = table(file, "items");
        assertThat(items.get("columns").toString()).doesNotContain("member_name");
        assertThat(rows(items)).hasSize(2).allMatch(r -> r.startsWith("[\"Test Branch Two\",null,"));
        // Who owns the item stays hidden: no member number, description or reference (here a national ID number).
        assertThat(rows(items).get(0))
                .startsWith("[\"Test Branch Two\",null,\"National ID\",null,null,")
                .doesNotContain("CMTEST");
        assertThat(rows(table(file, "flags"))).contains("[\"Items in the register\",2]");
    }

    // JUSTIFICATION-A3: as the review of #750 asked for the arrears list, names follow the branches of their permission
    // and a download is refused once that access narrows.

    @Test
    void namesFollowTheBranchesOfMemberAccessAndANarrowedDownloadIsRefused() throws Exception {
        Book b = registerBook();
        UUID user = UUID.randomUUID();
        var h = headers(b.t(), user, MEMBERS);
        h.set("X-Dev-Scopes", "lending.members.read=" + b.t().headOffice());
        var posted = http.exchange(
                "/api/v1/report-runs",
                HttpMethod.POST,
                new HttpEntity<>(body(REGISTER, Map.of(), "json"), h),
                JsonNode.class);
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        String doc = run(b.t(), user, MEMBERS, posted.getBody().get("id").asString())
                .get("document_id")
                .asString();
        var url = http.exchange(
                "/api/v1/documents/" + doc + "/download-url", HttpMethod.POST, new HttpEntity<>(h), JsonNode.class);
        assertThat(url.getStatusCode().value()).as("%s", url.getBody()).isEqualTo(200);
        var plain = new org.springframework.http.HttpHeaders();
        plain.add("X-Tenant", b.t().slug());
        JsonNode file = mapper.readTree(http.exchange(
                        java.net.URI.create(
                                http.getRootUri() + url.getBody().get("url").asString()),
                        HttpMethod.GET,
                        new HttpEntity<>(plain),
                        String.class)
                .getBody());
        List<String> items = rows(table(file, "items"));
        assertThat(items.get(0)).startsWith("[\"Head Office\",\"MLN0001\",\"Test Borrower LN0001\"");
        // Test Branch Two's members: no number, no name, no description, no reference.
        assertThat(items.get(3)).startsWith("[\"Test Branch Two\",null,null,\"National ID\",null,null,");
        assertThat(items.get(4)).startsWith("[\"Test Branch Two\",null,null,\"Household item\"");

        // Member access gone: the file made with names is refused.
        assertThat(http.exchange(
                                "/api/v1/documents/" + doc + "/download-url",
                                HttpMethod.POST,
                                new HttpEntity<>(headers(b.t(), user, "lending.reports.members")),
                                JsonNode.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    // JUSTIFICATION-A3: review round 1 of #754 asked for the officer limit and the summary's snapshot path to be
    // proven.

    @Test
    void aLoanOfficerGetsOnlyTheItemsSecuringTheirOwnLoansAndAnotherOfficerCannotFetchTheFile() throws Exception {
        Book b = registerBook();
        String perms = "lending.reports.members,lending.members.read";
        // Officer B's loans: LN0004 to LN0006. Of the five items only the fridge secures one of them (LN0005).
        JsonNode run = runReport(b, b.officerB(), perms, REGISTER, Map.of(), "json");
        JsonNode file = file(b, b.officerB(), perms, run);
        assertThat(rows(table(file, "items"))).hasSize(1).first().asString().contains("\"Fabricated fridge\"");
        assertThat(rows(table(file, "flags"))).contains("[\"Items in the register\",1]");
        assertThat(http.exchange(
                                "/api/v1/documents/" + run.get("document_id").asString() + "/download-url",
                                HttpMethod.POST,
                                new HttpEntity<>(headers(b.t(), b.officerA(), perms)),
                                JsonNode.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    // JUSTIFICATION-A3: review round 2 of #754 asked for an item pledged to two officers' loans, blank cells, totals
    // from their rows and the two passes proven to read one snapshot.

    @Test
    void anOfficersRegisterNamesOnlyTheirOwnLoansUnderLoans() throws Exception {
        Book b = registerBook();
        TestDatabase.Fixture t = b.t();
        // A bicycle first pledged to officer A's LN0002 and released, then pledged to officer B's LN0006 (one open
        // pledge per item, V8).
        UUID bike = item(t, t.secondBranch(), "MLN0006", "other", "Fabricated bicycle", null, "pledged", null, 90_000L);
        pledge(t, "LN0002", bike);
        TestDatabase.owner().sql("""
                        UPDATE lending_loan_collateral SET released_at = now()
                         WHERE tenant_id = ? AND collateral_id = ?
                        """).params(t.tenantId(), bike).update();
        pledge(t, "LN0006", bike);

        String perms = "lending.reports.members,lending.members.read";
        List<String> own = rows(table(
                file(b, b.officerB(), perms, runReport(b, b.officerB(), perms, REGISTER, Map.of(), "json")), "items"));
        assertThat(own).hasSize(2);
        assertThat(own.get(1))
                .contains("\"Fabricated bicycle\"")
                .contains("\"LN0006 (active)\"")
                .doesNotContain("LN0002");
        // Every officer's register names both loans.
        UUID user = UUID.randomUUID();
        assertThat(rows(table(
                        file(b, user, MEMBERS, runReport(b, user, MEMBERS, REGISTER, Map.of(), "json")), "items")))
                .anyMatch(r -> r.contains("\"LN0002 (active), LN0006 (active)\""));
    }

    @Test
    void bothPassesOverTheRegisterReadOneSnapshot() {
        Book b = registerBook();
        UUID member = TestDatabase.owner()
                .sql("SELECT id FROM lending_members WHERE tenant_id = ? AND member_no = 'MLN0001'")
                .param(b.t().tenantId())
                .query(UUID.class)
                .single();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        RegisterFigures.CollateralFilter all = new RegisterFigures.CollateralFilter(null, null, null, null, null);
        long[] passes = new long[2];
        tenantJobs.callAs(
                b.t().tenantId(),
                () -> tx.execute(x -> {
                    registers.collateral(all, () -> {}, l -> {
                        if (passes[0]++ == 0) {
                            // Committed by another connection while the first pass reads.
                            TestDatabase.owner()
                                    .sql("""
                                    INSERT INTO lending_collateral_items (id, tenant_id, branch_id, member_id, collateral_type,
                                                                          description, currency, custody_status)
                                    VALUES (gen_random_uuid(), ?, ?, ?, 'other', 'Fabricated late item', 'UGX', 'pledged')
                                    """)
                                    .params(b.t().tenantId(), b.t().headOffice(), member)
                                    .update();
                        }
                    });
                    registers.collateral(all, () -> {}, l -> passes[1]++);
                    return null;
                }));
        assertThat(passes[0]).isEqualTo(5);
        assertThat(passes[1]).isEqualTo(passes[0]);
        // The late item was committed: a register made afterwards has it.
        long[] after = {0};
        tenantJobs.callAs(
                b.t().tenantId(),
                () -> tx.execute(x -> {
                    registers.collateral(all, () -> {}, l -> after[0]++);
                    return null;
                }));
        assertThat(after[0]).isEqualTo(6);
    }

    @Test
    void anEmptyBookPrintsNoDashAndAnOfficerGetsNoBranchesOrStaff() throws Exception {
        Book b = summaryBook();
        // Every loan closed: nothing owed, so PAR has nothing to measure.
        TestDatabase.owner().sql("""
                        UPDATE lending_loans SET status = 'closed', closed_on = DATE '2026-10-05', principal_outstanding_minor = 0
                         WHERE tenant_id = ? AND status = 'active'
                        """).param(b.t().tenantId()).update();
        UUID user = UUID.randomUUID();
        JsonNode file = file(b, user, COMPLIANCE, runReport(b, user, COMPLIANCE, SUMMARY, PERIOD, "json"));
        assertThat(rows(table(file, "risk")))
                .containsExactly(
                        "[\"Portfolio at risk over 30 days (PAR 30)\",null]",
                        "[\"Portfolio at risk over 90 days (PAR 90)\",null]");
        JsonNode pdfRun = runReport(b, user, COMPLIANCE, SUMMARY, PERIOD, "pdf");
        assertThat(pdfRun.get("status").asString()).as("%s", pdfRun).isEqualTo("ready");
        String text = new PdfProbe(downloadBytes(
                        b.t(), user, COMPLIANCE, pdfRun.get("document_id").asString()))
                .text();
        assertThat(text)
                .contains("Portfolio at risk over 30 days (PAR 30) Portfolio at risk over 90 days (PAR 90)")
                .doesNotContain(" - ")
                .doesNotContain("-%");

        // Officer B, limited to their own loans: borrowers only, no branch or staff count.
        String officer = "lending.reports.compliance,lending.insights.read";
        Book live = summaryBook();
        JsonNode own = file(
                live, live.officerB(), officer, runReport(live, live.officerB(), officer, SUMMARY, PERIOD, "json"));
        assertThat(rows(table(own, "people"))).containsExactly("[\"Active borrowers at the end of the period\",3]");
        assertThat(own.get("notes").toString()).contains("Savings, investments, branches and staff are not shown");
    }

    @Test
    void onAPastPeriodEndTheSummaryReadsTheSnapshotAndItsParIsThatOfTheParReport() throws Exception {
        Book b = summaryBook();
        tenantJobs.callAs(
                b.t().tenantId(),
                () -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                        .execute(x -> snapshots.backfill(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7))));
        UUID user = UUID.randomUUID();
        String perms = COMPLIANCE + "," + PORTFOLIO;
        Map<String, Object> period = Map.of("from", "2026-10-01", "to", "2026-10-07");
        JsonNode file = file(b, user, perms, runReport(b, user, perms, SUMMARY, period, "json"));
        // On 7 Oct the golden loans are each a day less late: LN0003 29 days, LN0005 30, LN0006 90. Only LN0006 is over
        // 30 (300,000 of 10,000,000: 3.0 percent) and none is over 90.
        assertThat(rows(table(file, "risk")))
                .containsExactly(
                        "[\"Portfolio at risk over 30 days (PAR 30)\",300]",
                        "[\"Portfolio at risk over 90 days (PAR 90)\",0]");
        assertThat(file.get("notes").toString()).contains("nightly snapshot of 7 Oct 2026");
        JsonNode par = file(b, user, perms, par(b, user, perms, Map.of("as_at", "2026-10-07"), "json"));
        JsonNode parTotal = table(par, "par").get("rows").get(0);
        assertThat(table(file, "risk").get("rows").get(0).get(1).asLong())
                .isEqualTo(parTotal.get(5).asLong());
        assertThat(table(file, "risk").get("rows").get(1).get(1).asLong())
                .isEqualTo(parTotal.get(7).asLong());
        assertThat(rows(table(file, "loans")))
                .first()
                .asString()
                .isEqualTo("[\"Active loans and principal outstanding\",6,10000000]");
        assertThat(rows(table(file, "purposes")))
                .containsExactly("[\"Business\",5,8000000]", "[\"Agriculture\",1,2000000]", "[\"Total\",6,10000000]");
        // The breakdowns read the snapshot's positions joined to the loans and members, as on the live path.
        assertThat(rows(table(file, "borrowers")))
                .containsExactly(
                        "[\"Women\",3]", "[\"Men\",2]", "[\"Other\",0]", "[\"Not recorded\",1]", "[\"Total\",6]");
        assertThat(rows(table(file, "products")))
                .containsExactly("[\"Test Product\",\"per term\",6,10000000,1000,1500,1130]");
    }

    @Test
    void aPastPeriodEndWithoutASnapshotIsRefusedNotGuessed() throws Exception {
        Book b = summaryBook();
        UUID user = UUID.randomUUID();
        JsonNode run =
                runReport(b, user, COMPLIANCE, SUMMARY, Map.of("from", "2026-10-01", "to", "2026-10-07"), "json");
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("failed");
        assertThat(run.get("error").asString()).isEqualTo("snapshot_unavailable");
    }

    @Test
    void aLongRegisterIsCutAtThePdfRowCapWithThePointerAndTheWorkbookHoldsEveryItem() throws Exception {
        Book b = goldenBook();
        UUID member = TestDatabase.owner()
                .sql("SELECT id FROM lending_members WHERE tenant_id = ? AND member_no = 'MLN0001'")
                .param(b.t().tenantId())
                .query(UUID.class)
                .single();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_collateral_items (id, tenant_id, branch_id, member_id, collateral_type, description,
                                                              currency, custody_status)
                        SELECT gen_random_uuid(), ?, ?, ?, 'household_item', 'Fabricated item ' || g, 'UGX', 'pledged'
                          FROM generate_series(1, 230) g
                        """)
                .params(b.t().tenantId(), b.t().headOffice(), member)
                .update();
        UUID user = UUID.randomUUID();
        JsonNode pdfRun = runReport(b, user, MEMBERS, REGISTER, Map.of(), "pdf");
        assertThat(pdfRun.get("status").asString()).as("%s", pdfRun).isEqualTo("ready");
        byte[] pdf =
                downloadBytes(b.t(), user, MEMBERS, pdfRun.get("document_id").asString());
        ReportFormatsIT.sample("collateral-register.pdf", pdf);
        PdfProbe probe = new PdfProbe(pdf);
        assertThat(probe.allFontsEmbedded()).isTrue();
        assertThat(probe.text())
                .contains("COLLATERAL REGISTER")
                .contains("Items in the register 230")
                .contains("No valuation 230")
                .contains("Showing the first 200 of 230 rows. The full detail is in the Excel file.")
                // No item is valued: the empty value and date cells print blank, not as a dash.
                .doesNotContain(" - ");

        JsonNode xlsxRun = runReport(b, user, MEMBERS, REGISTER, Map.of(), "xlsx");
        byte[] x =
                downloadBytes(b.t(), user, MEMBERS, xlsxRun.get("document_id").asString());
        ReportFormatsIT.sample("collateral-register.xlsx", x);
        Sheet sh = XlsxReader.read(x).sheet("Collateral items");
        assertThat(sh.rows()).hasSize(231);
    }

    // ---- regulator summary ----------------------------------------------------------------------------

    /**
     * The golden book (six active loans, principal outstanding 10,000,000; PAR 30 8.0 percent, PAR 90 3.0 percent) with:
     * borrowers of LN0001 to LN0003 women, of LN0004 and LN0005 men, of LN0006 not recorded; LN0004 for agriculture, the
     * rest for business; rates of 15.0 percent on LN0002 and 12.0 on LN0003, 10.0 on the rest, all per term; LN0001's
     * disbursement dated 3 Oct; LN0007 written off on 2 Oct (200,000); one active staff member with a role at the head
     * office and one whose role was revoked.
     */
    Book summaryBook() {
        Book b = goldenBook();
        TestDatabase.Fixture t = b.t();
        var owner = TestDatabase.owner();
        owner.sql("""
                        UPDATE lending_members SET gender = CASE
                            WHEN member_no IN ('MLN0001', 'MLN0002', 'MLN0003') THEN 'female'
                            WHEN member_no IN ('MLN0004', 'MLN0005') THEN 'male' ELSE NULL END
                         WHERE tenant_id = ?
                        """).param(t.tenantId()).update();
        owner.sql(
                        "UPDATE lending_loans SET purpose_category = 'agriculture' WHERE tenant_id = ? AND loan_no = 'LN0004'")
                .param(t.tenantId())
                .update();
        owner.sql("UPDATE lending_loans SET interest_rate_bp = 1500 WHERE tenant_id = ? AND loan_no = 'LN0002'")
                .param(t.tenantId())
                .update();
        owner.sql("UPDATE lending_loans SET interest_rate_bp = 1200 WHERE tenant_id = ? AND loan_no = 'LN0003'")
                .param(t.tenantId())
                .update();
        owner.sql(
                        "UPDATE lending_loans SET written_off_on = DATE '2026-10-02' WHERE tenant_id = ? AND loan_no = 'LN0007'")
                .param(t.tenantId())
                .update();
        txn(t, "LN0001", "disbursement", 4_000_000, LocalDate.of(2026, 10, 3));
        txn(t, "LN0007", "write_off", 200_000, LocalDate.of(2026, 10, 2));
        UUID active = staff(t, "Test Staff Active");
        role(t, active, t.headOffice(), false);
        UUID gone = staff(t, "Test Staff Gone");
        role(t, gone, t.headOffice(), true);
        return b;
    }

    @Test
    void theSummaryIsTheHandComputedFiguresUnderItsCoverNote() throws Exception {
        Book b = summaryBook();
        UUID user = UUID.randomUUID();
        JsonNode file = file(b, user, COMPLIANCE, runReport(b, user, COMPLIANCE, SUMMARY, PERIOD, "json"));

        assertThat(file.get("notes").get(0).asString()).isEqualTo("Data summary. Not a filed return.");
        assertThat(file.get("as_of").asString()).isEqualTo("2026-10-08");
        // Figure, number, amount, rate.
        // No figure cell is empty: a table per kind of figure.
        assertThat(rows(table(file, "loans")))
                .containsExactly(
                        "[\"Active loans and principal outstanding\",6,10000000]",
                        "[\"Loans disbursed in the period\",1,4000000]",
                        "[\"Loans written off in the period\",1,200000]",
                        "[\"Savings held from members (accounts, balance)\",0,0]",
                        "[\"Investments held from members (open, principal)\",0,0]");
        assertThat(rows(table(file, "risk")))
                .containsExactly(
                        "[\"Portfolio at risk over 30 days (PAR 30)\",800]",
                        "[\"Portfolio at risk over 90 days (PAR 90)\",300]");
        assertThat(rows(table(file, "people")))
                .containsExactly(
                        "[\"Active borrowers at the end of the period\",6]", "[\"Branches\",2]", "[\"Staff\",1]");
        assertThat(rows(table(file, "borrowers")))
                .containsExactly(
                        "[\"Women\",3]", "[\"Men\",2]", "[\"Other\",0]", "[\"Not recorded\",1]", "[\"Total\",6]");
        // 4,000,000 at 10.0, 2,200,000 at 15.0, 1,000,000 at 12.0 and 2,800,000 at 10.0: 11,300,000,000 over 10,000,000
        // is 11.3 percent, 1130 basis points.
        assertThat(rows(table(file, "products")))
                .containsExactly("[\"Test Product\",\"per term\",6,10000000,1000,1500,1130]");
        assertThat(rows(table(file, "purposes")))
                .containsExactly("[\"Business\",5,8000000]", "[\"Agriculture\",1,2000000]", "[\"Total\",6,10000000]");
    }

    @Test
    void itsPar30AndPar90AreThoseOfTheParReportAndTheInsightsPage() throws Exception {
        Book b = summaryBook();
        UUID user = UUID.randomUUID();
        String perms = COMPLIANCE + "," + PORTFOLIO;
        JsonNode summary = file(b, user, perms, runReport(b, user, perms, SUMMARY, PERIOD, "json"));
        JsonNode par = file(b, user, perms, par(b, user, perms, Map.of(), "json"));
        JsonNode parTotal = table(par, "par").get("rows").get(0);
        JsonNode risk = table(summary, "risk").get("rows");
        JsonNode loans = table(summary, "loans").get("rows");
        assertThat(risk.get(0).get(1).asLong())
                .isEqualTo(parTotal.get(5).asLong())
                .isEqualTo(800);
        assertThat(risk.get(1).get(1).asLong())
                .isEqualTo(parTotal.get(7).asLong())
                .isEqualTo(300);
        assertThat(loans.get(0).get(2).asLong()).isEqualTo(parTotal.get(2).asLong());
        JsonNode page = insightsPortfolio(b, user, perms, null, "");
        assertThat(risk.get(0).get(1).asLong()).isEqualTo(metric(page, "portfolio.par30"));
        assertThat(risk.get(1).get(1).asLong()).isEqualTo(metric(page, "portfolio.par90"));
    }

    @Test
    void thePdfCarriesTheCoverNoteAndTheWorkbookTheSameFigures() throws Exception {
        Book b = summaryBook();
        UUID user = UUID.randomUUID();
        JsonNode pdfRun = runReport(b, user, COMPLIANCE, SUMMARY, PERIOD, "pdf");
        assertThat(pdfRun.get("status").asString()).as("%s", pdfRun).isEqualTo("ready");
        byte[] pdf =
                downloadBytes(b.t(), user, COMPLIANCE, pdfRun.get("document_id").asString());
        ReportFormatsIT.sample("regulatory-summary.pdf", pdf);
        assertThat(new PdfProbe(pdf).text())
                .contains("REGULATOR DATA SUMMARY")
                .contains("Data summary. Not a filed return.")
                .contains("Portfolio at risk over 30 days (PAR 30) 8.0%")
                .contains("Portfolio at risk over 90 days (PAR 90) 3.0%")
                .contains("Active loans and principal outstanding 6 10,000,000")
                .doesNotContain(" - ")
                // The gender table may run onto a second page, where its heading repeats.
                .contains("Women 3")
                .contains("Men 2 Other 0 Not recorded 1 Total 6")
                .contains("Test Product per term 6 10,000,000 10.0% 15.0% 11.3%");
        JsonNode xlsxRun = runReport(b, user, COMPLIANCE, SUMMARY, PERIOD, "xlsx");
        byte[] x = downloadBytes(
                b.t(), user, COMPLIANCE, xlsxRun.get("document_id").asString());
        ReportFormatsIT.sample("regulatory-summary.xlsx", x);
        Sheet sh = XlsxReader.read(x).sheet("Loans, savings and investments");
        assertThat(sh.text("A2")).isEqualTo("Active loans and principal outstanding");
        assertThat(sh.text("C2")).isEqualTo("10000000");
    }

    @Test
    void theCatalogueOffersBothReportsUnderTheirPermissions() {
        Book b = goldenBook();
        JsonNode members = call(HttpMethod.GET, "/api/v1/reports", b.t(), UUID.randomUUID(), MEMBERS)
                .getBody();
        assertThat(members.toString()).contains(REGISTER).doesNotContain(SUMMARY);
        JsonNode compliance = call(HttpMethod.GET, "/api/v1/reports", b.t(), UUID.randomUUID(), COMPLIANCE)
                .getBody();
        assertThat(compliance.toString()).contains(SUMMARY).doesNotContain(REGISTER);
        for (JsonNode e : compliance) {
            if (e.get("key").asString().equals(SUMMARY)) {
                assertThat(e.get("formats"))
                        .extracting(JsonNode::asString)
                        .containsExactly("csv", "json", "pdf", "xlsx");
            }
        }
        // Without insights access the summary is refused, as the other portfolio reports.
        assertThat(post(b.t(), UUID.randomUUID(), "lending.reports.compliance", SUMMARY, Map.of(), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    UUID item(
            TestDatabase.Fixture t,
            UUID branch,
            String memberNo,
            String type,
            String description,
            String reference,
            String custody,
            String location,
            Long estimated) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_collateral_items (id, tenant_id, branch_id, member_id, collateral_type, description,
                            reference_no, reference_no_normalised, estimated_value_minor, currency, custody_status, storage_location)
                        SELECT ?, ?, ?, m.id, ?, ?, ?, ?, ?, 'UGX', ?, ?
                          FROM lending_members m WHERE m.tenant_id = ? AND m.member_no = ?
                        """)
                .params(
                        id,
                        t.tenantId(),
                        branch,
                        type,
                        description,
                        reference,
                        reference,
                        estimated,
                        custody,
                        location,
                        t.tenantId(),
                        memberNo)
                .update();
        return id;
    }

    void valuation(TestDatabase.Fixture t, UUID item, LocalDate on, long value) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_collateral_valuations (id, tenant_id, collateral_id, valued_on, market_value_minor,
                                                                   recorded_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), item, Date.valueOf(on), value, UUID.randomUUID())
                .update();
    }

    /** A pledge is only written on a draft loan (V8's trigger), so the loan is a draft for the insert and then put back. */
    void pledge(TestDatabase.Fixture t, String loanNo, UUID item) {
        var owner = TestDatabase.owner();
        String status = owner.sql("SELECT status FROM lending_loans WHERE tenant_id = ? AND loan_no = ?")
                .params(t.tenantId(), loanNo)
                .query(String.class)
                .single();
        String set = "UPDATE lending_loans SET status = ? WHERE tenant_id = ? AND loan_no = ?";
        owner.sql(set).params("draft", t.tenantId(), loanNo).update();
        owner.sql("""
                        INSERT INTO lending_loan_collateral (id, tenant_id, loan_id, collateral_id, pledged_value_minor)
                        SELECT ?, ?, l.id, ?, 100000 FROM lending_loans l WHERE l.tenant_id = ? AND l.loan_no = ?
                        """)
                .params(UUID.randomUUID(), t.tenantId(), item, t.tenantId(), loanNo)
                .update();
        owner.sql(set).params(status, t.tenantId(), loanNo).update();
    }

    void event(TestDatabase.Fixture t, UUID item, String type, String note) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_collateral_events (id, tenant_id, collateral_id, event_type, note, occurred_at,
                                                               recorded_by)
                        VALUES (?, ?, ?, ?, ?, now(), ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), item, type, note, UUID.randomUUID())
                .update();
    }

    /** A historic entry (no journal) on a golden loan. */
    void txn(TestDatabase.Fixture t, String loanNo, String type, long amount, LocalDate on) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_loan_transactions (id, tenant_id, branch_id, loan_id, txn_type, amount_minor,
                            currency, value_date, source, is_historic)
                        SELECT ?, ?, l.branch_id, l.id, ?, ?, 'UGX', ?, 'staff', true
                          FROM lending_loans l WHERE l.tenant_id = ? AND l.loan_no = ?
                        """)
                .params(UUID.randomUUID(), t.tenantId(), type, amount, Date.valueOf(on), t.tenantId(), loanNo)
                .update();
    }

    UUID staff(TestDatabase.Fixture t, String name) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?, 'active')")
                .params(id, t.tenantId(), name, id + "@example.test")
                .update();
        return id;
    }

    void role(TestDatabase.Fixture t, UUID user, UUID branch, boolean revoked) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by, revoked_at,
                                                           revoked_by)
                        VALUES (?, ?, ?, 'loan_officer', ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        user,
                        branch,
                        user,
                        revoked ? java.sql.Timestamp.from(java.time.Instant.parse("2026-10-01T08:00:00Z")) : null,
                        revoked ? user : null)
                .update();
    }
}
