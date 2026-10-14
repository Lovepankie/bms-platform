package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.insights.InsightsSnapshots;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The fabricated loan book the lending report tests share (extras slices D1 and D2): the golden dataset of chapter 14
 * (the worked example of PAR), one loan with one schedule item per row so every figure is a hand computed sum, and the
 * helpers that queue a report, read its file and read the insights page for the same scope. Every name is fabricated and
 * the business date is the movable clock's 2026-10-08, so nothing depends on the day the suite runs.
 */
abstract class LendingBookSupport extends ReportsItSupport {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    static final java.util.concurrent.atomic.AtomicInteger NIN = new java.util.concurrent.atomic.AtomicInteger();
    static final String PAR_PERMS = PORTFOLIO + ",lending.insights.read,lending.insights.all_officers";

    @Autowired
    ObjectMapper mapper;

    @Autowired
    InsightsSnapshots snapshots;

    @Autowired
    PlatformTransactionManager transactions;

    /** The tenant of the golden dataset and the ids the tests need. */
    record Book(TestDatabase.Fixture t, UUID officerA, UUID officerB, UUID product) {}

    // ---- the dataset -------------------------------------------------------------------------------

    /**
     * Head office, officer A: 4,000,000 current; 2,200,000 current; 1,000,000 + 50,000 interest, first unpaid item due
     * 30 days ago. Test Branch Two, officer B: 2,000,000 current; 500,000 + 10,000 fees, due 31 days ago; 300,000, due
     * 91 days ago. Not counted: a written-off loan (200,000 overdue).
     */
    Book goldenBook() {
        TestDatabase.Fixture t = tenant(true);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID product = product(t, a);
        loan(t, product, t.headOffice(), a, "LN0001", "active", 4_000_000, 0, 0, TODAY.plusDays(30));
        loan(t, product, t.headOffice(), a, "LN0002", "active", 2_200_000, 0, 0, TODAY.plusDays(30));
        loan(t, product, t.headOffice(), a, "LN0003", "active", 1_000_000, 50_000, 0, TODAY.minusDays(30));
        loan(t, product, t.secondBranch(), b, "LN0004", "active", 2_000_000, 0, 0, TODAY.plusDays(30));
        loan(t, product, t.secondBranch(), b, "LN0005", "active", 500_000, 0, 10_000, TODAY.minusDays(31));
        loan(t, product, t.secondBranch(), b, "LN0006", "active", 300_000, 0, 0, TODAY.minusDays(91));
        loan(t, product, t.headOffice(), a, "LN0007", "written_off", 200_000, 0, 0, TODAY.minusDays(200));
        // Written off on 1 September: not in the loan book on any date after it.
        TestDatabase.owner()
                .sql(
                        "UPDATE lending_loans SET written_off_on = DATE '2026-09-01' WHERE tenant_id = ? AND loan_no = 'LN0007'")
                .param(t.tenantId())
                .update();
        return new Book(t, a, b, product);
    }

    UUID product(TestDatabase.Fixture t, UUID user) {
        UUID product = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO lending_loan_products (id, tenant_id, code, name, status) VALUES (?, ?, 'PAR', 'Test Product', 'active')")
                .params(product, t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_loan_product_versions (id, tenant_id, product_id, version_no, currency,
                            interest_method, interest_rate_bp, rate_unit, term_unit, min_term_count, max_term_count,
                            default_term_count, repayment_pattern, min_principal_minor, max_principal_minor, created_by)
                        VALUES (?, ?, ?, 1, 'UGX', 'flat', 1000, 'per_term', 'month', 1, 1, 1, 'bullet', 1000, 100000000, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), product, user)
                .update();
        return product;
    }

