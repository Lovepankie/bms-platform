package com.rincoltech.bms.core.help.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.ai.LlmException.Kind;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.core.help.internal.HelpArticles.Article;
import com.rincoltech.bms.testsupport.Api;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Ask BMS on a real database (issue #20, ADR-096): off until the platform names the business; a cited answer for an
 * administrator and "another role's job" for a cashier; grounding only on articles the asker may read; the cache key
 * (repeat, permission set, permission matrix version, weak matches not kept); the scrub; the provider chain Gemini, Groq,
 * DeepSeek with fakes, a bad citation, the model finding no answer, all failing, none configured and the DeepSeek spend
 * cap; and the daily question cap. The providers are recording stubs, so nothing here can reach a network. Fabricated
 * data only.
 */
@Import(AskBmsIT.Fakes.class)
// A context of its own (the fakes and the tenant list): keep its pool small, as every cached context holds one open.
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=3",
            "bms.help.ask.daily-questions=6",
            "bms.help.ask.user-hourly-questions=9"
        })
class AskBmsIT extends IntegrationTest {

    static final RecordingLlmClient GEMINI = new RecordingLlmClient("gemini-test-model");
    static final RecordingLlmClient GROQ = new RecordingLlmClient("groq-test-model");
    static final RecordingLlmClient DEEPSEEK = new RecordingLlmClient("deepseek-test-model");
    static volatile boolean noProviders;

    /** Businesses Ask BMS is on for, handed to the application as BMS_HELP_ASK_TENANTS would be. */
    static final List<TestDatabase.Fixture> ON = new ArrayList<>();

    static final AtomicInteger NEXT = new AtomicInteger();

    static {
        for (int i = 0; i < 22; i++) {
            ON.add(TestDatabase.tenant("ask-on", true, true));
        }
    }

