package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The canary at the client boundary (spec 5.8 leak test, ADR-037 decision 9; issue #223): a fabricated insights book whose
 * branches and products are named with canaries goes through the whole chain to the real gateway and the recording client,
 * and no canary is in the request the client received, in the call log, or in the facts. The same request passes the
 * gateway's own input screen (it is answered, not refused for digits), and a valid recorded answer comes back mapped to
 * the real names for the person who asked and for nobody else. The provider is the recording stub: no network.
 */
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=3",
            "db-scheduler.enabled=false",
            "bms.ai.provider=recording",
            "bms.ai.model=test-model",
            "bms.ai.platform-daily-tokens=1000000",
            "bms.ai.platform-daily-cost-micro=100000000",
            "bms.ai.tenant-daily-tokens=1000000",
            "bms.ai.tenant-daily-cost-micro=100000000"
        })
class AdvisorCanaryIT extends IntegrationTest {

    static final TestDatabase.Fixture TENANT = TestDatabase.tenant("advisor-canary", true);
    static final List<String> CANARIES = new ArrayList<>();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static boolean ready;

    @DynamicPropertySource
    static void allowList(DynamicPropertyRegistry registry) {
        registry.add("bms.ai.enabled-tenants", () -> TENANT.tenantId().toString());
    }

    @Autowired
    LendingSeeder seeder;

    @Autowired
    PortfolioFacts portfolioFacts;

    @Autowired
    AiGateway gateway;

    @Autowired
    AiSettings ai;

    @Autowired
    LlmClient llm;

    RecordingLlmClient recording;
    UUID staff;

