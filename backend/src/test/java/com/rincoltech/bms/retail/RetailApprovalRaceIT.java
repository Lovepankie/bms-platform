package com.rincoltech.bms.retail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The race of ADR-091 (review of #588) for every retail action kind the approval rules list: an administrator switches the action
 * off between the module's own read of the rule and its request. The module passes the rule it read to {@code Approvals.request},
 * so the rule is read once and the module's decision stands: 202 with a real pending approval, nothing made, no
 * {@code core.approval.executed_switched_off} row, and the idempotent replay gives the same answer. The module's read is made to
 * say "on" while the stored switch is off. The lending kinds are never switchable, so no switch can race them. A caller that read
 * no rule has a switched-off action run at once: for a write-off that is still flagged and told (ADR-043 decision 10), proven
 * below. Fabricated data.
 */
class RetailApprovalRaceIT extends IntegrationTest {

    static final String RETURNER = RetailTestSupport.ADMIN + ",retail.sale.return,retail.cashbook.read";
    static final String WRITER = RetailTestSupport.ADMIN + ",retail.credit.manage,retail.credit.write_off";
    static final String BUYER = RetailTestSupport.ADMIN + ",retail.purchase.approve";

    @Autowired
    TestRestTemplate http;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    Approvals approvals;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    CashbookTestSupport cb;
    UUID hq;
    /** Cost 1,000, price 1,500. */
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-race", false, true);
        api = new RetailTestSupport(http, t);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("RC-1", 1_000, 1_500);
        api.stockUp(hq, product, "20");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Stored off; the module's own read says on from this amount, as it would have just before the switch committed. */
    void switchedOffAfterTheModuleRead(String action, long thresholdMinor) {
        storedOff(action);
        doReturn(new Approvals.Rule(true, thresholdMinor)).when(approvals).rule(action);
    }

    void storedOff(String action) {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        TestDatabase.owner().sql("""
                        UPDATE tenant_settings SET settings = settings
                               || jsonb_build_object('approval_switches', jsonb_build_object(?::text, false))
                         WHERE tenant_id = ?
                        """).params(action, t.tenantId()).update();
    }

    void capability(String key) {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, true),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    UUID sale(String method) {
        ResponseEntity<JsonNode> r = api.sell(hq, method, product, "1");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    BigDecimal stock() {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(hq, product)
                .query(BigDecimal.class)
                .single();
    }

    /** One request of a kind: where it goes, as whom, and how to tell that nothing was made. */
    record Case(String path, Object body, String perms, boolean keyed, BooleanSupplier nothingMade) {}

    enum Kind {
        SALE_VOID,
        EXPENSE,
        STOCK_CORRECTION,
        CUSTOMER_RETURN,
        CREDIT_WRITE_OFF,
        PURCHASE_ORDER,
        RETURN_VOID
    }

    Case prepare(Kind kind) {
        return switch (kind) {
            case SALE_VOID -> {
                switchedOffAfterTheModuleRead("retail_sale_void", 0);
                UUID s = sale("cash");
                yield new Case(
                        "/sales/" + s + "/void",
                        Map.of("reason", "Rang up twice"),
                        RetailTestSupport.ADMIN,
                        false,
                        () -> api.get("/sales/" + s, RetailTestSupport.ADMIN)
                                .getBody()
                                .get("status")
                                .asString()
                                .equals("completed"));
            }
            case EXPENSE -> {
                switchedOffAfterTheModuleRead("retail_expense", 0);
                UUID[] ci = cb.categoryWithItem("Transport", "Boda fare", false);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("branch_id", hq);
                body.put("category_id", ci[0]);
                body.put("item_id", ci[1]);
                body.put("amount_minor", 70_000);
                yield new Case(
                        "/expenses",
                        body,
                        CashbookTestSupport.ALL,
                        true,
                        () -> count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?", t.tenantId()) == 0);
            }
            case STOCK_CORRECTION -> {
                switchedOffAfterTheModuleRead("retail_stock_correction", 0);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("branch_id", hq);
                body.put("product_id", product);
                body.put("new_qty", "15");
                body.put("expected_qty", "20");
                body.put("reason_code", "miscount");
                yield new Case(
                        "/stock/corrections",
                        body,
                        RetailTestSupport.ADMIN,
                        true,
                        () -> stock().compareTo(new BigDecimal("20")) == 0);
            }
            case CUSTOMER_RETURN -> {
                capability("returns");
                switchedOffAfterTheModuleRead("retail_sale_return", 0);
                UUID s = sale("cash");
                String line = api.get("/sales/" + s, RetailTestSupport.ADMIN)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("id")
                        .asString();
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("sale_line_id", line);
                l.put("qty", "1");
                l.put("reason", "defective");
                l.put("disposition", "restock");
                yield new Case(
                        "/sales/" + s + "/returns",
                        Map.of("lines", List.of(l), "refund_method", "cash"),
                        RETURNER,
                        true,
                        () -> count("SELECT count(*) FROM retail_sale_returns WHERE tenant_id = ?", t.tenantId()) == 0);
            }
            case CREDIT_WRITE_OFF -> {
                // From 500: a debt of 1,500 asks whatever the number of administrators (with 0 it would ask only with
                // two).
                switchedOffAfterTheModuleRead("retail_sale_write_off", 500);
                UUID s = sale("credit");
                yield new Case(
                        "/sales/" + s + "/write-off",
                        Map.of("reason", "Buyer left town"),
                        WRITER,
                        true,
                        () -> count("SELECT count(*) FROM retail_sale_writeoffs WHERE tenant_id = ?", t.tenantId())
                                == 0);
            }
            case RETURN_VOID -> {
                // A return made while returns ask for nothing, then voided while the module reads them as on from 0:
                // the void
                // of a return follows the returns rule (ADR-091 decision 6), and the rule it read goes with the
                // request.
                capability("returns");
                UUID s = sale("cash");
                String line = api.get("/sales/" + s, RetailTestSupport.ADMIN)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("id")
                        .asString();
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("sale_line_id", line);
                l.put("qty", "1");
                l.put("reason", "defective");
                l.put("disposition", "restock");
                ResponseEntity<JsonNode> back = api.postKeyed(
                        "/sales/" + s + "/returns",
                        Map.of("lines", List.of(l), "refund_method", "cash"),
                        RETURNER,
                        "*",
                        UUID.randomUUID().toString());
                assertThat(back.getStatusCode()).as("%s", back.getBody()).isEqualTo(HttpStatus.CREATED);
                String id = back.getBody().get("sale_return").get("id").asString();
                switchedOffAfterTheModuleRead("retail_sale_return", 0);
                yield new Case(
                        "/returns/" + id + "/void",
                        Map.of("reason", "Entered twice"),
                        RETURNER,
                        true,
                        () -> api.get("/returns/" + id, RETURNER)
                                .getBody()
                                .get("status")
                                .asString()
                                .equals("completed"));
            }
            case PURCHASE_ORDER -> {
                capability("purchasing");
                switchedOffAfterTheModuleRead("retail_purchase_order", 0);
                UUID supplier = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 01"), BUYER));
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("product_id", product);
                line.put("qty", "10");
                line.put("cost_minor", 1_000);
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("supplier_id", supplier);
                order.put("branch_id", hq);
                order.put("lines", List.of(line));
                ResponseEntity<JsonNode> draft = api.postKeyed(
                        "/purchase-orders", order, BUYER, "*", UUID.randomUUID().toString());
                assertThat(draft.getStatusCode()).as("%s", draft.getBody()).isEqualTo(HttpStatus.CREATED);
                UUID id = RetailTestSupport.id(draft);
                yield new Case(
                        "/purchase-orders/" + id + "/send",
                        Map.of(),
                        BUYER,
                        true,
                        () -> api.get("/purchase-orders/" + id, BUYER)
                                .getBody()
                                .get("status")
                                .asString()
                                .equals("draft"));
            }
        };
    }

    // ---- the race, for every kind ------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anActionAskedForWaitsAlthoughTheSwitchWentOff(Kind kind) {
        Case c = prepare(kind);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = c.keyed()
                ? api.postKeyed(c.path(), c.body(), c.perms(), "*", key)
                : api.post(c.path(), c.body(), c.perms());
        assertThat(made.getStatusCode()).as("%s %s", kind, made.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = made.getBody().get("approval_id").asString();
        assertThat(count(
                        "SELECT count(*) FROM approval_requests WHERE tenant_id = ? AND id = ? AND status = 'pending'",
                        t.tenantId(),
                        UUID.fromString(approval)))
                .as("%s", kind)
                .isEqualTo(1);
        assertThat(c.nothingMade().getAsBoolean())
                .as("%s: nothing made while it waits", kind)
                .isTrue();
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.approval.executed_switched_off'",
                        t.tenantId()))
                .as("%s", kind)
                .isZero();
        if (c.keyed()) {
            // The stored outcome is the true one: the replay is the same waiting request.
            ResponseEntity<JsonNode> replay = api.postKeyed(c.path(), c.body(), c.perms(), "*", key);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.getBody().get("approval_id").asString()).isEqualTo(approval);
        }
        assertThat(count("SELECT count(*) FROM approval_requests WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
    }

    // ---- a caller that read no rule ------------------------------------------------------------

    /**
     * ADR-091 decision 4 with ADR-043 decision 10: a write-off the approvals module runs at once because the switch is off is
     * still flagged ({@code approval_switched_off}) and told to the administrators who count.
     */
    @Test
    void aWriteOffRunAtOnceBecauseTheSwitchIsOffIsStillFlaggedAndTold() {
        storedOff("retail_sale_write_off");
        UUID admin = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?, 'active')")
                .params(admin, t.tenantId(), "Test Admin 01", "test.admin." + admin + "@example.invalid")
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, granted_by)"
                        + " VALUES (?, ?, ?, 'tenant_admin', ?)")
                .params(UUID.randomUUID(), t.tenantId(), admin, admin)
                .update();
        UUID s = sale("credit");
        int version = api.get("/sales/" + s, RetailTestSupport.ADMIN)
                .getBody()
                .get("version")
                .asInt();
        Approvals.Outcome outcome = TenantContext.callAs(t.tenantId(), () -> {
            CurrentPrincipal.set(Principal.uniform(
                    api.userId(), "staff", Set.of("retail.credit.write_off", "retail.profit.read"), true, Set.of()));
            try {
                return new TransactionTemplate(transactionManager)
                        .execute(status -> approvals.request(new ActionRequest(
                                "retail_sale_write_off",
                                hq,
                                s,
                                version,
                                1_500L,
                                "UGX",
                                Map.of("reason", "Buyer left town", "unpaid_minor", 1_500))));
            } finally {
                CurrentPrincipal.clear();
            }
        });
        assertThat(outcome.executed()).isTrue();
        assertThat(TestDatabase.owner()
                        .sql("SELECT data -> 'after' ->> 'no_second_person' FROM audit_log WHERE tenant_id = ?"
                                + " AND action = 'retail.sale.written_off'")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("approval_switched_off");
        assertThat(count(
                        "SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key = 'retail.write_off_alone'",
                        t.tenantId()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.approval.executed_switched_off'",
                        t.tenantId()))
                .isEqualTo(1);
    }
}
