package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code lending.par}, the first report on the framework (extras slice D1, issue #214; FR-RPT-05, FR-RPT-02; ADR-038
 * decision 3; chapter 14 section 14.3). It lives in this package because it drives the runner, which is internal to
 * {@code core.reporting}.
 *
 * <p>The golden dataset is the worked example of chapter 14: six active loans whose principal outstanding totals
 * 10,000,000, of which 1,000,000 is 30 days past due, 500,000 is 31, and 300,000 is 91, so PAR 1 is 18.0 percent, PAR
 * 30 is 8.0 percent and PAR 60 and 90 are 3.0 percent; plus a written-off loan that must count in neither. Every figure below was computed by hand and is written as a number, not recomputed by the code under test.
 * The business date is 2026-10-08 (the movable clock of {@link TestReports}), so nothing depends on the day it runs.
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
class LendingParReportIT extends LendingBookSupport {
    // ---- golden figures ----------------------------------------------------------------------------

    @Test
    void theWorkedExampleOfChapter14GivesTheHandComputedParAndAgeing() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();

        JsonNode run = par(book, user, PAR_PERMS, Map.of(), "json");
        JsonNode file = file(book, user, PAR_PERMS, run);

        // Parameters and dates are echoed (FR-RPT-02), with the defaults filled in.
        assertThat(file.get("report_key").asString()).isEqualTo("lending.par");
        assertThat(file.get("parameters").get("as_at").asString()).isEqualTo("2026-10-08");
        assertThat(file.get("parameters").get("group_by").asString()).isEqualTo("none");
        assertThat(file.get("as_of").asString()).isEqualTo("2026-10-08");
        assertThat(file.get("notes").toString()).contains("loan tables").contains("written-off loans are not");

