package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHIER;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * Issue #811, ADR-120: the app sets the date of every entry. For each form (expenses, savings, banking, withdrawals,
 * advances and their repayments, Buy stock, Move stock) a caller without {@code retail.entry.backdate} is refused an earlier
 * date (403 {@code date_not_today}, nothing written) and gets today when sending none; the owner may send an earlier date
 * only with a reason, and the backdate is audited. Bodies go out exactly as written here (no reason is added by the test
 * support). Names and amounts are fabricated.
 */
class RetailEntryDatesIT extends IntegrationTest {

    /** Everything an administrator holds except the owner's backdate: the strongest caller who is still refused. */
    static final String NOT_OWNER = Arrays.stream(ALL.split(","))
            .filter(p -> !p.equals("retail.entry.backdate"))
            .collect(Collectors.joining(","));

    static final String REASON = "Receipt found in the drawer";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;
    UUID product;
    UUID rent;
    UUID rentItem;
    UUID owner;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("entry-dates", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        product = cb.api.product("ENTRY-CABLE", 1_000, 1_500);
        cb.api.stockUp(hq, product, "100");
        UUID[] c = cb.categoryWithItem("Premises", "Shop rent", false);
        rent = c[0];
        rentItem = c[1];
        owner = cb.party("Test Owner 01", "owner");
    }

    /** A keyed POST with the body exactly as given. */
    ResponseEntity<JsonNode> send(String path, Map<String, Object> body, String permissions) {
        return send(path, body, permissions, "*");
    }

    /** As above, with every permission scoped to {@code branches}. */
    ResponseEntity<JsonNode> send(String path, Map<String, Object> body, String permissions, String branches) {
        return http.exchange(
                RetailTestSupport.BASE + path,
                HttpMethod.POST,
                new HttpEntity<>(
                        body,
                        cb.api.headers(
                                permissions,
                                branches,
                                Map.of("Idempotency-Key", UUID.randomUUID().toString()))),
                JsonNode.class);
    }

    static LocalDate today() {
        return LocalDate.now(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE);
    }

