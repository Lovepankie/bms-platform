package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.operations.Idempotency;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Quick add (issue #632, ADR-101, FR-RET-79): one request makes the item and adds the quantity typed to its stock at the chosen
 * branch, in one transaction, as one opening movement posted to inventory against opening balance equity, never a stock count.
 * Other items keep their stock, a name already in the list is refused with the existing item to add stock to instead (the
 * quantity is added, never set), General and Piece are made when missing, the stock needs its own permission, and the stock
 * history, the daily profit and stock health read it as opening stock, never as a difference or a loss. The tenant fixture has
 * no unit and no category, so each test says which it adds. Fabricated data.
 */
class RetailQuickAddIT extends IntegrationTest {

    /** Catalogue manage without counting stock. */
    static final String CATALOGUE_ONLY = "retail.catalogue.manage,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-quick", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
    }

    ResponseEntity<JsonNode> quickAdd(Map<String, Object> body, String perms, String scope, String key) {
        return api.postKeyed("/quick-add", body, perms, scope, key);
    }

    ResponseEntity<JsonNode> quickAdd(Map<String, Object> body) {
        return quickAdd(body, ADMIN, "*", UUID.randomUUID().toString());
    }

    static Map<String, Object> item(String name, long sell, String qty, UUID branch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", name);
        body.put("sell_minor", sell);
        if (qty != null) {
            body.put("qty", qty);
        }
        body.put("branch_id", branch);
        return body;
    }

    BigDecimal stockAt(UUID branch, UUID product) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branch, product)
                .query(BigDecimal.class)
                .single();
    }

    long scalar(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    /** Debits less credits on the account of `systemKey` from entries of `sourceType`. */
    long sourceTotal(String systemKey, String sourceType) {
        return scalar("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries j ON j.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = ? AND j.source_type = ?
                        """, t.tenantId(), systemKey, sourceType);
    }

    static UUID productOf(ResponseEntity<JsonNode> r) {
        return UUID.fromString(r.getBody().get("product_id").asString());
    }

    @Test
    void oneTapMakesTheItemAndItsStockAtTheChosenBranchOnly() {
        UUID other = api.product("QA-OTHER", 500, 800);
        api.stockUp(hq, other, "7");

        Map<String, Object> body = item("Test Bulb 60W", 6_000, "600", br2);
        body.put("cost_minor", 4_000);
        ResponseEntity<JsonNode> r = quickAdd(body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode added = r.getBody();
        UUID product = productOf(r);
        assertThat(added.get("item_made").asBoolean()).isTrue();
        assertThat(added.get("description").asString()).isEqualTo("Test Bulb 60W");
        assertThat(added.get("code").asString()).isEqualTo("GNR-0001");
        assertThat(added.get("qty").asString()).isEqualTo("600");
        assertThat(added.get("branch_id").asString()).isEqualTo(br2.toString());

        assertThat(stockAt(br2, product)).isEqualByComparingTo("600");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("0");
        // The other item is untouched: its stock stands and Quick add never names it.
        assertThat(stockAt(hq, other)).isEqualByComparingTo("7");
        assertThat(added.get("total_qty").asString()).isEqualTo("600");
        // One opening movement of its own source, at the buying price typed; no stock count, so no lost or damaged
        // stock.
        UUID movement = UUID.fromString(added.get("movement_id").asString());
        assertThat(scalar(
                        "SELECT count(*) FROM retail_stock_movements WHERE id = ? AND kind = 'opening'"
                                + " AND source_type = 'retail.quick_add' AND branch_id = ? AND qty = 600 AND unit_cost_minor = 4000",
                        movement,
                        br2))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM retail_stock_movements WHERE product_id = ?", product))
                .isEqualTo(1);
        // The item is made in its own unit with no pack (ADR-099), so no pack purpose or pack price is set.
        assertThat(scalar("SELECT count(*) FROM retail_product_units WHERE product_id = ?", product))
                .isEqualTo(0);
        assertThat(scalar("SELECT count(*) FROM retail_stocktakes WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        // Starting stock is never profit: inventory against opening balance equity, 600 at 4,000; the shrinkage account
        // holds only the other item's count.
        assertThat(sourceTotal("inventory", "retail.quick_add")).isEqualTo(2_400_000);
        assertThat(sourceTotal("opening_balance_equity", "retail.quick_add")).isEqualTo(-2_400_000);
        assertThat(sourceTotal("stock_shrinkage", "retail.quick_add")).isZero();
        // The item carries the prices typed.
        JsonNode read = api.get("/products/" + product, ADMIN).getBody();
        assertThat(read.get("sell_minor").asLong()).isEqualTo(6_000);
        assertThat(read.get("cost_minor").asLong()).isEqualTo(4_000);
    }

    @Test
    void generalAndPieceAreMadeWhenMissingAndUsedAfterwards() {
        // The fixture starts with no unit at all (no default units, ADR-098, for a tenant made by the test database).
        assertThat(scalar("SELECT count(*) FROM retail_units WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        ResponseEntity<JsonNode> first = quickAdd(item("Test Soap Bar", 2_500, "10", hq));
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("category_made").asBoolean()).isTrue();
        assertThat(first.getBody().get("unit_made").asBoolean()).isTrue();
        assertThat(first.getBody().get("unit").asString()).isEqualTo("Piece");

        ResponseEntity<JsonNode> second = quickAdd(item("Test Soap Box", 9_000, "2", hq));
        assertThat(second.getStatusCode()).as("%s", second.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("category_made").asBoolean()).isFalse();
        assertThat(second.getBody().get("unit_made").asBoolean()).isFalse();
        assertThat(scalar(
                        "SELECT count(*) FROM retail_categories WHERE tenant_id = ? AND lower(name) = 'general'",
                        t.tenantId()))
                .isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(*) FROM retail_units WHERE tenant_id = ? AND lower(name) IN ('piece', 'pc', 'pcs')",
                        t.tenantId()))
                .isEqualTo(1);
    }

    void switchOff(String table, UUID id) {
        TestDatabase.owner()
                .sql("UPDATE " + table + " SET active = false WHERE id = ?")
                .param(id)
                .update();
    }

    long costOf(UUID product) {
        return scalar("SELECT cost_minor FROM retail_products WHERE id = ?", product);
    }

    @Test
    void aSwitchedOffPcIsPassedOverAndPieceIsMade() {
        UUID pc = api.unit("pc");
        switchOff("retail_units", pc);
        ResponseEntity<JsonNode> r = quickAdd(item("Test Candle", 300, "12", hq));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("unit").asString()).isEqualTo("Piece");
        assertThat(r.getBody().get("unit_made").asBoolean()).isTrue();
    }

    @Test
    void aSwitchedOffPieceOrGeneralIsNotSwitchedBackOnAndTheFieldIsNamed() {
        UUID piece = api.unit("Piece");
        switchOff("retail_units", piece);
        ResponseEntity<JsonNode> noUnit = quickAdd(item("Test Brush", 700, "2", hq));
        assertThat(noUnit.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noUnit.getBody().toString()).contains("unit_id").contains("Piece unit is switched off");

        UUID general = api.category("General");
        switchOff("retail_categories", general);
        Map<String, Object> body = item("Test Brush", 700, "2", hq);
        body.put("unit_id", api.unit("Bundle"));
        ResponseEntity<JsonNode> noCategory = quickAdd(body);
        assertThat(noCategory.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noCategory.getBody().toString())
                .contains("category_id")
                .contains("General category is switched off");
        // Nothing was made, and neither was switched back on.
        assertThat(scalar(
                        "SELECT count(*) FROM retail_products WHERE tenant_id = ? AND description = 'Test Brush'",
                        t.tenantId()))
                .isZero();
        assertThat(scalar("SELECT count(*) FROM retail_units WHERE id = ? AND active", piece))
                .isZero();
        assertThat(scalar("SELECT count(*) FROM retail_categories WHERE id = ? AND active", general))
                .isZero();
    }

    @Test
    void aBuyingPriceFromSomeoneWhoMayNotReadCostIsKeptAsAddingOnItemsKeepsIt() {
        String noProfit = "retail.catalogue.manage,retail.stock.read,retail.stocktake.commit";
        Map<String, Object> body = item("Test Kettle", 9_000, "1", hq);
        body.put("cost_minor", 6_000);
        ResponseEntity<JsonNode> quick =
                quickAdd(body, noProfit, "*", UUID.randomUUID().toString());
        assertThat(quick.getStatusCode()).as("%s", quick.getBody()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("description", "Test Kettle Large");
        plain.put("category_id", api.category("Test Kitchen"));
        plain.put("unit_id", api.unit("Each"));
        plain.put("cost_minor", 6_000);
        plain.put("sell_minor", 9_000);
        ResponseEntity<JsonNode> items = api.post("/products", plain, noProfit);
        assertThat(items.getStatusCode()).as("%s", items.getBody()).isEqualTo(HttpStatus.CREATED);
        // The same rule as Items: the price typed is kept, and neither answer shows a cost to someone without
        // retail.profit.read.
        assertThat(costOf(productOf(quick)))
                .isEqualTo(costOf(RetailTestSupport.id(items)))
                .isEqualTo(6_000);
        assertThat(items.getBody().has("cost_minor")
                        && !items.getBody().get("cost_minor").isNull())
                .isFalse();
    }

    @Test
    void theHistoryAndTheReportsReadItAsOpeningStockNeverAsADifferenceOrALoss() {
        Map<String, Object> body = item("Test Lantern", 15_000, "20", hq);
        body.put("cost_minor", 10_000);
        UUID product = productOf(quickAdd(body));
        JsonNode moves = api.get("/stock/movements?branch_id=" + hq + "&product_id=" + product, ADMIN)
                .getBody()
                .get("items");
        assertThat(moves).hasSize(1);
        assertThat(moves.get(0).get("kind").asString()).isEqualTo("opening");
        assertThat(moves.get(0).get("source_type").asString()).isEqualTo("retail.quick_add");
        assertThat(moves.get(0).get("qty").asString()).isEqualTo("20.000");
        // The item's cost changes after the Quick add (issue #641): the opening layer keeps 10,000, so every figure
        // below proves the stock is costed from that layer, never from the item's cost now.
        ResponseEntity<JsonNode> repriced = api.editPrices(
                product, Map.of("cost_minor", 14_000, "sell_minor", 15_000, "reason", "Test new cost"), ADMIN);
        assertThat(repriced.getStatusCode()).as("%s", repriced.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(costOf(product)).isEqualTo(14_000);
        String today = java.time.LocalDate.now(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE)
                .toString();
        // Sell two at 15,000 and report one used, so every report has a row of this item and its checks cannot pass
        // empty.
        ResponseEntity<JsonNode> sale = api.sell(hq, "cash", product, "2");
        assertThat(sale.getStatusCode()).as("%s", sale.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> used = new LinkedHashMap<>();
        used.put("branch_id", hq);
        used.put("kind", "used");
        used.put("reason", "Test lamp for the counter");
        used.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> usage =
                api.postKeyed("/usage", used, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(usage.getStatusCode()).as("%s", usage.getBody()).isEqualTo(HttpStatus.CREATED);
        // The usage is costed from the opening layer, 10,000 a piece.
        assertThat(usage.getBody().get("lines").get(0).get("unit_cost_minor").asLong())
                .isEqualTo(10_000);

        // Margins: the two sold cost the opening layer's 10,000 each, so 30,000 of sales make 10,000 of profit.
        JsonNode margins = api.get("/reports/margins?from=" + today + "&to=" + today, ADMIN)
                .getBody();
        JsonNode row = null;
        for (JsonNode r : margins.get("by_product")) {
            if (r.get("id").asString().equals(product.toString())) {
                row = r;
            }
        }
        assertThat(row).as("%s", margins).isNotNull();
        assertThat(new BigDecimal(row.get("qty").asString())).isEqualByComparingTo("2");
        assertThat(row.get("sales_minor").asLong()).isEqualTo(30_000);
        assertThat(row.get("cost_minor").asLong()).isEqualTo(20_000);
        assertThat(row.get("profit_minor").asLong()).isEqualTo(10_000);

        // The daily profit: the sale and the usage at the opening cost, and no stock-take difference from the opening
        // stock.
        JsonNode profit = api.get("/reports/profit/daily?from=" + today + "&to=" + today, ADMIN)
                .getBody();
        assertThat(profit.get("sales_minor").asLong()).isEqualTo(30_000);
        assertThat(profit.get("cost_of_sales_minor").asLong()).isEqualTo(20_000);
        assertThat(profit.get("usage_cost_minor").asLong()).isEqualTo(10_000);
        assertThat(profit.get("stocktake_difference_minor").asLong()).isZero();

        // Stock health's lost or damaged row of the branch: the one usage report, no short or over line, no stock-take
        // gain.
        JsonNode health = api.get("/reports/stock-health", ADMIN).getBody();
        JsonNode shrink = null;
        for (JsonNode r : health.get("shrinkage")) {
            if (r.get("branch_id").asString().equals(hq.toString())) {
                shrink = r;
            }
        }
        assertThat(shrink).as("%s", health.get("shrinkage")).isNotNull();
        assertThat(shrink.get("used_reports").asInt()).isEqualTo(1);
        assertThat(shrink.get("used_cost_minor").asLong()).isEqualTo(10_000);
        assertThat(shrink.get("damaged_reports").asInt()).isZero();
        assertThat(shrink.get("short_lines").asInt() + shrink.get("over_lines").asInt())
                .isZero();
        assertThat(shrink.get("stocktake_gain_minor").asLong()).isZero();
        assertThat(shrink.get("stocktake_loss_minor").asLong()).isZero();
        assertThat(stockAt(hq, product)).isEqualByComparingTo("17");
        // A switched-off item takes no stock here.
        switchOff("retail_products", product);
        Map<String, Object> more = new LinkedHashMap<>();
        more.put("product_id", product);
        more.put("qty", "5");
        more.put("branch_id", hq);
        ResponseEntity<JsonNode> off = quickAdd(more);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(off.getBody().toString()).contains("inactive_product");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("17");
    }

    @Test
    void aChosenCategoryAndUnitAreKept() {
        UUID category = api.category("Test Drinks");
        UUID unit = api.unit("Bottle");
        Map<String, Object> body = item("Test Juice 500ml", 3_000, "24", hq);
        body.put("category_id", category);
        body.put("unit_id", unit);
        ResponseEntity<JsonNode> r = quickAdd(body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("unit").asString()).isEqualTo("Bottle");
        assertThat(r.getBody().get("category_made").asBoolean()).isFalse();
        JsonNode read = api.get("/products/" + productOf(r), ADMIN).getBody();
        assertThat(read.get("category_id").asString()).isEqualTo(category.toString());
    }

    @Test
    void aNameAlreadyInTheListIsRefusedAndTheExistingItemTakesTheStock() {
        ResponseEntity<JsonNode> first = quickAdd(item("Test Rice 1kg", 5_000, "5", hq));
        UUID product = productOf(first);

        ResponseEntity<JsonNode> again = quickAdd(item("test rice 1KG", 5_200, "8", hq));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("duplicate_product_name");
        assertThat(again.getBody().get("existing_id").asString()).isEqualTo(product.toString());
        // Nothing was made and the stock is as it was.
        assertThat(scalar(
                        "SELECT count(*) FROM retail_products WHERE tenant_id = ? AND lower(description) = 'test rice 1kg'",
                        t.tenantId()))
                .isEqualTo(1);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("5");

        // Use the existing one and just add stock: exactly the quantity typed is added, 5 becomes 13, never set to 8.
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("product_id", product);
        existing.put("qty", "8");
        existing.put("branch_id", hq);
        ResponseEntity<JsonNode> added = quickAdd(existing);
        assertThat(added.getStatusCode()).as("%s", added.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody().get("item_made").asBoolean()).isFalse();
        assertThat(stockAt(hq, product)).isEqualByComparingTo("13");
        assertThat(added.getBody().get("qty").asString()).isEqualTo("8");
        assertThat(added.getBody().get("total_qty").asString()).isEqualTo("13");
        // Adding nothing to an item already in the list is refused.
        existing.put("qty", "0");
        assertThat(quickAdd(existing).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/products/" + product, ADMIN)
                        .getBody()
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(5_000);
    }

    @Test
    void addingStockNeedsStockCountPermissionAndNothingIsMadeWithoutIt() {
        // The route's permission is held but not the one RetailBranchContext.resolve checks for the stock: 403
        // permission_denied (spec trade-capabilities section 2.2, issue #257).
        ResponseEntity<JsonNode> refused = quickAdd(
                item("Test Pen Blue", 500, "30", hq),
                CATALOGUE_ONLY,
                "*",
                UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("permission_denied");
        assertThat(scalar(
                        "SELECT count(*) FROM retail_products WHERE tenant_id = ? AND description = 'Test Pen Blue'",
                        t.tenantId()))
                .isZero();

        // Without a quantity, catalogue manage alone makes the item, with no stock recorded.
        ResponseEntity<JsonNode> itemOnly = quickAdd(
                item("Test Pen Blue", 500, null, null),
                CATALOGUE_ONLY,
                "*",
                UUID.randomUUID().toString());
        assertThat(itemOnly.getStatusCode()).as("%s", itemOnly.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(itemOnly.getBody().has("movement_id")
                        && !itemOnly.getBody().get("movement_id").isNull())
                .isFalse();

        // Without catalogue manage the route is refused.
        ResponseEntity<JsonNode> seller = quickAdd(
                item("Test Pen Red", 500, "3", hq),
                RetailTestSupport.SALES + ",retail.stocktake.commit",
                "*",
                UUID.randomUUID().toString());
        assertThat(seller.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void addingStockIsLimitedToTheBranchesInScope() {
        ResponseEntity<JsonNode> r = quickAdd(
                item("Test Torch", 8_000, "4", br2),
                ADMIN,
                hq.toString(),
                UUID.randomUUID().toString());
        // Issue #257: a branch outside the scope is 404 unknown_branch.
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code").asString()).isEqualTo("unknown_branch");
        assertThat(scalar(
                        "SELECT count(*) FROM retail_products WHERE tenant_id = ? AND description = 'Test Torch'",
                        t.tenantId()))
                .isZero();
    }

    @Test
    void theSameKeyReplaysAndMakesOneItem() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = item("Test Matches", 200, "50", hq);
        ResponseEntity<JsonNode> first = quickAdd(body, ADMIN, "*", key);
        ResponseEntity<JsonNode> second = quickAdd(body, ADMIN, "*", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(productOf(second)).isEqualTo(productOf(first));
        assertThat(stockAt(hq, productOf(first))).isEqualByComparingTo("50");
    }

    @Test
    void aRetryAfterALostAnswerAddsOnceSoFiftyAndTenMakeSixty() {
        UUID product = productOf(quickAdd(item("Test Chalk Box", 1_000, "50", hq)));
        Map<String, Object> more = new LinkedHashMap<>();
        more.put("product_id", product);
        more.put("qty", "10");
        more.put("branch_id", hq);
        String key = UUID.randomUUID().toString();
        // The first add commits; its answer is lost on the way (a 502 at the proxy), so the form sends the same request
        // again.
        assertThat(quickAdd(more, ADMIN, "*", key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> retry = quickAdd(more, ADMIN, "*", key);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("total_qty").asString()).isEqualTo("60");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("60");
        // The same key with another quantity is refused, never a second add.
        more.put("qty", "20");
        ResponseEntity<JsonNode> reused = quickAdd(more, ADMIN, "*", key);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // The code the form treats as "may have been saved" (quick-add.tsx, IDEMPOTENCY_UNSURE_CODES), so it never
        // takes a new key.
        assertThat(reused.getBody().get("code").asString()).isEqualTo(Idempotency.KEY_REUSED);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("60");
    }

    @Test
    void aMissingNameOrPriceIsRefused() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qty", "1");
        ResponseEntity<JsonNode> r = quickAdd(body);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("description").contains("sell_minor");
    }
    // Issue #646: "I just bought these, take the money from cash" books the goods as a purchase paid from the branch's
    // cash or
    // mobile money, as Buy stock does: the cash goes down by qty times cost and nothing posts to opening balance
    // equity.

    @Test
    void paidFromCashTheCashGoesDownByQtyTimesCostAndTheStockGoesUp() {
        Map<String, Object> body = item("Test Soap Bar", 3_000, "12", br2);
        body.put("cost_minor", 2_500);
        body.put("paid_from", "cash");
        ResponseEntity<JsonNode> r = quickAdd(body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID product = productOf(r);
        assertThat(r.getBody().get("paid_from").asString()).isEqualTo("cash");
        assertThat(r.getBody().get("paid_minor").asLong()).isEqualTo(30_000);
        assertThat(r.getBody().get("total_qty").asString()).isEqualTo("12");
        String purchaseNo = r.getBody().get("purchase_no").asString();
        assertThat(stockAt(br2, product)).isEqualByComparingTo("12");
        // One cash purchase at the branch, with no supplier, read by the cash book as cash out of the till.
        assertThat(scalar(
                        "SELECT count(*) FROM retail_purchases WHERE tenant_id = ? AND purchase_no = ?"
                                + " AND payment_method = 'cash' AND supplier_id IS NULL AND total_minor = 30000",
                        t.tenantId(),
                        purchaseNo))
                .isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(*) FROM retail_stock_movements WHERE product_id = ? AND branch_id = ?"
                                + " AND kind = 'purchase' AND source_type = 'retail.purchase' AND qty = 12 AND unit_cost_minor = 2500",
                        product,
                        br2))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM retail_stock_movements WHERE product_id = ?", product))
                .isEqualTo(1);
        // Dr stock, Cr cash; never opening balance equity.
        assertThat(sourceTotal("inventory", "retail.purchase")).isEqualTo(30_000);
        assertThat(sourceTotal("cash_on_hand", "retail.purchase")).isEqualTo(-30_000);
        assertThat(sourceTotal("opening_balance_equity", "retail.quick_add")).isZero();
        assertThat(sourceTotal("inventory", "retail.quick_add")).isZero();
        assertThat(scalar(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.stock.quick_added'"
                                + " AND data -> 'after' ->> 'paid_from' = 'cash' AND entity_type = 'retail.quick_add'"
                                + " AND data -> 'after' ->> 'purchase_id' = (SELECT id::text FROM retail_purchases"
                                + " WHERE tenant_id = ? AND purchase_no = ?)"
                                + " AND entity_id <> (SELECT id FROM retail_purchases WHERE tenant_id = ? AND purchase_no = ?)",
                        t.tenantId(),
                        t.tenantId(),
                        purchaseNo,
                        t.tenantId(),
                        purchaseNo))
                .isEqualTo(1);
        // The purchase sets the same cost the new item was made with, so the price history gains no second row.
        assertThat(scalar(
                        "SELECT count(*) FROM retail_price_history WHERE product_id = ? AND source = 'purchase'",
                        product))
                .isZero();
    }

    @Test
    void anItemAlreadyInTheListPaidFromMobileMoneyIsAPurchaseOnMobileMoney() {
        UUID product = productOf(quickAdd(item("Test Rice 1kg", 5_000, "10", hq)));
        Map<String, Object> more = new LinkedHashMap<>();
        more.put("product_id", product);
        more.put("qty", "4");
        more.put("branch_id", hq);
        more.put("cost_minor", 4_200);
        more.put("paid_from", "mobile_money");
        ResponseEntity<JsonNode> r = quickAdd(more);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("total_qty").asString()).isEqualTo("14");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("14");
        assertThat(sourceTotal("mobile_money", "retail.purchase")).isEqualTo(-16_800);
        assertThat(sourceTotal("cash_on_hand", "retail.purchase")).isZero();
    }

    @Test
    void aRetryOfAPaidAddPostsOnce() {
        Map<String, Object> body = item("Test Sugar 2kg", 9_000, "5", hq);
        body.put("cost_minor", 7_000);
        body.put("paid_from", "cash");
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = quickAdd(body, ADMIN, "*", key);
        ResponseEntity<JsonNode> retry = quickAdd(body, ADMIN, "*", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("purchase_no").asString())
                .isEqualTo(first.getBody().get("purchase_no").asString());
        assertThat(scalar("SELECT count(*) FROM retail_purchases WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        assertThat(sourceTotal("cash_on_hand", "retail.purchase")).isEqualTo(-35_000);
        assertThat(stockAt(hq, productOf(first))).isEqualByComparingTo("5");
    }

    @Test
    void payingFromCashNeedsBuyStockPermissionOnTheServerAndNothingIsMadeWithoutIt() {
        Map<String, Object> body = item("Test Salt", 1_000, "3", hq);
        body.put("cost_minor", 700);
        body.put("paid_from", "cash");
        // The same body without paid_from is accepted for this caller, so the refusal is the permission and nothing
        // else.
        String counter = "retail.catalogue.manage,retail.stock.read,retail.stocktake.commit";
        ResponseEntity<JsonNode> refused =
                quickAdd(body, counter, "*", UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("permission_denied");

        // Buy stock held only at the second branch: refused at the head office, the branch the money would leave.
        ResponseEntity<JsonNode> elsewhere = api.call(
                org.springframework.http.HttpMethod.POST,
                "/quick-add",
                body,
                counter,
                "*",
                Map.of(
                        "Idempotency-Key",
                        UUID.randomUUID().toString(),
                        "X-Dev-Scopes",
                        "retail.purchase.create=" + br2));
        assertThat(elsewhere.getStatusCode()).as("%s", elsewhere.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(elsewhere.getBody().get("code").asString()).isEqualTo("permission_denied");
        assertThat(scalar(
                        "SELECT count(*) FROM retail_products WHERE tenant_id = ? AND description = 'Test Salt'",
                        t.tenantId()))
                .isZero();
        assertThat(scalar("SELECT count(*) FROM retail_purchases WHERE tenant_id = ?", t.tenantId()))
                .isZero();

        // At the branch where it is held, the same add goes through as a purchase.
        body.put("branch_id", br2);
        ResponseEntity<JsonNode> there = api.call(
                org.springframework.http.HttpMethod.POST,
                "/quick-add",
                body,
                counter,
                "*",
                Map.of(
                        "Idempotency-Key",
                        UUID.randomUUID().toString(),
                        "X-Dev-Scopes",
                        "retail.purchase.create=" + br2));
        assertThat(there.getStatusCode()).as("%s", there.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(scalar("SELECT count(*) FROM retail_purchases WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
    }

    @Test
    void aBankedCashDayTreatsAPaidQuickAddExactlyAsBuyStockTreatsACashRestock() {
        // Buy stock has no banked-day refusal (only a supplier payment in cash has one, cash_day_banked): its cash
        // restock is
        // dated today and leaves the till today. A paid Quick add runs the same record(), so it must give the same
        // outcome.
        String today = java.time.LocalDate.now(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE)
                .toString();
        String owner = ADMIN + ",retail.banking.record";
        Map<String, Object> banking = new LinkedHashMap<>();
        banking.put("branch_id", hq);
        banking.put("business_date", today);
        banking.put("amount_minor", 1_000);
        ResponseEntity<JsonNode> banked = api.postKeyed(
                "/bankings", banking, owner, "*", UUID.randomUUID().toString());
        assertThat(banked.getStatusCode()).as("%s", banked.getBody()).isEqualTo(HttpStatus.CREATED);

        UUID bought = productOf(quickAdd(item("Test Beans 1kg", 6_000, null, null)));
        Map<String, Object> restock = new LinkedHashMap<>();
        restock.put("purchased_on", today);
        restock.put("payment_method", "cash");
        restock.put(
                "lines",
                List.of(Map.of(
                        "product_id",
                        bought,
                        "cost_minor",
                        4_000,
                        "qty_by_branch",
                        List.of(Map.of("branch_id", hq, "qty", "2")))));
        ResponseEntity<JsonNode> buyStock = api.postKeyed(
                "/purchases", restock, owner, "*", UUID.randomUUID().toString());

        Map<String, Object> body = item("Test Peas 1kg", 7_000, "2", hq);
        body.put("cost_minor", 5_000);
        body.put("paid_from", "cash");
        ResponseEntity<JsonNode> paid =
                quickAdd(body, owner, "*", UUID.randomUUID().toString());

        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(buyStock.getStatusCode());
        assertThat(buyStock.getStatusCode()).as("%s", buyStock.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(scalar(
                        "SELECT count(*) FROM retail_purchases WHERE tenant_id = ? AND payment_method = 'cash'"
                                + " AND purchased_on = ?::date",
                        t.tenantId(),
                        today))
                .isEqualTo(2);
        assertThat(sourceTotal("cash_on_hand", "retail.purchase")).isEqualTo(-18_000);
    }

    @Test
    void whatBuyStockRefusesAPaidQuickAddRefusesTheSameWayAndTakesNoCash() {
        UUID item = productOf(quickAdd(item("Test Flour 2kg", 9_000, null, null)));
        String today = java.time.LocalDate.now(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE)
                .toString();
        // A quantity finer than a thousandth, and a cost above the largest amount: Buy stock refuses both.
        List<Map<String, Object>> refusedValues = List.of(
                Map.of("qty", "1.2345", "cost_minor", 1_000L),
                Map.of(
                        "qty",
                        "1",
                        "cost_minor",
                        com.rincoltech.bms.retail.catalogue.RetailCatalogue.MAX_AMOUNT_MINOR + 1));
        for (Map<String, Object> v : refusedValues) {
            Map<String, Object> restock = new LinkedHashMap<>();
            restock.put("purchased_on", today);
            restock.put("payment_method", "cash");
            restock.put(
                    "lines",
                    List.of(Map.of(
                            "product_id",
                            item,
                            "cost_minor",
                            v.get("cost_minor"),
                            "qty_by_branch",
                            List.of(Map.of("branch_id", hq, "qty", v.get("qty"))))));
            ResponseEntity<JsonNode> buyStock = api.postKeyed(
                    "/purchases", restock, ADMIN, "*", UUID.randomUUID().toString());

            Map<String, Object> paid = new LinkedHashMap<>();
            paid.put("product_id", item);
            paid.put("qty", v.get("qty"));
            paid.put("branch_id", hq);
            paid.put("cost_minor", v.get("cost_minor"));
            paid.put("paid_from", "cash");
            ResponseEntity<JsonNode> quick = quickAdd(paid);

            assertThat(buyStock.getStatusCode())
                    .as("%s %s", v, buyStock.getBody())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(quick.getStatusCode()).as("%s %s", v, quick.getBody()).isEqualTo(buyStock.getStatusCode());
            assertThat(quick.getBody().get("code").asString())
                    .isEqualTo(buyStock.getBody().get("code").asString());
        }
        assertThat(scalar("SELECT count(*) FROM retail_purchases WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        assertThat(sourceTotal("cash_on_hand", "retail.purchase")).isZero();
        assertThat(stockAt(hq, item)).isEqualByComparingTo("0");
    }

    @Test
    void payingFromCashNeedsTheBuyingPriceAndAQuantity() {
        Map<String, Object> body = item("Test Oil 1L", 8_000, null, hq);
        body.put("paid_from", "cash");
        ResponseEntity<JsonNode> r = quickAdd(body);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("cost_minor").contains("qty");
        body.put("paid_from", "bank");
        body.put("qty", "1");
        body.put("cost_minor", 6_000);
        assertThat(quickAdd(body).getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(scalar("SELECT count(*) FROM retail_purchases WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }
}