        // Group, active loans, principal outstanding, arrears, PAR 1, 30, 60, 90 in basis points.
        assertThat(rows(table(file, "par"))).containsExactly("[\"All loans\",6,10000000,1860000,1800,800,300,300]");
        assertThat(table(file, "par").get("columns").get(4).get("kind").asString())
                .isEqualTo("basis_points");
        // Group, age, loans, principal outstanding, share of principal in basis points, arrears.
        assertThat(rows(table(file, "ageing")))
                .containsExactly(
                        "[\"All loans\",\"Current (not late)\",3,8200000,8200,0]",
                        "[\"All loans\",\"1 to 30 days late\",1,1000000,1000,1050000]",
                        "[\"All loans\",\"31 to 60 days late\",1,500000,500,510000]",
                        "[\"All loans\",\"61 to 90 days late\",0,0,0,0]",
                        "[\"All loans\",\"Over 90 days late\",1,300000,300,300000]");
        assertThat(run.get("row_count").asInt()).isEqualTo(6);
    }

    @Test
    void theCsvIsTheSameFiguresWithTheParametersAndTheCurrencyInTheHeader() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode run = par(book, user, PAR_PERMS, Map.of("group_by", "branch"), "csv");
        assertThat(run.get("status").asString()).isEqualTo("ready");
        String csv = download(book.t(), user, PAR_PERMS, run.get("document_id").asString());

        assertThat(csv)
                .contains("Report key,lending.par\r\n")
                .contains("As at,2026-10-08\r\n")
                .contains("Parameter group_by,branch\r\n")
                .contains(
                        "Group,Active loans,Principal outstanding (UGX minor units),Arrears (UGX minor units),PAR 1 (basis points),PAR 30 (basis points),PAR 60 (basis points),PAR 90 (basis points)\r\n")
                .contains("Head Office,3,7200000,1050000,1389,0,0,0\r\n")
                .contains("Test Branch Two,3,2800000,810000,2857,2857,1071,1071\r\n")
                .contains("Total,6,10000000,1860000,1800,800,300,300\r\n");
    }

    @Test
    void groupedByBranchEachGroupHasItsOwnHandComputedShares() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode file = file(book, user, PAR_PERMS, par(book, user, PAR_PERMS, Map.of("group_by", "branch"), "json"));

        // Head office: 7,200,000, of which 1,000,000 is 30 days late: PAR 1 = 13.89 percent, none over 30.
        // Test Branch Two: 2,800,000, of which 800,000 is late (28.57 percent), 300,000 over 60 (10.71 percent).
        assertThat(rows(table(file, "par")))
                .containsExactly(
                        "[\"Head Office\",3,7200000,1050000,1389,0,0,0]",
                        "[\"Test Branch Two\",3,2800000,810000,2857,2857,1071,1071]",
                        "[\"Total\",6,10000000,1860000,1800,800,300,300]");
        assertThat(rows(table(file, "ageing")))
                .contains(
                        "[\"Head Office\",\"1 to 30 days late\",1,1000000,1389,1050000]",
                        "[\"Test Branch Two\",\"31 to 60 days late\",1,500000,1786,510000]",
                        "[\"Test Branch Two\",\"Over 90 days late\",1,300000,1071,300000]");
    }

    @Test
    void groupedByOfficerEachOfficerIsAGroup() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode file = file(book, user, PAR_PERMS, par(book, user, PAR_PERMS, Map.of("group_by", "officer"), "json"));
        // The officers are not user rows, so each is labelled by the last four characters of its id; two groups plus
        // the total.
        JsonNode par = table(file, "par");
        assertThat(par.get("rows")).hasSize(3);
        assertThat(par.get("rows").get(0).get(1).asInt()
                        + par.get("rows").get(1).get(1).asInt())
                .isEqualTo(6);
        assertThat(par.get("rows").get(2).get(0).asString()).isEqualTo("Total");
    }

    // ---- scope ----------------------------------------------------------------------------------

    @Test
    void aBranchScopedUserGetsOnlyTheirBranchWhateverTheyAsk() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        // The user holds the permissions at the head office only.
        var h = headers(book.t(), user, PAR_PERMS);
        h.set("X-Dev-Branch-Ids", book.t().headOffice().toString());
        // Asking for every branch, and asking for the other branch by name, both give the head office alone.
        for (Map<String, Object> params : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of(
                        "branch_id", List.of(book.t().secondBranch().toString())))) {
            ResponseEntity<JsonNode> posted = http.exchange(
                    "/api/v1/report-runs",
                    HttpMethod.POST,
                    new HttpEntity<>(body("lending.par", params, "json"), h),
                    JsonNode.class);
            assertThat(posted.getStatusCode().value())
                    .as("%s", posted.getBody())
                    .isEqualTo(202);
            drain();
            JsonNode run =
                    run(book.t(), user, PAR_PERMS, posted.getBody().get("id").asString());
            assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
            JsonNode file = mapper.readTree(
                    download(book.t(), user, PAR_PERMS, run.get("document_id").asString()));
            if (params.isEmpty()) {
                assertThat(rows(table(file, "par"))).containsExactly("[\"All loans\",3,7200000,1050000,1389,0,0,0]");
            } else {
                assertThat(rows(table(file, "par"))).containsExactly("[\"All loans\",0,0,0,null,null,null,null]");
            }
        }
    }

    @Test
    void withoutAllOfficersAUserSeesOnlyTheirOwnLoans() throws Exception {
        Book book = goldenBook();
        String perms = PORTFOLIO + ",lending.insights.read";
        JsonNode file = file(book, book.officerB(), perms, par(book, book.officerB(), perms, Map.of(), "json"));
        // Officer B's three loans: 2,000,000 current, 500,000 at 31 days, 300,000 at 91: 800,000 of 2,800,000 late.
        assertThat(rows(table(file, "par"))).containsExactly("[\"All loans\",3,2800000,810000,2857,2857,1071,1071]");
    }

    @Test
    void aUserWithoutInsightsAccessCannotQueueThePortfolioReport() {
        Book book = goldenBook();
        ResponseEntity<JsonNode> refused =
                post(book.t(), UUID.randomUUID(), PORTFOLIO, "lending.par", Map.of(), "json");
        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("permission_denied");
    }

    // JUSTIFICATION-A3: new cases for a run that crosses midnight and for who may download after their access narrowed.
    @Test
    void aRunQueuedJustBeforeMidnightAndExecutedAfterItStillReadsTheLoanTablesOfTheDayItWasAskedOn() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        // 23:59:30 in Kampala on 2026-10-08 (20:59:30 UTC): the default date is that day.
        clock.set(java.time.Instant.parse("2026-10-08T20:59:30Z"));
        ResponseEntity<JsonNode> posted = post(book.t(), user, PAR_PERMS, "lending.par", Map.of(), "json");
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        assertThat(posted.getBody().get("as_of").asString()).isEqualTo("2026-10-08");
        // The 9th's first disbursement lands, and the runner gets to the run after midnight (00:00:30 on 2026-10-09),
        // before
        // tonight's snapshot job has written 2026-10-08: the loan tables are no longer the 8th's book, so the run is
        // refused
        // with a retry hint and never prints the 9th's figures under the 8th.
        loanOfMember(
                book.t(),
                book.product(),
                book.t().headOffice(),
                book.officerA(),
                "LN0009",
                "MLN0002",
                5_000_000,
                LocalDate.of(2026, 10, 9));
        clock.set(java.time.Instant.parse("2026-10-08T21:00:30Z"));
        drain();
        JsonNode refused =
                run(book.t(), user, PAR_PERMS, posted.getBody().get("id").asString());
        assertThat(refused.get("status").asString()).as("%s", refused).isEqualTo("failed");
        assertThat(refused.get("error").asString()).isEqualTo("report_stale_as_of");

        // Once the nightly job has written the 8th, the same request, made at 23:59:30 and run after midnight, is the
        // 8th's
        // book from the snapshot: six loans and 10,000,000, without the loan of the 9th.
        tenantJobs.callAs(
                book.t().tenantId(),
                () -> new TransactionTemplate(transactions)
                        .execute(x -> snapshots.backfill(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8))));
        clock.set(java.time.Instant.parse("2026-10-08T20:59:30Z"));
        posted = post(book.t(), user, PAR_PERMS, "lending.par", Map.of(), "json");
        clock.set(java.time.Instant.parse("2026-10-08T21:00:30Z"));
        drain();

        JsonNode run = run(book.t(), user, PAR_PERMS, posted.getBody().get("id").asString());
        assertThat(run.get("status").asString())
                .as("not snapshot_unavailable: %s", run)
                .isEqualTo("ready");
        JsonNode file = mapper.readTree(
                download(book.t(), user, PAR_PERMS, run.get("document_id").asString()));
        assertThat(file.get("parameters").get("as_at").asString()).isEqualTo("2026-10-08");
        assertThat(file.get("notes").toString()).contains("nightly snapshot of 2026-10-08");
        assertThat(rows(table(file, "par"))).containsExactly("[\"All loans\",6,10000000,1860000,1800,800,300,300]");
    }

    private ResponseEntity<JsonNode> downloadUrl(
            Book book, UUID user, String permissions, String branches, String documentId) {
        var h = headers(book.t(), user, permissions);
        h.set("X-Dev-Branch-Ids", branches);
        return http.exchange(
                "/api/v1/documents/" + documentId + "/download-url",
                HttpMethod.POST,
                new HttpEntity<>(h),
                JsonNode.class);
    }

    @Test
    void aFileIsNotDownloadedByARequesterWhoseAccessHasNarrowedBelowTheScopeItWasMadeUnder() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode wide = par(book, user, PAR_PERMS, Map.of(), "json");
        String wideDoc = wide.get("document_id").asString();
        String head = book.t().headOffice().toString();

        assertThat(downloadUrl(book, user, PAR_PERMS, "*", wideDoc)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        // Moved from every branch to one: the tenant-wide file is no longer theirs, and it looks like no file at all.
        ResponseEntity<JsonNode> narrowed = downloadUrl(book, user, PAR_PERMS, head, wideDoc);
        assertThat(narrowed.getStatusCode().value()).isEqualTo(404);
        // Who now sees only their own loans cannot keep a file made for everyone's.
        assertThat(downloadUrl(book, user, PORTFOLIO + ",lending.insights.read", "*", wideDoc)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);

        // A file made under one branch is still theirs when they have all of them, and when they still have that one.
        var h = headers(book.t(), user, PAR_PERMS);
        h.set("X-Dev-Branch-Ids", head);
        ResponseEntity<JsonNode> posted = http.exchange(
                "/api/v1/report-runs",
                HttpMethod.POST,
                new HttpEntity<>(body("lending.par", Map.of(), "json"), h),
                JsonNode.class);
        drain();
        String narrowDoc = run(
                        book.t(), user, PAR_PERMS, posted.getBody().get("id").asString())
                .get("document_id")
                .asString();
        assertThat(downloadUrl(book, user, PAR_PERMS, "*", narrowDoc)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(downloadUrl(book, user, PAR_PERMS, head, narrowDoc)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(downloadUrl(book, user, PAR_PERMS, book.t().secondBranch().toString(), narrowDoc)
                        .getStatusCode()
                        .value())
                .as("a different branch now")
                .isEqualTo(404);

        // And while reports are switched off for the tenant nothing is downloaded.
        TestDatabase.owner()
                .sql(
                        "UPDATE tenant_settings SET settings = settings || '{\"reports_enabled\": false}' WHERE tenant_id = ?")
                .param(book.t().tenantId())
                .update();
        assertThat(downloadUrl(book, user, PAR_PERMS, "*", wideDoc)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void theScopeIsFrozenWhenTheRunIsQueued() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        var h = headers(book.t(), user, PAR_PERMS);
        h.set("X-Dev-Branch-Ids", book.t().headOffice().toString());
        ResponseEntity<JsonNode> posted = http.exchange(
                "/api/v1/report-runs",
                HttpMethod.POST,
                new HttpEntity<>(body("lending.par", Map.of(), "json"), h),
                JsonNode.class);
        String id = posted.getBody().get("id").asString();
        String scope = TestDatabase.owner()
                .sql("SELECT scope::text FROM report_runs WHERE id = ?")
                .param(UUID.fromString(id))
                .query(String.class)
                .single();
        assertThat(scope)
                .contains(book.t().headOffice().toString())
                .doesNotContain(book.t().secondBranch().toString());
        assertThat(scope).contains("2026-10-08");
    }

    // ---- dates -----------------------------------------------------------------------------------

    @Test
    void aPastDateReadsTheNightlySnapshotAndWithoutOneIsRefused() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();

        JsonNode none = par(book, user, PAR_PERMS, Map.of("as_at", "2026-10-07"), "json");
        assertThat(none.get("status").asString()).isEqualTo("failed");
        assertThat(none.get("error").asString()).isEqualTo("snapshot_unavailable");

        // The nightly job writes 2026-10-07; here its writer is called for that date.
        tenantJobs.callAs(
                book.t().tenantId(),
                () -> new TransactionTemplate(transactions)
                        .execute(x -> snapshots.backfill(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7))));
        JsonNode run = par(book, user, PAR_PERMS, Map.of("as_at", "2026-10-07"), "json");
        JsonNode file = file(book, user, PAR_PERMS, run);
        // A day earlier the same loans were one day less late: 29, 30 and 90 days. 1,500,000 is 1 to 30 late, 300,000
        // is
        // 61 to 90; none is over 30 apart from the last (PAR 30 = PAR 60 = 3.0 percent), none over 90.
        assertThat(rows(table(file, "par"))).containsExactly("[\"All loans\",6,10000000,1860000,1800,300,300,0]");
        assertThat(file.get("notes").toString()).contains("nightly snapshot of 2026-10-07");
        assertThat(run.get("as_of").asString()).isEqualTo("2026-10-07");
        // A date in the future is refused when asked.
        ResponseEntity<JsonNode> future =
                post(book.t(), user, PAR_PERMS, "lending.par", Map.of("as_at", "2026-10-09"), "json");
        assertThat(future.getStatusCode().value()).isEqualTo(422);
        assertThat(post(book.t(), user, PAR_PERMS, "lending.par", Map.of("as_at", "yesterday"), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        assertThat(post(book.t(), user, PAR_PERMS, "lending.par", Map.of("group_by", "colour"), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    // ---- the same numbers as the insights page ------------------------------------------------------

    @Test
    void theReportEqualsTheInsightsPortfolioForTheSameDateAndBranches() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        String insights = "lending.insights.read,lending.insights.all_officers";
        JsonNode page = http.exchange(
                        "/api/v1/lending/insights/portfolio?from=" + TODAY + "&to=" + TODAY,
                        HttpMethod.GET,
                        new HttpEntity<>(headers(book.t(), user, insights)),
                        JsonNode.class)
                .getBody();
        JsonNode file = file(book, user, PAR_PERMS, par(book, user, PAR_PERMS, Map.of(), "json"));
        JsonNode par = table(file, "par").get("rows").get(0);

        assertThat(par.get(2).asLong()).isEqualTo(metric(page, "portfolio.principal_outstanding"));
        assertThat(par.get(3).asLong()).isEqualTo(metric(page, "portfolio.arrears"));
        assertThat(par.get(4).asLong()).isEqualTo(metric(page, "portfolio.par1"));
        assertThat(par.get(5).asLong()).isEqualTo(metric(page, "portfolio.par30"));
        assertThat(par.get(6).asLong()).isEqualTo(metric(page, "portfolio.par60"));
        assertThat(par.get(7).asLong()).isEqualTo(metric(page, "portfolio.par90"));
        assertThat(par.get(1).asLong()).isEqualTo(metric(page, "portfolio.active_loans"));
    }

    // ---- a book past the checkpoint block --------------------------------------------------------

    @Autowired
    com.rincoltech.bms.lending.insights.InsightsFigures figures;

    // JUSTIFICATION-A3: new case; every other book in this suite is under the 1,000 loans of the port's checkpoint
    // block.
    /**
     * 2,600 loans in three statements (members, loans, schedule items), one per loan, 1,000,000 each, due 0 to 99 days ago.
     * The port reads them in blocks and calls its checkpoint every 1,000 loans: twice mid-stream, besides the start and the
     * end. A checkpoint that throws on the first mid-stream call stops the read there (the end is never reached), which is
     * how a cancelled or timed out run stops.
     */
    @Test
    void theCheckpointFiresMidStreamOnABookOfThousandsAndAThrowStopsTheRead() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        UUID product = product(t, user);
        int n = 2_600;
        var owner = TestDatabase.owner();
        owner.sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                                     national_id, currency, kyc_status, status, source)
                        SELECT md5('m' || g)::uuid, ?, ?, 'MB' || g, 'Test Borrower ' || g, '+256700000001', 'nin',
                               'CMTEST' || lpad(g::text, 8, '0'), 'UGX', 'incomplete', 'active', 'staff'
                          FROM generate_series(1, ?) g
                        """).params(t.tenantId(), t.headOffice(), n).update();
        owner.sql("""
                        INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                            officer_user_id, status, channel, purpose_category, currency, requested_principal_minor,
                            requested_term_count, term_unit, interest_method, interest_rate_bp, rate_unit, repayment_pattern,
                            disbursed_on, principal_disbursed_minor, principal_outstanding_minor, maturity_date)
                        SELECT md5('l' || g)::uuid, ?, ?, 'LB' || g, md5('m' || g)::uuid, v.id, ?, 'active', 'staff', 'business',
                               'UGX', 1000000, 1, 'month', 'flat', 1000, 'per_term', 'bullet', DATE '2026-01-05', 1000000,
                               1000000, DATE '2026-12-31'
                          FROM generate_series(1, ?) g
                          JOIN lending_loan_product_versions v ON v.product_id = ?
                        """).params(t.tenantId(), t.headOffice(), user, n, product).update();
        owner.sql("""
                        INSERT INTO lending_schedule_items (id, tenant_id, loan_id, item_no, due_date, principal_due_minor,
                                                            interest_due_minor, fees_due_minor, status)
                        SELECT md5('i' || g)::uuid, ?, md5('l' || g)::uuid, 1, DATE '2026-10-08' - (g % 100)::int, 1000000, 0, 0,
                               'due'
                          FROM generate_series(1, ?) g
                        """).params(t.tenantId(), n).update();

        var scope = new com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope(
                TODAY, TODAY, TODAY, null, null, null);
        var none = com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy.NONE;
        int[] calls = {0};
        var figuresRead = tenantJobs.callAs(t.tenantId(), () -> figures.par(scope, none, () -> calls[0]++));
        assertThat(figuresRead.total().loans()).isEqualTo(n);
        assertThat(figuresRead.total().principalOutstandingMinor()).isEqualTo(n * 1_000_000L);
        assertThat(calls[0]).as("start, 1,000th loan, 2,000th loan, end").isEqualTo(4);

        // A throw on the first mid-stream call (the second call overall) ends the read at 1,000 loans: the end is not
        // reached.
        int[] seen = {0};
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tenantJobs.callAs(
                        t.tenantId(),
                        () -> figures.par(scope, none, () -> {
                            if (++seen[0] == 2) {
                                throw new IllegalStateException("cancelled mid-stream");
                            }
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cancelled mid-stream");
        assertThat(seen[0]).as("no call after the throw").isEqualTo(2);
    }

    // ---- a book of 500 loans ---------------------------------------------------------------------

    /**
     * 500 loans, each 1,000,000 or 2,000,000 by turns, due 0 to 99 days ago by their number: the figures are summed here
     * with plain loops, never by the code under test. The table sizes stay far below the 500 row cap of this profile.
     */
    @Test
    void aBookOfFiveHundredLoansAddsUpToTheIndependentSums() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        UUID product = product(t, user);
        long po = 0;
        long[] atRisk = new long[4];
        long[] bucketPo = new long[5];
        long[] bucketLoans = new long[5];
        for (int i = 0; i < 500; i++) {
            long principal = (i % 2 == 0 ? 1_000_000L : 2_000_000L);
            int dpd = i % 100; // 0 means the item is due today, which is not late.
            loan(
                    t,
                    product,
                    i % 3 == 0 ? t.secondBranch() : t.headOffice(),
                    user,
                    String.format("LB%04d", i),
                    "active",
                    principal,
                    0,
                    0,
                    TODAY.minusDays(dpd));
            po += principal;
            int[] edges = {0, 30, 60, 90};
            for (int e = 0; e < 4; e++) {
                if (dpd > edges[e]) {
                    atRisk[e] += principal;
                }
            }
            int bucket = dpd == 0 ? 0 : dpd <= 30 ? 1 : dpd <= 60 ? 2 : dpd <= 90 ? 3 : 4;
            bucketPo[bucket] += principal;
            bucketLoans[bucket]++;
        }
        Book book = new Book(t, user, user, product);
        JsonNode file = file(book, user, PAR_PERMS, par(book, user, PAR_PERMS, Map.of("group_by", "branch"), "json"));

        JsonNode total = table(file, "par").get("rows").get(2);
        assertThat(total.get(0).asString()).isEqualTo("Total");
        assertThat(total.get(1).asLong()).isEqualTo(500);
        assertThat(total.get(2).asLong()).isEqualTo(po);
        for (int e = 0; e < 4; e++) {
            assertThat(total.get(4 + e).asLong()).as("PAR edge %d", e).isEqualTo(Math.round(atRisk[e] * 10_000.0 / po));
        }
        long loansSeen = 0;
        for (JsonNode r : table(file, "par").get("rows")) {
            if (!r.get(0).asString().equals("Total")) {
                loansSeen += r.get(1).asLong();
            }
        }
        assertThat(loansSeen).as("the branches add up to the book").isEqualTo(500);
        // The total ageing rows are the last five of the table: they equal the independent bucket sums.
        JsonNode ageing = table(file, "ageing").get("rows");
        int n = ageing.size();
        for (int b = 0; b < 5; b++) {
            JsonNode row = ageing.get(n - 5 + b);
            assertThat(row.get(0).asString()).isEqualTo("Total");
            assertThat(row.get(2).asLong()).as("loans in bucket %d", b).isEqualTo(bucketLoans[b]);
            assertThat(row.get(3).asLong()).as("principal in bucket %d", b).isEqualTo(bucketPo[b]);
        }
    }
}
