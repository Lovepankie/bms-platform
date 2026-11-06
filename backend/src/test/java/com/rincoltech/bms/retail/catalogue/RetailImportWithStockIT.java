package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.KeyOwnership;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Add many items through Import items (issue #870, ADR-101 amendment of 6 Nov 2026, FR-RET-93): a {@code qty} column is how
 * many of each item there are now at the chosen shop, added as one opening movement per row through Quick add's own path, in
 * the import's transaction; {@code stock_only} adds the quantity to the item already in the list; an apply with stock runs
 * once per Idempotency-Key and answers only its maker; a comma used as a decimal point is refused; selling below buying is a
 * warning; a list kept on the phone offline is dated the day it was made. Every name, price and quantity is fabricated.
 */
class RetailImportWithStockIT extends IntegrationTest {

    static final ZoneId ZONE = BusinessClock.DEFAULT_ZONE;
    static final String HEADER = "description,category,unit,qty,cost_price,sell_price";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-imp-stock", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
    }

    static Map<String, Object> body(String csv, UUID branch) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("csv", csv);
        b.put("branch_id", branch);
        return b;
    }

    ResponseEntity<JsonNode> check(String csv, UUID branch) {
        return api.post("/products/import?dry_run=true", body(csv, branch), ADMIN);
    }

    ResponseEntity<JsonNode> apply(Map<String, Object> body, String perms, String key) {
        return api.postKeyed("/products/import?dry_run=false", body, perms, "*", key);
    }

    ResponseEntity<JsonNode> apply(String csv, UUID branch) {
        return apply(body(csv, branch), ADMIN, UUID.randomUUID().toString());
    }

    long scalar(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    long products() {
        return scalar("SELECT count(*) FROM retail_products WHERE tenant_id = ?", t.tenantId());
    }

    long openings() {
        return scalar(
                "SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'opening'"
                        + " AND source_type = 'retail.quick_add' AND note = 'Import items'",
                t.tenantId());
    }

    UUID productNamed(String name) {
        return TestDatabase.owner()
                .sql("SELECT id FROM retail_products WHERE tenant_id = ? AND description = ?")
                .params(t.tenantId(), name)
                .query(UUID.class)
                .single();
    }

    BigDecimal stockAt(UUID branch, UUID product) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branch, product)
                .query(BigDecimal.class)
                .single();
    }

    @Test
    void eachRowsQuantityIsOpeningStockAtTheChosenShopOnly() {
        String csv = HEADER
                + "\nTest Soda 300ml,Test Drinks,Piece,24,1000,1500\nTest Bar Soap,Test Cleaning,Piece,10,2000,2500"
                + "\nTest Candle,Test Cleaning,Piece,,300,500\n";
        ResponseEntity<JsonNode> checked = check(csv, br2);
        assertThat(checked.getStatusCode()).as("%s", checked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(checked.getBody().get("added").asInt()).isEqualTo(3);
        assertThat(checked.getBody().get("rows").get(0).get("qty").asString()).isEqualTo("24");
        // A check saves nothing.
        assertThat(products()).isZero();

        ResponseEntity<JsonNode> r = apply(csv, br2);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("branch_id").asString()).isEqualTo(br2.toString());
        assertThat(stockAt(br2, productNamed("Test Soda 300ml"))).isEqualByComparingTo("24");
        assertThat(stockAt(br2, productNamed("Test Bar Soap"))).isEqualByComparingTo("10");
        assertThat(stockAt(hq, productNamed("Test Soda 300ml"))).isEqualByComparingTo("0");
        assertThat(stockAt(br2, productNamed("Test Candle"))).isEqualByComparingTo("0");
        // One opening entry per row with a quantity, through Quick add's opening stock.
        assertThat(openings()).isEqualTo(2);
    }

    @Test
    void anApplyWithStockNeedsItsKeyAndTheSameKeyAddsNothingTwice() {
        String csv = HEADER + "\nTest Replay Salt,Test Food,Piece,12,300,500\n";
        ResponseEntity<JsonNode> noKey = api.post("/products/import?dry_run=false", body(csv, hq), ADMIN);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noKey.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");
        assertThat(products()).isZero();

        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = apply(body(csv, hq), ADMIN, key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> again = apply(body(csv, hq), ADMIN, key);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(products()).isEqualTo(1);
        assertThat(openings()).isEqualTo(1);
        assertThat(stockAt(hq, productNamed("Test Replay Salt"))).isEqualByComparingTo("12");
    }

    @Test
    void anotherPersonsKeyIsRefusedAndNothingIsAdded() {
        RetailTestSupport someoneElse = new RetailTestSupport(http, t);
        String key = UUID.randomUUID().toString();
        Map<String, Object> list = body(HEADER + "\nTest Key Torch,Test Lights,Piece,5,2000,3000\n", hq);
        ResponseEntity<JsonNode> made = apply(list, ADMIN, key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.OK);

        KeyOwnership.refused(
                someoneElse.postKeyed("/products/import?dry_run=false", list, ADMIN, "*", key), made, list);
        assertThat(products()).isEqualTo(1);
        assertThat(openings()).isEqualTo(1);
        assertThat(KeyOwnership.holders(t.tenantId(), key)).containsExactly(api.userId());
    }

    @Test
    void aNameAlreadyInTheListIsSkippedWithItsCodeOrTakesTheQuantityWithStockOnly() {
        apply(HEADER + "\nTest Rice 1kg,Test Food,Piece,5,4000,5000\n", hq);
        UUID rice = productNamed("Test Rice 1kg");

        ResponseEntity<JsonNode> skipped = check(HEADER + "\ntest rice 1KG,Test Food,Piece,8,4000,5000\n", hq);
        JsonNode row = skipped.getBody().get("rows").get(0);
        assertThat(row.get("outcome").asString()).isEqualTo("skipped");
        assertThat(row.get("existing_code").asString()).isNotBlank();
        assertThat(row.get("message").asString()).contains("Add to its stock");

        String stockOnly =
                "description,category,unit,qty,sell_price,stock_only\nTest Rice 1kg,Test Food,Piece,8,,yes\n";
        ResponseEntity<JsonNode> r = apply(stockOnly, hq);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("stock_added").asInt()).isEqualTo(1);
        assertThat(r.getBody().get("rows").get(0).get("outcome").asString()).isEqualTo("stock");
        // Exactly the quantity typed is added, 5 becomes 13; no second item.
        assertThat(stockAt(hq, rice)).isEqualByComparingTo("13");
        assertThat(products()).isEqualTo(1);
    }

    // JUSTIFICATION-A3: the review of #871 asks a test of the name race and of the import's trace, one each.
    @Test
    void aNameAddedBetweenTheCheckAndTheApplyComesBackWithItsQuantityAndCode() {
        // The Lead on the review of #871: the check passes Test Soap with 24; another phone then adds Test Soap.
        // The apply adds the other rows and answers the Soap row skipped, naming the item and the 24, so the screen
        // (or the outbox, with Fix) shows it with Add to its stock; its stock is never dropped unseen.
        String list =
                HEADER + "\nTest Soap,Test Cleaning,Piece,24,2000,2500\nTest Brush,Test Cleaning,Piece,3,500,800\n";
        assertThat(check(list, hq).getBody().get("added").asInt()).isEqualTo(2);
        apply(HEADER + "\nTest Soap,Test Cleaning,Piece,5,2000,2500\n", hq);
        UUID soap = productNamed("Test Soap");

        ResponseEntity<JsonNode> r = apply(list, hq);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode skipped = r.getBody().get("rows").get(0);
        assertThat(skipped.get("outcome").asString()).isEqualTo("skipped");
        assertThat(skipped.get("qty").asString()).isEqualTo("24");
        assertThat(skipped.get("existing_code").asString()).isNotBlank();
        assertThat(r.getBody().get("rows").get(1).get("outcome").asString()).isEqualTo("added");
        assertThat(stockAt(hq, soap)).isEqualByComparingTo("5");
        assertThat(stockAt(hq, productNamed("Test Brush"))).isEqualByComparingTo("3");

        // Add to its stock: one row of stock_only posts the 24 to the item already in the list.
        String addTo = "description,category,unit,qty,sell_price,stock_only\nTest Soap,Test Cleaning,Piece,24,,yes\n";
        assertThat(apply(addTo, hq).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stockAt(hq, soap)).isEqualByComparingTo("29");
    }

    @Test
    void anImportsStockIsTracedToItByItsMovementsAndEntries() {
        // Review of #871: every opening movement and journal entry of one apply carries the same source id.
        apply(HEADER + "\nTest Trace Cup,Test Home,Piece,4,100,200\nTest Trace Plate,Test Home,Piece,6,150,300\n", hq);
        assertThat(scalar(
                        "SELECT count(DISTINCT source_id) FROM retail_stock_movements WHERE tenant_id = ?"
                                + " AND note = 'Import items'",
                        t.tenantId()))
                .isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(DISTINCT source_id) FROM journal_entries WHERE tenant_id = ?"
                                + " AND source_type = 'retail.quick_add'",
                        t.tenantId()))
                .isEqualTo(1);
    }

    @Test
    void aCommaUsedAsADecimalPointIsRefusedAndThousandsCommasAreRead() {
        ResponseEntity<JsonNode> r = check(
                HEADER + "\nTest Rope,Test Hardware,Piece,\"2,5\",100,300\nTest Wire,Test Hardware,Piece,1,100,\"1,5\""
                        + "\nTest Pipe,Test Hardware,Piece,\"1,200\",\"9,000\",\"12,500\"\n",
                hq);
        JsonNode rows = r.getBody().get("rows");
        assertThat(rows.get(0).get("outcome").asString()).isEqualTo("error");
        assertThat(rows.get(0).get("message").asString()).contains("Use a point for decimals");
        assertThat(rows.get(1).get("outcome").asString()).isEqualTo("error");
        assertThat(rows.get(1).get("message").asString()).contains("Use a point for decimals");
        assertThat(rows.get(2).get("outcome").asString()).isEqualTo("added");
        assertThat(rows.get(2).get("qty").asString()).isEqualTo("1200");
        // A file with a wrong row adds nothing, its good rows included.
        String mixed =
                HEADER + "\nTest Rope,Test Hardware,Piece,\"2,5\",100,300\nTest Nail,Test Hardware,Piece,4,10,20\n";
        assertThat(apply(mixed, hq).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(products()).isZero();
    }

    @Test
    void sellingBelowBuyingIsAWarningAndTheBuyingPriceIsOptional() {
        ResponseEntity<JsonNode> r =
                apply(HEADER + "\nTest Torch,Test Lights,Piece,1,5000,4000\nTest Lamp,Test Lights,Piece,2,,3000\n", hq);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("rows").get(0).get("message").asString())
                .contains("The selling price is below the buying price.");
        assertThat(products()).isEqualTo(2);
    }

    @Test
    void theStockNeedsTheCountPermissionAtTheShopAndNothingIsMadeWithoutIt() {
        String csv = HEADER + "\nTest No Count Lamp,Test Lights,Piece,3,1000,2000\n";
        String noCount = "retail.catalogue.manage,retail.stock.read,retail.profit.read";
        assertThat(api.post("/products/import?dry_run=true", body(csv, hq), noCount)
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        assertThat(apply(body(csv, hq), noCount, UUID.randomUUID().toString())
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        assertThat(products()).isZero();
    }

    @Test
    void aListKeptOnThePhoneIsDatedTheDayItWasMadeAndSentOnce() {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        Instant made = yesterday.atTime(12, 0).atZone(ZONE).toInstant();
        Map<String, Object> list = body(HEADER + "\nTest Offline Bucket,Test Home,Piece,6,3000,4500\n", hq);
        String asOf = made.minus(Duration.ofHours(1)).toString();
        list.put("offline", Map.of("occurred_at", made.toString(), "online_as_of", asOf));
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = apply(list, ADMIN, key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(apply(list, ADMIN, key).getHeaders().getFirst("Idempotent-Replayed"))
                .isEqualTo("true");
        assertThat(products()).isEqualTo(1);
        assertThat(openings()).isEqualTo(1);
        List<String> audits = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.entry.recorded_offline'")
                .params(t.tenantId())
                .query(String.class)
                .list();
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst()).contains("retail.product_import").contains(yesterday.toString());
        assertThat(scalar(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_type = 'retail.quick_add'"
                                + " AND entry_date = ?",
                        t.tenantId(),
                        yesterday))
                .isEqualTo(1);
    }
}
