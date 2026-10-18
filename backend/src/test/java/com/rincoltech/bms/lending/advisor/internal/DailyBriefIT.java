package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.lending.advisor.Facts;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.BriefView;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.SettingsView;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.advisor.internal.DailyBriefService.Outcome;
import com.rincoltech.bms.lending.insights.FactDisplay;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Fact;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The daily AI brief end to end (extras slice C3, issue #226, ADR-037): the switch, the job, the structured answer path, the
 * stored brief and who may read it, and the fallback on every failure path. The provider is the recording stub, so nothing
 * can reach a network; every figure and name is fabricated. The clock is the movable one, fixed at 23:59 Kampala time on
 * 2026-10-15 (so "today" is the 15th and the seeded snapshots end on the 14th) and moved by the tests that need midnight.
 *
 * <p>One tenant is seeded and switched on; a second is on the platform list and left off; a third has its switch ON but is
 * not on the platform list (the state of a tenant removed from {@code BMS_AI_ENABLED_TENANTS} while on). The class has a
 * model name of its own so that it gets an application context, and a circuit breaker, of its own: the breaker test opens it.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=4",
            "db-scheduler.enabled=false",
            "bms.ai.provider=recording",
            "bms.ai.model=c3-daily-brief-test-model",
            "bms.ai.platform-daily-tokens=1000000",
            "bms.ai.platform-daily-cost-micro=100000000",
            "bms.ai.tenant-daily-tokens=500000",
            "bms.ai.tenant-daily-cost-micro=50000000"
        })
@TestMethodOrder(MethodOrderer.MethodName.class)
class DailyBriefIT extends IntegrationTest {

    static final TestDatabase.Fixture ON = TestDatabase.tenant("brief-on", true);
    static final TestDatabase.Fixture OFF = TestDatabase.tenant("brief-off", true);
    static final TestDatabase.Fixture UNLISTED = TestDatabase.tenant("brief-unlisted", true);
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PROSE = "Zq-unlikely-model-prose: call every borrower today.";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    static final Pattern GROUPED_SEVEN_DIGITS = Pattern.compile("\\d{1,3}(,\\d{3}){2,}");
    static final List<String> CANARIES = new ArrayList<>();
    static boolean ready;
    static int dayOffset = 1;

    @DynamicPropertySource
    static void allowList(DynamicPropertyRegistry registry) {
        registry.add("bms.ai.enabled-tenants", () -> ON.tenantId() + "," + OFF.tenantId());
    }

    @Autowired
    LendingSeeder seeder;

    @MockitoSpyBean
    PortfolioFacts portfolioFacts;

    @Autowired
    AiGateway gateway;

    @Autowired
    AiSettings ai;

    @Autowired
    LlmClient llm;

    @Autowired
    DailyBriefService daily;

    @Autowired
    BriefReadService reader;

    @Autowired
    AdvisorSettingsService settings;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    MovableClock clock;

    @Autowired
    RecurringTask<Void> advisorDailyBrief;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient appJdbc;

    @Autowired
    TestRestTemplate http;

    RecordingLlmClient recording;
    UUID staff;

    @BeforeEach
    void ready() {
        clock.set(MovableClockConfig.BEFORE_MIDNIGHT);
        recording = (RecordingLlmClient) llm;
        recording.reset();
        staff = UUID.randomUUID();
        for (TestDatabase.Fixture t : List.of(ON, OFF, UNLISTED)) {
            TestDatabase.owner()
                    .sql(
                            "INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status) VALUES (?, ?, 'staff', 'Brief Staff Qx', '+256700000091', 'active') ON CONFLICT DO NOTHING")
                    .params(staff, t.tenantId())
                    .update();
        }
        admin();
        if (ready) {
            return;
        }
        seeder.seed(ON.slug(), new LendingSeeder.Options(true, 1));
        int i = 0;
        for (UUID id : TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ? ORDER BY id")
                .param(ON.tenantId())
                .query(UUID.class)
                .list()) {
            String name = "Brief Canary Branch Zq" + (++i);
            CANARIES.add(name);
            TestDatabase.owner()
                    .sql("UPDATE branches SET name = ? WHERE id = ?")
                    .params(name, id)
                    .update();
        }
        CANARIES.add("Brief Staff Qx");
        int version = TenantContext.callAs(ON.tenantId(), ai::status).version();
        TenantContext.callAs(ON.tenantId(), () -> ai.enable("advisor-1", version));
        setAiSwitch(UNLISTED, true);
        ready = true;
    }

    @AfterEach
    void signOut() {
        CurrentPrincipal.clear();
        clock.set(MovableClockConfig.BEFORE_MIDNIGHT);
    }

