package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Cell;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Sheet;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * "Download my data" (issue #334 step 1, ADR-074, chapter 7 section 7.11.8): the workbook of a retail and of a lending
 * business, sheet by sheet with plain column names, dates and times in the business's time zone, money as whole numbers,
 * text that begins like a formula kept as text; one business never sees another's rows or file; a business whose
 * subscription has ended still gets its copy; and the weekly job queues one workbook and sends one email a week however
 * often it runs. The year split itself is proved without a database in {@link DataExportSheetsTest}. Every name and figure
 * is fabricated and the clock is the movable one of {@link TestReports} (Thursday 8 October 2026, 13:00 in Kampala).
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
class DataExportIT extends LendingBookSupport {

    static final String PERM = "core.data_export.run";
    static final String EVIL = "=HYPERLINK(\"http://example.invalid\")";

    /** 8 October 2026 as a spreadsheet date serial. */
    static final String OCT_8 = "46303";

    @Autowired
    DataExportWeekly weekly;

    @Autowired
    DataExportReport report;

    // ---- helpers -----------------------------------------------------------------------------------

    ResponseEntity<JsonNode> card(TestDatabase.Fixture t, UUID user, String permissions) {
        return call(HttpMethod.GET, "/api/v1/data-export", t, user, permissions);
    }

    ResponseEntity<JsonNode> ask(TestDatabase.Fixture t, UUID user, String permissions) {
        return call(HttpMethod.POST, "/api/v1/data-export/runs", t, user, permissions);
    }

    ResponseEntity<JsonNode> weeklyEmail(TestDatabase.Fixture t, UUID user, boolean on) {
        HttpHeaders h = headers(t, user, PERM);
        h.add("Content-Type", "application/json");
        return http.exchange(
                "/api/v1/data-export/settings",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("weekly_email", on), h),
                JsonNode.class);
    }

    /** Asks for the workbook, runs the queue and returns the caller's newest run as the card shows it. */
    JsonNode export(TestDatabase.Fixture t, UUID user) throws InterruptedException {
        ResponseEntity<JsonNode> posted = ask(t, user, PERM);
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        ResponseEntity<JsonNode> card = card(t, user, PERM);
        assertThat(card.getStatusCode().value()).as("%s", card.getBody()).isEqualTo(200);
        JsonNode latest = card.getBody().get("latest");
        assertThat(latest.get("id").asString())
                .isEqualTo(posted.getBody().get("id").asString());
        return latest;
    }

    XlsxReader workbook(TestDatabase.Fixture t, UUID user, JsonNode run) {
        assertReady(run, "the data export");
        assertThat(run.get("report_key").asString()).isEqualTo("core.data_export");
        return XlsxReader.read(
                downloadBytes(t, user, PERM, run.get("document_id").asString()));
    }

    static List<String> header(Sheet sheet) {
        return sheet.rows().get(0).values().stream().map(Cell::value).toList();
    }

    static List<String> names(XlsxReader x) {
        return x.sheets.stream().map(Sheet::name).toList();
    }

    /** One sale of one line, recorded as the importer records history, at {@code at} (UTC) on business date {@code day}. */
    static void sale(
            TestDatabase.Fixture t, UUID product, UUID user, String no, String buyer, LocalDate day, Instant at) {
        UUID sale = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, 'credit', ?, (SELECT currency FROM tenants WHERE id = ?), 5000, 3000, 0,
                            'completed', true, ?, ?)
                        """)
                .params(
                        sale,
                        t.tenantId(),
                        t.headOffice(),
                        no,
                        java.sql.Date.valueOf(day),
                        buyer,
                        t.tenantId(),
                        user,
                        Timestamp.from(at))
                .update();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (gen_random_uuid(), ?, ?, 1, ?, 5, 1000, 600, 5000, 3000)
                        """).params(t.tenantId(), sale, product).update();
    }

    record Shop(TestDatabase.Fixture t, RetailTestSupport api, UUID product) {}

    Shop shop(String code) {
        TestDatabase.Fixture t = TestDatabase.tenant("dx", false, true);
        RetailTestSupport api = new RetailTestSupport(http, t);
        UUID product = api.product(code, 600, 1000);
        api.stockUp(t.headOffice(), product, "12");
        return new Shop(t, api, product);
    }

    // ---- the workbook ------------------------------------------------------------------------------

    @Test
    void aShopGetsItsTablesAsSheetsWithPlainColumnsBusinessTimeAndWholeShillings() throws Exception {
        Shop s = shop("DX1");
        // 22:30 UTC on 7 October is 01:30 on 8 October in Kampala: the row says 8 October, 01:30.
        sale(
                s.t(),
                s.product(),
                s.api().userId(),
                "IMP-0001",
                EVIL,
                LocalDate.of(2026, 10, 8),
                Instant.parse("2026-10-07T22:30:00Z"));
        XlsxReader x = workbook(s.t(), s.api().userId(), export(s.t(), s.api().userId()));

        assertThat(names(x))
                .containsSubsequence(
                        "Parameters",
                        "Branches",
                        "Products",
                        "Suppliers",
                        "Customers",
                        "Credit buyers",
                        "Stock balances",
                        "Sales",
                        "Sale lines",
                        "Sale payments",
                        "Account payments",
                        "Restocks",
                        "Goods receipts",
                        "Cash book")
                .doesNotContain("Members", "Loans", "Repayments", "Arrears");

        Sheet sales = x.sheet("Sales");
        assertThat(header(sales))
                .containsExactly(
                        "Date",
                        "Time",
                        "Sale no",
                        "Branch",
                        "Payment",
                        "Buyer",
                        "Total (UGX)",
                        "Paid (UGX)",
                        "Due date",
                        "Status",
                        "Sold by",
                        "Void reason");
        assertThat(sales.cell("A2").value()).isEqualTo(OCT_8);
        assertThat(sales.text("B2")).isEqualTo("01:30");
        assertThat(sales.text("C2")).isEqualTo("IMP-0001");
        assertThat(sales.text("D2")).isEqualTo("Head Office");
        // Formula injection: the buyer is a string cell with the quote prefix, never a formula.
        Cell buyer = sales.cell("F2");
        assertThat(buyer.isText()).isTrue();
        assertThat(buyer.value()).isEqualTo(EVIL);
        assertThat(x.styles.get(buyer.style()).quotePrefix()).isTrue();
        assertThat(x.parts.get("xl/worksheets/sheet" + (names(x).indexOf("Sales") + 1) + ".xml"))
                .asString()
                .doesNotContain("<f>");
        // Money: UGX 5,000 is the number 5000, a numeric cell, not text and not 500000.
        assertThat(sales.cell("G2").value()).isEqualTo("5000");
        assertThat(sales.cell("G2").t()).isEmpty();
        assertThat(sales.cell("H2").value()).isEqualTo("0");

        Sheet lines = x.sheet("Sale lines");
        assertThat(header(lines))
                .contains("Sale no", "Product code", "Quantity", "Unit price (UGX)", "Line total (UGX)");

        Sheet products = x.sheet("Products");
        assertThat(header(products))
                .startsWith("Product code", "Product")
                .contains("Cost price (UGX)", "Selling price (UGX)", "Added on");
        assertThat(products.text("A2")).isEqualTo("DX1");
        assertThat(products.text("B2")).isEqualTo("Test Product DX1");

        Sheet stock = x.sheet("Stock balances");
        assertThat(header(stock)).containsExactly("Branch", "Product code", "Product", "Quantity", "Kept for orders");
        assertThat(stock.text("B2")).isEqualTo("DX1");
        assertThat(stock.cell("D2").value()).isEqualTo("12");

        assertThat(header(x.sheet("Credit buyers")))
                .containsExactly(
                        "Name",
                        "Contact",
                        "Unpaid credit sales",
                        "Owed (UGX)",
                        "Oldest due date",
                        "Credit limit (UGX)");
        assertThat(header(x.sheet("Cash book"))).startsWith("Date", "Time");
        assertThat(header(x.sheet("Branches")))
                .containsExactly("Code", "Name", "Location", "Head office", "Status", "Opened on");
    }

    @Test
    void aLenderGetsMembersLoansRepaymentsAndArrearsAndNoShopSheets() throws Exception {
        Book book = goldenBook();
        TestDatabase.owner().sql("""
                        UPDATE lending_loans SET days_past_due = 30, arrears_minor = 1050000
                         WHERE tenant_id = ? AND loan_no = 'LN0003'
                        """).param(book.t().tenantId()).update();
        UUID user = UUID.randomUUID();
        XlsxReader x = workbook(book.t(), user, export(book.t(), user));

        assertThat(names(x))
                .containsSubsequence("Parameters", "Branches", "Members", "Loans", "Repayments", "Arrears")
                .doesNotContain("Products", "Sales", "Stock balances", "Cash book");

        Sheet members = x.sheet("Members");
        assertThat(header(members)).startsWith("Member no", "Name", "Phone");
        assertThat(members.rows()).hasSize(1 + 7);

        Sheet loans = x.sheet("Loans");
        assertThat(header(loans))
                .startsWith("Loan no", "Member no", "Member", "Branch", "Product", "Officer")
                .contains("Principal disbursed (UGX)", "Principal outstanding (UGX)", "Days past due");
        assertThat(loans.rows()).hasSize(1 + 7);

        assertThat(header(x.sheet("Repayments")))
                .contains("Date", "Receipt no", "Loan no", "Amount (UGX)", "Method", "Recorded by");

        Sheet arrears = x.sheet("Arrears");
        assertThat(header(arrears))
                .containsExactly(
                        "Loan no",
                        "Member no",
                        "Member",
                        "Phone",
                        "Branch",
                        "Officer",
                        "Days past due",
                        "Arrears (UGX)",
                        "Principal outstanding (UGX)",
                        "Next due date",
                        "Last repayment");
        assertThat(arrears.rows()).hasSize(2);
        assertThat(arrears.text("A2")).isEqualTo("LN0003");
        assertThat(arrears.cell("G2").value()).isEqualTo("30");
        assertThat(arrears.cell("H2").value()).isEqualTo("1050000");
        assertThat(arrears.cell("I2").value()).isEqualTo("1000000");
    }

    // ---- who may have it ---------------------------------------------------------------------------

    @Test
    void oneBusinessNeverSeesAnothersRowsOrFileAndOnlyTheAdministratorsAsk() throws Exception {
        Shop a = shop("DXA");
        Shop b = shop("DXB");
        sale(
                a.t(),
                a.product(),
                a.api().userId(),
                "A-0001",
                "Test Buyer A",
                LocalDate.of(2026, 10, 8),
                Instant.parse("2026-10-08T08:00:00Z"));
        sale(
                b.t(),
                b.product(),
                b.api().userId(),
                "B-0001",
                "Test Buyer B",
                LocalDate.of(2026, 10, 8),
                Instant.parse("2026-10-08T08:00:00Z"));
        UUID user = a.api().userId();
        JsonNode run = export(a.t(), user);
        XlsxReader x = workbook(a.t(), user, run);
        String all = x.parts.values().stream().map(String::new).reduce("", String::concat);
        assertThat(all).contains("A-0001", "Test Buyer A", "DXA").doesNotContain("B-0001", "Test Buyer B", "DXB");

        // The same run id and document id under the other business: not found, never the file.
        UUID other = b.api().userId();
        ResponseEntity<JsonNode> url = http.exchange(
                "/api/v1/documents/" + run.get("document_id").asString() + "/download-url",
                HttpMethod.POST,
                new HttpEntity<>(headers(b.t(), other, PERM)),
                JsonNode.class);
        assertThat(url.getStatusCode().value()).isEqualTo(404);
        JsonNode otherCard = card(b.t(), other, PERM).getBody();
        assertThat(otherCard.get("latest").isNull()).isTrue();
        assertThat(otherCard.get("last_export_at").isNull()).isTrue();

        // Without the permission (a cashier, a branch manager): refused.
        String retailOnly = RetailTestSupport.ADMIN;
        assertThat(card(a.t(), user, retailOnly).getStatusCode().value()).isEqualTo(403);
        assertThat(ask(a.t(), user, retailOnly).getStatusCode().value()).isEqualTo(403);
        // Another administrator of the same business sees when it was last made, but not the first one's file.
        UUID admin = UUID.randomUUID();
        JsonNode adminCard = card(a.t(), admin, PERM).getBody();
        assertThat(adminCard.get("last_export_at").isNull()).isFalse();
        assertThat(adminCard.get("latest").isNull()).isTrue();
    }

    @Test
    void aBusinessWhoseSubscriptionEndedStillDownloadsItsDataButChangesNothing() throws Exception {
        Shop s = shop("DXS");
        sale(
                s.t(),
                s.product(),
                s.api().userId(),
                "S-0001",
                "Test Buyer S",
                LocalDate.of(2026, 10, 8),
                Instant.parse("2026-10-08T08:00:00Z"));
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(s.t().tenantId())
                .update();
        UUID user = s.api().userId();
        XlsxReader x = workbook(s.t(), user, export(s.t(), user));
        assertThat(x.sheet("Sales").text("C2")).isEqualTo("S-0001");
        // Every other write stays refused while the business is read only, the weekly switch among them.
        ResponseEntity<JsonNode> settings = weeklyEmail(s.t(), user, false);
        assertThat(settings.getStatusCode().value()).isEqualTo(423);
        assertThat(s.api()
                        .sell(s.t().headOffice(), "cash", s.product(), "1")
                        .getStatusCode()
                        .value())
                .isEqualTo(423);
    }

    @Test
    void anEndedBusinessesFileIsStillDeletedWhenItsThirtyDaysArePast() throws Exception {
        Shop s = shop("DXE");
        UUID user = s.api().userId();
        JsonNode run = export(s.t(), user);
        assertReady(run, "the data export");
        // The subscription ends: the business is read only (FR-TEN-06) and drops out of the active tenants.
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(s.t().tenantId())
                .update();
        String documentId = run.get("document_id").asString();

        clock.set(TestReports.START.plus(java.time.Duration.ofDays(29)));
        expiry.expireEveryTenant();
        assertThat(count("SELECT count(*) FROM report_runs WHERE id = ? AND files_deleted_at IS NULL", uuid(run)))
                .as("day 29: kept")
                .isEqualTo(1);

        clock.set(TestReports.START.plus(java.time.Duration.ofDays(31)));
        expiry.expireEveryTenant();
        assertThat(count("SELECT count(*) FROM report_runs WHERE id = ? AND files_deleted_at IS NOT NULL", uuid(run)))
                .as("day 31: the ended business's file is deleted like any other")
                .isEqualTo(1);
        ResponseEntity<JsonNode> gone =
                call(HttpMethod.POST, "/api/v1/documents/" + documentId + "/download-url", s.t(), user, PERM);
        assertThat(gone.getStatusCode().value()).isEqualTo(410);
        assertThat(gone.getBody().get("code").asString()).isEqualTo("report_expired");
    }

    static UUID uuid(JsonNode run) {
        return UUID.fromString(run.get("id").asString());
    }

    // ---- the weekly email --------------------------------------------------------------------------

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    static String ownerEmail(TestDatabase.Fixture t) {
        return "owner-" + t.slug() + "@example.test";
    }

    /** An active Owner with an email address, an administrator first (V79); returns their id. */
    static UUID owner(TestDatabase.Fixture t) {
        UUID tenantId = t.tenantId();
        UUID owner = UUID.randomUUID();
        TestDatabase.owner().sql("""
                        INSERT INTO users (id, tenant_id, kind, full_name, email, status)
                        VALUES (?, ?, 'staff', 'Test Owner', ?, 'active')
                        """).params(owner, tenantId, ownerEmail(t)).update();
        TestDatabase.owner()
                .sql("INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, granted_by)"
                        + " VALUES (?, ?, ?, 'tenant_admin', ?)")
                .params(UUID.randomUUID(), tenantId, owner, owner)
                .update();
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE tenant_id = ? AND id = ?")
                .params(tenantId, owner)
                .update();
        return owner;
    }

    /** The document of the week's run of the business's weekly copy. */
    static String weeklyDocument(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT r.document_id::text FROM data_export_weekly w JOIN report_runs r ON r.id = w.run_id"
                        + " WHERE w.tenant_id = ? ORDER BY w.week_of DESC LIMIT 1")
                .param(t.tenantId())
                .query(String.class)
                .single();
    }

    @Test
    void theEmailedWeeklyCopyLeavesOutPersonalDataWhichStaysInTheCopyDownloadedAfterSigningIn() throws Exception {
        Book book = goldenBook();
        TestDatabase.owner().sql("""
                        UPDATE lending_loans SET days_past_due = 30, arrears_minor = 1050000
                         WHERE tenant_id = ? AND loan_no = 'LN0003'
                        """).param(book.t().tenantId()).update();
        UUID owner = owner(book.t());
        weekly.tick();
        drain();
        weekly.tick();
        assertThat(TestDatabase.owner()
                        .sql("SELECT template_key FROM notification_outbox WHERE recipient = ?")
                        .param(ownerEmail(book.t()))
                        .query(String.class)
                        .single())
                .isEqualTo(DataExportWeekly.ATTACHED);

        XlsxReader emailed = XlsxReader.read(downloadBytes(book.t(), owner, PERM, weeklyDocument(book.t())));
        assertThat(header(emailed.sheet("Members")))
                .contains("Member no", "Name", "Gender", "District", "Occupation", "Branch")
                .doesNotContain("Phone", "Other phone", "ID number", "Date of birth", "Village");
        assertThat(header(emailed.sheet("Arrears")))
                .startsWith("Loan no", "Member no", "Member", "Branch")
                .doesNotContain("Phone");
        assertThat(emailed.sheet("Arrears").text("A2")).isEqualTo("LN0003");
        String all = emailed.parts.values().stream().map(String::new).reduce("", String::concat);
        assertThat(all).contains("contact details and ID numbers are only in the copy you download after signing in");

        // The copy the Owner makes after signing in is the full one, and it is the one the card shows.
        XlsxReader full = workbook(book.t(), owner, export(book.t(), owner));
        assertThat(header(full.sheet("Members")))
                .contains("Phone", "Other phone", "ID number", "Date of birth", "Village");
        assertThat(header(full.sheet("Arrears"))).contains("Phone");
        String phone = full.sheet("Members").text("C2");
        assertThat(phone).isNotBlank();
        assertThat(all).doesNotContain(phone);
    }

    @Test
    void theWeeklyJobQueuesOneWorkbookAndSendsOneEmailHoweverOftenItRunsAndStopsWhenSwitchedOff() throws Exception {
        Shop s = shop("DXW");
        UUID tenantId = s.t().tenantId();
        UUID owner = owner(s.t());
        String email = ownerEmail(s.t());
        weekly.tick();
        weekly.tick();
        assertThat(count("SELECT count(*) FROM data_export_weekly WHERE tenant_id = ?", tenantId))
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT week_of FROM data_export_weekly WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(LocalDate.class)
                        .single())
                .as("the Monday that starts the week of Thursday 8 October")
                .isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(count(
                        "SELECT count(*) FROM report_runs WHERE tenant_id = ? AND report_key = 'core.data_export' AND requested_by = ?",
                        tenantId,
                        owner))
                .isEqualTo(1);

        drain();
        weekly.tick();
        weekly.tick();
        assertThat(count("SELECT count(*) FROM notification_outbox WHERE recipient = ?", email))
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT template_key FROM notification_outbox WHERE recipient = ?")
                        .param(email)
                        .query(String.class)
                        .single())
                .as("a small workbook is attached")
                .isEqualTo(DataExportWeekly.ATTACHED);
        assertThat(TestDatabase.owner()
                        .sql("SELECT status || ' ' || attached FROM data_export_weekly WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .single())
                .as("written to the outbox, not yet sent")
                .isEqualTo("queued true");
        assertThat(card(s.t(), owner, PERM).getBody().get("last_emailed_at").isNull())
                .as("the card says emailed only once the email went")
                .isTrue();
        // The outbox sends it; the next tick reads that outcome.
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'sent', sent_at = now() WHERE recipient = ?")
                .param(email)
                .update();
        weekly.tick();
        assertThat(TestDatabase.owner()
                        .sql("SELECT status || ' ' || attached FROM data_export_weekly WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .single())
                .isEqualTo("emailed true");
        JsonNode card = card(s.t(), owner, PERM).getBody();
        assertThat(card.get("weekly_email").asBoolean()).isTrue();
        assertThat(card.get("last_emailed_at").isNull()).isFalse();
        assertThat(card.get("last_export_at").isNull()).isFalse();

        // Switched off (audited): the next week gets nothing. Before 02:00 on Monday nothing is queued either.
        ResponseEntity<JsonNode> off = weeklyEmail(s.t(), owner, false);
        assertThat(off.getStatusCode().value()).as("%s", off.getBody()).isEqualTo(200);
        assertThat(off.getBody().get("weekly_email").asBoolean()).isFalse();
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
                        tenantId,
                        DataExportService.AUDIT_SETTINGS))
                .isEqualTo(1);
        clock.set(Instant.parse("2026-10-12T03:00:00Z"));
        weekly.tick();
        assertThat(count("SELECT count(*) FROM data_export_weekly WHERE tenant_id = ?", tenantId))
                .isEqualTo(1);

        // On again mid-week: that week's copy is made.
        assertThat(weeklyEmail(s.t(), owner, true).getStatusCode().value()).isEqualTo(200);
        weekly.tick();
        assertThat(count(
                        "SELECT count(*) FROM data_export_weekly WHERE tenant_id = ? AND week_of = DATE '2026-10-12'",
                        tenantId))
                .isEqualTo(1);
    }

    @Test
    void aWeekWhoseEmailTheOutboxGaveUpEndsAsFailedAndIsNotShownAsEmailed() throws Exception {
        Shop s = shop("DXF");
        UUID owner = owner(s.t());
        weekly.tick();
        drain();
        weekly.tick();
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', failed_at = now() WHERE recipient = ?")
                .param(ownerEmail(s.t()))
                .update();
        weekly.tick();
        assertThat(TestDatabase.owner()
                        .sql("SELECT status || ' ' || reason FROM data_export_weekly WHERE tenant_id = ?")
                        .param(s.t().tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("failed email_failed");
        assertThat(card(s.t(), owner, PERM).getBody().get("last_emailed_at").isNull())
                .isTrue();
    }

    @Test
    void aWeeklyRunThatTimedOutEndsTheWeekAsFailedAndTellsTheOwnerOnce() throws Exception {
        // The whole business has longer than one report: 30 minutes against the runner's limit.
        assertThat(report.timeout()).isEqualTo(java.time.Duration.ofMinutes(30));
        Shop s = shop("DXT");
        owner(s.t());
        weekly.tick();
        TestDatabase.owner()
                .sql("UPDATE report_runs SET status = 'failed', error = 'timeout', finished_at = now()"
                        + " WHERE tenant_id = ? AND report_key = 'core.data_export'")
                .param(s.t().tenantId())
                .update();
        weekly.tick();
        weekly.tick();
        assertThat(TestDatabase.owner()
                        .sql("SELECT status || ' ' || reason FROM data_export_weekly WHERE tenant_id = ?")
                        .param(s.t().tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("failed timeout");
        assertThat(TestDatabase.owner()
                        .sql("SELECT template_key FROM notification_outbox WHERE recipient = ?")
                        .param(ownerEmail(s.t()))
                        .query(String.class)
                        .list())
                .containsExactly(DataExportWeekly.NOT_MADE);
    }

    /** The subscription ended {@code daysAgo} days before the test clock, as the operator's audit records it. */
    void ended(TestDatabase.Fixture t, int daysAgo) {
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO platform_audit_log (id, occurred_at, action, tenant_id, data)"
                        + " VALUES (?, ?, 'platform.tenant.subscription_changed', ?,"
                        + " '{\"before\": \"active\", \"after\": \"cancelled\"}'::jsonb)")
                .params(
                        UUID.randomUUID(),
                        Timestamp.from(TestReports.START.minus(java.time.Duration.ofDays(daysAgo))),
                        t.tenantId())
                .update();
    }

    @Test
    void anEndedBusinessGetsTheWeeklyEmailForNinetyDaysAfterTheEndThenItStops() throws Exception {
        Shop recent = shop("DXR");
        Shop old = shop("DXO");
        owner(recent.t());
        owner(old.t());
        ended(recent.t(), 10);
        ended(old.t(), 91);
        weekly.tick();
        drain();
        weekly.tick();
        assertThat(TestDatabase.owner()
                        .sql("SELECT template_key FROM notification_outbox WHERE recipient = ?")
                        .param(ownerEmail(recent.t()))
                        .query(String.class)
                        .list())
                .as("ended 10 days ago: the email as for any business")
                .containsExactly(DataExportWeekly.ATTACHED);
        assertThat(count(
                        "SELECT count(*) FROM data_export_weekly WHERE tenant_id = ?",
                        old.t().tenantId()))
                .as("ended 91 days ago: no more")
                .isZero();

        // The same business 81 days later is past its 90 days: nothing more is queued.
        clock.set(TestReports.START.plus(java.time.Duration.ofDays(81)));
        weekly.tick();
        assertThat(count(
                        "SELECT count(*) FROM data_export_weekly WHERE tenant_id = ?",
                        recent.t().tenantId()))
                .isEqualTo(1);
    }

    @Test
    void aWeekWithNoOwnerToEmailIsRecordedAsSkippedOnce() {
        Shop s = shop("DXN");
        weekly.tick();
        weekly.tick();
        assertThat(TestDatabase.owner()
                        .sql("SELECT status || ' ' || reason FROM data_export_weekly WHERE tenant_id = ?")
                        .param(s.t().tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("skipped no_owner_email");
    }
}
