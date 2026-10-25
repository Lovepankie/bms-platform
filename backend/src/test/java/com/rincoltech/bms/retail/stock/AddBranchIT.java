package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Adding a branch from the app (issue #581, ADR-089, FR-BR-01, FR-TEN-04): a shop, a store or a branch that keeps no goods, made
 * by a real tenant administrator with no code typed, so the server makes the code from the name. The shop and store rules of
 * ADR-048 apply from the moment the branch exists, and a store becomes a shop and a store again with the rules following each
 * switch. Retail events are made with the development headers. Names are fabricated.
 */
class AddBranchIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport retail;
    Api api;
    Session admin;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("add-branch", false, true);
        retail = new RetailTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        String email = Api.email("admin");
        admin = api.signIn(Api.staff(t, email, new Role("tenant_admin", null)), email, Api.PASSWORD, null);
        product = retail.product("ADD-BRANCH-SOAP", 1_000, 1_500);
    }

    ResponseEntity<JsonNode> add(String name, Boolean sells, Boolean stocks) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (sells != null) body.put("sells_goods", sells);
        if (stocks != null) body.put("stocks_goods", stocks);
        return api.post("/api/v1/branches", body, admin.accessToken());
    }

    UUID added(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(r.getBody().get("id").asString());
    }

    ResponseEntity<JsonNode> sell(UUID branch) {
        retail.stockUp(branch, product, "5");
        return retail.sell(branch, "cash", product, "1");
    }

    ResponseEntity<JsonNode> setSelling(UUID branch, boolean sells) {
        String etag = api.get("/api/v1/branches/" + branch, admin.accessToken())
                .getHeaders()
                .getETag();
        return api.call(
                HttpMethod.PATCH,
                "/api/v1/branches/" + branch,
                Map.of("sells_goods", sells),
                admin.accessToken(),
                Map.of("If-Match", etag));
    }

    /**
     * Sends the calls while the test itself holds the business's branch lock (ADR-089), waits until every call is blocked on
     * that lock in pg_locks, then lets go and returns the answers in order. So every call has read nothing before the others
     * start: the race is certain, not left to thread timing. Without the lock in BranchService no call ever waits, and the
     * wait below fails.
     */
    List<ResponseEntity<JsonNode>> racing(List<Callable<ResponseEntity<JsonNode>>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try (Connection held = TestDatabase.ownerDataSource().getConnection()) {
            try (PreparedStatement lock =
                    held.prepareStatement("SELECT pg_advisory_lock(hashtextextended(? || ':core.branch.open', 0))")) {
                lock.setString(1, t.tenantId().toString());
                lock.execute();
            }
            List<Future<ResponseEntity<JsonNode>>> sent =
                    calls.stream().map(pool::submit).toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            long waiting = 0;
            while (waiting < calls.size() && System.nanoTime() < deadline) {
                waiting = TestDatabase.owner().sql("""
                                SELECT count(*) FROM pg_locks
                                WHERE locktype = 'advisory' AND NOT granted
                                  AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                                """).query(Long.class).single();
                if (waiting < calls.size()) Thread.sleep(50);
            }
            assertThat(waiting)
                    .as("every call waits on the branch lock before it counts")
                    .isEqualTo(calls.size());
            // And they stay held: none of them has answered while the lock is ours.
            Thread.sleep(1_500);
            assertThat(sent).as("no call answers while the branch lock is held").noneMatch(Future::isDone);
            try (PreparedStatement unlock =
                    held.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(? || ':core.branch.open', 0))")) {
                unlock.setString(1, t.tenantId().toString());
                unlock.execute();
            }
            List<ResponseEntity<JsonNode>> answers = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : sent) answers.add(f.get(30, TimeUnit.SECONDS));
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    Callable<ResponseEntity<JsonNode>> adding(String name) {
        return () -> add(name, null, null);
    }

    Callable<ResponseEntity<JsonNode>> reopening(UUID branch) {
        return () -> api.call(
                HttpMethod.POST,
                "/api/v1/branches/" + branch + "/reopen",
                null,
                admin.accessToken(),
                Map.of("If-Match", "\"1\""));
    }

    long branchCount() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM branches WHERE tenant_id = ?")
                .params(t.tenantId())
                .query(Long.class)
                .single();
    }

    void planOf(int maxBranches) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO plans (id, code, name, max_branches, max_staff_users, max_active_members, allowed_modules)
                        VALUES (?, ?, 'Test Small Plan', ?, 10, 0, '{retail}')
                        """)
                .params(UUID.randomUUID(), "small-" + t.slug(), maxBranches)
                .update();
        TestDatabase.owner()
                .sql("UPDATE tenants SET plan_id = (SELECT id FROM plans WHERE code = ?) WHERE id = ?")
                .params("small-" + t.slug(), t.tenantId())
                .update();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    /** A shop, a store and a branch that keeps no goods, each with its flags, in /me at once, and its rules from creation. */
    @Test
    void eachKindIsAddedAndItsRulesApplyAtOnce() {
        ResponseEntity<JsonNode> shop = add("Test Sample Shop", null, null);
        UUID shopId = added(shop);
        assertThat(shop.getBody().get("sells_goods").asBoolean()).isTrue();
        assertThat(shop.getBody().get("stocks_goods").asBoolean()).isTrue();

        ResponseEntity<JsonNode> store = add("Test Sample Store", false, null);
        UUID storeId = added(store);
        assertThat(store.getBody().get("sells_goods").asBoolean()).isFalse();
        assertThat(store.getBody().get("stocks_goods").asBoolean()).isTrue();

        ResponseEntity<JsonNode> office = add("Test Back Office", true, false);
        UUID officeId = added(office);
        assertThat(office.getBody().get("sells_goods").asBoolean()).isTrue();
        assertThat(office.getBody().get("stocks_goods").asBoolean()).isFalse();

        JsonNode me = api.get("/api/v1/me", admin.accessToken()).getBody();
        assertThat(StreamSupport.stream(me.get("branches").spliterator(), false)
                        .map(b -> b.get("id").asString())
                        .toList())
                .contains(shopId.toString(), storeId.toString(), officeId.toString());

        assertThat(sell(shopId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> atStore = sell(storeId);
        assertThat(atStore.getStatusCode()).as("%s", atStore.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(atStore)).isEqualTo("branch_does_not_sell");
        ResponseEntity<JsonNode> atOffice = retail.sell(officeId, "cash", product, "1");
        assertThat(atOffice.getStatusCode()).as("%s", atOffice.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(atOffice)).isEqualTo("branch_not_stocking");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ? AND branch_id IN (?, ?)")
                        .params(t.tenantId(), storeId, officeId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** No code typed: the name's letters in capitals, at most six, then a number when that is taken. */
    @Test
    void theCodeIsMadeFromTheName() {
        assertThat(code(add("Kasubi Store", false, null))).isEqualTo("KASUBI");
        assertThat(code(add("Kasubi Shop", null, null))).isEqualTo("KASUBI2");
        assertThat(code(add("Kasubi Shop", null, null))).isEqualTo("KASUBI3");
        assertThat(code(add("No. 7 Road", null, null))).isEqualTo("NO7ROA");
        assertThat(code(add("A", null, null))).isEqualTo("BR");
        // A typed code is kept, and a taken one is still refused.
        assertThat(code(api.post("/api/v1/branches", Map.of("code", "KSB", "name", "Typed"), admin.accessToken())))
                .isEqualTo("KSB");
        ResponseEntity<JsonNode> taken =
                api.post("/api/v1/branches", Map.of("code", "KSB", "name", "Again"), admin.accessToken());
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(taken)).isEqualTo("duplicate_branch_code");
    }

    /** The owner's ask: a store becomes a shop and a store again, and the sale rule follows each switch. */
    @Test
    void aStoreBecomesAShopAndBackWithTheRulesFollowing() {
        UUID branch = added(add("Test Switch Store", false, null));
        assertThat(code(sell(branch))).isEqualTo("branch_does_not_sell");

        assertThat(setSelling(branch, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sell(branch).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> back = setSelling(branch, false);
        assertThat(back.getStatusCode()).as("%s", back.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(code(retail.sell(branch, "cash", product, "1"))).isEqualTo("branch_does_not_sell");

        assertThat(setSelling(branch, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retail.sell(branch, "cash", product, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** A store that keeps no goods cannot be made, and nothing is written. */
    @Test
    void aStoreThatKeepsNoGoodsIsRefused() {
        // Checked before the plan limit, so a full plan still names what is wrong with the request.
        planOf(2);
        ResponseEntity<JsonNode> r = add("Test Empty Store", false, false);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("store_must_stock");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM branches WHERE tenant_id = ? AND name = 'Test Empty Store'")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** FR-TEN-04: past the plan's branch limit the add is refused with plan_limit_reached, and nothing is written. */
    @Test
    void thePlanLimitRefusesAnotherBranch() {
        planOf(2);
        ResponseEntity<JsonNode> r = add("Test One Too Many", null, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("plan_limit_reached");
        assertThat(branchCount()).isEqualTo(2);
    }

    /** Two adds at the same moment with room for one: one is added, the other is refused, never both (ADR-089). */
    @Test
    void twoAddsAtOnceNeverPassThePlanLimit() throws Exception {
        planOf(3);
        List<ResponseEntity<JsonNode>> answers = racing(List.of(adding("Test Race One"), adding("Test Race Two")));
        assertThat(answers.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED))
                .as("%s", answers)
                .hasSize(1);
        assertThat(answers.stream()
                        .filter(r -> r.getStatusCode() != HttpStatus.CREATED)
                        .map(AddBranchIT::code))
                .containsExactly("plan_limit_reached");
        assertThat(branchCount()).isEqualTo(3);
    }

    /** Two reopens at the same moment with room for one: one branch opens, the other is refused (ADR-089). */
    @Test
    void twoReopensAtOnceNeverPassThePlanLimit() throws Exception {
        planOf(3);
        List<UUID> closed = List.of(UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < closed.size(); i++) {
            TestDatabase.owner()
                    .sql("""
                            INSERT INTO branches (id, tenant_id, code, name, status, closed_at)
                            VALUES (?, ?, ?, ?, 'inactive', now())
                            """)
                    .params(closed.get(i), t.tenantId(), "SHUT" + i, "Test Shut Shop " + i)
                    .update();
        }
        List<ResponseEntity<JsonNode>> answers =
                racing(closed.stream().map(this::reopening).toList());
        assertThat(answers.stream().filter(r -> r.getStatusCode() == HttpStatus.OK))
                .as("%s", answers)
                .hasSize(1);
        assertThat(answers.stream()
                        .filter(r -> r.getStatusCode() != HttpStatus.OK)
                        .map(AddBranchIT::code))
                .containsExactly("plan_limit_reached");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM branches WHERE tenant_id = ? AND status = 'active'")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    /**
     * The same closed branch reopened twice with the same If-Match: once at a time the second gets a plain 409, and at the same
     * moment the second waits on the branch row, then finds it open; either way it opens once, with one audit row.
     */
    @Test
    void reopeningOneBranchTwiceOpensItOnce() throws Exception {
        UUID shut = UUID.randomUUID();
        TestDatabase.owner().sql("""
                        INSERT INTO branches (id, tenant_id, code, name, status, closed_at)
                        VALUES (?, ?, 'SHUTX', 'Test Shut Shop X', 'inactive', now())
                        """).params(shut, t.tenantId()).update();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<ResponseEntity<JsonNode>> answers = new ArrayList<>();
        try (Connection held = TestDatabase.ownerDataSource().getConnection()) {
            try (PreparedStatement lock =
                    held.prepareStatement("SELECT pg_advisory_lock(hashtextextended(? || ':core.branch.open', 0))")) {
                lock.setString(1, t.tenantId().toString());
                lock.execute();
            }
            List<Future<ResponseEntity<JsonNode>>> sent =
                    List.of(pool.submit(reopening(shut)), pool.submit(reopening(shut)));
            // One holds the branch row and waits on the business lock; the other waits on the row.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            long waiting = 0;
            while (waiting < 2 && System.nanoTime() < deadline) {
                waiting = TestDatabase.owner().sql("""
                                SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted
                                """).query(Long.class).single();
                if (waiting < 2) Thread.sleep(50);
            }
            assertThat(waiting).as("both reopens are held").isEqualTo(2);
            try (PreparedStatement unlock =
                    held.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(? || ':core.branch.open', 0))")) {
                unlock.setString(1, t.tenantId().toString());
                unlock.execute();
            }
            for (Future<ResponseEntity<JsonNode>> f : sent) answers.add(f.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(answers.stream().filter(r -> r.getStatusCode() == HttpStatus.OK))
                .as("%s", answers)
                .hasSize(1);
        ResponseEntity<JsonNode> second = answers.stream()
                .filter(r -> r.getStatusCode() != HttpStatus.OK)
                .findFirst()
                .orElseThrow();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(second)).isIn("version_conflict", "invalid_status_transition");
        // Once at a time: the same If-Match again is a plain 409 too.
        ResponseEntity<JsonNode> again = reopening(shut).call();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.branch.reopened' AND entity_id = ?")
                        .params(t.tenantId(), shut)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT version FROM branches WHERE id = ?")
                        .params(shut)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    /** Two adds of the same name at the same moment both succeed, with different codes made from the name. */
    @Test
    void twoAddsOfOneNameAtOnceGetTwoCodes() throws Exception {
        List<ResponseEntity<JsonNode>> answers =
                racing(List.of(adding("Kasubi Store"), adding("Kasubi Store"), adding("Kasubi Store")));
        assertThat(answers)
                .allSatisfy(
                        r -> assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED));
        assertThat(answers.stream().map(AddBranchIT::code)).containsExactlyInAnyOrder("KASUBI", "KASUBI2", "KASUBI3");
    }

    /** Only core.branches.manage adds a branch. */
    @Test
    void onlyABranchManagerAddsABranch() {
        String email = Api.email("auditor");
        Session auditor = api.signIn(Api.staff(t, email, new Role("auditor", null)), email, Api.PASSWORD, null);
        ResponseEntity<JsonNode> r =
                api.post("/api/v1/branches", Map.of("name", "Test Not Allowed"), auditor.accessToken());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(branchCount()).isEqualTo(2);
    }
}