    static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(field, value.toString());
        return copy;
    }

    /**
     * The four cases of one form: the caller without the permission is refused an earlier date and gets today without
     * one; the owner without a reason is refused; the owner with a reason gets the earlier date and the audit row.
     */
    void provesTheRule(
            String path,
            String dateField,
            String entityType,
            Function<LocalDate, Map<String, Object>> bodyFor,
            String notOwner) {
        LocalDate earlier = daysAgo(2);
        int auditsBefore = cb.auditPayloads("retail.entry.backdated").size();
        int createdBefore = cb.auditPayloads(entityType + ".created").size();

        ResponseEntity<JsonNode> refused = send(path, with(bodyFor.apply(earlier), dateField, earlier), notOwner);
        assertThat(refused.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("date_not_today");
        assertThat(refused.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo(dateField);
        // A reason does not stand in for the permission.
        Map<String, Object> withReason = with(with(bodyFor.apply(earlier), dateField, earlier), "date_reason", REASON);
        assertThat(send(path, withReason, notOwner).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        // Nothing was written by either refusal.
        assertThat(cb.auditPayloads(entityType + ".created")).as(path).hasSize(createdBefore);

        ResponseEntity<JsonNode> today = send(path, bodyFor.apply(today()), notOwner);
        assertThat(today.getStatusCode()).as(path + " " + today.getBody()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> noReason = send(path, with(bodyFor.apply(earlier), dateField, earlier), ALL);
        assertThat(noReason.getStatusCode()).as(path).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noReason.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("date_reason");
        // Today's entry only: the owner's refusal wrote nothing either.
        assertThat(cb.auditPayloads(entityType + ".created")).as(path).hasSize(createdBefore + 1);

        ResponseEntity<JsonNode> backdated = send(path, withReason, ALL);
        assertThat(backdated.getStatusCode())
                .as(path + " " + backdated.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(backdated.getBody().get(dateField).asString()).isEqualTo(earlier.toString());
        List<String> audits = cb.auditPayloads("retail.entry.backdated");
        assertThat(audits).hasSize(auditsBefore + 1);
        String enteredOn = today().toString();
        assertThat(audits)
                .anyMatch(a -> a.contains(REASON)
                        && a.contains("\"date\": \"" + earlier + "\"")
                        && a.contains("\"entry\": \"" + entityType + "\"")
                        && a.contains("\"entered_on\": \"" + enteredOn + "\""));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.entry.backdated'"
                                + " AND entity_id = ?")
                        .params(t.tenantId(), RetailTestSupport.id(backdated))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void anExpenseIsDatedTodayUnlessTheOwnerGivesAReason() {
        provesTheRule(
                "/expenses",
                "business_date",
                "retail.expense",
                d -> new LinkedHashMap<>(
                        Map.of("branch_id", hq, "category_id", rent, "item_id", rentItem, "amount_minor", 4_000)),
                CASHIER);
    }

    @Test
    void anExpenseWithNoDateIsDatedTodayByTheServer() {
        ResponseEntity<JsonNode> r = send(
                "/expenses",
                new LinkedHashMap<>(
                        Map.of("branch_id", hq, "category_id", rent, "item_id", rentItem, "amount_minor", 4_000)),
                CASHIER);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("business_date").asString()).isEqualTo(today().toString());
        assertThat(cb.auditPayloads("retail.entry.backdated")).isEmpty();
    }

    @Test
    void savingsAreDatedTodayUnlessTheOwnerGivesAReason() {
        LocalDate earlier = daysAgo(2);
        assertThat(cb.sale(hq, "cash", product, "10", earlier).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.sale(hq, "cash", product, "10", today()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        provesTheRule(
                "/savings",
                "business_date",
                "retail.savings",
                d -> {
                    String token = cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + d, ALL)
                            .getBody()
                            .get("suggestion_token")
                            .asString();
                    return new LinkedHashMap<>(Map.of("branch_id", hq, "suggestion_token", token));
                },
                CASHIER);
    }

    @Test
    void aBankingIsDatedTodayUnlessTheOwnerGivesAReason() {
        provesTheRule(
                "/bankings",
                "business_date",
                "retail.banking",
                d -> new LinkedHashMap<>(Map.of("branch_id", hq, "amount_minor", 1_000)),
                CASHIER);
    }

    @Test
    void aBankingOrWithdrawalTimeOnAnEarlierDayIsTheOwnersAndIsAudited() {
        java.time.Instant twoDaysAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(2));
        LocalDate thatDay = twoDaysAgo
                .atZone(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE)
                .toLocalDate();
        for (String[] form : List.of(
                new String[] {"/bankings", "banked_at", "retail.banking", CASHIER},
                new String[] {"/withdrawals", "withdrawn_at", "retail.withdrawal", NOT_OWNER})) {
            Map<String, Object> body =
                    new LinkedHashMap<>(Map.of("branch_id", hq, "amount_minor", 1_000, form[1], twoDaysAgo.toString()));

            ResponseEntity<JsonNode> refused = send(form[0], body, form[3]);
            assertThat(refused.getStatusCode()).as(form[0]).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody().get("errors").get(0).get("field").asString())
                    .isEqualTo(form[1]);

            // The owner, no business date (so today), the time two days ago: allowed with a reason, and audited.
            ResponseEntity<JsonNode> made = send(form[0], with(body, "date_reason", REASON), ALL);
            assertThat(made.getStatusCode()).as(form[0] + " " + made.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(cb.auditPayloads("retail.entry.backdated"))
                    .as(form[0])
                    .anyMatch(a -> a.contains("\"entry\": \"" + form[2] + "\"")
                            && a.contains("\"date\": \"" + thatDay + "\"")
                            && a.contains(REASON));
        }
    }

    @Test
    void aMoveThatCompletesNowNeedsTheOwnersDateAtTheDestinationToo() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "from_branch_id",
                hq,
                "to_branch_id",
                t.secondBranch(),
                "transfer_date",
                daysAgo(2).toString(),
                "date_reason",
                REASON,
                "lines",
                List.of(Map.of("product_id", product, "qty", "1"))));

        // Every permission, the owner's date included, at the head office only: the second shop's day is not theirs.
        ResponseEntity<JsonNode> r = send("/transfers", body, ALL, hq.toString());

        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("code").asString()).isEqualTo("date_not_today");
        assertThat(send("/transfers", body, ALL).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aWithdrawalIsDatedTodayUnlessTheOwnerGivesAReason() {
        provesTheRule(
                "/withdrawals",
                "business_date",
                "retail.withdrawal",
                d -> new LinkedHashMap<>(Map.of("branch_id", hq, "amount_minor", 1_000)),
                NOT_OWNER);
    }

    @Test
    void anAdvanceIsDatedTodayUnlessTheOwnerGivesAReason() {
        provesTheRule(
                "/advances",
                "business_date",
                "retail.advance",
                d -> new LinkedHashMap<>(Map.of("branch_id", hq, "party_id", owner, "principal_minor", 5_000)),
                NOT_OWNER);
    }

    @Test
    void aRepaymentIsDatedTodayUnlessTheOwnerGivesAReason() {
        Map<String, Object> advance = new LinkedHashMap<>(Map.of(
                "branch_id",
                hq,
                "party_id",
                owner,
                "principal_minor",
                50_000,
                "business_date",
                daysAgo(5).toString(),
                "date_reason",
                REASON));
        UUID id = RetailTestSupport.id(send("/advances", advance, ALL));
        provesTheRule(
                "/advances/" + id + "/repayments",
                "paid_on",
                "retail.advance_repayment",
                d -> new LinkedHashMap<>(Map.of("branch_id", hq, "amount_minor", 1_000, "method", "cash")),
                NOT_OWNER);
    }

    @Test
    void aPurchaseIsDatedTodayUnlessTheOwnerGivesAReason() {
        provesTheRule(
                "/purchases",
                "purchased_on",
                "retail.purchase",
                d -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("product_id", product);
                    line.put("cost_minor", 1_000);
                    line.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", "2")));
                    return new LinkedHashMap<>(Map.of("payment_method", "cash", "lines", List.of(line)));
                },
                NOT_OWNER);
    }

    @Test
    void aStockMoveIsDatedTodayUnlessTheOwnerGivesAReason() {
        provesTheRule(
                "/transfers",
                "transfer_date",
                "retail.transfer",
                d -> new LinkedHashMap<>(Map.of(
                        "from_branch_id",
                        hq,
                        "to_branch_id",
                        t.secondBranch(),
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "1")))),
                NOT_OWNER);
    }

    @Test
    void aFutureDateIsStillRefusedForTheOwner() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "branch_id",
                hq,
                "category_id",
                rent,
                "item_id",
                rentItem,
                "amount_minor",
                4_000,
                "business_date",
                daysAgo(-1).toString(),
                "date_reason",
                REASON));

        ResponseEntity<JsonNode> r = send("/expenses", body, ALL);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");
    }
}
