package com.rincoltech.bms.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
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
 * Search at the size of a busy tenant (ADR-039 decision 7): 25,000 sales, 12,000 members, 6,000 products and
 * 800 credit buyers in one tenant, read through the API as {@code bms_app} under row-level security. Under
 * that role no text operator is leakproof, so every search is a filter over the tenant's rows; this keeps the
 * cost per row honest. The bounds are generous for a shared CI machine (a developer laptop is 5 to 20 times
 * under them) and exist to catch a query that walks the rows more than once per row, as a correlated
 * subquery or a repeated fold would. Names and numbers are fabricated.
 */
class SearchScaleIT extends IntegrationTest {

    static final long BOUND_MILLIS = 400;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void seed() {
        t = TestDatabase.tenant("search-scale", true, true);
        api = new RetailTestSupport(http, t);
        UUID category = api.category("Scale Category");
        UUID unit = api.unit("scale-unit");
        var owner = TestDatabase.owner();
        owner.sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                                     marital_status, currency, kyc_status, status, source)
                        SELECT gen_random_uuid(), ?, ?, 'M' || lpad(n::text, 6, '0'),
                               (ARRAY['Amina','Brian','Cissy','David','Esther','Fred','Grace','Hassan','Irene','Joseph'])[1 + n % 10]
                                 || ' ' || (ARRAY['Kato','Mukasa','Nabirye','Okello','Ssemakula','Tumusiime','Waiswa','Zawedde'])[1 + (n / 10) % 8]
                                 || ' ' || n,
                               '+25670' || lpad(n::text, 7, '0'), 'none', 'unknown', 'UGX', 'verified', 'active', 'staff'
                          FROM generate_series(1, 12000) n
                        """).params(t.tenantId(), t.headOffice()).update();
        owner.sql("""
                        INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                     sell_minor, currency)
                        SELECT gen_random_uuid(), ?, 'SC-' || n, 'Scale item number ' || n || ' with a longer description', ?, ?,
                               100, 200, 'UGX'
                          FROM generate_series(1, 6000) n
                        """).params(t.tenantId(), category, unit).update();
        owner.sql("""
                        INSERT INTO retail_customers (id, tenant_id, name, contact)
                        SELECT gen_random_uuid(), ?, 'Buyer ' || n || ' ' || (ARRAY['Kato','Mukasa','Nabirye'])[1 + n % 3],
                               '07710' || lpad(n::text, 5, '0')
                          FROM generate_series(1, 800) n
                        """).params(t.tenantId()).update();
        owner.sql("""
                        WITH c AS (SELECT array_agg(id ORDER BY name) ids FROM retail_customers WHERE tenant_id = ?)
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                                                  buyer_name, buyer_contact, currency, total_minor, cost_total_minor, paid_minor,
                                                  status, created_by)
                        SELECT gen_random_uuid(), ?, ?, 'S' || lpad(n::text, 7, '0'), current_date - (n % 300),
                               CASE WHEN n % 3 = 0 THEN 'credit' ELSE 'cash' END,
                               CASE WHEN n % 6 = 0 THEN c.ids[1 + n % 800] END,
                               CASE WHEN n % 6 <> 0 AND n % 3 = 0 THEN 'Walk-in ' || n WHEN n % 5 = 0 THEN 'Cash buyer ' || n END,
                               CASE WHEN n % 3 = 0 THEN '07720' || lpad(n::text, 5, '0') END,
                               'UGX', 1000, 600, CASE WHEN n % 3 = 0 THEN 0 ELSE 1000 END, 'completed', ?
                          FROM generate_series(1, 25000) n, c
                        """)
                .params(t.tenantId(), t.tenantId(), t.headOffice(), api.userId())
                .update();
        owner.sql("ANALYZE").update();
    }

    URI uri(String path, String param, String q) {
        return UriComponentsBuilder.fromPath(path)
                .queryParam(param, "{q}")
                .queryParam("limit", 50)
                .encode()
                .buildAndExpand(Map.of("q", q))
                .toUri();
    }

    /** The median of five calls after two warm-up calls, in milliseconds, with the answer checked. */
    long median(String path, String param, String q, String perms, int expectedFirstPage) {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            long started = System.nanoTime();
            ResponseEntity<JsonNode> r = http.exchange(
                    uri(path, param, q),
                    HttpMethod.GET,
                    new HttpEntity<>(api.headers(perms, "*", Map.of())),
                    JsonNode.class);
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(r.getStatusCode())
                    .as("%s?%s=%s %s", path, param, q, r.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(r.getBody().get("items").size())
                    .as("%s?%s=%s", path, param, q)
                    .isEqualTo(expectedFirstPage);
            if (i >= 2) {
                times.add(millis);
            }
        }
        Collections.sort(times);
        long median = times.get(times.size() / 2);
        System.out.printf("SEARCH-SCALE %-34s %-18s %4d ms%n", path, q, median);
        return median;
    }

    @Test
    void searchesAtTheSizeOfABusyTenantStayQuick() {
        String lending = "lending.members.read";
        String admin = RetailTestSupport.ADMIN;
        List<Long> all = new ArrayList<>();
        // members, 12,000: a name with many matches, a name with none (walks every row), a phone, a number
        all.add(median("/api/v1/lending/members", "q", "kato", lending, 50));
        all.add(median("/api/v1/lending/members", "q", "zzzz qqqq", lending, 0));
        all.add(median("/api/v1/lending/members", "q", "0700001234", lending, 1));
        all.add(median("/api/v1/lending/members", "q", "+256 70 0001", lending, 50));
        // sales, 25,000: no match (walks every sale, linked credit buyers through a subquery), a buyer, a sale number
        all.add(median("/api/v1/retail/sales", "buyer", "zzzz qqqq", admin, 0));
        all.add(median("/api/v1/retail/sales", "buyer", "walk-in", admin, 50));
        all.add(median("/api/v1/retail/sales", "buyer", "kato", admin, 50));
        all.add(median("/api/v1/retail/sales", "buyer", "S0012345", admin, 1));
        all.add(median("/api/v1/retail/sales", "buyer", "0772000123", admin, 1));
        // products, 6,000, and the credit buyers
        all.add(median("/api/v1/retail/products", "query", "zzzz qqqq", admin, 0));
        all.add(median("/api/v1/retail/products", "query", "item number 5999", admin, 1));
        all.add(median("/api/v1/retail/customers", "query", "zzzz", admin, 0));
        all.add(median("/api/v1/retail/customers", "query", "mukasa", admin, 50));
        assertThat(all).allSatisfy(ms -> assertThat(ms).isLessThan(BOUND_MILLIS));
    }
}
