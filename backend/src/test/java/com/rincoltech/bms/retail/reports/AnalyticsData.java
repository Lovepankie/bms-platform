package com.rincoltech.bms.retail.reports;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The fabricated retail tenant the analytics tests share (issue #149). Products: ANA-1 costs 1,000
 * and sells at 1,500; ANA-2 costs 200 and sells at 300; ANA-3 (stocked, never sold) and ANA-4 (never
 * stocked, never sold). Sales are dated by moving the sale date as the owner, as the importer does.
 */
final class AnalyticsData {

    static final ZoneId ZONE = ZoneId.of("Africa/Kampala");

    final TestDatabase.Fixture t;
    final RetailTestSupport api;
    UUID p1;
    UUID p2;
    UUID p3;
    UUID p4;

    AnalyticsData(TestDatabase.Fixture t, RetailTestSupport api) {
        this.t = t;
        this.api = api;
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    void products() {
        p1 = api.product("ANA-1", 1_000, 1_500);
        p2 = api.product("ANA-2", 200, 300);
        p3 = api.product("ANA-3", 400, 700);
        p4 = api.product("ANA-4", 50, 100);
    }

    /** Head office holds 20 of ANA-1, 20 of ANA-2 and 4 of ANA-3; branch two holds 10 of ANA-1. */
    void stock() {
        api.stockUp(t.headOffice(), p1, "20");
        api.stockUp(t.headOffice(), p2, "20");
        api.stockUp(t.headOffice(), p3, "4");
        api.stockUp(t.secondBranch(), p1, "10");
    }

    UUID sell(UUID branch, String method, UUID product, String qty, int daysAgo) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("sale_date", today().minusDays(daysAgo).toString());
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
            body.put("due_date", today().plusDays(30 - daysAgo).toString());
        }
        body.put("lines", java.util.List.of(java.util.Map.of("product_id", product, "qty", qty)));
        var r = api.postKeyed(
                "/sales", body, RetailTestSupport.ADMIN, "*", UUID.randomUUID().toString());
        if (!r.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("sale: " + r.getBody());
        }
        return RetailTestSupport.id(r);
    }

    /**
     * Head office: 2 x ANA-1 cash yesterday (3,000, cost 2,000); 3 x ANA-2 on credit 10 days ago (900,
     * cost 600); 1 x ANA-1 today at head office, voided. Branch two: 1 x ANA-1 cash today (1,500, cost
     * 1,000). All sold by one user. Totals: sales 5,400, cost 3,600, profit 1,800 (3,333 basis points).
     */
    void sales() {
        sell(t.headOffice(), "cash", p1, "2", 1);
        sell(t.headOffice(), "credit", p2, "3", 10);
        UUID voided = sell(t.headOffice(), "cash", p1, "1", 0);
        api.post("/sales/" + voided + "/void", java.util.Map.of("reason", "Test void"), RetailTestSupport.ADMIN);
        sell(t.secondBranch(), "cash", p1, "1", 0);
    }

    static boolean hasKey(JsonNode node, String fragment) {
        if (node.isObject()) {
            for (var e : node.properties()) {
                if (e.getKey().contains(fragment) || hasKey(e.getValue(), fragment)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode n : node) {
                if (hasKey(n, fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    static long auditRows() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log")
                .query(Long.class)
                .single();
    }
}
