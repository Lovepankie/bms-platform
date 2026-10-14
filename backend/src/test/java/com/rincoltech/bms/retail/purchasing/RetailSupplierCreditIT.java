package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Supplier credit notes (ADR-033, issue #199 amendment; docs/specs/trade-capabilities.md sections 7.1 and 12.3): goods sent
 * back to the supplier and price adjustments, against a supplier invoice that stands. The postings (trade creditors against
 * inventory and purchase price variance, the cost of goods sold for a price credit on goods already sold), the stock movement
 * and its guard, the limits (the quantity billed, the value of the line, what the invoice has left to pay), the cost update
 * guard, the voids and what blocks them, idempotency, branch scope and the capability. Every name and amount is fabricated.
 */
class RetailSupplierCreditIT extends LandedCostTestBase {

    /** A supplier invoice of the given receipts' first lines, each in full, at a price per unit. */
    JsonNode billOf(String ref, long price, JsonNode... receipts) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        long total = 0;
        for (JsonNode r : receipts) {
            JsonNode l = r.get("lines").get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("receipt_line_id", uuid(l, "id"));
            m.put(
                    "qty",
                    l.get("unit_qty") != null
                            ? l.get("unit_qty").asString()
                            : l.get("qty").asString());
            m.put("unit_price_minor", price);
            lines.add(m);
            total += new java.math.BigDecimal(l.get("qty").asString())
                    .multiply(java.math.BigDecimal.valueOf(price))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("supplier_ref", ref);
        b.put("bill_date", TODAY);
        b.put("due_date", DUE);
        b.put("total_minor", total);
        b.put("variance_reason", "Test supplier raised the price");
        b.put("lines", lines);
        ResponseEntity<JsonNode> r = keyed("/supplier-bills", b, PAYER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    static UUID billLine(JsonNode bill, int index) {
        return uuid(bill.get("lines").get(index), "id");
    }

    static Map<String, Object> goodsLine(UUID billLine, String qty, Long unitCredit) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("bill_line_id", billLine);
        l.put("qty", qty);
        if (unitCredit != null) {
            l.put("unit_credit_minor", unitCredit);
        }
        return l;
    }

    static Map<String, Object> priceLine(UUID billLine, long amount) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("bill_line_id", billLine);
        l.put("amount_minor", amount);
        return l;
    }

    Map<String, Object> creditBody(UUID bill, String kind, String ref, long total, Map<String, Object>... lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("bill_id", bill);
        b.put("kind", kind);
        b.put("supplier_ref", ref);
        b.put("credit_date", TODAY);
        b.put("total_minor", total);
        b.put("reason", "Test goods damaged on delivery");
        b.put("lines", List.of(lines));
        return b;
    }

    ResponseEntity<JsonNode> creditRaw(Map<String, Object> body) {
        return keyed("/supplier-credits", body, PAYER);
    }

    JsonNode credited(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = creditRaw(body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    ResponseEntity<JsonNode> voidCredit(UUID id) {
        return keyed("/supplier-credits/" + id + "/void", Map.of("reason", "Test credit note withdrawn"), PAYER);
    }

    JsonNode bill(UUID id) {
        return api.get("/supplier-bills/" + id, PAYER).getBody();
    }

    // ---- goods sent back ---------------------------------------------------------------------

    @Test
    void goodsSentBackLeaveStockAndInventoryAndTakeTheCreditOffTheInvoice() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-1", 120, r);
        UUID billId = uuid(bill, "id");
        long inventory = balance("inventory");
        JsonNode credit = credited(creditBody(billId, "goods", "CN-1", 480, goodsLine(billLine(bill, 0), "4", null)));
        UUID id = uuid(credit, "id");
        assertThat(credit.get("credit_no").asString()).isEqualTo("SCHQ-000001");
        assertThat(credit.get("kind").asString()).isEqualTo("goods");
        assertThat(credit.get("total_minor").asLong()).isEqualTo(480);
        // 4 of 10 units carried at 1000 leave inventory at 400; the supplier credited 480, so 80 goes back to variance.
        assertThat(posted("retail.supplier_credit", id).get("trade_creditors")).containsExactly(480, 0);
        assertThat(posted("retail.supplier_credit", id).get("inventory")).containsExactly(0, 400);
        assertThat(posted("retail.supplier_credit", id).get("purchase_price_variance"))
                .containsExactly(0, 80);
        assertThat(balance("inventory")).isEqualTo(inventory - 400);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("6");
        assertThat(scalar(
                        "SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'return_to_supplier' AND qty = -4",
                        t.tenantId()))
                .isEqualTo(1);
        JsonNode line = credit.get("lines").get(0);
        assertThat(line.get("qty").asString()).isEqualTo("4.000");
        assertThat(line.get("line_total_minor").asLong()).isEqualTo(480);
        assertThat(line.get("to_inventory_minor").asLong()).isEqualTo(400);
        assertThat(line.get("variance_minor").asLong()).isEqualTo(80);
        // The invoice leaves 720 to pay, which a payment can settle in full.
        JsonNode after = bill(billId);
        assertThat(after.get("credited_minor").asLong()).isEqualTo(480);
        assertThat(after.get("open_minor").asLong()).isEqualTo(720);
        JsonNode open = api.get("/suppliers/" + supplier + "/open-items", PAYER).getBody();
        assertThat(open.get("items").get(0).get("credited_minor").asLong()).isEqualTo(480);
        assertThat(open.get("total_open_minor").asLong()).isEqualTo(720);
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("supplier_id", supplier);
        pay.put("branch_id", hq);
        pay.put("method", "bank");
        pay.put("amount_minor", 720);
        assertThat(keyed("/supplier-payments", pay, PAYER).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bill(billId).get("open_minor").asLong()).isZero();
        assertThat(balance("trade_creditors")).isZero();
        assertBooksBalance();
    }

    @Test
    void theReportAgesWhatIsLeftAfterTheCredit() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-R", 120, r);
        credited(creditBody(uuid(bill, "id"), "goods", "CN-R", 480, goodsLine(billLine(bill, 0), "4", null)));
        JsonNode report = api.get("/reports/supplier-payables", PAYER).getBody();
        assertThat(report.get("totals").get("owed_minor").asLong()).isEqualTo(720);
        assertThat(report.get("totals").get("not_due_minor").asLong()).isEqualTo(720);
    }

    @Test
    void theCreditForOneUnitMayDifferFromTheBilledPriceAndTheDifferenceGoesToVariance() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-2", 120, r);
        // The supplier takes the goods back at 90 a unit, keeping a restocking charge: 4 x 90 = 360 against 400
        // carried.
        JsonNode credit =
                credited(creditBody(uuid(bill, "id"), "goods", "CN-2", 360, goodsLine(billLine(bill, 0), "4", 90L)));
        UUID id = uuid(credit, "id");
        assertThat(posted("retail.supplier_credit", id).get("trade_creditors")).containsExactly(360, 0);
        assertThat(posted("retail.supplier_credit", id).get("inventory")).containsExactly(0, 400);
        assertThat(posted("retail.supplier_credit", id).get("purchase_price_variance"))
                .containsExactly(40, 0);
        assertBooksBalance();
    }

    @Test
    void theLastUnitsTakeTheRemainderSoInventoryClearsExactly() {
        JsonNode r = receive(hq, product, "3", 100);
        JsonNode bill = billOf("INV-3", 100, r);
        // 300 carried over 3 units: three credits of one unit each take 100, 100 and the last 100.
        for (int i = 1; i <= 3; i++) {
            credited(creditBody(uuid(bill, "id"), "goods", "CN-3-" + i, 100, goodsLine(billLine(bill, 0), "1", null)));
        }
        assertThat(balance("inventory")).isZero();
        assertThat(stockAt(hq, product)).isEqualByComparingTo("0");
        assertThat(bill(uuid(bill, "id")).get("open_minor").asLong()).isZero();
        // Nothing more can be sent back.
        ResponseEntity<JsonNode> more = creditRaw(
                creditBody(uuid(bill, "id"), "goods", "CN-3-4", 100, goodsLine(billLine(bill, 0), "1", null)));
        assertThat(more.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(more)).isEqualTo("credit_exceeds_billed");
    }

    @Test
    void goodsSentBackTakeTheLandedCostWithThemSoTheFreightShareIsLost() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-4", 100, r);
        landed(freight("FWD-C", 500, lineId(r)));
        // 4 of 10 units are carried at (1000 + 500) x 4 / 10 = 600; the supplier credits only the goods, 400.
        JsonNode credit =
                credited(creditBody(uuid(bill, "id"), "goods", "CN-4", 400, goodsLine(billLine(bill, 0), "4", null)));
        UUID id = uuid(credit, "id");
        assertThat(posted("retail.supplier_credit", id).get("inventory")).containsExactly(0, 600);
        assertThat(posted("retail.supplier_credit", id).get("purchase_price_variance"))
                .containsExactly(200, 0);
        // A landed cost cannot now be added to the line, and the one on it cannot be voided.
        ResponseEntity<JsonNode> again = landedRaw(freight("FWD-C2", 100, lineId(r)));
        assertThat(code(again)).isEqualTo("line_returned");
        ResponseEntity<JsonNode> voided = voidLanded(
                uuid(api.get("/landed-costs", PAYER).getBody().get("items").get(0), "id"));
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(voided)).isEqualTo("landed_cost_credited");
        assertBooksBalance();
    }

    @Test
    void aCreditIsRefusedForMoreThanWasBilledSentBackOrLeftToPayOrOnHand() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-5", 120, r);
        UUID billId = uuid(bill, "id");
        UUID line = billLine(bill, 0);
        // More than the invoice billed.
        ResponseEntity<JsonNode> tooMany =
                creditRaw(creditBody(billId, "goods", "CN-5a", 1_320, goodsLine(line, "11", null)));
        assertThat(code(tooMany)).isEqualTo("credit_exceeds_billed");
        // More value than the line holds.
        ResponseEntity<JsonNode> tooDear =
                creditRaw(creditBody(billId, "goods", "CN-5b", 1_300, goodsLine(line, "10", 130L)));
        assertThat(code(tooDear)).isEqualTo("credit_exceeds_billed_value");
        // A typing check: the total is the lines.
        ResponseEntity<JsonNode> typo =
                creditRaw(creditBody(billId, "goods", "CN-5c", 999, goodsLine(line, "4", null)));
        assertThat(code(typo)).isEqualTo("credit_total_mismatch");
        // Not on hand: eight were sold, so four cannot go back.
        assertThat(api.sell(hq, "cash", product, "8").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> sold =
                creditRaw(creditBody(billId, "goods", "CN-5d", 480, goodsLine(line, "4", null)));
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(sold)).isEqualTo("credit_exceeds_on_hand");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("2");
        // Two can go back, and then the same number twice is a duplicate.
        credited(creditBody(billId, "goods", "CN-5e", 240, goodsLine(line, "2", null)));
        ResponseEntity<JsonNode> dup = creditRaw(creditBody(billId, "goods", "cn-5e", 240, goodsLine(line, "2", null)));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(dup)).isEqualTo("duplicate_supplier_credit");
        assertBooksBalance();
    }

    @Test
    void aCreditCannotExceedWhatTheInvoiceLeavesToPay() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-6", 100, r);
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("supplier_id", supplier);
        pay.put("branch_id", hq);
        pay.put("method", "bank");
        pay.put("amount_minor", 800);
        assertThat(keyed("/supplier-payments", pay, PAYER).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 200 is left to pay: a credit of 300 would take the invoice below zero.
        ResponseEntity<JsonNode> over =
                creditRaw(creditBody(uuid(bill, "id"), "goods", "CN-6", 300, goodsLine(billLine(bill, 0), "3", null)));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("credit_exceeds_open");
        credited(creditBody(uuid(bill, "id"), "goods", "CN-6b", 200, goodsLine(billLine(bill, 0), "2", null)));
        assertThat(bill(uuid(bill, "id")).get("open_minor").asLong()).isZero();
        // Paid and credited in full, the invoice is no longer among those still owed.
        assertThat(api.get("/supplier-bills?open=true", PAYER).getBody().get("items"))
                .isEmpty();
        assertThat(api.get("/suppliers/" + supplier + "/open-items", PAYER)
                        .getBody()
                        .get("items"))
                .isEmpty();
    }

    @Test
    void creditsOfDifferentKindsOnOneInvoiceAreKeptApart() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-7", 100, r);
        UUID line = billLine(bill, 0);
        // A goods credit needs a quantity and no amount, a price credit an amount and no quantity.
        assertThat(code(creditRaw(creditBody(uuid(bill, "id"), "goods", "CN-7a", 100, priceLine(line, 100)))))
                .isEqualTo("validation_failed");
        assertThat(code(creditRaw(creditBody(uuid(bill, "id"), "price", "CN-7b", 100, goodsLine(line, "1", null)))))
                .isEqualTo("validation_failed");
        assertThat(code(creditRaw(
                        creditBody(uuid(bill, "id"), "price", "CN-7c", 100, priceLine(UUID.randomUUID(), 100)))))
                .isEqualTo("validation_failed");
        // A credit dated before the invoice, or in the future.
        Map<String, Object> early = creditBody(uuid(bill, "id"), "price", "CN-7d", 100, priceLine(line, 100));
        early.put("credit_date", "2026-10-07");
        assertThat(code(creditRaw(early))).isEqualTo("validation_failed");
        Map<String, Object> future = creditBody(uuid(bill, "id"), "price", "CN-7e", 100, priceLine(line, 100));
        future.put("credit_date", "2026-10-09");
        assertThat(code(creditRaw(future))).isEqualTo("validation_failed");
        assertThat(scalar("SELECT count(*) FROM retail_supplier_credits WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    // ---- price adjustments -------------------------------------------------------------------

    @Test
    void aPriceCreditFirstCorrectsTheInvoicesVarianceThenLowersInventoryAndTheCost() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-8", 120, r);
        assertThat(balance("purchase_price_variance")).isEqualTo(200);
        // 300 off: 200 of it is the price variance the invoice posted, the other 100 comes off goods on hand.
        JsonNode credit =
                credited(creditBody(uuid(bill, "id"), "price", "CN-8", 300, priceLine(billLine(bill, 0), 300)));
        UUID id = uuid(credit, "id");
        assertThat(posted("retail.supplier_credit", id).get("trade_creditors")).containsExactly(300, 0);
        assertThat(posted("retail.supplier_credit", id).get("purchase_price_variance"))
                .containsExactly(0, 200);
        assertThat(posted("retail.supplier_credit", id).get("inventory")).containsExactly(0, 100);
        assertThat(balance("purchase_price_variance")).isZero();
        // The product's cost falls by 100 over 10 units.
        assertThat(costOf(product)).isEqualTo(90);
        JsonNode line = credit.get("lines").get(0);
        assertThat(line.get("cost_applied").asBoolean()).isTrue();
        assertThat(line.get("cost_before_minor").asLong()).isEqualTo(100);
        assertThat(line.get("cost_after_minor").asLong()).isEqualTo(90);
        assertThat(bill(uuid(bill, "id")).get("open_minor").asLong()).isEqualTo(900);
        assertBooksBalance();
    }

    @Test
    void aPriceCreditOnGoodsAlreadySoldGoesToCostOfGoodsSold() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-9", 100, r);
        assertThat(api.sell(hq, "cash", product, "4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long cogs = balance("cost_of_goods_sold");
        JsonNode credit =
                credited(creditBody(uuid(bill, "id"), "price", "CN-9", 100, priceLine(billLine(bill, 0), 100)));
        UUID id = uuid(credit, "id");
        // 6 of 10 units are on hand: 60 off inventory, 40 off cost of goods sold.
        assertThat(posted("retail.supplier_credit", id).get("inventory")).containsExactly(0, 60);
        assertThat(posted("retail.supplier_credit", id).get("cost_of_goods_sold"))
                .containsExactly(0, 40);
        assertThat(balance("cost_of_goods_sold")).isEqualTo(cogs - 40);
        // And the daily profit takes it off the cost of sales on the day of the credit note: 400 sold, 40 back.
        assertThat(costOfSalesOn(TODAY)).isEqualTo(360);
        assertThat(credit.get("lines").get(0).get("to_cogs_minor").asLong()).isEqualTo(40);
        assertBooksBalance();
    }

    @Test
    void aPriceCreditNeverOverwritesANewerCost() {
        JsonNode first = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-10", 100, first);
        receive(hq, product, "10", 120);
        JsonNode credit =
                credited(creditBody(uuid(bill, "id"), "price", "CN-10", 100, priceLine(billLine(bill, 0), 100)));
        assertThat(credit.get("lines").get(0).get("cost_applied").asBoolean()).isFalse();
        assertThat(costOf(product)).isEqualTo(120);
    }

    @Test
    void aPriceCreditIsRefusedOnALineWithGoodsSentBack() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-11", 100, r);
        credited(creditBody(uuid(bill, "id"), "goods", "CN-11a", 100, goodsLine(billLine(bill, 0), "1", null)));
        ResponseEntity<JsonNode> refused =
                creditRaw(creditBody(uuid(bill, "id"), "price", "CN-11b", 50, priceLine(billLine(bill, 0), 50)));
        assertThat(code(refused)).isEqualTo("line_returned");
    }

    @Test
    void goodsSentBackFromANewerReceiptDoNotMakeAnOlderOnesStockLookSold() {
        JsonNode older = receive(hq, product, "10", 100);
        JsonNode newer = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-F", 100, newer);
        // Four of the newer receipt go back: the branch holds 16, ten of them the older receipt's.
        credited(creditBody(uuid(bill, "id"), "goods", "CN-F", 400, goodsLine(billLine(bill, 0), "4", null)));
        assertThat(stockAt(hq, product)).isEqualByComparingTo("16");
        JsonNode doc = landed(freight("FWD-F", 100, lineId(older)));
        JsonNode line = doc.get("lines").get(0);
        assertThat(line.get("on_hand_qty").asString()).isEqualTo("10.000");
        assertThat(line.get("to_inventory_minor").asLong()).isEqualTo(100);
        assertThat(line.get("to_cogs_minor").asLong()).isZero();
        // Nothing of the older receipt moved, so its landed cost can still be voided.
        assertThat(voidLanded(uuid(doc, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertBooksBalance();
    }

    @Test
    void theDefaultCreditForGoodsSentBackFollowsWhatAPriceCreditAlreadyCorrected() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-D", 120, r);
        UUID billId = uuid(bill, "id");
        // The supplier first corrects the price by the 200 the invoice was above the receipt.
        credited(creditBody(billId, "price", "CN-D1", 200, priceLine(billLine(bill, 0), 200)));
        // Five units go back at no typed price: they are credited at the corrected 100, not at the billed 120.
        JsonNode credit = credited(creditBody(billId, "goods", "CN-D2", 500, goodsLine(billLine(bill, 0), "5", null)));
        UUID id = uuid(credit, "id");
        assertThat(posted("retail.supplier_credit", id).get("trade_creditors")).containsExactly(500, 0);
        assertThat(posted("retail.supplier_credit", id).get("inventory")).containsExactly(0, 500);
        assertThat(posted("retail.supplier_credit", id)).doesNotContainKey("purchase_price_variance");
        // 1200 billed, 200 and 500 credited: 500 is left, and it is what 5 units at 100 are worth.
        assertThat(bill(billId).get("open_minor").asLong()).isEqualTo(500);
        assertBooksBalance();
    }

    @Test
    void aReceiptWhoseUnitsWereAllSoldCannotBeSentBackOutOfALaterReceiptsStock() {
        JsonNode older = receive(hq, product, "10", 100);
        receive(hq, product, "10", 120);
        JsonNode bill = billOf("INV-OLD", 100, older);
        // First in, first out: the ten sold are the older receipt's, so the ten left are the newer one's.
        assertThat(api.sell(hq, "cash", product, "10").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> refused = creditRaw(
                creditBody(uuid(bill, "id"), "goods", "CN-OLD", 500, goodsLine(billLine(bill, 0), "5", null)));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("credit_exceeds_on_hand");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("10");
        assertThat(scalar("SELECT count(*) FROM retail_supplier_credits WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        assertBooksBalance();
    }

    @Test
    void theMovementOfGoodsSentBackCarriesWhatTheLedgerTookOutOfInventory() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-MV", 120, r);
        credited(creditBody(uuid(bill, "id"), "goods", "CN-MV", 480, goodsLine(billLine(bill, 0), "4", null)));
        // 400 left inventory for four units: 100 a unit, not the product's cost of the day.
        assertThat(api.editPrices(product, Map.of("cost_minor", 130, "reason", "Test supplier rise"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        long cost = scalar(
                "SELECT unit_cost_minor FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'return_to_supplier'",
                t.tenantId());
        assertThat(cost).isEqualTo(100);
    }

    // ---- voids -------------------------------------------------------------------------------

    @Test
    void voidingAGoodsCreditPutsTheGoodsBackAndReopensTheInvoice() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-12", 120, r);
        UUID billId = uuid(bill, "id");
        long inventory = balance("inventory");
        JsonNode credit = credited(creditBody(billId, "goods", "CN-12", 480, goodsLine(billLine(bill, 0), "4", null)));
        UUID id = uuid(credit, "id");
        // While the credit stands the invoice cannot be voided; the credit is voided first.
        ResponseEntity<JsonNode> blocked =
                keyed("/supplier-bills/" + billId + "/void", Map.of("reason", "Test wrong invoice"), PAYER);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(blocked)).isEqualTo("bill_credited");
        ResponseEntity<JsonNode> voided = voidCredit(id);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("10");
        assertThat(balance("inventory")).isEqualTo(inventory + 400 - 400);
        assertThat(bill(billId).get("open_minor").asLong()).isEqualTo(1_200);
        assertThat(bill(billId).get("credited_minor").asLong()).isZero();
        ResponseEntity<JsonNode> twice = voidCredit(id);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(twice)).isEqualTo("credit_voided");
        // The number can be entered again, and now the invoice can be voided.
        credited(creditBody(billId, "goods", "CN-12", 480, goodsLine(billLine(bill, 0), "4", null)));
        assertBooksBalance();
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_supplier_credits SET total_minor = 1 WHERE id = ?")
                        .param(id)
                        .update())
                .rootCause()
                .hasMessageContaining("only be voided");
    }

    @Test
    void voidingAPriceCreditPutsTheCostBackAndIsRefusedOnceTheGoodsAreSold() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-13", 120, r);
        JsonNode credit =
                credited(creditBody(uuid(bill, "id"), "price", "CN-13", 300, priceLine(billLine(bill, 0), 300)));
        UUID id = uuid(credit, "id");
        assertThat(costOf(product)).isEqualTo(90);
        assertThat(voidCredit(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(costOf(product)).isEqualTo(100);
        assertThat(balance("purchase_price_variance")).isEqualTo(200);
        // Again, and this time four are sold before the void.
        JsonNode second =
                credited(creditBody(uuid(bill, "id"), "price", "CN-13b", 300, priceLine(billLine(bill, 0), 300)));
        assertThat(api.sell(hq, "cash", product, "4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> blocked = voidCredit(uuid(second, "id"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(blocked)).isEqualTo("credit_stock_moved");
    }

    // ---- idempotency, scope, tenancy ---------------------------------------------------------

    @Test
    void theSameKeyRecordsOneCredit() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-14", 100, r);
        String k = key();
        Map<String, Object> body =
                creditBody(uuid(bill, "id"), "goods", "CN-14", 200, goodsLine(billLine(bill, 0), "2", null));
        ResponseEntity<JsonNode> first = api.postKeyed("/supplier-credits", body, PAYER, "*", k);
        ResponseEntity<JsonNode> second = api.postKeyed("/supplier-credits", body, PAYER, "*", k);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(scalar("SELECT count(*) FROM retail_supplier_credits WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("8");
    }

    @Test
    void aCreditAcrossTwoBranchesPostsOneEntryPerBranchAndNetsInClearing() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode b = receive(br2, product2, "10", 100);
        JsonNode bill = billOf("INV-15", 100, a, b);
        // Credit the second line, received in the other branch: the creditors are debited where the invoice sits.
        JsonNode credit =
                credited(creditBody(uuid(bill, "id"), "goods", "CN-15", 300, goodsLine(billLine(bill, 1), "3", null)));
        UUID id = uuid(credit, "id");
        assertThat(scalar("SELECT count(*) FROM retail_supplier_credit_entries WHERE credit_id = ?", id))
                .isEqualTo(2);
        assertThat(credit.get("branch_id").asString())
                .isEqualTo(bill.get("branch_id").asString());
        assertThat(balance("interbranch_clearing", hq) + balance("interbranch_clearing", br2))
                .isZero();
        assertThat(stockAt(br2, product2)).isEqualByComparingTo("7");
        assertBooksBalance();
    }

    @Test
    void aCallerWithoutTheBranchOfAReceivedLineCannotCreditItAndOthersCannotReadOrVoid() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-16", 100, r);
        Map<String, Object> body =
                creditBody(uuid(bill, "id"), "goods", "CN-16", 100, goodsLine(billLine(bill, 0), "1", null));
        // Scoped to the other branch the invoice is not even found.
        ResponseEntity<JsonNode> scoped = api.postKeyed("/supplier-credits", body, PAYER, br2.toString(), key());
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        JsonNode credit = credited(body);
        UUID id = uuid(credit, "id");
        assertThat(api.call(HttpMethod.GET, "/supplier-credits/" + id, null, PAYER, br2.toString(), Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.call(HttpMethod.GET, "/supplier-credits", null, PAYER, br2.toString(), Map.of())
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(api.postKeyed(
                                "/supplier-credits/" + id + "/void",
                                Map.of("reason", "Test no right"),
                                PAYER,
                                br2.toString(),
                                key())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/supplier-credits", BUYER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/supplier-credits", ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void costsAreAbsentFromACreditWithoutTheRightToReadThem() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-17", 120, r);
        JsonNode credit =
                credited(creditBody(uuid(bill, "id"), "goods", "CN-17", 480, goodsLine(billLine(bill, 0), "4", null)));
        JsonNode line = api.get("/supplier-credits/" + uuid(credit, "id"), NO_COSTS)
                .getBody()
                .get("lines")
                .get(0);
        assertThat(line.get("line_total_minor").asLong()).isEqualTo(480);
        for (String hidden : List.of("to_inventory_minor", "to_cogs_minor", "variance_minor", "cost_before_minor")) {
            assertThat(line.has(hidden)).as(hidden).isFalse();
        }
    }

    @Test
    void theListFiltersByInvoiceAndStatusAndSearch() {
        JsonNode r = receive(hq, product, "10", 100);
        JsonNode bill = billOf("INV-18", 100, r);
        JsonNode one =
                credited(creditBody(uuid(bill, "id"), "goods", "CN-18a", 100, goodsLine(billLine(bill, 0), "1", null)));
        JsonNode two =
                credited(creditBody(uuid(bill, "id"), "goods", "CN-18b", 100, goodsLine(billLine(bill, 0), "1", null)));
        voidCredit(uuid(two, "id"));
        assertThat(api.get("/supplier-credits", PAYER).getBody().get("items")).hasSize(2);
        assertThat(api.get("/supplier-credits?status=posted", PAYER).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/supplier-credits?bill_id=" + uuid(bill, "id"), PAYER)
                        .getBody()
                        .get("items"))
                .hasSize(2);
        assertThat(api.get("/supplier-credits?q=CN-18a", PAYER).getBody().get("items"))
                .hasSize(1);
        assertThat(uuid(
                        api.get("/supplier-credits?q=CN-18a", PAYER)
                                .getBody()
                                .get("items")
                                .get(0),
                        "id"))
                .isEqualTo(uuid(one, "id"));
        assertThat(api.get("/supplier-credits", PAYER)
                        .getBody()
                        .get("items")
                        .get(0)
                        .has("lines"))
                .isFalse();
    }
}