    @DynamicPropertySource
    static void tenants(DynamicPropertyRegistry registry) {
        registry.add(
                "bms.help.ask.enabled-tenants",
                () -> String.join(
                        ",", ON.stream().map(t -> t.tenantId().toString()).toList()));
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        AskProviders testProviders() {
            AskProperties.Provider free = new AskProperties.Provider(null, null, null, null, null, null);
            // DeepSeek is paid: one micro-unit a token, so its spend shows in the log.
            AskProperties.Provider paid = new AskProperties.Provider(null, null, null, 1_000_000L, 1_000_000L, null);
            return new AskProviders(List.of(
                    new AskProviders.Provider("gemini", GEMINI, free),
                    new AskProviders.Provider("groq", GROQ, free),
                    new AskProviders.Provider("deepseek", DEEPSEEK, paid))) {
                @Override
                List<Provider> chain() {
                    return noProviders ? List.of() : super.chain();
                }
            };
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    HelpArticles corpus;

    TestDatabase.Fixture t;
    Api api;
    String admin;
    String cashier;

    @BeforeEach
    void setUp() {
        GEMINI.reset();
        GROQ.reset();
        DEEPSEEK.reset();
        noProviders = false;
        t = ON.get(NEXT.getAndIncrement());
        api = Api.tenant(Api.withoutRetries(http), t.slug());
        admin = signIn(t, api, "ask-admin", new Api.Role("tenant_admin", null));
        cashier = signIn(t, api, "ask-cashier", new Api.Role("cashier", t.headOffice()));
    }

    static String signIn(TestDatabase.Fixture t, Api api, String who, Api.Role... roles) {
        String email = Api.email(who);
        UUID id = Api.staff(t, email, roles);
        return api.signIn(id, email, Api.PASSWORD, null).accessToken();
    }

    ResponseEntity<JsonNode> ask(String token, String question) {
        return api.post("/api/v1/help/ask", Map.of("question", question, "locale", "en-UG"), token);
    }

    JsonNode okAsk(String token, String question) {
        ResponseEntity<JsonNode> r = ask(token, question);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static LlmClient.Result answer(String text, String article) {
        return new LlmClient.Result(
                "{\"answer\": \"" + text + "\", \"article\": \"" + article + "\"}", 120, 40, "stop");
    }

    static final String INVITE = "Open Staff, tap Invite someone, then send the link on WhatsApp.";

    /** The newest spend log row of the business. */
    Map<String, Object> lastLog() {
        return TestDatabase.owner().sql("""
                        SELECT outcome, fallback_reason, provider, model, array_to_string(failures, ',') AS failures,
                               counted, cited_article, input_tokens, output_tokens, cost_micro, locale, question_hash
                          FROM help_ask_questions WHERE tenant_id = ? ORDER BY asked_at DESC, id DESC LIMIT 1
                        """).param(t.tenantId()).query().singleRow();
    }

    static String userPrompt(RecordingLlmClient client, int i) {
        return client.requests().get(i).messages().get(1).content();
    }

    @Test
    void offUntilThePlatformNamesTheBusiness() {
        TestDatabase.Fixture off = TestDatabase.tenant("ask-off", true);
        Api offApi = Api.tenant(Api.withoutRetries(http), off.slug());
        String offAdmin = signIn(off, offApi, "ask-off-admin", new Api.Role("tenant_admin", null));
        ResponseEntity<JsonNode> status = offApi.get("/api/v1/help/ask", offAdmin);
        assertThat(status.getBody().get("enabled").asBoolean()).isFalse();
        assertThat(status.getBody().get("revision").asString()).isEqualTo(corpus.revision());
        ResponseEntity<JsonNode> refused =
                offApi.post("/api/v1/help/ask", Map.of("question", "How do I invite a cashier?"), offAdmin);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("ask_bms_off");
        assertThat(api.get("/api/v1/help/ask", admin).getBody().get("enabled").asBoolean())
                .isTrue();
        assertThat(GEMINI.requests()).isEmpty();
    }

    @Test
    void anAdministratorGetsACitedAnswerAndACashierIsToldItIsAnotherRolesJob() {
        GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
        JsonNode a = okAsk(admin, "How do I invite a cashier?");
        assertThat(a.get("source").asString()).isEqualTo("model");
        assertThat(a.get("answer").asString()).isEqualTo(INVITE);
        assertThat(a.get("cited").get("id").asString()).isEqualTo("invite-someone");
        assertThat(a.get("cited").get("title").asString()).isEqualTo("How do I invite someone to use BMS?");
        assertThat(a.get("revision").asString()).isEqualTo(corpus.revision());
        // Temperature 0 is the adapter's; the request asks for JSON and carries the article.
        assertThat(GEMINI.requests()).hasSize(1);
        assertThat(GEMINI.requests().getFirst().json()).isTrue();
        assertThat(userPrompt(GEMINI, 0)).contains("[article invite-someone]").contains("Question: How do I invite");
        Map<String, Object> row = lastLog();
        assertThat(row.get("outcome")).isEqualTo("model");
        assertThat(row.get("provider")).isEqualTo("gemini");
        assertThat(row.get("model")).isEqualTo("gemini-test-model");
        assertThat(row.get("counted")).isEqualTo(true);
        assertThat(row.get("cited_article")).isEqualTo("invite-someone");
        // en-UG is answered from the English articles.
        assertThat(row.get("locale")).isEqualTo("en");
        assertThat((String) row.get("question_hash")).matches("^[0-9a-f]{64}$");

        JsonNode c = okAsk(cashier, "How do I invite a cashier?");
        assertThat(c.get("source").asString()).isEqualTo("other_role");
        assertThat(c.get("answer").asString())
                .contains("How do I invite someone to use BMS?")
                .contains("another role")
                .contains("administrator");
        assertThat(c.get("cited").isNull()).isTrue();
        assertThat(lastLog().get("outcome")).isEqualTo("other_role");
        // Nothing was sent for the cashier.
        assertThat(GEMINI.requests()).hasSize(1);
        assertThat(GROQ.requests()).isEmpty();
    }

    @Test
    void theModelReadsOnlyArticlesTheAskerMayReadAndMustCiteOneOfThem() {
        String seller = signIn(t, api, "ask-seller", new Api.Role("retail_sales", t.headOffice()));
        // Gemini cites an article it was not given (an administrator's): refused, and Groq answers.
        GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
        GROQ.thenAnswer(answer("Open Sell, find the item and tap Record sale.", "record-a-sale"));
        JsonNode a = okAsk(seller, "How do I record a sale and invite a cashier?");
        assertThat(a.get("source").asString()).isEqualTo("model");
        assertThat(a.get("cited").get("id").asString()).isEqualTo("record-a-sale");
        Map<String, Object> row = lastLog();
        assertThat(row.get("provider")).isEqualTo("groq");
        assertThat(row.get("failures")).isEqualTo("gemini:bad_answer");

        Set<String> held = new HashSet<>(TestDatabase.owner()
                .sql("SELECT permission_key FROM role_permissions WHERE role_key = 'retail_sales'")
                .query(String.class)
                .list());
        Set<String> modules = new HashSet<>();
        for (String m : List.of("retail", "lending")) {
            if (held.stream().anyMatch(p -> p.startsWith(m + "."))) {
                modules.add(m);
            }
        }
        Matcher sent = Pattern.compile("\\[article ([a-z0-9-]+)\\]").matcher(userPrompt(GEMINI, 0));
        List<String> ids = new ArrayList<>();
        while (sent.find()) {
            ids.add(sent.group(1));
        }
        assertThat(ids).isNotEmpty().contains("record-a-sale").doesNotContain("invite-someone");
        for (String id : ids) {
            Article article = corpus.all().stream()
                    .filter(x -> x.id().equals(id))
                    .findFirst()
                    .orElseThrow();
            assertThat(article.visibleTo(held, modules)).as(id).isTrue();
        }
    }

    @Test
    void aRepeatedQuestionIsServedFromTheCacheAndAnyChangeOfWhatProducedItIsANewKey() {
        GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
        assertThat(okAsk(admin, "How do I invite a cashier?").get("source").asString())
                .isEqualTo("model");
        JsonNode again = okAsk(admin, "  how do i invite a CASHIER ");
        assertThat(again.get("source").asString()).isEqualTo("cache");
        assertThat(again.get("answer").asString()).isEqualTo(INVITE);
        assertThat(again.get("cited").get("id").asString()).isEqualTo("invite-someone");
        assertThat(GEMINI.requests()).hasSize(1);
        Map<String, Object> row = lastLog();
        assertThat(row.get("outcome")).isEqualTo("cache");
        assertThat(row.get("counted")).isEqualTo(false);

        // Another permission set (an administrator who is also a cashier): a new key, so the provider is asked.
        String both = signIn(t, api, "ask-both", new Api.Role("tenant_admin", null), new Api.Role("cashier", null));
        GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
        assertThat(okAsk(both, "How do I invite a cashier?").get("source").asString())
                .isEqualTo("model");
        assertThat(GEMINI.requests()).hasSize(2);

        // A change to what any role may do is a new permission matrix version, so a new key at once, with no flush.
        boolean added = TestDatabase.owner().sql("""
                                INSERT INTO role_permissions (role_key, permission_key) VALUES ('auditor', 'core.users.manage')
                                ON CONFLICT DO NOTHING
                                """).update() == 1;
        try {
            assertThat(added).isTrue();
            GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
            assertThat(okAsk(admin, "How do I invite a cashier?").get("source").asString())
                    .isEqualTo("model");
            assertThat(GEMINI.requests()).hasSize(3);
        } finally {
            if (added) {
                TestDatabase.owner()
                        .sql(
                                "DELETE FROM role_permissions WHERE role_key = 'auditor' AND permission_key = 'core.users.manage'")
                        .update();
            }
        }

        // A weak match (one word of three) is answered but never kept.
        GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
        GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
        assertThat(okAsk(admin, "invite giraffes zebras").get("source").asString())
                .isEqualTo("model");
        assertThat(okAsk(admin, "invite giraffes zebras").get("source").asString())
                .isEqualTo("model");
        assertThat(GEMINI.requests()).hasSize(5);
    }

    @Test
    void phonesIdsAndNamesNeverLeaveTheServerAndNoTextIsStored() {
        GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
        okAsk(admin, "How do I invite Zorvex Testperson on 0772 123 456, ID CM90010000000X, test.person@example.test?");
        String sent = String.join(
                "\n",
                GEMINI.requests().getFirst().messages().stream()
                        .map(LlmClient.Message::content)
                        .toList());
        assertThat(sent)
                .contains("[name]", "[phone]", "[id]", "[email]")
                .doesNotContain("Zorvex", "Testperson", "0772", "123 456", "CM900", "example.test");
        String stored = TestDatabase.owner()
                        .sql("""
                        SELECT coalesce(string_agg(row_to_json(q)::text, ' '), '') FROM help_ask_questions q
                         WHERE tenant_id = ?
                        """)
                        .param(t.tenantId())
                        .query(String.class)
                        .single()
                + TestDatabase.owner()
                        .sql("""
                                SELECT coalesce(string_agg(row_to_json(a)::text, ' '), '') FROM help_ask_answers a
                                 WHERE tenant_id = ?
                                """)
                        .param(t.tenantId())
                        .query(String.class)
                        .single();
        assertThat(stored).doesNotContain("Zorvex", "0772", "CM900", "example.test", "How do I invite");
    }

    @Test
    void theChainFallsFromGeminiToGroqToDeepSeekThenToTheArticles() {
        GEMINI.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 3));
        GROQ.thenAnswer(answer(INVITE, "invite-someone"));
        assertThat(okAsk(admin, "How do I invite a cashier?").get("source").asString())
                .isEqualTo("model");
        Map<String, Object> row = lastLog();
        assertThat(row.get("provider")).isEqualTo("groq");
        assertThat(row.get("failures")).isEqualTo("gemini:rate_limited");
        assertThat(row.get("cost_micro")).isEqualTo(0L);

        GEMINI.thenFail(new LlmException(Kind.TIMEOUT, 0, 1));
        GROQ.thenFail(new LlmException(Kind.AUTH, 401, 1));
        DEEPSEEK.thenAnswer(answer(INVITE, "invite-someone"));
        assertThat(okAsk(admin, "How do I invite a seller?").get("source").asString())
                .isEqualTo("model");
        row = lastLog();
        assertThat(row.get("provider")).isEqualTo("deepseek");
        assertThat(row.get("failures")).isEqualTo("gemini:timeout,groq:auth_error");
        // 120 + 40 tokens at one micro-unit each: the spend log holds it.
        assertThat(row.get("input_tokens")).isEqualTo(120);
        assertThat(row.get("output_tokens")).isEqualTo(40);
        assertThat(row.get("cost_micro")).isEqualTo(160L);

        GEMINI.thenFail(new LlmException(Kind.PROVIDER_ERROR, 503, 3));
        GROQ.thenFail(new LlmException(Kind.NETWORK, 0, 3));
        // DeepSeek answers, is paid, and the answer is refused: its cost still counts against DeepSeek's month.
        DEEPSEEK.thenAnswer(new LlmClient.Result("Sure, here is how.", 120, 40, "stop"));
        JsonNode fallback = okAsk(admin, "How do I invite a storekeeper?");
        assertThat(fallback.get("source").asString()).isEqualTo("articles");
        assertThat(fallback.get("answer").isNull()).isTrue();
        assertThat(fallback.get("articles").get(0).get("id").asString()).isEqualTo("invite-someone");
        row = lastLog();
        assertThat(row.get("outcome")).isEqualTo("articles");
        assertThat(row.get("fallback_reason")).isEqualTo("all_failed");
        assertThat(row.get("failures")).isEqualTo("gemini:provider_error,groq:network,deepseek:bad_answer");
        assertThat(row.get("counted")).isEqualTo(true);
        // Every call left a spend row, settled with what it billed: two DeepSeek answers of 160 each, the rest nothing.
        Map<String, Object> spend =
                TestDatabase.owner().sql("""
                        SELECT count(*) FILTER (WHERE settled_at IS NULL) AS open,
                               coalesce(sum(cost_micro) FILTER (WHERE provider = 'deepseek'), 0)::bigint AS deepseek,
                               coalesce(sum(cost_micro) FILTER (WHERE provider <> 'deepseek'), 0)::bigint AS free
                          FROM help_ask_spend WHERE tenant_id = ?
                        """).param(t.tenantId()).query().singleRow();
        assertThat(spend.get("deepseek")).isEqualTo(320L);
        assertThat(spend.get("free")).isEqualTo(0L);
        // What may have been billed keeps its reservation (0 on a free tier): the rate limit after three attempts, the
        // timeout, the 503 and the broken connection. The key rejected at its only attempt billed nothing.
        assertThat(spend.get("open")).isEqualTo(4L);
    }

    @Test
    void withNoProviderConfiguredTheMatchingArticlesAreShown() {
        noProviders = true;
        JsonNode a = okAsk(admin, "How do I invite a cashier?");
        assertThat(a.get("source").asString()).isEqualTo("articles");
        assertThat(a.get("articles").get(0).get("id").asString()).isEqualTo("invite-someone");
        Map<String, Object> row = lastLog();
        assertThat(row.get("fallback_reason")).isEqualTo("no_provider");
        assertThat(row.get("counted")).isEqualTo(false);
        assertThat(GEMINI.requests()).isEmpty();
    }

    @Test
    void theModelFindingNoAnswerInTheArticlesShowsThemAndNoMatchAsksNobody() {
        GEMINI.thenAnswer(new LlmClient.Result("{\"answer\": \"\", \"article\": \"\"}", 100, 5, "stop"));
        JsonNode a = okAsk(admin, "How do I invite a cashier?");
        assertThat(a.get("source").asString()).isEqualTo("articles");
        Map<String, Object> row = lastLog();
        assertThat(row.get("fallback_reason")).isEqualTo("not_in_articles");
        assertThat(row.get("provider")).isEqualTo("gemini");
        assertThat(GROQ.requests()).isEmpty();

        JsonNode none = okAsk(admin, "zzqx wvvy");
        assertThat(none.get("source").asString()).isEqualTo("no_match");
        assertThat(none.get("articles")).isEmpty();
        assertThat(lastLog().get("outcome")).isEqualTo("no_match");
        assertThat(GEMINI.requests()).hasSize(1);
    }

    @Test
    void theDailyCapStopsTheProvidersButNotTheAnswers() {
        for (String who :
                List.of("a cashier", "a seller", "a storekeeper", "a worker", "a staff member", "with a role")) {
            GEMINI.thenAnswer(answer(INVITE, "invite-someone"));
            assertThat(okAsk(admin, "How do I invite " + who + "?")
                            .get("source")
                            .asString())
                    .isEqualTo("model");
        }
        JsonNode capped = okAsk(admin, "How do I invite a new user?");
        assertThat(capped.get("source").asString()).isEqualTo("articles");
        assertThat(capped.get("articles").get(0).get("id").asString()).isEqualTo("invite-someone");
        Map<String, Object> row = lastLog();
        assertThat(row.get("fallback_reason")).isEqualTo("daily_cap");
        assertThat(row.get("counted")).isEqualTo(false);
        assertThat(GEMINI.requests()).hasSize(6);
        // A question answered before is still served from the cache.
        assertThat(okAsk(admin, "How do I invite a cashier?").get("source").asString())
                .isEqualTo("cache");
    }

    @Test
    void deepSeekIsSkippedOnceEveryBusinessesSpendThisMonthReachesTheCap() {
        // The month's spend is another business's: the cap is for every business together.
        TestDatabase.Fixture other = TestDatabase.tenant("ask-spend", false);
        UUID spent = UUID.randomUUID();
        TestDatabase.owner().sql("""
                        INSERT INTO help_ask_spend (id, tenant_id, provider, model, reserved_at, settled_at, input_tokens,
                                                    output_tokens, cost_micro, cost_currency)
                        VALUES (?, ?, 'deepseek', 'deepseek-test-model', now(), now(), 1000, 100, 50000000, 'USD')
                        """).params(spent, other.tenantId()).update();
        try {
            GEMINI.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 3));
            GROQ.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 3));
            assertThat(okAsk(admin, "How do I invite a cashier?").get("source").asString())
                    .isEqualTo("articles");
            assertThat(lastLog().get("failures")).isEqualTo("gemini:rate_limited,groq:rate_limited,deepseek:spend_cap");
            assertThat(DEEPSEEK.requests()).isEmpty();
        } finally {
            TestDatabase.owner()
                    .sql("DELETE FROM help_ask_spend WHERE id = ?")
                    .param(spent)
                    .update();
        }
    }

    /** Questions sent at once are counted one after the other: no more reach the providers than the day allows. */
    @Test
    void questionsSentAtOnceCannotPassTheDailyCapTogether() throws Exception {
        String second = signIn(t, api, "ask-admin-2", new Api.Role("tenant_admin", null));
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<ResponseEntity<JsonNode>>> sent = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String token = i % 2 == 0 ? admin : second;
                // The stub's default answer is not an answer, so nothing is cached and every question goes out.
                sent.add(pool.submit(() -> ask(token, "How do I invite a cashier?")));
            }
            for (Future<ResponseEntity<JsonNode>> f : sent) {
                ResponseEntity<JsonNode> r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
                assertThat(r.getBody().get("source").asString()).isEqualTo("articles");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(GEMINI.requests()).hasSize(6);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM help_ask_questions WHERE tenant_id = ? AND fallback_reason = 'daily_cap'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(6L);
    }

    /** Every question, whatever path it takes, counts against the person's hour. */
    @Test
    void onePersonIsLimitedToTheirHourlyQuestionsOnEveryPath() {
        String seller = signIn(t, api, "ask-limited", new Api.Role("retail_sales", t.headOffice()));
        for (int i = 0; i < 9; i++) {
            assertThat(okAsk(seller, "zzqx wvvy").get("source").asString()).isEqualTo("no_match");
        }
        ResponseEntity<JsonNode> limited = ask(seller, "zzqx wvvy");
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code").asString()).isEqualTo("ask_rate_limited");
        // Another person of the same business is not limited by it.
        assertThat(okAsk(admin, "zzqx wvvy").get("source").asString()).isEqualTo("no_match");
    }

    @Autowired
    com.rincoltech.bms.core.ai.LlmClientFactory factory;

    /**
     * The real factory bean Ask BMS builds its providers with returns the gateway's OpenAI-compatible client, calling the base
     * URL it was given and nothing else; nothing here sends a request.
     */
    @Test
    void theFactoryBuildsTheOpenAiCompatibleClientForTheGivenHostOnly() {
        Map<String, String> bases = Map.of(
                "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                "groq", "https://api.groq.com/openai/v1",
                "deepseek", "https://api.deepseek.com");
        bases.forEach((name, base) -> {
            assertThat(java.net.URI.create(base).getHost()).isEqualTo(AskProviders.ALLOWED.get(name));
            LlmClient client = factory.openAiCompatible(
                            base, "test-key-not-real", name + "-test-model", java.time.Duration.ofSeconds(5))
                    .orElseThrow();
            assertThat(client.getClass().getSimpleName()).isEqualTo("OpenAiCompatibleClient");
            assertThat(client.toString()).doesNotContain("test-key-not-real");
            Matcher endpoint = Pattern.compile("endpoint=([^,\\]]+)").matcher(client.toString());
            assertThat(endpoint.find()).isTrue();
            assertThat(endpoint.group(1)).isEqualTo(base + "/chat/completions");
            assertThat(java.net.URI.create(endpoint.group(1)).getHost()).isEqualTo(AskProviders.ALLOWED.get(name));
            assertThat(client.model()).isEqualTo(name + "-test-model");
        });
    }

    /** A paid answer that reports no usage is settled at its reservation: the monthly cap still moves. */
    @Test
    void aPaidAnswerWithNoUsageKeepsItsReservation() {
        GEMINI.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 3));
        GROQ.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 3));
        DEEPSEEK.thenAnswer(new LlmClient.Result("not an answer", -1, -1, "stop"));
        assertThat(okAsk(admin, "How do I invite a cashier?").get("source").asString())
                .isEqualTo("articles");
        Map<String, Object> spend = TestDatabase.owner()
                .sql("SELECT settled_at, cost_micro FROM help_ask_spend WHERE tenant_id = ? AND provider = 'deepseek'")
                .param(t.tenantId())
                .query()
                .singleRow();
        assertThat(spend.get("settled_at")).isNotNull();
        // The reservation: the estimated input and the 400 output tokens, one micro-unit each.
        assertThat((Long) spend.get("cost_micro")).isGreaterThan(400L);
        assertThat((Long) lastLog().get("cost_micro")).isEqualTo((Long) spend.get("cost_micro"));
    }

    /**
     * DeepSeek's monthly cap holds for questions sent at once: with room for one reservation left this month, five
     * questions that all fall through to DeepSeek call it once. Gemini and Groq refuse, DeepSeek answers with no usage
     * (settled at its reservation), so the room never comes back during the test.
     */
    @Test
    void questionsSentAtOnceCannotPassDeepSeeksMonthlyCapTogether() throws Exception {
        // One question first, to learn what a reservation for this prompt is.
        GEMINI.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 1));
        GROQ.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 1));
        DEEPSEEK.thenAnswer(new LlmClient.Result("not an answer", -1, -1, "stop"));
        okAsk(admin, "How do I invite a cashier?");
        long reservation = TestDatabase.owner()
                .sql("SELECT cost_micro FROM help_ask_spend WHERE tenant_id = ? AND provider = 'deepseek'")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(reservation).isPositive();
        long spent = TestDatabase.owner()
                .sql("SELECT help_ask_platform_spend('deepseek', 'USD',"
                        + " date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')")
                .query(Long.class)
                .single();
        // The month's other DeepSeek spend in this database is small, so the room below is exactly what the test sets.
        assertThat(spent).isLessThan(5_000_000L - 2 * reservation);
        // Another business has spent all but one and a half reservations of the default cap (5000000).
        TestDatabase.Fixture other = TestDatabase.tenant("ask-month", false);
        UUID seeded = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO help_ask_spend (id, tenant_id, provider, model, reserved_at, settled_at, cost_micro,
                                                    cost_currency)
                        VALUES (?, ?, 'deepseek', 'deepseek-test-model', now(), now(), ?, 'USD')
                        """)
                .params(seeded, other.tenantId(), 5_000_000L - spent - reservation - reservation / 2)
                .update();
        GEMINI.reset();
        GROQ.reset();
        DEEPSEEK.reset();
        for (int i = 0; i < 5; i++) {
            GEMINI.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 1));
            GROQ.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 1));
            DEEPSEEK.thenAnswer(new LlmClient.Result("not an answer", -1, -1, "stop"));
        }
        String second = signIn(t, api, "ask-admin-month", new Api.Role("tenant_admin", null));
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<ResponseEntity<JsonNode>>> sent = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String token = i % 2 == 0 ? admin : second;
                sent.add(pool.submit(() -> ask(token, "How do I invite a cashier?")));
            }
            for (Future<ResponseEntity<JsonNode>> f : sent) {
                ResponseEntity<JsonNode> r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
            }
            assertThat(DEEPSEEK.requests()).hasSize(1);
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*) FROM help_ask_questions WHERE tenant_id = ?"
                                    + " AND 'deepseek:spend_cap' = ANY (failures)")
                            .param(t.tenantId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(4L);
        } finally {
            pool.shutdownNow();
            TestDatabase.owner()
                    .sql("DELETE FROM help_ask_spend WHERE id = ?")
                    .param(seeded)
                    .update();
        }
    }

    /** A call that fails on our side may have been sent and billed: it keeps its reservation and the log counts it. */
    @Test
    void aCallThatFailsOnOurSideKeepsItsReservationInTheSpendAndTheLog() {
        GEMINI.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 1));
        GROQ.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 1));
        DEEPSEEK.onEachCall(() -> {
            throw new IllegalStateException("fabricated adapter bug");
        });
        assertThat(okAsk(admin, "How do I invite a cashier?").get("source").asString())
                .isEqualTo("articles");
        Map<String, Object> spend = TestDatabase.owner()
                .sql("SELECT settled_at, cost_micro FROM help_ask_spend WHERE tenant_id = ? AND provider = 'deepseek'")
                .param(t.tenantId())
                .query()
                .singleRow();
        assertThat(spend.get("settled_at")).isNull();
        assertThat((Long) spend.get("cost_micro")).isPositive();
        Map<String, Object> row = lastLog();
        assertThat(row.get("failures")).isEqualTo("gemini:rate_limited,groq:rate_limited,deepseek:internal_error");
        assertThat(row.get("cost_micro")).isEqualTo(spend.get("cost_micro"));
    }
}
