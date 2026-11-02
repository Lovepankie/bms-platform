package com.rincoltech.bms.retail.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.advisor.internal.RetailDailyBriefService.Outcome;
import com.rincoltech.bms.retail.reports.RetailBriefFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The retail AI brief end to end (issue #422): the switch, the daily brief through the one gateway with the recording stub, the
 * stored brief and who reads it, a refused answer, and the automatic summary for a Storekeeper and for a seller who sees only
 * their own sales. Every figure and name is fabricated; the item's code is a canary that must never reach the request.
 */
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=4",
            "db-scheduler.enabled=false",
            "bms.ai.provider=recording",
            "bms.ai.model=retail-brief-test-model",
            "bms.ai.platform-daily-tokens=1000000",
            "bms.ai.platform-daily-cost-micro=100000000",
            "bms.ai.tenant-daily-tokens=500000",
            "bms.ai.tenant-daily-cost-micro=50000000"
        })
@TestMethodOrder(MethodOrderer.MethodName.class)
class RetailBriefIT extends IntegrationTest {

    static final TestDatabase.Fixture ON = TestDatabase.tenant("rbrief-on", false, true);
    static final TestDatabase.Fixture OFF = TestDatabase.tenant("rbrief-off", false, true);
    static final String CANARY = "ZQCANARYSOAP";
    static final String PROSE = "Zq unlikely model prose: lower every price today.";

    static final String OWNER =
            RetailTestSupport.ADMIN + ",retail.advisor.use,retail.advisor.configure,core.settings.manage,"
                    + "retail.controls.read,retail.order.manage";
    static final String STOREKEEPER = "retail.advisor.use,retail.stock.read,retail.stock.transfer";
    static final String SELLER = RetailTestSupport.SALES + ",retail.advisor.use";

    static boolean ready;
    static int dayOffset = 2;

