package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The retail approvals the approval rules add (issue #343, ADR-091): sale voids, stock corrections and expenses. Each is off by
 * default and then behaves exactly as before; switched on, from its amount it answers 202, changes nothing, and waits for an
 * administrator, who is never the maker and never a seller; approved, it runs in the maker's name. Fabricated data.
 */
class RetailApprovalRulesIT extends IntegrationTest {

    /** The administrator who decides: the settings permission and the approvals inbox, nothing of the shop. */
    static final String CHECKER = "core.settings.manage,core.approvals.read";

    static final String MAKER = CashbookTestSupport.ALL + ",core.approvals.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    CashbookTestSupport cb;
    UUID hq;
    /** Cost 1,000, price 1,500. */
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-rules", false, true);
        api = new RetailTestSupport(http, t);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("AR-1", 1_000, 1_500);
        api.stockUp(hq, product, "20");
    }

    // ---- helpers ---------------------------------------------------------------------------

    void rule(String action, boolean on, long amount) {
        // A business that never saved a setting has no row yet.
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("""
                        UPDATE tenant_settings SET settings = settings
                               || jsonb_build_object('approval_switches',
                                      coalesce(settings->'approval_switches', '{}'::jsonb) || jsonb_build_object(?::text, ?))
                               || jsonb_build_object('approval_thresholds_minor',
                                      coalesce(settings->'approval_thresholds_minor', '{}'::jsonb) || jsonb_build_object(?::text, ?))
                         WHERE tenant_id = ?
                        """)
                .params(action, on, action, amount, t.tenantId())
                .update();
    }

    ResponseEntity<JsonNode> approve(String approval, RetailTestSupport who, String perms) {
        return http.exchange(
                "/api/v1/approvals/" + approval + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Checked"), who.headers(perms, "*", Map.of())),
                JsonNode.class);
    }

    ResponseEntity<JsonNode> approval(String id, RetailTestSupport who, String perms) {
        return http.exchange(
                "/api/v1/approvals/" + id,
                HttpMethod.GET,
                new HttpEntity<>(who.headers(perms, "*", Map.of())),
                JsonNode.class);
    }

    UUID sale(String qty) {
        ResponseEntity<JsonNode> r = api.sell(hq, "cash", product, qty);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> voidSale(UUID sale) {
        return api.post("/sales/" + sale + "/void", Map.of("reason", "Rang up twice"), MAKER);
    }

    String status(UUID sale) {
        return api.get("/sales/" + sale, ADMIN).getBody().get("status").asString();
    }

    BigDecimal stock() {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(hq, product)
                .query(BigDecimal.class)
                .single();
    }

    ResponseEntity<JsonNode> correct(String newQty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("product_id", product);
        body.put("new_qty", newQty);
        body.put("expected_qty", stock().toPlainString());
        body.put("reason_code", "miscount");
        return api.postKeyed(
                "/stock/corrections", body, MAKER, "*", UUID.randomUUID().toString());
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    // ---- defaults ----------------------------------------------------------------------------

    @Test
    void offByDefaultEveryActionRunsAtOnceAsBefore() {
        assertThat(voidSale(sale("1")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(correct("15").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID[] ci = cb.categoryWithItem("Transport", "Boda fare", false);
        assertThat(cb.expense(hq, ci[0], ci[1], 900_000, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("SELECT count(*) FROM approval_requests WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    // ---- sale voids ----------------------------------------------------------------------------

    @Test
    void switchedOnALargeVoidWaitsForAnAdministratorAndRunsInTheMakersName() {
        rule("retail_sale_void", true, 3_000);
        UUID small = sale("1");
        assertThat(voidSale(small).getStatusCode()).as("below the amount").isEqualTo(HttpStatus.OK);
        assertThat(status(small)).isEqualTo("voided");

        UUID big = sale("2");
        BigDecimal before = stock();
        ResponseEntity<JsonNode> asked = voidSale(big);
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(asked.getBody().get("action_type").asString()).isEqualTo("retail_sale_void");
        assertThat(asked.getBody().get("amount_minor").asLong()).isEqualTo(3_000);
        String id = asked.getBody().get("approval_id").asString();
        // Nothing changed while it waits.
        assertThat(status(big)).isEqualTo("completed");
        assertThat(stock()).isEqualByComparingTo(before);
        // Asking twice for the same sale is refused while one waits.
        assertThat(voidSale(big).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The maker cannot decide it, a seller who may void cannot either; an administrator can.
        assertThat(approve(id, api, MAKER + "," + CHECKER).getStatusCode().is2xxSuccessful())
                .isFalse();
        RetailTestSupport seller = new RetailTestSupport(http, t);
        assertThat(approve(id, seller, MAKER).getStatusCode().value()).isIn(403, 404);
        assertThat(status(big)).isEqualTo("completed");
        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> done = approve(id, checker, CHECKER);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode voided = api.get("/sales/" + big, ADMIN).getBody();
        assertThat(voided.get("status").asString()).isEqualTo("voided");
        assertThat(voided.get("voided_by").asString()).isEqualTo(api.userId().toString());
        assertThat(voided.get("void_reason").asString()).isEqualTo("Rang up twice");
        assertThat(stock()).isEqualByComparingTo(before.add(new BigDecimal("2")));
    }

    @Test
    void aWaitingVoidGoesStaleWhenTheSaleChangedMeanwhile() {
        rule("retail_sale_void", true, 0);
        ResponseEntity<JsonNode> credit = api.sell(hq, "credit", product, "1");
        assertThat(credit.getStatusCode()).as("%s", credit.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID s = RetailTestSupport.id(credit);
        String id = voidSale(s).getBody().get("approval_id").asString();
        // The buyer pays part meanwhile: the sale's version moves.
        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/sales/" + s + "/payments",
                Map.of("amount_minor", 500, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode().is2xxSuccessful())
                .as("%s", paid.getBody())
                .isTrue();
        ResponseEntity<JsonNode> late = approve(id, new RetailTestSupport(http, t), CHECKER);
        assertThat(late.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(late.getBody().get("code").asString()).isEqualTo("subject_changed");
        assertThat(status(s)).isEqualTo("completed");
    }

    // ---- stock corrections ------------------------------------------------------------------------

    @Test
    void switchedOnACorrectionWorthTheAmountWaitsAndRunsOnlyOnTheQuantityTheMakerSaw() {
        // Two pieces at the average cost of 1,000 is 2,000.
        rule("retail_stock_correction", true, 2_000);
        assertThat(correct("19").getStatusCode())
                .as("1,000 is below the amount")
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> asked = correct("17");
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(asked.getBody().get("amount_minor").asLong()).isEqualTo(2_000);
        assertThat(stock()).isEqualByComparingTo("19");
        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> done =
                approve(asked.getBody().get("approval_id").asString(), checker, CHECKER);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stock()).isEqualByComparingTo("17");
        assertThat(count(
                        "SELECT count(*) FROM retail_stock_corrections WHERE tenant_id = ? AND created_by = ? AND new_qty = 17",
                        t.tenantId(),
                        api.userId()))
                .isEqualTo(1);

        // Waiting again; a sale moves the shelf meanwhile, so the approval finds the request stale and nothing is
        // corrected.
        ResponseEntity<JsonNode> again = correct("10");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        sale("1");
        String id = again.getBody().get("approval_id").asString();
        ResponseEntity<JsonNode> late = approve(id, checker, CHECKER);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("code").asString()).isEqualTo("stock_moved");
        assertThat(approval(id, api, MAKER).getBody().get("status").asString()).isEqualTo("stale");
        assertThat(stock()).isEqualByComparingTo("16");
    }

    // ---- expenses ----------------------------------------------------------------------------

    @Test
    void switchedOnALargeExpenseIsNotInTheCashBookUntilAnAdministratorApprovesIt() {
        rule("retail_expense", true, 50_000);
        UUID[] ci = cb.categoryWithItem("Repairs", "Door lock", false);
        assertThat(cb.expense(hq, ci[0], ci[1], 49_999, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> asked = cb.expense(hq, ci[0], ci[1], 50_000, null);
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(asked.getBody().get("action_type").asString()).isEqualTo("retail_expense");
        assertThat(count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        // A cashier who records expenses cannot approve one.
        RetailTestSupport cashier = new RetailTestSupport(http, t);
        String id = asked.getBody().get("approval_id").asString();
        assertThat(approve(id, cashier, CashbookTestSupport.CASHIER + ",core.approvals.read")
                        .getStatusCode()
                        .value())
                .isIn(403, 404);
        ResponseEntity<JsonNode> done = approve(id, new RetailTestSupport(http, t), CHECKER);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(count(
                        "SELECT count(*) FROM retail_expenses WHERE tenant_id = ? AND amount_minor = 50000 AND recorded_by = ?"
                                + " AND id = ?",
                        t.tenantId(),
                        cb.api.userId(),
                        UUID.fromString(done.getBody().get("subject_id").asString())))
                .isEqualTo(1);
    }

    /**
     * Review of #588: what was asked can no longer be recorded when it is approved (here its category was switched off meanwhile).
     * The request goes stale with the reason, rather than failing on every approval until it expires.
     */
    @Test
    void anExpenseThatNoLongerPassesWhenApprovedGoesStaleWithTheReason() {
        rule("retail_expense", true, 0);
        UUID[] ci = cb.categoryWithItem("Repairs", "Door lock", false);
        ResponseEntity<JsonNode> asked = cb.expense(hq, ci[0], ci[1], 20_000, null);
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String id = asked.getBody().get("approval_id").asString();
        TestDatabase.owner()
                .sql("UPDATE retail_expense_categories SET active = false WHERE id = ?")
                .param(ci[0])
                .update();
        ResponseEntity<JsonNode> late = approve(id, new RetailTestSupport(http, t), CHECKER);
        assertThat(late.getStatusCode()).as("%s", late.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("code").asString()).isEqualTo("category_inactive");
        assertThat(approval(id, cb.api, MAKER).getBody().get("status").asString())
                .isEqualTo("stale");
        assertThat(count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    /**
     * Review of #588, ADR-091 decisions 6 and 11: the void of a return follows the returns rule, so a refusal on approval makes it
     * stale with the reason (here the goods it put back were sold meanwhile), rather than failing on every approval.
     */
    @Test
    void aReturnVoidThatNoLongerPassesWhenApprovedGoesStaleWithTheReason() {
        String settings = "core.settings.manage";
        String etag = api.get("/settings/capabilities", settings).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("returns", true),
                                settings,
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String returner = MAKER + ",retail.sale.return";
        UUID s = sale("1");
        String line = api.get("/sales/" + s, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString();
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("sale_line_id", line);
        l.put("qty", "1");
        l.put("reason", "defective");
        l.put("disposition", "restock");
        ResponseEntity<JsonNode> back = api.postKeyed(
                "/sales/" + s + "/returns",
                Map.of("lines", java.util.List.of(l), "refund_method", "cash"),
                returner,
                "*",
                UUID.randomUUID().toString());
        assertThat(back.getStatusCode()).as("%s", back.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = back.getBody().get("sale_return").get("id").asString();
        rule("retail_sale_return", true, 0);
        ResponseEntity<JsonNode> asked = api.postKeyed(
                "/returns/" + id + "/void",
                Map.of("reason", "Entered twice"),
                returner,
                "*",
                UUID.randomUUID().toString());
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String approvalId = asked.getBody().get("approval_id").asString();
        // Every piece on the shelf is sold, the one the return put back included.
        sale(stock().toPlainString());
        ResponseEntity<JsonNode> late =
                approve(approvalId, new RetailTestSupport(http, t), CHECKER + ",retail.sale.return");
        assertThat(late.getStatusCode()).as("%s", late.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("code").asString()).isEqualTo("return_stock_moved");
        assertThat(approval(approvalId, api, returner).getBody().get("status").asString())
                .isEqualTo("stale");
        assertThat(api.get("/returns/" + id, returner).getBody().get("status").asString())
                .isEqualTo("completed");
    }

    // ---- answers stored before ADR-091 still replay ----------------------------------------------

    /** Rewrites the stored answer of a key into the shape it had before ADR-091: the bare record, not {record, waiting}. */
    void storedTheOldWay(String key, String field) {
        int rows = TestDatabase.owner()
                .sql("UPDATE idempotency_keys SET response_body = response_body -> ? WHERE tenant_id = ? AND key = ?")
                .params(field, t.tenantId(), key)
                .update();
        assertThat(rows).isEqualTo(1);
    }

    /** Review of #588: an expense recorded before the deploy, whose answer was lost, replays after it as the same expense. */
    @Test
    void anExpenseAnswerStoredBeforeTheApprovalRulesStillReplays() {
        UUID[] ci = cb.categoryWithItem("Transport", "Boda fare", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", ci[0]);
        body.put("item_id", ci[1]);
        body.put("amount_minor", 5_000);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = api.postKeyed("/expenses", body, CashbookTestSupport.ALL, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        storedTheOldWay(key, "expense");
        ResponseEntity<JsonNode> replay = api.postKeyed("/expenses", body, CashbookTestSupport.ALL, "*", key);
        assertThat(replay.getStatusCode()).as("%s", replay.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asString())
                .isEqualTo(made.getBody().get("id").asString());
        assertThat(count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
    }

    /** The same for a stock correction. */
    @Test
    void aCorrectionAnswerStoredBeforeTheApprovalRulesStillReplays() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("product_id", product);
        body.put("new_qty", "18");
        body.put("expected_qty", stock().toPlainString());
        body.put("reason_code", "miscount");
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = api.postKeyed("/stock/corrections", body, MAKER, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        storedTheOldWay(key, "correction");
        ResponseEntity<JsonNode> replay = api.postKeyed("/stock/corrections", body, MAKER, "*", key);
        assertThat(replay.getStatusCode()).as("%s", replay.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asString())
                .isEqualTo(made.getBody().get("id").asString());
        assertThat(stock()).isEqualByComparingTo("18");
    }

    @Test
    void switchedOffAgainAnAmountSetBeforeAsksNothing() {
        rule("retail_expense", false, 1);
        rule("retail_sale_void", false, 1);
        rule("retail_stock_correction", false, 1);
        UUID[] ci = cb.categoryWithItem("Transport", "Boda fare", false);
        assertThat(cb.expense(hq, ci[0], ci[1], 5_000, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(voidSale(sale("1")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(correct("3").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
