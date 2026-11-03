package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Cell;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Sheet;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The monthly report pack (extras slice D5, issue #229; ADR-038 decision 4; chapter 14 section 14.10; FR-RPT-01,
 * FR-RPT-02, FR-RPT-04, FR-RPT-05): one run over the eight lending reports from one snapshot, as one PDF (cover,
 * contents, sign off, then every report) and one workbook (a Parameters sheet, then every report's sheets). The figures
 * of the reports agree with each other and with the insights page; the pack needs every report's permission; names and
 * phones follow the requester's branches and are checked again at download; a loan officer gets only their own loans; a
 * past period end reads its nightly snapshot; both files go after 30 days; and the requester is told the pack is ready.
 * The book is the fabricated golden dataset of {@link LendingBookSupport}; the business date is 8 October 2026.
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
class LendingPackIT extends LendingBookSupport {

    static final String PACK = "lending.monthly_pack";

    /** Every report's permission, the insights scope, and names and phones in every branch. */
    static final String ALL = "lending.reports.portfolio,lending.reports.collections,lending.reports.members,"
            + "lending.reports.compliance,lending.insights.read,lending.insights.all_officers,lending.members.read,"
            + "lending.members.contact";

    static final String NO_COMPLIANCE = ALL.replace("lending.reports.compliance,", "");
    static final String NO_CONTACT = ALL.replace(",lending.members.contact", "");
    static final String NO_NAMES = NO_CONTACT.replace(",lending.members.read", "");
    static final String OWN_LOANS = ALL.replace("lending.insights.all_officers,", "");

    static final Map<String, Object> OCTOBER = Map.of("period_end", "2026-10-08");

    @Autowired
    ApplicationContext context;

    /** The active book of the golden dataset: six loans, 10,000,000 outstanding. */
    static final String PRINCIPAL = "10000000";

    /** PAR 30: LN0005 (31 days late, 500,000) and LN0006 (91 days, 300,000) over 10,000,000, 800 basis points. */
    static final String PAR30 = "0.08";

    // ---- the pack ------------------------------------------------------------------------------------------

    @Test
    void theFiguresOfThePackAgreeAcrossItsReportsAndWithTheInsightsPage() throws Exception {
        Book b = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode run = runReport(b, user, ALL, PACK, OCTOBER, "pack");
        assertReady(run, "the pack");
        assertThat(run.get("format").asString()).isEqualTo("pack");
        assertThat(run.get("as_of").asString()).isEqualTo("2026-10-08");
        assertThat(run.get("files")).hasSize(2);
        assertThat(run.get("files").get(0).get("format").asString()).isEqualTo("pdf");
        assertThat(run.get("files").get(1).get("format").asString()).isEqualTo("xlsx");
        assertThat(run.get("files").get(0).get("document_id").asString())
                .isEqualTo(run.get("document_id").asString());

        XlsxReader book = workbook(b, user, ALL, run);
        assertThat(book.sheets.stream().map(Sheet::name).toList())
                .startsWith("Parameters", "1 Loan portfolio", "2 Portfolio at risk", "2 Ageing of the active loans")
                .contains(
                        "3 Expected and collected",
                        "4 Loans in arrears",
                        "5 Loans disbursed",
                        "5 Summary",
                        "6 Officer performance",
                        "7 What needs attention",
                        "7 Collateral items",
                        "8 Portfolio at risk");

        // Principal outstanding: the portfolio, PAR and officer reports, the regulator summary and the page.
        JsonNode page = insights(b, user, "2026-10-08", "2026-10-08");
        assertThat(String.valueOf(metric(page, "portfolio.principal_outstanding")))
                .isEqualTo(PRINCIPAL);
        assertThat(total(book.sheet("1 Loan portfolio"), "D")).isEqualTo(PRINCIPAL);
        assertThat(total(book.sheet("2 Portfolio at risk"), "C")).isEqualTo(PRINCIPAL);
        assertThat(total(book.sheet("6 Officer performance"), "C")).isEqualTo(PRINCIPAL);
        // A sheet name holds 31 characters, so the summary's first table is cut.
        assertThat(labelled(
                        book.sheet("8 Loans, savings and investment"), "Active loans and principal outstanding", "C"))
                .isEqualTo(PRINCIPAL);

        // PAR 30, computed by hand, in the PAR report, the officer report and the summary, and equal to the page.
        assertThat(ReportFormats.plainRate(metric(page, "portfolio.par30"))).isEqualTo(PAR30);
        assertThat(total(book.sheet("2 Portfolio at risk"), "F")).isEqualTo(PAR30);
        assertThat(total(book.sheet("6 Officer performance"), "D")).isEqualTo(PAR30);
        assertThat(labelled(book.sheet("8 Portfolio at risk"), "Portfolio at risk over 30 days (PAR 30)", "B"))
                .isEqualTo(PAR30);

        // The month's flows: expected and the collection rate, and disbursed, in each report that prints them, as
        // the page.
        JsonNode month = insights(b, user, "2026-10-01", "2026-10-08");
        assertThat(number(total(book.sheet("3 Expected and collected"), "B")))
                .isEqualTo(metric(month, "portfolio.expected"));
        assertThat(total(book.sheet("3 Expected and collected"), "D"))
                .isEqualTo(total(book.sheet("6 Officer performance"), "I"));
        assertThat(number(total(book.sheet("5 Summary"), "C"))).isEqualTo(metric(month, "portfolio.disbursed"));
        assertThat(number(
                        labelled(book.sheet("8 Loans, savings and investment"), "Loans disbursed in the period", "C")))
                .isEqualTo(metric(month, "portfolio.disbursed"));

        // The Parameters sheet names the pack, its date and every report with its sheets.
        Sheet parameters = book.sheet("Parameters");
        assertThat(texts(parameters))
                .contains("Monthly report pack", PACK, "Reports in this pack", "8. Regulator data summary")
                .anyMatch(s -> s.startsWith("Sheets: 1 Loan portfolio"));
    }

    @Test
    void thePdfHasACoverWithContentsAndSignOffThenEveryReport() throws Exception {
        Book b = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode run = runReport(b, user, ALL, PACK, OCTOBER, "pack");
        assertReady(run, "the pack");
        byte[] bytes = downloadBytes(
                b.t(), user, ALL, run.get("files").get(0).get("document_id").asString());
        PdfProbe.sample("monthly-pack.pdf", bytes);
        PdfProbe pdf = new PdfProbe(bytes);
        List<String> pages = pdf.pages();
        String cover = pages.getFirst();
        assertThat(cover)
                .contains("MONTHLY REPORT PACK")
                .contains("Period end 8 Oct 2026")
                .contains("Contents")
                .contains("Loan portfolio outstanding")
                .contains("Regulator data summary")
                .contains("Sign off")
                .contains("Prepared by")
                .contains("Approved by")
                .contains("Every report in this pack was read from the books at the same moment")
                .contains("cover, page 1 of 1");
        assertThat(pdf.text())
                .contains("PORTFOLIO AT RISK AND AGEING")
                .contains("COLLATERAL REGISTER")
                .contains("Data summary. Not a filed return.")
                .contains("part 1 of 8, page 1 of")
                .contains("part 8 of 8, page 1 of");
        assertThat(pdf.allFontsEmbedded()).isTrue();
        // Every part starts where the contents say: the second report starts on the page after the first ends.
        int start = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).contains("part 2 of 8, page 1 of")) {
                start = i + 1;
                break;
            }
        }
        assertThat(start).isGreaterThan(2);
        assertThat(cover).containsPattern("2 Portfolio at risk and ageing 8 Oct 2026 \\d+ " + start + "\\b");
        // A blank, not a dash, where a figure is missing: no expected amount this month.
        assertThat(pdf.text()).doesNotContain(" - ");
    }

    // ---- permissions -------------------------------------------------------------------------------------

    @Test
    void aRequesterMissingOneReportPermissionCannotRunThePackButCanRunTheReports() {
        Book b = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode all = call(HttpMethod.GET, "/api/v1/reports", b.t(), user, ALL).getBody();
        JsonNode pack = null;
        for (JsonNode e : all) {
            if (e.get("key").asString().equals(PACK)) {
                pack = e;
            }
        }
        assertThat(pack).as("the pack is offered with every permission").isNotNull();
        assertThat(pack.get("formats")).extracting(JsonNode::asString).containsExactly("pack");

        assertThat(call(HttpMethod.GET, "/api/v1/reports", b.t(), user, NO_COMPLIANCE)
                        .getBody()
                        .toString())
                .doesNotContain(PACK)
                .contains("lending.par");
        ResponseEntity<JsonNode> refused = post(b.t(), user, NO_COMPLIANCE, PACK, OCTOBER, "pack");
        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("permission_denied");
        assertThat(post(b.t(), user, NO_COMPLIANCE, "lending.par", Map.of(), "pdf")
                        .getStatusCode()
                        .value())
                .isEqualTo(202);
        // A pack is offered only as a pack, and a report never as one.
        assertThat(post(b.t(), user, ALL, PACK, OCTOBER, "pdf").getStatusCode().value())
                .isEqualTo(422);
        assertThat(post(b.t(), user, ALL, "lending.par", Map.of(), "pack")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        // A future period end is refused, as the reports refuse it.
        assertThat(post(b.t(), user, ALL, PACK, Map.of("period_end", "2026-10-31"), "pack")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    @Test
    void namesAndPhonesFollowTheRequestersBranchesAndAreCheckedAgainAtDownload() throws Exception {
        Book b = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode run = runReport(b, user, NO_CONTACT, PACK, OCTOBER, "pack");
        assertReady(run, "names without phones");
        List<String> arrears = texts(workbook(b, user, NO_CONTACT, run).sheet("4 Loans in arrears"));
        assertThat(arrears).contains("Test Borrower LN0005", "+256*****0001").doesNotContain("+256700000001");

        // Without the name permission now, neither file of the pack can be fetched.
        for (JsonNode file : run.get("files")) {
            assertThat(call(
                                    HttpMethod.POST,
                                    "/api/v1/documents/"
                                            + file.get("document_id").asString() + "/download-url",
                                    b.t(),
                                    user,
                                    NO_NAMES)
                            .getStatusCode()
                            .value())
                    .isEqualTo(404);
        }

        UUID other = UUID.randomUUID();
        JsonNode nameless = runReport(b, other, NO_NAMES, PACK, OCTOBER, "pack");
        assertReady(nameless, "no names");
        XlsxReader book = workbook(b, other, NO_NAMES, nameless);
        assertThat(texts(book.sheet("4 Loans in arrears"))).noneMatch(s -> s.contains("Test Borrower"));
    }

    @Test
    void aLoanOfficerGetsOnlyTheirOwnLoansInEveryReport() throws Exception {
        Book b = goldenBook();
        UUID officer = b.officerA();
        JsonNode run = runReport(b, officer, OWN_LOANS, PACK, OCTOBER, "pack");
        assertReady(run, "an officer's pack");
        XlsxReader book = workbook(b, officer, OWN_LOANS, run);
        // Officer A: LN0001, LN0002 and LN0003, 7,200,000; none of them over 30 days late.
        assertThat(total(book.sheet("1 Loan portfolio"), "D")).isEqualTo("7200000");
        assertThat(total(book.sheet("6 Officer performance"), "C")).isEqualTo("7200000");
        assertThat(texts(book.sheet("4 Loans in arrears"))).contains("LN0003").doesNotContain("LN0005", "LN0006");
        assertThat(texts(book.sheet("Parameters"))).anyMatch(s -> s.contains("your own loans only"));
        // The page agrees for the same caller.
        assertThat(String.valueOf(metric(
                        insights(b, officer, OWN_LOANS, "2026-10-08", "2026-10-08"),
                        "portfolio.principal_outstanding")))
                .isEqualTo("7200000");
    }

    // ---- dates, expiry and the notice ------------------------------------------------------------------

    @Test
    void aPastPeriodEndReadsItsNightlySnapshotOrIsRefused() throws Exception {
        Book b = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode missing = runReport(b, user, ALL, PACK, Map.of("period_end", "2026-09-30"), "pack");
        assertThat(missing.get("status").asString()).isEqualTo("failed");
        assertThat(missing.get("error").asString()).isEqualTo("snapshot_unavailable");

        tenantJobs.callAs(
                b.t().tenantId(),
                () -> new TransactionTemplate(transactions)
                        .execute(x -> snapshots.backfill(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30))));
        JsonNode run = runReport(b, user, ALL, PACK, Map.of("period_end", "2026-09-30"), "pack");
        assertReady(run, "September's pack");
        XlsxReader book = workbook(b, user, ALL, run);
        assertThat(texts(book.sheet("Parameters")))
                .anyMatch(s -> s.contains("nightly snapshot of 2026-09-30"))
                .anyMatch(s -> s.startsWith("Sheets: 3 Expected and collected"));
        assertThat(total(book.sheet("1 Loan portfolio"), "D")).isEqualTo(PRINCIPAL);
    }

    @Test
    void bothFilesAreDeletedAfterThirtyDaysAndTheRequesterIsToldThePackIsReady() throws Exception {
        Book b = goldenBook();
        UUID user = UUID.randomUUID();
        String email = "pack-" + user + "@example.com";
        TestDatabase.owner().sql("""
                        INSERT INTO users (id, tenant_id, kind, full_name, email, status)
                        VALUES (?, ?, 'staff', 'Test Requester', ?, 'active')
                        """).params(user, b.t().tenantId(), email).update();
        JsonNode run = runReport(b, user, ALL, PACK, OCTOBER, "pack");
        assertReady(run, "the pack");
        assertThat(TestDatabase.owner()
                        .sql("SELECT template_key FROM notification_outbox WHERE recipient = ?")
                        .param(email)
                        .query(String.class)
                        .list())
                .containsExactly("core.report_pack_ready");
        String params = TestDatabase.owner()
                .sql("SELECT params::text FROM notification_outbox WHERE recipient = ?")
                .param(email)
                .query(String.class)
                .single();
        assertThat(params)
                .contains("monthly report pack")
                .contains("8 Oct 2026")
                .doesNotContain("Test Borrower");

        List<String> keys = new ArrayList<>();
        for (JsonNode file : run.get("files")) {
            keys.add(objectKey(file.get("document_id").asString()));
        }
        assertThat(keys).hasSize(2).allMatch(this::stored);
        clock.set(TestReports.START.plus(Duration.ofDays(31)));
        assertThat(tenantJobs.callAs(b.t().tenantId(), expiry::expireDue)).isEqualTo(1);
        assertThat(keys).as("both files are gone").noneMatch(this::stored);
        JsonNode view = run(b.t(), user, ALL, run.get("id").asString());
        assertThat(view.get("expired").asBoolean()).isTrue();
        assertThat(view.get("files")).isEmpty();
        for (JsonNode file : run.get("files")) {
            assertThat(call(
                                    HttpMethod.POST,
                                    "/api/v1/documents/"
                                            + file.get("document_id").asString() + "/download-url",
                                    b.t(),
                                    user,
                                    ALL)
                            .getStatusCode()
                            .value())
                    .isEqualTo(410);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    XlsxReader workbook(Book b, UUID user, String permissions, JsonNode run) {
        return XlsxReader.read(downloadBytes(
                b.t(),
                user,
                permissions,
                run.get("files").get(1).get("document_id").asString()));
    }

    JsonNode insights(Book b, UUID user, String from, String to) {
        return insights(b, user, ALL, from, to);
    }

    JsonNode insights(Book b, UUID user, String permissions, String from, String to) {
        return http.exchange(
                        "/api/v1/lending/insights/portfolio?from=" + from + "&to=" + to,
                        HttpMethod.GET,
                        new HttpEntity<>(headers(b.t(), user, permissions)),
                        JsonNode.class)
                .getBody();
    }

    /** The value in {@code column} of a sheet's last row, its total. */
    static String total(Sheet sheet, String column) {
        Map<String, Cell> last = sheet.rows().getLast();
        for (Map.Entry<String, Cell> e : last.entrySet()) {
            if (e.getKey().replaceAll("\\d", "").equals(column)) {
                return e.getValue().value();
            }
        }
        return null;
    }

    /** The value in {@code column} of the row whose first cell reads {@code label}. */
    static String labelled(Sheet sheet, String label, String column) {
        for (Map<String, Cell> row : sheet.rows()) {
            Cell first = row.values().iterator().next();
            if (label.equals(first.value())) {
                for (Map.Entry<String, Cell> e : row.entrySet()) {
                    if (e.getKey().replaceAll("\\d", "").equals(column)) {
                        return e.getValue().value();
                    }
                }
            }
        }
        throw new AssertionError("no row " + label + " in " + sheet.name());
    }

    /** Every value of a sheet, text and numbers. */
    static List<String> texts(Sheet sheet) {
        List<String> out = new ArrayList<>();
        for (Map<String, Cell> row : sheet.rows()) {
            for (Cell c : row.values()) {
                if (c.value() != null) {
                    out.add(c.value());
                }
            }
        }
        return out;
    }

    /** A workbook amount in whole units (UGX has no minor unit), as the page's integer; a blank cell is 0. */
    static long number(String value) {
        return value == null || value.isEmpty() ? 0 : Long.parseLong(value);
    }

    private String objectKey(String documentId) {
        return TestDatabase.owner()
                .sql("SELECT object_key FROM documents WHERE id = ?")
                .param(UUID.fromString(documentId))
                .query(String.class)
                .single();
    }

    /** Whether the fake object store still holds the key (the interface is package-private to core.documents). */
    private boolean stored(String key) {
        try {
            Class<?> type = Class.forName("com.rincoltech.bms.core.documents.internal.ObjectStorage");
            Object storage = context.getBean(type);
            var get = type.getDeclaredMethod("get", String.class);
            get.setAccessible(true);
            return ((java.util.Optional<?>) get.invoke(storage, key)).isPresent();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