    /** A tenant administrator: every permission of the brief, in every branch. */
    void admin() {
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of(
                        "core.settings.manage",
                        "lending.insights.read",
                        "lending.insights.all_officers",
                        "lending.advisor.use",
                        "lending.advisor.configure"),
                true,
                Set.of()));
    }

    /** Writes the tenant's own switch straight into its settings, as the owner: no allow-list check on the way. */
    static void setAiSwitch(TestDatabase.Fixture t, boolean on) {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        int rows = TestDatabase.owner().sql("""
                        UPDATE tenant_settings
                           SET settings = settings || jsonb_build_object('module_groups',
                                   coalesce(settings->'module_groups', '{}'::jsonb)
                                   || jsonb_build_object('ai', jsonb_build_object('enabled', ?::boolean)))
                         WHERE tenant_id = ?
                        """).params(on, t.tenantId()).update();
        assertThat(rows).as("the fixture has a settings row").isEqualTo(1);
    }

    /** The next date nothing has used: the 14th (the last seeded snapshot) and back. */
    static LocalDate nextDay() {
        return TODAY.minusDays(dayOffset++);
    }

    static PortfolioScope scopeOf(LocalDate date) {
        return new PortfolioScope(date.withDayOfMonth(1), date, date, null, null, null);
    }

    FactSet facts(LocalDate date) {
        return TenantContext.callAs(ON.tenantId(), () -> portfolioFacts.build(scopeOf(date)));
    }

    /** A valid structured answer that cites the active loans and one group's loans. */
    static String validAnswer(AliasedFacts facts) {
        PromptFact figure = facts.facts().stream()
                .filter(f -> f.key().equals("portfolio.active_loans"))
                .findFirst()
                .orElseThrow();
        PromptFact group = facts.facts().stream()
                .filter(f -> f.alias() != null && !f.suppressed() && f.key().endsWith(".loans"))
                .findFirst()
                .orElseThrow();
        ObjectNode root = JSON.createObjectNode();
        ObjectNode f = root.putArray("findings").addObject();
        f.putArray("fact_keys").add(figure.key()).add(group.key());
        f.put("topic", "concentration");
        f.put("action", "CONCENTRATION_REVIEW_SPREAD");
        f.put("priority", "medium");
        root.putArray("watch");
        return root.toString();
    }

    LlmClient.Result valid(LocalDate date) {
        return new LlmClient.Result(validAnswer(AliasedFacts.of(facts(date))), 400, 80, "stop");
    }

    /** One answered call, so that failures never run five in a row and open the breaker in a test that is not about it. */
    void succeed() {
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        generate(nextDay());
    }

    Outcome generate(LocalDate date) {
        return TenantContext.callAs(ON.tenantId(), () -> daily.generate(date));
    }

    Map<String, Object> storedRow(LocalDate date) {
        return TestDatabase.owner()
                .sql("SELECT * FROM lending_ai_briefs WHERE tenant_id = ? AND brief_date = ?")
                .params(ON.tenantId(), date)
                .query()
                .singleRow();
    }

    long briefRows(TestDatabase.Fixture t, LocalDate date) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_ai_briefs WHERE tenant_id = ? AND brief_date = ?")
                .params(t.tenantId(), date)
                .query(Long.class)
                .single();
    }

    long calls(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM ai_calls WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    Optional<BriefView> view(TestDatabase.Fixture t, LocalDate date) {
        return TenantContext.callAs(t.tenantId(), () -> reader.forDate(date));
    }

    // ---- The switch: off includes not being on the platform list -------------------------------------------------

    @Test
    void aOffSwitchMakesNoCallReadsNoFactsStoresNothingAndTheCardIsNotOffered() {
        LocalDate date = nextDay();
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        Outcome outcome = TenantContext.callAs(OFF.tenantId(), () -> daily.generate(date));
        assertThat(outcome).isEqualTo(Outcome.OFF);
        assertThat(recording.requests()).isEmpty();
        assertThat(calls(OFF)).isZero();
        assertThat(briefRows(OFF, date)).isZero();

        // The page: nothing for the card, and the figures of the caller are not read for it.
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of("lending.insights.read", "lending.insights.all_officers", "lending.advisor.use"),
                true,
                Set.of()));
        org.mockito.Mockito.clearInvocations(portfolioFacts);
        assertThat(view(OFF, date)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(portfolioFacts);
        assertThat(recording.requests()).isEmpty();
    }

    @Test
    void aSwitchedOnTenantThatIsNotOnThePlatformListIsOffNoFactsNoCallNoRowAndAnOldModelBriefIsNotServed() {
        LocalDate date = nextDay();
        // A model brief stored while the tenant was on the list: it must not be served now.
        TestDatabase.owner().sql("""
                        INSERT INTO lending_ai_briefs (tenant_id, brief_date, source, summary, points, chips, facts_as_of,
                                                       generated_at, model)
                        VALUES (?, ?, 'model', 'An old brief of a fabricated day.', '[]'::jsonb, '[]'::jsonb, ?, now(), 'm')
                        """).params(UNLISTED.tenantId(), date, date).update();
        SettingsView s = TenantContext.callAs(UNLISTED.tenantId(), settings::view);
        assertThat(s.enabled()).as("its own switch is on").isTrue();
        assertThat(s.allowed()).as("but the platform has not named it").isFalse();

        org.mockito.Mockito.clearInvocations(portfolioFacts);
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        assertThat(TenantContext.callAs(UNLISTED.tenantId(), () -> daily.generate(date.minusDays(1))))
                .isEqualTo(Outcome.OFF);
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of("lending.insights.read", "lending.insights.all_officers", "lending.advisor.use"),
                true,
                Set.of()));
        assertThat(view(UNLISTED, date)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(portfolioFacts);
        assertThat(recording.requests()).isEmpty();
        assertThat(calls(UNLISTED)).isZero();
        assertThat(briefRows(UNLISTED, date.minusDays(1))).isZero();
    }

    @Test
    void bTheSwitchNeedsTheAllowListTheAcknowledgementAndTheRightPermission() {
        // Not on the platform list: refused with advisor_not_available, whatever is acknowledged.
        TestDatabase.Fixture other = TestDatabase.tenant("brief-other", true);
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status) VALUES (?, ?, 'staff', 'Brief Staff Qx', '+256700000091', 'active') ON CONFLICT DO NOTHING")
                .params(staff, other.tenantId())
                .update();
        SettingsView before = TenantContext.callAs(other.tenantId(), settings::view);
        assertThat(before.allowed()).isFalse();
        assertThat(before.enabled()).isFalse();
        assertThatThrownBy(() -> TenantContext.callAs(
                        other.tenantId(),
                        () -> settings.update(
                                String.valueOf(before.version()),
                                new AdvisorSettingsService.Change(
                                        true, AdvisorSettingsService.ACK_TEXT_VERSION, null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("advisor_not_available"));

        SettingsView off = TenantContext.callAs(OFF.tenantId(), settings::view);
        assertThat(off.allowed()).isTrue();
        assertThat(off.acknowledgement().text()).contains("outside Uganda").contains("never a name");

        // No acknowledgement, or the wrong text version: refused, still off.
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(off.version()),
                                new AdvisorSettingsService.Change(true, null, null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("acknowledgement_required"));
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(off.version()),
                                new AdvisorSettingsService.Change(true, "old-text", null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("acknowledgement_required"));

        // Without core.settings.manage the switch is refused (the caps alone need only configure).
        CurrentPrincipal.set(Principal.uniform(staff, "staff", Set.of("lending.advisor.configure"), true, Set.of()));
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(off.version()),
                                new AdvisorSettingsService.Change(
                                        true, AdvisorSettingsService.ACK_TEXT_VERSION, null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("permission_denied"));

        // A stale version is a conflict.
        admin();
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(off.version() + 7),
                                new AdvisorSettingsService.Change(
                                        true, AdvisorSettingsService.ACK_TEXT_VERSION, null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("version_conflict"));

        // Acknowledged: on, with who and when and which text (the version and a digest of the wording).
        SettingsView on = TenantContext.callAs(
                OFF.tenantId(),
                () -> settings.update(
                        String.valueOf(off.version()),
                        new AdvisorSettingsService.Change(true, AdvisorSettingsService.ACK_TEXT_VERSION, 1000L, null)));
        assertThat(on.enabled()).isTrue();
        assertThat(on.acknowledgedBy()).isEqualTo(staff);
        assertThat(on.acknowledgedAt()).isNotNull();
        assertThat(on.acknowledgedTextVersion())
                .isEqualTo(AdvisorSettingsService.ACK_RECORD)
                .startsWith("advisor-1+");
        assertThat(on.dailyTokenCap()).isEqualTo(1000L);

        // A cap above the platform's ceiling is refused; off again keeps the record.
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(on.version()),
                                new AdvisorSettingsService.Change(null, null, 2_000_000L, null))))
                .isInstanceOf(ApiException.class);
        SettingsView disabled = TenantContext.callAs(
                OFF.tenantId(),
                () -> settings.update(
                        String.valueOf(on.version()), new AdvisorSettingsService.Change(false, null, null, null)));
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.acknowledgedBy()).isEqualTo(staff);
    }

    // ---- The structured answer path and the stored brief ---------------------------------------------------

    @Test
    void cAnAcceptedAnswerIsStoredRenderedNeverTheModelsOwnAndARerunChangesNothing() {
        LocalDate date = nextDay();
        AliasedFacts facts = AliasedFacts.of(facts(date));
        String raw = validAnswer(facts);
        recording.thenAnswer(new LlmClient.Result(raw, 400, 80, "stop"));

        assertThat(generate(date)).isEqualTo(Outcome.STORED_MODEL);

        Map<String, Object> row = storedRow(date);
        assertThat(row.get("source")).isEqualTo("model");
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("model")).isEqualTo("c3-daily-brief-test-model");
        assertThat(row.get("call_id")).isNotNull();
        assertThat(row.get("facts_as_of").toString()).isEqualTo(date.toString());
        String kept = row.get("summary") + " " + row.get("points") + " " + row.get("chips");
        assertThat(kept).doesNotContain("CONCENTRATION_REVIEW_SPREAD").doesNotContain("fact_keys");
        assertThat(kept).contains("Active loans (");
        // The real names are put back for the people who may see every branch, and no alias is left.
        assertThat(kept).contains("Brief Canary Branch").doesNotContain("Branch 1");

        // The prompt that went through the gateway's input screen carried a figure of seven digits or more, grouped
        // (the
        // seeded book's own), and no name.
        assertThat(recording.requests()).hasSize(1);
        String sent = recording.requests().get(0).messages().stream()
                .map(LlmClient.Message::content)
                .collect(Collectors.joining("\n"));
        assertThat(GROUPED_SEVEN_DIGITS.matcher(sent).find())
                .as("the real prompt holds a grouped figure of 7 digits or more")
                .isTrue();
        CANARIES.forEach(c -> assertThat(sent).doesNotContainIgnoringCase(c));

        // One per tenant and day: a second run reads nothing and calls nothing.
        assertThat(generate(date)).isEqualTo(Outcome.EXISTS);
        assertThat(recording.requests()).hasSize(1);
        assertThat(briefRows(ON, date)).isEqualTo(1);
    }

    @Test
    void dTheGatewayInputScreenLetsLargeFiguresAndGroupedFactsThroughAndRefusesPersonalShapes() {
        // A tenant with figures of 11 digits and a grouped group fact: the screen lets the real prompt through.
        FactSet big = Facts.asOf("2026-10-14")
                .money(
                        "portfolio.principal_outstanding",
                        "Principal outstanding",
                        Polarity.NEUTRAL,
                        98_765_432_100L,
                        1_234_567L,
                        null)
                .count("portfolio.active_loans", "Active loans", Polarity.NEUTRAL, 1_234_567L, null, null)
                .group(Dimension.BRANCH, "b1", "Big Branch One")
                .groupFact(
                        Dimension.BRANCH,
                        "b1",
                        "branch.loans",
                        "Active loans",
                        Kind.COUNT,
                        Polarity.NEUTRAL,
                        1_000_000L)
                .build();
        LlmClient.Request request = PromptBuilder.brief(AliasedFacts.of(big));
        String content = request.messages().get(1).content();
        assertThat(content).contains("98,765,432,100").contains("1,234,567").contains("1,000,000");
        assertThat(content).doesNotContain("Big Branch One");
        AiResult through = TenantContext.callAs(ON.tenantId(), () -> gateway.complete("brief", null, request));
        assertThat(through.reason()).as("%s", through).isNull();
        assertThat(recording.requests()).hasSize(1);

        // A phone, an ID, an email or a digit run is refused before any call.
        recording.reset();
        for (String bad : List.of(
                "Call +256 700 000 092 about this.",
                "ID CM00000000000A held by the borrower",
                "mail test.person@example.invalid now",
                "account 123456789012")) {
            LlmClient.Request r = new LlmClient.Request(
                    List.of(new LlmClient.Message("system", "x"), new LlmClient.Message("user", bad)), 100, true);
            AiResult result = TenantContext.callAs(ON.tenantId(), () -> gateway.complete("brief", null, r));
            assertThat(result.reason()).as(bad).isEqualTo(AiGateway.Reason.INPUT_REFUSED);
        }
        assertThat(recording.requests()).isEmpty();
    }

    @Test
    void eAPromptThatCarriesAPhoneIsRefusedByTheScreenAndTheJobStoresTheFactsOnlyBrief() {
        LocalDate date = nextDay();
        FactSet real = facts(date);
        List<Fact> tainted = new ArrayList<>(real.facts());
        // A figure the facts-only brief does not print, so that what is stored shows only that no call was made.
        int at = 0;
        while (Fallback.KEY_FIGURES.contains(tainted.get(at).key())) {
            at++;
        }
        Fact first = tainted.get(at);
        // A figure whose name carries a phone number: our own prompt builder would never do it; the screen is the
        // second line.
        tainted.set(
                at,
                new Fact(
                        first.key(),
                        "Call +256 700 000 092",
                        first.kind(),
                        first.polarity(),
                        first.value(),
                        first.display(),
                        first.suppressed(),
                        first.weekOnWeek(),
                        first.monthOnMonth(),
                        first.group()));
        FactSet bad = new FactSet(real.asOf(), real.from(), real.to(), real.currency(), tainted, real.names());
        doReturn(bad).when(portfolioFacts).build(any());
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));

        assertThat(generate(date)).isEqualTo(Outcome.STORED_FALLBACK);

        assertThat(recording.requests()).as("nothing reached the provider").isEmpty();
        Map<String, Object> row = storedRow(date);
        assertThat(row.get("source")).isEqualTo("fallback");
        assertThat(row.get("reason")).isEqualTo("input_refused");
        assertThat(row.get("model")).as("no call was made").isNull();
        assertThat(row.get("call_id")).isNull();
        assertThat(row.get("summary") + " " + row.get("points")).doesNotContain("700 000");
    }

    @Test
    void fARefusedAnswerStoresTheFactsOnlyBriefWithReasonValidationAndStillCountsAgainstTheCaps() {
        LocalDate date = nextDay();
        long tokensBefore = TenantContext.callAs(ON.tenantId(), settings::view).tokensUsedToday();
        recording.thenAnswer(new LlmClient.Result(PROSE, 500, 100, "stop"));

        assertThat(generate(date)).isEqualTo(Outcome.STORED_FALLBACK);

        Map<String, Object> row = storedRow(date);
        assertThat(row.get("source")).isEqualTo("fallback");
        assertThat(row.get("reason")).isEqualTo("validation");
        assertThat(row.get("summary") + " " + row.get("points")).doesNotContain("Zq-unlikely");
        assertThat(row.get("points").toString()).contains("Key figures");

        // The call was paid for: its tokens and cost are counted, and the day's use went up by them.
        Map<String, Object> call = TestDatabase.owner()
                .sql("SELECT * FROM ai_calls WHERE id = ?")
                .param(row.get("call_id"))
                .query()
                .singleRow();
        assertThat(call.get("outcome")).isEqualTo("ok");
        assertThat(((Number) call.get("tokens_counted")).longValue()).isEqualTo(600L);
        assertThat(call.get("estimated_cost_micro")).isNotNull();
        SettingsView after = TenantContext.callAs(ON.tenantId(), settings::view);
        assertThat(after.tokensUsedToday() - tokensBefore).isEqualTo(600L);

        // ...and so it counts toward the daily cap: with the cap just above what is used, the next brief is a cap
        // fallback,
        // which is also final for the day (a cap is the tenant's own limit) and has no call of its own to point at.
        long cap = after.tokensUsedToday() + 100;
        admin();
        TenantContext.callAs(
                ON.tenantId(),
                () -> settings.update(
                        String.valueOf(after.version()), new AdvisorSettingsService.Change(null, null, cap, null)));
        LocalDate next = nextDay();
        assertThat(generate(next)).isEqualTo(Outcome.STORED_FALLBACK);
        assertThat(storedRow(next).get("reason")).isEqualTo("tenant_token_cap");
        assertThat(storedRow(next).get("model")).isNull();
        assertThat(storedRow(next).get("call_id")).isNull();
        assertThat(recording.requests()).hasSize(1);
        SettingsView capped = TenantContext.callAs(ON.tenantId(), settings::view);
        TenantContext.callAs(
                ON.tenantId(),
                () -> settings.update(
                        String.valueOf(capped.version()),
                        new AdvisorSettingsService.Change(null, null, 500_000L, null)));
    }

    // ---- Fallback on every failure path, and the page still loads ------------------------------------------

    @Test
    void gEveryPaidFailureStoresTheFactsOnlyBriefAndThePageStillLoadsLabelledAutomaticSummary() {
        record Path(String name, LlmException failure, String stored, String api) {}
        List<Path> paths = List.of(
                new Path(
                        "provider error",
                        new LlmException(LlmException.Kind.PROVIDER_ERROR, 503, 3),
                        "provider_error",
                        "provider_down"),
                new Path("timeout", new LlmException(LlmException.Kind.TIMEOUT, 0, 1), "timeout", "provider_down"),
                new Path("network", new LlmException(LlmException.Kind.NETWORK, 0, 1), "network", "provider_down"),
                new Path(
                        "rate limited",
                        new LlmException(LlmException.Kind.RATE_LIMITED, 429, 3),
                        "rate_limited",
                        "provider_down"),
                new Path(
                        "bad response",
                        new LlmException(LlmException.Kind.BAD_RESPONSE, 200, 1),
                        "bad_response",
                        "provider_down"));
        for (Path p : paths) {
            LocalDate date = nextDay();
            recording.reset();
            recording.thenFail(p.failure());
            assertThat(generate(date)).as(p.name()).isEqualTo(Outcome.STORED_FALLBACK);
            Map<String, Object> row = storedRow(date);
            assertThat(row.get("source")).as(p.name()).isEqualTo("fallback");
            assertThat(row.get("reason")).as(p.name()).isEqualTo(p.stored());
            assertThat(row.get("call_id")).as(p.name()).isNotNull();
            BriefView v = view(ON, date).orElseThrow();
            assertThat(v.source()).as(p.name()).isEqualTo("fallback");
            assertThat(v.reason()).as(p.name()).isEqualTo(p.api());
            assertThat(v.label()).as(p.name()).isEqualTo("Automatic summary");
            assertThat(v.advisory()).isTrue();
            assertThat(v.points().get(0).text()).startsWith("Key figures");
            succeed();
        }
    }

    // ---- State refusals do not lock the day -----------------------------------------------------------------

    @Test
    void hASwitchFlippedBetweenTheFactsAndTheCallStoresNothingAndALaterRunMakesTheBrief() {
        LocalDate date = nextDay();
        recording.thenAnswer(valid(date));
        // The facts are read, then (in the same run) the tenant switches the assistant off before the gateway is
        // reached.
        doAnswer(inv -> {
                    Object real = inv.callRealMethod();
                    setAiSwitch(ON, false);
                    return real;
                })
                .when(portfolioFacts)
                .build(any());
        assertThat(generate(date)).isEqualTo(Outcome.NOT_STORED);
        assertThat(briefRows(ON, date))
                .as("nothing was paid for, so the day is not locked")
                .isZero();
        assertThat(recording.requests()).isEmpty();

        // Switched on again (an off and on bounce): the next run makes the day's brief.
        setAiSwitch(ON, true);
        org.mockito.Mockito.reset(portfolioFacts);
        assertThat(generate(date)).isEqualTo(Outcome.STORED_MODEL);
        assertThat(briefRows(ON, date)).isEqualTo(1);
    }

    @Test
    void iNoFactsForTheQueuedDateStoresNothingAndNeverReadsTheNextDaysLiveTables() {
        // The queued date is the 15th; the run is served at 00:01 on the 16th, when tonight's snapshot is not written.
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        clock.set(MovableClockConfig.AFTER_MIDNIGHT);
        assertThat(generate(TODAY)).isEqualTo(Outcome.NO_FACTS);
        assertThat(briefRows(ON, TODAY)).isZero();
        assertThat(briefRows(ON, TODAY.plusDays(1))).isZero();
        assertThat(recording.requests()).isEmpty();

        // The same date served before midnight reads the live tables of that very day.
        clock.set(MovableClockConfig.BEFORE_MIDNIGHT);
        recording.reset();
        recording.thenAnswer(valid(TODAY));
        assertThat(generate(TODAY)).isEqualTo(Outcome.STORED_MODEL);
        assertThat(storedRow(TODAY).get("facts_as_of").toString()).isEqualTo(TODAY.toString());
        deleteBrief(TODAY);
    }

    /** Removes a day's brief as the owner, so that a later test can make it again. */
    static void deleteBrief(LocalDate date) {
        TestDatabase.owner()
                .sql("DELETE FROM lending_ai_briefs WHERE tenant_id = ? AND brief_date = ?")
                .params(ON.tenantId(), date)
                .update();
    }

    @Test
    void jAJobQueuedBeforeMidnightAndServedAfterItUsesTheDateItWasQueuedFor() {
        // A job queued at 23:50 on the 14th, served at 00:01 on the 16th (the clock is two days on): the facts are the
        // 14th's snapshot, stored under the 14th, never the 16th's live tables.
        LocalDate queued = TODAY.minusDays(1);
        recording.thenAnswer(valid(queued));
        clock.set(MovableClockConfig.AFTER_MIDNIGHT);
        var dueAt = queued.atTime(LocalTime.of(23, 50))
                .atZone(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE)
                .toInstant();
        AdvisorJobs.runFor(dueAt, () -> List.of(ON.tenantId()), tenantJobs, daily);

        Map<String, Object> row = storedRow(queued);
        assertThat(row.get("facts_as_of").toString()).isEqualTo(queued.toString());
        long inSnapshot = TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_loan_daily_snapshots WHERE tenant_id = ? AND business_date = ?")
                .params(ON.tenantId(), queued)
                .query(Long.class)
                .single();
        assertThat(row.get("points").toString()).contains("Active loans (" + FactDisplay.count(inSnapshot) + ")");
        assertThat(briefRows(ON, TODAY))
                .as("nothing is stored for the day the job ran")
                .isZero();
        assertThat(briefRows(ON, TODAY.plusDays(1))).isZero();
    }

    @Test
    void kTheTwentyFiftyNineAndTwentyOneOhOneUtcEdgeThroughTheRealTaskAndItsExecutionTime() {
        // The real bean, driven with db-scheduler's own Execution: due at 20:50 UTC (23:50 in Kampala) on the 15th it
        // makes
        // the 15th's brief; due at 21:01 UTC (00:01 on the 16th) it does nothing, being before 06:00.
        deleteBrief(TODAY);
        recording.thenAnswer(valid(TODAY));
        runTask(TODAY.atTime(20, 59).atZone(ZoneOffset.UTC).toInstant());
        assertThat(briefRows(ON, TODAY)).isEqualTo(1);
        assertThat(recording.requests()).hasSize(1);

        recording.reset();
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        runTask(TODAY.atTime(21, 1).atZone(ZoneOffset.UTC).toInstant());
        assertThat(briefRows(ON, TODAY.plusDays(1))).isZero();
        assertThat(recording.requests()).isEmpty();

        // Before 06:00 in Kampala (02:59 UTC) nothing is made either, the same date or not.
        runTask(TODAY.plusDays(1).atTime(2, 59).atZone(ZoneOffset.UTC).toInstant());
        assertThat(briefRows(ON, TODAY.plusDays(1))).isZero();
        assertThat(recording.requests()).isEmpty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void runTask(java.time.Instant dueAt) {
        TaskInstance instance = new TaskInstance(advisorDailyBrief.getName(), "1");
        ExecutionContext context = org.mockito.Mockito.mock(ExecutionContext.class);
        org.mockito.Mockito.when(context.getExecution()).thenReturn(new Execution(dueAt, instance));
        advisorDailyBrief.execute(instance, context);
    }

    @Test
    void lAPoisonTenantDoesNotBlockTheRestAndTheRunFailsAfterThem() {
        LocalDate date = TODAY.minusDays(dayOffset++);
        recording.thenAnswer(valid(date));
        UUID poison = UUID.randomUUID();
        TenantJobs jobs = spy(tenantJobs);
        doThrow(new IllegalStateException("poison tenant")).when(jobs).callAs(eq(poison), any());
        var dueAt = date.atTime(LocalTime.of(12, 0))
                .atZone(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE)
                .toInstant();
        clock.set(MovableClockConfig.AFTER_MIDNIGHT);

        assertThatThrownBy(() -> AdvisorJobs.runFor(dueAt, () -> List.of(poison, ON.tenantId()), jobs, daily))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 tenant");
        assertThat(briefRows(ON, date))
                .as("the tenant after the poisoned one was served")
                .isEqualTo(1);
    }

    @Test
    void mTheRealListerFindsOnlyActiveLendingTenantsAndTheRealJobSkipsEveryOffTenant() {
        List<UUID> listed = AdvisorJobs.lendingTenants(appJdbc);
        assertThat(listed).contains(ON.tenantId(), OFF.tenantId(), UNLISTED.tenantId());
        // Every tenant on the shared test database is visited and the off ones cost no facts, no call and no row.
        org.mockito.Mockito.clearInvocations(portfolioFacts);
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        var dueAt = TODAY.minusDays(dayOffset++)
                .atTime(12, 0)
                .atZone(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE)
                .toInstant();
        AdvisorJobs.runFor(dueAt, () -> AdvisorJobs.lendingTenants(appJdbc), tenantJobs, daily);
        assertThat(calls(OFF)).isZero();
        assertThat(calls(UNLISTED)).isZero();
    }

    @Test
    void nTwoInstancesAtOnceStoreOneRowAndNeitherFails() throws Exception {
        LocalDate date = nextDay();
        recording.thenAnswer(valid(date)).thenAnswer(valid(date));
        CountDownLatch both = new CountDownLatch(2);
        recording.onEachCall(() -> {
            both.countDown();
            try {
                both.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> generate(date));
            Future<Outcome> b = pool.submit(() -> generate(date));
            assertThat(List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS)))
                    .allMatch(o -> o == Outcome.STORED_MODEL || o == Outcome.STORED_FALLBACK);
        } finally {
            pool.shutdownNow();
        }
        assertThat(briefRows(ON, date))
                .as("one per tenant and day, whoever got there first")
                .isEqualTo(1);
    }

    // ---- The call log holds no text ------------------------------------------------------------------------

    @Test
    void oTheCallLogHoldsNoPromptNoResponseNoFactValueAndNoNameForAcceptedRefusedAndFailedCalls() {
        LocalDate accepted = nextDay();
        AliasedFacts facts = AliasedFacts.of(facts(accepted));
        recording.thenAnswer(new LlmClient.Result(validAnswer(facts), 400, 80, "stop"));
        generate(accepted);
        recording.thenAnswer(new LlmClient.Result(PROSE, 400, 80, "stop"));
        generate(nextDay());
        recording.thenFail(new LlmException(LlmException.Kind.TIMEOUT, 0, 1));
        generate(nextDay());
        succeed();

        List<Map<String, Object>> rows = TestDatabase.owner()
                .sql("SELECT * FROM ai_calls WHERE tenant_id = ?")
                .param(ON.tenantId())
                .query()
                .listOfRows();
        assertThat(rows).extracting(r -> r.get("outcome")).contains("ok", "failed");
        String logged = rows.stream().map(Object::toString).collect(Collectors.joining("\n"));
        CANARIES.forEach(c -> assertThat(logged).doesNotContainIgnoringCase(c));
        assertThat(logged)
                .doesNotContain("Zq-unlikely")
                .doesNotContain("ADVISOR-PROMPT")
                .doesNotContain("FACTS")
                .doesNotContain("UGX")
                .doesNotContain("Branch 1")
                .doesNotContain("CONCENTRATION");
        for (PromptFact f : facts.facts()) {
            if (f.display() != null && f.display().length() > 6) {
                assertThat(logged).doesNotContain(f.display());
            }
        }
        // The stored brief table has no column that could hold the model's words.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT column_name FROM information_schema.columns WHERE table_name = 'lending_ai_briefs'")
                        .query(String.class)
                        .list())
                .doesNotContain("prompt", "response", "answer", "raw");
    }

    // ---- Who may read the stored brief (service level) ------------------------------------------------------

    @Test
    void pTheAllBranchesBriefGoesOnlyToWhoseScopeCoversEveryActiveBranchAndHoldsAllOfficers() {
        LocalDate date = nextDay();
        recording.thenAnswer(valid(date));
        assertThat(generate(date)).isEqualTo(Outcome.STORED_MODEL);

        BriefView all = view(ON, date).orElseThrow();
        assertThat(all.source()).isEqualTo("model");
        assertThat(all.label()).isEqualTo("AI assistant (advisory)");
        assertThat(all.advisory()).isTrue();
        assertThat(all.note()).contains("Check before acting").contains("does not change any record");
        assertThat(all.asOf()).isNotNull();
        assertThat(all.facts()).isNotEmpty();

        // A branch manager (all officers, but only their own branch): never the all-branches brief.
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of("lending.insights.read", "lending.insights.all_officers", "lending.advisor.use"),
                false,
                Set.of(ON.headOffice())));
        BriefView manager = view(ON, date).orElseThrow();
        assertThat(manager.source()).isEqualTo("fallback");
        assertThat(manager.reason()).isEqualTo("scope");
        assertThat(manager.label()).isEqualTo("Automatic summary");
        assertThat(manager.points()).isNotEqualTo(all.points());

        // Every branch but without all officers (the loan officer's own loans): not the all-branches brief either.
        CurrentPrincipal.set(Principal.uniform(
                staff, "staff", Set.of("lending.insights.read", "lending.advisor.use"), true, Set.of()));
        BriefView officer = view(ON, date).orElseThrow();
        assertThat(officer.source()).isEqualTo("fallback");
        assertThat(officer.reason()).isEqualTo("scope");

        // A scope that lists every ACTIVE branch by id covers them all; an inactive branch need not be listed.
        TestDatabase.owner()
                .sql("UPDATE branches SET status = 'inactive' WHERE id = ?")
                .param(ON.secondBranch())
                .update();
        Set<UUID> active = Set.copyOf(TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ? AND status = 'active'")
                .param(ON.tenantId())
                .query(UUID.class)
                .list());
        assertThat(active).doesNotContain(ON.secondBranch());
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of("lending.insights.read", "lending.insights.all_officers", "lending.advisor.use"),
                false,
                active));
        assertThat(view(ON, date).orElseThrow().source()).isEqualTo("model");
        TestDatabase.owner()
                .sql("UPDATE branches SET status = 'active' WHERE id = ?")
                .param(ON.secondBranch())
                .update();

        // No brief made yet for a date: the automatic summary says it is not ready, and the page loads.
        admin();
        BriefView none = view(ON, LocalDate.of(2020, 1, 2)).orElseThrow();
        assertThat(none.source()).isEqualTo("fallback");
        assertThat(none.reason()).isEqualTo("not_ready");
    }

    // ---- The routes over HTTP ----------------------------------------------------------------------------------

    Map<String, String> dev(String permissions, String branchIds) {
        return Map.of(
                "X-Dev-User-Id", staff.toString(), "X-Dev-Permissions", permissions, "X-Dev-Branch-Ids", branchIds);
    }

    @Test
    void qTheBriefRouteOverHttpServesTheStoredBriefToAnAllBranchesUserAndTheSummaryToABranchManager() {
        deleteBrief(TODAY);
        recording.thenAnswer(valid(TODAY));
        assertThat(generate(TODAY)).isEqualTo(Outcome.STORED_MODEL);
        Api api = Api.tenant(http, ON.slug());
        String perms = "lending.advisor.use,lending.insights.read,lending.insights.all_officers";

        ResponseEntity<JsonNode> all =
                api.call(HttpMethod.GET, "/api/v1/lending/advisor/brief", null, null, dev(perms, "*"));
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getHeaders().getCacheControl()).contains("no-store");
        assertThat(all.getBody().get("source").asString()).isEqualTo("model");
        assertThat(all.getBody().get("label").asString()).isEqualTo("AI assistant (advisory)");
        assertThat(all.getBody().get("advisory").asBoolean()).isTrue();

        ResponseEntity<JsonNode> manager = api.call(
                HttpMethod.GET,
                "/api/v1/lending/advisor/brief",
                null,
                null,
                dev(perms, ON.headOffice().toString()));
        assertThat(manager.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(manager.getBody().get("source").asString()).isEqualTo("fallback");
        assertThat(manager.getBody().get("reason").asString()).isEqualTo("scope");

        // Without the permission: 403. For a tenant with the assistant off: 204, and no card.
        assertThat(api.call(
                                HttpMethod.GET,
                                "/api/v1/lending/advisor/brief",
                                null,
                                null,
                                dev("lending.insights.read", "*"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> off = Api.tenant(http, OFF.slug())
                .call(HttpMethod.GET, "/api/v1/lending/advisor/brief", null, null, dev(perms, "*"));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<JsonNode> unlisted = Api.tenant(http, UNLISTED.slug())
                .call(HttpMethod.GET, "/api/v1/lending/advisor/brief", null, null, dev(perms, "*"));
        assertThat(unlisted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void rTheSettingsRoutesOverHttpNeedIfMatchAndTheRightPermissions() {
        Api api = Api.tenant(http, OFF.slug());
        String configure = "lending.advisor.configure";
        ResponseEntity<JsonNode> read =
                api.call(HttpMethod.GET, "/api/v1/lending/advisor/settings", null, null, dev(configure, "*"));
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        String etag = read.getHeaders().getETag();
        assertThat(etag).isNotNull();
        assertThat(read.getBody().get("enabled").asBoolean()).isFalse();
        assertThat(read.getBody().get("allowed").asBoolean()).isTrue();

        // Without lending.advisor.configure: 403 on the route.
        assertThat(api.call(
                                HttpMethod.GET,
                                "/api/v1/lending/advisor/settings",
                                null,
                                null,
                                dev("lending.advisor.use", "*"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // With configure but without core.settings.manage the caps may change and the switch may not.
        Map<String, Object> switchOn =
                Map.of("enabled", true, "acknowledged_text_version", AdvisorSettingsService.ACK_TEXT_VERSION);
        ResponseEntity<JsonNode> denied = api.call(
                HttpMethod.PUT,
                "/api/v1/lending/advisor/settings",
                switchOn,
                null,
                withIfMatch(dev(configure, "*"), etag));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asString()).isEqualTo("permission_denied");

        // No If-Match: 428. A stale one: 409. The version read: 200 with the next version as the ETag.
        Map<String, Object> caps = Map.of("daily_token_cap", 4000);
        assertThat(api.call(HttpMethod.PUT, "/api/v1/lending/advisor/settings", caps, null, dev(configure, "*"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/api/v1/lending/advisor/settings",
                                caps,
                                null,
                                withIfMatch(dev(configure, "*"), "\"999\""))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> saved = api.call(
                HttpMethod.PUT, "/api/v1/lending/advisor/settings", caps, null, withIfMatch(dev(configure, "*"), etag));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("daily_token_cap").asLong()).isEqualTo(4000L);
        assertThat(saved.getHeaders().getETag()).isNotEqualTo(etag);
    }

    static Map<String, String> withIfMatch(Map<String, String> headers, String ifMatch) {
        Map<String, String> all = new java.util.HashMap<>(headers);
        all.put("If-Match", ifMatch);
        return all;
    }

    // ---- Last: the breaker stays open for a minute -----------------------------------------------------------

    @Test
    void zzLastAnOpenBreakerStoresNothingAndALaterRunMakesTheBrief() {
        // Five failures in a row open the breaker; the next brief never reaches the client, and because nothing was
        // paid for the
        // day is not locked. (The class has a context, and so a breaker, of its own.)
        for (int i = 0; i < 5; i++) {
            recording.thenFail(new LlmException(LlmException.Kind.PROVIDER_ERROR, 503, 3));
            generate(nextDay());
        }
        int reached = recording.requests().size();
        LocalDate date = nextDay();
        assertThat(generate(date)).isEqualTo(Outcome.NOT_STORED);
        assertThat(briefRows(ON, date)).isZero();
        assertThat(recording.requests()).hasSize(reached);
    }
}
