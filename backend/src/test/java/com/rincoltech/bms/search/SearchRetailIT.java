package com.rincoltech.bms.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * One matching convention on every retail list (ADR-039, docs/specs/search.md): products, stock,
 * credit buyers, sales and cash parties match case and accent insensitively, as an AND of terms,
 * with phone numbers in any written form and LIKE characters literal, rank prefix matches first and
 * never reach a row outside the caller's branches. All data fabricated.
 */
class SearchRetailIT extends IntegrationTest {

    static final String BOOKKEEPER = com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_ADMIN;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID ledBulb;
    UUID ledger;
    UUID cable;
    UUID table;
    UUID cotton;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("search-retail", false, true);
        api = new RetailTestSupport(http, t);
        ledBulb = product("LED-9W", "Bulb 9W screw", "Lighting");
        ledger = product("LEDGER-1", "Ledger book", "Stationery");
        cable = product("CAB-2", "Cable ledwire 2.5mm", "Wiring");
        table = product("TBL-1", "Café table", "Furniture");
        cotton = product("PCT-100", "Cotton 100% blend", "Fabric");
        for (UUID p : List.of(ledBulb, ledger, cable, table, cotton)) {
            api.stockUp(t.headOffice(), p, "10");
        }
    }

    UUID product(String code, String description, String category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("description", description);
        body.put("category_id", api.category(category));
        body.put("unit_id", api.unit("u-" + code.toLowerCase()));
        body.put("cost_minor", 100);
        body.put("sell_minor", 200);
        return RetailTestSupport.id(api.post("/products", body, RetailTestSupport.ADMIN));
    }

    URI uri(String path, String param, String q, Integer limit, String cursor) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(RetailTestSupport.BASE + path);
        Map<String, Object> vars = new LinkedHashMap<>();
        if (q != null) {
            b.queryParam(param, "{q}");
            vars.put("q", q);
        }
        if (limit != null) {
            b.queryParam("limit", "{limit}");
            vars.put("limit", limit);
        }
        if (cursor != null) {
            b.queryParam("cursor", "{cursor}");
            vars.put("cursor", cursor);
        }
        return b.encode().buildAndExpand(vars).toUri();
    }

    ResponseEntity<JsonNode> page(
            String path, String param, String q, Integer limit, String cursor, String perms, String branches) {
        return http.exchange(
                uri(path, param, q, limit, cursor),
                HttpMethod.GET,
                new HttpEntity<>(api.headers(perms, branches, Map.of())),
                JsonNode.class);
    }

    List<String> list(String path, String param, String q, String field, String perms, String branches) {
        ResponseEntity<JsonNode> r = page(path, param, q, 100, null, perms, branches);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        List<String> out = new ArrayList<>();
        r.getBody().get("items").forEach(i -> out.add(i.get(field).asString()));
        return out;
    }

    List<String> codes(String q) {
        return list("/products", "query", q, "code", RetailTestSupport.ADMIN, "*");
    }

    // ---- products and stock -----------------------------------------------------------------

    @Test
    void productsMatchAcrossCodeDescriptionAndCategoryAsAnAndOfTerms() {
        assertThat(codes("led screw bulb")).containsExactly("LED-9W");
        assertThat(codes("bulb led")).containsExactly("LED-9W");
        assertThat(codes("lighting")).containsExactly("LED-9W");
        assertThat(codes("lighting ledger")).isEmpty();
        assertThat(codes("stationery book")).containsExactly("LEDGER-1");
    }

    @Test
    void productsFoldAccentsAndCase() {
        assertThat(codes("cafe")).containsExactly("TBL-1");
        assertThat(codes("CAFÉ TABLE")).containsExactly("TBL-1");
        assertThat(codes("café")).containsExactly("TBL-1");
    }

    @Test
    void productsTakeLikeCharactersLiterally() {
        assertThat(codes("100%")).containsExactly("PCT-100");
        assertThat(codes("%")).containsExactly("PCT-100");
        assertThat(codes("_")).isEmpty();
        assertThat(codes("\\")).isEmpty();
    }

    @Test
    void productsRankPrefixThenWordStartThenSubstring() {
        // LED-9W and LEDGER-1 start with the term, "Cable ledwire" has a word that starts with it
        assertThat(codes("led")).containsExactly("LED-9W", "LEDGER-1", "CAB-2");
        // a substring inside a word ranks last
        assertThat(codes("edge")).containsExactly("LEDGER-1");
        assertThat(codes("wire")).containsExactly("CAB-2");
    }

    @Test
    void pagingProductsKeepsTheRankedOrder() {
        List<String> all = codes("led");
        List<String> paged = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 6; i++) {
            ResponseEntity<JsonNode> r = page("/products", "query", "led", 1, cursor, RetailTestSupport.ADMIN, "*");
            r.getBody().get("items").forEach(item -> paged.add(item.get("code").asString()));
            JsonNode next = r.getBody().get("next_cursor");
            cursor = next == null || next.isNull() ? null : next.asString();
            if (cursor == null) {
                break;
            }
        }
        assertThat(paged).isEqualTo(all);
    }

    @Test
    void stockListsUseTheSameMatching() {
        String hq = t.headOffice().toString();
        assertThat(stockCodes("/stock", "led screw bulb", hq)).containsExactly("LED-9W");
        assertThat(stockCodes("/stock", "cafe", hq)).containsExactly("TBL-1");
        assertThat(stockCodes("/stock", "led", hq)).containsExactly("LED-9W", "LEDGER-1", "CAB-2");
        assertThat(stockCodes("/stock", "100%", hq)).containsExactly("PCT-100");
        assertThat(stockCodes("/stock/all-branches", "led", null)).containsExactly("LED-9W", "LEDGER-1", "CAB-2");
        assertThat(stockCodes("/stock/all-branches", "cafe", null)).containsExactly("TBL-1");
        // paged, the ranked order holds
        List<String> paged = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 6; i++) {
            ResponseEntity<JsonNode> r =
                    page("/stock/all-branches", "query", "led", 1, cursor, RetailTestSupport.ADMIN, "*");
            r.getBody().get("items").forEach(item -> paged.add(item.get("code").asString()));
            JsonNode next = r.getBody().get("next_cursor");
            cursor = next == null || next.isNull() ? null : next.asString();
            if (cursor == null) {
                break;
            }
        }
        assertThat(paged).containsExactly("LED-9W", "LEDGER-1", "CAB-2");
    }

    List<String> stockCodes(String path, String q, String branch) {
        String full = branch == null ? path : path + "?branch_id=" + branch;
        // the branch is part of the path for /stock; the text goes through the encoder in uri()
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(RetailTestSupport.BASE + path);
        Map<String, Object> vars = new LinkedHashMap<>();
        b.queryParam("query", "{q}");
        vars.put("q", q);
        if (branch != null) {
            b.queryParam("branch_id", "{b}");
            vars.put("b", branch);
        }
        b.queryParam("limit", "{l}");
        vars.put("l", 100);
        ResponseEntity<JsonNode> r = http.exchange(
                b.encode().buildAndExpand(vars).toUri(),
                HttpMethod.GET,
                new HttpEntity<>(api.headers(RetailTestSupport.ADMIN, "*", Map.of())),
                JsonNode.class);
        assertThat(r.getStatusCode()).as("%s %s", full, r.getBody()).isEqualTo(HttpStatus.OK);
        List<String> out = new ArrayList<>();
        r.getBody().get("items").forEach(i -> out.add(i.get("code").asString()));
        return out;
    }

    // ---- credit buyers ----------------------------------------------------------------------

    UUID customer(String name, String contact) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (contact != null) {
            body.put("contact", contact);
        }
        return RetailTestSupport.id(api.post("/customers", body, RetailTestSupport.ADMIN));
    }

    List<String> buyers(String q) {
        return list("/customers", "query", q, "name", RetailTestSupport.ADMIN, "*");
    }

    @Test
    void creditBuyersMatchNamePhoneAccentAndRankPrefixFirst() {
        customer("Amina Kato", "0700000111");
        customer("Kato Amos", "+256 700 000 333");
        customer("Mukato Brian", null);
        customer("José Mukasa", "0700000222");
        assertThat(buyers("kato")).containsExactly("Kato Amos", "Amina Kato", "Mukato Brian");
        assertThat(buyers("amina kato")).containsExactly("Amina Kato");
        assertThat(buyers("jose")).containsExactly("José Mukasa");
        for (String q : List.of("0700000333", "+256 700 000 333", "700000333", "256700000333", "0700 000 333")) {
            assertThat(buyers(q)).as("q=%s", q).containsExactly("Kato Amos");
        }
        assertThat(buyers("700000111")).containsExactly("Amina Kato");
        assertThat(buyers("%")).isEmpty();
        // paged
        List<String> paged = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 6; i++) {
            ResponseEntity<JsonNode> r = page("/customers", "query", "kato", 1, cursor, RetailTestSupport.ADMIN, "*");
            r.getBody().get("items").forEach(item -> paged.add(item.get("name").asString()));
            JsonNode next = r.getBody().get("next_cursor");
            cursor = next == null || next.isNull() ? null : next.asString();
            if (cursor == null) {
                break;
            }
        }
        assertThat(paged).containsExactly("Kato Amos", "Amina Kato", "Mukato Brian");
    }

    /** The whole phrase first, then every word starting a word, then the rest: "buyer b" and "john k". */
    @Test
    void severalWordsRankTheWholePhraseThenEveryWordAboveASubstring() {
        for (String name : List.of("Test Buyer A", "Test Buyer B", "Test Buyer C")) {
            customer(name, null);
        }
        assertThat(buyers("buyer b")).containsExactly("Test Buyer B", "Test Buyer A", "Test Buyer C");
        for (String name : List.of("Kato John", "Johnson Kimbugwe", "John Kato", "Jon Kasule")) {
            customer(name, null);
        }
        assertThat(buyers("john k")).containsExactly("John Kato", "Johnson Kimbugwe", "Kato John");
    }

    /** A number kept as free text is found by any way of writing its start: +256, 256, 0 or none. */
    @Test
    void freeTextContactsAreFoundByAnyPrefixOfTheNumber() {
        customer("Zed Zero", "0772123456");
        customer("Zed Plus", "+256 772 123456");
        customer("Zed Bare", "772123456");
        customer("Zed Other", "0701999999");
        List<String> all = List.of("Zed Bare", "Zed Plus", "Zed Zero");
        // +2567 and 2567 are the start of every Uganda mobile number, Zed Other's included
        for (String q : List.of("+2567", "2567")) {
            assertThat(buyers(q))
                    .as("customer %s", q)
                    .containsExactlyInAnyOrder("Zed Bare", "Zed Plus", "Zed Zero", "Zed Other");
        }
        for (String q : List.of(
                "+25677",
                "256772",
                "0772",
                "07721",
                "0772 123",
                "+256 772 123",
                "772123456",
                "0772123456",
                "+256772123456")) {
            assertThat(buyers(q)).as("customer %s", q).containsExactlyInAnyOrderElementsOf(all);
        }
        assertThat(buyers("0701")).containsExactly("Zed Other");
        // the middle of a number is not its start
        assertThat(buyers("0712")).isEmpty();
        assertThat(buyers("0212")).isEmpty();
    }

    @Test
    void freeTextContactsOfPartiesAndSalesAreFoundByAnyPrefixToo() {
        for (String[] p : new String[][] {
            {"Party Zero", "0772123456"}, {"Party Plus", "+256 772 123456"}, {"Party Bare", "772123456"}
        }) {
            assertThat(api.post("/cash-parties", Map.of("name", p[0], "contact", p[1], "kind", "other"), BOOKKEEPER)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        api.stockUp(t.headOffice(), ledBulb, "30");
        String zero = creditSale(t.headOffice(), null, "Sale Zero", "0772123456")
                .get("sale_no")
                .asString();
        String plus = creditSale(t.headOffice(), null, "Sale Plus", "+256 772 123456")
                .get("sale_no")
                .asString();
        String bare = creditSale(t.headOffice(), null, "Sale Bare", "772123456")
                .get("sale_no")
                .asString();
        for (String q : List.of("+2567", "2567", "+25677", "0772", "256772", "07721")) {
            assertThat(list("/cash-parties", "query", q, "name", BOOKKEEPER, "*"))
                    .as("party %s", q)
                    .containsExactlyInAnyOrder("Party Zero", "Party Plus", "Party Bare");
            assertThat(saleNumbers(q, RetailTestSupport.ADMIN, "*"))
                    .as("sale %s", q)
                    .containsExactlyInAnyOrder(zero, plus, bare);
        }
    }

    /** A four digit term that is not a phone keeps its text match and ranks above the contact that holds the digits. */
    @Test
    void aFourDigitTermKeepsItsTextMatchAndRank() {
        customer("Contact Holder", "0702023111");
        customer("Order 2023 Batch", null);
        assertThat(buyers("2023")).containsExactly("Order 2023 Batch", "Contact Holder");
    }

    // ---- sales ------------------------------------------------------------------------------

    JsonNode creditSale(UUID branch, UUID customerId, String buyerName, String buyerContact) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", "credit");
        if (customerId != null) {
            body.put("customer_id", customerId);
        }
        if (buyerName != null) {
            body.put("buyer_name", buyerName);
        }
        if (buyerContact != null) {
            body.put("buyer_contact", buyerContact);
        }
        body.put("lines", List.of(Map.of("product_id", ledBulb, "qty", "1")));
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales", body, RetailTestSupport.ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    List<String> saleNumbers(String q, String perms, String branches) {
        return list("/sales", "buyer", q, "sale_no", perms, branches);
    }

    @Test
    void salesMatchBuyerNameContactSaleNumberAndTheLinkedCreditBuyer() {
        api.stockUp(t.secondBranch(), ledBulb, "10");
        UUID linked = customer("Amina Kato", "0700000111");
        String walkIn = creditSale(t.headOffice(), null, "Walk-in Kato", "0700000999")
                .get("sale_no")
                .asString();
        String viaCustomer =
                creditSale(t.headOffice(), linked, null, null).get("sale_no").asString();
        String other = creditSale(t.secondBranch(), null, "Kato Secondbranch", "0700000888")
                .get("sale_no")
                .asString();

        String admin = RetailTestSupport.ADMIN;
        assertThat(saleNumbers("kato", admin, "*")).containsExactlyInAnyOrder(walkIn, viaCustomer, other);
        assertThat(saleNumbers("walk-in kato", admin, "*")).containsExactly(walkIn);
        assertThat(saleNumbers("+256 700 000 999", admin, "*")).containsExactly(walkIn);
        assertThat(saleNumbers("0700000111", admin, "*")).containsExactly(viaCustomer);
        assertThat(saleNumbers("amina", admin, "*")).containsExactly(viaCustomer);
        assertThat(saleNumbers(viaCustomer.toLowerCase(), admin, "*")).containsExactly(viaCustomer);
        assertThat(saleNumbers("%", admin, "*")).isEmpty();

        // a caller scoped to head office never finds the second branch's sale, by name or by phone
        String hq = t.headOffice().toString();
        assertThat(saleNumbers("kato", admin, hq)).containsExactlyInAnyOrder(walkIn, viaCustomer);
        assertThat(saleNumbers("secondbranch", admin, hq)).isEmpty();
        assertThat(saleNumbers("0700000888", admin, hq)).isEmpty();
    }

    // JUSTIFICATION-A3: a new test case for the sales cursor; no existing case pages the sales list with a search text.
    @Test
    void aSalesCursorIsTiedToTheSearchTextItWasIssuedFor() {
        api.stockUp(t.headOffice(), ledBulb, "30");
        for (int i = 0; i < 3; i++) {
            creditSale(t.headOffice(), null, "Cursor Buyer " + i, null);
        }
        String admin = RetailTestSupport.ADMIN;
        String cursor = page("/sales", "buyer", "cursor buyer", 1, null, admin, "*")
                .getBody()
                .get("next_cursor")
                .asString();
        assertThat(page("/sales", "buyer", "  CURSOR   buyer ", 1, cursor, admin, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> other = page("/sales", "buyer", "someone else", 1, cursor, admin, "*");
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(other.getBody().get("detail").asString()).contains("different search text");
        assertThat(page("/sales", "buyer", null, 1, cursor, admin, "*").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- cash parties -----------------------------------------------------------------------

    @Test
    void cashPartiesMatchNameAndPhoneAndRankPrefixFirst() {
        for (String[] p : new String[][] {
            {"Amina Kato", "0700000111", "staff"},
            {"Kato Amos", "+256 700 000 333", "supplier"},
            {"Mukato Brian", null, "other"},
            {"50% Partners", null, "other"},
            {"50x Partners", null, "other"},
        }) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", p[0]);
            if (p[1] != null) {
                body.put("contact", p[1]);
            }
            body.put("kind", p[2]);
            ResponseEntity<JsonNode> made = api.post("/cash-parties", body, BOOKKEEPER);
            assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(list("/cash-parties", "query", "kato", "name", BOOKKEEPER, "*"))
                .containsExactly("Kato Amos", "Amina Kato", "Mukato Brian");
        assertThat(list("/cash-parties", "query", "0700 000 333", "name", BOOKKEEPER, "*"))
                .containsExactly("Kato Amos");
        assertThat(list("/cash-parties", "query", "50%", "name", BOOKKEEPER, "*"))
                .containsExactly("50% Partners");
        assertThat(list("/cash-parties", "query", "_", "name", BOOKKEEPER, "*")).isEmpty();
        assertThat(list("/cash-parties", "query", "amina kato", "name", BOOKKEEPER, "*"))
                .containsExactly("Amina Kato");
    }
}
