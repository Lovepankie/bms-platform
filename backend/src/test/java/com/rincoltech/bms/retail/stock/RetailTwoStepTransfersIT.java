package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The two-step transfer (capability {@code transfer_receipt}; ADR-033, issue #193; docs/specs/
 * trade-capabilities.md sections 8 and 9.3): dispatch, receive with a shortage valued at the source
 * cost, cancel, void, the in-flight report, the stock-by-location report, and that nothing changes
 * while the capability is off. Names and amounts fabricated.
 */
class RetailTwoStepTransfersIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String ROLE_MANAGE = "retail.branch_role.manage";
    /** May move and read stock, holds no profit read: sees no cost anywhere. */
    static final String MOVER = "retail.stock.transfer,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    @Autowired
    BusinessClock clock;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;
    UUID other;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-twostep", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("CABLE-2MM", 1_000, 1_500);
        other = api.product("SOCKET-13A", 2_000, 3_000);
    }

    // ---------------------------------------------------------------------------------------- helpers

    ResponseEntity<JsonNode> putCapabilities(Map<String, Object> body) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        Map<String, String> headers = etag == null ? Map.of() : Map.of("If-Match", etag);
        return api.call(HttpMethod.PUT, "/settings/capabilities", body, SETTINGS, "*", headers);
    }

    void switchOn() {
        assertThat(putCapabilities(Map.of("transfer_receipt", true)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    Map<String, Object> body(UUID from, UUID to, Object... productQty) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            lines.add(Map.of("product_id", productQty[i], "qty", productQty[i + 1]));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("from_branch_id", from);
        b.put("to_branch_id", to);
        b.put("lines", lines);
        return b;
    }

    ResponseEntity<JsonNode> dispatch(String qty) {
        return api.postKeyed(
                "/transfers",
                body(t.headOffice(), t.secondBranch(), product, qty),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
    }

    UUID dispatched(String qty) {
        ResponseEntity<JsonNode> r = dispatch(qty);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> receive(UUID id, Object body, String perms, String branches) {
        return api.postKeyed(
                "/transfers/" + id + "/receive",
                body,
                perms,
                branches,
                UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> receiveAll(UUID id) {
        return receive(id, Map.of(), ADMIN, "*");
    }

    Map<String, Object> received(UUID p, String qty) {
        return Map.of("lines", List.of(Map.of("product_id", p, "qty", qty)));
    }

    ResponseEntity<JsonNode> cancel(UUID id, String perms, String branches) {
        return api.postKeyed(
                "/transfers/" + id + "/cancel",
                Map.of("reason", "Test sent by mistake"),
                perms,
                branches,
                UUID.randomUUID().toString());
    }

    String qty(UUID branch, UUID p) {
        return TestDatabase.owner()
                .sql(
                        "SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), branch, p)
                .query(String.class)
                .optional()
                .orElse("0.000");
    }

    /** The net (debits less credits) of one account in one branch over every entry of the transfer, reversals included. */
    long net(UUID transfer, UUID branch, String systemKey) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0)
                          FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.source_type = 'retail.transfer' AND e.source_id = ?
                           AND e.branch_id = ? AND a.system_key = ?
                        """)
                .params(t.tenantId(), transfer, branch, systemKey)
                .query(Long.class)
                .single();
    }

    long entryCount(UUID transfer) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_type = 'retail.transfer' AND source_id = ?")
                .params(t.tenantId(), transfer)
                .query(Long.class)
                .single();
    }

    JsonNode inFlight(String perms) {
        ResponseEntity<JsonNode> r = api.get("/reports/transfers-in-flight", perms);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode byLocation(String perms) {
        ResponseEntity<JsonNode> r = api.get("/reports/stock-by-location", perms);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode row(JsonNode report, UUID p) {
        for (JsonNode r : report.get("items")) {
            if (r.get("product_id").asString().equals(p.toString())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + p + " in " + report);
    }

    String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    // ------------------------------------------------------------------------- the capability is off

    /** Acceptance 1: with the capability off a transfer is atomic as before and the new routes are 404. */
    @Test
    void withTheCapabilityOffTransfersAreAtomicAndTheTwoStepRoutesAreAbsent() {
        api.stockUp(t.headOffice(), product, "10");

        ResponseEntity<JsonNode> created = dispatch("4");

        assertThat(created.getBody().get("status").asString()).isEqualTo("completed");
        assertThat(qty(t.headOffice(), product)).isEqualTo("6.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("4.000");
        UUID id = RetailTestSupport.id(created);
        assertThat(entryCount(id)).isEqualTo(2);
        ResponseEntity<JsonNode> receive = receiveAll(id);
        assertThat(receive.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(receive)).isEqualTo("capability_not_enabled");
        assertThat(cancel(id, ADMIN, "*").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> report = api.get("/reports/transfers-in-flight", ADMIN);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(report)).isEqualTo("capability_not_enabled");
        assertThat(api.get("/transfers?status=in_transit", ADMIN).getBody().get("items"))
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------- dispatch

    /**
     * Acceptance 2: a dispatched transfer is in neither branch's balance; only the source has posted;
     * the in-flight report equals the open dispatches and the retail clearing entries agree with it.
     */
    @Test
    void aDispatchedTransferIsInNeitherBalanceAndTheReportEqualsTheOpenDispatches() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");

        ResponseEntity<JsonNode> created = dispatch("4");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status").asString()).isEqualTo("in_transit");
        UUID id = RetailTestSupport.id(created);
        assertThat(qty(t.headOffice(), product)).isEqualTo("6.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("0.000");
        assertThat(entryCount(id)).isEqualTo(1);
        assertThat(net(id, t.headOffice(), "inventory")).isEqualTo(-4_000);
        assertThat(net(id, t.headOffice(), "interbranch_clearing")).isEqualTo(4_000);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND transfer_id = ?")
                        .params(t.tenantId(), id)
                        .query(Long.class)
                        .single())
                .as("only the transfer_out movement exists")
                .isEqualTo(1);

        JsonNode flight = inFlight(ADMIN);
        assertThat(flight.get("items")).hasSize(1);
        assertThat(flight.get("items").get(0).get("id").asString()).isEqualTo(id.toString());
        assertThat(flight.get("items").get(0).get("cost_total_minor").asLong()).isEqualTo(4_000);
        assertThat(flight.get("in_transit_value_minor").asLong()).isEqualTo(4_000);
        assertThat(flight.get("clearing_minor").asLong()).isEqualTo(4_000);
        assertThat(flight.get("reconciled").asBoolean()).isTrue();
        assertThat(api.get("/transfers?status=in_transit", ADMIN).getBody().get("items"))
                .hasSize(1);

        JsonNode valuation = api.get("/reports/valuation", ADMIN).getBody();
        assertThat(valuation.get("value_at_cost_minor").asLong())
                .as("the held stock only: 6 at head office")
                .isEqualTo(6_000);
        assertThat(valuation
                        .get("branches")
                        .get(0)
                        .get("revaluation_difference_minor")
                        .asLong())
                .isZero();

        JsonNode location = byLocation(ADMIN);
        JsonNode r = row(location, product);
        assertThat(r.get("in_transit_qty").asString()).isEqualTo("4.000");
        assertThat(r.get("combined_qty").asString())
                .as("held 6 plus 4 on the way")
                .isEqualTo("10.000");
        assertThat(r.get("in_transit_value_minor").asLong()).isEqualTo(4_000);
        assertThat(r.get("combined_value_minor").asLong()).isEqualTo(10_000);
        assertThat(location.get("totals").get("combined_value_minor").asLong()).isEqualTo(10_000);
        assertThat(location.get("totals").get("revaluation_difference_minor").asLong())
                .isZero();
    }

    /** The shared clearing account: a lending-sourced entry in 1190 is not read as in-flight stock. */
    @Test
    void theReconciliationReadsOnlyRetailTransferEntriesOfAccount1190() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        dispatched("4");
        UUID period = TestDatabase.owner()
                .sql("SELECT id FROM gl_periods WHERE tenant_id = ? LIMIT 1")
                .param(t.tenantId())
                .query(UUID.class)
                .optional()
                .orElse(null);
        assertThat(period).as("a retail posting opened a period").isNotNull();
        // A lending branch settlement: debit clearing 777 against cash, from another module.
        TestDatabase.owner()
                .sql("""
                        WITH e AS (INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id,
                                       reference, source_module, source_type)
                                   VALUES (gen_random_uuid(), ?, ?, 'JE90000001', current_date, ?, 'TEST-LENDING', 'lending',
                                       'lending.settlement') RETURNING id)
                        INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)
                        SELECT gen_random_uuid(), ?, e.id, 1, ?, 777, 0, 'UGX' FROM e
                        UNION ALL
                        SELECT gen_random_uuid(), ?, e.id, 2, ?, 0, 777, 'UGX' FROM e
                        """)
                .params(
                        t.tenantId(),
                        t.headOffice(),
                        period,
                        t.tenantId(),
                        t.account("interbranch_clearing"),
                        t.tenantId(),
                        t.account("cash_on_hand"))
                .update();

        JsonNode flight = inFlight(ADMIN);

        assertThat(flight.get("in_transit_value_minor").asLong()).isEqualTo(4_000);
        assertThat(flight.get("clearing_minor").asLong())
                .as("the 777 is not a retail transfer entry")
                .isEqualTo(4_000);
        assertThat(flight.get("reconciled").asBoolean()).isTrue();
    }

    // -------------------------------------------------------------------------------------- receive

    /** Receiving everything posts the destination entry; clearing returns to zero; the report empties. */
    @Test
    void receivingEverythingCompletesTheMoveAndClearsClearing() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");

        ResponseEntity<JsonNode> done = receiveAll(id);

        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("received");
        assertThat(done.getBody().get("lines").get(0).get("received_qty").asString())
                .isEqualTo("4.000");
        assertThat(done.getBody().get("shortage_cost_minor").asLong()).isZero();
        assertThat(done.getBody().get("received_at").isNull()).isFalse();
        assertThat(qty(t.headOffice(), product)).isEqualTo("6.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("4.000");
        assertThat(entryCount(id)).isEqualTo(2);
        assertThat(net(id, t.secondBranch(), "inventory")).isEqualTo(4_000);
        assertThat(net(id, t.headOffice(), "interbranch_clearing") + net(id, t.secondBranch(), "interbranch_clearing"))
                .isZero();
        JsonNode flight = inFlight(ADMIN);
        assertThat(flight.get("items")).isEmpty();
        assertThat(flight.get("in_transit_value_minor").asLong()).isZero();
        assertThat(flight.get("clearing_minor").asLong()).isZero();
        assertThat(flight.get("reconciled").asBoolean()).isTrue();
        JsonNode valuation = api.get("/reports/valuation", ADMIN).getBody();
        assertThat(valuation.get("value_at_cost_minor").asLong()).isEqualTo(10_000);
        ResponseEntity<JsonNode> twice = receiveAll(id);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(twice)).isEqualTo("transfer_not_in_transit");
    }

    /**
     * Acceptance 3: a shortage is shrinkage at the destination valued at the SOURCE cost, even when
     * the product cost changed in between, and the clearing legs of the transfer net to zero.
     */
    @Test
    void aShortageIsValuedAtTheSourceCostAndClearingReturnsToZero() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");
        TestDatabase.owner()
                .sql("UPDATE retail_products SET cost_minor = 5000 WHERE tenant_id = ? AND id = ?")
                .params(t.tenantId(), product)
                .update();

        ResponseEntity<JsonNode> done = receive(id, received(product, "3"), ADMIN, "*");

        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("received");
        assertThat(done.getBody().get("lines").get(0).get("received_qty").asString())
                .isEqualTo("3.000");
        assertThat(done.getBody().get("shortage_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(qty(t.headOffice(), product)).isEqualTo("6.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("3.000");
        assertThat(entryCount(id)).as("out, in and shortage").isEqualTo(3);
        assertThat(net(id, t.secondBranch(), "inventory")).isEqualTo(3_000);
        assertThat(net(id, t.secondBranch(), "stock_shrinkage")).isEqualTo(1_000);
        assertThat(net(id, t.headOffice(), "interbranch_clearing") + net(id, t.secondBranch(), "interbranch_clearing"))
                .isZero();
        JsonNode flight = inFlight(ADMIN);
        assertThat(flight.get("items")).isEmpty();
        assertThat(flight.get("clearing_minor").asLong()).isZero();
        assertThat(flight.get("reconciled").asBoolean()).isTrue();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT received_qty::text FROM retail_transfer_lines WHERE tenant_id = ? AND transfer_id = ?")
                        .params(t.tenantId(), id)
                        .query(String.class)
                        .single())
                .isEqualTo("3.000");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data::text FROM audit_log WHERE entity_id = ? AND action = 'retail.transfer.received'")
                        .param(id)
                        .query(String.class)
                        .single())
                .contains("\"short_lines\": 1")
                .doesNotContain("cost");
    }

    /** A line with nothing received is a whole line of shortage, and is allowed. */
    @Test
    void aLineThatDidNotArriveAtAllIsAShortage() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.headOffice(), other, "10");
        ResponseEntity<JsonNode> created = api.postKeyed(
                "/transfers",
                body(t.headOffice(), t.secondBranch(), product, "4", other, "2"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        UUID id = RetailTestSupport.id(created);

        ResponseEntity<JsonNode> done = receive(
                id,
                Map.of(
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "4"), Map.of("product_id", other, "qty", "0"))),
                ADMIN,
                "*");

        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(qty(t.secondBranch(), product)).isEqualTo("4.000");
        assertThat(qty(t.secondBranch(), other)).isEqualTo("0.000");
        assertThat(done.getBody().get("shortage_cost_minor").asLong()).isEqualTo(4_000);
        assertThat(net(id, t.secondBranch(), "stock_shrinkage")).isEqualTo(4_000);
        assertThat(net(id, t.headOffice(), "interbranch_clearing") + net(id, t.secondBranch(), "interbranch_clearing"))
                .isZero();
    }

    /** Received more than dispatched is 422 received_exceeds_dispatched; an incomplete count is a field problem. */
    @Test
    void receivingMoreThanWasDispatchedIsRefusedAndNothingMoves() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");

        ResponseEntity<JsonNode> over = receive(id, received(product, "4.001"), ADMIN, "*");

        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("received_exceeds_dispatched");
        ResponseEntity<JsonNode> unknown = receive(id, received(other, "1"), ADMIN, "*");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(unknown.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("unknown_product");
        ResponseEntity<JsonNode> fraction = receive(id, received(product, "1.0001"), ADMIN, "*");
        assertThat(fraction.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(qty(t.secondBranch(), product)).isEqualTo("0.000");
        assertThat(entryCount(id)).isEqualTo(1);
        assertThat(api.get("/transfers/" + id, ADMIN).getBody().get("status").asString())
                .isEqualTo("in_transit");
    }

    @Test
    void anIncompleteCountNamesEveryItem() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.headOffice(), other, "10");
        UUID id = RetailTestSupport.id(api.postKeyed(
                "/transfers",
                body(t.headOffice(), t.secondBranch(), product, "4", other, "2"),
                ADMIN,
                "*",
                UUID.randomUUID().toString()));

        ResponseEntity<JsonNode> partial = receive(id, received(product, "4"), ADMIN, "*");

        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(partial.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("incomplete");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("0.000");
    }

    // JUSTIFICATION-A3: the V116 receipt rules #239 asks for; no existing test writes a receipt past the service.
    /**
     * V116 (issue #239): the database refuses a receipt that leaves a line without received_qty, or that brought stock
     * of value without the destination entry; a receipt where nothing arrived needs no destination entry.
     */
    @Test
    void theDatabaseRefusesAReceiptWithoutEveryLineOrWithoutTheDestinationEntry() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");
        String receipt =
                "UPDATE retail_transfers SET status = 'received', received_at = now(), received_by = created_by"
                        + " WHERE tenant_id = ? AND id = ?";

        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql(receipt)
                        .params(t.tenantId(), id)
                        .update())
                .hasMessageContaining("a receipt records what arrived on every line");
        TestDatabase.owner()
                .sql("UPDATE retail_transfer_lines SET received_qty = 3 WHERE tenant_id = ? AND transfer_id = ?")
                .params(t.tenantId(), id)
                .update();
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql(receipt)
                        .params(t.tenantId(), id)
                        .update())
                .hasMessageContaining("has the destination entry");
        assertThat(api.get("/transfers/" + id, ADMIN).getBody().get("status").asString())
                .isEqualTo("in_transit");

        UUID lost = dispatched("2");
        ResponseEntity<JsonNode> nothing = receive(lost, received(product, "0"), ADMIN, "*");
        assertThat(nothing.getStatusCode()).as("%s", nothing.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(net(lost, t.secondBranch(), "inventory")).isZero();
        assertThat(net(lost, t.secondBranch(), "stock_shrinkage")).isEqualTo(2_000);
    }

    /** A replayed key returns the first receipt and moves nothing again; two racing receipts receive once. */
    @Test
    void aReceiptIsIdempotentAndTwoRacingReceiptsReceiveOnce() throws Exception {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = api.postKeyed("/transfers/" + id + "/receive", Map.of(), ADMIN, "*", key);
        ResponseEntity<JsonNode> again = api.postKeyed("/transfers/" + id + "/receive", Map.of(), ADMIN, "*", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("4.000");

        UUID second = dispatched("2");
        List<HttpStatusCode> statuses = Api.race(6, () -> receiveAll(second));
        assertThat(Api.count(statuses, HttpStatus.OK)).isEqualTo(1);
        assertThat(Api.count(statuses, HttpStatus.CONFLICT)).isEqualTo(5);
        assertThat(qty(t.secondBranch(), product)).isEqualTo("6.000");
        assertThat(entryCount(second)).isEqualTo(2);
    }

    // --------------------------------------------------------------------------------------- cancel

    /** Acceptance 4: cancel restores the source at the original cost and reverses its entry, once. */
    @Test
    void cancelRestoresTheSourceAndReversesItsEntry() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");

        ResponseEntity<JsonNode> cancelled = cancel(id, ADMIN, "*");

        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.getBody().get("void_reason").asString()).isEqualTo("Test sent by mistake");
        assertThat(qty(t.headOffice(), product)).isEqualTo("10.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("0.000");
        assertThat(net(id, t.headOffice(), "inventory")).isZero();
        assertThat(net(id, t.headOffice(), "interbranch_clearing")).isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND transfer_id = ? AND reverses_movement_id IS NOT NULL AND kind = 'transfer_in' AND unit_cost_minor = 1000")
                        .params(t.tenantId(), id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        JsonNode flight = inFlight(ADMIN);
        assertThat(flight.get("items")).isEmpty();
        assertThat(flight.get("clearing_minor").asLong()).isZero();
        assertThat(flight.get("reconciled").asBoolean()).isTrue();
        assertThat(code(receiveAll(id))).isEqualTo("transfer_not_in_transit");
        assertThat(code(cancel(id, ADMIN, "*"))).isEqualTo("transfer_not_in_transit");
        ResponseEntity<JsonNode> voided = api.post("/transfers/" + id + "/void", Map.of("reason", "Test"), ADMIN);
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(voided)).isEqualTo("transfer_cancelled");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data::text FROM audit_log WHERE entity_id = ? AND action = 'retail.transfer.cancelled'")
                        .param(id)
                        .query(String.class)
                        .single())
                .doesNotContain("cost");
    }

    // ------------------------------------------------------------------------------------------ void

    /** Voiding a transfer still on its way is refused: it is cancelled instead. */
    @Test
    void aTransferOnItsWayCannotBeVoided() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");

        ResponseEntity<JsonNode> voided = api.post("/transfers/" + id + "/void", Map.of("reason", "Test"), ADMIN);

        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(voided)).isEqualTo("transfer_in_transit");
        assertThat(qty(t.headOffice(), product)).isEqualTo("6.000");
    }

    /** A received transfer with a shortage voids to the state before it, every entry reversed. */
    @Test
    void aReceivedTransferWithAShortageVoidsEverything() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");
        assertThat(receive(id, received(product, "3"), ADMIN, "*").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> voided =
                api.post("/transfers/" + id + "/void", Map.of("reason", "Test miscount"), ADMIN);

        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        assertThat(qty(t.headOffice(), product)).isEqualTo("10.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("0.000");
        for (UUID branch : List.of(t.headOffice(), t.secondBranch())) {
            for (String key : List.of("inventory", "interbranch_clearing", "stock_shrinkage")) {
                assertThat(net(id, branch, key)).as("%s %s", branch, key).isZero();
            }
        }
        assertThat(entryCount(id)).as("three entries and their three reversals").isEqualTo(6);
        assertThat(inFlight(ADMIN).get("reconciled").asBoolean()).isTrue();
    }

    /** The void rule applies to what was received: the destination must still hold it. */
    @Test
    void aReceivedTransferCannotBeVoidedOnceTheDestinationSoldIt() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");
        assertThat(receive(id, received(product, "3"), ADMIN, "*").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.sell(t.secondBranch(), "cash", product, "2").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> refused = api.post("/transfers/" + id + "/void", Map.of("reason", "Test"), ADMIN);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("transfer_stock_moved");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("1.000");
    }

    // JUSTIFICATION-A3: the two tests #239 item 7 asks for; no existing test covers either.
    /** Issue #239: a receipt and a sale of the same item at the destination at once both land, and the books agree. */
    @Test
    void aReceiptWhileTheDestinationSellsTheItemKeepsBothAndTheBooksAgree() throws Exception {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.secondBranch(), product, "5");
        UUID id = dispatched("4");

        CompletableFuture<ResponseEntity<JsonNode>> receipt = CompletableFuture.supplyAsync(() -> receiveAll(id));
        CompletableFuture<ResponseEntity<JsonNode>> sale =
                CompletableFuture.supplyAsync(() -> api.sell(t.secondBranch(), "cash", product, "2"));

        assertThat(receipt.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sale.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(qty(t.secondBranch(), product))
                .as("5 held, 4 received, 2 sold")
                .isEqualTo("7.000");
        assertThat(qty(t.headOffice(), product)).isEqualTo("6.000");
        assertThat(net(id, t.secondBranch(), "inventory")).isEqualTo(4_000);
        assertThat(net(id, t.headOffice(), "interbranch_clearing") + net(id, t.secondBranch(), "interbranch_clearing"))
                .isZero();
        assertThat(inFlight(ADMIN).get("reconciled").asBoolean()).isTrue();
    }

    /**
     * Issue #239: a one-step transfer from before V38 (completed, both entries, no receipt columns, no received_qty)
     * voids through the API after the two-step move is switched on.
     */
    @Test
    void aCompletedTransferFromBeforeTheTwoStepMoveVoidsThroughTheApi() {
        api.stockUp(t.headOffice(), product, "10");
        ResponseEntity<JsonNode> before = dispatch("4");
        assertThat(before.getBody().get("status").asString()).isEqualTo("completed");
        UUID id = RetailTestSupport.id(before);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_transfer_lines WHERE tenant_id = ? AND transfer_id = ?"
                                + " AND received_qty IS NULL")
                        .params(t.tenantId(), id)
                        .query(Long.class)
                        .single())
                .as("a one-step line, as every line before V38")
                .isEqualTo(1);
        switchOn();

        ResponseEntity<JsonNode> voided =
                api.post("/transfers/" + id + "/void", Map.of("reason", "Test wrong branch"), ADMIN);

        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        assertThat(voided.getBody().get("received_at").isNull()).isTrue();
        assertThat(qty(t.headOffice(), product)).isEqualTo("10.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("0.000");
        assertThat(entryCount(id)).as("two entries and their two reversals").isEqualTo(4);
        for (UUID branch : List.of(t.headOffice(), t.secondBranch())) {
            for (String key : List.of("inventory", "interbranch_clearing")) {
                assertThat(net(id, branch, key)).as("%s %s", branch, key).isZero();
            }
        }
    }

    // ------------------------------------------------------------------------- switching it off

    /** Acceptance 6: switching the capability off is refused while any transfer is in transit. */
    @Test
    void switchingTheCapabilityOffIsRefusedWhileATransferIsInTransit() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");

        ResponseEntity<JsonNode> flag = putCapabilities(Map.of("transfer_receipt", false));
        ResponseEntity<JsonNode> profile = putCapabilities(Map.of("profile", "shops"));
        // Wholesale has no two-step move, so choosing it would switch the capability off too (issue #424).
        ResponseEntity<JsonNode> wholesale = putCapabilities(Map.of("profile", "wholesale"));

        assertThat(flag.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(flag)).isEqualTo("transfers_in_transit");
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(profile)).isEqualTo("transfers_in_transit");
        assertThat(wholesale.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(wholesale)).isEqualTo("transfers_in_transit");
        // A kind that keeps the two-step move, or the flag sent with the kind, is allowed while stock is on its way.
        assertThat(putCapabilities(Map.of("profile", "wholesale", "transfer_receipt", true))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(putCapabilities(Map.of("profile", "stores_and_shops")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("transfer_receipt")
                        .asBoolean())
                .isTrue();
        // Other flags are untouched by the guard.
        assertThat(putCapabilities(Map.of("documents", true)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(receiveAll(id).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(putCapabilities(Map.of("transfer_receipt", false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> after = dispatch("1");
        assertThat(after.getBody().get("status").asString()).isEqualTo("completed");
    }

    // JUSTIFICATION-A3: the switch-off race test #239 asks for; no existing test holds a dispatch still.
    /**
     * Issue #239: a switch-off sent while a dispatch is between reading the capability and committing waits for the
     * dispatch, then sees the transfer on its way and is refused. The dispatch is held still on the source's balance row,
     * after it has read the capability under the settings lock.
     */
    @Test
    void aSwitchOffDuringADispatchWaitsForItAndIsRefused() throws Exception {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT 1 FROM retail_stock_balances WHERE tenant_id = '" + t.tenantId()
                        + "' AND branch_id = '" + t.headOffice() + "' AND product_id = '" + product + "' FOR UPDATE");
            }
            CompletableFuture<ResponseEntity<JsonNode>> sending = CompletableFuture.supplyAsync(() -> dispatch("4"));
            assertThat(waitersReach(1, sending))
                    .as("the dispatch waits on the balance row")
                    .isTrue();
            CompletableFuture<ResponseEntity<JsonNode>> switching =
                    CompletableFuture.supplyAsync(() -> putCapabilities(Map.of("transfer_receipt", false)));
            assertThat(waitersReach(2, switching))
                    .as("the switch-off waits for the dispatch's share lock on the settings")
                    .isTrue();
            c.commit();

            ResponseEntity<JsonNode> sent = sending.get(30, TimeUnit.SECONDS);
            ResponseEntity<JsonNode> refused = switching.get(30, TimeUnit.SECONDS);

            assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(sent.getBody().get("status").asString()).isEqualTo("in_transit");
            assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(code(refused)).isEqualTo("transfers_in_transit");
            assertThat(api.get("/settings/capabilities", SETTINGS)
                            .getBody()
                            .get("transfer_receipt")
                            .asBoolean())
                    .isTrue();
            assertThat(receiveAll(RetailTestSupport.id(sent)).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    /**
     * Waits (up to 30 seconds) until {@code n} database backends wait for a lock, then reports whether the call is still
     * unfinished. The only lock waits in this test are the ones it arranges.
     */
    private boolean waitersReach(int n, CompletableFuture<?> call) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && !call.isDone()) {
            long waiting = TestDatabase.owner()
                    .sql("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted")
                    .query(Long.class)
                    .single();
            if (waiting >= n) {
                return !call.isDone();
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** A branch's role is pinned while a dispatched transfer touches it (spec 2.1), and free again once it is received. */
    @Test
    void aBranchRoleCannotChangeWhileATransferIsOnItsWay() {
        switchOn();
        assertThat(putCapabilities(Map.of("branch_roles", true)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");

        ResponseEntity<JsonNode> refused = api.call(
                HttpMethod.PUT,
                "/branch-roles/" + t.secondBranch(),
                Map.of("role", "shop"),
                ROLE_MANAGE,
                "*",
                Map.of());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("branch_has_open_work");
        assertThat(receiveAll(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/branch-roles/" + t.secondBranch(),
                                Map.of("role", "shop"),
                                ROLE_MANAGE,
                                "*",
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------------------------- permissions and scope

    /** The destination receives, the source cancels: scope on the other branch is a 404. */
    @Test
    void receiveNeedsTheDestinationScopeAndCancelTheSourceScope() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");
        String hq = t.headOffice().toString();
        String two = t.secondBranch().toString();

        assertThat(receive(id, Map.of(), ADMIN, hq).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cancel(id, ADMIN, two).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(receive(id, Map.of(), RetailTestSupport.SALES, "*").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel(id, RetailTestSupport.SALES, "*").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(receive(id, Map.of(), MOVER, two).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Acceptance 5: cost and value are absent without retail.profit.read, on every new response. */
    @Test
    void costAndValueAreAbsentWithoutProfitRead() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");

        ResponseEntity<JsonNode> moved = receive(id, received(product, "3"), MOVER, "*");
        assertThat(moved.getBody().toString()).doesNotContain("cost");
        UUID second = dispatched("1");
        assertThat(cancel(second, MOVER, "*").getBody().toString()).doesNotContain("cost");

        UUID third = dispatched("1");
        String flight = inFlight(MOVER).toString();
        assertThat(flight).doesNotContain("cost").doesNotContain("value").doesNotContain("clearing");
        assertThat(inFlight(MOVER).get("items")).hasSize(1);
        String location = byLocation(MOVER).toString();
        assertThat(location).doesNotContain("cost").doesNotContain("value").doesNotContain("revaluation");
        assertThat(api.get("/transfers/" + third, MOVER).getBody().toString()).doesNotContain("cost");
    }

    /** Profit read held at head office only: values show for that branch and not for the other. */
    @Test
    void valuesAreShownPerBranchWhereProfitIsHeld() {
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.secondBranch(), product, "5");

        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/reports/stock-by-location",
                null,
                "retail.stock.read",
                "*",
                Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice()));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = row(r.getBody(), product);
        assertThat(item.has("value_minor"))
                .as("not everywhere, so no row total")
                .isFalse();
        assertThat(item.has("combined_value_minor")).isFalse();
        for (JsonNode b : item.get("balances")) {
            boolean hq = b.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(b.has("value_minor")).as("%s", b).isEqualTo(hq);
            if (hq) {
                assertThat(b.get("value_minor").asLong()).isEqualTo(10_000);
            }
        }
        assertThat(r.getBody().get("totals").has("combined_value_minor")).isFalse();
        assertThat(r.getBody().get("totals").get("by_branch")).hasSize(1);
    }

    // ------------------------------------------------------------------- stock by store and shop

    /**
     * Stores first, then shops; a subtotal per role and a combined total; a scoped caller sees its own columns. A store is the
     * Store flag (ADR-104).
     */
    @Test
    void stockByLocationGroupsByRoleWithSubtotalsAndACombinedTotal() {
        assertThat(putCapabilities(Map.of("branch_roles", true)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE id = ?")
                .param(t.headOffice())
                .update();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/branch-roles/" + t.secondBranch(),
                                Map.of("role", "shop"),
                                ROLE_MANAGE,
                                "*",
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.secondBranch(), product, "3");
        api.stockUp(t.secondBranch(), other, "2");

        JsonNode report = byLocation(ADMIN);

        assertThat(report.get("branches")).hasSize(2);
        assertThat(report.get("branches").get(0).get("role").asString()).isEqualTo("store");
        assertThat(report.get("branches").get(0).get("id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(report.get("branches").get(1).get("role").asString()).isEqualTo("shop");
        JsonNode r = row(report, product);
        assertThat(r.get("balances").get(0).get("qty").asString()).isEqualTo("10.000");
        assertThat(r.get("balances").get(1).get("qty").asString()).isEqualTo("3.000");
        assertThat(r.get("subtotals").get(0).get("role").asString()).isEqualTo("store");
        assertThat(r.get("subtotals").get(0).get("qty").asString()).isEqualTo("10.000");
        assertThat(r.get("subtotals").get(1).get("role").asString()).isEqualTo("shop");
        assertThat(r.get("subtotals").get(1).get("qty").asString()).isEqualTo("3.000");
        assertThat(r.get("in_transit_qty").asString()).isEqualTo("0.000");
        assertThat(r.get("combined_qty").asString()).isEqualTo("13.000");
        assertThat(r.get("value_minor").asLong()).isEqualTo(13_000);
        JsonNode totals = report.get("totals");
        assertThat(totals.get("value_minor").asLong()).isEqualTo(17_000);
        assertThat(totals.get("combined_value_minor").asLong()).isEqualTo(17_000);
        assertThat(totals.get("by_role").get(0).get("role").asString()).isEqualTo("store");
        assertThat(totals.get("by_role").get(0).get("value_minor").asLong()).isEqualTo(10_000);
        assertThat(totals.get("by_role").get(1).get("value_minor").asLong()).isEqualTo(7_000);
        assertThat(totals.get("revaluation_difference_minor").asLong()).isZero();

        JsonNode shopOnly = api.call(
                        HttpMethod.GET,
                        "/reports/stock-by-location",
                        null,
                        ADMIN,
                        t.secondBranch().toString(),
                        Map.of())
                .getBody();
        assertThat(shopOnly.get("branches")).hasSize(1);
        JsonNode mine = row(shopOnly, product);
        assertThat(mine.get("balances")).hasSize(1);
        assertThat(mine.get("combined_qty").asString())
                .as("another branch's balance is not leaked")
                .isEqualTo("3.000");
    }

    /** With branch_roles off every branch is both, one group, and the report is the all-branches view plus transit. */
    @Test
    void withRolesOffEveryBranchIsBoth() {
        api.stockUp(t.headOffice(), product, "10");

        JsonNode report = byLocation(ADMIN);

        for (JsonNode b : report.get("branches")) {
            assertThat(b.get("role").asString()).isEqualTo("both");
        }
        JsonNode r = row(report, product);
        assertThat(r.get("subtotals")).hasSize(1);
        assertThat(r.get("subtotals").get(0).get("role").asString()).isEqualTo("both");
        assertThat(r.get("combined_qty").asString()).isEqualTo("10.000");
        JsonNode all = api.get("/stock/all-branches", ADMIN).getBody();
        assertThat(all.get("items").get(0).get("total_qty").asString())
                .isEqualTo(r.get("combined_qty").asString());
    }

    /** The routes need retail.stock.read. */
    @Test
    void theReportsNeedStockRead() {
        switchOn();
        assertThat(api.get("/reports/stock-by-location", "retail.sale.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/reports/transfers-in-flight", "retail.sale.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
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

    /** ADR-045: a transfer already on the road is received even if the item was switched off after it was sent. */
    @Test
    void anInTransitTransferOfAnItemSwitchedOffIsStillReceived() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        UUID id = dispatched("4");
        switchOff(product);
        ResponseEntity<JsonNode> r = receiveAll(id);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(qty(t.secondBranch(), product)).isEqualTo("4.000");
    }

    // JUSTIFICATION-A3: the two report fixes of #239 (items 3 and 4); no existing test covers either.
    /** Issue #239: an inactive item whose stock is all on its way keeps its row, so the combined total does not lose it. */
    @Test
    void anInactiveItemWithAllItsStockOnTheWayStaysInStockByLocation() {
        switchOn();
        api.stockUp(t.headOffice(), product, "4");
        dispatched("4");
        switchOff(product);

        JsonNode r = row(byLocation(ADMIN), product);

        assertThat(r.get("in_transit_qty").asString()).isEqualTo("4.000");
        assertThat(r.get("combined_qty").asString()).isEqualTo("4.000");
        assertThat(r.get("combined_value_minor").asLong()).isEqualTo(4_000);
    }

    /** Issue #239: the age of a transfer on its way counts from when it was sent, not from a transfer date set in the past. */
    @Test
    void theAgeInFlightCountsFromWhenItWasSent() {
        switchOn();
        api.stockUp(t.headOffice(), product, "10");
        Map<String, Object> backdated = body(t.headOffice(), t.secondBranch(), product, "4");
        backdated.put(
                "transfer_date",
                clock.today(BusinessClock.DEFAULT_ZONE).minusDays(5).toString());
        ResponseEntity<JsonNode> sent = api.postKeyed(
                "/transfers", backdated, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);

        JsonNode item = inFlight(ADMIN).get("items").get(0);

        assertThat(item.get("transfer_date").asString()).isEqualTo(backdated.get("transfer_date"));
        assertThat(item.get("age_days").asInt()).isZero();
    }
}
