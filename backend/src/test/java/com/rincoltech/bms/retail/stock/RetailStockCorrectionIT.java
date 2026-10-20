package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Correcting the quantity on the stock list (issue #351, ADR-044): the administrator types the right quantity and a reason, the
 * app posts one adjustment that is flagged as a correction, shows who, when, why and the old and new value on the product's stock
 * history, keeps the books equal to the shelf, and is not shrinkage or usage in any report. Fabricated data.
 */
class RetailStockCorrectionIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    /** Cost 1,000. */
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-correct", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("SC-1", 1_000, 1_500);
    }

    // ---- helpers ---------------------------------------------------------------------------

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    UUID restock(UUID branch, String qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 1_000);
        line.put("qty_by_branch", List.of(Map.of("branch_id", branch, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-10-01");
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r = api.postKeyed("/purchases", body, ADMIN, "*", key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(r.getBody().get("id").asString());
    }

    Map<String, Object> request(UUID branch, String newQty, String reason, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("product_id", product);
        body.put("new_qty", newQty);
        // What the form showed: the shelf as it stands, unless a test says otherwise.
        body.put("expected_qty", stockAt(branch).toPlainString());
        body.put("reason_code", reason);
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    ResponseEntity<JsonNode> correct(Map<String, Object> body, String perms, String scope, String key) {
        return api.call(
                org.springframework.http.HttpMethod.POST,
                "/stock/corrections",
                body,
                perms,
                scope,
                key == null ? Map.of() : Map.of("Idempotency-Key", key));
    }

    ResponseEntity<JsonNode> correct(UUID branch, String newQty, String reason, String note) {
        return correct(request(branch, newQty, reason, note), ADMIN, "*", key());
    }

    BigDecimal stockAt(UUID branch) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branch, product)
                .query(BigDecimal.class)
                .single();
    }

    long scalar(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    long account(String systemKey) {
        return scalar("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = ?
                        """, t.tenantId(), systemKey);
    }

    // ---- the correction ----------------------------------------------------------------------

    @Test
    void theAdministratorTypesTheRightQuantityAndTheOriginalRestockIsUntouched() {
        UUID restock = restock(hq, "10");
        String k = key();
        Map<String, Object> sent = request(hq, "6", "entry_mistake", "Typed ten, really six");
        ResponseEntity<JsonNode> done = correct(sent, ADMIN, "*", k);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode c = done.getBody();
        assertThat(c.get("old_qty").asString()).isEqualTo("10.000");
        assertThat(c.get("new_qty").asString()).isEqualTo("6.000");
        assertThat(c.get("reason_code").asString()).isEqualTo("entry_mistake");
        assertThat(c.get("note").asString()).isEqualTo("Typed ten, really six");
        assertThat(c.get("by").asString()).isEqualTo(api.userId().toString());
        assertThat(c.get("code").asString()).isEqualTo("SC-1");
        assertThat(stockAt(hq)).isEqualByComparingTo("6");

        // One adjustment for the difference, from its own source: not a stock-take, not usage.
        assertThat(scalar("""
                                SELECT count(*) FROM retail_stock_movements
                                 WHERE tenant_id = ? AND kind = 'adjustment' AND source_type = 'retail.stock_correction'
                                   AND qty = -4 AND unit_cost_minor = 1000 AND note = 'Entry mistake: Typed ten, really six'
                                """, t.tenantId())).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM retail_stock_corrections WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        // Nothing deleted or edited: the restock and its movement are there as they were.
        assertThat(scalar(
                        "SELECT count(*) FROM retail_purchases WHERE tenant_id = ? AND id = ?", t.tenantId(), restock))
                .isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND source_id = ? AND kind = 'purchase' AND qty = 10",
                        t.tenantId(),
                        restock))
                .isEqualTo(1);
        // The books follow the shelf: inventory was 10,000 and is 6,000.
        assertThat(account("inventory")).isEqualTo(6_000);
        assertThat(scalar("""
                                SELECT count(*) FROM journal_entries
                                 WHERE tenant_id = ? AND source_type = 'retail.stock_correction' AND memo = 'Stock correction'
                                """, t.tenantId())).isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.stock.corrected' AND entity_id = ?",
                        t.tenantId(),
                        UUID.fromString(c.get("id").asString())))
                .isEqualTo(1);

        // It shows on the product's stock history with who, when, why and the old and new quantity.
        JsonNode history = api.get("/stock/movements?product_id=" + product + "&branch_id=" + hq, ADMIN)
                .getBody()
                .get("items");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.get("kind").asString()).isEqualTo("adjustment");
        assertThat(last.get("source_type").asString()).isEqualTo("retail.stock_correction");
        assertThat(last.get("by").asString()).isEqualTo(api.userId().toString());
        assertThat(last.get("correction").get("reason_code").asString()).isEqualTo("entry_mistake");
        assertThat(last.get("correction").get("old_qty").asString()).isEqualTo("10.000");
        assertThat(last.get("correction").get("new_qty").asString()).isEqualTo("6.000");
        assertThat(history.get(0).has("correction")).isFalse();
        // Newest first, for the history on the stock page.
        JsonNode newest = api.get(
                        "/stock/movements?product_id=" + product + "&branch_id=" + hq + "&newest_first=true", ADMIN)
                .getBody()
                .get("items");
        assertThat(newest.get(0).get("source_type").asString()).isEqualTo("retail.stock_correction");
        assertThat(newest.get(newest.size() - 1).get("kind").asString()).isEqualTo("purchase");

        // The same key replays the answer and does not correct twice.
        ResponseEntity<JsonNode> replay = correct(sent, ADMIN, "*", k);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asString()).isEqualTo(c.get("id").asString());
        assertThat(stockAt(hq)).isEqualByComparingTo("6");
    }

    @Test
    void aMiscountMayRaiseTheQuantityAndTheBooksFollow() {
        restock(hq, "5");
        ResponseEntity<JsonNode> up = correct(hq, "7.5", "miscount", null);
        assertThat(up.getStatusCode()).as("%s", up.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq)).isEqualByComparingTo("7.5");
        assertThat(account("inventory")).isEqualTo(7_500);
        assertThat(up.getBody().get("reason_code").asString()).isEqualTo("miscount");
        // The quantity can go to none, and then the item has none.
        assertThat(correct(hq, "0", "entry_mistake", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq)).isEqualByComparingTo("0");
        assertThat(account("inventory")).isZero();
    }

    @Test
    void aCorrectionIsNeitherShrinkageNorUsageInTheReports() {
        // A stock-take finds one of another item missing: that is shrinkage, and the control for the figures below.
        UUID other = api.product("SC-2", 400, 600);
        api.stockUp(hq, other, "5");
        ResponseEntity<JsonNode> take = api.post(
                "/stocktakes",
                Map.of("branch_id", hq, "lines", List.of(Map.of("product_id", other, "counted_qty", "4"))),
                ADMIN);
        assertThat(api.post("/stocktakes/" + RetailTestSupport.id(take) + "/commit", Map.of(), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        restock(hq, "10");

        JsonNode shrinkageBefore = shrinkage();
        JsonNode profitBefore = api.get("/reports/profit/daily", ADMIN).getBody();
        assertThat(shrinkageBefore.get("stocktake_loss_minor").asLong()).isEqualTo(400);

        assertThat(correct(hq, "3", "entry_mistake", "Typed ten, really three").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(correct(hq, "12", "miscount", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode shrinkageAfter = shrinkage();
        assertThat(shrinkageAfter).isEqualTo(shrinkageBefore);
        assertThat(shrinkageAfter.get("short_lines").asInt()).isEqualTo(1);
        assertThat(shrinkageAfter.get("over_lines").asInt())
                .isEqualTo(shrinkageBefore.get("over_lines").asInt());
        assertThat(shrinkageAfter.get("used_reports").asInt()).isZero();
        assertThat(shrinkageAfter.get("damaged_reports").asInt()).isZero();
        assertThat(api.get("/reports/profit/daily", ADMIN).getBody()).isEqualTo(profitBefore);
    }

    JsonNode shrinkage() {
        for (JsonNode s : api.get("/reports/stock-health", ADMIN).getBody().get("shrinkage")) {
            if (s.get("branch_id").asString().equals(hq.toString())) {
                return s;
            }
        }
        throw new AssertionError("no shrinkage row for the head office");
    }

    // ---- refusals ----------------------------------------------------------------------------

    @Test
    void theSameQuantityANegativeOneAnUnknownReasonAndAMissingKeyAreRefused() {
        restock(hq, "4");
        ResponseEntity<JsonNode> same = correct(hq, "4", "miscount", null);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(same)).isEqualTo("quantity_unchanged");
        assertThat(correct(hq, "-1", "miscount", null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(correct(hq, "2", "because", null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(correct(hq, "2.0005", "miscount", null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> noKey = correct(request(hq, "2", "miscount", null), ADMIN, "*", null);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noKey)).isEqualTo("idempotency_key_missing");
        Map<String, Object> unknown = request(hq, "2", "miscount", null);
        unknown.put("product_id", UUID.randomUUID());
        assertThat(correct(unknown, ADMIN, "*", key()).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(stockAt(hq)).isEqualByComparingTo("4");
        assertThat(scalar("SELECT count(*) FROM retail_stock_corrections WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    @Test
    void thePermissionIsTheStocktakePermissionAtTheBranch() {
        restock(hq, "4");
        assertThat(correct(request(hq, "2", "miscount", null), SALES, "*", key())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Held at the second branch only: the head office is out of reach.
        ResponseEntity<JsonNode> other = correct(request(hq, "2", "miscount", null), ADMIN, br2.toString(), key());
        assertThat(other.getStatusCode().is4xxClientError()).isTrue();
        assertThat(other.getStatusCode()).isNotEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq)).isEqualByComparingTo("4");
        // Held at the head office, it works there.
        assertThat(correct(request(hq, "2", "miscount", null), ADMIN, hq.toString(), key())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anotherBusinessCannotCorrectOrSeeTheCorrection() {
        restock(hq, "4");
        ResponseEntity<JsonNode> done = correct(hq, "3", "miscount", null);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestDatabase.Fixture other = TestDatabase.tenant("retail-correct-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        ResponseEntity<JsonNode> blind = theirs.call(
                org.springframework.http.HttpMethod.POST,
                "/stock/corrections",
                request(other.headOffice(), "9", "miscount", null),
                ADMIN,
                "*",
                Map.of("Idempotency-Key", key()));
        assertThat(blind.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(theirs.get("/stock/movements?product_id=" + product, ADMIN)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(stockAt(hq)).isEqualByComparingTo("3");
    }

    @Test
    void aQuantityThatMovedSinceTheFormWasOpenedIsNeverOverwritten() {
        restock(hq, "10");
        // The form showed 10; a cashier sells 2 before it is saved.
        assertThat(api.sell(hq, "cash", product, "2").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> stale = request(hq, "6", "entry_mistake", null);
        stale.put("expected_qty", "10");
        ResponseEntity<JsonNode> refused = correct(stale, ADMIN, "*", key());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(refused)).isEqualTo("stock_moved");
        assertThat(refused.getBody().get("detail").asString())
                .contains("changed while you were typing")
                .contains("8");
        assertThat(stockAt(hq)).isEqualByComparingTo("8");
        assertThat(scalar("SELECT count(*) FROM retail_stock_corrections WHERE tenant_id = ?", t.tenantId()))
                .isZero();

        // Typed against the shelf as it is now, it goes through; a request without the expected quantity is refused.
        Map<String, Object> fresh = request(hq, "6", "entry_mistake", null);
        fresh.put("expected_qty", "8");
        assertThat(correct(fresh, ADMIN, "*", key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq)).isEqualByComparingTo("6");
        Map<String, Object> missing = request(hq, "5", "miscount", null);
        missing.remove("expected_qty");
        assertThat(correct(missing, ADMIN, "*", key()).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aStockTakeThatIsNotCommittedBlocksACorrectionOfAnItemItCounts() {
        restock(hq, "10");
        UUID take = RetailTestSupport.id(api.post(
                "/stocktakes",
                Map.of("branch_id", hq, "lines", List.of(Map.of("product_id", product, "counted_qty", "9"))),
                ADMIN));
        ResponseEntity<JsonNode> refused = correct(hq, "6", "entry_mistake", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(refused)).isEqualTo("stocktake_in_progress");
        assertThat(refused.getBody().get("detail").asString()).contains("Finish it first");
        assertThat(stockAt(hq)).isEqualByComparingTo("10");

        // Another branch is not covered by it, and once it is committed the correction is allowed again.
        assertThat(correct(br2, "1", "miscount", null).getStatusCode()).isNotEqualTo(HttpStatus.CONFLICT);
        assertThat(api.post("/stocktakes/" + take + "/commit", Map.of(), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(stockAt(hq)).isEqualByComparingTo("9");
        assertThat(correct(hq, "6", "entry_mistake", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq)).isEqualByComparingTo("6");
    }

    /** Marks a product inactive, as the Items screen does (issue #342). */
    void switchOff(UUID id) {
        String etag = api.get("/products/" + id, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + id,
                                Map.of("active", false),
                                ADMIN,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** ADR-045: a quantity correction works for an item switched off, so a forgotten balance can be put right. */
    @Test
    void aQuantityOfAnInactiveItemCanStillBeCorrected() {
        api.stockUp(t.headOffice(), product, "10");
        switchOff(product);
        ResponseEntity<JsonNode> r = correct(t.headOffice(), "8", "miscount", null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }
}
