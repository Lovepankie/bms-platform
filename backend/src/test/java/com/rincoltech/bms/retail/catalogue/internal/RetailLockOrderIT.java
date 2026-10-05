package com.rincoltech.bms.retail.catalogue.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Review F2: a restock locks its products while usage, voids, stock-takes and sales insert stock
 * movements whose foreign key check takes {@code FOR KEY SHARE} on the product. The product lock
 * must not conflict with that check, or the two sides deadlock. Amounts fabricated.
 */
class RetailLockOrderIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID p1;
    UUID p2;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-lock", false, true);
        api = new RetailTestSupport(http, t);
        p1 = api.product("LCK-01", 100, 150);
        p2 = api.product("LCK-02", 100, 150);
    }

    /**
     * Deterministic: while one transaction holds the catalogue's product lock, another inserts a
     * stock movement for that product. With {@code FOR UPDATE} the insert waits (the first half of
     * the reproduced deadlock); with {@code FOR NO KEY UPDATE} it goes through at once.
     */
    @Test
    void theProductLockDoesNotBlockAMovementInsert() throws Exception {
        try (Connection holder = app();
                Connection writer = app()) {
            try (PreparedStatement lock = holder.prepareStatement(CatalogueRepository.LOCK)) {
                lock.setObject(1, p1);
                assertThat(lock.executeQuery().next()).isTrue();
            }
            try (Statement s = writer.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '2s'");
            }
            try (PreparedStatement insert = writer.prepareStatement("""
                    INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, branch_id, product_id, kind, qty,
                        unit_cost_minor, source_type)
                    VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, now(), ?, ?, 'adjustment', 1, 0,
                        'test.lock')
                    """)) {
                insert.setObject(1, t.headOffice());
                insert.setObject(2, p1);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            } catch (SQLException e) {
                throw new AssertionError("the movement insert waited on the product lock: " + e.getSQLState(), e);
            } finally {
                writer.rollback();
                holder.rollback();
            }
        }
    }

    /**
     * Mixed races on one product, each in several rounds: a restock against a usage report, against
     * a void, against a stock-take commit, and a two-line sale naming the products in reverse order
     * against a two-line restock. No call fails with a 500 and every balance equals its movements.
     */
    @Test
    void restocksRaceUsageVoidsStocktakesAndReversedSalesWithoutA500() throws Exception {
        api.stockUp(t.headOffice(), p1, "1000");
        api.stockUp(t.headOffice(), p2, "1000");
        List<HttpStatusCode> statuses = new ArrayList<>();
        for (int round = 0; round < 4; round++) {
            UUID sale = RetailTestSupport.id(api.sell(t.headOffice(), "cash", p1, "1"));
            UUID count = RetailTestSupport.id(api.post(
                    "/stocktakes",
                    Map.of(
                            "branch_id",
                            t.headOffice(),
                            "lines",
                            List.of(Map.of("product_id", p1, "counted_qty", "990"))),
                    ADMIN));
            statuses.addAll(pair(
                    this::restockBoth,
                    () -> api.postKeyed(
                            "/usage",
                            Map.of(
                                    "branch_id",
                                    t.headOffice(),
                                    "kind",
                                    "used",
                                    "reason",
                                    "Test use",
                                    "lines",
                                    List.of(Map.of("product_id", p1, "qty", "1"))),
                            ADMIN,
                            "*",
                            UUID.randomUUID().toString())));
            statuses.addAll(pair(
                    this::restockBoth, () -> api.post("/sales/" + sale + "/void", Map.of("reason", "Test"), ADMIN)));
            statuses.addAll(
                    pair(this::restockBoth, () -> api.post("/stocktakes/" + count + "/commit", Map.of(), ADMIN)));
            statuses.addAll(pair(this::restockBoth, this::sellReversed));
        }
        assertThat(statuses).allMatch(s -> s.is2xxSuccessful(), "every racing call succeeds");
        for (UUID p : List.of(p1, p2)) {
            BigDecimal balance = TestDatabase.owner()
                    .sql("SELECT qty FROM retail_stock_balances WHERE tenant_id = ? AND product_id = ?")
                    .params(t.tenantId(), p)
                    .query(BigDecimal.class)
                    .single();
            BigDecimal moved = TestDatabase.owner()
                    .sql("SELECT sum(qty) FROM retail_stock_movements WHERE tenant_id = ? AND product_id = ?")
                    .params(t.tenantId(), p)
                    .query(BigDecimal.class)
                    .single();
            assertThat(balance).isEqualByComparingTo(moved);
        }
    }

    private final AtomicInteger cost = new AtomicInteger(100);

    ResponseEntity<JsonNode> restockBoth() {
        long c = cost.incrementAndGet();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", "cash");
        body.put(
                "lines",
                List.of(
                        Map.of(
                                "product_id",
                                p1,
                                "cost_minor",
                                c,
                                "qty_by_branch",
                                List.of(Map.of("branch_id", t.headOffice(), "qty", "1"))),
                        Map.of(
                                "product_id",
                                p2,
                                "cost_minor",
                                c,
                                "qty_by_branch",
                                List.of(Map.of("branch_id", t.headOffice(), "qty", "1")))));
        return api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> sellReversed() {
        UUID[] order = p1.compareTo(p2) < 0 ? new UUID[] {p2, p1} : new UUID[] {p1, p2};
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put(
                "lines",
                List.of(
                        Map.of("product_id", order[0], "qty", "1", "unit_price_minor", 10_000),
                        Map.of("product_id", order[1], "qty", "1", "unit_price_minor", 10_000)));
        return api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    static List<HttpStatusCode> pair(
            java.util.function.Supplier<ResponseEntity<JsonNode>> a,
            java.util.function.Supplier<ResponseEntity<JsonNode>> b)
            throws Exception {
        AtomicInteger turn = new AtomicInteger();
        return Api.race(2, () -> turn.getAndIncrement() == 0 ? a.get() : b.get());
    }

    private Connection app() throws SQLException {
        Connection c = TestDatabase.appDataSource().getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement s = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            s.setString(1, t.tenantId().toString());
            s.execute();
        }
        return c;
    }
}
