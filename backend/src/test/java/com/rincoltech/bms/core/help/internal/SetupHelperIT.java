package com.rincoltech.bms.core.help.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.ai.LlmException.Kind;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.testsupport.Api;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * The setup helper on a real database (issue #626, owner decision D62, ADR-102): off, and nothing read or sent, for a
 * business the platform has not named; a typed list drafted into item import rows with the shop's own names as context
 * and nothing saved; the chain Gemini, Groq, DeepSeek falling through a failure and a bad answer; every provider failing
 * and none set answered with a reason, never an error; a photo going only to the providers that read pictures; the
 * business's daily cap and the person's hourly limit; the spend of each call in Ask BMS's log; and a loan register drafted
 * with its members matched by phone on this side, the member list never sent. The providers are recording stubs, so
 * nothing here can reach a network. Fabricated data only.
 */
@Import(SetupHelperIT.Fakes.class)
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=3",
            "bms.help.setup.daily-drafts=3",
            "bms.help.setup.user-hourly-drafts=5"
        })
class SetupHelperIT extends IntegrationTest {

    static final RecordingLlmClient GEMINI = new RecordingLlmClient("gemini-test-model");
    static final RecordingLlmClient GROQ = new RecordingLlmClient("groq-test-model");
    static final RecordingLlmClient DEEPSEEK = new RecordingLlmClient("deepseek-test-model");
    static final RecordingLlmClient GEMINI_PHOTO = new RecordingLlmClient("gemini-photo-test-model");
    static volatile boolean noProviders;

    static final List<TestDatabase.Fixture> ON = new ArrayList<>();
    static final AtomicInteger NEXT = new AtomicInteger();

    static {
        for (int i = 0; i < 12; i++) {
            ON.add(TestDatabase.tenant("setup-on", true, true));
        }
    }

    @DynamicPropertySource
    static void tenants(DynamicPropertyRegistry registry) {
        registry.add(
                "bms.help.setup.enabled-tenants",
                () -> String.join(
                        ",", ON.stream().map(t -> t.tenantId().toString()).toList()));
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        SetupProviders testSetupProviders() {
            AskProperties.Provider free = new AskProperties.Provider(null, null, null, null, null, null);
            AskProperties.Provider paid = new AskProperties.Provider(null, null, null, 1_000_000L, 1_000_000L, null);
            AskProviders text =
                    new AskProviders(List.of(
                            new AskProviders.Provider("gemini", GEMINI, free),
                            new AskProviders.Provider("groq", GROQ, free),
                            new AskProviders.Provider("deepseek", DEEPSEEK, paid))) {
                        @Override
                        List<Provider> chain() {
                            return noProviders ? List.of() : super.chain();
                        }
                    };
            AskProviders photos = new AskProviders(List.of(new AskProviders.Provider("gemini", GEMINI_PHOTO, free)));
            return new SetupProviders(text, photos);
        }
    }

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    String admin;

    @BeforeEach
    void setUp() {
        for (RecordingLlmClient c : List.of(GEMINI, GROQ, DEEPSEEK, GEMINI_PHOTO)) {
            c.reset();
        }
        noProviders = false;
        t = ON.get(NEXT.getAndIncrement());
        api = Api.tenant(Api.withoutRetries(http), t.slug());
        admin = AskBmsIT.signIn(t, api, "setup-admin", new Api.Role("tenant_admin", null));
    }

    static final String ITEMS = "/api/v1/retail/products/import/draft";
    static final String LOANS = "/api/v1/lending/existing-loans/import/draft";

