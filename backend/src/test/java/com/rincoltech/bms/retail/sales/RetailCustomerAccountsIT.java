package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Customer accounts (ADR-033, issue #196; docs/specs/trade-capabilities.md sections 5 and 13): the credit limit at its
 * boundary and under concurrency, terms and the default due date, who may set a limit, the typed-name credit sale
 * refused, payment on account (oldest first, manual, skipping imported and out-of-scope sales, posting in the receiving
 * branch, idempotent, reversible), the statement against the ledger and the branch-scoped balance, the cash book, and
 * "capability off equals today". Every name and amount is fabricated. The clock is pinned at 22:30 UTC, which is already
 * 01:30 the next day in Africa/Kampala, so every test runs at the edge where the UTC date and the business date differ
 * and none depends on the day it runs.
 */
@Import(RetailCustomerAccountsIT.FixedClock.class)
class RetailCustomerAccountsIT extends IntegrationTest {

    /** 2026-10-08 01:30 in Kampala, 2026-10-07 22:30 in UTC. */
    static final Instant NOW = Instant.parse("2026-10-07T22:30:00Z");

    static final String TODAY = "2026-10-08";
    static final String UTC_DAY = "2026-10-07";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static final String SETTINGS = "core.settings.manage";
    /** The administrator column after migration V44: the credit permissions are the administrator's only. */
    static final String MANAGER = ADMIN + ",retail.credit.manage,retail.credit.override";
    /** An administrator who may set limits but not sell past one. */
    static final String NO_OVERRIDE = ADMIN + ",retail.credit.manage";
    /** The sales role: it edits a buyer's name and contact (retail.customer.manage) and holds no credit permission. */
    static final String CLERK = RetailTestSupport.SALES;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    /** Cost 30, sell 100 per piece: a quantity of 10 is a sale of 1,000. */
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-acct", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("CA-1", 30, 100);
        api.stockUp(hq, product, "100000");
        api.stockUp(br2, product, "100000");
        capability("customer_accounts", true);
    }

    // ---- helpers ---------------------------------------------------------------------------

    void capability(String key, boolean on) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, on),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    /** Each field problem's message by its field, so no assertion leans on the order of the problems. */
    static Map<String, String> messages(ResponseEntity<JsonNode> r) {
        Map<String, String> byField = new LinkedHashMap<>();
        r.getBody()
                .get("errors")
                .forEach(e ->
                        byField.put(e.get("field").asString(), e.get("message").asString()));
        return byField;
    }

    UUID customer(String name) {
        return RetailTestSupport.id(api.post("/customers", Map.of("name", name), CLERK));
    }

    int version(UUID customer) {
        return api.get("/customers/" + customer + "/account", ADMIN)
                .getBody()
                .get("version")
                .asInt();
    }

    ResponseEntity<JsonNode> setAccount(UUID customer, Long limit, Integer terms, String perms) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Each field is a value or an explicit clear: a body that leaves one out is refused.
        if (limit != null) {
            body.put("credit_limit_minor", limit);
        } else {
            body.put("clear_credit_limit", true);
        }
        if (terms != null) {
            body.put("terms_days", terms);
        } else {
            body.put("clear_terms_days", true);
        }
        return api.call(
                HttpMethod.PUT,
                "/customers/" + customer + "/account",
                body,
                perms,
                "*",
                Map.of("If-Match", String.valueOf(version(customer))));
    }

    UUID buyer(String name, Long limit, Integer terms) {
        UUID id = customer(name);
        if (limit != null || terms != null) {
            assertThat(setAccount(id, limit, terms, MANAGER).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        return id;
    }

    ResponseEntity<JsonNode> creditSale(
            UUID branch, UUID customer, String qty, String saleDate, String dueDate, String reason, String perms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", "credit");
        if (customer != null) {
            body.put("customer_id", customer);
        } else {
            body.put("buyer_name", "Typed Buyer 01");
        }
        if (saleDate != null) {
            body.put("sale_date", saleDate);
        }
        if (dueDate != null) {
            body.put("due_date", dueDate);
        }
        if (reason != null) {
            body.put("credit_override_reason", reason);
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", qty)));
        return api.postKeyed("/sales", body, perms, "*", UUID.randomUUID().toString());
    }

    /** A credit sale of qty x 100 at a branch, as an administrator; must succeed. */
    UUID sold(UUID branch, UUID customer, String qty, String saleDate) {
        ResponseEntity<JsonNode> r = creditSale(branch, customer, qty, saleDate, null, null, MANAGER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> pay(
            UUID customer,
            UUID branch,
            long amount,
            String method,
            List<Map<String, Object>> allocations,
            String paidOn,
            String perms,
            String branchScope,
            String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (branch != null) {
            body.put("branch_id", branch);
        }
        body.put("amount_minor", amount);
        body.put("method", method);
        if (paidOn != null) {
            body.put("paid_on", paidOn);
        }
        if (allocations != null) {
            body.put("allocations", allocations);
        }
        return api.postKeyed("/customers/" + customer + "/payments", body, perms, branchScope, key);
    }

    ResponseEntity<JsonNode> pay(UUID customer, UUID branch, long amount) {
        return pay(
                customer,
                branch,
                amount,
                "cash",
                null,
                null,
                MANAGER,
                "*",
                UUID.randomUUID().toString());
    }

    static Map<String, Object> part(UUID sale, long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sale_id", sale);
        m.put("amount_minor", amount);
        return m;
    }

    ResponseEntity<JsonNode> reverse(UUID customer, UUID receipt, String reason, String perms) {
        return api.postKeyed(
                "/customers/" + customer + "/payments/" + receipt + "/reverse",
                Map.of("reason", reason),
                perms,
                "*",
                UUID.randomUUID().toString());
    }

    JsonNode sale(UUID id) {
        return api.get("/sales/" + id, ADMIN).getBody();
    }

    JsonNode statement(UUID customer, String from, String to, String perms, String branchScope) {
        String q = (from == null ? "" : "from=" + from + "&") + (to == null ? "" : "to=" + to);
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET, "/customers/" + customer + "/statement?" + q, null, perms, branchScope, Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode dayRow(JsonNode report, String day) {
        for (JsonNode r : report.get("items")) {
            if (r.get("business_date").asString().equals(day)) {
                return r;
            }
        }
        throw new AssertionError("no row for " + day + " in " + report);
    }

    JsonNode balance(UUID customer, String perms, String branchScope) {
        return api.call(HttpMethod.GET, "/customers/" + customer + "/balance", null, perms, branchScope, Map.of())
                .getBody();
    }

    /** Trade debtors by the customer's sales, from the ledger: debit less credit over every entry. */
    long ledgerDebtors(UUID customer) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'trade_debtors'
                           AND l.subledger_id IN (SELECT id FROM retail_sales WHERE customer_id = ?)
                        """)
                .params(t.tenantId(), customer)
                .query(Long.class)
                .single();
    }

    /** An imported credit sale: historical, unpaid and never journalled (ADR-020 decision 9). */
    UUID imported(UUID customer, UUID branch, long total, String saleDate) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (?, ?, ?, ?, CAST(? AS date), 'credit', ?, 'UGX', ?, 0, 0, 'completed', true, gen_random_uuid())
                        """)
                .params(id, t.tenantId(), branch, "RS9" + id.toString().substring(0, 7), saleDate, customer, total)
                .update();
        return id;
    }

    // ---- limit, terms, who may set them -----------------------------------------------------

    /** Spec 13: a tenant with null limits behaves as today; and with the capability off nothing new appears or applies. */
    @Test
    void aBuyerWithNoLimitAndNoTermsIsSoldToAsBefore() {
        UUID c = customer("Test Buyer 01");
        ResponseEntity<JsonNode> r = creditSale(hq, c, "10", "2026-01-05", null, null, CLERK);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("due_date").isNull()).isTrue();
        JsonNode account = api.get("/customers/" + c + "/account", ADMIN).getBody();
        assertThat(account.has("credit_limit_minor")).isFalse();
        assertThat(account.has("terms_days")).isFalse();
        assertThat(account.get("balance_minor").asLong()).isEqualTo(1_000);
        // A very large credit sale goes through: there is no limit to cross.
        assertThat(creditSale(hq, c, "5000", "2026-01-06", null, null, CLERK).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void withTheCapabilityOffTheRoutesAreAbsentTheSaleIsAsBeforeAndNoResponseGainsAField() {
        capability("customer_accounts", false);
        UUID c = customer("Test Buyer 02");
        // The account routes answer 404 capability_not_enabled, for every method.
        for (String path : List.of("/account", "/statement", "/payments")) {
            ResponseEntity<JsonNode> r = api.get("/customers/" + c + path, MANAGER);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(code(r)).isEqualTo("capability_not_enabled");
        }
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/customers/" + c + "/account",
                                Map.of("credit_limit_minor", 5),
                                MANAGER,
                                "*",
                                Map.of("If-Match", "1"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pay(c, hq, 100).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/reports/customer-ageing", MANAGER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // A credit sale to a typed name still works, with no due date, exactly as before accounts.
        ResponseEntity<JsonNode> typed = creditSale(hq, null, "10", "2026-01-05", null, null, CLERK);
        assertThat(typed.getStatusCode()).as("%s", typed.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(typed.getBody().get("due_date").isNull()).isTrue();
        // A limit and terms written while it was on are kept but ignored: the sale is not refused and gets no due date.
        capability("customer_accounts", true);
        UUID limited = buyer("Test Buyer 03", 500L, 30);
        capability("customer_accounts", false);
        ResponseEntity<JsonNode> big = creditSale(hq, limited, "10", "2026-01-05", null, null, CLERK);
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(big.getBody().get("due_date").isNull()).isTrue();
        // No response gains a field: the customer and the credit control buyer carry nothing about accounts.
        JsonNode list = api.get("/customers", ADMIN).getBody();
        for (JsonNode item : list.get("items")) {
            assertThat(item.has("credit_limit_minor")).isFalse();
            assertThat(item.has("terms_days")).isFalse();
        }
        JsonNode control = api.get("/reports/credit-control", ADMIN).getBody();
        for (JsonNode b : control.get("buyers")) {
            assertThat(b.has("credit_limit_minor")).isFalse();
        }
    }

    @Test
    void theBuyerListAndTheBalanceCarryTheFiguresOnlyWhileTheFeatureIsOn() {
        UUID c = buyer("Test Buyer 27", 4_000L, 14);
        sold(hq, c, "10", "2026-01-05");
        imported(c, hq, 700, "2025-12-01");
        JsonNode listed = null;
        for (JsonNode item : api.get("/customers", ADMIN).getBody().get("items")) {
            if (item.get("id").asString().equals(c.toString())) {
                listed = item;
            }
        }
        assertThat(listed.get("credit_limit_minor").asLong()).isEqualTo(4_000);
        assertThat(listed.get("terms_days").asInt()).isEqualTo(14);
        assertThat(listed.get("balance_minor").asLong()).isEqualTo(1_700);
        // The sales role reads the same figures: a seller sees the limit a credit sale is held to.
        assertThat(api.get("/customers", CLERK).getBody().get("items").toString())
                .contains("credit_limit_minor");
        // The open sales say which one is imported, so a payment form can leave it out.
        JsonNode open = balance(c, ADMIN, "*").get("open_sales");
        assertThat(open).hasSize(2);
        long flagged = 0;
        for (JsonNode o : open) {
            if (o.has("historical")) {
                assertThat(o.get("historical").asBoolean()).isTrue();
                flagged++;
            }
        }
        assertThat(flagged).isEqualTo(1);
        // Off: the same buyer reads exactly as before accounts, and the balance says nothing about imports.
        int ver = version(c);
        capability("customer_accounts", false);
        for (JsonNode item : api.get("/customers", ADMIN).getBody().get("items")) {
            assertThat(item.has("credit_limit_minor")).isFalse();
            assertThat(item.has("terms_days")).isFalse();
        }
        JsonNode offBalance = balance(c, ADMIN, "*");
        assertThat(offBalance.get("balance_minor").asLong()).isEqualTo(1_700);
        offBalance
                .get("open_sales")
                .forEach(o -> assertThat(o.has("historical")).isFalse());
        // And a buyer edit answers without them too.
        ResponseEntity<JsonNode> patched = api.call(
                HttpMethod.PATCH,
                "/customers/" + c,
                Map.of("contact", "+256700000000"),
                CLERK,
                "*",
                Map.of("If-Match", String.valueOf(ver)));
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().has("credit_limit_minor")).isFalse();
        assertThat(patched.getBody().has("terms_days")).isFalse();
    }

    @Test
    void aTypedBuyerNameAloneIsRefusedWhileAccountsAreOn() {
        ResponseEntity<JsonNode> r = creditSale(hq, null, "1", "2026-01-05", null, null, MANAGER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("customer_required");
        // Cash sales are untouched.
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("branch_id", hq);
        cash.put("payment_method", "cash");
        cash.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        assertThat(api.postKeyed("/sales", cash, MANAGER, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void onlyCreditManageSetsALimitAndTermsAndTheSalesRoleCannotRaiseItsOwnBuyersLimit() {
        UUID c = customer("Test Buyer 04");
        int v = version(c);
        // The sales role holds retail.customer.manage and may read the account, but not set it (403, nothing changed).
        ResponseEntity<JsonNode> denied = api.call(
                HttpMethod.PUT,
                "/customers/" + c + "/account",
                Map.of("credit_limit_minor", 9_999_999),
                CLERK,
                "*",
                Map.of("If-Match", String.valueOf(v)));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/customers/" + c + "/account", CLERK).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(version(c)).isEqualTo(v);
        // PATCH edits name and contact only: a limit sent on it is not read, and the account stays as it was.
        ResponseEntity<JsonNode> patch = api.call(
                HttpMethod.PATCH,
                "/customers/" + c,
                Map.of("name", "Test Buyer 04 Renamed", "credit_limit_minor", 9_999_999),
                CLERK,
                "*",
                Map.of("If-Match", String.valueOf(v)));
        assertThat(patch.getStatusCode().is2xxSuccessful()
                        || patch.getStatusCode().is4xxClientError())
                .isTrue();
        assertThat(api.get("/customers/" + c + "/account", ADMIN).getBody().has("credit_limit_minor"))
                .isFalse();

        // The manager sets both; If-Match is required and a stale version is a conflict.
        ResponseEntity<JsonNode> missing = api.call(
                HttpMethod.PUT,
                "/customers/" + c + "/account",
                Map.of("terms_days", 30, "clear_credit_limit", true),
                MANAGER,
                "*",
                Map.of());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        ResponseEntity<JsonNode> ok = setAccount(c, 50_000L, 30, MANAGER);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("credit_limit_minor").asLong()).isEqualTo(50_000);
        assertThat(ok.getBody().get("terms_days").asInt()).isEqualTo(30);
        ResponseEntity<JsonNode> stale = api.call(
                HttpMethod.PUT,
                "/customers/" + c + "/account",
                Map.of("credit_limit_minor", 60_000, "clear_terms_days", true),
                MANAGER,
                "*",
                Map.of("If-Match", String.valueOf(v)));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Negative or absurd values are refused; null clears.
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/customers/" + c + "/account",
                                Map.of("credit_limit_minor", -1, "clear_terms_days", true),
                                MANAGER,
                                "*",
                                Map.of("If-Match", String.valueOf(version(c))))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/customers/" + c + "/account",
                                Map.of("terms_days", 366, "clear_credit_limit", true),
                                MANAGER,
                                "*",
                                Map.of("If-Match", String.valueOf(version(c))))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> cleared = setAccount(c, null, null, MANAGER);
        assertThat(cleared.getBody().has("credit_limit_minor")).isFalse();
        // Both changes are in the audit trail with old and new values.
        List<String> audit = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.customer.account_changed'"
                                + " ORDER BY created_at")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0)).contains("50000");
    }

    @Test
    void termsDefaultTheDueDateAndATypedDateWins() {
        UUID net30 = buyer("Test Buyer 05", null, 30);
        UUID net0 = buyer("Test Buyer 06", null, 0);
        UUID none = buyer("Test Buyer 07", 100_000L, null);
        assertThat(creditSale(hq, net30, "1", "2026-01-05", null, null, CLERK)
                        .getBody()
                        .get("due_date")
                        .asString())
                .isEqualTo("2026-02-04");
        assertThat(creditSale(hq, net0, "1", "2026-01-05", null, null, CLERK)
                        .getBody()
                        .get("due_date")
                        .asString())
                .isEqualTo("2026-01-05");
        assertThat(creditSale(hq, none, "1", "2026-01-05", null, null, CLERK)
                        .getBody()
                        .get("due_date")
                        .isNull())
                .isTrue();
        assertThat(creditSale(hq, net30, "1", "2026-01-05", "2026-03-01", null, CLERK)
                        .getBody()
                        .get("due_date")
                        .asString())
                .isEqualTo("2026-03-01");
    }

    @Test
    void theLimitAllowsEqualRefusesAboveAndCountsEveryBranchAndImportedSalesWithoutLeakingThem() {
        UUID c = buyer("Test Buyer 08", 3_000L, null);
        UUID other = sold(br2, c, "10", "2026-01-05");
        String otherNo = sale(other).get("sale_no").asString();
        imported(c, br2, 500, "2025-12-01");
        // 1,000 at another branch and 500 imported are owed: 1,500 of 3,000. 1,500 more is exactly the limit.
        ResponseEntity<JsonNode> equal = creditSale(hq, c, "15", "2026-01-06", null, null, CLERK);
        assertThat(equal.getStatusCode()).as("%s", equal.getBody()).isEqualTo(HttpStatus.CREATED);
        // One piece more is above it: 422, by how much, and nothing of the other branch's sales.
        ResponseEntity<JsonNode> above = creditSale(hq, c, "1", "2026-01-07", null, null, CLERK);
        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(above)).isEqualTo("credit_limit_exceeded");
        assertThat(above.getBody().get("exceeded_by_minor").asLong()).isEqualTo(100);
        String text = above.getBody().toString();
        assertThat(text).doesNotContain(otherNo).doesNotContain(br2.toString());
        // A refused sale leaves no sale, stock or entry behind.
        assertThat(balance(c, ADMIN, "*").get("balance_minor").asLong()).isEqualTo(3_000);
        // A payment makes room again: the limit measures what is owed now.
        assertThat(pay(
                                c,
                                hq,
                                100,
                                "cash",
                                null,
                                null,
                                MANAGER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(creditSale(hq, c, "1", "2026-01-07", null, null, CLERK).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // Cash sales to the buyer are never limited.
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("branch_id", hq);
        cash.put("payment_method", "cash");
        cash.put("customer_id", c);
        cash.put("lines", List.of(Map.of("product_id", product, "qty", "500")));
        assertThat(api.postKeyed("/sales", cash, CLERK, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anOverrideNeedsThePermissionAndAReasonAndIsAudited() {
        UUID c = buyer("Test Buyer 09", 1_000L, null);
        sold(hq, c, "10", "2026-01-05");
        // Without the override permission: refused, whatever reason is typed.
        ResponseEntity<JsonNode> clerk = creditSale(hq, c, "1", "2026-01-06", null, "The owner said so", CLERK);
        assertThat(code(clerk)).isEqualTo("credit_limit_exceeded");
        ResponseEntity<JsonNode> noOverride =
                creditSale(hq, c, "1", "2026-01-06", null, "The owner said so", NO_OVERRIDE);
        assertThat(code(noOverride)).isEqualTo("credit_limit_exceeded");
        // With it but without a reason, or with a short one: a reason is asked for.
        ResponseEntity<JsonNode> noReason = creditSale(hq, c, "1", "2026-01-06", null, null, MANAGER);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noReason)).isEqualTo("override_reason_required");
        assertThat(code(creditSale(hq, c, "1", "2026-01-06", null, "ok", MANAGER)))
                .isEqualTo("override_reason_required");
        // With both it goes through, and the audit trail holds who, why and by how much.
        ResponseEntity<JsonNode> ok =
                creditSale(hq, c, "3", "2026-01-06", null, "Long standing buyer, paying Friday", MANAGER);
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        List<String> audit = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.credit.limit_overridden'")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst())
                .contains("Long standing buyer, paying Friday")
                .contains("300")
                .contains("exceeded_by_minor");
        assertThat(TestDatabase.owner()
                        .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.sale.created'"
                                + " AND data::text LIKE '%credit_override%'")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .hasSize(1);
        // An override at a branch the user does not hold it in is not one.
        ResponseEntity<JsonNode> elsewhere = api.postKeyed(
                "/sales",
                Map.of(
                        "branch_id",
                        hq,
                        "payment_method",
                        "credit",
                        "customer_id",
                        c,
                        "credit_override_reason",
                        "Long standing buyer",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "1"))),
                CLERK,
                "*",
                UUID.randomUUID().toString());
        assertThat(code(elsewhere)).isEqualTo("credit_limit_exceeded");
    }

    @Test
    void theSaleLocksTheBuyerRowBeforeItChecksSoTwoShopsCannotBothPass() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // Deterministic: another connection holds the buyer's row; a credit sale to that buyer must wait for it.
            UUID c = buyer("Test Buyer 10", 1_500L, null);
            try (Connection holder = TestDatabase.ownerDataSource().getConnection()) {
                holder.setAutoCommit(false);
                try (Statement st = holder.createStatement()) {
                    st.execute("SELECT 1 FROM retail_customers WHERE id = '" + c + "' FOR UPDATE");
                }
                Future<ResponseEntity<JsonNode>> blocked =
                        pool.submit(() -> creditSale(hq, c, "10", "2026-01-05", null, null, CLERK));
                Thread.sleep(1_500);
                assertThat(blocked.isDone())
                        .as("the sale waits for the buyer's row")
                        .isFalse();
                holder.commit();
                assertThat(blocked.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }

            // And the outcome: two shops sell 1,000 each to one buyer with a limit of 1,500; exactly one wins, every
            // time.
            for (int round = 0; round < 5; round++) {
                UUID buyer = buyer("Test Buyer R" + round, 1_500L, null);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<ResponseEntity<JsonNode>>> both = new ArrayList<>();
                for (UUID branch : List.of(hq, br2)) {
                    both.add(pool.submit(() -> {
                        go.await();
                        return creditSale(branch, buyer, "10", "2026-01-05", null, null, CLERK);
                    }));
                }
                go.countDown();
                List<String> outcomes = new ArrayList<>();
                for (Future<ResponseEntity<JsonNode>> f : both) {
                    ResponseEntity<JsonNode> r = f.get(30, TimeUnit.SECONDS);
                    outcomes.add(r.getStatusCode().is2xxSuccessful() ? "sold" : code(r));
                }
                assertThat(outcomes).as("round %d", round).containsExactlyInAnyOrder("sold", "credit_limit_exceeded");
                assertThat(balance(buyer, ADMIN, "*").get("balance_minor").asLong())
                        .isEqualTo(1_000);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theAccountShowsExposureOnlyToACallerWhoseScopeCoversEveryBranch() {
        UUID c = buyer("Test Buyer 11", 5_000L, null);
        sold(hq, c, "10", "2026-01-05");
        sold(br2, c, "20", "2026-01-06");
        JsonNode full = api.get("/customers/" + c + "/account", ADMIN).getBody();
        assertThat(full.get("balance_minor").asLong()).isEqualTo(3_000);
        assertThat(full.get("exposure_minor").asLong()).isEqualTo(3_000);
        assertThat(full.get("available_minor").asLong()).isEqualTo(2_000);
        assertThat(full.get("over_limit").asBoolean()).isFalse();
        JsonNode scoped = api.call(HttpMethod.GET, "/customers/" + c + "/account", null, ADMIN, hq.toString(), Map.of())
                .getBody();
        assertThat(scoped.get("balance_minor").asLong()).isEqualTo(1_000);
        assertThat(scoped.has("exposure_minor")).isFalse();
        assertThat(scoped.has("available_minor")).isFalse();
        assertThat(scoped.has("over_limit")).isFalse();
        assertThat(scoped.get("credit_limit_minor").asLong()).isEqualTo(5_000);
    }

    // ---- payment on account ------------------------------------------------------------------

    @Test
    void aPaymentIsAllocatedOldestFirstAndPostsInTheReceivingBranch() {
        UUID c = buyer("Test Buyer 12", null, null);
        UUID s1 = sold(hq, c, "10", "2026-01-05");
        UUID s3 = sold(hq, c, "5", "2026-03-05");
        UUID s2 = sold(br2, c, "8", "2026-02-05");
        UUID imported = imported(c, hq, 700, "2025-10-01");
        ResponseEntity<JsonNode> r = pay(
                c,
                br2,
                1_500,
                "mobile_money",
                null,
                "2026-04-01",
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode receipt = r.getBody();
        assertThat(receipt.get("allocation").asString()).isEqualTo("oldest_first");
        assertThat(receipt.get("receipt_no").asString()).startsWith("RC");
        // January's 1,000 first, then 500 of February's 800; the imported sale and March's are untouched.
        JsonNode parts = receipt.get("allocations");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).get("sale_id").asString()).isEqualTo(s1.toString());
        assertThat(parts.get(0).get("amount_minor").asLong()).isEqualTo(1_000);
        assertThat(parts.get(1).get("sale_id").asString()).isEqualTo(s2.toString());
        assertThat(parts.get(1).get("amount_minor").asLong()).isEqualTo(500);
        assertThat(sale(s1).get("paid_minor").asLong()).isEqualTo(1_000);
        assertThat(sale(s2).get("paid_minor").asLong()).isEqualTo(500);
        assertThat(sale(s3).get("paid_minor").asLong()).isZero();
        assertThat(sale(imported).get("paid_minor").asLong()).isZero();
        // They are ordinary sale payments, listed on the sale, with the method and date of the receipt.
        JsonNode payments =
                api.get("/sales/" + s2 + "/payments", ADMIN).getBody().get("items");
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).get("method").asString()).isEqualTo("mobile_money");
        assertThat(payments.get(0).get("paid_on").asString()).isEqualTo("2026-04-01");
        // One entry, in the receiving branch (not the sale's): debit mobile money, credit trade debtors by sale.
        UUID entry = UUID.fromString(receipt.get("journal_entry_id").asString());
        assertThat(TestDatabase.owner()
                        .sql("SELECT branch_id FROM journal_entries WHERE id = ?")
                        .param(entry)
                        .query(UUID.class)
                        .single())
                .isEqualTo(br2);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT a.system_key || ':' || l.debit || ':' || l.credit || ':' || coalesce(l.subledger_id::text, '-')
                                  FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
                                 WHERE l.entry_id = ? ORDER BY l.line_no
                                """)
                        .param(entry)
                        .query(String.class)
                        .list())
                .containsExactly("mobile_money:1500:0:-", "trade_debtors:0:1000:" + s1, "trade_debtors:0:500:" + s2);
        // The ledger agrees with the sales: 800 is owed on the sales of the books, and the imported 700 is outside it.
        assertThat(ledgerDebtors(c)).isEqualTo(300 + 500);
        assertThat(balance(c, ADMIN, "*").get("balance_minor").asLong()).isEqualTo(300 + 500 + 700);
    }

    @Test
    void anAmountThatCannotBeAllocatedIsRefusedAndNothingIsHeld() {
        UUID c = buyer("Test Buyer 13", null, null);
        sold(hq, c, "10", "2026-01-05");
        imported(c, hq, 5_000, "2025-10-01");
        ResponseEntity<JsonNode> r = pay(c, hq, 1_001);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("payment_exceeds_allocatable");
        assertThat(r.getBody().get("allocatable_minor").asLong()).isEqualTo(1_000);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_account_payments WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        // A buyer with nothing to pay: allocatable is zero.
        UUID idle = customer("Test Buyer 14");
        assertThat(pay(idle, hq, 1).getBody().get("allocatable_minor").asLong()).isZero();
        // The exact balance settles it.
        assertThat(pay(c, hq, 1_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void sourcesOutsideTheCallersScopeAreSkippedAndTheReceivingBranchMustBeInScope() {
        UUID c = buyer("Test Buyer 15", null, null);
        UUID elsewhere = sold(br2, c, "10", "2026-01-05");
        UUID here = sold(hq, c, "5", "2026-02-05");
        // A cashier scoped to head office: the older sale at the other branch is skipped (it would be a 404 to them).
        ResponseEntity<JsonNode> scoped = pay(
                c,
                hq,
                500,
                "cash",
                null,
                null,
                CLERK,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(scoped.getStatusCode()).as("%s", scoped.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(scoped.getBody().get("allocations").get(0).get("sale_id").asString())
                .isEqualTo(here.toString());
        assertThat(sale(elsewhere).get("paid_minor").asLong()).isZero();
        // More than their scope holds is refused with what they can allocate.
        ResponseEntity<JsonNode> more = pay(
                c,
                hq,
                100,
                "cash",
                null,
                null,
                CLERK,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(code(more)).isEqualTo("payment_exceeds_allocatable");
        assertThat(more.getBody().get("allocatable_minor").asLong()).isZero();
        // Receiving at a branch outside the scope is refused: 404 unknown_branch (spec section 2.2, issue #257).
        ResponseEntity<JsonNode> wrongBranch = pay(
                c,
                br2,
                100,
                "cash",
                null,
                null,
                CLERK,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(wrongBranch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(wrongBranch)).isEqualTo("unknown_branch");
        // Naming the other branch's sale by hand is refused as unknown.
        ResponseEntity<JsonNode> named = pay(
                c,
                hq,
                100,
                "cash",
                List.of(part(elsewhere, 100)),
                null,
                CLERK,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(named.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(named.getBody().toString()).contains("unknown_sale");
    }

    @Test
    void aManualAllocationIsCheckedLineByLine() {
        UUID c = buyer("Test Buyer 16", null, null);
        UUID s1 = sold(hq, c, "10", "2026-01-05");
        UUID s2 = sold(hq, c, "10", "2026-02-05");
        UUID old = imported(c, hq, 900, "2025-10-01");
        // The sum must equal the payment.
        ResponseEntity<JsonNode> off = pay(
                c,
                hq,
                700,
                "cash",
                List.of(part(s2, 600)),
                null,
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(code(off)).isEqualTo("allocations_must_equal_amount");
        // No more than a sale owes, no duplicate, no imported sale.
        ResponseEntity<JsonNode> over = pay(
                c,
                hq,
                1_100,
                "cash",
                List.of(part(s2, 1_100)),
                null,
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(over.getBody().toString()).contains("payment_exceeds_balance");
        ResponseEntity<JsonNode> twice = pay(
                c,
                hq,
                200,
                "cash",
                List.of(part(s2, 100), part(s2, 100)),
                null,
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(twice.getBody().toString()).contains("duplicate");
        ResponseEntity<JsonNode> hist = pay(
                c,
                hq,
                100,
                "cash",
                List.of(part(old, 100)),
                null,
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(hist.getBody().toString()).contains("sale_historical");
        // The cashier's choice is followed, newest first if that is what was asked.
        ResponseEntity<JsonNode> ok = pay(
                c,
                hq,
                900,
                "bank",
                List.of(part(s2, 600), part(s1, 300)),
                null,
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("allocation").asString()).isEqualTo("manual");
        assertThat(sale(s2).get("paid_minor").asLong()).isEqualTo(600);
        assertThat(sale(s1).get("paid_minor").asLong()).isEqualTo(300);
        // A payment dated before a sale cannot be allocated to it.
        ResponseEntity<JsonNode> early = pay(
                c,
                hq,
                100,
                "cash",
                List.of(part(s2, 100)),
                "2026-02-04",
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // A future date is refused.
        ResponseEntity<JsonNode> future = pay(
                c,
                hq,
                100,
                "cash",
                null,
                "2026-10-09",
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aPaymentOnAccountIsIdempotentAndNeedsItsKey() {
        UUID c = buyer("Test Buyer 17", null, null);
        UUID s1 = sold(hq, c, "10", "2026-01-05");
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = pay(c, hq, 400, "cash", null, null, MANAGER, "*", key);
        ResponseEntity<JsonNode> again = pay(c, hq, 400, "cash", null, null, MANAGER, "*", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());
        assertThat(sale(s1).get("paid_minor").asLong()).isEqualTo(400);
        // The same key with another body is refused.
        assertThat(code(pay(c, hq, 500, "cash", null, null, MANAGER, "*", key))).isEqualTo("idempotency_key_reused");
        // No key: refused.
        ResponseEntity<JsonNode> none = api.call(
                HttpMethod.POST,
                "/customers/" + c + "/payments",
                Map.of("branch_id", hq, "amount_minor", 100, "method", "cash"),
                MANAGER,
                "*",
                Map.of());
        assertThat(code(none)).isEqualTo("idempotency_key_missing");
        // The sale-paid route needs retail.sale.create; the receipt route needs it too.
        assertThat(pay(
                                c,
                                hq,
                                100,
                                "cash",
                                null,
                                null,
                                "retail.sale.read",
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aReceiptCanBeReversedOnceAsAWholeAndTheMoneyAndTheSalesGoBack() {
        UUID c = buyer("Test Buyer 18", null, null);
        UUID s1 = sold(hq, c, "10", "2026-01-05");
        UUID s2 = sold(hq, c, "10", "2026-02-05");
        JsonNode receipt = pay(
                        c,
                        br2,
                        1_500,
                        "cash",
                        null,
                        "2026-03-01",
                        MANAGER,
                        "*",
                        UUID.randomUUID().toString())
                .getBody();
        UUID id = UUID.fromString(receipt.get("id").asString());
        assertThat(ledgerDebtors(c)).isEqualTo(500);
        // The reversal needs retail.sale.void and a reason; the sales role has neither.
        assertThat(reverse(c, id, "Wrong buyer", CLERK).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> blank = api.postKeyed(
                "/customers/" + c + "/payments/" + id + "/reverse",
                Map.of("reason", " "),
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // One rule on both sides: a reason is at least five characters once trimmed, as the screen asks.
        ResponseEntity<JsonNode> shortReason = reverse(c, id, "  oops ", MANAGER);
        assertThat(shortReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(shortReason.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("reason");
        assertThat(sale(s1).get("paid_minor").asLong()).isEqualTo(1_000);
        ResponseEntity<JsonNode> done = reverse(c, id, "Wrong buyer", MANAGER);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("reversed");
        assertThat(done.getBody().get("reverse_reason").asString()).isEqualTo("Wrong buyer");
        // The sales owe again, the ledger agrees, and the reversed payments are no longer listed on the sales.
        assertThat(sale(s1).get("paid_minor").asLong()).isZero();
        assertThat(sale(s2).get("paid_minor").asLong()).isZero();
        assertThat(ledgerDebtors(c)).isEqualTo(2_000);
        assertThat(api.get("/sales/" + s1 + "/payments", ADMIN).getBody().get("items"))
                .isEmpty();
        // Once only; and the money can be recorded again, allocated by hand this time.
        ResponseEntity<JsonNode> twice = reverse(c, id, "Again", MANAGER);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(twice)).isEqualTo("receipt_reversed");
        ResponseEntity<JsonNode> redo = pay(
                c,
                br2,
                1_500,
                "cash",
                List.of(part(s2, 1_000), part(s1, 500)),
                "2026-03-01",
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(redo.getStatusCode()).as("%s", redo.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ledgerDebtors(c)).isEqualTo(500);
        // The listing shows both receipts, newest first, with the reversed one marked.
        JsonNode listed =
                api.get("/customers/" + c + "/payments", ADMIN).getBody().get("items");
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("status").asString()).isEqualTo("posted");
        assertThat(listed.get(1).get("status").asString()).isEqualTo("reversed");
        // A reversal and its reason are audited.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action ="
                                + " 'retail.customer.account_payment_reversed'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // The receipt row cannot be edited or deleted by the application, only reversed.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_account_payments WHERE tenant_id = ? AND status = 'reversed'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aReceiptOfAnotherBuyerOrBranchScopeIsNotFound() {
        UUID a = buyer("Test Buyer 19", null, null);
        UUID b = buyer("Test Buyer 20", null, null);
        sold(hq, a, "10", "2026-01-05");
        UUID id = UUID.fromString(pay(a, hq, 100).getBody().get("id").asString());
        assertThat(reverse(b, id, "Wrong buyer", MANAGER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // A reverser scoped to the other branch cannot reach a receipt received at head office.
        ResponseEntity<JsonNode> scoped = api.postKeyed(
                "/customers/" + a + "/payments/" + id + "/reverse",
                Map.of("reason", "Wrong buyer"),
                MANAGER,
                br2.toString(),
                UUID.randomUUID().toString());
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Received at head office but paying a sale at the other branch: a reverser scoped to head office alone may not
        // undo
        // what it did to the other branch's sale.
        UUID b2 = buyer("Test Buyer 28", null, null);
        UUID there = sold(br2, b2, "10", "2026-01-05");
        UUID receiptAtHq = UUID.fromString(pay(b2, hq, 300).getBody().get("id").asString());
        ResponseEntity<JsonNode> partial = api.postKeyed(
                "/customers/" + b2 + "/payments/" + receiptAtHq + "/reverse",
                Map.of("reason", "Wrong buyer"),
                MANAGER,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(sale(there).get("paid_minor").asLong()).isEqualTo(300);
        assertThat(reverse(b2, receiptAtHq, "Wrong buyer", MANAGER).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void theDatabaseRefusesWhatTheServiceNeverDoes() {
        UUID c = buyer("Test Buyer 26", null, null);
        UUID s1 = sold(hq, c, "10", "2026-01-05");
        JsonNode receipt = pay(c, hq, 400).getBody();
        UUID id = UUID.fromString(receipt.get("id").asString());
        // The paid amount of a sale cannot fall below what its payments that were not reversed add up to.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_sales SET paid_minor = 300 WHERE id = ?")
                        .param(s1)
                        .update())
                .hasStackTraceContaining("only the paid amount");
        // A receipt is never edited: not its amount, not its status back, and it is never deleted.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_account_payments SET amount_minor = 1 WHERE id = ?")
                        .param(id)
                        .update())
                .hasStackTraceContaining("can only be reversed");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM retail_account_payments WHERE id = ?")
                        .param(id)
                        .update())
                .isNotNull();
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM retail_account_payment_allocations WHERE receipt_id = ?")
                        .param(id)
                        .update())
                .isNotNull();
        // A reversal is once: the receipt cannot be put back to posted.
        assertThat(reverse(c, id, "Wrong buyer", MANAGER).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql(
                                "UPDATE retail_account_payments SET status = 'posted', reversed_at = NULL, reversed_on = NULL,"
                                        + " reversed_by = NULL, reverse_reason = NULL, reversal_entry_id = NULL WHERE id = ?")
                        .param(id)
                        .update())
                .hasStackTraceContaining("can only be reversed");
        // After the reversal the sale owes again, and the paid amount sits at what the unreversed payments add up to.
        assertThat(sale(s1).get("paid_minor").asLong()).isZero();
        // A payment recorded again can then be raised and is held at its own floor.
        pay(c, hq, 250);
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_sales SET paid_minor = 100 WHERE id = ?")
                        .param(s1)
                        .update())
                .hasStackTraceContaining("only the paid amount");
    }

    /**
     * V44 lets a sale's paid amount fall only inside the reversal of one named receipt: the transaction-local setting holds
     * that receipt's id, the receipt must have allocated to this very sale, read reversed, and the new value is the old one
     * less exactly that allocation (and not below the sale's unreversed payments). Everything else is refused.
     */
    @Test
    void aPaidAmountFallsOnlyInsideTheReversalOfTheReceiptThatPaidItByExactlyThatAmount() throws Exception {
        UUID c = buyer("Test Buyer 29", null, null);
        UUID s1 = sold(hq, c, "10", "2026-01-05");
        UUID s2 = sold(hq, c, "10", "2026-02-05");
        UUID s3 = sold(hq, c, "10", "2026-03-05");
        assertThat(api.postKeyed(
                                "/sales/" + s1 + "/payments",
                                Map.of("amount_minor", 400, "method", "cash", "paid_on", "2026-01-10"),
                                MANAGER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(api.postKeyed(
                                "/sales/" + s3 + "/payments",
                                Map.of("amount_minor", 50, "method", "cash", "paid_on", "2026-03-10"),
                                MANAGER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // R pays 600 of the first sale and 100 of the second; R2 pays 200 more of the second.
        String r = pay(c, hq, 700).getBody().get("id").asString();
        String r2 = pay(c, hq, 200).getBody().get("id").asString();
        assertThat(sale(s1).get("paid_minor").asLong()).isEqualTo(1_000);
        assertThat(sale(s2).get("paid_minor").asLong()).isEqualTo(300);
        // A plain UPDATE from the application role is refused: to any lower value, the right one included.
        for (long lower : new long[] {0, 400, 999}) {
            assertThatThrownBy(() -> updatePaid(s1, lower, null)).hasStackTraceContaining("only the paid amount");
        }
        // The owner role is held to it too (bms.allow_mutation is the only way round, for restores).
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_sales SET paid_minor = 400 WHERE id = ?")
                        .param(s1)
                        .update())
                .hasStackTraceContaining("only the paid amount");
        // A setting that merely says "on", or names no receipt, or a receipt that is not there, opens nothing.
        assertThatThrownBy(() -> updatePaid(s1, 0, "on")).hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> updatePaid(s1, 0, UUID.randomUUID().toString()))
                .hasStackTraceContaining("only the paid amount");
        // The right receipt and the right value, but the receipt has not been reversed yet.
        assertThatThrownBy(() -> updatePaid(s1, 400, r)).hasStackTraceContaining("only the paid amount");
        assertThat(sale(s1).get("paid_minor").asLong()).isEqualTo(1_000);

        // The reversal itself: each sale falls by what R paid it, and nothing else moves.
        assertThat(reverse(c, UUID.fromString(r), "Wrong buyer", MANAGER).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(sale(s1).get("paid_minor").asLong()).isEqualTo(400);
        assertThat(sale(s2).get("paid_minor").asLong()).isEqualTo(200);
        assertThat(sale(s3).get("paid_minor").asLong()).isEqualTo(50);
        // With R's id and R reversed: zero is refused, another value is refused, and a second lowering is refused (the
        // allocation was given back once; the sale cannot go below its unreversed payments).
        assertThatThrownBy(() -> updatePaid(s1, 0, r)).hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> updatePaid(s2, 100, r)).hasStackTraceContaining("only the paid amount");
        // R does not allocate to the third sale: its id opens nothing there, whatever the value.
        assertThatThrownBy(() -> updatePaid(s3, 0, r)).hasStackTraceContaining("only the paid amount");
        // R2 is posted, not reversed: its id opens nothing, even for the exact amount it paid.
        assertThatThrownBy(() -> updatePaid(s2, 0, r2)).hasStackTraceContaining("only the paid amount");
        // Without the setting nothing is accepted either.
        assertThatThrownBy(() -> updatePaid(s1, 0, null)).hasStackTraceContaining("only the paid amount");
        // A sale whose paid amount no payment row backs cannot be written down by anyone's receipt id or by a plain
        // UPDATE.
        UUID legacy = UUID.randomUUID();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                            currency, total_minor, cost_total_minor, paid_minor, status, created_by)
                        VALUES (?, ?, ?, 'RS9LEGACY1', DATE '2026-01-15', 'credit', ?, 'UGX', 300, 249, 100, 'completed',
                            gen_random_uuid())
                        """).params(legacy, t.tenantId(), hq, c).update();
        assertThatThrownBy(() -> updatePaid(legacy, 0, null)).hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> updatePaid(legacy, 0, r)).hasStackTraceContaining("only the paid amount");
        assertThat(sale(legacy).get("paid_minor").asLong()).isEqualTo(100);
    }

    /** The list a sale was priced from is fixed when the sale is made (V41); the V44 guard keeps it so. */
    @Test
    void theApplicationCannotChangeTheListASaleWasPricedFrom() throws Exception {
        UUID c = buyer("Test Buyer 34", null, null);
        UUID s = sold(hq, c, "1", "2026-01-05");
        assertThatThrownBy(() -> updateSale(s, "price_list_id = gen_random_uuid()", null))
                .hasStackTraceContaining("only the paid amount");
        assertThat(TestDatabase.owner()
                        .sql("SELECT price_list_id IS NULL FROM retail_sales WHERE id = ?")
                        .param(s)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    /** One UPDATE of a sale's paid amount as the application role; the reversal setting holds {@code receipt} or is not set. */
    void updatePaid(UUID sale, long paid, String receipt) throws Exception {
        updateSale(sale, "paid_minor = " + paid, receipt);
    }

    /** One UPDATE of a sale as the application role, in its own transaction, with the reversal setting raised or not. */
    void updateSale(UUID sale, String assignment, String receipt) throws Exception {
        try (Connection conn = TestDatabase.appDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + t.tenantId() + "', true)");
                if (receipt != null) {
                    st.execute("SELECT set_config('bms.reverse_account_payment', '" + receipt + "', true)");
                }
                st.executeUpdate("UPDATE retail_sales SET " + assignment + " WHERE id = '" + sale + "'");
            }
            conn.commit();
        }
    }

    // ---- statement -------------------------------------------------------------------------------

    @Test
    void theStatementCloseEqualsTheLedgerAndTheBranchScopedBalance() {
        UUID c = buyer("Test Buyer 21", null, null);
        UUID s1 = sold(hq, c, "10", "2026-01-05");
        UUID s2 = sold(br2, c, "20", "2026-01-20");
        UUID s3 = sold(hq, c, "4", "2026-02-10");
        imported(c, hq, 700, "2025-11-01");
        // A direct payment on one sale, a payment on account across sales, and a reversal.
        assertThat(api.postKeyed(
                                "/sales/" + s1 + "/payments",
                                Map.of("amount_minor", 250, "method", "cash", "paid_on", "2026-01-10"),
                                MANAGER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode receipt = pay(
                        c,
                        br2,
                        1_000,
                        "cash",
                        null,
                        "2026-02-01",
                        MANAGER,
                        "*",
                        UUID.randomUUID().toString())
                .getBody();
        UUID rid = UUID.fromString(receipt.get("id").asString());
        assertThat(reverse(c, rid, "Wrong buyer", MANAGER).getStatusCode()).isEqualTo(HttpStatus.OK);
        pay(
                c,
                hq,
                600,
                "bank",
                null,
                "2026-02-15",
                MANAGER,
                "*",
                UUID.randomUUID().toString());

        // A full-scope caller: the statement closes at the ledger (debtors on the books' sales) and the balance adds
        // the
        // imported 700, which the ledger never held.
        JsonNode full = statement(c, "2025-01-01", TODAY, ADMIN, "*");
        long ledger = ledgerDebtors(c);
        assertThat(full.get("closing_minor").asLong()).isEqualTo(ledger);
        long balance = balance(c, ADMIN, "*").get("balance_minor").asLong();
        assertThat(full.get("balance_minor").asLong()).isEqualTo(balance);
        assertThat(full.get("before_system_start").get("total_minor").asLong()).isEqualTo(700);
        assertThat(full.get("before_system_start").get("sales")).hasSize(1);
        assertThat(balance - ledger).isEqualTo(700);
        // 3,400 sold less 250 and 600 paid; the 1,000 receipt was reversed.
        assertThat(ledger).isEqualTo(1_000 + 2_000 + 400 - 250 - 600);
        // Running balance: each line moves it, the last line is the close, the opening is zero.
        assertThat(full.get("opening_minor").asLong()).isZero();
        long running = 0;
        for (JsonNode line : full.get("lines")) {
            running +=
                    line.get("debit_minor").asLong() - line.get("credit_minor").asLong();
            assertThat(line.get("balance_minor").asLong()).isEqualTo(running);
        }
        assertThat(running).isEqualTo(full.get("closing_minor").asLong());
        List<String> kinds = new ArrayList<>();
        full.get("lines").forEach(l -> kinds.add(l.get("kind").asString()));
        assertThat(kinds)
                .containsExactly("sale", "payment", "sale", "account_payment", "sale", "account_payment", "reversal");
        // The receipt is one line for its allocations.
        JsonNode accountLine = full.get("lines").get(3);
        assertThat(accountLine.get("credit_minor").asLong()).isEqualTo(1_000);
        assertThat(accountLine.get("reference").asString()).startsWith("RC");

        // A caller scoped to head office: the statement and the balance both cover head office's sales only.
        JsonNode scoped = statement(c, "2025-01-01", TODAY, ADMIN, hq.toString());
        long scopedBalance =
                balance(c, ADMIN, hq.toString()).get("balance_minor").asLong();
        assertThat(scoped.get("balance_minor").asLong()).isEqualTo(scopedBalance);
        assertThat(scoped.get("closing_minor").asLong())
                .isNotEqualTo(full.get("closing_minor").asLong());
        // The range: the opening balance carries what came before, and the closing is the same.
        JsonNode february = statement(c, "2026-02-01", "2026-02-28", ADMIN, "*");
        JsonNode january = statement(c, "2026-01-01", "2026-01-31", ADMIN, "*");
        assertThat(february.get("opening_minor").asLong())
                .isEqualTo(january.get("closing_minor").asLong());
        JsonNode later = statement(c, "2026-03-01", null, ADMIN, "*");
        assertThat(later.get("opening_minor").asLong())
                .isEqualTo(february.get("closing_minor").asLong());
        assertThat(later.get("closing_minor").asLong())
                .isEqualTo(full.get("closing_minor").asLong());
        // A range backwards, or longer than the limit, is refused.
        assertThat(api.get("/customers/" + c + "/statement?from=2026-02-01&to=2026-01-01", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/customers/" + c + "/statement?from=2020-01-01&to=2026-01-01", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(s2).isNotNull();
    }

    // ---- cash book, credit control -----------------------------------------------------------------

    @Test
    void aCashPaymentOnAccountIsTakenInTheReceivingBranchAndItsReversalLeavesThatBranch() {
        UUID c = buyer("Test Buyer 22", null, null);
        sold(hq, c, "10", "2026-01-05");
        String perms = ADMIN + ",retail.cashbook.read";
        pay(
                c,
                br2,
                400,
                "cash",
                null,
                "2026-01-10",
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        JsonNode receipt = api.get("/customers/" + c + "/payments", ADMIN)
                .getBody()
                .get("items")
                .get(0);
        // The cash takings of the day are the receiving branch's, not the sale's branch's.
        JsonNode br2Days = api.get("/reports/cash/daily?branch_id=" + br2 + "&from=2026-01-10&to=2026-01-10", perms)
                .getBody();
        assertThat(br2Days.get("items").get(0).get("cash_takings_minor").asLong())
                .isEqualTo(400);
        assertThat(br2Days.get("items").get(0).get("other_movements_minor").asLong())
                .isZero();
        JsonNode hqDays = api.get("/reports/cash/daily?branch_id=" + hq + "&from=2026-01-10&to=2026-01-10", perms)
                .getBody();
        hqDays.get("items")
                .forEach(row ->
                        assertThat(row.get("cash_takings_minor").asLong()).isZero());
        // Reversed: the day it was taken stays as it was; the reversal is the void of the day it was undone, so the
        // closing still agrees with the ledger's cash on hand.
        assertThat(reverse(c, UUID.fromString(receipt.get("id").asString()), "Wrong buyer", MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String today = TODAY;
        JsonNode after = api.get("/reports/cash/daily?branch_id=" + br2 + "&from=2026-01-10&to=" + today, perms)
                .getBody();
        assertThat(dayRow(after, "2026-01-10").get("cash_takings_minor").asLong())
                .isEqualTo(400);
        assertThat(dayRow(after, today).get("cash_sale_voids_minor").asLong()).isEqualTo(400);
        for (JsonNode row : after.get("items")) {
            assertThat(row.get("other_movements_minor").asLong()).isZero();
        }
    }

    @Test
    void creditControlCarriesTheLimitAndLeavesAReversedPaymentOut() {
        UUID c = buyer("Test Buyer 23", 9_000L, null);
        sold(hq, c, "10", "2026-01-05");
        UUID receipt = UUID.fromString(pay(
                        c,
                        hq,
                        300,
                        "cash",
                        null,
                        "2026-01-10",
                        MANAGER,
                        "*",
                        UUID.randomUUID().toString())
                .getBody()
                .get("id")
                .asString());
        JsonNode control = api.get("/reports/credit-control?from=2026-01-01&to=2026-01-31", ADMIN)
                .getBody();
        assertThat(control.get("payments_minor").asLong()).isEqualTo(300);
        assertThat(control.get("buyers").get(0).get("credit_limit_minor").asLong())
                .isEqualTo(9_000);
        reverse(c, receipt, "Wrong buyer", MANAGER);
        // The receipt stays on the day it was received, so a range that holds that day and not the day it was reversed
        // (today) reports what it reported before: the books keep the money on that day, as the ledger and the cash
        // book do.
        JsonNode january = api.get("/reports/credit-control?from=2026-01-01&to=2026-01-31", ADMIN)
                .getBody();
        assertThat(january.get("payments_minor").asLong()).isEqualTo(300);
        assertThat(january.get("payments").get(0).get("date").asString()).isEqualTo("2026-01-10");
        // The reversal is its own negative row on the day it was undone.
        JsonNode both = api.get("/reports/credit-control?from=2026-01-01&to=" + TODAY, ADMIN)
                .getBody();
        assertThat(both.get("payments_minor").asLong()).isZero();
        assertThat(both.get("payments")).hasSize(2);
        assertThat(both.get("payments").get(1).get("date").asString()).isEqualTo(TODAY);
        assertThat(both.get("payments").get(1).get("amount_minor").asLong()).isEqualTo(-300);
        JsonNode lateOnly = api.get("/reports/credit-control?from=2026-02-01&to=" + TODAY, ADMIN)
                .getBody();
        assertThat(lateOnly.get("payments_minor").asLong()).isEqualTo(-300);
        // The customer ageing lists the buyer with the limit; over limit is said only to a full-scope caller.
        sold(br2, c, "80", "2026-01-06");
        JsonNode ageing = api.get("/reports/customer-ageing", ADMIN).getBody();
        JsonNode row = ageing.get("customers").get(0);
        assertThat(row.get("customer_id").asString()).isEqualTo(c.toString());
        assertThat(row.get("credit_limit_minor").asLong()).isEqualTo(9_000);
        assertThat(row.get("over_limit").asBoolean()).isFalse();
        ResponseEntity<JsonNode> over = creditSale(hq, c, "5", "2026-01-08", null, "Standing arrangement", MANAGER);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The credit control report says it the same way: over the limit only to a caller whose scope covers every
        // branch.
        JsonNode controlRow = api.get("/reports/credit-control", ADMIN)
                .getBody()
                .get("buyers")
                .get(0);
        assertThat(controlRow.get("over_limit").asBoolean()).isTrue();
        JsonNode scopedControlRow = api.call(
                        HttpMethod.GET, "/reports/credit-control", null, ADMIN, hq.toString(), Map.of())
                .getBody()
                .get("buyers")
                .get(0);
        assertThat(scopedControlRow.get("credit_limit_minor").asLong()).isEqualTo(9_000);
        assertThat(scopedControlRow.has("over_limit")).isFalse();
        JsonNode overRow = api.get("/reports/customer-ageing", ADMIN)
                .getBody()
                .get("customers")
                .get(0);
        assertThat(overRow.get("over_limit").asBoolean()).isTrue();
        JsonNode scopedRow = api.call(HttpMethod.GET, "/reports/customer-ageing", null, ADMIN, hq.toString(), Map.of())
                .getBody()
                .get("customers")
                .get(0);
        assertThat(scopedRow.has("over_limit")).isFalse();
    }

    // ---- the business day ----------------------------------------------------------------------------

    /** At 22:30 UTC the business date is the next day: every default date and every bucket uses it, not the UTC date. */
    @Test
    void theBusinessDateNotTheUtcDateDatesTheSaleTheTermsThePaymentTheReversalAndTheStatement() {
        UUID c = buyer("Test Buyer 24", null, 30);
        JsonNode sale = creditSale(hq, c, "10", null, null, null, CLERK).getBody();
        assertThat(sale.get("sale_date").asString()).isEqualTo(TODAY);
        assertThat(sale.get("due_date").asString()).isEqualTo("2026-11-07");
        JsonNode receipt = pay(
                        c,
                        hq,
                        400,
                        "cash",
                        null,
                        null,
                        MANAGER,
                        "*",
                        UUID.randomUUID().toString())
                .getBody();
        assertThat(receipt.get("paid_on").asString()).isEqualTo(TODAY);
        // A payment dated with the UTC day is dated before the sale (the Kampala day), so there is nothing for it to
        // pay.
        assertThat(pay(
                                c,
                                hq,
                                100,
                                "cash",
                                null,
                                UTC_DAY,
                                MANAGER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        UUID id = UUID.fromString(receipt.get("id").asString());
        assertThat(reverse(c, id, "Wrong buyer", MANAGER).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(TestDatabase.owner()
                        .sql("SELECT reversed_on::text FROM retail_account_payments WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo(TODAY);
        // The statement's default end is the business day, so the lines of today are in it, and it starts 90 days back.
        JsonNode st = api.get("/customers/" + c + "/statement", ADMIN).getBody();
        assertThat(st.get("to").asString()).isEqualTo(TODAY);
        assertThat(st.get("from").asString()).isEqualTo("2026-07-11");
        List<String> kinds = new ArrayList<>();
        st.get("lines").forEach(l -> kinds.add(l.get("kind").asString()));
        assertThat(kinds).containsExactly("sale", "account_payment", "reversal");
        assertThat(st.get("closing_minor").asLong()).isEqualTo(1_000);
        // The void of a sale is dated by the business day too, not the UTC one.
        UUID other = sold(hq, c, "1", "2026-10-01");
        assertThat(api.post("/sales/" + other + "/void", Map.of("reason", "Entered twice"), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // A sale is voided with the database's own clock (voided_at), which a test cannot pin, so the day to ask for is
        // read.
        String voidDay = TestDatabase.owner()
                .sql("SELECT (voided_at AT TIME ZONE 'Africa/Kampala')::date::text FROM retail_sales WHERE id = ?")
                .param(other)
                .query(String.class)
                .single();
        JsonNode after = api.get("/customers/" + c + "/statement?from=" + voidDay + "&to=" + voidDay, ADMIN)
                .getBody();
        JsonNode lastLine = after.get("lines").get(after.get("lines").size() - 1);
        assertThat(lastLine.get("kind").asString()).isEqualTo("void");
        assertThat(lastLine.get("date").asString()).isEqualTo(voidDay);
    }

    /** Spec 5.2: not yet due, 1 to 30, 31 to 60, 61 to 90 and over 90 days past the due date, on the business day. */
    @Test
    void theAgeingBucketsAreCountedInBusinessDaysPastTheDueDateAtEveryEdge() {
        UUID c = buyer("Test Buyer 25", null, null);
        // Today is 2026-10-08 in Kampala. Days past due: 0, 1, 30, 31, 60, 61, 90, 91.
        for (String due : List.of(
                "2026-10-08",
                "2026-10-07",
                "2026-09-08",
                "2026-09-07",
                "2026-08-09",
                "2026-08-08",
                "2026-07-10",
                "2026-07-09")) {
            ResponseEntity<JsonNode> r = creditSale(hq, c, "1", "2026-06-01", due, null, MANAGER);
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        // A sale with no due date counts as not yet due.
        sold(hq, c, "1", "2026-06-01");
        JsonNode report = api.get("/reports/customer-ageing", ADMIN).getBody();
        assertThat(report.get("as_of").asString()).isEqualTo(TODAY);
        JsonNode row = report.get("customers").get(0);
        JsonNode a = row.get("ageing");
        assertThat(a.get("owed_minor").asLong()).isEqualTo(900);
        assertThat(a.get("not_due_minor").asLong()).isEqualTo(200);
        assertThat(a.get("days1_to30_minor").asLong()).isEqualTo(200);
        assertThat(a.get("days31_to60_minor").asLong()).isEqualTo(200);
        assertThat(a.get("days61_to90_minor").asLong()).isEqualTo(200);
        assertThat(a.get("over90_minor").asLong()).isEqualTo(100);
        assertThat(row.get("oldest_overdue_days").asInt()).isEqualTo(91);
        assertThat(report.get("totals").get("owed_minor").asLong()).isEqualTo(900);
        // The credit control screen's own ageing agrees with it.
        JsonNode control = api.get("/reports/credit-control", ADMIN).getBody();
        assertThat(control.get("totals").get("days1_to30_minor").asLong()).isEqualTo(200);
        assertThat(control.get("totals").get("over90_minor").asLong()).isEqualTo(100);
        // A payment takes the oldest first, so the oldest bucket empties first.
        assertThat(pay(c, hq, 150).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Allocation is oldest first by sale date, ties in the order the sales were made. All eight share a sale date
        // here,
        // so the order is creation order; that the sale date and not the due date decides is its own test. What matters
        // here is that the total fell and the buckets still add up.
        JsonNode after = api.get("/reports/customer-ageing", ADMIN)
                .getBody()
                .get("customers")
                .get(0)
                .get("ageing");
        assertThat(after.get("owed_minor").asLong()).isEqualTo(750);
        long sum = after.get("not_due_minor").asLong()
                + after.get("days1_to30_minor").asLong()
                + after.get("days31_to60_minor").asLong()
                + after.get("days61_to90_minor").asLong()
                + after.get("over90_minor").asLong();
        assertThat(sum).isEqualTo(750);
    }

    // ---- the account, scoped views -----------------------------------------------------------------

    @Test
    void anAccountRequestMustDecideTheLimitAndTheTermsOnPurpose() {
        UUID c = buyer("Test Buyer 30", 7_000L, 14);
        int v = version(c);
        String path = "/customers/" + c + "/account";
        Map<String, String> match = Map.of("If-Match", String.valueOf(v));
        // An empty body, or one that leaves a field out, is refused and changes nothing.
        ResponseEntity<JsonNode> empty = api.call(HttpMethod.PUT, path, Map.of(), MANAGER, "*", match);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        List<String> fields = new ArrayList<>();
        empty.getBody().get("errors").forEach(e -> fields.add(e.get("field").asString()));
        assertThat(fields).containsExactlyInAnyOrder("credit_limit_minor", "terms_days");
        assertThat(api.call(HttpMethod.PUT, path, Map.of("credit_limit_minor", 1), MANAGER, "*", match)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> termsOnly =
                api.call(HttpMethod.PUT, path, Map.of("terms_days", 1), MANAGER, "*", match);
        assertThat(termsOnly.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Issue #257: each refusal is in a shop owner's words, never an API field name.
        assertThat(messages(termsOnly))
                .containsExactly(Map.entry(
                        "credit_limit_minor",
                        "Also say how much this buyer may owe, or that they have no credit limit."));
        assertThat(messages(empty))
                .containsOnly(
                        Map.entry(
                                "credit_limit_minor",
                                "Also say how much this buyer may owe, or that they have no credit limit."),
                        Map.entry(
                                "terms_days",
                                "Also say how many days this buyer has to pay, or that they have no payment terms."));
        // A value and its clear flag together contradict each other.
        ResponseEntity<JsonNode> both = api.call(
                HttpMethod.PUT,
                path,
                Map.of("credit_limit_minor", 1, "clear_credit_limit", true, "terms_days", 1, "clear_terms_days", true),
                MANAGER,
                "*",
                match);
        assertThat(both.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(messages(both))
                .containsOnly(
                        Map.entry("credit_limit_minor", "Either set a credit limit or say there is none, not both."),
                        Map.entry("terms_days", "Either set payment terms or say there are none, not both."));
        assertThat(version(c)).isEqualTo(v);
        JsonNode kept = api.get(path, ADMIN).getBody();
        assertThat(kept.get("credit_limit_minor").asLong()).isEqualTo(7_000);
        assertThat(kept.get("terms_days").asInt()).isEqualTo(14);
        // One cleared on purpose and one given: the limit goes, the terms change.
        ResponseEntity<JsonNode> changed = api.call(
                HttpMethod.PUT, path, Map.of("clear_credit_limit", true, "terms_days", 21), MANAGER, "*", match);
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().has("credit_limit_minor")).isFalse();
        assertThat(changed.getBody().get("terms_days").asInt()).isEqualTo(21);
    }

    @Test
    void aScopedCallerSeesTheReceiptsAndAllocationsOfTheirBranchSalesAndTheStatementAgrees() {
        UUID c = buyer("Test Buyer 31", null, null);
        UUID atHq = sold(hq, c, "10", "2026-01-05");
        UUID atBr2 = sold(br2, c, "8", "2026-02-05");
        // 1,500 received at head office pays all of the head office sale and 500 of the other branch's.
        JsonNode mixed = pay(
                        c,
                        hq,
                        1_500,
                        "cash",
                        null,
                        "2026-03-01",
                        MANAGER,
                        "*",
                        UUID.randomUUID().toString())
                .getBody();
        // 300 received at head office pays the other branch's sale only.
        JsonNode elsewhere = pay(
                        c,
                        hq,
                        300,
                        "cash",
                        null,
                        "2026-03-02",
                        MANAGER,
                        "*",
                        UUID.randomUUID().toString())
                .getBody();
        assertThat(elsewhere.get("allocations").get(0).get("sale_id").asString())
                .isEqualTo(atBr2.toString());

        // Full scope: both receipts at their full amounts with every allocation.
        JsonNode full =
                api.get("/customers/" + c + "/payments", ADMIN).getBody().get("items");
        assertThat(full).hasSize(2);
        assertThat(full.get(1).get("amount_minor").asLong()).isEqualTo(1_500);
        assertThat(full.get(1).get("allocations")).hasSize(2);

        // Head office only: the mixed receipt shows the 1,000 it paid there and the one allocation; the receipt that
        // paid
        // only the other branch is not listed, though head office banked it.
        JsonNode hqList = api.call(
                        HttpMethod.GET, "/customers/" + c + "/payments", null, ADMIN, hq.toString(), Map.of())
                .getBody()
                .get("items");
        assertThat(hqList).hasSize(1);
        assertThat(hqList.get(0).get("receipt_no").asString())
                .isEqualTo(mixed.get("receipt_no").asString());
        assertThat(hqList.get(0).get("amount_minor").asLong()).isEqualTo(1_000);
        assertThat(hqList.get(0).get("allocations")).hasSize(1);
        assertThat(hqList.get(0).get("allocations").get(0).get("sale_id").asString())
                .isEqualTo(atHq.toString());

        // The other branch: 500 of the mixed receipt and the whole of the second, the amounts the allocations add up
        // to.
        JsonNode br2List = api.call(
                        HttpMethod.GET, "/customers/" + c + "/payments", null, ADMIN, br2.toString(), Map.of())
                .getBody()
                .get("items");
        assertThat(br2List).hasSize(2);
        for (JsonNode r : br2List) {
            long allocated = 0;
            for (JsonNode a : r.get("allocations")) {
                allocated += a.get("amount_minor").asLong();
                assertThat(a.get("branch_id").asString()).isEqualTo(br2.toString());
            }
            assertThat(r.get("amount_minor").asLong()).isEqualTo(allocated);
        }
        assertThat(br2List.get(0).get("amount_minor").asLong()).isEqualTo(300);
        assertThat(br2List.get(1).get("amount_minor").asLong()).isEqualTo(500);

        // The statement of each scope carries a line for the same receipts at the same amounts.
        for (UUID scope : List.of(hq, br2)) {
            JsonNode listed = api.call(
                            HttpMethod.GET, "/customers/" + c + "/payments", null, ADMIN, scope.toString(), Map.of())
                    .getBody()
                    .get("items");
            Map<String, Long> fromList = new LinkedHashMap<>();
            listed.forEach(r -> fromList.put(
                    r.get("receipt_no").asString(), r.get("amount_minor").asLong()));
            Map<String, Long> fromStatement = new LinkedHashMap<>();
            statement(c, "2026-01-01", TODAY, ADMIN, scope.toString())
                    .get("lines")
                    .forEach(l -> {
                        if (l.get("kind").asString().equals("account_payment")) {
                            fromStatement.put(
                                    l.get("reference").asString(),
                                    l.get("credit_minor").asLong());
                        }
                    });
            assertThat(fromStatement).as("scope %s", scope).isEqualTo(fromList);
        }
    }

    @Test
    void paymentsReceivedInCreditControlFollowTheReceivingBranchLikeTheLedgerAndTheCashBook() {
        UUID c = buyer("Test Buyer 32", null, null);
        UUID s1 = sold(hq, c, "10", "2026-01-05");
        // A direct payment on the sale counts in the sale's branch; a payment on account counts where it was received.
        assertThat(api.postKeyed(
                                "/sales/" + s1 + "/payments",
                                Map.of("amount_minor", 250, "method", "cash", "paid_on", "2026-01-10"),
                                MANAGER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(pay(
                                c,
                                br2,
                                400,
                                "cash",
                                null,
                                "2026-01-10",
                                MANAGER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String path = "/reports/credit-control?from=2026-01-01&to=2026-01-31";
        assertThat(api.get(path, ADMIN).getBody().get("payments_minor").asLong())
                .isEqualTo(650);
        assertThat(api.call(HttpMethod.GET, path, null, ADMIN, br2.toString(), Map.of())
                        .getBody()
                        .get("payments_minor")
                        .asLong())
                .isEqualTo(400);
        assertThat(api.call(HttpMethod.GET, path, null, ADMIN, hq.toString(), Map.of())
                        .getBody()
                        .get("payments_minor")
                        .asLong())
                .isEqualTo(250);
        // The cash book of the receiving branch holds the same 400.
        JsonNode days = api.get(
                        "/reports/cash/daily?branch_id=" + br2 + "&from=2026-01-10&to=2026-01-10",
                        ADMIN + ",retail.cashbook.read")
                .getBody();
        assertThat(days.get("items").get(0).get("cash_takings_minor").asLong()).isEqualTo(400);
    }

    @Test
    void aPaymentOnAccountClearsTheOldestSaleDateFirstWhateverTheDueDatesAre() {
        UUID c = buyer("Test Buyer 33", null, null);
        // Sale dates run A, B, C; due dates run the other way round. Today is 2026-10-08.
        UUID a = UUID.fromString(creditSale(hq, c, "1", "2026-01-05", "2026-11-15", null, MANAGER)
                .getBody()
                .get("id")
                .asString());
        UUID b = UUID.fromString(creditSale(hq, c, "1", "2026-02-05", "2026-03-01", null, MANAGER)
                .getBody()
                .get("id")
                .asString());
        UUID d = UUID.fromString(creditSale(hq, c, "1", "2026-03-05", "2026-07-20", null, MANAGER)
                .getBody()
                .get("id")
                .asString());
        JsonNode before = api.get("/reports/customer-ageing", ADMIN)
                .getBody()
                .get("customers")
                .get(0)
                .get("ageing");
        assertThat(before.get("not_due_minor").asLong()).isEqualTo(100);
        assertThat(before.get("days61_to90_minor").asLong()).isEqualTo(100);
        assertThat(before.get("over90_minor").asLong()).isEqualTo(100);
        // 150 clears A (the oldest sale, though due last) and takes 50 of B; C is untouched.
        JsonNode receipt = pay(c, hq, 150).getBody();
        JsonNode parts = receipt.get("allocations");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).get("sale_id").asString()).isEqualTo(a.toString());
        assertThat(parts.get(0).get("amount_minor").asLong()).isEqualTo(100);
        assertThat(parts.get(1).get("sale_id").asString()).isEqualTo(b.toString());
        assertThat(parts.get(1).get("amount_minor").asLong()).isEqualTo(50);
        assertThat(sale(d).get("paid_minor").asLong()).isZero();
        JsonNode after = api.get("/reports/customer-ageing", ADMIN)
                .getBody()
                .get("customers")
                .get(0)
                .get("ageing");
        assertThat(after.get("owed_minor").asLong()).isEqualTo(150);
        assertThat(after.get("not_due_minor").asLong()).isZero();
        assertThat(after.get("days61_to90_minor").asLong()).isEqualTo(100);
        assertThat(after.get("over90_minor").asLong()).isEqualTo(50);
    }
}
