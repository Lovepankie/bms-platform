package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Controls page, credit write-offs (issue #357; ADR-087). Product WC-1 costs 600 and sells for 1,000. Test Seller One
 * sells on credit at head office three times (1, 2 and 3 pieces) and once at branch two (1 piece), and once for cash at head
 * office; the buyer of the 3-piece sale pays 1,000 of it. Test Admin One writes off the 3-piece sale (2,000 still owed) and the
 * branch two sale. The value is what the buyer still owed, never the loss; the person is whose sale it was and the approver who
 * wrote it off; the rate is sales written off per 100 sales entered. The Kampala day and the maker-checker wait are in
 * RetailControlsWriteOffClockIT, which moves the application's clock. Everything is fabricated.
 */
class RetailControlsWriteOffsIT extends IntegrationTest {

    static final String CONTROLS = "retail.controls.read,retail.sale.read";
    static final String WRITE_OFF = ADMIN + ",retail.credit.manage,retail.credit.write_off,core.approvals.read";
    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport seller;
    RetailTestSupport admin;
    UUID product;
    UUID partlyPaid;
    UUID branchTwoSale;

    static LocalDate today() {
        return LocalDate.now(KAMPALA);
    }

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-ctl-wo", false, true);
        seller = new RetailTestSupport(http, t);
        admin = new RetailTestSupport(http, t);
        RetailControlsIT.user(t, seller.userId(), "Test Seller One", "active");
        RetailControlsIT.user(t, admin.userId(), "Test Admin One", "active");
        product = seller.product("WC-1", 600, 1_000);
        seller.importedBalance(t.headOffice(), product, "100");
        seller.importedBalance(t.secondBranch(), product, "100");
        sell(t.headOffice(), 1, "credit");
        sell(t.headOffice(), 2, "credit");
        partlyPaid = sell(t.headOffice(), 3, "credit");
        branchTwoSale = sell(t.secondBranch(), 1, "credit");
        sell(t.headOffice(), 1, "cash");
        ResponseEntity<JsonNode> paid = seller.postKeyed(
                "/sales/" + partlyPaid + "/payments",
                Map.of("amount_minor", 1_000, "method", "cash", "paid_on", today().toString()),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode().is2xxSuccessful())
                .as("%s", paid.getBody())
                .isTrue();
        writeOff(partlyPaid, "Test buyer moved away");
        writeOff(branchTwoSale, "Test buyer closed the shop");
    }

    UUID sell(UUID branch, int qty, String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", String.valueOf(qty))));
        ResponseEntity<JsonNode> r =
                seller.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    void writeOff(UUID sale, String reason) {
        ResponseEntity<JsonNode> r = admin.postKeyed(
                "/sales/" + sale + "/write-off",
                Map.of("reason", reason),
                WRITE_OFF,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("executed").asBoolean()).isTrue();
    }

    JsonNode get(String path, Map<String, String> extra) {
        ResponseEntity<JsonNode> r = seller.call(HttpMethod.GET, "/controls" + path, null, CONTROLS, "*", extra);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void aWriteOffIsCountedAtWhatTheBuyerStillOwedAndRatedOverTheSalesEntered() {
        JsonNode s = get("/summary", Map.of());
        // Five sales entered (four on credit, one for cash): 1,000 + 2,000 + 3,000 + 1,000 + 1,000, all completed.
        assertThat(s.get("period").get("sales_count").asLong()).isEqualTo(5);
        assertThat(s.get("period").get("sales_minor").asLong()).isEqualTo(8_000);
        JsonNode c = RetailControlsStockIT.card(s, "credit_writeoffs");
        assertThat(c.get("tracked").asBoolean()).isTrue();
        assertThat(c.get("count").asLong()).isEqualTo(2);
        assertThat(c.get("sales_affected").asLong()).isEqualTo(2);
        // 2,000 still owed on the 3-piece sale and 1,000 on branch two's: never the loss (the cost under on_payment).
        assertThat(c.get("value_minor").asLong()).isEqualTo(3_000);
        // 2 sales written off out of 5 entered: 40.0 per 100; 3,000 of 8,000 sold is 3,750 basis points.
        assertThat(c.get("rate_per_100").asString()).isEqualTo("40.0");
        assertThat(c.get("share_of_sales_bp").asInt()).isEqualTo(3_750);
        assertThat(c.get("previous_count").asLong()).isZero();
        assertThat(s.toString()).doesNotContain("loss_minor").doesNotContain("cost_share");
    }

    @Test
    void theSellerIsThePersonAndTheAdministratorWhoWroteItOffIsTheApprover() {
        JsonNode records = get("/records?metric=credit_writeoffs&branch_id=" + t.headOffice(), Map.of());
        assertThat(records.get("items")).hasSize(1);
        JsonNode r = records.get("items").get(0);
        assertThat(r.get("sale_id").asString()).isEqualTo(partlyPaid.toString());
        assertThat(r.get("sale_no").asString()).isNotBlank();
        assertThat(r.get("user_name").asString()).isEqualTo("Test Seller One");
        assertThat(r.get("approver_name").asString()).isEqualTo("Test Admin One");
        assertThat(r.get("reason").asString()).isEqualTo("Test buyer moved away");
        assertThat(r.get("value_minor").asLong()).isEqualTo(2_000);

        // By person: the seller's 2 written off over the 5 sales they entered; by approver: counted, with no rate.
        JsonNode people = get("/groups?metric=credit_writeoffs&group_by=user", Map.of());
        JsonNode one = RetailControlsIT.group(people, seller.userId().toString());
        assertThat(one.get("count").asLong()).isEqualTo(2);
        assertThat(one.get("sales_count").asLong()).isEqualTo(5);
        assertThat(one.get("rate_per_100").asString()).isEqualTo("40.0");
        JsonNode approvers = get("/groups?metric=credit_writeoffs&group_by=approver", Map.of());
        JsonNode adm = RetailControlsIT.group(approvers, admin.userId().toString());
        assertThat(adm.get("count").asLong()).isEqualTo(2);
        assertThat(adm.has("rate_per_100")).isFalse();
        // By reason: the text typed.
        JsonNode reasons = get("/groups?metric=credit_writeoffs&group_by=reason", Map.of());
        assertThat(RetailControlsIT.group(reasons, "test buyer moved away")
                        .get("count")
                        .asLong())
                .isEqualTo(1);
    }

    @Test
    void theBranchScopeNarrowsThemAndThePermissionIsNeeded() {
        Map<String, String> branchTwo = Map.of("X-Dev-Scopes", "retail.controls.read=" + t.secondBranch());
        JsonNode c = RetailControlsStockIT.card(get("/summary", branchTwo), "credit_writeoffs");
        assertThat(c.get("count").asLong()).isEqualTo(1);
        assertThat(c.get("value_minor").asLong()).isEqualTo(1_000);
        // One sale entered at branch two and it was written off: 100.0 per 100.
        assertThat(c.get("rate_per_100").asString()).isEqualTo("100.0");
        JsonNode records = get("/records?metric=credit_writeoffs", branchTwo);
        assertThat(records.get("items")).hasSize(1);
        assertThat(records.get("items").get(0).get("sale_id").asString()).isEqualTo(branchTwoSale.toString());
        // Head office is outside that scope: asking for it gives nothing.
        assertThat(get("/records?metric=credit_writeoffs&branch_id=" + t.headOffice(), branchTwo)
                        .get("items"))
                .isEmpty();

        ResponseEntity<JsonNode> refused =
                seller.call(HttpMethod.GET, "/controls/records?metric=credit_writeoffs", null, ADMIN, "*", Map.of());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