    /** One loan with one schedule item due on {@code due} and nothing paid on it. */
    void loan(
            TestDatabase.Fixture t,
            UUID product,
            UUID branch,
            UUID officer,
            String no,
            String status,
            long principal,
            long interest,
            long fees,
            LocalDate due) {
        UUID member = UUID.randomUUID();
        // A national id is CM or CF and twelve letters or digits (chapter 6, lending_members).
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
                               'bullet', DATE '2026-01-05', ?, ?, DATE '2026-12-31'
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
                        principal,
                        principal,
                        product)
                .update();
        owner.sql("""
                        INSERT INTO lending_schedule_items (id, tenant_id, loan_id, item_no, due_date, principal_due_minor,
                                                            interest_due_minor, fees_due_minor, status)
                        VALUES (?, ?, ?, 1, ?, ?, ?, ?, 'due')
                        """)
                .params(UUID.randomUUID(), t.tenantId(), loan, Date.valueOf(due), principal, interest, fees)
                .update();
    }

    JsonNode par(Book book, UUID user, String permissions, Map<String, Object> params, String format) throws Exception {
        ResponseEntity<JsonNode> posted = post(book.t(), user, permissions, "lending.par", params, format);
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        return run(book.t(), user, permissions, posted.getBody().get("id").asString());
    }

    JsonNode file(Book book, UUID user, String permissions, JsonNode run) throws Exception {
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        return mapper.readTree(
                download(book.t(), user, permissions, run.get("document_id").asString()));
    }

    static JsonNode table(JsonNode file, String key) {
        for (JsonNode t : file.get("tables")) {
            if (t.get("key").asString().equals(key)) {
                return t;
            }
        }
        throw new AssertionError("no table " + key);
    }

    static List<String> rows(JsonNode table) {
        List<String> out = new ArrayList<>();
        table.get("rows").forEach(r -> out.add(r.toString()));
        return out;
    }

    // JUSTIFICATION-A3: shared by the portfolio and PAR report tests (issue #217); the PAR test had these inline.
    /** Queues any report, drains the queue and returns the finished run. */
    JsonNode runReport(Book book, UUID user, String permissions, String key, Map<String, Object> params, String format)
            throws Exception {
        ResponseEntity<JsonNode> posted = post(book.t(), user, permissions, key, params, format);
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        return run(book.t(), user, permissions, posted.getBody().get("id").asString());
    }

    /** One more active loan of an existing member (a member with loans in two branches), due in 30 days. */
    void loanOfMember(
            TestDatabase.Fixture t,
            UUID product,
            UUID branch,
            UUID officer,
            String no,
            String memberNo,
            long principal) {
        loanOfMember(t, product, branch, officer, no, memberNo, principal, LocalDate.of(2026, 1, 5));
    }

    /** As above, disbursed on {@code disbursedOn} (a loan of the day after the figures' date, for the midnight tests). */
    void loanOfMember(
            TestDatabase.Fixture t,
            UUID product,
            UUID branch,
            UUID officer,
            String no,
            String memberNo,
            long principal,
            LocalDate disbursedOn) {
        UUID loan = UUID.randomUUID();
        var owner = TestDatabase.owner();
        owner.sql("""
                        INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                            officer_user_id, status, channel, purpose_category, currency, requested_principal_minor,
                            requested_term_count, term_unit, interest_method, interest_rate_bp, rate_unit, repayment_pattern,
                            disbursed_on, principal_disbursed_minor, principal_outstanding_minor, maturity_date)
                        SELECT ?, ?, ?, ?, m.id, v.id, ?, 'active', 'staff', 'business', 'UGX', ?, 1, 'month', 'flat', 1000,
                               'per_term', 'bullet', ?, ?, ?, DATE '2026-12-31'
                          FROM lending_members m, lending_loan_product_versions v
                         WHERE m.tenant_id = ? AND m.member_no = ? AND v.product_id = ?
                        """)
                .params(
                        loan,
                        t.tenantId(),
                        branch,
                        no,
                        officer,
                        principal,
                        Date.valueOf(disbursedOn),
                        principal,
                        principal,
                        t.tenantId(),
                        memberNo,
                        product)
                .update();
        owner.sql("""
                        INSERT INTO lending_schedule_items (id, tenant_id, loan_id, item_no, due_date, principal_due_minor,
                                                            interest_due_minor, fees_due_minor, status)
                        VALUES (?, ?, ?, 1, ?, ?, 0, 0, 'due')
                        """)
                .params(UUID.randomUUID(), t.tenantId(), loan, Date.valueOf(TODAY.plusDays(30)), principal)
                .update();
    }

    /** A second loan product, for the grouping by product. */
    UUID productNamed(TestDatabase.Fixture t, UUID user, String code, String name) {
        UUID product = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO lending_loan_products (id, tenant_id, code, name, status) VALUES (?, ?, ?, ?, 'active')")
                .params(product, t.tenantId(), code, name)
                .update();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_loan_product_versions (id, tenant_id, product_id, version_no, currency,
                            interest_method, interest_rate_bp, rate_unit, term_unit, min_term_count, max_term_count,
                            default_term_count, repayment_pattern, min_principal_minor, max_principal_minor, created_by)
                        VALUES (?, ?, ?, 1, 'UGX', 'flat', 1000, 'per_term', 'month', 1, 1, 1, 'bullet', 1000, 100000000, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), product, user)
                .update();
        return product;
    }

    /** The insights page's own portfolio answer for the same date and filter, as the user would see it. */
    JsonNode insightsPortfolio(Book book, UUID user, String permissions, String branchHeader, String filter) {
        var h = headers(book.t(), user, permissions);
        if (branchHeader != null) {
            h.set("X-Dev-Branch-Ids", branchHeader);
        }
        return http.exchange(
                        "/api/v1/lending/insights/portfolio?from=" + TODAY + "&to=" + TODAY + filter,
                        HttpMethod.GET,
                        new HttpEntity<>(h),
                        JsonNode.class)
                .getBody();
    }

    static long metric(JsonNode body, String key) {
        for (JsonNode m : body.get("metrics")) {
            if (m.get("key").asString().equals(key)) {
                return m.get("value").asLong();
            }
        }
        throw new AssertionError("no metric " + key);
    }
}