    @BeforeEach
    void ready() {
        recording = (RecordingLlmClient) llm;
        recording.reset();
        staff = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Canary Staff Qx', ?, 'active')")
                .params(staff, TENANT.tenantId(), "canary-" + staff + "@example.test")
                .update();
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of("core.settings.manage", "lending.insights.read", "lending.insights.all_officers"),
                true,
                Set.of()));
        if (ready) {
            return;
        }
        seeder.seed(TENANT.slug(), new LendingSeeder.Options(true, 1));
        int i = 0;
        for (UUID id : TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ? ORDER BY id")
                .param(TENANT.tenantId())
                .query(UUID.class)
                .list()) {
            String name = "Canary Branch Zq" + (++i);
            CANARIES.add(name);
            TestDatabase.owner()
                    .sql("UPDATE branches SET name = ? WHERE id = ?")
                    .params(name, id)
                    .update();
        }
        i = 0;
        for (UUID id : TestDatabase.owner()
                .sql("SELECT id FROM lending_loan_products WHERE tenant_id = ? ORDER BY id")
                .param(TENANT.tenantId())
                .query(UUID.class)
                .list()) {
            String name = "Canary Product Zq" + (++i);
            CANARIES.add(name);
            TestDatabase.owner()
                    .sql("UPDATE lending_loan_products SET name = ? WHERE id = ?")
                    .params(name, id)
                    .update();
        }
        CANARIES.add("Canary Staff Qx");
        int version = TenantContext.callAs(TENANT.tenantId(), ai::status).version();
        TenantContext.callAs(TENANT.tenantId(), () -> ai.enable("ack-v1", version));
        ready = true;
    }

    @AfterEach
    void signOut() {
        CurrentPrincipal.clear();
    }

    static String answerAbout(PromptFact figure, PromptFact group) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode findings = root.putArray("findings");
        ObjectNode f = findings.addObject();
        f.putArray("fact_keys").add(figure.key()).add(group.key());
        f.put("topic", "concentration");
        f.put("action", "CONCENTRATION_REVIEW_SPREAD");
        f.put("priority", "medium");
        root.putArray("watch");
        return root.toString();
    }

    @Test
    void aPlantedNameIsInNoRequestNoLogRowAndNoFactAndTheAnswerComesBackWithNames() {
        FactSet set = TenantContext.callAs(TENANT.tenantId(), portfolioFacts::forCaller);
        assertThat(CANARIES).hasSizeGreaterThan(3);
        assertThat(set.names()).as("the groups are named for the mapping back").isNotEmpty();
        assertThat(set.facts().toString()).doesNotContain("Canary");

        AliasedFacts facts = AliasedFacts.of(set);
        PromptFact figure = facts.facts().stream()
                .filter(f -> f.key().equals("portfolio.active_loans"))
                .findFirst()
                .orElseThrow();
        PromptFact group = facts.facts().stream()
                .filter(f -> f.alias() != null && !f.suppressed() && f.key().endsWith(".loans"))
                .findFirst()
                .orElseThrow();
        recording.thenAnswer(new LlmClient.Result(answerAbout(figure, group), 400, 80, "stop"));

        LlmClient.Request request = PromptBuilder.brief(facts);
        AiResult result = TenantContext.callAs(TENANT.tenantId(), () -> gateway.complete("brief", staff, request));

        // The gateway's own input screen let the prompt through: grouped figures, no phone, no run of six digits.
        assertThat(result.reason()).as("%s", result).isNull();
        assertThat(result.answered()).isTrue();

        // The request the client received holds no canary, no id, no staff name, and no run of six digits.
        assertThat(recording.requests()).hasSize(1);
        String sent = recording.requests().get(0).messages().stream()
                .map(LlmClient.Message::content)
                .collect(Collectors.joining("\n"));
        for (String canary : CANARIES) {
            assertThat(sent).doesNotContainIgnoringCase(canary);
        }
        for (var name : set.names()) {
            assertThat(sent).doesNotContain(name.group().id()).doesNotContainIgnoringCase(name.name());
        }
        assertThat(sent).doesNotContain(staff.toString()).doesNotContain("+256").doesNotContain("0700");
        assertThat(sent).contains(group.alias()).contains(group.key());

        // No log row holds a canary: the log keeps hashes and counts, never text.
        List<Map<String, Object>> rows = TestDatabase.owner()
                .sql("SELECT * FROM ai_calls WHERE tenant_id = ?")
                .param(TENANT.tenantId())
                .query()
                .listOfRows();
        assertThat(rows).isNotEmpty();
        String logged = rows.stream().map(Object::toString).collect(Collectors.joining("\n"));
        for (String canary : CANARIES) {
            assertThat(logged).doesNotContainIgnoringCase(canary);
        }
        assertThat(logged).doesNotContain(group.alias());

        // The answer is valid, rendered by our code, and shown with the real name in place of the alias.
        NumericValidator.Result verdict =
                StructuredValidator.validate(result.text(), NumericValidator.Context.of(facts));
        assertThat(verdict.violations()).isEmpty();
        assertThat(verdict.valid()).isTrue();
        String shown = facts.aliases().toNames(verdict.answer().points().get(0).text());
        assertThat(shown)
                .doesNotContain(group.alias())
                .contains("Canary ")
                .contains("Active loans (" + group.display() + ")");
    }

    @Test
    void anAnswerWithFreeTextOrAnUnknownFactIsRefusedAndTheDeterministicBriefIsShown() {
        FactSet set = TenantContext.callAs(TENANT.tenantId(), portfolioFacts::forCaller);
        AliasedFacts facts = AliasedFacts.of(set);
        String name = set.names().get(0).name();
        NumericValidator.Context ctx = NumericValidator.Context.of(facts);

        // Free prose, even one that repeats a real name, is not JSON of the shape: refused, nothing of it shown.
        NumericValidator.Result r = StructuredValidator.validate(name + " is the weakest.", ctx);
        assertThat(r.valid()).isFalse();
        assertThat(r.answer()).isNull();
        assertThat(r.violations()).extracting(NumericValidator.Violation::code).containsExactly("not_json");

        // A field the schema does not have, however harmless it looks, is refused.
        ObjectNode extra = JSON.createObjectNode();
        extra.putArray("findings");
        extra.putArray("watch");
        extra.put("summary", name + " is the weakest.");
        r = StructuredValidator.validate(extra.toString(), ctx);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).extracting(NumericValidator.Violation::code).contains("unknown_field");

        // A fact key that was not given (a real name, an invented figure) is refused.
        ObjectNode invented = JSON.createObjectNode();
        ObjectNode f = invented.putArray("findings").addObject();
        f.putArray("fact_keys").add(name);
        f.put("topic", "concentration");
        f.put("action", "CONCENTRATION_REVIEW_SPREAD");
        f.put("priority", "low");
        invented.putArray("watch");
        r = StructuredValidator.validate(invented.toString(), ctx);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).extracting(NumericValidator.Violation::code).contains("unknown_fact");

        // What the person sees instead is made by our code from the facts alone and holds no canary.
        AdvisorAnswer shownInstead = Fallback.render(facts);
        String fallback = shownInstead.summary() + shownInstead.points();
        assertThat(fallback).contains("Key figures:");
        for (String canary : CANARIES) {
            assertThat(fallback).doesNotContainIgnoringCase(canary);
        }
    }
}
