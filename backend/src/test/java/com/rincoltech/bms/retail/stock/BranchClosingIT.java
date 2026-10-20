package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Closing a branch and marking a branch as not stocking (issue #329, ADR-041). The branch is closed by a real tenant
 * administrator; retail events are made with the development headers as the other retail tests do. Names and amounts are
 * fabricated.
 */
class BranchClosingIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport retail;
    Api api;
    Session admin;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("branch-close", false, true);
        retail = new RetailTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        String email = Api.email("admin");
        admin = api.signIn(Api.staff(t, email, new Role("tenant_admin", null)), email, Api.PASSWORD, null);
        product = retail.product("CLOSE-CABLE", 1_000, 1_500);
    }

    // ---------------------------------------------------------------------------------------- helpers

    String etag(UUID branch) {
        return api.get("/api/v1/branches/" + branch, admin.accessToken())
                .getHeaders()
                .getETag();
    }

    ResponseEntity<JsonNode> close(UUID branch, Map<String, Object> body) {
        return api.call(
                HttpMethod.POST,
                "/api/v1/branches/" + branch + "/close",
                body,
                admin.accessToken(),
                Map.of("If-Match", etag(branch)));
    }

    ResponseEntity<JsonNode> reopen(UUID branch) {
        return api.call(
                HttpMethod.POST,
                "/api/v1/branches/" + branch + "/reopen",
                null,
                admin.accessToken(),
                Map.of("If-Match", etag(branch)));
    }

    ResponseEntity<JsonNode> patch(UUID branch, Map<String, Object> body) {
        return api.call(
                HttpMethod.PATCH,
                "/api/v1/branches/" + branch,
                body,
                admin.accessToken(),
                Map.of("If-Match", etag(branch)));
    }

    String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    long audits(String action, UUID entity) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id = ?")
                .params(t.tenantId(), action, entity)
                .query(Long.class)
                .single();
    }

    long movements(UUID branch) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND branch_id = ?")
                .params(t.tenantId(), branch)
                .query(Long.class)
                .single();
    }

    ResponseEntity<JsonNode> restock(UUID branch) {
        Map<String, Object> line = Map.of(
                "product_id",
                product,
                "cost_minor",
                1_000,
                "qty_by_branch",
                List.of(Map.of("branch_id", branch, "qty", "2")));
        Map<String, Object> body =
                Map.of("purchased_on", "2026-01-15", "payment_method", "cash", "lines", List.of(line));
        return retail.postKeyed(
                "/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> transfer(UUID from, UUID to) {
        Map<String, Object> body = Map.of(
                "from_branch_id", from, "to_branch_id", to, "lines", List.of(Map.of("product_id", product, "qty", 1)));
        return retail.postKeyed(
                "/transfers", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> stocktake(UUID branch) {
        Map<String, Object> body =
                Map.of("branch_id", branch, "lines", List.of(Map.of("product_id", product, "counted_qty", "3")));
        return retail.post("/stocktakes", body, ADMIN);
    }

    ResponseEntity<JsonNode> usage(UUID branch) {
        Map<String, Object> body = Map.of(
                "branch_id",
                branch,
                "kind",
                "damaged",
                "reason",
                "Test damaged in the store",
                "lines",
                List.of(Map.of("product_id", product, "qty", "1")));
        return retail.postKeyed("/usage", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    List<String> codes(JsonNode list, String field) {
        return StreamSupport.stream(list.get(field).spliterator(), false)
                .map(b -> b.get("code").asString())
                .toList();
    }

    // ---------------------------------------------------------------------------------------- closing

    /** Closing an empty branch keeps its history, leaves the pickers and can be reversed. */
    @Test
    void closingAnEmptyBranchKeepsItsHistoryAndReopeningBringsItBack() {
        retail.stockUp(t.secondBranch(), product, "5");
        ResponseEntity<JsonNode> sale = retail.sell(t.secondBranch(), "cash", product, "1");
        assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID saleId = RetailTestSupport.id(sale);
        // Bring the shelves to zero so closing needs no confirmation.
        UUID count = RetailTestSupport.id(retail.post(
                "/stocktakes",
                Map.of(
                        "branch_id",
                        t.secondBranch(),
                        "lines",
                        List.of(Map.of("product_id", product, "counted_qty", "0"))),
                ADMIN));
        assertThat(retail.post("/stocktakes/" + count + "/commit", Map.of(), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        long before = movements(t.secondBranch());

        ResponseEntity<JsonNode> closed = close(t.secondBranch(), null);
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().get("status").asString()).isEqualTo("inactive");
        assertThat(closed.getBody().get("closed_at").isNull()).isFalse();
        assertThat(audits("core.branch.closed", t.secondBranch())).isEqualTo(1);

        // Nothing was deleted or posted: the sale and the movements are still there.
        assertThat(retail.get("/sales/" + saleId, ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(movements(t.secondBranch())).isEqualTo(before);

        // It leaves the picker (/me) but the administrator's branch list still shows it, marked closed.
        JsonNode me = api.get("/api/v1/me", admin.accessToken()).getBody();
        assertThat(codes(me, "branches")).containsExactly("HQ");
        assertThat(codes(api.get("/api/v1/branches", admin.accessToken()).getBody(), "items"))
                .containsExactly("HQ", "BR2");

        // Closing twice is a conflict, and the head office cannot be closed.
        assertThat(close(t.secondBranch(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(close(t.headOffice(), null))).isEqualTo("head_office_required");

        ResponseEntity<JsonNode> reopened = reopen(t.secondBranch());
        assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reopened.getBody().get("status").asString()).isEqualTo("active");
        assertThat(reopened.getBody().get("closed_at").isNull()).isTrue();
        assertThat(audits("core.branch.reopened", t.secondBranch())).isEqualTo(1);
        assertThat(reopen(t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codes(api.get("/api/v1/me", admin.accessToken()).getBody(), "branches"))
                .containsExactly("HQ", "BR2");
        retail.stockUp(t.secondBranch(), product, "5");
        assertThat(retail.sell(t.secondBranch(), "cash", product, "0.5").getStatusCode())
                .as("a reopened branch trades again")
                .isEqualTo(HttpStatus.CREATED);
    }

    /** Stock still held is a warning with the stock value; a reason lets the administrator go ahead; nothing is posted. */
    @Test
    void closingOverStockNeedsAReasonAndPostsNothing() {
        retail.stockUp(t.secondBranch(), product, "20");
        long before = movements(t.secondBranch());

        ResponseEntity<JsonNode> refused = close(t.secondBranch(), null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("branch_close_needs_reason");
        assertThat(refused.getBody().get("stock_value_minor").asLong()).isEqualTo(20_000L);
        assertThat(refused.getBody().get("stock_items").asInt()).isEqualTo(1);
        assertThat(refused.getBody().get("detail").asString())
                .contains("20,000")
                .contains("Test Branch Two");
        assertThat(close(t.secondBranch(), Map.of("reason", "  ")).getStatusCode())
                .as("a blank reason is no reason")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> closed = close(t.secondBranch(), Map.of("reason", "Test shop shut for good"));
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().get("closed_reason").asString()).isEqualTo("Test shop shut for good");
        assertThat(movements(t.secondBranch()))
                .as("no stock was moved or written off")
                .isEqualTo(before);
        // The administrator's list shows what is still on the closed branch's shelves.
        JsonNode listed =
                api.get("/api/v1/branches", admin.accessToken()).getBody().get("items");
        JsonNode shown = StreamSupport.stream(listed.spliterator(), false)
                .filter(b -> b.get("code").asString().equals("BR2"))
                .findFirst()
                .orElseThrow();
        assertThat(shown.get("stock_items").asInt()).isEqualTo(1);
        assertThat(shown.get("stock_value_minor").asLong()).isEqualTo(20_000L);
        String after = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.branch.closed'"
                        + " AND entity_id = ?")
                .params(t.tenantId(), t.secondBranch())
                .query(String.class)
                .single();
        assertThat(after).contains("Test shop shut for good").contains("20000");
    }

    /** A transfer dispatched and not yet received also needs the reason. */
    @Test
    void aTransferOnTheWayNeedsAReason() {
        retail.stockUp(t.headOffice(), product, "10");
        String etag =
                retail.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        assertThat(retail.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("transfer_receipt", true),
                                SETTINGS,
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(transfer(t.headOffice(), t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> refused = close(t.secondBranch(), null);
        assertThat(code(refused)).isEqualTo("branch_close_needs_reason");
        assertThat(refused.getBody().get("transfers_in_flight").asInt()).isEqualTo(1);
        assertThat(close(t.secondBranch(), Map.of("reason", "Test closing with a parcel on the way"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The goods on the road to a branch that closes meanwhile are physically there: the receipt succeeds, the stock lands in the
     * closed branch and the history shows it, while an ordinary sale at the closed branch is still refused (ADR-041).
     */
    @Test
    void aClosedBranchStillReceivesATransferAlreadyOnTheRoad() {
        retail.stockUp(t.headOffice(), product, "10");
        String etag =
                retail.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        assertThat(retail.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("transfer_receipt", true),
                                SETTINGS,
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> sent = transfer(t.headOffice(), t.secondBranch());
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID transfer = RetailTestSupport.id(sent);
        assertThat(close(t.secondBranch(), Map.of("reason", "Test closing with a parcel on the way"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> received = retail.postKeyed(
                "/transfers/" + transfer + "/receive",
                Map.of(),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(received.getStatusCode()).as("%s", received.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(retail.get("/transfers/" + transfer, ADMIN)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("received");
        assertThat(TestDatabase.owner()
                        .sql("SELECT qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ?")
                        .params(t.tenantId(), t.secondBranch())
                        .query(java.math.BigDecimal.class)
                        .single())
                .isEqualByComparingTo("1");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND branch_id = ?")
                        .params(t.tenantId(), t.secondBranch())
                        .query(Long.class)
                        .single())
                .as("the receipt is posted to the closed branch")
                .isPositive();
        assertThat(retail.sell(t.secondBranch(), "cash", product, "1").getStatusCode())
                .as("an ordinary sale at the closed branch is still refused")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** A closed branch is refused for sales, restocks and transfers, and a stale or missing If-Match is refused. */
    @Test
    void aClosedBranchIsRefusedAndTheVersionIsChecked() {
        assertThat(api.call(
                                HttpMethod.POST,
                                "/api/v1/branches/" + t.secondBranch() + "/close",
                                null,
                                admin.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(code(api.call(
                        HttpMethod.POST,
                        "/api/v1/branches/" + t.secondBranch() + "/close",
                        null,
                        admin.accessToken(),
                        Map.of("If-Match", "\"9\""))))
                .isEqualTo("version_conflict");
        retail.stockUp(t.headOffice(), product, "10");
        assertThat(close(t.secondBranch(), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(retail.sell(t.secondBranch(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(restock(t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(transfer(t.headOffice(), t.secondBranch()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** The default stock list leaves a closed branch that holds stock out; include_closed brings it back, flagged. */
    @Test
    void theDefaultStockListLeavesClosedBranchesOut() {
        retail.stockUp(t.headOffice(), product, "4");
        retail.stockUp(t.secondBranch(), product, "6");
        assertThat(close(t.secondBranch(), Map.of("reason", "Test closing with stock"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode plain = retail.get("/stock/all-branches", ADMIN).getBody();
        assertThat(codes(plain, "branches")).containsExactly("HQ");
        assertThat(plain.get("closed_branches").asInt()).isEqualTo(1);
        assertThat(plain.get("items").get(0).get("total_qty").asString()).isEqualTo("4.000");

        JsonNode withClosed =
                retail.get("/stock/all-branches?include_closed=true", ADMIN).getBody();
        assertThat(codes(withClosed, "branches")).containsExactly("HQ", "BR2");
        assertThat(withClosed.get("branches").get(1).get("closed").asBoolean()).isTrue();
        assertThat(withClosed.get("closed_branches").asInt()).isZero();
        assertThat(withClosed.get("items").get(0).get("total_qty").asString()).isEqualTo("10.000");

        // The report by store and shop is a report: the closed branch stays in it, flagged.
        JsonNode byLocation = retail.get("/reports/stock-by-location", ADMIN).getBody();
        assertThat(codes(byLocation, "branches")).containsExactly("HQ", "BR2");
        assertThat(byLocation.get("branches").get(1).get("closed").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------- not stocking

    /** A branch that does not stock is refused for sales, restocks, transfers in and stock-takes, and still lets goods leave. */
    @Test
    void aBranchThatDoesNotStockIsRefusedEverywhereStockMoves() {
        retail.stockUp(t.secondBranch(), product, "10");
        assertThat(patch(t.headOffice(), Map.of("stocks_goods", false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID hq = t.headOffice();

        for (ResponseEntity<JsonNode> r : List.of(
                retail.sell(hq, "cash", product, "1"), restock(hq), transfer(t.secondBranch(), hq), stocktake(hq))) {
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(code(r)).isEqualTo("branch_not_stocking");
            assertThat(r.getBody().get("detail").asString())
                    .contains("not stocking goods")
                    .doesNotContain("null");
        }
        // Goods stranded at the branch (a void or an old balance) can still leave: it may be a transfer source, and
        // take
        // usage and damage out (ADR-041); it still cannot take anything in.
        // With nothing on its shelves there is nothing to send: the source exit is for stranded goods only.
        assertThat(transfer(hq, t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        retail.importedBalance(hq, product, "3");
        assertThat(transfer(hq, t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(usage(hq).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The shop is untouched by the refusals and still trades.
        assertThat(retail.sell(t.secondBranch(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // It stays in /me and in the administrator's list (cash book and scope), with the flag.
        JsonNode me = api.get("/api/v1/me", admin.accessToken()).getBody();
        assertThat(codes(me, "branches")).containsExactly("HQ", "BR2");
        assertThat(me.get("branches").get(0).get("stocks_goods").asBoolean()).isFalse();
        assertThat(me.get("default_branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        // Switching it back on offers it again.
        assertThat(patch(hq, Map.of("stocks_goods", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restock(hq).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** A stock-take started and not committed would write stock into the branch later, so it blocks marking the branch. */
    @Test
    void notStockingIsRefusedWhileAStockTakeIsOpen() {
        UUID draft = RetailTestSupport.id(stocktake(t.headOffice()));
        ResponseEntity<JsonNode> refused = patch(t.headOffice(), Map.of("stocks_goods", false));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("branch_still_has_stock");
        assertThat(refused.getBody().get("open_stocktakes").asInt()).isEqualTo(1);
        assertThat(refused.getBody().get("detail").asString()).contains("stock-take");
        assertThat(retail.post("/stocktakes/" + draft + "/commit", Map.of(), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** Marking a branch as not stocking is refused while goods remain, so the stock is not stranded. */
    @Test
    void notStockingIsRefusedWhileTheBranchHoldsStock() {
        retail.stockUp(t.headOffice(), product, "3");
        ResponseEntity<JsonNode> refused = patch(t.headOffice(), Map.of("stocks_goods", false));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("branch_still_has_stock");
        assertThat(refused.getBody().get("stock_value_minor").asLong()).isEqualTo(3_000L);
        assertThat(etag(t.headOffice())).isEqualTo("\"1\"");

        // Move all of it to the shop, and then the head office can be marked.
        ResponseEntity<JsonNode> moved = retail.postKeyed(
                "/transfers",
                Map.of(
                        "from_branch_id",
                        t.headOffice(),
                        "to_branch_id",
                        t.secondBranch(),
                        "lines",
                        List.of(Map.of("product_id", product, "qty", 3))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(patch(t.headOffice(), Map.of("stocks_goods", false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(audits("core.branch.updated", t.headOffice())).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------- permission and isolation

    /** Only a holder of core.branches.manage closes; a scoped officer and another tenant's administrator cannot. */
    @Test
    void permissionAndTenantIsolationHold() {
        String email = Api.email("officer");
        Session officer =
                api.signIn(Api.staff(t, email, new Role("loan_officer", t.secondBranch())), email, Api.PASSWORD, null);
        ResponseEntity<JsonNode> byOfficer = api.call(
                HttpMethod.POST,
                "/api/v1/branches/" + t.secondBranch() + "/close",
                null,
                officer.accessToken(),
                Map.of("If-Match", "\"1\""));
        assertThat(byOfficer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        TestDatabase.Fixture other = TestDatabase.tenant("branch-close-other", false, true);
        Api otherApi = Api.tenant(http, other.slug());
        String otherEmail = Api.email("otheradmin");
        Session otherAdmin = otherApi.signIn(
                Api.staff(other, otherEmail, new Role("tenant_admin", null)), otherEmail, Api.PASSWORD, null);
        ResponseEntity<JsonNode> foreign = otherApi.call(
                HttpMethod.POST,
                "/api/v1/branches/" + t.secondBranch() + "/close",
                null,
                otherAdmin.accessToken(),
                Map.of("If-Match", "\"1\""));
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otherApi.call(
                                HttpMethod.PATCH,
                                "/api/v1/branches/" + t.secondBranch(),
                                Map.of("stocks_goods", false),
                                otherAdmin.accessToken(),
                                Map.of("If-Match", "\"1\""))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(etag(t.secondBranch())).isEqualTo("\"1\"");
        assertThat(audits("core.branch.closed", t.secondBranch())).isZero();
    }
}
