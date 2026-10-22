package com.rincoltech.bms.core.notifications.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Each real publisher of a push notice, driven through its own route, queues a {@code push} outbox row for the right
 * people and for nobody else (ADR-050): the two-step sign-in relaxed (switched off, narrowed to roles, postponed), the
 * approval amount raised, a sale voided, a credit sale written off and a transfer dispatched; and a deactivated person's
 * devices are deleted. Nothing is sent here (the scheduler is off): the test reads the rows the publishers wrote.
 * Fabricated tenants, people and devices.
 */
@TestPropertySource(properties = {"bms.push.ephemeral-keys=true", "db-scheduler.enabled=false"})
class PushPublishersIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String MANAGER = ADMIN + ",retail.credit.manage,retail.credit.write_off,retail.sale.return";

    @Autowired
    TestRestTemplate http;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestDatabase.Fixture t;
    Api api;
    RetailTestSupport retail;
    Api.Session maker;
    UUID makerId;
    UUID alice;
    UUID dave;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("pushpub", false, true);
        api = Api.tenant(http, t.slug());
        retail = new RetailTestSupport(http, t);
        String email = Api.email("pub-maker");
        makerId = Api.staff(t, email, new Role("tenant_admin", null));
        maker = api.signIn(makerId, email, Api.PASSWORD, null);
        alice = Api.staff(t, Api.email("pub-alice"), new Role("tenant_admin", null));
        dave = Api.staff(t, Api.email("pub-dave"), new Role("tenant_admin", null));
        device(makerId);
        device(alice);
        // Dave holds the permission but has no device, so he is never a recipient unless a test gives him one.
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** A device row for the person, as the owner (the routes are covered by PushIT). The values only satisfy the checks. */
    void device(UUID user) {
        String endpoint = "https://fcm.googleapis.com/fcm/send/test-" + UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO push_subscriptions (id, tenant_id, user_id, endpoint, endpoint_hash, p256dh, auth, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, now())
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        user,
                        endpoint,
                        PushDevices.hash(endpoint),
                        "A".repeat(87),
                        "A".repeat(22))
                .update();
    }

    /**
     * The person the retail routes act as (the {@code X-Dev-User-Id} of {@link RetailTestSupport}) made a real tenant admin
     * with a device, so a test can prove the publisher leaves the actor out rather than finding nobody to leave out.
     */
    UUID retailActorWithADevice() {
        UUID actor = retail.userId();
        var owner = TestDatabase.owner();
        owner.sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?, 'active')")
                .params(actor, t.tenantId(), "Test Staff pub-actor", Api.email("pub-actor"))
                .update();
        owner.sql("""
                        INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)
                        VALUES (?, ?, ?, 'tenant_admin', NULL, ?)
                        """).params(UUID.randomUUID(), t.tenantId(), actor, actor).update();
        device(actor);
        return actor;
    }

    long devices(UUID user) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM push_subscriptions WHERE user_id = ?")
                .param(user)
                .query(Long.class)
                .single();
    }

    /** Who has a queued push row for this template in the tenant. */
    List<String> told(String template) {
        return TestDatabase.owner()
                .sql("""
                        SELECT recipient FROM notification_outbox
                         WHERE tenant_id = ? AND channel = 'push' AND idempotency_key LIKE ?
                        """)
                .params(t.tenantId(), "push:" + template + ":%")
                .query(String.class)
                .list();
    }

    void toldOnly(String template, UUID person) {
        assertThat(told(template)).containsExactly("user:" + person);
    }

    ResponseEntity<JsonNode> patch(Map<String, Object> body) {
        Map<String, Object> withProof = new LinkedHashMap<>(body);
        withProof.put("password", Api.PASSWORD);
        String etag =
                api.get("/api/v1/settings", maker.accessToken()).getHeaders().getETag();
        return api.call(HttpMethod.PATCH, "/api/v1/settings", withProof, maker.accessToken(), Map.of("If-Match", etag));
    }

    void patchOk(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = patch(body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    void publish(PushNotice notice) {
        TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(status -> {
                    events.publishEvent(notice);
                    return null;
                }));
    }

    // ---- Two-step sign-in relaxed ----------------------------------------------------------------

    @Test
    void switchingTwoStepOffTellsTheOtherAdministratorsWithADevice() {
        patchOk(Map.of("require_mfa_all_staff", true));
        assertThat(told("core.signin_policy_weakened")).isEmpty();
        patchOk(Map.of("require_mfa_all_staff", false));
        toldOnly("core.signin_policy_weakened", alice);
    }

    @Test
    void narrowingTwoStepToFewerRolesTellsThem() {
        patchOk(Map.of("require_mfa_all_staff", true));
        // From everyone to chosen roles is narrower, and so is dropping a role: two notices, one person each.
        patchOk(Map.of("require_mfa_all_staff", false, "require_mfa_roles", List.of("cashier", "auditor")));
        assertThat(told("core.signin_policy_weakened")).containsExactly("user:" + alice);
        patchOk(Map.of("require_mfa_roles", List.of("cashier")));
        assertThat(told("core.signin_policy_weakened")).containsExactly("user:" + alice, "user:" + alice);
    }

    @Test
    void postponingTheTwoStepDeadlineTellsThemButBringingItForwardDoesNot() {
        patchOk(Map.of("require_mfa_all_staff", true, "mfa_grace_days", 14));
        patchOk(Map.of("mfa_grace_days", 7));
        assertThat(told("core.signin_policy_weakened")).isEmpty();
        patchOk(Map.of("mfa_grace_days", 30));
        toldOnly("core.signin_policy_weakened", alice);
    }

    // ---- Approval amount raised ------------------------------------------------------------------

    @Test
    void raisingTheAmountThatNeedsASecondPersonTellsTheOtherAdministrators() {
        patchOk(Map.of("approval_thresholds_minor", Map.of("retail_sale_write_off", 1_000)));
        // From nothing to a first amount is a raise from zero: it is told, so this first call already queued a row.
        List<String> first = told("core.approval_policy_weakened");
        assertThat(first).isNotEmpty();
        patchOk(Map.of("approval_thresholds_minor", Map.of("retail_sale_write_off", 500)));
        assertThat(told("core.approval_policy_weakened")).hasSameSizeAs(first);
        patchOk(Map.of("approval_thresholds_minor", Map.of("retail_sale_write_off", 9_000)));
        assertThat(told("core.approval_policy_weakened")).hasSize(first.size() + 1);
        assertThat(told("core.approval_policy_weakened")).containsOnly("user:" + alice);
    }

    // ---- Retail: sale void, write-off, transfer -----------------------------------------------------

    UUID stockedProduct() {
        UUID product = retail.product("PUB-1", 600, 1_000);
        retail.importedBalance(t.headOffice(), product, "100");
        return product;
    }

    @Test
    void voidingASaleTellsTheAdministratorsWithADeviceThatHaveADevice() {
        UUID product = stockedProduct();
        ResponseEntity<JsonNode> sold = retail.sell(t.headOffice(), "cash", product, "1");
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID sale = RetailTestSupport.id(sold);
        ResponseEntity<JsonNode> voided =
                retail.post("/sales/" + sale + "/void", Map.of("reason", "Test wrong item"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(told("retail.sale_voided")).containsExactlyInAnyOrder("user:" + makerId, "user:" + alice);
    }

    /**
     * ADR-067: a sale recorded offline with a clash tells the administrators and the Supervisors of that branch with a device,
     * once per sale; a Supervisor of another branch is not told, and a sale without a clash tells nobody.
     */
    @Test
    void anOfflineSaleWithAClashTellsTheManagersOfItsBranch() {
        UUID here = Api.staff(t, Api.email("pub-sv-here"), new Role("retail_supervisor", t.headOffice()));
        UUID there = Api.staff(t, Api.email("pub-sv-there"), new Role("retail_supervisor", t.secondBranch()));
        device(here);
        device(there);
        UUID product = retail.product("PUB-OFF", 600, 1_000);
        retail.importedBalance(t.headOffice(), product, "1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1", "unit_price_minor", 1_000)));
        body.put("client_sale_id", UUID.randomUUID());
        body.put("occurred_at", java.time.Instant.now().minusSeconds(600).toString());
        body.put("recorded_offline", true);
        ResponseEntity<JsonNode> covered =
                retail.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(covered.getStatusCode()).as("%s", covered.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(told("retail.sale_clash")).isEmpty();

        body.put("client_sale_id", UUID.randomUUID());
        body.put("lines", List.of(Map.of("product_id", product, "qty", "4", "unit_price_minor", 1_000)));
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> short1 = retail.postKeyed("/sales", body, ADMIN, "*", key);
        assertThat(short1.getStatusCode()).as("%s", short1.getBody()).isEqualTo(HttpStatus.CREATED);
        retail.postKeyed("/sales", body, ADMIN, "*", key);
        assertThat(told("retail.sale_clash"))
                .containsExactlyInAnyOrder("user:" + makerId, "user:" + alice, "user:" + here);
    }

    @Test
    void aWriteOffDoneAloneTellsTheOtherAdministratorsWithADevice() {
        // Only one active administrator counts as "the others": the second-person rule needs two or more.
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated', updated_at = now() - interval '90 days'"
                        + " WHERE id IN (?, ?)")
                .params(makerId, dave)
                .update();
        UUID product = stockedProduct();
        ResponseEntity<JsonNode> put = retail.get("/settings/capabilities", SETTINGS);
        String etag = put.getHeaders().getETag();
        assertThat(retail.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("returns", true),
                                SETTINGS,
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> sold = retail.sell(t.headOffice(), "credit", product, "3");
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> written = retail.postKeyed(
                "/sales/" + RetailTestSupport.id(sold) + "/write-off",
                Map.of("reason", "Buyer left town"),
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(written.getBody().get("executed").asBoolean()).isTrue();
        toldOnly("retail.write_off_alone", alice);
    }

    @Test
    void dispatchingATransferTellsTheHoldersAtTheDestinationNotTheDispatcher() {
        UUID product = stockedProduct();
        ResponseEntity<JsonNode> on = retail.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("transfer_receipt", true),
                SETTINGS,
                "*",
                etagOfCapabilities());
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        UUID scoped = Api.staff(t, Api.email("pub-scoped"), new Role("retail_storekeeper", t.secondBranch()));
        UUID elsewhere = Api.staff(t, Api.email("pub-elsewhere"), new Role("retail_storekeeper", t.headOffice()));
        device(scoped);
        device(elsewhere);
        device(dave);
        // The dispatcher is a tenant admin with a device too: only the publisher's exclusion keeps them off the list.
        UUID dispatcher = retailActorWithADevice();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_branch_id", t.headOffice());
        body.put("to_branch_id", t.secondBranch());
        body.put("lines", List.of(Map.of("product_id", product, "qty", "4")));
        ResponseEntity<JsonNode> sent = retail.postKeyed(
                "/transfers", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        List<String> told = told("retail.transfer_dispatched");
        assertThat(told).contains("user:" + scoped, "user:" + alice, "user:" + makerId, "user:" + dave);
        assertThat(told).doesNotContain("user:" + dispatcher, "user:" + elsewhere);
    }

    Map<String, String> etagOfCapabilities() {
        String etag =
                retail.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        return etag == null ? Map.of() : Map.of("If-Match", etag);
    }

    /** TransferService.receiveTransfer allows receiving at a closed branch, so its staff must still hear of the dispatch. */
    @Test
    void staffOfAClosedDestinationStillHearOfATransferOnItsWayButNotOfOtherNotices() {
        UUID scoped = Api.staff(t, Api.email("pub-closed"), new Role("retail_storekeeper", t.secondBranch()));
        device(scoped);
        TestDatabase.owner()
                .sql("UPDATE branches SET status = 'inactive', closed_at = now() WHERE id = ?")
                .param(t.secondBranch())
                .update();
        String ref = UUID.randomUUID().toString();
        publish(new PushNotice(
                "retail.transfer_dispatched",
                ref,
                new PushNotice.Holders("retail.stock.transfer", t.secondBranch(), makerId),
                Map.of(
                        "from_branch_id", t.headOffice().toString(),
                        "to_branch_id", t.secondBranch().toString(),
                        "path", "/staff/retail/in-transit")));
        assertThat(told("retail.transfer_dispatched")).containsExactlyInAnyOrder("user:" + scoped, "user:" + alice);
        // Any other notice still needs an active branch.
        publish(new PushNotice(
                "core.approval_waiting",
                ref,
                new PushNotice.Holders("retail.stock.transfer", t.secondBranch(), makerId),
                Map.of("action_label", "Test action", "path", "/staff/approvals")));
        assertThat(told("core.approval_waiting")).contains("user:" + alice).doesNotContain("user:" + scoped);
    }

    // ---- Deactivation ------------------------------------------------------------------------------

    @Test
    void deactivatingAPersonDeletesTheirDevices() {
        UUID leaver = Api.staff(t, Api.email("pub-leaver"), new Role("cashier", t.headOffice()));
        device(leaver);
        device(leaver);
        assertThat(devices(leaver)).isEqualTo(2);
        ResponseEntity<JsonNode> r = api.post("/api/v1/users/" + leaver + "/deactivate", null, maker.accessToken());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(devices(leaver)).isZero();
        assertThat(devices(alice)).isEqualTo(1);
    }
}
