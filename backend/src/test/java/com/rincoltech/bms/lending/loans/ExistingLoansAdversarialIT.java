package com.rincoltech.bms.lending.loans;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.internal.ArrearsTestRun;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Adversarial checks of existing loans brought in from a paper register (issue #466, ADR-071), from the test review of
 * #475: a brought-in loan ends where the same loan serviced in the app with the same dates ends, nothing is charged or
 * texted for the time before the bring-in, a register never comes in twice, and the maker never checks their own
 * bring-in. All figures and people fabricated.
 */
class ExistingLoansAdversarialIT extends LoanFixtures {

    static final String EXISTING = "/api/v1/lending/existing-loans";

    @Autowired
    BusinessClock clock;

    @Autowired
    ApplicationContext context;

    final UUID admin = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    final UUID appraiser = UUID.randomUUID();
    final UUID cashier = UUID.randomUUID();
    String cashierPerms;
    LocalDate today;
    UUID owner;

    @BeforeEach
    void dates() {
        today = clock.today(BusinessClock.DEFAULT_ZONE);
        cashierPerms = permissionsOf("cashier");
    }

    // ---- Fixtures -------------------------------------------------------------------------

    String product(String code, Map<String, Object> overrides) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("interest_method", "flat");
        terms.put("interest_rate_bp", 1000);
        terms.put("rate_unit", "per_month");
        terms.put("term_unit", "month");
        terms.put("min_term_count", 1);
        terms.put("max_term_count", 6);
        terms.put("default_term_count", 3);
        terms.put("repayment_pattern", "instalments");
        terms.put("instalment_frequency", "monthly");
        terms.put("min_principal_minor", 100_000);
        terms.put("max_principal_minor", 5_000_000);
        terms.putAll(overrides);
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    String borrower(int n) {
        return member("Test Borrower %02d".formatted(n), "07000001%02d".formatted(n), t.headOffice(), true);
    }

    String memberNo(String memberId) {
        return TestDatabase.owner()
                .sql("SELECT member_no FROM lending_members WHERE id = ?::uuid")
                .param(memberId)
                .query(String.class)
                .single();
    }

    void setGoLive(LocalDate date) {
        if (owner == null) {
            owner = Api.staff(t, Api.email("exla-owner"), new Api.Role("tenant_admin", null));
            TestDatabase.owner()
                    .sql("UPDATE users SET is_owner = true WHERE id = ?")
                    .param(owner)
                    .update();
        }
        String version = send(HttpMethod.GET, "/api/v1/settings", as(owner, adminPerms, "*", null), null)
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> r = send(
                HttpMethod.PATCH,
                "/api/v1/settings",
                as(owner, adminPerms, "*", version),
                Map.of("go_live_date", date));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    static Map<String, Object> repayment(LocalDate date, long amount) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("value_date", date.toString());
        r.put("amount_minor", amount);
        r.put("payment_method_key", "cash");
        return r;
    }

    Map<String, Object> request(
            String member, String product, long principal, LocalDate disbursed, List<Map<String, Object>> repayments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member_id", member);
        body.put("product_id", product);
        body.put("principal_minor", principal);
        body.put("disbursement_date", disbursed.toString());
        body.put("repayments", repayments);
        return body;
    }

    ResponseEntity<JsonNode> record(UUID maker, String perms, Map<String, Object> body) {
        HttpHeaders h = as(maker, perms, "*", null);
        h.add("Idempotency-Key", "test-" + UUID.randomUUID());
        return send(HttpMethod.POST, EXISTING, h, body);
    }

    JsonNode recorded(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = record(admin, adminPerms, body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    ResponseEntity<JsonNode> decide(String approvalId, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approvalId + "/approve",
                as(user, perms, "*", null),
                Map.of("note", "Checked against the register"));
    }

    void approve(String approvalId) {
        ResponseEntity<JsonNode> d = decide(approvalId, manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> importCsv(String csv, boolean dryRun) {
        return send(
                HttpMethod.POST,
                EXISTING + "/import?dry_run=" + dryRun,
                as(admin, adminPerms, "*", null),
                Map.of("csv", csv));
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    /** A loan made in the app: applied, appraised and approved by three people, then disbursed on this date. */
    String liveLoan(String member, String product, long principal, int term, LocalDate disbursed) {
        ResponseEntity<JsonNode> created = apply(member, product, principal, term);
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String loan = created.getBody().get("id").asString();
        assertThat(asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/submit", "\"1\"", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(
                                HttpMethod.POST,
                                LOANS + "/" + loan + "/appraisals",
                                as(appraiser, officerPerms, "*", "\"2\""),
                                Map.of("declared_monthly_income_minor", 2_400_000))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> decided = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/decision",
                as(manager, managerPerms, "*", "\"3\""),
                Map.of("decision", "approve"));
        assertThat(decided.getBody().get("status").asString()).isEqualTo("approved");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disbursement_date", disbursed.toString());
        body.put("payment_method_key", "cash");
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "test-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, LOANS + "/" + loan + "/disbursements", h, body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        approve(r.getBody().get("approval_request_id").asString());
        return loan;
    }

    void liveRepayment(String loan, LocalDate date, long amount) {
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "test-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/repayments",
                h,
                Map.of("amount_minor", amount, "value_date", date.toString(), "payment_method_key", "cash"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    /** Each schedule item as due date and every due and paid component. */
    static List<String> items(String loan) {
        return TestDatabase.owner().sql("""
                        SELECT item_no || ' ' || due_date || ' due ' || principal_due_minor || '/' || interest_due_minor
                               || '/' || fees_due_minor || '/' || penalties_due_minor || ' paid ' || principal_paid_minor
                               || '/' || interest_paid_minor || '/' || fees_paid_minor || '/' || penalties_paid_minor
                               || ' waived ' || interest_waived_minor || '/' || fees_waived_minor || ' ' || status
                               || ' ' || coalesce(paid_on::text, '-')
                          FROM lending_schedule_items WHERE loan_id = ?::uuid ORDER BY item_no
                        """).param(loan).query(String.class).list();
    }

    /** Each repayment's allocation rows in R-ALLOC order: value date, item, component, amount. */
    static List<String> allocations(String loan) {
        return TestDatabase.owner().sql("""
                        SELECT t.value_date || ' ' || t.amount_minor || ' -> ' || s.item_no || ':' || a.component || ':'
                               || a.amount_minor
                          FROM lending_repayment_allocations a
                          JOIN lending_loan_transactions t ON t.id = a.transaction_id
                          JOIN lending_schedule_items s ON s.id = a.schedule_item_id
                         WHERE t.loan_id = ?::uuid AND t.txn_type = 'repayment'
                         ORDER BY t.value_date, s.item_no, a.component
                        """).param(loan).query(String.class).list();
    }

    /** The loan's balances as the API shows them, without the fields that name the loan itself. */
    Map<String, String> balances(String loan) {
        JsonNode b = send(HttpMethod.GET, LOANS + "/" + loan, as(admin, adminPerms, "*", null), null)
                .getBody()
                .get("balances");
        Map<String, String> out = new LinkedHashMap<>();
        b.properties().forEach(e -> out.put(e.getKey(), e.getValue().toString()));
        return out;
    }

    // ---- 1. A brought-in loan ends where the same loan serviced live ends ---------------------------

    /**
     * The same member, product, principal, term, disbursement date and four repayments on the same dates (one short, one
     * spanning two instalments, one paying ahead, one small), once brought in from the register and once serviced in the
     * app as it happened: the schedule, every item's paid components and status, every allocation row and every balance
     * (outstanding by component, arrears, days past due, next due date, total paid, last repayment) are the same. Run for
     * a flat product paying interest first and a declining one paying principal first.
     */
    @Test
    void aBroughtInLoanMatchesTheSameLoanServicedLiveWithTheSameDates() {
        setGoLive(today.minusDays(3));
        List<Map<String, Object>> cases = List.of(
                Map.of(
                        "interest_method",
                        "flat",
                        "allocation_order",
                        List.of("penalty", "fee", "interest", "principal")),
                Map.of(
                        "interest_method",
                        "declining",
                        "interest_rate_bp",
                        500,
                        "allocation_order",
                        List.of("principal", "interest", "fee", "penalty")));
        int n = 0;
        for (Map<String, Object> terms : cases) {
            n++;
            String product = product("EXLA" + n, terms);
            LocalDate disbursed = today.minusMonths(5).minusDays(12);
            List<Object[]> paid = List.of(
                    new Object[] {disbursed.plusMonths(1).plusDays(4), 200_000L},
                    new Object[] {disbursed.plusMonths(2).plusDays(1), 700_000L},
                    new Object[] {disbursed.plusMonths(2).plusDays(20), 450_000L},
                    new Object[] {disbursed.plusMonths(4).plusDays(2), 35_000L});

            String live = liveLoan(borrower(10 * n), product, 1_500_000, 4, disbursed);
            for (Object[] p : paid) {
                liveRepayment(live, (LocalDate) p[0], (Long) p[1]);
            }

            List<Map<String, Object>> lines = new ArrayList<>();
            // Entered in reverse order: the bring-in applies them in date order.
            for (int k = paid.size() - 1; k >= 0; k--) {
                lines.add(repayment((LocalDate) paid.get(k)[0], (Long) paid.get(k)[1]));
            }
            Map<String, Object> body = request(borrower(10 * n + 1), product, 1_500_000, disbursed, lines);
            body.put("term_count", 4);
            JsonNode in = recorded(body);
            String brought = in.get("loan_id").asString();
            approve(in.get("approval_request_id").asString());

            assertThat(items(live)).hasSize(4);
            assertThat(allocations(live)).hasSizeGreaterThanOrEqualTo(paid.size());
            assertThat(items(brought)).as("case %d: the schedule items", n).isEqualTo(items(live));
            assertThat(allocations(brought))
                    .as("case %d: the allocation rows", n)
                    .isEqualTo(allocations(live));
            Map<String, String> expected = balances(live);
            Map<String, String> actual = balances(brought);
            assertThat(actual.keySet()).contains("principal_outstanding_minor", "days_past_due", "next_due_date");
            assertThat(actual.keySet()).isEqualTo(expected.keySet());
            List<String> differences = new ArrayList<>();
            expected.forEach((k, v) -> {
                if (!v.equals(actual.get(k))) {
                    differences.add(k + ": live " + v + ", brought in " + actual.get(k));
                }
            });
            assertThat(differences).as("case %d: the balances", n).isEmpty();
            // The receivable each loan carries in the books is its principal outstanding.
            assertThat(count("""
                            SELECT coalesce(sum(j.debit - j.credit), 0) FROM journal_lines j
                              JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'loans_receivable'
                             WHERE j.subledger_type = 'lending.loan' AND j.subledger_id = ?::uuid
                            """, brought)).isEqualTo(Long.parseLong(actual.get("principal_outstanding_minor")));
        }
    }

    // ---- 2. Nothing charged or texted for the time before the bring-in ------------------------------

    /**
     * A loan overdue for five months on a product charging 5,000 a week after the due date, brought in with SMS on and
     * an arrears run history going back years. On the bring-in day: no penalty, no arrears SMS, and no text at all for
     * the old disbursement and repayments. Then the bring-in date is moved twenty days back (the loan has been in the app
     * twenty days) and the run catches up: every penalty period charged starts on or after the bring-in date, none that
     * started before it, each overdue item is charged, and the arrears notices that go are all dated after the hold.
     */
    @Test
    void noPenaltyPeriodOrArrearsSmsBeforeTheBringInDateEvenWhenTheRunCatchesUp() {
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        Map<String, Object> penalty = new LinkedHashMap<>();
        penalty.put("penalty_method", "flat_per_period");
        penalty.put("penalty_period_unit", "week");
        penalty.put("penalty_grace_days", 0);
        penalty.put("penalty_flat_minor", 5_000);
        String product = product("EXLA3", penalty);
        setGoLive(today.minusDays(40));
        LocalDate disbursed = today.minusMonths(5);
        String member = borrower(31);
        JsonNode in = recorded(request(
                member,
                product,
                1_200_000,
                disbursed,
                List.of(repayment(disbursed.plusMonths(1).plusDays(3), 100_000))));
        String id = in.get("loan_id").asString();
        approve(in.get("approval_request_id").asString());
        TestDatabase.owner().sql("""
                        INSERT INTO lending_arrears_runs (tenant_id, business_date, started_at, lease_until, finished_at)
                        VALUES (?, DATE '2000-01-01', now(), now(), now()) ON CONFLICT DO NOTHING
                        """).param(t.tenantId()).update();
        assertThat(count(
                        "SELECT count(*) FROM notification_outbox WHERE channel = 'sms' AND tenant_id = ?",
                        t.tenantId()))
                .as("no text for the register's old disbursement or repayment")
                .isZero();

        ArrearsTestRun.run(context, t.tenantId());
        assertThat(count("SELECT count(*) FROM lending_loan_charges WHERE loan_id = ?::uuid", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM lending_loan_notices WHERE loan_id = ?::uuid", id))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM notification_outbox WHERE channel = 'sms' AND tenant_id = ?",
                        t.tenantId()))
                .isZero();

        LocalDate broughtIn = today.minusDays(20);
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET brought_in_on = ?, created_at = created_at - interval '20 days'"
                        + " WHERE id = ?::uuid")
                .params(broughtIn, UUID.fromString(id))
                .update();
        TestDatabase.owner()
                .sql("DELETE FROM lending_arrears_runs WHERE tenant_id = ? AND business_date > DATE '2000-01-01'")
                .param(t.tenantId())
                .update();
        ArrearsTestRun.run(context, t.tenantId());

        List<LocalDate> periodStarts = TestDatabase.owner()
                .sql("SELECT business_date - 7 FROM lending_loan_charges WHERE loan_id = ?::uuid AND charge_type ="
                        + " 'penalty' ORDER BY 1")
                .param(id)
                .query(LocalDate.class)
                .list();
        assertThat(periodStarts).as("weekly periods ending in the twenty days").isNotEmpty();
        assertThat(periodStarts)
                .as("no period that started before the bring-in date")
                .allSatisfy(d -> assertThat(d).isAfterOrEqualTo(broughtIn));
        // Every item a full week overdue by today has a whole weekly period inside the twenty days.
        long overdueItems = count(
                "SELECT count(*) FROM lending_schedule_items WHERE loan_id = ?::uuid AND due_date <= ?",
                id,
                today.minusDays(7));
        assertThat(count(
                        "SELECT count(DISTINCT schedule_item_id) FROM lending_loan_charges WHERE loan_id = ?::uuid",
                        id))
                .as("each overdue item is charged for the weeks after the bring-in")
                .isEqualTo(overdueItems);
        assertThat(count(
                        "SELECT count(*) FROM lending_loan_charges WHERE loan_id = ?::uuid AND amount_minor <> 5000",
                        id))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM lending_loan_notices WHERE loan_id = ?::uuid AND business_date < ?",
                        id,
                        broughtIn.plusDays(7)))
                .as("no arrears notice dated in the hold after the bring-in")
                .isZero();
    }

    // ---- 3. A register never comes in twice ----------------------------------------------------------

    /**
     * A register of two loans is imported and both are approved (one closed, one active). The same file again, the same
     * file with its numbers in another case and padded, and a file holding a loan the form recorded with its register
     * number are all skipped; two commits of a fresh file at the same time record it once. The ledger holds one opening
     * entry per loan and the receivable is the principal outstanding of the loans, not twice it.
     */
    @Test
    void theSameRegisterImportedTwiceOrAlreadyEnteredByTheFormIsNeverDoubleCounted() throws Exception {
        product("EXLA4", Map.of());
        String one = memberNo(borrower(41));
        String two = memberNo(borrower(42));
        String formMember = borrower(43);
        setGoLive(today.minusDays(10));
        LocalDate d1 = today.minusMonths(2);
        LocalDate d2 = today.minusMonths(4);
        String header = "kind,ref,member_no,product_code,principal,disbursement_date,term,agreed_total,purpose,"
                + "repayment_date,amount,method,note\n";
        String register = header
                + "loan,REG-A1," + one + ",EXLA4,1200000," + d1 + ",3,,business,,,,\n"
                + "repayment,REG-A1,,,,,,,," + d1.plusMonths(1) + ",520000,cash,Test receipt 1\n"
                + "loan,REG-A2," + two + ",EXLA4,500000," + d2 + ",3,,,,,,\n"
                + "repayment,REG-A2,,,,,,,," + d2.plusMonths(1) + ",650000,cash,Test receipt 2\n";
        JsonNode first = importCsv(register, false).getBody();
        assertThat(first.get("added").asInt()).as("%s", first).isEqualTo(2);
        TestDatabase.owner()
                .sql("SELECT a.id::text FROM approval_requests a WHERE a.tenant_id = ? AND a.action_type ="
                        + " 'loan_bring_in' AND a.status = 'pending'")
                .param(t.tenantId())
                .query(String.class)
                .list()
                .forEach(this::approve);
        assertThat(count(
                        "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND status IN ('active', 'closed')",
                        t.tenantId()))
                .isEqualTo(2);

        JsonNode again = importCsv(register, false).getBody();
        assertThat(again.get("added").asInt()).as("%s", again).isZero();
        assertThat(again.get("skipped").asInt()).isEqualTo(2);
        String recased = register.replace("REG-A1", " reg-a1 ").replace("REG-A2", "Reg-A2");
        JsonNode cased = importCsv(recased, false).getBody();
        assertThat(cased.get("added").asInt()).as("%s", cased).isZero();
        assertThat(cased.get("skipped").asInt()).isEqualTo(2);

        Map<String, Object> form = request(formMember, productId("EXLA4"), 900_000, d1, List.of());
        form.put("register_ref", "REG-F1");
        JsonNode byForm = recorded(form);
        approve(byForm.get("approval_request_id").asString());
        String withForm = header + "loan,reg-f1," + memberNo(formMember) + ",EXLA4,900000," + d1 + ",,,,,,,\n";
        JsonNode formAgain = importCsv(withForm, false).getBody();
        assertThat(formAgain.get("added").asInt()).as("%s", formAgain).isZero();
        assertThat(formAgain.get("skipped").asInt()).isEqualTo(1);
        Map<String, Object> formTwice = request(formMember, productId("EXLA4"), 900_000, d1, List.of());
        formTwice.put("register_ref", "Reg-F1 ");
        ResponseEntity<JsonNode> refused = record(admin, adminPerms, formTwice);
        assertThat(refused.getStatusCode().is2xxSuccessful())
                .as("%s", refused.getBody())
                .isFalse();

        // Two commits of a fresh file at once: the register number lets one loan in.
        String fresh = header + "loan,REG-A3," + one + ",EXLA4,400000," + d1 + ",,,,,,,\n";
        Thread a = Thread.ofVirtual().start(() -> importCsv(fresh, false));
        Thread b = Thread.ofVirtual().start(() -> importCsv(fresh, false));
        a.join();
        b.join();
        assertThat(count(
                        "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND lower(register_ref) = 'reg-a3'",
                        t.tenantId()))
                .isEqualTo(1);

        assertThat(count(
                        "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND status NOT IN ('cancelled',"
                                + " 'rejected')",
                        t.tenantId()))
                .isEqualTo(4);
        assertThat(count(
                        "SELECT count(*) FROM lending_loan_transactions WHERE tenant_id = ? AND txn_type ="
                                + " 'disbursement'",
                        t.tenantId()))
                .as("one historic disbursement per approved loan")
                .isEqualTo(3);
        assertThat(count(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_module = 'lending'",
                        t.tenantId()))
                .as("an opening entry for each of the two loans still owing, none for the closed one")
                .isEqualTo(2);
        assertThat(count("""
                        SELECT coalesce(sum(j.debit - j.credit), 0) FROM journal_lines j
                          JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'loans_receivable'
                         WHERE j.tenant_id = ?
                        """, t.tenantId()))
                .isEqualTo(count(
                        "SELECT coalesce(sum(principal_outstanding_minor), 0) FROM lending_loans WHERE tenant_id = ?",
                        t.tenantId()))
                .isEqualTo((1_200_000 - 400_000) + 900_000);
    }

    String productId(String code) {
        return TestDatabase.owner()
                .sql("SELECT id::text FROM lending_loan_products WHERE tenant_id = ? AND code = ?")
                .params(t.tenantId(), code)
                .query(String.class)
                .single();
    }

    // ---- 4. The maker never checks their own bring-in -------------------------------------------------

    /**
     * A branch manager (who holds both the permission to record and the checker's permission) records a loan, and an
     * administrator imports one: neither approves their own, by the approvals route, after asking again, or for the
     * reversal they asked for; the database refuses a loan approved by its maker too. The other person approves.
     */
    @Test
    void theMakerNeverApprovesTheirOwnBringInWhicheverWayItCameIn() {
        String product = product("EXLA5", Map.of());
        setGoLive(today.minusDays(5));
        LocalDate disbursed = today.minusMonths(2);

        ResponseEntity<JsonNode> byManager =
                record(manager, managerPerms, request(borrower(51), product, 800_000, disbursed, List.of()));
        assertThat(byManager.getStatusCode()).as("%s", byManager.getBody()).isEqualTo(HttpStatus.CREATED);
        String managerLoan = byManager.getBody().get("loan_id").asString();
        String managerRequest = byManager.getBody().get("approval_request_id").asString();
        ResponseEntity<JsonNode> self = decide(managerRequest, manager, managerPerms);
        assertThat(self.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(code(self)).isEqualTo("self_approval_forbidden");

        // Rejected, asked again by the maker: still the maker's request.
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/approvals/" + managerRequest + "/reject",
                                as(admin, adminPerms, "*", null),
                                Map.of("note", "Check the date"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> again = send(
                HttpMethod.POST,
                EXISTING + "/" + managerLoan + "/approval",
                as(manager, managerPerms, "*", null),
                null);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String second = again.getBody().get("approval_request_id").asString();
        assertThat(code(decide(second, manager, managerPerms))).isEqualTo("self_approval_forbidden");
        assertThat(count("SELECT count(*) FROM lending_loan_transactions WHERE loan_id = ?::uuid", managerLoan))
                .isZero();

        // The import's maker is the person who committed it.
        String header = "kind,ref,member_no,product_code,principal,disbursement_date,term,agreed_total,purpose,"
                + "repayment_date,amount,method,note\n";
        JsonNode imported = importCsv(
                        header + "loan,REG-M1," + memberNo(borrower(52)) + ",EXLA5,600000," + disbursed + ",,,,,,,\n",
                        false)
                .getBody();
        String importedLoan = imported.get("rows").get(0).get("loan_id").asString();
        String importRequest = TestDatabase.owner()
                .sql("SELECT id::text FROM approval_requests WHERE subject_id = ?::uuid AND status = 'pending'")
                .param(importedLoan)
                .query(String.class)
                .single();
        assertThat(code(decide(importRequest, admin, adminPerms))).isEqualTo("self_approval_forbidden");
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM lending_loans WHERE id = ?::uuid")
                        .param(importedLoan)
                        .query(String.class)
                        .single())
                .isEqualTo("brought_in");

        // The checkers approve; the database itself refuses the maker as the approver.
        approve(importRequest);
        ResponseEntity<JsonNode> other = decide(second, admin, adminPerms);
        assertThat(other.getStatusCode()).as("%s", other.getBody()).isEqualTo(HttpStatus.OK);
        int changed;
        try {
            changed = TestDatabase.owner()
                    .sql("UPDATE lending_loans SET approved_by = submitted_by WHERE id = ?::uuid")
                    .param(importedLoan)
                    .update();
        } catch (RuntimeException e) {
            changed = -1;
        }
        assertThat(changed)
                .as("the V8 check keeps the maker from being the approver")
                .isEqualTo(-1);

        // The reversal's maker does not approve it either.
        ResponseEntity<JsonNode> asked = send(
                HttpMethod.POST,
                EXISTING + "/" + importedLoan + "/reversal",
                as(admin, adminPerms, "*", null),
                Map.of("reason", "Entered from the wrong register"));
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String reversal = asked.getBody().get("approval_request_id").asString();
        assertThat(code(decide(reversal, admin, adminPerms))).isEqualTo("self_approval_forbidden");
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM lending_loans WHERE id = ?::uuid")
                        .param(importedLoan)
                        .query(String.class)
                        .single())
                .isEqualTo("active");
    }
}