    ResponseEntity<JsonNode> send(Api as, String path, String token, String text, byte[] file) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        if (text != null) {
            form.add("text", text);
        }
        if (file != null) {
            form.add("file", new ByteArrayResource(file) {
                @Override
                public String getFilename() {
                    return "list.png";
                }
            });
        }
        if (path.equals(ITEMS)) {
            form.add("shop_type", "Test Hardware");
        }
        return as.call(HttpMethod.POST, path, form, token, Map.of("Content-Type", MediaType.MULTIPART_FORM_DATA_VALUE));
    }

    JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static LlmClient.Result answer(String json) {
        return new LlmClient.Result(json, 900, 300, "stop");
    }

    static final String TWO_ITEMS = """
            {"items": [
              {"name": "Test Nails 3 inch", "category": "test fasteners", "unit": "kg", "sell_price": "7,500", "buy_price": 6000,
               "pack_unit": null, "pack_size": null},
              {"name": "Test Padlock", "category": "Test Locks", "unit": "Piece", "sell_price": 25000, "buy_price": null,
               "pack_unit": "Box", "pack_size": 12}
            ]}""";

    Map<String, Object> lastDraft() {
        return TestDatabase.owner().sql("""
                        SELECT kind, source, outcome, provider, array_to_string(failures, ',') AS failures, rows_drafted,
                               input_tokens, output_tokens, cost_micro
                          FROM help_setup_drafts WHERE tenant_id = ? ORDER BY asked_at DESC, id DESC LIMIT 1
                        """).param(t.tenantId()).query().singleRow();
    }

    long count(String sql) {
        return TestDatabase.owner()
                .sql(sql)
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    String category(String name) {
        ResponseEntity<JsonNode> r = api.post("/api/v1/retail/categories", Map.of("name", name), admin);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    @Test
    void offUntilThePlatformNamesTheBusinessAndNothingIsReadOrSent() {
        TestDatabase.Fixture off = TestDatabase.tenant("setup-off", true, true);
        Api offApi = Api.tenant(Api.withoutRetries(http), off.slug());
        String offAdmin = AskBmsIT.signIn(off, offApi, "setup-off-admin", new Api.Role("tenant_admin", null));
        JsonNode status = ok(offApi.get("/api/v1/help/setup", offAdmin));
        assertThat(status.get("enabled").asBoolean()).isFalse();
        assertThat(status.get("photos").asBoolean()).isFalse();
        for (String path : List.of(ITEMS, LOANS)) {
            ResponseEntity<JsonNode> refused = send(offApi, path, offAdmin, "Test Sugar 5500", null);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("setup_ai_off");
        }
        assertThat(GEMINI.requests()).isEmpty();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM help_setup_drafts WHERE tenant_id = ?")
                        .param(off.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        JsonNode on = ok(api.get("/api/v1/help/setup", admin));
        assertThat(on.get("enabled").asBoolean()).isTrue();
        assertThat(on.get("photos").asBoolean()).isTrue();
    }

    @Test
    void aTypedListIsDraftedIntoImportRowsWithTheShopsNamesAndNothingIsSaved() {
        category("Test Fasteners");
        GEMINI.thenAnswer(answer(TWO_ITEMS));
        JsonNode draft = ok(send(api, ITEMS, admin, "nails 3 inch 7500 a kg\npadlock 25000, box of 12", null));
        assertThat(draft.get("reason").isNull()).isTrue();
        assertThat(draft.get("currency_exponent").asInt()).isZero();
        JsonNode rows = draft.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("description").asString()).isEqualTo("Test Nails 3 inch");
        assertThat(rows.get(0).get("category").asString())
                .as("the shop's own spelling")
                .isEqualTo("Test Fasteners");
        assertThat(rows.get(0).get("sell_price_minor").asLong()).isEqualTo(7500);
        assertThat(rows.get(0).get("cost_price_minor").asLong()).isEqualTo(6000);
        assertThat(rows.get(1).get("pack_unit").isNull())
                .as("no pack while the shop's pack capability is off: the import would refuse it")
                .isTrue();

        LlmClient.Request sent = GEMINI.requests().getFirst();
        assertThat(sent.json()).isTrue();
        assertThat(sent.messages().get(1).content())
                .contains("Kind of shop: Test Hardware.")
                .contains("Categories: Test Fasteners.")
                .contains("nails 3 inch 7500 a kg");
        assertThat(GROQ.requests()).isEmpty();
        // The AI only drafts: no item, category or unit is added until the owner checks the rows and taps Add.
        assertThat(count("SELECT count(*) FROM retail_products WHERE tenant_id = ?"))
                .isZero();
        assertThat(count("SELECT count(*) FROM retail_categories WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(lastDraft())
                .containsEntry("kind", "retail")
                .containsEntry("source", "text")
                .containsEntry("outcome", "drafted")
                .containsEntry("provider", "gemini")
                .containsEntry("rows_drafted", 2)
                .containsEntry("input_tokens", 900);
        assertThat(count("SELECT count(*) FROM help_ask_spend WHERE tenant_id = ? AND provider = 'gemini'"
                        + " AND settled_at IS NOT NULL"))
                .isEqualTo(1);

        // The rows, written as the screen writes them (setup-helper-state.ts itemsCsv), pass the real import's check.
        StringBuilder csv = new StringBuilder("description,category,unit,cost_price,sell_price\n");
        for (JsonNode r : rows) {
            csv.append(String.join(
                            ",",
                            r.get("description").asString(),
                            r.get("category").asString(),
                            r.get("unit").asString(),
                            r.get("cost_price_minor").isNull()
                                    ? ""
                                    : r.get("cost_price_minor").asString(),
                            r.get("sell_price_minor").asString()))
                    .append('\n');
        }
        JsonNode checked =
                ok(api.post("/api/v1/retail/products/import?dry_run=true", Map.of("csv", csv.toString()), admin));
        assertThat(checked.get("errors").asInt()).as("%s", checked).isZero();
        assertThat(checked.get("added").asInt()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM retail_products WHERE tenant_id = ?"))
                .as("the check saves nothing either")
                .isZero();
    }

    @Test
    void theChainFallsThroughAFailureAndABadAnswer() {
        GEMINI.thenFail(new LlmException(Kind.RATE_LIMITED, 429, 1));
        GROQ.thenAnswer(answer("Here are the items: nails and a padlock."));
        DEEPSEEK.thenAnswer(answer(TWO_ITEMS));
        JsonNode draft = ok(send(api, ITEMS, admin, "nails, padlock", null));
        assertThat(draft.get("rows")).hasSize(2);
        assertThat(lastDraft())
                .containsEntry("outcome", "drafted")
                .containsEntry("provider", "deepseek")
                .containsEntry("failures", "gemini:rate_limited,groq:bad_answer");
        assertThat((long) lastDraft().get("cost_micro")).as("DeepSeek is paid").isPositive();
    }

    @Test
    void everyProviderFailingOrNoneSetIsAReasonNeverAnError() {
        for (RecordingLlmClient c : List.of(GEMINI, GROQ, DEEPSEEK)) {
            c.thenFail(new LlmException(Kind.PROVIDER_ERROR, 503, 3));
        }
        JsonNode failed = ok(send(api, ITEMS, admin, "nails, padlock", null));
        assertThat(failed.get("reason").asString()).isEqualTo("all_failed");
        assertThat(failed.get("rows")).isEmpty();
        assertThat(lastDraft()).containsEntry("outcome", "all_failed");

        noProviders = true;
        JsonNode none = ok(send(api, LOANS, admin, "Test Borrower Alpha 500000", null));
        assertThat(none.get("reason").asString()).isEqualTo("no_provider");
        assertThat(lastDraft()).containsEntry("kind", "lending").containsEntry("outcome", "no_provider");
    }

    static byte[] photo() throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", png);
        return png.toByteArray();
    }

    @Test
    void aPhotoGoesOnlyToTheProvidersThatReadPictures() throws IOException {
        GEMINI_PHOTO.thenAnswer(answer(TWO_ITEMS));
        JsonNode draft = ok(send(api, ITEMS, admin, null, photo()));
        assertThat(draft.get("rows")).hasSize(2);
        LlmClient.Message user = GEMINI_PHOTO.requests().getFirst().messages().get(1);
        assertThat(user.imageDataUrl()).startsWith("data:image/jpeg;base64,");
        assertThat(user.content()).contains("The owner's list is in this picture.");
        assertThat(GEMINI.requests()).isEmpty();
        assertThat(DEEPSEEK.requests()).isEmpty();
        assertThat(lastDraft()).containsEntry("source", "photo").containsEntry("provider", "gemini");

        GEMINI_PHOTO.thenFail(new LlmException(Kind.TIMEOUT, 0, 1));
        JsonNode failed = ok(send(api, ITEMS, admin, null, photo()));
        assertThat(failed.get("reason").asString()).isEqualTo("all_failed");
        assertThat(DEEPSEEK.requests()).as("DeepSeek never reads a photo").isEmpty();
    }

    @Test
    void theDailyCapAndThePersonsHourlyLimit() {
        for (int i = 0; i < 3; i++) {
            GEMINI.thenAnswer(answer(TWO_ITEMS));
            assertThat(ok(send(api, ITEMS, admin, "nails", null)).get("reason").isNull())
                    .isTrue();
        }
        JsonNode capped = ok(send(api, ITEMS, admin, "nails", null));
        assertThat(capped.get("reason").asString()).isEqualTo("daily_cap");
        assertThat(GEMINI.requests()).hasSize(3);
        assertThat(lastDraft()).containsEntry("outcome", "daily_cap");
        assertThat(ok(send(api, ITEMS, admin, "nails", null)).get("reason").asString())
                .isEqualTo("daily_cap");
        ResponseEntity<JsonNode> limited = send(api, ITEMS, admin, "nails", null);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code").asString()).isEqualTo("setup_ai_rate_limited");
    }

    @Test
    void onlyThoseWhoMayImportMayDraftAndAListIsNeeded() {
        String cashier = AskBmsIT.signIn(t, api, "setup-cashier", new Api.Role("cashier", t.headOffice()));
        assertThat(send(api, ITEMS, cashier, "nails", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(api, LOANS, cashier, "a loan", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> empty = send(api, ITEMS, admin, null, null);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(empty.getBody().get("code").asString()).isEqualTo("setup_list_missing");
        ResponseEntity<JsonNode> wrongFile =
                send(api, ITEMS, admin, null, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
        assertThat(wrongFile.getBody().get("code").asString()).isEqualTo("setup_file_not_read");
        assertThat(lastDraft())
                .as("a refused file never leaves its row started, so it holds no place in the day's cap")
                .containsEntry("outcome", "unreadable");
        assertThat(GEMINI.requests()).isEmpty();
    }

    @Test
    void aLoanRegisterIsDraftedWithItsMembersMatchedByPhoneHereNeverByTheModel() {
        Map<String, Object> member = new java.util.LinkedHashMap<>();
        member.put("branch_id", t.headOffice());
        member.put("full_name", "Test Member Known");
        member.put("phone", "0700000201");
        member.put("id_type", "none");
        ResponseEntity<JsonNode> created = api.post("/api/v1/lending/members", member, admin);
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String memberNo = created.getBody().get("member_no").asString();
        GEMINI.thenAnswer(answer("""
                {"loans": [
                  {"ref": "Y-01", "member_name": "T. Known", "member_phone": "0700 000 201", "principal": 400000,
                   "date": "2026-08-01", "term_months": 2, "rate_percent_per_month": 10, "total_to_repay": null,
                   "payments": [{"date": "2026-09-01", "amount": 240000}]},
                  {"ref": "Y-02", "member_name": "Test Member New", "member_phone": "0700000202", "principal": 200000,
                   "date": "2026-08-15", "term_months": 1, "rate_percent_per_month": null, "total_to_repay": 230000,
                   "payments": []}
                ]}"""));
        JsonNode draft = ok(send(api, LOANS, admin, "Y-01 T. Known 0700 000 201 400,000 1/8/26", null));
        JsonNode loans = draft.get("loans");
        assertThat(loans).hasSize(2);
        assertThat(loans.get(0).get("member_no").asString()).isEqualTo(memberNo);
        assertThat(loans.get(0).get("member_full_name").asString()).isEqualTo("Test Member Known");
        assertThat(loans.get(0).get("agreed_total_minor").asLong()).isEqualTo(480_000);
        assertThat(loans.get(0).get("total_worked_out").asBoolean()).isTrue();
        assertThat(loans.get(0).get("payments").get(0).get("amount_minor").asLong())
                .isEqualTo(240_000);
        assertThat(loans.get(1).get("member_no").isNull()).isTrue();
        assertThat(loans.get(1).get("member_phone").asString()).isEqualTo("+256700000202");
        assertThat(GEMINI.requests().getFirst().messages().get(1).content())
                .as("the member list is never sent")
                .doesNotContain("Test Member Known")
                .doesNotContain(memberNo);
        assertThat(count("SELECT count(*) FROM lending_loans WHERE tenant_id = ?"))
                .isZero();
        assertThat(count("SELECT count(*) FROM lending_members WHERE tenant_id = ?"))
                .isEqualTo(1);
    }

    /**
     * A member is named only to someone who may bring loans into that member's branch: a branch manager of the head office
     * drafting a phone held in the second branch gets a new row, which the import refuses in its own words.
     */
    @Test
    void aDraftNeverNamesAMemberOfABranchTheCallerMayNotUse() {
        Map<String, Object> member = new java.util.LinkedHashMap<>();
        member.put("branch_id", t.secondBranch());
        member.put("full_name", "Test Member Elsewhere");
        member.put("phone", "0700000203");
        member.put("id_type", "none");
        ResponseEntity<JsonNode> created = api.post("/api/v1/lending/members", member, admin);
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String manager = AskBmsIT.signIn(t, api, "setup-manager", new Api.Role("branch_manager", t.headOffice()));
        GEMINI.thenAnswer(answer("""
                {"loans": [{"ref": "Y-09", "member_name": "Someone", "member_phone": "0700000203", "principal": 100000,
                  "date": "2026-08-01", "term_months": 1, "payments": []}]}"""));
        JsonNode loan = ok(send(api, LOANS, manager, "Y-09 Someone 0700000203 100000", null))
                .get("loans")
                .get(0);
        assertThat(loan.get("member_no").isNull()).isTrue();
        assertThat(loan.get("member_full_name").isNull()).isTrue();

        // What the import's check then says of this row is ExistingLoansIT's to prove, on ready rows:
        // frExl06_aPhoneHeldInAnotherBranchReadsAsNewAndTheCommitRefusesWithoutNamingAnyone.
        assertThat(loan.toString())
                .doesNotContain(created.getBody().get("member_no").asString())
                .doesNotContain("Test Member Elsewhere");
    }

    /**
     * A file just under the 5 MB the screen allows passes the server's upload limit (spring.servlet.multipart, 5 MB a file)
     * and is answered in the helper's own plain words, here that the list is longer than it reads at once.
     */
    @Test
    void aFileJustUnderFiveMegabytesReachesTheHelper() {
        byte[] big = ("Test Item,1000\n".repeat(340_000)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(big.length).isBetween(4_900_000, 5 * 1024 * 1024);
        ResponseEntity<JsonNode> r = send(api, ITEMS, admin, null, big);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("setup_list_too_long");
        assertThat(GEMINI.requests()).isEmpty();
    }
}
