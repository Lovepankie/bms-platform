package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Landed cost (ADR-033, issue #199 amendment; docs/specs/trade-capabilities.md section 7.2): the exact allocation and its
 * remainder rule, the split between inventory and cost of goods sold for goods already sold or moved, the cost update guard,
 * the payee's invoice in the supplier machinery, the voids and their guards, branch scope, cost visibility, idempotency and the
 * hold on an uncosted receipt. Every name and amount is fabricated.
 */
class RetailLandedCostIT extends LandedCostTestBase {

    // ---- the allocation --------------------------------------------------------------------

    @Test
    void byValueTheSharesAddExactlyAndTheLeftOverGoesToTheLargestRemainder() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode b = receive(hq, product2, "20", 150);
        // 1001 over 1000 and 3000: 250.25 and 750.75, so 250 and 750 and the one unit left to the larger remainder.
        JsonNode doc = landed(freight("FWD-1", 1_001, lineId(a), lineId(b)));
        List<Long> shares = new ArrayList<>();
        doc.get("lines").forEach(l -> shares.add(l.get("allocated_minor").asLong()));
        assertThat(shares).containsExactly(250L, 751L);
        assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(1_001);
        assertThat(doc.get("total_minor").asLong()).isEqualTo(1_001);
        assertThat(doc.get("method").asString()).isEqualTo("value");
        assertThat(doc.get("landed_no").asString()).isEqualTo("LCHQ-000001");
        // The same lines typed in the other order give the same shares: the result does not depend on the order typed.
        JsonNode again = landed(landedBody(
                "FWD-2",
                1_001,
                null,
                List.of(charge("freight", 1_001)),
                List.of(target(lineId(b)), target(lineId(a)))));
        List<Long> reversed = new ArrayList<>();
        again.get("lines").forEach(l -> reversed.add(l.get("allocated_minor").asLong()));
        assertThat(reversed).containsExactly(250L, 751L);
        assertBooksBalance();
    }

    @Test
    void anEqualSplitGivesTheExtraUnitsToTheEarlierLines() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode b = receive(hq, product, "10", 100);
        JsonNode c = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-3", 100, lineId(a), lineId(b), lineId(c)));
        List<Long> shares = new ArrayList<>();
        doc.get("lines").forEach(l -> shares.add(l.get("allocated_minor").asLong()));
        // 33.33 each: the one left over goes to the first line in receipt order on equal remainders and bases.
        assertThat(shares).containsExactly(34L, 33L, 33L);
    }

    @Test
    void byQuantityAndByWeightAndByTypedAmounts() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode b = receive(hq, product2, "30", 150);
        // Quantity: 10 and 30 units share 400 as 100 and 300.
        JsonNode byQty = landed(landedBody(
                "FWD-Q",
                400,
                "quantity",
                List.of(charge("freight", 300), charge("duty", 100)),
                List.of(target(lineId(a)), target(lineId(b)))));
        assertThat(byQty.get("lines").get(0).get("allocated_minor").asLong()).isEqualTo(100);
        assertThat(byQty.get("lines").get(1).get("allocated_minor").asLong()).isEqualTo(300);
        assertThat(byQty.get("charges")).hasSize(2);
        // Weight: the line weights are typed; 2.5 and 7.5 share 1000 as 250 and 750.
        JsonNode byWeight = landed(landedBody(
                "FWD-W",
                1_000,
                "weight",
                List.of(charge("freight", 1_000)),
                List.of(target(lineId(a), "2.5", null), target(lineId(b), "7.5", null))));
        assertThat(byWeight.get("lines").get(0).get("allocated_minor").asLong()).isEqualTo(250);
        assertThat(byWeight.get("lines").get(1).get("allocated_minor").asLong()).isEqualTo(750);
        assertThat(byWeight.get("lines").get(0).get("weight").asString()).isEqualTo("2.500");
        // Typed amounts: they must add to the total.
        JsonNode manual = landed(landedBody(
                "FWD-M",
                500,
                "manual",
                List.of(charge("clearing", 500)),
                List.of(target(lineId(a), null, 123L), target(lineId(b), null, 377L))));
        assertThat(manual.get("lines").get(0).get("allocated_minor").asLong()).isEqualTo(123);
        assertThat(manual.get("lines").get(1).get("allocated_minor").asLong()).isEqualTo(377);
        ResponseEntity<JsonNode> off = landedRaw(landedBody(
                "FWD-M2",
                500,
                "manual",
                List.of(charge("clearing", 500)),
                List.of(target(lineId(a), null, 100L), target(lineId(b), null, 377L))));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(off)).isEqualTo("allocation_total_mismatch");
        // A weight method without weights is refused, and so is a typed total that is not the charges.
        assertThat(code(landedRaw(landedBody(
                        "FWD-W2",
                        1_000,
                        "weight",
                        List.of(charge("freight", 1_000)),
                        List.of(target(lineId(a)), target(lineId(b)))))))
                .isEqualTo("validation_failed");
        ResponseEntity<JsonNode> typo = landedRaw(
                landedBody("FWD-T", 999, null, List.of(charge("freight", 1_000)), List.of(target(lineId(a)))));
        assertThat(code(typo)).isEqualTo("landed_total_mismatch");
        assertBooksBalance();
    }

    @Test
    void goodsReceivedAtNoCostCannotBeSpreadByValue() {
        JsonNode free = receive(hq, product, "5", 0);
        ResponseEntity<JsonNode> r = landedRaw(freight("FWD-Z", 100, lineId(free)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("allocation_basis_zero");
        // By quantity it can.
        JsonNode ok = landed(
                landedBody("FWD-Z2", 100, "quantity", List.of(charge("freight", 100)), List.of(target(lineId(free)))));
        assertThat(ok.get("lines").get(0).get("allocated_minor").asLong()).isEqualTo(100);
    }

    @Test
    void thePreviewShowsTheSpreadAndTheEffectAndWritesNothing() {
        JsonNode a = receive(hq, product, "10", 100);
        long journals = scalar("SELECT count(*) FROM journal_entries WHERE tenant_id = ?", t.tenantId());
        ResponseEntity<JsonNode> r = api.post("/landed-costs/preview", freight("FWD-P", 1_000, lineId(a)), PAYER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode line = r.getBody().get("lines").get(0);
        assertThat(line.get("allocated_minor").asLong()).isEqualTo(1_000);
        assertThat(line.get("to_inventory_minor").asLong()).isEqualTo(1_000);
        assertThat(line.get("to_cogs_minor").asLong()).isZero();
        assertThat(line.get("cost_before_minor").asLong()).isEqualTo(100);
        assertThat(line.get("cost_after_minor").asLong()).isEqualTo(200);
        assertThat(r.getBody().get("total_minor").asLong()).isEqualTo(1_000);
        assertThat(scalar("SELECT count(*) FROM journal_entries WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(journals);
        assertThat(scalar("SELECT count(*) FROM retail_landed_costs WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        assertThat(costOf(product)).isEqualTo(100);
    }

    // ---- the books and the product's cost ---------------------------------------------------

    @Test
    void goodsOnHandTakeTheCostIntoInventoryAndTheProductCostRises() {
        JsonNode a = receive(hq, product, "10", 100);
        long inventory = balance("inventory");
        JsonNode doc = landed(freight("FWD-A", 500, lineId(a)));
        UUID id = uuid(doc, "id");
        // Debit inventory, credit trade creditors with the payee as the subledger; nothing to cost of goods sold.
        assertThat(flat(posted("retail.landed_cost", id))).containsOnlyKeys("inventory", "trade_creditors");
        assertThat(posted("retail.landed_cost", id).get("inventory")).containsExactly(500, 0);
        assertThat(posted("retail.landed_cost", id).get("trade_creditors")).containsExactly(0, 500);
        assertThat(balance("inventory")).isEqualTo(inventory + 500);
        assertThat(balance("trade_creditors")).isEqualTo(-500);
        // 500 over 10 units is 50 a unit: the cost rises from 100 to 150, with a history row naming the document.
        assertThat(costOf(product)).isEqualTo(150);
        JsonNode line = doc.get("lines").get(0);
        assertThat(line.get("cost_applied").asBoolean()).isTrue();
        assertThat(line.get("cost_before_minor").asLong()).isEqualTo(100);
        assertThat(line.get("cost_after_minor").asLong()).isEqualTo(150);
        assertThat(line.get("on_hand_qty").asString()).isEqualTo("10.000");
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.get("new_cost_minor").asLong()).isEqualTo(150);
        assertThat(last.get("source").asString()).isEqualTo("purchase");
        assertThat(last.get("source_id").asString()).isEqualTo(id.toString());
        assertThat(last.get("reason").asString()).contains("LCHQ-000001").doesNotContain("per unit");
        // The receipt shows what was added to its line.
        JsonNode receipt = api.get("/goods-receipts/" + uuid(a, "id"), PAYER).getBody();
        assertThat(receipt.get("lines").get(0).get("landed_minor").asLong()).isEqualTo(500);
        assertBooksBalance();
    }

    @Test
    void aSecondLandedCostOnTheSameReceiptStillMovesTheCost() {
        JsonNode a = receive(hq, product, "10", 100);
        landed(freight("FWD-A1", 500, lineId(a)));
        assertThat(costOf(product)).isEqualTo(150);
        // Our own earlier history row is not a newer cost event.
        JsonNode second = landed(freight("FWD-A2", 100, lineId(a)));
        assertThat(second.get("lines").get(0).get("cost_applied").asBoolean()).isTrue();
        assertThat(costOf(product)).isEqualTo(160);
    }

    @Test
    void aNewerReceiptIsNeverOverwrittenByALateLandedCost() {
        JsonNode first = receive(hq, product, "10", 100);
        receive(hq, product, "10", 120);
        assertThat(costOf(product)).isEqualTo(120);
        int version =
                api.get("/products/" + product, ADMIN).getBody().get("version").asInt();
        JsonNode doc = landed(freight("FWD-L", 500, lineId(first)));
        JsonNode line = doc.get("lines").get(0);
        // A note in the history is not an edit of the product: its version stays, so nobody editing it is told it
        // changed.
        assertThat(api.get("/products/" + product, ADMIN)
                        .getBody()
                        .get("version")
                        .asInt())
                .isEqualTo(version);
        // The newer receipt set the cost: the landed cost is posted, the cost is not moved, and a history row says so.
        assertThat(line.get("cost_applied").asBoolean()).isFalse();
        assertThat(line.get("cost_note").asString()).contains("newer");
        assertThat(costOf(product)).isEqualTo(120);
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.get("new_cost_minor").asLong()).isEqualTo(120);
        assertThat(last.get("old_cost_minor").asLong()).isEqualTo(120);
        assertThat(last.get("reason").asString()).contains("cost not applied");
        // 20 units are on hand: the first receipt's 10 are the oldest, so by first in first out all of them are still
        // there.
        assertThat(line.get("on_hand_qty").asString()).isEqualTo("10.000");
        assertThat(balance("inventory")).isEqualTo(1_000 + 1_200 + 500);
    }

    @Test
    void aManualPriceChangeAfterTheReceiptAlsoStopsTheCostFromMoving() {
        JsonNode a = receive(hq, product, "10", 100);
        assertThat(api.editPrices(product, Map.of("cost_minor", 130, "reason", "Test supplier rise"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode doc = landed(freight("FWD-MAN", 500, lineId(a)));
        assertThat(doc.get("lines").get(0).get("cost_applied").asBoolean()).isFalse();
        assertThat(costOf(product)).isEqualTo(130);
    }

    @Test
    void goodsAlreadySoldTakeTheirShareToCostOfGoodsSold() {
        JsonNode a = receive(hq, product, "10", 100);
        assertThat(api.sell(hq, "cash", product, "4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long cogsBefore = balance("cost_of_goods_sold");
        long inventoryBefore = balance("inventory");
        JsonNode doc = landed(freight("FWD-S", 100, lineId(a)));
        UUID id = uuid(doc, "id");
        // 6 of 10 units are on hand: 60 to inventory and 40 to cost of goods sold.
        JsonNode line = doc.get("lines").get(0);
        assertThat(line.get("on_hand_qty").asString()).isEqualTo("6.000");
        assertThat(line.get("to_inventory_minor").asLong()).isEqualTo(60);
        assertThat(line.get("to_cogs_minor").asLong()).isEqualTo(40);
        assertThat(posted("retail.landed_cost", id).get("inventory")).containsExactly(60, 0);
        assertThat(posted("retail.landed_cost", id).get("cost_of_goods_sold")).containsExactly(40, 0);
        assertThat(posted("retail.landed_cost", id).get("trade_creditors")).containsExactly(0, 100);
        assertThat(balance("cost_of_goods_sold")).isEqualTo(cogsBefore + 40);
        assertThat(balance("inventory")).isEqualTo(inventoryBefore + 60);
        // The product's cost rises by the charge per unit for what is still sold from now on.
        assertThat(costOf(product)).isEqualTo(110);
        assertBooksBalance();
    }

    @Test
    void thePartForGoodsAlreadySoldReachesTheDailyProfitOnTheDayOfTheChargeAndLeavesWithTheVoid() {
        JsonNode a = receive(hq, product, "10", 100);
        assertThat(api.sell(hq, "cash", product, "4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(costOfSalesOn(TODAY)).isEqualTo(400);
        JsonNode doc = landed(freight("FWD-PR", 100, lineId(a)));
        // 40 of the 100 belongs to the four sold: it is a cost of sales on the day of the charge.
        assertThat(costOfSalesOn(TODAY)).isEqualTo(440);
        assertThat(voidLanded(uuid(doc, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(costOfSalesOn(TODAY)).isEqualTo(400);
    }

    @Test
    void goodsMovedToAnotherBranchAreCapitalisedThereAndTheValuationEqualsTheLedger() {
        JsonNode a = receive(hq, product, "10", 100);
        assertThat(transfer(hq, br2, product, "3").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode doc = landed(freight("FWD-T3", 100, lineId(a)));
        JsonNode line = doc.get("lines").get(0);
        // All 10 units are still on hand, 7 in the receiving branch and 3 in the other: the charge is 10 a unit and
        // every unit
        // takes it where it is, so nothing is expensed.
        assertThat(line.get("on_hand_qty").asString()).isEqualTo("10.000");
        assertThat(line.get("to_inventory_minor").asLong()).isEqualTo(100);
        assertThat(line.get("to_cogs_minor").asLong()).isZero();
        assertThat(balance("inventory", hq)).isEqualTo(770);
        assertThat(balance("inventory", br2)).isEqualTo(330);
        assertThat(costOf(product)).isEqualTo(110);
        // The stock valuation (what each branch holds times the cost) is what the ledger says, branch by branch.
        assertThat(valuation(hq, product)).isEqualTo(balance("inventory", hq));
        assertThat(valuation(br2, product)).isEqualTo(balance("inventory", br2));
        assertBooksBalance();
    }

    @Test
    void aChargeUnderOneMinorUnitAUnitIsExpensedAndTheCostDoesNotMove() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-TINY", 7, lineId(a)));
        JsonNode line = doc.get("lines").get(0);
        assertThat(line.get("to_inventory_minor").asLong()).isZero();
        assertThat(line.get("to_cogs_minor").asLong()).isEqualTo(7);
        assertThat(line.get("cost_applied").asBoolean()).isFalse();
        assertThat(costOf(product)).isEqualTo(100);
        assertThat(valuation(hq, product)).isEqualTo(balance("inventory", hq));
    }

    @Test
    void theCostChangeIsRoundedDownSoNoMoreIsCapitalisedThanWasCharged() {
        JsonNode a = receive(hq, product, "3", 100);
        // 100 over 3 units is 33.33 a unit: 33 a unit is capitalised (99) and the 1 left is expensed.
        JsonNode doc = landed(freight("FWD-R3", 100, lineId(a)));
        JsonNode line = doc.get("lines").get(0);
        assertThat(line.get("to_inventory_minor").asLong()).isEqualTo(99);
        assertThat(line.get("to_cogs_minor").asLong()).isEqualTo(1);
        assertThat(costOf(product)).isEqualTo(133);
        assertThat(valuation(hq, product)).isEqualTo(balance("inventory", hq));
    }

    @Test
    void landedCostsVoidedInEitherOrderEachTakeBackOnlyTheirOwnChangeOfTheCost() {
        long expected = 0;
        for (boolean firstFirst : new boolean[] {true, false}) {
            expected += 1_000;
            UUID prod = api.product(firstFirst ? "LC-V1" : "LC-V2", 100, 400);
            JsonNode a = receive(hq, prod, "10", 100);
            JsonNode one = landed(freight("FWD-O1" + firstFirst, 500, lineId(a)));
            JsonNode two = landed(freight("FWD-O2" + firstFirst, 100, lineId(a)));
            assertThat(costOf(prod)).isEqualTo(160);
            UUID first = uuid(firstFirst ? one : two, "id");
            UUID second = uuid(firstFirst ? two : one, "id");
            assertThat(voidLanded(first).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(costOf(prod)).isEqualTo(firstFirst ? 110 : 150);
            assertThat(voidLanded(second).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(costOf(prod)).isEqualTo(100);
            assertThat(balance("inventory", hq)).isEqualTo(expected);
        }
        assertBooksBalance();
    }

    @Test
    void firstInFirstOutDecidesWhichReceiptsStockIsLeft() {
        JsonNode oldest = receive(hq, product, "10", 100);
        JsonNode newest = receive(hq, product, "10", 120);
        assertThat(api.sell(hq, "cash", product, "12").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 8 units are left and they are the newest receipt's: the oldest is gone, the newest has 8 of 10.
        JsonNode doc = landed(landedBody(
                "FWD-F",
                200,
                "quantity",
                List.of(charge("freight", 200)),
                List.of(target(lineId(oldest)), target(lineId(newest)))));
        JsonNode first = doc.get("lines").get(0);
        JsonNode second = doc.get("lines").get(1);
        assertThat(first.get("receipt_no").asString())
                .isEqualTo(oldest.get("receipt_no").asString());
        assertThat(first.get("on_hand_qty").asString()).isEqualTo("0.000");
        assertThat(first.get("to_inventory_minor").asLong()).isZero();
        assertThat(first.get("to_cogs_minor").asLong()).isEqualTo(100);
        assertThat(second.get("on_hand_qty").asString()).isEqualTo("8.000");
        assertThat(second.get("to_inventory_minor").asLong()).isEqualTo(80);
        assertThat(second.get("to_cogs_minor").asLong()).isEqualTo(20);
        assertBooksBalance();
    }

    @Test
    void chargesOverReceiptsInTwoBranchesPostOneEntryPerBranchAndNetInClearing() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode b = receive(br2, product2, "10", 100);
        JsonNode doc = landed(freight("FWD-2B", 1_000, lineId(a), lineId(b)));
        UUID id = uuid(doc, "id");
        assertThat(doc.get("branch_id").asString()).isIn(hq.toString(), br2.toString());
        UUID home = uuid(doc, "branch_id");
        UUID other = home.equals(hq) ? br2 : hq;
        // The whole payable sits in the document's branch; the other branch debits inventory against clearing.
        assertThat(balance("trade_creditors", home)).isEqualTo(-1_000);
        assertThat(balance("trade_creditors", other)).isZero();
        assertThat(balance("interbranch_clearing", home) + balance("interbranch_clearing", other))
                .isZero();
        assertThat(scalar("SELECT count(*) FROM retail_landed_cost_entries WHERE landed_cost_id = ?", id))
                .isEqualTo(2);
        assertBooksBalance();
    }

    // ---- the payee's invoice and its payment ----------------------------------------------

    @Test
    void thePayeesInvoiceIsASupplierBillOfKindLandedCostThatIsPaidAndAgedLikeAnyOther() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-PAY", 500, lineId(a)));
        UUID bill = uuid(doc, "bill_id");
        JsonNode b = api.get("/supplier-bills/" + bill, PAYER).getBody();
        assertThat(b.get("kind").asString()).isEqualTo("landed_cost");
        assertThat(b.get("total_minor").asLong()).isEqualTo(500);
        assertThat(b.get("open_minor").asLong()).isEqualTo(500);
        assertThat(b.get("supplier_id").asString()).isEqualTo(forwarder.toString());
        assertThat(b.get("due_date").asString()).isEqualTo(DUE);
        // It is among the forwarder's open items and in the payables report.
        JsonNode open =
                api.get("/suppliers/" + forwarder + "/open-items", PAYER).getBody();
        assertThat(open.get("items")).hasSize(1);
        assertThat(open.get("total_open_minor").asLong()).isEqualTo(500);
        JsonNode report = api.get("/reports/supplier-payables", PAYER).getBody();
        assertThat(report.get("totals").get("owed_minor").asLong()).isEqualTo(500);
        // Pay it part by part with a supplier payment.
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("supplier_id", forwarder);
        pay.put("branch_id", hq);
        pay.put("method", "bank");
        pay.put("amount_minor", 200);
        ResponseEntity<JsonNode> paid = keyed("/supplier-payments", pay, PAYER);
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.get("/supplier-bills/" + bill, PAYER)
                        .getBody()
                        .get("open_minor")
                        .asLong())
                .isEqualTo(300);
        assertThat(landedCost(uuid(doc, "id")).get("paid_minor").asLong()).isEqualTo(200);
        assertThat(balance("trade_creditors")).isEqualTo(-300);
        // The invoice cannot be voided from the invoice screen: it belongs to the landed cost.
        ResponseEntity<JsonNode> direct =
                keyed("/supplier-bills/" + bill + "/void", Map.of("reason", "Test wrong invoice"), PAYER);
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(direct)).isEqualTo("bill_is_landed_cost");
    }

    @Test
    void theSameInvoiceNumberOfThePayeeIsRefusedWhileItStands() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-DUP", 500, lineId(a)));
        ResponseEntity<JsonNode> again = landedRaw(freight("fwd-dup", 500, lineId(a)));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("duplicate_supplier_bill");
        assertThat(voidLanded(uuid(doc, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Voided, the number can be entered again.
        landed(freight("FWD-DUP", 500, lineId(a)));
    }

    @Test
    void theChargesCanBelongToAGoodsInvoiceAndEveryLineMustBeBilledByIt() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode b = receive(hq, product2, "10", 150);
        Map<String, Object> billLine = new LinkedHashMap<>();
        billLine.put("receipt_line_id", lineId(a));
        billLine.put("qty", "10");
        billLine.put("unit_price_minor", 100);
        Map<String, Object> bill = new LinkedHashMap<>();
        bill.put("supplier_id", supplier);
        bill.put("supplier_ref", "INV-G1");
        bill.put("bill_date", TODAY);
        bill.put("due_date", DUE);
        bill.put("total_minor", 1_000);
        bill.put("lines", List.of(billLine));
        ResponseEntity<JsonNode> billed = keyed("/supplier-bills", bill, PAYER);
        assertThat(billed.getStatusCode()).as("%s", billed.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID goodsBill = uuid(billed.getBody(), "id");
        Map<String, Object> body = freight("FWD-GB", 500, lineId(a));
        body.put("goods_bill_id", goodsBill);
        JsonNode ok = landed(body);
        assertThat(ok.get("goods_bill_id").asString()).isEqualTo(goodsBill.toString());
        // The invoice cannot be voided while a landed cost names it.
        ResponseEntity<JsonNode> blocked =
                keyed("/supplier-bills/" + goodsBill + "/void", Map.of("reason", "Test wrong invoice"), PAYER);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(blocked)).isEqualTo("bill_costed");
        assertThat(voidLanded(uuid(ok, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(keyed("/supplier-bills/" + goodsBill + "/void", Map.of("reason", "Test wrong invoice"), PAYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> wrong = freight("FWD-GB2", 500, lineId(b));
        wrong.put("goods_bill_id", goodsBill);
        ResponseEntity<JsonNode> r = landedRaw(wrong);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("not_on_bill");
    }

    // ---- validation ------------------------------------------------------------------------

    @Test
    void aLandedCostIsValidatedAgainstItsLinesDatesAndPayee() {
        JsonNode a = receive(hq, product, "10", 100);
        // An unknown line, a duplicate line, a date in the future and a due date before the charge.
        assertThat(code(landedRaw(freight("V-1", 100, UUID.randomUUID())))).isEqualTo("validation_failed");
        assertThat(code(landedRaw(freight("V-2", 100, lineId(a), lineId(a))))).isEqualTo("validation_failed");
        Map<String, Object> future = freight("V-3", 100, lineId(a));
        future.put("cost_date", "2026-10-09");
        assertThat(code(landedRaw(future))).isEqualTo("validation_failed");
        Map<String, Object> early = freight("V-4", 100, lineId(a));
        early.put("due_date", "2026-10-07");
        assertThat(code(landedRaw(early))).isEqualTo("validation_failed");
        Map<String, Object> noPayee = freight("V-5", 100, lineId(a));
        noPayee.put("supplier_id", UUID.randomUUID());
        assertThat(code(landedRaw(noPayee))).isEqualTo("validation_failed");
        // A charge dated before the goods were received.
        JsonNode late = receive(hq, product2, "5", 150);
        TestDatabase.owner()
                .sql("ALTER TABLE retail_goods_receipts DISABLE TRIGGER retail_goods_receipts_guard_update")
                .update();
        TestDatabase.owner()
                .sql("UPDATE retail_goods_receipts SET received_on = DATE '2026-10-08' WHERE id = ?")
                .param(uuid(late, "id"))
                .update();
        TestDatabase.owner()
                .sql("ALTER TABLE retail_goods_receipts ENABLE TRIGGER retail_goods_receipts_guard_update")
                .update();
        Map<String, Object> before = freight("V-6", 100, lineId(late));
        before.put("cost_date", "2026-10-07");
        before.put("due_date", DUE);
        ResponseEntity<JsonNode> r = landedRaw(before);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("cost_before_receipt");
        // Nothing was recorded by any of the refusals.
        assertThat(scalar("SELECT count(*) FROM retail_landed_costs WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        assertThat(costOf(product)).isEqualTo(100);
    }

    @Test
    void aVoidedReceiptCannotBeCostedAndACostedReceiptCannotBeVoided() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode gone = receive(hq, product2, "5", 150);
        assertThat(keyed("/goods-receipts/" + uuid(gone, "id") + "/void", Map.of("reason", "Test wrong goods"), BUYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(code(landedRaw(freight("V-7", 100, lineId(gone))))).isEqualTo("validation_failed");
        JsonNode doc = landed(freight("V-8", 100, lineId(a)));
        ResponseEntity<JsonNode> blocked =
                keyed("/goods-receipts/" + uuid(a, "id") + "/void", Map.of("reason", "Test wrong goods"), BUYER);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(blocked)).isEqualTo("receipt_costed");
        assertThat(voidLanded(uuid(doc, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(keyed("/goods-receipts/" + uuid(a, "id") + "/void", Map.of("reason", "Test wrong goods"), BUYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---- voids -----------------------------------------------------------------------------

    @Test
    void voidingReversesTheEntriesPutsTheCostBackAndVoidsTheInvoice() {
        JsonNode a = receive(hq, product, "10", 100);
        long inventory = balance("inventory");
        JsonNode doc = landed(freight("FWD-V", 500, lineId(a)));
        UUID id = uuid(doc, "id");
        assertThat(costOf(product)).isEqualTo(150);
        ResponseEntity<JsonNode> voided = voidLanded(id);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        assertThat(voided.getBody().get("void_reason").asString()).isEqualTo("Test wrong invoice");
        assertThat(balance("inventory")).isEqualTo(inventory);
        assertThat(balance("trade_creditors")).isZero();
        assertThat(costOf(product)).isEqualTo(100);
        assertThat(api.get("/supplier-bills/" + uuid(doc, "bill_id"), PAYER)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("voided");
        // Once only.
        ResponseEntity<JsonNode> twice = voidLanded(id);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(twice)).isEqualTo("landed_cost_voided");
        // A voided landed cost no longer counts for the receipt.
        assertThat(api.get("/goods-receipts/" + uuid(a, "id"), PAYER)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("landed_minor")
                        .asLong())
                .isZero();
        assertBooksBalance();
        // The rows are append-only: nothing but the void changes a landed cost.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_landed_costs SET total_minor = 1 WHERE id = ?")
                        .param(id)
                        .update())
                .rootCause()
                .hasMessageContaining("only be voided");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM retail_landed_cost_lines WHERE landed_cost_id = ?")
                        .param(id)
                        .update())
                .isInstanceOf(Exception.class);
    }

    @Test
    void aVoidIsRefusedWhileAPaymentStandsAndWorksOnceThePaymentIsReversed() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-VP", 500, lineId(a)));
        UUID id = uuid(doc, "id");
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("supplier_id", forwarder);
        pay.put("branch_id", hq);
        pay.put("method", "bank");
        pay.put("amount_minor", 500);
        JsonNode payment = keyed("/supplier-payments", pay, PAYER).getBody();
        ResponseEntity<JsonNode> blocked = voidLanded(id);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(blocked)).isEqualTo("bill_paid");
        assertThat(keyed(
                                "/supplier-payments/" + uuid(payment, "id") + "/reverse",
                                Map.of("reason", "Test wrong payment"),
                                PAYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(voidLanded(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aVoidIsRefusedWhenTheGoodsItWasAddedToHaveBeenSold() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-VS", 500, lineId(a)));
        assertThat(api.sell(hq, "cash", product, "4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> blocked = voidLanded(uuid(doc, "id"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(blocked)).isEqualTo("landed_cost_stock_moved");
        assertThat(landedCost(uuid(doc, "id")).get("status").asString()).isEqualTo("posted");
    }

    @Test
    void aVoidLeavesANewerCostAlone() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-VC", 500, lineId(a)));
        assertThat(costOf(product)).isEqualTo(150);
        assertThat(api.editPrices(product, Map.of("cost_minor", 170, "reason", "Test supplier rise"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(voidLanded(uuid(doc, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(costOf(product)).isEqualTo(170);
    }

    @Test
    void aVoidLeavesANewerReceiptsCostAloneEvenWhenItIsTheSameNumber() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-VE", 100, lineId(a)));
        assertThat(costOf(product)).isEqualTo(110);
        // A newer receipt arrives at the very cost the landed cost made: it, not the document, is the latest cost
        // event.
        receive(hq, product, "10", 110);
        assertThat(voidLanded(uuid(doc, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(costOf(product)).isEqualTo(110);
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        assertThat(history.get(history.size() - 1).get("reason").asString()).contains("not put back");
    }

    // ---- idempotency, scope, visibility, tenancy ---------------------------------------------

    @Test
    void theSameKeyRecordsOneLandedCost() {
        JsonNode a = receive(hq, product, "10", 100);
        String k = key();
        Map<String, Object> body = freight("FWD-K", 500, lineId(a));
        ResponseEntity<JsonNode> first = api.postKeyed("/landed-costs", body, PAYER, "*", k);
        ResponseEntity<JsonNode> second = api.postKeyed("/landed-costs", body, PAYER, "*", k);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());
        assertThat(scalar("SELECT count(*) FROM retail_landed_costs WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        assertThat(costOf(product)).isEqualTo(150);
    }

    @Test
    void aCallerScopedToOneBranchCannotCostAnotherBranchsGoods() {
        JsonNode a = receive(hq, product, "10", 100);
        ResponseEntity<JsonNode> r =
                api.postKeyed("/landed-costs", freight("FWD-SC", 500, lineId(a)), PAYER, br2.toString(), key());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("validation_failed");
        JsonNode doc = landed(freight("FWD-SC2", 500, lineId(a)));
        // Nor read it, list it or void it.
        UUID id = uuid(doc, "id");
        assertThat(api.call(HttpMethod.GET, "/landed-costs/" + id, null, PAYER, br2.toString(), Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.call(HttpMethod.GET, "/landed-costs", null, PAYER, br2.toString(), Map.of())
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(api.postKeyed(
                                "/landed-costs/" + id + "/void",
                                Map.of("reason", "Test no right"),
                                PAYER,
                                br2.toString(),
                                key())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // And the permission is the administrator's.
        assertThat(api.get("/landed-costs", BUYER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/landed-costs", RetailTestSupport.SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void costsAreAbsentWithoutTheRightToReadThem() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-CV", 500, lineId(a)));
        JsonNode seen = api.get("/landed-costs/" + uuid(doc, "id"), NO_COSTS).getBody();
        JsonNode line = seen.get("lines").get(0);
        for (String hidden : List.of(
                "allocated_minor",
                "basis",
                "to_inventory_minor",
                "to_cogs_minor",
                "cost_before_minor",
                "cost_after_minor",
                "on_hand_qty")) {
            assertThat(line.has(hidden)).as(hidden).isFalse();
        }
        JsonNode receipt = api.get("/goods-receipts/" + uuid(a, "id"), NO_COSTS).getBody();
        assertThat(receipt.get("lines").get(0).has("landed_minor")).isFalse();
        assertThat(api.get("/goods-receipts/" + uuid(a, "id"), PAYER)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("landed_minor")
                        .asLong())
                .isEqualTo(500);
    }

    @Test
    void anotherTenantSeesNothing() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-RLS", 500, lineId(a)));
        TestDatabase.Fixture other = TestDatabase.tenant("retail-landed-b", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        String etag =
                theirs.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        theirs.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("purchasing", true),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        assertThat(theirs.get("/landed-costs/" + uuid(doc, "id"), PAYER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.get("/landed-costs", PAYER).getBody().get("items")).isEmpty();
    }

    @Test
    void withTheCapabilityOffEveryRouteIsNotFound() {
        capability("purchasing", false);
        for (String path : List.of("/landed-costs", "/supplier-credits")) {
            ResponseEntity<JsonNode> r = api.get(path, PAYER);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(code(r)).as(path).isEqualTo("capability_not_enabled");
        }
        ResponseEntity<JsonNode> create = landedRaw(freight("OFF", 100, UUID.randomUUID()));
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(create)).isEqualTo("capability_not_enabled");
    }

    @Test
    void theListFiltersByReceiptAndSupplierAndStatus() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode b = receive(hq, product2, "10", 150);
        JsonNode one = landed(freight("FWD-LA", 100, lineId(a)));
        JsonNode two = landed(freight("FWD-LB", 100, lineId(b)));
        voidLanded(uuid(two, "id"));
        assertThat(api.get("/landed-costs", PAYER).getBody().get("items")).hasSize(2);
        assertThat(api.get("/landed-costs?receipt_id=" + uuid(a, "id"), PAYER)
                        .getBody()
                        .get("items"))
                .hasSize(1);
        assertThat(api.get("/landed-costs?status=voided", PAYER).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/landed-costs?supplier_id=" + forwarder, PAYER)
                        .getBody()
                        .get("items"))
                .hasSize(2);
        assertThat(api.get("/landed-costs?supplier_id=" + supplier, PAYER)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(api.get("/landed-costs?q=FWD-LA", PAYER).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/landed-costs", PAYER).getBody().get("items").get(0).has("lines"))
                .isFalse();
        assertThat(uuid(
                        api.get("/landed-costs?q=FWD-LA", PAYER)
                                .getBody()
                                .get("items")
                                .get(0),
                        "id"))
                .isEqualTo(uuid(one, "id"));
    }

    // ---- concurrency ------------------------------------------------------------------------

    @Test
    void sixLandedCostsPostedAtOnceOnOneReceiptLineAllCountAndTheBooksBalance() throws Exception {
        JsonNode a = receive(hq, product, "10", 100);
        UUID line = lineId(a);
        long inventory = balance("inventory");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < 6; i++) {
                String ref = "FWD-CC-" + i;
                results.add(pool.submit(() -> {
                    go.await();
                    return landedRaw(freight(ref, 100, line));
                }));
            }
            go.countDown();
            for (Future<ResponseEntity<JsonNode>> f : results) {
                assertThat(f.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(scalar("SELECT count(*) FROM retail_landed_costs WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(6);
        assertThat(balance("inventory")).isEqualTo(inventory + 600);
        assertThat(balance("trade_creditors")).isEqualTo(-600);
        // Each charge is 10 a unit and each one was the receipt's latest cost event when it was recorded.
        assertThat(costOf(product)).isEqualTo(160);
        assertThat(api.get("/goods-receipts/" + uuid(a, "id"), PAYER)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("landed_minor")
                        .asLong())
                .isEqualTo(600);
        assertBooksBalance();
    }

    @Test
    void aLandedCostAndTheVoidOfItsReceiptAtOnceNeverBothStand() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                JsonNode a = receive(hq, product, "5", 100);
                UUID line = lineId(a);
                UUID receipt = uuid(a, "id");
                String ref = "FWD-RACE-" + round;
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<JsonNode>> cost = pool.submit(() -> {
                    go.await();
                    return landedRaw(freight(ref, 100, line));
                });
                Future<ResponseEntity<JsonNode>> undone = pool.submit(() -> {
                    go.await();
                    return keyed("/goods-receipts/" + receipt + "/void", Map.of("reason", "Test wrong goods"), BUYER);
                });
                go.countDown();
                int costStatus = cost.get(60, TimeUnit.SECONDS).getStatusCode().value();
                int voidStatus =
                        undone.get(60, TimeUnit.SECONDS).getStatusCode().value();
                boolean costed = scalar(
                                "SELECT count(*) FROM retail_landed_cost_lines l JOIN retail_landed_costs d ON d.id = l.landed_cost_id"
                                        + " AND d.status = 'posted' WHERE l.receipt_line_id = ?",
                                line)
                        > 0;
                boolean gone =
                        scalar("SELECT count(*) FROM retail_goods_receipts WHERE id = ? AND status = 'voided'", receipt)
                                > 0;
                assertThat(costed && gone)
                        .as("round %d: costed %d, void %d", round, costStatus, voidStatus)
                        .isFalse();
                assertThat(costed || gone).as("round %d", round).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertBooksBalance();
    }

    @Test
    void aLandedCostAndASaleOnTheSameLotAtOnceLeaveTheValuationEqualToTheLedger() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                UUID prod = api.product("LC-S" + round, 100, 400);
                JsonNode a = receive(hq, prod, "10", 100);
                UUID line = lineId(a);
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<JsonNode>> cost = pool.submit(() -> {
                    go.await();
                    return landedRaw(freight("FWD-CS-" + line, 100, line));
                });
                Future<ResponseEntity<JsonNode>> sale = pool.submit(() -> {
                    go.await();
                    return api.sell(hq, "cash", prod, "4");
                });
                go.countDown();
                JsonNode doc = cost.get(60, TimeUnit.SECONDS).getBody();
                assertThat(sale.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
                JsonNode l = doc.get("lines").get(0);
                // Either order: all 10 units were on hand (100 to inventory) or the 4 were already sold (60 and 40).
                long onHand = new java.math.BigDecimal(l.get("on_hand_qty").asString()).longValue();
                assertThat(l.get("to_inventory_minor").asLong()).isEqualTo(onHand * 10);
                assertThat(l.get("to_inventory_minor").asLong()
                                + l.get("to_cogs_minor").asLong())
                        .isEqualTo(100);
                assertThat(costOf(prod)).isEqualTo(110);
                assertThat(stockAt(hq, prod)).isEqualByComparingTo("6");
                // The six left are worth six times the cost now, in the stock and in the ledger.
                assertThat(valuation(hq, prod)).isEqualTo(660);
            }
        } finally {
            pool.shutdownNow();
        }
        assertBooksBalance();
    }

    @Test
    void aLandedCostAndATransferOfTheSameLotAtOnceLeaveTheTwoBranchesValuedAtTheLedger() throws Exception {
        List<UUID> prods = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                UUID prod = api.product("LC-X" + round, 100, 400);
                prods.add(prod);
                JsonNode a = receive(hq, prod, "10", 100);
                UUID line = lineId(a);
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<JsonNode>> cost = pool.submit(() -> {
                    go.await();
                    return landedRaw(freight("FWD-CX-" + line, 100, line));
                });
                Future<ResponseEntity<JsonNode>> moved = pool.submit(() -> {
                    go.await();
                    return transfer(hq, br2, prod, "3");
                });
                go.countDown();
                assertThat(cost.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(moved.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(costOf(prod)).isEqualTo(110);
            }
        } finally {
            pool.shutdownNow();
        }
        // Whichever came first, the two branches together are valued at what their inventory accounts say. (A transfer
        // reads
        // the product's cost before it takes the balance locks, so one that overlaps a change of the cost can carry the
        // older cost between the branches; the total does not depend on it.)
        assertThat(balance("inventory", hq) + balance("inventory", br2))
                .isEqualTo(prods.stream()
                        .mapToLong(prod -> valuation(hq, prod) + valuation(br2, prod))
                        .sum());
        assertBooksBalance();
    }

    @Test
    void goodsSoldAndThenReturnedByACustomerCountAsOnHandAgain() {
        capability("returns", true);
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode sale = api.sell(hq, "cash", product, "4").getBody();
        UUID saleLine = uuid(
                api.get("/sales/" + uuid(sale, "id"), ADMIN)
                        .getBody()
                        .get("lines")
                        .get(0),
                "id");
        Map<String, Object> back = new LinkedHashMap<>();
        back.put("sale_line_id", saleLine);
        back.put("qty", "4");
        back.put("reason", "defective");
        back.put("disposition", "restock");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(back));
        body.put("refund_method", "cash");
        ResponseEntity<JsonNode> returned = api.postKeyed(
                "/sales/" + uuid(sale, "id") + "/returns",
                body,
                ADMIN + ",retail.sale.return,retail.cashbook.read",
                "*",
                key());
        assertThat(returned.getStatusCode()).as("%s", returned.getBody()).isEqualTo(HttpStatus.CREATED);
        // The four came back to the shelf: the ten are on hand again and the charge goes wholly to inventory.
        JsonNode line = landed(freight("FWD-RET", 100, lineId(a))).get("lines").get(0);
        assertThat(line.get("on_hand_qty").asString()).isEqualTo("10.000");
        assertThat(line.get("to_cogs_minor").asLong()).isZero();
    }

    @Test
    void aReaderScopedToOneBranchSeesOnlyThatBranchsLinesOfALandedCost() {
        JsonNode a = receive(hq, product, "10", 100);
        JsonNode b = receive(br2, product2, "10", 100);
        JsonNode doc = landed(freight("FWD-SCOPE", 1_000, lineId(a), lineId(b)));
        UUID home = uuid(doc, "branch_id");
        UUID other = home.equals(hq) ? br2 : hq;
        JsonNode seen = api.call(
                        HttpMethod.GET, "/landed-costs/" + uuid(doc, "id"), null, PAYER, home.toString(), Map.of())
                .getBody();
        assertThat(seen.get("lines")).hasSize(1);
        assertThat(uuid(seen.get("lines").get(0), "branch_id")).isEqualTo(home);
        // A reader of the other branch alone cannot see the document at all.
        assertThat(api.call(HttpMethod.GET, "/landed-costs/" + uuid(doc, "id"), null, PAYER, other.toString(), Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/landed-costs/" + uuid(doc, "id"), PAYER).getBody().get("lines"))
                .hasSize(2);
    }

    // ---- the hold on an uncosted receipt ---------------------------------------------------

    void requireCosting(boolean on) {
        String etag = api.get("/purchasing/settings", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/purchasing/settings",
                Map.of("require_costing_before_sale", on),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aHeldReceiptRefusesATransferOutOnlyWhileTheSettingIsOn() {
        JsonNode held = receive(hq, product, "10", 100, true);
        assertThat(held.get("held_for_costing").asBoolean()).isTrue();
        // The setting is off: nothing is refused.
        assertThat(transfer(hq, br2, product, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        requireCosting(true);
        ResponseEntity<JsonNode> refused = transfer(hq, br2, product, "1");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("receipt_not_costed");
        assertThat(refused.getBody().get("detail").asString())
                .contains(held.get("receipt_no").asString());
        // Another product is not affected.
        receive(hq, product2, "5", 150);
        assertThat(transfer(hq, br2, product2, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // A landed cost on the receipt releases the hold.
        landed(freight("FWD-H", 100, lineId(held)));
        assertThat(api.get("/goods-receipts/" + uuid(held, "id"), PAYER)
                        .getBody()
                        .get("held_for_costing")
                        .asBoolean())
                .isFalse();
        assertThat(transfer(hq, br2, product, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aHoldReservesOnlyTheHeldReceiptsUnitsNotEveryUnitOfTheProduct() {
        JsonNode held = receive(hq, product, "10", 100, true);
        receive(hq, product, "10", 100);
        requireCosting(true);
        // 20 are in the branch and the held receipt's 10 are reserved: ten can go, the eleventh cannot.
        assertThat(transfer(hq, br2, product, "10").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> refused = transfer(hq, br2, product, "1");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("receipt_not_costed");
        assertThat(refused.getBody().get("detail").asString()).contains("Only 0");
        assertThat(keyed(
                                "/goods-receipts/" + uuid(held, "id") + "/release-hold",
                                Map.of("reason", "No freight on this one"),
                                PAYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(transfer(hq, br2, product, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aVoidedReceiptCannotHaveItsHoldReleasedAndAVoidedLandedCostPutsTheHoldBack() {
        JsonNode held = receive(hq, product, "10", 100, true);
        JsonNode gone = receive(hq, product2, "5", 150, true);
        assertThat(keyed("/goods-receipts/" + uuid(gone, "id") + "/void", Map.of("reason", "Test wrong goods"), BUYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> late = keyed(
                "/goods-receipts/" + uuid(gone, "id") + "/release-hold",
                Map.of("reason", "No freight on this one"),
                PAYER);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(late)).isEqualTo("receipt_voided");
        // A landed cost releases the hold; voiding it holds the receipt again.
        JsonNode doc = landed(freight("FWD-RH", 100, lineId(held)));
        assertThat(api.get("/goods-receipts/" + uuid(held, "id"), PAYER)
                        .getBody()
                        .get("held_for_costing")
                        .asBoolean())
                .isFalse();
        assertThat(voidLanded(uuid(doc, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/goods-receipts/" + uuid(held, "id"), PAYER)
                        .getBody()
                        .get("held_for_costing")
                        .asBoolean())
                .isTrue();
        // With a second landed cost standing the hold stays released when the first is voided.
        JsonNode one = landed(freight("FWD-RH1", 100, lineId(held)));
        JsonNode two = landed(freight("FWD-RH2", 100, lineId(held)));
        assertThat(voidLanded(uuid(one, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/goods-receipts/" + uuid(held, "id"), PAYER)
                        .getBody()
                        .get("held_for_costing")
                        .asBoolean())
                .isFalse();
        assertThat(voidLanded(uuid(two, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/goods-receipts/" + uuid(held, "id"), PAYER)
                        .getBody()
                        .get("held_for_costing")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void aHoldIsReleasedByHandWithAReasonAndOnlyOnce() {
        JsonNode held = receive(hq, product, "10", 100, true);
        requireCosting(true);
        UUID id = uuid(held, "id");
        // The clerk who received the goods cannot lift the hold; an administrator can.
        assertThat(keyed(
                                "/goods-receipts/" + id + "/release-hold",
                                Map.of("reason", "No freight on this delivery"),
                                BUYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(keyed("/goods-receipts/" + id + "/release-hold", Map.of("reason", "x"), PAYER)))
                .isEqualTo("validation_failed");
        ResponseEntity<JsonNode> released = keyed(
                "/goods-receipts/" + id + "/release-hold", Map.of("reason", "No freight on this delivery"), PAYER);
        assertThat(released.getStatusCode()).as("%s", released.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(released.getBody().get("held_for_costing").asBoolean()).isFalse();
        assertThat(transfer(hq, br2, product, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> twice = keyed(
                "/goods-receipts/" + id + "/release-hold", Map.of("reason", "No freight on this delivery"), PAYER);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(twice)).isEqualTo("not_held");
    }

    @Test
    void theCostingSettingIsEditedUnderIfMatchAndAudited() {
        String etag = api.get("/purchasing/settings", SETTINGS).getHeaders().getETag();
        Map<String, Object> change = Map.of("require_costing_before_sale", true);
        assertThat(api.call(HttpMethod.PUT, "/purchasing/settings", change, SETTINGS, "*", Map.of())
                        .getStatusCode()
                        .value())
                .isEqualTo(428);
        assertThat(api.call(HttpMethod.PUT, "/purchasing/settings", change, SETTINGS, "*", Map.of("If-Match", "99"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> ok =
                api.call(HttpMethod.PUT, "/purchasing/settings", change, SETTINGS, "*", Map.of("If-Match", etag));
        assertThat(ok.getBody().get("require_costing_before_sale").asBoolean()).isTrue();
        assertThat(scalar(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.purchasing.settings_changed'",
                        t.tenantId()))
                .isEqualTo(1);
    }

    /**
     * ADR-079: a landed cost spread over two branches is taken back on void from exactly the branches that took it, by the
     * parts recorded when it was posted, so each branch's inventory account still equals its stock at its own average after
     * the other branch sold some of the product in between.
     */
    @Test
    void aVoidTakesTheLandedCostBackFromTheBranchesThatTookIt() {
        receive(br2, product, "10", 100);
        JsonNode lot = receive(hq, product, "10", 100);
        // 1,000 for the 10 of the lot, spread over what each branch holds: 500 each, both averages 150.
        JsonNode doc = landed(freight("FWD-SPREAD", 1_000, lineId(lot)));
        assertThat(doc.get("lines").get(0).get("to_inventory_minor").asLong()).isEqualTo(1_000);
        assertThat(valuation(hq, product)).isEqualTo(1_500);
        assertThat(valuation(br2, product)).isEqualTo(1_500);
        assertThat(api.sell(br2, "cash", product, "3").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(voidLanded(uuid(doc, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Head office gives back its 500 (10 at 100 again).
        // Branch two gives back its 500 from the 7 left: 1,050 less 500 is 550, carried as 79 each.
        assertThat(valuation(hq, product)).isEqualTo(1_000);
        assertThat(balance("inventory", hq)).isEqualTo(valuation(hq, product));
        assertThat(valuation(br2, product)).isEqualTo(7 * 79);
        assertThat(balance("inventory", br2)).isEqualTo(valuation(br2, product));
        assertBooksBalance();
    }

    /** ADR-079: when a branch has since sold most of what took the cost, taking its part back is refused in plain words. */
    @Test
    void aVoidThatWouldLeaveABranchBelowZeroIsRefused() {
        receive(br2, product, "10", 100);
        JsonNode lot = receive(hq, product, "10", 100);
        JsonNode doc = landed(freight("FWD-SOLD", 1_000, lineId(lot)));
        assertThat(api.sell(br2, "cash", product, "8").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> refused = voidLanded(uuid(doc, "id"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("average_cost_negative");
        assertThat(refused.getBody().get("detail").asString()).contains("cannot be voided");
        assertThat(landedCost(uuid(doc, "id")).get("status").asString()).isEqualTo("posted");
        assertThat(balance("inventory", br2)).isEqualTo(valuation(br2, product));
        assertBooksBalance();
    }

    /**
     * ADR-079: the void of a landed cost over two products racing a transfer of both between the branches that took its
     * parts. Both take their balance locks in branch and product order, after any product locks, so neither deadlocks: both
     * succeed, in either order, and each branch's inventory account still equals its stock at its own average.
     */
    @Test
    void aTwoProductVoidRacingATransferOfBothNeitherDeadlocksNorDrifts() throws Exception {
        List<UUID> prods = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                UUID p1 = api.product("LC-R1-" + round, 100, 400);
                UUID p2 = api.product("LC-R2-" + round, 150, 600);
                prods.add(p1);
                prods.add(p2);
                receive(br2, p1, "10", 100);
                receive(br2, p2, "10", 150);
                JsonNode a = receive(hq, p1, "10", 100);
                JsonNode b = receive(hq, p2, "10", 150);
                JsonNode doc = landed(freight("FWD-RACE-" + round, 1_000, lineId(a), lineId(b)));
                Map<String, Object> both = new LinkedHashMap<>();
                both.put("from_branch_id", br2);
                both.put("to_branch_id", hq);
                both.put("lines", List.of(Map.of("product_id", p1, "qty", "2"), Map.of("product_id", p2, "qty", "2")));
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<JsonNode>> voided = pool.submit(() -> {
                    go.await();
                    return voidLanded(uuid(doc, "id"));
                });
                Future<ResponseEntity<JsonNode>> moved = pool.submit(() -> {
                    go.await();
                    return keyed("/transfers", both, ADMIN);
                });
                go.countDown();
                ResponseEntity<JsonNode> v = voided.get(60, TimeUnit.SECONDS);
                ResponseEntity<JsonNode> m = moved.get(60, TimeUnit.SECONDS);
                assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
                assertThat(m.getStatusCode()).as("%s", m.getBody()).isEqualTo(HttpStatus.CREATED);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID branch : List.of(hq, br2)) {
            assertThat(balance("inventory", branch))
                    .isEqualTo(prods.stream()
                            .mapToLong(prod -> valuation(branch, prod))
                            .sum());
        }
        assertBooksBalance();
    }
}
