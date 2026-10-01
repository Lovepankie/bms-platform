package com.rincoltech.bms.lending.products;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Loan products through HTTP (#40; FR-PRD-01 to FR-PRD-05, FR-AUD-01, chapter 7 section 7.11.12).
 * The schedule arithmetic itself is proven in {@link ScheduleCalculatorTest}; here the preview
 * shows the same worked examples come out of the endpoint. All figures are fabricated.
 */
class LoanProductsIT extends IntegrationTest {

    static final String BASE = "/api/v1/lending/loan-products";
    static final String MANAGE = "lending.products.read,lending.products.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("products", true);
    }

    HttpHeaders headers(String permissions, String ifMatch) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        if (ifMatch != null) {
            h.add(HttpHeaders.IF_MATCH, ifMatch);
        }
        return h;
    }

    ResponseEntity<JsonNode> send(HttpMethod method, String path, String ifMatch, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(MANAGE, ifMatch)), JsonNode.class);
    }

    /** The pilot shape of worked example A: flat, a rate for the whole term, one payment after a month. */
    static Map<String, Object> bulletTerms() {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("interest_method", "flat");
        terms.put("interest_rate_bp", 2000);
        terms.put("rate_unit", "per_term");
        terms.put("term_unit", "month");
        terms.put("min_term_count", 1);
        terms.put("max_term_count", 3);
        terms.put("default_term_count", 1);
        terms.put("repayment_pattern", "bullet");
        terms.put("min_principal_minor", 100_000);
        terms.put("max_principal_minor", 5_000_000);
        return terms;
    }

    static Map<String, Object> product(String code, Map<String, Object> terms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Test Product " + code);
        body.put("terms", terms);
        return body;
    }

    static Map<String, Object> with(Map<String, Object> terms, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(terms);
        copy.put(key, value);
        return copy;
    }

    /** FR-PRD-01: a product is created with its version 1, defaults filled, and audited. */
    @Test
    void aProductIsCreatedWithItsFirstVersion() {
        ResponseEntity<JsonNode> created = send(HttpMethod.POST, BASE, null, product("BULLET-1M", bulletTerms()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getETag()).isEqualTo("\"1\"");
        JsonNode p = created.getBody();
        JsonNode v = p.get("current_version");
        assertThat(p.get("status").asString()).isEqualTo("active");
        assertThat(v.get("version_no").asInt()).isEqualTo(1);
        assertThat(v.get("currency").asString()).isEqualTo("UGX");
        assertThat(v.get("penalty_method").asString()).isEqualTo("none");
        assertThat(v.get("instalment_frequency").isNull()).isTrue();
        assertThat(v.get("allocation_order")
                        .valueStream()
                        .map(JsonNode::asString)
                        .toList())
                .containsExactly("penalty", "fee", "interest", "principal");

        assertThat(send(HttpMethod.GET, BASE, null, null).getBody().get("items"))
                .hasSize(1);
        String action = TestDatabase.owner()
                .sql("SELECT action FROM audit_log WHERE entity_id = ?::uuid")
                .param(p.get("id").asString())
                .query(String.class)
                .single();
        assertThat(action).isEqualTo("lending.product.created");

        ResponseEntity<JsonNode> again = send(HttpMethod.POST, BASE, null, product("BULLET-1M", bulletTerms()));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("duplicate_product_code");
    }

    /** FR-PRD-01: combinations that break R-RATE or R-TERM are refused at save with the rule code. */
    @Test
    void invalidCombinationsAreRefusedWithTheRuleCode() {
        Map<String, Object> rate = with(bulletTerms(), "rate_unit", "per_week");
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, BASE, null, product("BAD-RATE", rate));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("invalid_rate_unit");

        Map<String, Object> weekly =
                with(with(bulletTerms(), "repayment_pattern", "instalments"), "instalment_frequency", "weekly");
        assertThat(send(HttpMethod.POST, BASE, null, product("BAD-FREQ", weekly))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("invalid_term_frequency");

        Map<String, Object> noFrequency = with(bulletTerms(), "repayment_pattern", "instalments");
        assertThat(send(HttpMethod.POST, BASE, null, product("NO-FREQ", noFrequency))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("invalid_term_frequency");

        Map<String, Object> fortnightly = new LinkedHashMap<>(bulletTerms());
        fortnightly.put("term_unit", "week");
        fortnightly.put("repayment_pattern", "instalments");
        fortnightly.put("instalment_frequency", "fortnightly");
        fortnightly.put("default_term_count", 3);
        fortnightly.put("max_term_count", 4);
        assertThat(send(HttpMethod.POST, BASE, null, product("ODD-WEEKS", fortnightly))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("invalid_term_frequency");

        Map<String, Object> range = with(bulletTerms(), "default_term_count", 9);
        ResponseEntity<JsonNode> badRange = send(HttpMethod.POST, BASE, null, product("BAD-RANGE", range));
        assertThat(badRange.getBody().get("code").asString()).isEqualTo("validation_failed");
        assertThat(badRange.getBody().get("errors").findValuesAsString("field")).contains("default_term_count");

        Map<String, Object> penalty = with(bulletTerms(), "penalty_method", "flat_per_period");
        assertThat(send(HttpMethod.POST, BASE, null, product("BAD-PEN", penalty))
                        .getBody()
                        .get("errors")
                        .findValuesAsString("field"))
                .contains("penalty_period_unit", "penalty_flat_minor");

        Map<String, Object> fee = with(
                bulletTerms(),
                "fees",
                List.of(Map.of(
                        "name",
                        "Test fee",
                        "fee_type",
                        "processing",
                        "calc_method",
                        "flat",
                        "rate_bp",
                        100,
                        "timing",
                        "paid_upfront")));
        assertThat(send(HttpMethod.POST, BASE, null, product("BAD-FEE", fee)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        Map<String, Object> order =
                with(bulletTerms(), "allocation_order", List.of("fee", "fee", "interest", "principal"));
        assertThat(send(HttpMethod.POST, BASE, null, product("BAD-ORDER", order))
                        .getBody()
                        .get("errors")
                        .findValuesAsString("field"))
                .contains("allocation_order");
    }

    /** FR-PRD-04: editing writes a new version; the old one is kept unchanged. */
    @Test
    void editingCreatesANewVersionAndKeepsTheOld() {
        String id = send(HttpMethod.POST, BASE, null, product("EDIT-ME", bulletTerms()))
                .getBody()
                .get("id")
                .asString();
        String versions = BASE + "/" + id + "/versions";

        assertThat(send(HttpMethod.POST, versions, null, with(bulletTerms(), "interest_rate_bp", 1500))
                        .getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        ResponseEntity<JsonNode> edited =
                send(HttpMethod.POST, versions, "\"1\"", with(bulletTerms(), "interest_rate_bp", 1500));
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getHeaders().getETag()).isEqualTo("\"2\"");
        assertThat(edited.getBody().get("current_version").get("version_no").asInt())
                .isEqualTo(2);
        assertThat(edited.getBody()
                        .get("current_version")
                        .get("interest_rate_bp")
                        .asInt())
                .isEqualTo(1500);

        JsonNode history =
                send(HttpMethod.GET, BASE + "/" + id, null, null).getBody().get("versions");
        assertThat(history).hasSize(2);
        assertThat(history.get(1).get("version_no").asInt()).isEqualTo(1);
        assertThat(history.get(1).get("interest_rate_bp").asInt()).isEqualTo(2000);

        assertThat(send(HttpMethod.POST, versions, "\"1\"", bulletTerms())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("version_conflict");
    }

    /** FR-PRD-05: an archived product takes no more edits. */
    @Test
    void anArchivedProductIsFrozen() {
        String id = send(HttpMethod.POST, BASE, null, product("ARCHIVE-ME", bulletTerms()))
                .getBody()
                .get("id")
                .asString();
        ResponseEntity<JsonNode> archived = send(HttpMethod.POST, BASE + "/" + id + "/archive", "\"1\"", null);
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archived.getBody().get("status").asString()).isEqualTo("archived");

        ResponseEntity<JsonNode> edit = send(HttpMethod.POST, BASE + "/" + id + "/versions", "\"2\"", bulletTerms());
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(edit.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
    }

    static Map<String, Object> preview(
            String method, int bp, String rateUnit, int count, String pattern, long principal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interest_method", method);
        body.put("interest_rate_bp", bp);
        body.put("rate_unit", rateUnit);
        body.put("term_unit", "month");
        body.put("term_count", count);
        body.put("repayment_pattern", pattern);
        body.put("instalment_frequency", pattern.equals("bullet") ? null : "monthly");
        body.put("principal_minor", principal);
        body.put("disbursement_date", "2026-03-16");
        return body;
    }

    /** FR-PRD-03: the preview returns worked examples A, B and C, and needs no saved product. */
    @Test
    void thePreviewReturnsTheWorkedExamples() {
        String path = BASE + "/schedule-preview";
        JsonNode a = send(HttpMethod.POST, path, null, preview("flat", 2000, "per_term", 1, "bullet", 500_000))
                .getBody();
        assertThat(a.get("items")).hasSize(1);
        assertThat(a.get("items").get(0).get("due_date").asString()).isEqualTo("2026-04-16");
        assertThat(a.get("items").get(0).get("total_minor").asLong()).isEqualTo(600_000);

        JsonNode b = send(HttpMethod.POST, path, null, preview("flat", 1000, "per_month", 3, "instalments", 1_200_000))
                .getBody();
        assertThat(b.get("items")
                        .valueStream()
                        .map(i -> i.get("total_minor").asLong())
                        .toList())
                .containsExactly(520_000L, 520_000L, 520_000L);
        assertThat(b.get("total_interest_minor").asLong()).isEqualTo(360_000);

        JsonNode c = send(
                        HttpMethod.POST,
                        path,
                        null,
                        preview("declining", 1000, "per_month", 3, "instalments", 1_000_000))
                .getBody();
        assertThat(c.get("items")
                        .valueStream()
                        .map(i -> i.get("interest_minor").asLong())
                        .toList())
                .containsExactly(100_000L, 69_789L, 36_556L);
        assertThat(c.get("total_due_minor").asLong()).isEqualTo(1_206_345);
    }

    /** FR-PRD-02: each fee timing shows where it falls: in the schedule, off the disbursement, or upfront. */
    @Test
    void thePreviewPlacesEachFeeByItsTiming() {
        Map<String, Object> body = preview("flat", 1000, "per_month", 3, "instalments", 1_200_000);
        body.put(
                "fees",
                List.of(
                        Map.of(
                                "name",
                                "Test insurance",
                                "fee_type",
                                "insurance",
                                "calc_method",
                                "flat",
                                "amount_minor",
                                30_000,
                                "timing",
                                "added_to_loan"),
                        Map.of(
                                "name",
                                "Test processing",
                                "fee_type",
                                "processing",
                                "calc_method",
                                "percent_of_principal",
                                "rate_bp",
                                100,
                                "timing",
                                "deducted_at_disbursement"),
                        Map.of(
                                "name",
                                "Test application",
                                "fee_type",
                                "application",
                                "calc_method",
                                "flat",
                                "amount_minor",
                                5_000,
                                "timing",
                                "paid_upfront")));
        JsonNode p =
                send(HttpMethod.POST, BASE + "/schedule-preview", null, body).getBody();
        assertThat(p.get("items")
                        .valueStream()
                        .map(i -> i.get("fee_minor").asLong())
                        .toList())
                .containsExactly(10_000L, 10_000L, 10_000L);
        assertThat(p.get("total_fees_minor").asLong()).isEqualTo(30_000);
        assertThat(p.get("deducted_at_disbursement_minor").asLong()).isEqualTo(12_000);
        assertThat(p.get("net_disbursed_minor").asLong()).isEqualTo(1_188_000);
        assertThat(p.get("paid_upfront_minor").asLong()).isEqualTo(5_000);

        ResponseEntity<JsonNode> bad = send(
                HttpMethod.POST,
                BASE + "/schedule-preview",
                null,
                preview("flat", 1000, "per_week", 3, "instalments", 1_200_000));
        assertThat(bad.getBody().get("code").asString()).isEqualTo("invalid_rate_unit");
    }

    /** Products are per tenant: another tenant sees none of them. */
    @Test
    void anotherTenantSeesNoProducts() {
        send(HttpMethod.POST, BASE, null, product("MINE", bulletTerms()));
        TestDatabase.Fixture other = TestDatabase.tenant("products-other", true);
        HttpHeaders h = headers(MANAGE, null);
        h.set("X-Tenant", other.slug());
        JsonNode items = http.exchange(BASE, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class)
                .getBody()
                .get("items");
        assertThat(items).isEmpty();
    }
}