    @DynamicPropertySource
    static void allowList(DynamicPropertyRegistry registry) {
        registry.add("bms.ai.enabled-tenants", () -> ON.tenantId() + "," + OFF.tenantId());
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    LlmClient llm;

    @Autowired
    AiSettings ai;

    @Autowired
    RetailDailyBriefService daily;

    @Autowired
    RetailBriefFacts briefFacts;

    RecordingLlmClient recording;
    RetailTestSupport api;

    @BeforeEach
    void ready() {
        recording = (RecordingLlmClient) llm;
        recording.reset();
        api = new RetailTestSupport(http, ON);
        if (ready) {
            return;
        }
        UUID soap = api.product(CANARY, 1_000, 1_500);
        api.stockUp(ON.headOffice(), soap, "40");
        sell(soap, "3", 1);
        sell(soap, "2", 8);
        // The acknowledgement records who switched it on.
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status)"
                        + " VALUES (?, ?, 'staff', 'Brief Staff Qy', '+256700000092', 'active') ON CONFLICT DO NOTHING")
                .params(api.userId(), ON.tenantId())
                .update();
        CurrentPrincipal.set(Principal.uniform(
                api.userId(), "staff", Set.of("core.settings.manage", "retail.advisor.configure"), true, Set.of()));
        try {
            int version = TenantContext.callAs(ON.tenantId(), ai::status).version();
            TenantContext.callAs(ON.tenantId(), () -> ai.enable("retail-advisor-1", version));
        } finally {
            CurrentPrincipal.clear();
        }
        ready = true;
    }

    void sell(UUID product, String qty, int daysAgo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", ON.headOffice());
        body.put("sale_date", today().minusDays(daysAgo).toString());
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", qty)));
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales", body, RetailTestSupport.ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    static LocalDate today() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    static LocalDate nextDay() {
        return today().minusDays(dayOffset++);
    }

    ResponseEntity<JsonNode> brief(TestDatabase.Fixture t, String permissions) {
        return new RetailTestSupport(http, t).call(HttpMethod.GET, "/advisor/brief", null, permissions, "*", Map.of());
    }

    Map<String, Object> row(LocalDate date) {
        return TestDatabase.owner()
                .sql("SELECT * FROM retail_ai_briefs WHERE tenant_id = ? AND brief_date = ?")
                .params(ON.tenantId(), date)
                .query()
                .singleRow();
    }

    static List<String> keys(JsonNode brief) {
        List<String> keys = new ArrayList<>();
        brief.get("points").forEach(p -> p.get("facts").forEach(f -> keys.add(f.asString())));
        return keys;
    }

    @Test
    void aOffTenantGetsNoCardAndMakesNoCall() {
        assertThat(TenantContext.callAs(OFF.tenantId(), () -> daily.generate(today())))
                .isEqualTo(Outcome.OFF);
        assertThat(recording.requests()).isEmpty();
        ResponseEntity<JsonNode> r = brief(OFF, OWNER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void bTheModelBriefIsWrittenByOurCodeAndShownToTheOwnerWithTheItemNamed() {
        PromptFacts facts = TenantContext.callAs(ON.tenantId(), () -> PromptFacts.of(briefFacts.forOwner(today())));
        assertThat(facts.facts())
                .extracting(PromptFacts.PromptFact::key)
                .contains("item_a.sales_week", "sales.yesterday");
        recording.thenAnswer(new LlmClient.Result("""
                {"findings": [{"fact_keys": ["item_a.sales_week"], "topic": "best_sellers",
                               "action": "BEST_KEEP_IN_STOCK", "priority": "medium"}],
                 "watch": ["stock.low"]}
                """, 400, 60, "stop"));
        assertThat(TenantContext.callAs(ON.tenantId(), () -> daily.generate(today())))
                .isEqualTo(Outcome.STORED_MODEL);

        // What went out: aliases only, never the item's name or code.
        assertThat(recording.requests()).hasSize(1);
        String sent = recording.requests().get(0).messages().stream()
                .map(LlmClient.Message::content)
                .reduce("", String::concat)
                .toUpperCase(Locale.ROOT);
        assertThat(sent).doesNotContain(CANARY).contains("ITEM A");
        assertThat(row(today()).get("source")).isEqualTo("model");

        JsonNode b = brief(ON, OWNER).getBody();
        assertThat(b.get("source").asString()).isEqualTo("model");
        assertThat(b.get("label").asString()).isEqualTo("AI assistant (advisory)");
        assertThat(b.get("advisory").asBoolean()).isTrue();
        assertThat(b.get("points").get(0).get("text").asString().toUpperCase(Locale.ROOT))
                .startsWith("MEDIUM PRIORITY.")
                .contains(CANARY)
                .contains("SUGGESTED ACTION: KEEP ENOUGH STOCK FOR");
        assertThat(b.get("facts").get(0).get("metric").asString()).isEqualTo("item.sales_week");
        // A second run of the same day changes nothing and pays nothing.
        assertThat(TenantContext.callAs(ON.tenantId(), () -> daily.generate(today())))
                .isEqualTo(Outcome.EXISTS);
        assertThat(recording.requests()).hasSize(1);
    }

    @Test
    void cAnAnswerOutsideTheSchemaStoresTheAutomaticSummaryWithTheReason() {
        LocalDate date = nextDay();
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        assertThat(TenantContext.callAs(ON.tenantId(), () -> daily.generate(date)))
                .isEqualTo(Outcome.STORED_FALLBACK);
        Map<String, Object> row = row(date);
        assertThat(row.get("source")).isEqualTo("fallback");
        assertThat(row.get("reason")).isEqualTo("validation");
        assertThat(row.get("call_id")).isNotNull();
        assertThat(String.valueOf(row.get("points"))).doesNotContain("unlikely");
    }

    @Test
    void dAStorekeeperGetsStockCountsAndNeverMoney() {
        JsonNode b = brief(ON, STOREKEEPER).getBody();
        assertThat(b.get("source").asString()).isEqualTo("fallback");
        assertThat(b.get("label").asString()).isEqualTo("Automatic summary");
        assertThat(keys(b)).isNotEmpty().allMatch(k -> k.startsWith("stock."));
        assertThat(b.toString()).doesNotContain("UGX");
    }

    @Test
    void eASellerWhoSeesOnlyTheirOwnSalesGetsTheirOwnFiguresOnly() {
        JsonNode b = brief(ON, SELLER).getBody();
        assertThat(b.get("source").asString()).isEqualTo("fallback");
        assertThat(keys(b))
                .contains("own.sales_yesterday")
                .noneMatch(k -> k.startsWith("sales.") || k.startsWith("credit."));
    }

    @Test
    void fTheSettingsShowTheRetailNoticeAndNeedTheConfigurePermission() {
        ResponseEntity<JsonNode> s = api.call(HttpMethod.GET, "/advisor/settings", null, OWNER, "*", Map.of());
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(s.getBody().get("enabled").asBoolean()).isTrue();
        assertThat(s.getBody().get("acknowledgement").get("text_version").asString())
                .isEqualTo("retail-advisor-1");
        assertThat(s.getBody().get("acknowledgement").get("text").asString())
                .contains("Item A")
                .contains("outside Uganda");
        ResponseEntity<JsonNode> refused =
                api.call(HttpMethod.GET, "/advisor/settings", null, "retail.advisor.use", "*", Map.of());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
