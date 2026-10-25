package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.payments.FakePaymentGateway;
import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The gateway core on a real database, with the in-process fake provider, a handler registered for a test purpose and a
 * clock the test moves (FR-PAY-01 to FR-PAY-03, FR-PAY-08, ADR-035, issue #213). It proves, over HTTP and the database:
 * the intent through the order to {@code pending}; a notification that is replayed, forged, for an unknown reference or for
 * another tenant's reference books nothing it should not; the status query is the only thing that moves an intent;
 * duplicates and concurrent polls make one booking; a handler refusal and a technical failure end where the spec says;
 * the lost order answer; expiry and a late payment; the database's own guard on transitions; the tenant binding of the
 * merchant account; the daily ceiling across the Kampala midnight; and tenant isolation. No test sleeps: the clock is a
 * bean and the pause between order attempts is replaced. Every name, number and reference is fabricated.
 *
 * <p>The clock starts at {@link #BEFORE_MIDNIGHT}, far ahead of the wall clock, never behind it (issue #362). The poller
 * claims the due checks of every tenant, and a check is due and an intent expired by whatever clock the claimer reads.
 * On the shared 15 October 2026 of {@link MovableClockConfig}, every intent here was already expired by the wall clock,
 * so a poller of another cached context could end one {@code expired} between this test's create and its assertion.
 * IntegrationTest switches the scheduled poller off (#516); ahead of the wall clock, nothing of this class is due or
 * expired for any claimer on the wall clock even if one ran.
 */
@Import({PaymentGatewayIT.Config.class, MovableClockConfig.class})
@ResourceLock(MovableClockConfig.LOCK)
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=4",
            "db-scheduler.enabled=false",
            "bms.payments.poller-enabled=false",
            "bms.payments.callback-ip-per-minute=100000",
            "bms.payments.callback-global-per-minute=1000000",
            "bms.pesapal.provider=fake",
            "bms.pesapal.callback-base-url=https://callbacks.example.test",
            "bms.pesapal.platform.consumer-key=invented-platform-key",
            "bms.pesapal.platform.consumer-secret=invented-platform-secret",
            "bms.pesapal.platform.ipn-id=aaaaaaaa-0000-4000-8000-000000000002",
            "bms.pesapal.platform.ipn-token=" + PaymentGatewayIT.PLATFORM_TOKEN,
            "bms.pesapal.refused-key-digests=fd7b571e25333d58599ee989f5fe9b901bead180a0d1b8d839c2bb59d078db49"
        })
class PaymentGatewayIT extends IntegrationTest {

    /** 20:59 UTC, 23:59 on 2099-10-15 in Kampala: the shape of {@link MovableClockConfig#BEFORE_MIDNIGHT}, ahead of the wall clock. */
    static final Instant BEFORE_MIDNIGHT = Instant.parse("2099-10-15T20:59:00Z");

    /** 21:01 UTC, 00:01 on 2099-10-16 in Kampala. */
    static final Instant AFTER_MIDNIGHT = Instant.parse("2099-10-15T21:01:00Z");

    static final String PLATFORM_TOKEN = "invented-platform-path-token-for-the-it-0001";
    static final String PERMS =
            "core.payments.read,core.payments.collect,core.settings.read,core.settings.manage,core.payment_account.manage";
    static final TestDatabase.Fixture BOUND = TestDatabase.tenant("pay-bound", true);
    static final TestDatabase.Fixture OTHER = TestDatabase.tenant("pay-other", true);
    static final String PURPOSE = "test.payment";

    /** The path token of each connected tenant's notification url, read from the registration the fake saw. */
    static final Map<UUID, String> TOKENS = new ConcurrentHashMap<>();

    /** The path token of the notification url of the BOUND tenant's own account. */
    static String token() {
        return TOKENS.get(BOUND.tenantId());
    }

    /** The handler for the test purpose: books by recording, and can be told to refuse or break. */
    static final class TestHandler implements PurposeHandler {
        final Map<UUID, UUID> branches = new ConcurrentHashMap<>();
        final List<Booking> bookings = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile String refuse;
        volatile String refuseFirstOnly;
        volatile boolean explode;

        @Override
        public String purpose() {
            return PURPOSE;
        }

        @Override
        public Subject describe(UUID subjectId, long amountMinor, String currency) {
            UUID branch = branches.get(subjectId);
            if (branch == null) {
                throw new PurposeRefusal("subject_not_payable");
            }
            return new Subject("test_subject", branch);
        }

        @Override
        public UUID book(Booking booking) {
            calls.incrementAndGet();
            if (explode) {
                throw new IllegalStateException("database broke while booking");
            }
            if (refuse != null) {
                throw new PurposeRefusal(refuse);
            }
            if (refuseFirstOnly != null && bookings.isEmpty() && calls.get() == 1) {
                throw new PurposeRefusal(refuseFirstOnly);
            }
            bookings.add(booking);
            return UUID.randomUUID();
        }

        void reset() {
            bookings.clear();
            calls.set(0);
            refuse = null;
            refuseFirstOnly = null;
            explode = false;
        }
    }

    /** The step-up of the real identity module needs a signed-in user with an authenticator; the test accepts 000000. */
    static final class StubSecurity implements com.rincoltech.bms.core.identity.AdminSecurity {
        final List<String> rejected = new CopyOnWriteArrayList<>();
        volatile List<Holder> holders = List.of();

        @Override
        public void requireTotp(String code) {
            if (!"000000".equals(code)) {
                rejected.add(String.valueOf(code));
                throw new com.rincoltech.bms.kernel.ApiException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                        "invalid_code",
                        "Invalid code",
                        "That code is not valid.");
            }
        }

        @Override
        public List<Holder> holders(String permission) {
            return holders;
        }

        @Override
        public String actorName() {
            return "Test Administrator";
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        TestHandler testHandler() {
            return new TestHandler();
        }

        @Bean
        @Primary
        StubSecurity stubSecurity() {
            return new StubSecurity();
        }

        @Bean
        @Primary
        Pauser noPause() {
            return pause -> {};
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    TestHandler handler;

    @Autowired
    PaymentPoller poller;

    @Autowired
    MerchantAccountService merchantAccounts;

    @Autowired
    MovableClock clock;

    FakePaymentGateway fake;
    UUID staff;
    UUID staffOther;
    UUID subject;

    @BeforeEach
    void setUp() {
        fake = (FakePaymentGateway) gateway;
        fake.reset();
        merchantAccounts.forgetTests();
        handler.reset();
        clock.set(BEFORE_MIDNIGHT);
        // Nothing left due from an earlier test: only this test's intents are checked.
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = NULL")
                .update();
        staff = user(BOUND);
        staffOther = user(OTHER);
        subject = UUID.randomUUID();
        handler.branches.put(subject, BOUND.headOffice());
        ensureOn();
    }

    // ---- helpers ------------------------------------------------------------------------------

    static UUID user(TestDatabase.Fixture t) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Test Staff 13', ?, 'active')")
                .params(id, t.tenantId(), "staff-" + id + "@example.test")
                .update();
        return id;
    }

    HttpHeaders headers(TestDatabase.Fixture t, UUID user, String permissions) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }

    ResponseEntity<JsonNode> call(
            HttpMethod method, String path, TestDatabase.Fixture t, UUID user, Object body, String key) {
        HttpHeaders h = headers(t, user, PERMS);
        if (key != null) {
            h.add("Idempotency-Key", key);
        }
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    /** Connects and tests the tenant's own account through the API, as its administrator would; returns the token. */
    String connect(TestDatabase.Fixture t, UUID user) {
        String suffix = t.slug().substring(t.slug().length() - 8);
        ResponseEntity<JsonNode> saved = call(
                HttpMethod.PUT,
                "/api/v1/payments/merchant-account",
                t,
                user,
                Map.of(
                        "consumer_key",
                        "invented-key-" + suffix,
                        "consumer_secret",
                        "invented-secret-" + suffix,
                        "totp_code",
                        "000000"),
                null);
        assertThat(saved.getStatusCode().value()).as("%s", saved.getBody()).isEqualTo(200);
        ResponseEntity<JsonNode> tested =
                call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", t, user, null, null);
        assertThat(tested.getStatusCode().value()).as("%s", tested.getBody()).isEqualTo(200);
        assertThat(tested.getBody().get("status").asString()).isEqualTo("verified");
        String url = fake.registeredUrl(PaymentGateway.Account.tenant(t.tenantId()));
        String token = url.substring(url.lastIndexOf('/') + 1);
        TOKENS.put(t.tenantId(), token);
        merchantAccounts.forgetTests();
        return token;
    }

    void ensureConnected(TestDatabase.Fixture t, UUID user) {
        JsonNode now = call(HttpMethod.GET, "/api/v1/payments/merchant-account", t, user, null, null)
                .getBody();
        if (!"verified".equals(now.get("status").asString()) || !TOKENS.containsKey(t.tenantId())) {
            connect(t, user);
        }
    }

    void ensureOn() {
        ensureConnected(BOUND, staff);
        boolean mapped = TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_methods WHERE tenant_id = ? AND method_key = 'gateway'")
                        .param(BOUND.tenantId())
                        .query(Long.class)
                        .single()
                > 0;
        if (!mapped) {
            TestDatabase.owner()
                    .sql(
                            "INSERT INTO payment_methods (id, tenant_id, method_key, gl_account_id) VALUES (?, ?, 'gateway', ?)")
                    .params(UUID.randomUUID(), BOUND.tenantId(), BOUND.account("gateway_clearing"))
                    .update();
        }
        ResponseEntity<JsonNode> now = call(HttpMethod.GET, "/api/v1/payments/settings", BOUND, staff, null, null);
        if (!now.getBody().get("collections_enabled").asBoolean()) {
            setSettings(BOUND, staff, Map.of("collections_enabled", true, "daily_ceiling_minor", 100000000L), 200);
        }
    }

    JsonNode setSettings(TestDatabase.Fixture t, UUID user, Map<String, Object> body, int expected) {
        ResponseEntity<JsonNode> current = call(HttpMethod.GET, "/api/v1/payments/settings", t, user, null, null);
        HttpHeaders h = headers(t, user, PERMS);
        h.add("If-Match", current.getHeaders().getETag());
        ResponseEntity<JsonNode> r =
                http.exchange("/api/v1/payments/settings", HttpMethod.PUT, new HttpEntity<>(body, h), JsonNode.class);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(expected);
        return r.getBody();
    }

    JsonNode create(long amount, String key) {
        ResponseEntity<JsonNode> r = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                BOUND,
                staff,
                Map.of("purpose", PURPOSE, "subject_id", subject, "amount_minor", amount, "payer_phone", "0700000123"),
                key);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        return r.getBody();
    }

    JsonNode create(long amount) {
        return create(amount, "key-" + UUID.randomUUID());
    }

    String statusOf(String id) {
        return TestDatabase.owner()
                .sql("SELECT status FROM payment_intents WHERE id = ?")
                .param(UUID.fromString(id))
                .query(String.class)
                .single();
    }

    Map<String, Object> row(String id) {
        return TestDatabase.owner()
                .sql("SELECT * FROM payment_intents WHERE id = ?")
                .param(UUID.fromString(id))
                .query()
                .singleRow();
    }

    String tracking(JsonNode intent) {
        return fake.orderFor(intent.get("merchant_reference").asString()).providerReference;
    }

    ResponseEntity<JsonNode> ipn(String token, String tracking, String reference) {
        return http.exchange(
                "/api/v1/payments/callbacks/pesapal/" + token + "?OrderTrackingId=" + tracking
                        + "&OrderMerchantReference=" + reference + "&OrderNotificationType=IPNCHANGE",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                JsonNode.class);
    }

    void tick() {
        poller.tick();
    }

    void pay(JsonNode intent, long paid, String currency, String code) {
        fake.complete(tracking(intent), paid, currency, code);
    }

    long audits(String id, String verb) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?")
                .params(UUID.fromString(id), "core.payments.intent." + verb)
                .query(Long.class)
                .single();
    }

    // ---- creating ------------------------------------------------------------------------------

    @Test
    void anIntentBecomesPendingWithARedirectUrlAndTheMerchantReferenceIsBoundToItsId() {
        JsonNode intent = create(1500);
        assertThat(intent.get("status").asString()).isEqualTo("pending");
        assertThat(intent.get("redirect_url").asString()).startsWith("https://");
        String id = intent.get("id").asString();
        assertThat(intent.get("merchant_reference").asString()).isEqualTo("BMS-DV-" + id.replace("-", ""));
        assertThat(intent.get("payer_phone").asString())
                .endsWith("0123")
                .contains("*")
                .doesNotContain("0700");
        assertThat(intent.get("currency").asString()).isEqualTo("UGX");
        assertThat(fake.creates()).isEqualTo(1);
        assertThat(audits(id, "order_created")).isEqualTo(1);
        // The tenant came from the row's host binding, and the expiry is 30 minutes.
        assertThat(row(id).get("expires_at").toString()).isNotNull();
        assertThat(Duration.between(BEFORE_MIDNIGHT, ((java.sql.Timestamp) row(id).get("expires_at")).toInstant()))
                .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void theSameIdempotencyKeyGivesTheSameIntentAndOneOrder() {
        JsonNode first = create(2000, "idem-key-0001");
        JsonNode again = create(2000, "idem-key-0001");
        assertThat(again.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(fake.creates()).isEqualTo(1);
        ResponseEntity<JsonNode> other = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                BOUND,
                staff,
                Map.of("purpose", PURPOSE, "subject_id", subject, "amount_minor", 2500, "payer_phone", "0700000123"),
                "idem-key-0001");
        assertThat(other.getStatusCode().value()).isEqualTo(422);
        assertThat(other.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void invalidRequestsAreRefusedBeforeAnythingIsWritten() {
        long before = count();
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of(
                        "purpose",
                        "no.such_purpose",
                        "subject_id",
                        subject,
                        "amount_minor",
                        1500,
                        "payer_phone",
                        "0700000123"),
                Map.<String, Object>of(
                        "purpose", PURPOSE, "subject_id", subject, "amount_minor", 0, "payer_phone", "0700000123"),
                Map.<String, Object>of(
                        "purpose", PURPOSE, "subject_id", subject, "amount_minor", -4, "payer_phone", "0700000123"),
                Map.<String, Object>of(
                        "purpose", PURPOSE, "subject_id", subject, "amount_minor", 1500, "payer_phone", "12345"),
                Map.<String, Object>of(
                        "purpose",
                        PURPOSE,
                        "subject_id",
                        UUID.randomUUID(),
                        "amount_minor",
                        1500,
                        "payer_phone",
                        "0700000123"))) {
            ResponseEntity<JsonNode> r =
                    call(HttpMethod.POST, "/api/v1/payments/intents", BOUND, staff, bad, "key-" + UUID.randomUUID());
            assertThat(r.getStatusCode().is4xxClientError()).as("%s", bad).isTrue();
        }
        ResponseEntity<JsonNode> noKey = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                BOUND,
                staff,
                Map.of("purpose", PURPOSE, "subject_id", subject, "amount_minor", 1500, "payer_phone", "0700000123"),
                null);
        assertThat(noKey.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");
        assertThat(count()).isEqualTo(before);
        assertThat(fake.creates()).isZero();
    }

    long count() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM payment_intents")
                .query(Long.class)
                .single();
    }

    // ---- notifications: never trusted ----------------------------------------------------------

    @Test
    void replayingOneNotificationThreeTimesBooksOnceAndAnswersTheDocumentedAcknowledgement() {
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0001");
        String ref = intent.get("merchant_reference").asString();
        for (int i = 0; i < 3; i++) {
            ResponseEntity<JsonNode> r = ipn(token(), tracking(intent), ref);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(r.getHeaders().getContentType().toString()).startsWith("application/json");
            assertThat(r.getBody().get("orderNotificationType").asString()).isEqualTo("IPNCHANGE");
            assertThat(r.getBody().get("orderTrackingId").asString()).isEqualTo(tracking(intent));
            assertThat(r.getBody().get("orderMerchantReference").asString()).isEqualTo(ref);
            assertThat(r.getBody().get("status").asInt()).isEqualTo(200);
            assertThat(r.getBody().size()).isEqualTo(4);
            clock.set(clock.instant().plusSeconds(11));
            tick();
        }
        assertThat(handler.bookings).hasSize(1);
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("booked");
        assertThat(handler.bookings.getFirst().amountMinor()).isEqualTo(1500);
        assertThat(handler.bookings.getFirst().confirmationCode()).isEqualTo("CONF-0001");
        assertThat(audits(intent.get("id").asString(), "booked")).isEqualTo(1);
        assertThat(audits(intent.get("id").asString(), "paid")).isEqualTo(1);
        // Three notifications are three rows, and every one was finished by a check.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_callbacks WHERE intent_id = ? AND outcome <> 'queued'")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void aForgedNotificationForAPendingIntentBooksNothingWhateverItClaims() {
        JsonNode intent = create(1500);
        String ref = intent.get("merchant_reference").asString();
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/payments/callbacks/pesapal/" + token() + "?OrderTrackingId=" + tracking(intent)
                        + "&OrderMerchantReference=" + ref
                        + "&OrderNotificationType=IPNCHANGE&status=COMPLETED&amount=1500&payment_status_description=Completed&status_code=1",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        clock.set(clock.instant().plusSeconds(30));
        tick();
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("pending");
        assertThat(handler.calls).hasValue(0);
        // The provider says failed: the intent fails and still nothing is booked.
        fake.fail(tracking(intent));
        ipn(token(), tracking(intent), ref);
        clock.set(clock.instant().plusSeconds(30));
        tick();
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("failed");
        assertThat(handler.calls).hasValue(0);
    }

    @Test
    void anUnknownReferenceIsAcknowledgedCountedAndMakesNoCallToTheProvider() {
        String unknown = "BMS-DV-" + UUID.randomUUID().toString().replace("-", "");
        int before = fake.queryCount();
        ResponseEntity<JsonNode> r = ipn(token(), "aaaaaaaa-0000-4000-8000-0000000000aa", unknown);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("status").asInt()).isEqualTo(200);
        ipn(token(), "aaaaaaaa-0000-4000-8000-0000000000aa", unknown);
        tick();
        assertThat(fake.queryCount()).isEqualTo(before);
        assertThat(TestDatabase.owner()
                        .sql("SELECT hits FROM payment_unmatched_callbacks WHERE merchant_reference = ?")
                        .param(unknown)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT coalesce(sum(hits), 0) FROM payment_callback_counters WHERE outcome = 'unknown_reference'")
                        .query(Long.class)
                        .single())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void aReferenceWithTheWrongTrackingIdIsUnknownAndNeverQueued() {
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0002");
        String ref = intent.get("merchant_reference").asString();
        ipn(token(), "aaaaaaaa-0000-4000-8000-0000000000ee", ref);
        clock.set(clock.instant().plusSeconds(30));
        tick();
        assertThat(statusOf(intent.get("id").asString()))
                .as("a forged id changes nothing; only the real order's own check would")
                .isEqualTo("pending");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM payment_unmatched_callbacks WHERE merchant_reference = ? AND reason = 'tracking_mismatch'")
                        .param(ref)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void theWrongTokenAMalformedBodyAndAnOversizedBodyAreRefusedWithoutTouchingAnything() {
        JsonNode intent = create(1500);
        String ref = intent.get("merchant_reference").asString();
        long callbacks = TestDatabase.owner()
                .sql("SELECT count(*) FROM payment_callbacks")
                .query(Long.class)
                .single();
        assertThat(ipn("wrong-token-wrong-token-wrong", tracking(intent), ref)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(ipn("x", tracking(intent), ref).getStatusCode().value()).isEqualTo(404);
        assertThat(http.exchange(
                                "/api/v1/payments/callbacks/pesapal/" + token(),
                                HttpMethod.GET,
                                HttpEntity.EMPTY,
                                JsonNode.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(ipn(token(), "not-a-guid", ref).getStatusCode().value()).isEqualTo(400);
        HttpHeaders json = new HttpHeaders();
        json.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        String big = "{\"OrderTrackingId\":\"" + tracking(intent) + "\",\"pad\":\"" + "x".repeat(6000) + "\"}";
        ResponseEntity<JsonNode> huge = http.exchange(
                "/api/v1/payments/callbacks/pesapal/" + token(),
                HttpMethod.POST,
                new HttpEntity<>(big, json),
                JsonNode.class);
        assertThat(huge.getStatusCode().value()).isEqualTo(413);
        // A POST with the JSON body is read whatever its content type says.
        String ok = "{\"OrderNotificationType\":\"IPNCHANGE\",\"OrderTrackingId\":\"" + tracking(intent)
                + "\",\"OrderMerchantReference\":\"" + ref + "\"}";
        ResponseEntity<JsonNode> posted = http.exchange(
                "/api/v1/payments/callbacks/pesapal/" + token(),
                HttpMethod.POST,
                new HttpEntity<>(ok, json),
                JsonNode.class);
        assertThat(posted.getStatusCode().value()).isEqualTo(200);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_callbacks")
                        .query(Long.class)
                        .single())
                .isEqualTo(callbacks + 1);
    }

    @Test
    void aNotificationNeedsNoSessionAndNoTenantHostAndSetsNoCookie() {
        JsonNode intent = create(1500);
        HttpHeaders none = new HttpHeaders();
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/payments/callbacks/pesapal/" + token() + "?OrderTrackingId=" + tracking(intent)
                        + "&OrderMerchantReference="
                        + intent.get("merchant_reference").asString(),
                HttpMethod.GET,
                new HttpEntity<>(none),
                JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().containsHeader("Set-Cookie")).isFalse();
    }

    // ---- the status query is the truth -----------------------------------------------------------

    @Test
    void aCompletedPaymentInAnotherCurrencyOrWithAnotherReferenceNeverBooks() {
        JsonNode usd = create(1500);
        pay(usd, 1500, "USD", "CONF-0003");
        ipn(token(), tracking(usd), usd.get("merchant_reference").asString());
        tick();
        // The adapter reads only whole shillings in UGX; the fake answers what a confused provider might.
        assertThat(statusOf(usd.get("id").asString())).isIn("needs_attention", "pending");
        assertThat(handler.calls).hasValue(0);

        JsonNode other = create(1500);
        pay(other, 1500, "UGX", "CONF-0004");
        fake.reportReference(tracking(other), "BMS-DV-" + "0".repeat(32));
        ipn(token(), tracking(other), other.get("merchant_reference").asString());
        tick();
        assertThat(statusOf(other.get("id").asString())).isEqualTo("needs_attention");
        assertThat(row(other.get("id").asString()).get("refusal_code")).isEqualTo("reference_mismatch");
        assertThat(handler.calls).hasValue(0);
    }

    @Test
    void aPartialAndAnOverPaymentAreBookedAtTheConfirmedAmountWithAFlag() {
        JsonNode less = create(2000);
        pay(less, 1500, "UGX", "CONF-0005");
        ipn(token(), tracking(less), less.get("merchant_reference").asString());
        tick();
        JsonNode more = create(2000);
        pay(more, 2600, "UGX", "CONF-0006");
        ipn(token(), tracking(more), more.get("merchant_reference").asString());
        tick();
        assertThat(handler.bookings)
                .extracting(PurposeHandler.Booking::amountMinor)
                .containsExactlyInAnyOrder(1500L, 2600L);
        ResponseEntity<JsonNode> view = call(
                HttpMethod.GET, "/api/v1/payments/intents/" + less.get("id").asString(), BOUND, staff, null, null);
        assertThat(view.getBody().get("flags").toString()).contains("paid_less");
        assertThat(call(
                                HttpMethod.GET,
                                "/api/v1/payments/intents/" + more.get("id").asString(),
                                BOUND,
                                staff,
                                null,
                                null)
                        .getBody()
                        .get("flags")
                        .toString())
                .contains("paid_more");
    }

    @Test
    void aProviderThatCannotBeReachedLeavesTheIntentPendingAndCountsTheFailure() {
        JsonNode intent = create(1500);
        fake.failQuery(new GatewayException(GatewayException.Kind.TRANSIENT, 0, "timeout"));
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("pending");
        assertThat(row(intent.get("id").asString()).get("check_failures")).isEqualTo(1);
        assertThat(row(intent.get("id").asString()).get("next_check_at")).isNotNull();
    }

    // ---- races -----------------------------------------------------------------------------------

    @Test
    void duplicateNotificationsAndConcurrentPollsMakeOneBookingAndTheOthersAreNoOps() throws Exception {
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0007");
        String ref = intent.get("merchant_reference").asString();
        String track = tracking(intent);
        // Slow the provider a little inside the call so the checks overlap for real.
        java.util.concurrent.CyclicBarrier meet = new java.util.concurrent.CyclicBarrier(2);
        fake.beforeQuery(() -> {
            try {
                meet.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException e) {
                // Nobody else arrived in time: carry on alone.
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> work = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            work.add(pool.submit(() -> {
                go.await();
                ipn(token(), track, ref);
                return null;
            }));
        }
        for (int i = 0; i < 4; i++) {
            work.add(pool.submit(() -> {
                go.await();
                clock.set(clock.instant().plusSeconds(0));
                tick();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : work) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // A last pass settles whatever the race left due.
        clock.set(clock.instant().plusSeconds(30));
        tick();
        tick();
        assertThat(handler.bookings).hasSize(1);
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("booked");
        assertThat(audits(intent.get("id").asString(), "booked")).isEqualTo(1);
        assertThat(audits(intent.get("id").asString(), "paid")).isEqualTo(1);
    }

    @Test
    void twoCreatorsMakeOneOrder() throws Exception {
        UUID id = UUID.randomUUID();
        JsonNode intent = createStuck();
        long created = fake.creates();
        String intentId = intent.get("id").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OrderCreator.Outcome>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return creatorFor(UUID.fromString(intentId));
            }));
        }
        go.countDown();
        List<OrderCreator.Outcome> outcomes = new ArrayList<>();
        for (Future<OrderCreator.Outcome> f : results) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertThat(outcomes).filteredOn(o -> o == OrderCreator.Outcome.CREATED).hasSize(1);
        assertThat(fake.creates() - created).isEqualTo(1);
        assertThat(statusOf(intentId)).isEqualTo("pending");
        assertThat(id).isNotNull();
    }

    @Autowired
    OrderCreator creator;

    @Autowired
    com.rincoltech.bms.core.jobs.TenantJobs tenantJobs;

    OrderCreator.Outcome creatorFor(UUID intentId) {
        return tenantJobs.callAs(BOUND.tenantId(), () -> creator.create(intentId, Duration.ofSeconds(5)));
    }

    /** An intent left {@code created} because the provider was down when it was asked for. */
    JsonNode createStuck() {
        fake.failCreate(new GatewayException(GatewayException.Kind.UNAVAILABLE, 0, "circuit_open"));
        JsonNode intent = create(1500);
        fake.failCreate(null);
        assertThat(intent.get("status").asString()).isEqualTo("created");
        return intent;
    }

    // ---- creation answers that are lost ------------------------------------------------------------

    @Test
    void aLostOrderAnswerIsRecoveredByRepeatingTheSameReferenceAndBody() {
        fake.loseNextCreateAnswers(1);
        JsonNode intent = create(1500);
        assertThat(intent.get("status").asString()).isEqualTo("pending");
        assertThat(fake.creates()).isEqualTo(2);
        assertThat(fake.calls().stream().filter(c -> c.startsWith("create:")).distinct())
                .hasSize(1);
    }

    @Test
    void whenEveryTryIsLostTheIntentFailsWithCreationUnknownAndNewIntentGetsANewReference() {
        fake.loseNextCreateAnswers(3);
        JsonNode first = create(1500);
        assertThat(first.get("status").asString()).isEqualTo("failed");
        assertThat(first.get("failure_reason").asString()).isEqualTo("creation_unknown");
        fake.reset();
        JsonNode second = create(1500);
        assertThat(second.get("status").asString()).isEqualTo("pending");
        assertThat(second.get("merchant_reference").asString())
                .isNotEqualTo(first.get("merchant_reference").asString());
    }

    @Test
    void aProviderThatIsDownLeavesTheIntentCreatedAndTheNextTickMakesTheOrder() {
        JsonNode stuck = createStuck();
        assertThat(statusOf(stuck.get("id").asString())).isEqualTo("created");
        assertThat(count()).isPositive();
        clock.set(clock.instant().plus(Duration.ofMinutes(2)));
        tick();
        assertThat(statusOf(stuck.get("id").asString())).isEqualTo("pending");
    }

    @Test
    void aRefusalFromTheProviderEndsTheIntentFailedAtOnce() {
        fake.failCreate(new GatewayException(GatewayException.Kind.REFUSED, 200, "InvalidIpnId"));
        JsonNode intent = create(1500);
        assertThat(intent.get("status").asString()).isEqualTo("failed");
        assertThat(intent.get("failure_reason").asString()).isEqualTo("order_refused:invalidipnid");
    }

    // ---- expiry and late payment ---------------------------------------------------------------------

    @Test
    void anIntentExpiresAfterThirtyMinutesButAPaymentThatCompletesLaterStillBooksAsLate() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        clock.set(clock.instant().plus(Duration.ofMinutes(31)));
        tick();
        assertThat(statusOf(id)).isEqualTo("expired");
        pay(intent, 1500, "UGX", "CONF-0008");
        clock.set(clock.instant().plus(Duration.ofMinutes(15)));
        tick();
        assertThat(statusOf(id)).isEqualTo("booked");
        assertThat(handler.bookings.getFirst().late()).isTrue();
        ResponseEntity<JsonNode> view =
                call(HttpMethod.GET, "/api/v1/payments/intents/" + id, BOUND, staff, null, null);
        assertThat(view.getBody().get("flags").toString()).contains("late");
    }

    @Test
    void anOrderStillUnpaidAfterFortyEightHoursIsCancelledAtTheProviderAndTheIntentEnds() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        for (int i = 0; i < 8; i++) {
            clock.set(clock.instant().plus(Duration.ofHours(7)));
            tick();
        }
        assertThat(statusOf(id)).isEqualTo("cancelled");
        assertThat(fake.calls()).anyMatch(c -> c.startsWith("cancel:"));
        assertThat(row(id).get("failure_reason")).isEqualTo("unpaid_after_48h");
        assertThat(row(id).get("next_check_at")).isNull();
    }

    // ---- a business whose subscription ended (ADR-073 decision 5) ----------------------------------------

    /** The Owner's cancellation took effect: cancelled with an end date, the tenant suspended (read only). */
    void endSubscription(TestDatabase.Fixture t, boolean byOwner) {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO subscriptions (id, tenant_id, plan_id, status, ended_on)"
                                + " SELECT gen_random_uuid(), id, plan_id, ?, ? FROM tenants WHERE id = ?"
                                + " ON CONFLICT (tenant_id) DO UPDATE SET status = EXCLUDED.status, ended_on = EXCLUDED.ended_on")
                .params(
                        byOwner ? "cancelled" : "suspended",
                        byOwner ? java.sql.Date.valueOf("2026-01-31") : null,
                        t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(t.tenantId())
                .update();
    }

    void resume(TestDatabase.Fixture t) {
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET status = 'active', ended_on = NULL WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'active' WHERE id = ?")
                .param(t.tenantId())
                .update();
    }

    @Test
    void aNotificationAfterTheSubscriptionEndedStillBooksThePayment() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        pay(intent, 1500, "UGX", "CONF-0E01");
        endSubscription(BOUND, true);
        try {
            ResponseEntity<JsonNode> r = ipn(
                    token(), tracking(intent), intent.get("merchant_reference").asString());
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*) FROM payment_callbacks WHERE intent_id = ?")
                            .param(UUID.fromString(id))
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            clock.set(clock.instant().plusSeconds(11));
            tick();
            assertThat(statusOf(id)).isEqualTo("booked");
            assertThat(handler.bookings).hasSize(1);
        } finally {
            resume(BOUND);
        }
    }

    @Test
    void aDuePollAfterTheSubscriptionEndedStillBooksThePayment() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        pay(intent, 1500, "UGX", "CONF-0E02");
        endSubscription(BOUND, true);
        try {
            clock.set(clock.instant().plus(Duration.ofMinutes(3)));
            tick();
            assertThat(statusOf(id)).isEqualTo("booked");
            assertThat(handler.bookings).hasSize(1);
        } finally {
            resume(BOUND);
        }
    }

    // JUSTIFICATION-A3: one new test method appended beside the ones whose fixtures it reuses; no production code.
    /**
     * Independent review of #474: the end made by the real definer (as the plan job does), not by hand. A mobile money
     * notification after it is recorded and booked, while a new payment request is refused; once the operator suspends the
     * ended business, the next notification is ignored.
     */
    @Test
    void aNotificationAfterTheRealEndIsRecordedUntilTheOperatorSuspends() {
        JsonNode first = create(1500);
        JsonNode second = create(1700);
        pay(first, 1500, "UGX", "CONF-0E04");
        pay(second, 1700, "UGX", "CONF-0E05");
        UUID tenant = BOUND.tenantId();
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('app.tenant_id', '" + tenant + "', true);"
                        + " PERFORM platform_end_subscription('" + tenant + "', DATE '2026-01-31'); END $$")
                .update();
        try {
            assertThat(TestDatabase.owner()
                            .sql(
                                    "SELECT s.status || '/' || t.status FROM subscriptions s JOIN tenants t ON t.id = s.tenant_id"
                                            + " WHERE t.id = ?")
                            .param(tenant)
                            .query(String.class)
                            .single())
                    .isEqualTo("cancelled/suspended");
            ResponseEntity<JsonNode> refused = call(
                    HttpMethod.POST,
                    "/api/v1/payments/intents",
                    BOUND,
                    staff,
                    Map.of("purpose", PURPOSE, "subject_id", subject, "amount_minor", 900, "payer_phone", "0700000123"),
                    "key-" + UUID.randomUUID());
            assertThat(refused.getStatusCode().value()).isEqualTo(423);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("subscription_ended");

            String firstId = first.get("id").asString();
            assertThat(ipn(
                                    token(),
                                    tracking(first),
                                    first.get("merchant_reference").asString())
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
            clock.set(clock.instant().plusSeconds(11));
            tick();
            assertThat(statusOf(firstId)).isEqualTo("booked");
            assertThat(handler.bookings).hasSize(1);

            TestDatabase.owner()
                    .sql("SELECT platform_set_subscription_status(?, 'suspended', NULL, NULL)")
                    .param(tenant)
                    .query()
                    .singleRow();
            ipn(token(), tracking(second), second.get("merchant_reference").asString());
            clock.set(clock.instant().plus(Duration.ofMinutes(3)));
            tick();
            assertThat(statusOf(second.get("id").asString())).isEqualTo("pending");
            assertThat(handler.bookings).hasSize(1);
        } finally {
            resume(BOUND);
        }
    }

    @Test
    void aBusinessTheOperatorSuspendedHasNeitherNotificationNorPollActedOn() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        pay(intent, 1500, "UGX", "CONF-0E03");
        endSubscription(BOUND, false);
        try {
            ipn(token(), tracking(intent), intent.get("merchant_reference").asString());
            clock.set(clock.instant().plus(Duration.ofMinutes(3)));
            tick();
            assertThat(statusOf(id)).isEqualTo("pending");
            assertThat(handler.bookings).isEmpty();
        } finally {
            resume(BOUND);
        }
    }

    // ---- the handler -------------------------------------------------------------------------------

    @Test
    void aRepaymentRefusedAsBeforeTheLastRepaymentIsRetriedOnceDatedToday() {
        handler.refuseFirstOnly = PurposeHandler.BEFORE_LAST_REPAYMENT;
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0009");
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("booked");
        assertThat(handler.calls).hasValue(2);
        assertThat(handler.bookings.getFirst().valueDate()).isEqualTo(LocalDate.of(2099, 10, 16));
    }

    @Test
    void aRepaymentRefusedAsPeriodClosedIsRetriedOnceDatedToday() {
        handler.refuseFirstOnly = "period_closed";
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0012");
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("booked");
        assertThat(handler.calls).hasValue(2);
        assertThat(handler.bookings.getFirst().valueDate()).isEqualTo(LocalDate.of(2099, 10, 16));
    }

    @Test
    void anyOtherBusinessRefusalNeverStrandsTheMoneyItIsPostedToUnallocatedReceipts() {
        handler.refuse = "loan_closed";
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0010");
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        String id = intent.get("id").asString();
        assertThat(statusOf(id)).isEqualTo("booked_unallocated");
        assertThat(row(id).get("refusal_code")).isEqualTo("loan_closed");
        assertThat(row(id).get("paid_amount_minor")).isEqualTo(1500L);
        assertThat(handler.bookings).isEmpty();
        // One receipt for the intent, and a balanced journal: debit gateway_clearing, credit unallocated_receipts.
        var receipt = TestDatabase.owner()
                .sql(
                        "SELECT amount_minor, refusal_code, status, journal_entry_id, confirmation_code FROM unallocated_receipts WHERE intent_id = ?")
                .param(UUID.fromString(id))
                .query((rs, n) -> new Object[] {
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5)
                })
                .single();
        assertThat(receipt[0]).isEqualTo(1500L);
        assertThat(receipt[1]).isEqualTo("loan_closed");
        assertThat(receipt[2]).isEqualTo("open");
        assertThat(receipt[4]).isEqualTo("CONF-0010");
        var lines = TestDatabase.owner()
                .sql(
                        "SELECT a.system_key, l.debit, l.credit FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id WHERE l.entry_id = ? ORDER BY l.debit DESC")
                .param(receipt[3])
                .query((rs, n) -> rs.getString(1) + ":" + rs.getLong(2) + ":" + rs.getLong(3))
                .list();
        assertThat(lines).containsExactly("gateway_clearing:1500:0", "unallocated_receipts:0:1500");
        // Booked once: another tick, or the same completion again, adds no receipt and no second journal.
        clock.set(clock.instant().plus(Duration.ofHours(1)));
        tick();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM unallocated_receipts WHERE intent_id = ?")
                        .param(UUID.fromString(id))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void aTechnicalFailureIsRetriedWithBackoffAndTheFifthEndsNeedsAttention() {
        handler.explode = true;
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0011");
        String id = intent.get("id").asString();
        for (int i = 1; i <= 5; i++) {
            clock.set(clock.instant().plus(Duration.ofMinutes(40)));
            tick();
            if (i < 5) {
                assertThat(statusOf(id)).as("after failure %d", i).isEqualTo("paid");
                assertThat(row(id).get("technical_failures")).isEqualTo(i);
            }
        }
        assertThat(statusOf(id)).isEqualTo("needs_attention");
        assertThat(row(id).get("refusal_code")).isEqualTo("technical_failures");
        // A later recovery of the handler books nothing by itself: a person decides.
        handler.explode = false;
        clock.set(clock.instant().plus(Duration.ofHours(2)));
        tick();
        assertThat(handler.bookings).isEmpty();
    }

    @Test
    void aReversalAfterBookingIsNeverAppliedByItselfItAsksForReview() {
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0012");
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        String id = intent.get("id").asString();
        assertThat(statusOf(id)).isEqualTo("booked");
        fake.reverse(tracking(intent));
        ipn(token(), tracking(intent), intent.get("merchant_reference").asString());
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        tick();
        assertThat(statusOf(id)).isEqualTo("reversed_pending_review");
        assertThat(handler.bookings).hasSize(1);
    }

    // JUSTIFICATION-A3: new tests the attack review asked for (contradicting answers, ceiling, return page); they add
    // cases, no mechanism.
    // ---- answers that contradict the intent -------------------------------------------------------------

    private void notifyAndCheck(JsonNode intent) {
        ipn(token(), tracking(intent), intent.get("merchant_reference").asString());
        clock.set(clock.instant().plusSeconds(30));
        tick();
    }

    private JsonNode booked() {
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-" + UUID.randomUUID().toString().substring(0, 8));
        notifyAndCheck(intent);
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("booked");
        return intent;
    }

    @Test
    void aPendingOrFailedAnswerAfterBookingChangesNothingAndBooksNothingMore() {
        JsonNode intent = booked();
        String id = intent.get("id").asString();
        Object txn = row(id).get("booked_subject_txn_id");
        for (PaymentGateway.Status contradiction :
                List.of(PaymentGateway.Status.PENDING, PaymentGateway.Status.FAILED)) {
            fake.forceStatus(contradiction);
            notifyAndCheck(intent);
            assertThat(statusOf(id)).as("after %s", contradiction).isEqualTo("booked");
            assertThat(row(id).get("booked_subject_txn_id")).isEqualTo(txn);
            assertThat(handler.calls).hasValue(1);
        }
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM payment_callbacks WHERE intent_id = ? AND outcome = 'status_mismatch'")
                        .param(UUID.fromString(id))
                        .query(Long.class)
                        .single())
                .as("both were seen and recorded as mismatches")
                .isEqualTo(2);
    }

    @Test
    void aReversedAnswerForAnOrderThatWasNeverPaidIsAMismatchNotAReversal() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        fake.forceStatus(PaymentGateway.Status.REVERSED);
        notifyAndCheck(intent);
        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(row(id).get("booked_subject_txn_id")).isNull();
        assertThat(handler.calls).hasValue(0);
        assertThat(audits(id, "reversed")).isZero();
    }

    @Test
    void aReversalAfterBookingKeepsTheBookingAndAFurtherReversalAnswerIsADuplicate() {
        JsonNode intent = booked();
        String id = intent.get("id").asString();
        Object txn = row(id).get("booked_subject_txn_id");
        fake.reverse(tracking(intent));
        notifyAndCheck(intent);
        assertThat(statusOf(id)).isEqualTo("reversed_pending_review");
        notifyAndCheck(intent);
        assertThat(statusOf(id)).isEqualTo("reversed_pending_review");
        assertThat(row(id).get("booked_subject_txn_id")).isEqualTo(txn);
        assertThat(handler.calls).hasValue(1);
        assertThat(audits(id, "reversal_reported")).isEqualTo(1);
    }

    /** The money landed in the database while a check was still waiting for the provider's (stale) answer. */
    private void markPaidBehindTheCheck(String id) {
        TestDatabase.owner()
                .sql("""
                        UPDATE payment_intents SET status = 'paid', paid_amount_minor = 1500, paid_currency = 'UGX',
                               confirmation_code = 'CONF-RACE001', paid_at = ?, verified_at = ?
                         WHERE id = ? AND status = 'pending'
                        """)
                .params(
                        java.sql.Timestamp.from(clock.instant()),
                        java.sql.Timestamp.from(clock.instant()),
                        UUID.fromString(id))
                .update();
    }

    @Test
    void aStalePendingOrFailedAnswerThatArrivesAfterThePaymentNeverClearsTheBookingRetry() {
        for (PaymentGateway.Status stale : List.of(PaymentGateway.Status.PENDING, PaymentGateway.Status.FAILED)) {
            handler.reset();
            fake.reset();
            JsonNode intent = create(1500);
            String id = intent.get("id").asString();
            fake.forceStatus(stale);
            fake.beforeQuery(() -> markPaidBehindTheCheck(id));
            clock.set(clock.instant().plus(Duration.ofMinutes(3)));
            tick();
            assertThat(statusOf(id))
                    .as("%s: the row is paid, the stale answer changed nothing", stale)
                    .isEqualTo("paid");
            assertThat(row(id).get("next_check_at"))
                    .as("%s: the booking is still due, the stale answer did not clear it", stale)
                    .isNotNull();
            assertThat(handler.calls).hasValue(0);
            // And the next tick books it.
            fake.beforeQuery(null);
            fake.forceStatus(null);
            clock.set(clock.instant().plusSeconds(5));
            tick();
            assertThat(statusOf(id)).isEqualTo("booked");
        }
    }

    @Test
    void aBookedPaymentIsAskedAboutOnceADayForFourteenDaysSoALostReversalNotificationIsStillSeen() {
        JsonNode intent = booked();
        String id = intent.get("id").asString();
        assertThat(row(id).get("next_check_at"))
                .as("a booked row stays due, a day on")
                .isNotNull();
        fake.reverse(tracking(intent));
        // No notification at all: only the daily review can see it.
        clock.set(clock.instant().plus(Duration.ofHours(25)));
        tick();
        assertThat(statusOf(id)).isEqualTo("reversed_pending_review");
        assertThat(audits(id, "reversal_reported")).isEqualTo(1);
        assertThat(handler.calls).hasValue(1);
    }

    @Test
    void theDailyReviewOfABookedPaymentStopsAfterFourteenDays() {
        JsonNode intent = booked();
        String id = intent.get("id").asString();
        int before = fake.queryCount();
        for (int day = 0; day < 16; day++) {
            clock.set(clock.instant().plus(Duration.ofHours(25)));
            tick();
        }
        assertThat(row(id).get("next_check_at")).isNull();
        int asked = fake.queryCount() - before;
        assertThat(asked).as("about one a day for 14 days, then none").isBetween(12, 15);
        clock.set(clock.instant().plus(Duration.ofDays(3)));
        tick();
        assertThat(fake.queryCount() - before).isEqualTo(asked);
    }

    @Test
    void aPaymentThatLandsBetweenTheProvidersCancelAndOursKeepsItsStatusAndAnswers409() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        // The provider accepted the cancel; before the intent row is locked, the payment is recorded.
        fake.afterCancel(() -> markPaidBehindTheCheck(id));
        ResponseEntity<JsonNode> r =
                call(HttpMethod.POST, "/api/v1/payments/intents/" + id + "/cancel", BOUND, staff, null, null);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(409);
        assertThat(r.getBody().get("code").asString()).isEqualTo("not_cancellable");
        assertThat(statusOf(id)).isEqualTo("paid");
        assertThat(audits(id, "cancelled")).isZero();
    }

    @Test
    void anIpnAndAPayerReturnWithinTenSecondsEachLeaveTheirOwnRow() {
        JsonNode intent = create(1500);
        String ref = intent.get("merchant_reference").asString();
        ipn(token(), tracking(intent), ref);
        http.getForEntity(
                "/api/v1/payments/return/pesapal?OrderTrackingId=" + tracking(intent) + "&OrderMerchantReference="
                        + ref,
                String.class);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(DISTINCT kind) FROM payment_callbacks WHERE intent_id = ?")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void enablingAndClearingTheCeilingInOneRequestOnATenantThatIsAlreadyOnIsRefusedAndNothingChanges() {
        ensureOn();
        JsonNode refused =
                setSettings(BOUND, staff, Map.of("collections_enabled", true, "clear_daily_ceiling", true), 409);
        assertThat(refused.get("code").asString()).isEqualTo("daily_ceiling_required");
        JsonNode after = call(HttpMethod.GET, "/api/v1/payments/settings", BOUND, staff, null, null)
                .getBody();
        assertThat(after.get("collections_enabled").asBoolean()).isTrue();
        assertThat(after.get("daily_ceiling_minor").isNull()).isFalse();
        // Off plus clear in one request is fine: the end state is off.
        setSettings(BOUND, staff, Map.of("collections_enabled", false, "clear_daily_ceiling", true), 200);
        ensureOn();
    }

    @Test
    void anOrderWhoseAnswersNeverMakeSenseIsEscalatedAfter48HoursNotLeftWithoutACheck() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        fake.forceStatus(PaymentGateway.Status.ANOMALY);
        for (int i = 0; i < 9; i++) {
            clock.set(clock.instant().plus(Duration.ofHours(7)));
            tick();
        }
        assertThat(statusOf(id)).isEqualTo("needs_attention");
        assertThat(row(id).get("refusal_code")).isEqualTo("anomaly_after_48h");
    }

    @Test
    void aPaidIntentIsNotCancellableAtTheOutset() {
        handler.explode = true;
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-PAIDCANCEL");
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        String id = intent.get("id").asString();
        assertThat(statusOf(id)).isEqualTo("paid");
        ResponseEntity<JsonNode> r =
                call(HttpMethod.POST, "/api/v1/payments/intents/" + id + "/cancel", BOUND, staff, null, null);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("code").asString()).isEqualTo("not_cancellable");
        assertThat(statusOf(id)).isEqualTo("paid");
    }

    @Test
    void aProviderThatIsDownDoesNotUseUpTheCreationAttempts() {
        JsonNode stuck = createStuck();
        String id = stuck.get("id").asString();
        fake.failCreate(new GatewayException(GatewayException.Kind.UNAVAILABLE, 0, "circuit_open"));
        for (int i = 0; i < 8; i++) {
            assertThat(creatorFor(UUID.fromString(id))).isEqualTo(OrderCreator.Outcome.DEFERRED);
        }
        fake.failCreate(null);
        assertThat(row(id).get("creation_attempts"))
                .as("a deferral is not an attempt")
                .isEqualTo(0);
        assertThat(statusOf(id)).isEqualTo("created");
    }

    @Test
    void collectionsNeedADailyCeilingToBeSwitchedOnAndItCannotBeRemovedWhileOn() {
        setSettings(BOUND, staff, Map.of("collections_enabled", false), 200);
        setSettings(BOUND, staff, Map.of("clear_daily_ceiling", true), 200);
        assertThat(setSettings(BOUND, staff, Map.of("collections_enabled", true), 409)
                        .get("code")
                        .asString())
                .isEqualTo("daily_ceiling_required");
        ensureOn();
        setSettings(BOUND, staff, Map.of("clear_daily_ceiling", true), 409);
    }

    @Test
    void thePayerReturnPageWritesNothingForAReferenceItDoesNotKnowAndNudgesAKnownOne() {
        String unknown = "BMS-DV-" + UUID.randomUUID().toString().replace("-", "");
        long unmatched = TestDatabase.owner()
                .sql("SELECT count(*) FROM payment_unmatched_callbacks")
                .query(Long.class)
                .single();
        long counted = TestDatabase.owner()
                .sql("SELECT coalesce(sum(hits), 0) FROM payment_callback_counters WHERE outcome = 'unknown_reference'")
                .query(Long.class)
                .single();
        ResponseEntity<String> page = http.getForEntity(
                "/api/v1/payments/return/pesapal?OrderTrackingId=aaaaaaaa-0000-4000-8000-0000000000bb&OrderMerchantReference="
                        + unknown + "&OrderNotificationType=CALLBACKURL",
                String.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("Payment received");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_unmatched_callbacks")
                        .query(Long.class)
                        .single())
                .isEqualTo(unmatched);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT coalesce(sum(hits), 0) FROM payment_callback_counters WHERE outcome = 'unknown_reference'")
                        .query(Long.class)
                        .single())
                .isEqualTo(counted);

        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-RETURN01");
        http.getForEntity(
                "/api/v1/payments/return/pesapal?OrderTrackingId=" + tracking(intent) + "&OrderMerchantReference="
                        + intent.get("merchant_reference").asString() + "&OrderNotificationType=CALLBACKURL",
                String.class);
        clock.set(clock.instant().plusSeconds(30));
        tick();
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("booked");
    }

    @Test
    void aReplayWithinTenSecondsAddsNoSecondCallbackRow() {
        JsonNode intent = create(1500);
        String ref = intent.get("merchant_reference").asString();
        for (int i = 0; i < 5; i++) {
            ipn(token(), tracking(intent), ref);
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_callbacks WHERE intent_id = ?")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ---- the database guard --------------------------------------------------------------------------

    @Test
    void theDatabaseRefusesATransitionTheStateMachineDoesNotAllowAndAChangeOfWhatWasAsked() {
        JsonNode intent = create(1500);
        UUID id = UUID.fromString(intent.get("id").asString());
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET status = 'failed', failure_reason = 'test' WHERE id = ?")
                .param(id)
                .update();
        // From failed it may only go to paid or needs_attention: booked is refused for the table owner too (no opt-in).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE payment_intents SET status = 'pending' WHERE id = ?")
                        .param(id)
                        .update())
                .hasMessageContaining("not an allowed transition");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE payment_intents SET amount_minor = 99999 WHERE id = ?")
                        .param(id)
                        .update())
                .hasMessageContaining("never change");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE payment_intents SET merchant_reference = 'BMS-DV-" + "1".repeat(32)
                                + "' WHERE id = ?")
                        .param(id)
                        .update())
                .hasMessageContaining("never change");
        // Paid cannot exist without what the provider reported.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE payment_intents SET status = 'paid' WHERE id = ?")
                        .param(id)
                        .update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void theApplicationRoleCannotDeleteAnIntentOrRewriteACallbackRow() {
        JsonNode intent = create(1500);
        ipn(token(), tracking(intent), intent.get("merchant_reference").asString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deleteAsApp("DELETE FROM payment_intents"))
                .hasMessageContaining("permission denied");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE payment_callbacks SET intent_id = intent_id, kind = 'return'")
                        .update())
                .rootCause()
                .hasMessageContaining("finished once");
        assertThat(count()).isPositive();
    }

    void deleteAsApp(String sql) {
        try (java.sql.Connection c = TestDatabase.appDataSource().getConnection();
                java.sql.Statement st = c.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + BOUND.tenantId() + "', false)");
            st.execute(sql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    // ---- tenants ---------------------------------------------------------------------------------------

    @Test
    void anotherTenantCannotReadTheIntentAndCannotSwitchCollectionsOn() {
        JsonNode intent = create(1500);
        ResponseEntity<JsonNode> r = call(
                HttpMethod.GET,
                "/api/v1/payments/intents/" + intent.get("id").asString(),
                OTHER,
                staffOther,
                null,
                null);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        JsonNode refused = setSettings(OTHER, staffOther, Map.of("collections_enabled", true), 409);
        assertThat(refused.get("code").asString()).isEqualTo("merchant_account_not_verified");
        ResponseEntity<JsonNode> off = call(HttpMethod.GET, "/api/v1/payments/settings", OTHER, staffOther, null, null);
        assertThat(off.getBody().get("collections_enabled").asBoolean()).isFalse();
        // And it cannot start a payment against the bound tenant's subject either.
        ResponseEntity<JsonNode> create = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                OTHER,
                staffOther,
                Map.of("purpose", PURPOSE, "subject_id", subject, "amount_minor", 1500, "payer_phone", "0700000123"),
                "key-" + UUID.randomUUID());
        assertThat(create.getStatusCode().value()).isEqualTo(422);
        assertThat(create.getBody().get("code").asString()).isEqualTo("collections_disabled");
        // A notification for the bound tenant's reference moves nothing in the other tenant's rows.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_intents WHERE tenant_id = ?")
                        .param(OTHER.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void anotherTenantsNotificationCannotBeResolvedThroughTheWrongScope() {
        JsonNode intent = create(1500);
        // The platform pair has no token on this host, so a token of that pair is simply wrong.
        assertThat(ipn(
                                "platform-token-that-is-not-configured",
                                tracking(intent),
                                intent.get("merchant_reference").asString())
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void collectionsCannotBeSwitchedOnWhileTheGatewayMethodIsUnmapped() {
        setSettings(BOUND, staff, Map.of("collections_enabled", false), 200);
        TestDatabase.owner()
                .sql("DELETE FROM payment_methods WHERE tenant_id = ? AND method_key = 'gateway'")
                .param(BOUND.tenantId())
                .update();
        // With no row the method has no default account either.
        JsonNode refused = setSettings(BOUND, staff, Map.of("collections_enabled", true), 409);
        assertThat(refused.get("code").asString()).isEqualTo("gateway_method_unmapped");
        ensureOn();
        ResponseEntity<JsonNode> disabled = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                BOUND,
                staff,
                Map.of("purpose", PURPOSE, "subject_id", subject, "amount_minor", 1500, "payer_phone", "0700000123"),
                "key-" + UUID.randomUUID());
        assertThat(disabled.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void collectionsAreOffUntilSwitchedOnAndSwitchingOffStopsNewIntents() {
        setSettings(BOUND, staff, Map.of("collections_enabled", false), 200);
        ResponseEntity<JsonNode> r = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                BOUND,
                staff,
                Map.of("purpose", PURPOSE, "subject_id", subject, "amount_minor", 1500, "payer_phone", "0700000123"),
                "key-" + UUID.randomUUID());
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody().get("code").asString()).isEqualTo("collections_disabled");
        ensureOn();
    }

    // ---- the daily ceiling across the Kampala midnight --------------------------------------------------

    @Test
    void theDailyCeilingCountsTheTenantsOwnDayAndTheNextKampalaDayStartsAtZero() {
        long askedToday = TestDatabase.owner()
                .sql(
                        "SELECT coalesce(sum(amount_minor), 0) FROM payment_intents WHERE tenant_id = ? AND status IN ('created','pending','paid','booked','booked_unallocated','needs_attention') AND created_at >= '2099-10-14T21:00:00Z' AND created_at < '2099-10-15T21:00:00Z'")
                .param(BOUND.tenantId())
                .query(Long.class)
                .single();
        setSettings(BOUND, staff, Map.of("daily_ceiling_minor", askedToday + 5000), 200);
        try {
            clock.set(BEFORE_MIDNIGHT);
            create(3000);
            ResponseEntity<JsonNode> over = call(
                    HttpMethod.POST,
                    "/api/v1/payments/intents",
                    BOUND,
                    staff,
                    Map.of(
                            "purpose",
                            PURPOSE,
                            "subject_id",
                            subject,
                            "amount_minor",
                            3000,
                            "payer_phone",
                            "0700000123"),
                    "key-" + UUID.randomUUID());
            assertThat(over.getStatusCode().value()).isEqualTo(422);
            assertThat(over.getBody().get("code").asString()).isEqualTo("daily_ceiling_exceeded");
            // Two minutes later it is 00:01 in Kampala on the 16th: a new day, the same 3000 fits.
            clock.set(AFTER_MIDNIGHT);
            create(3000);
        } finally {
            setSettings(BOUND, staff, Map.of("daily_ceiling_minor", 100000000L), 200);
        }
    }

    // ---- cancelling ------------------------------------------------------------------------------------

    @Test
    void aStaffMemberCancelsAnUnpaidIntentAndItsOrderIsCancelledAtTheProvider() {
        JsonNode intent = create(1500);
        ResponseEntity<JsonNode> r = call(
                HttpMethod.POST,
                "/api/v1/payments/intents/" + intent.get("id").asString() + "/cancel",
                BOUND,
                staff,
                null,
                null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("status").asString()).isEqualTo("cancelled");
        assertThat(fake.orderFor(intent.get("merchant_reference").asString()).cancelled())
                .isTrue();
        assertThat(audits(intent.get("id").asString(), "cancelled")).isEqualTo(1);
        ResponseEntity<JsonNode> again = call(
                HttpMethod.POST,
                "/api/v1/payments/intents/" + intent.get("id").asString() + "/cancel",
                BOUND,
                staff,
                null,
                null);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void noPhoneNumberOrProviderBodyIsInTheAuditRowsOrTheCallbackRows() {
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0013");
        ipn(token(), tracking(intent), intent.get("merchant_reference").asString());
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        String id = intent.get("id").asString();
        List<String> audit = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE entity_id = ?")
                .param(UUID.fromString(id))
                .query(String.class)
                .list();
        assertThat(audit).isNotEmpty();
        for (String d : audit) {
            assertThat(d).doesNotContain("0700000123").doesNotContain("+256700000123");
        }
        List<String> callbacks = TestDatabase.owner()
                .sql("SELECT coalesce(status_query_result::text, '') FROM payment_callbacks WHERE intent_id = ?")
                .param(UUID.fromString(id))
                .query(String.class)
                .list();
        assertThat(callbacks).isNotEmpty().allMatch(c -> c.length() < 80);
    }

    // ---- the lender's own merchant account (issue #233) ---------------------------------------------------

    static final String SECRET_TEXT = "invented-secret-for-the-leak-check-0001";
    static final String KEY_TEXT = "invented-key-for-the-leak-check-0001";

    Map<String, Object> creds(String key, String secret) {
        return Map.of("consumer_key", key, "consumer_secret", secret, "totp_code", "000000");
    }

    ResponseEntity<JsonNode> as(
            HttpMethod method, String path, TestDatabase.Fixture t, UUID user, String permissions, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(t, user, permissions)), JsonNode.class);
    }

    @Test
    void twoTenantsCollectThroughTwoMerchantsAndNeitherSeesTheOther() {
        TestDatabase.Fixture second = TestDatabase.tenant("pay-second", true);
        UUID staffSecond = user(second);
        TestDatabase.owner()
                .sql(
                        "INSERT INTO payment_methods (id, tenant_id, method_key, gl_account_id) VALUES (?, ?, 'gateway', ?)")
                .params(UUID.randomUUID(), second.tenantId(), second.account("gateway_clearing"))
                .update();
        UUID secondSubject = UUID.randomUUID();
        handler.branches.put(secondSubject, second.headOffice());
        String secondToken = connect(second, staffSecond);
        setSettings(second, staffSecond, Map.of("collections_enabled", true, "daily_ceiling_minor", 100000000L), 200);
        fake.reset();

        JsonNode mine = create(1500);
        ResponseEntity<JsonNode> theirs = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                second,
                staffSecond,
                Map.of(
                        "purpose",
                        PURPOSE,
                        "subject_id",
                        secondSubject,
                        "amount_minor",
                        2500,
                        "payer_phone",
                        "0700000456"),
                "key-" + UUID.randomUUID());
        assertThat(theirs.getStatusCode().value()).as("%s", theirs.getBody()).isEqualTo(201);

        // Each order was made for its own tenant's account, and no call ever named the platform's.
        assertThat(fake.accountsUsed().stream()
                        .map(PaymentGateway.Account::tenantId)
                        .toList())
                .containsExactly(BOUND.tenantId(), second.tenantId());
        assertThat(fake.accountsUsed())
                .allMatch(a -> a.scope() == PaymentGateway.Scope.TENANT && a.merchantId() != null);
        assertThat(row(mine.get("id").asString()).get("scope")).isEqualTo("tenant");
        assertThat(row(theirs.getBody().get("id").asString()).get("scope")).isEqualTo("tenant");

        // Each administrator sees their own account and nobody else's, and never a secret.
        JsonNode viewMine = call(HttpMethod.GET, "/api/v1/payments/merchant-account", BOUND, staff, null, null)
                .getBody();
        JsonNode viewTheirs = call(HttpMethod.GET, "/api/v1/payments/merchant-account", second, staffSecond, null, null)
                .getBody();
        assertThat(viewMine.get("key_hint").asString())
                .isNotEqualTo(viewTheirs.get("key_hint").asString());
        assertThat(call(
                                HttpMethod.GET,
                                "/api/v1/payments/intents/" + mine.get("id").asString(),
                                second,
                                staffSecond,
                                null,
                                null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);

        // A notification sent to one lender's own url cannot be matched to the other lender's order, in either
        // direction.
        String theirRef = theirs.getBody().get("merchant_reference").asString();
        String myRef = mine.get("merchant_reference").asString();
        long unmatchedBefore = unmatched("scope_mismatch");
        ipn(token(), fake.orderFor(theirRef).providerReference, theirRef);
        ipn(secondToken, fake.orderFor(myRef).providerReference, myRef);
        assertThat(unmatched("scope_mismatch")).isGreaterThan(unmatchedBefore);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_callbacks WHERE intent_id IN (?, ?)")
                        .params(
                                UUID.fromString(mine.get("id").asString()),
                                UUID.fromString(theirs.getBody().get("id").asString()))
                        .query(Long.class)
                        .single())
                .isZero();
        // Each lender's own url does reach its own order.
        ipn(token(), fake.orderFor(myRef).providerReference, myRef);
        ipn(secondToken, fake.orderFor(theirRef).providerReference, theirRef);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_callbacks WHERE intent_id = ?")
                        .param(UUID.fromString(mine.get("id").asString()))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(token()).isNotEqualTo(secondToken).isNotEqualTo(PLATFORM_TOKEN);
    }

    long unmatched(String reason) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(hits), 0) FROM payment_unmatched_callbacks WHERE reason = ?")
                .param(reason)
                .query(Long.class)
                .single();
    }

    @Test
    void theSecretIsWriteOnlyNoEndpointNoAuditRowAndNoColumnHoldsIt() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-secret", true);
        UUID admin = user(t);
        ResponseEntity<JsonNode> saved =
                as(HttpMethod.PUT, "/api/v1/payments/merchant-account", t, admin, PERMS, creds(KEY_TEXT, SECRET_TEXT));
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody().get("status").asString()).isEqualTo("not_connected");
        assertThat(saved.getBody().get("credentials_saved").asBoolean()).isTrue();
        assertThat(saved.getBody().get("key_hint").asString()).isEqualTo("0001");
        ResponseEntity<JsonNode> tested =
                as(HttpMethod.POST, "/api/v1/payments/merchant-account/test", t, admin, PERMS, null);
        ResponseEntity<JsonNode> read = as(HttpMethod.GET, "/api/v1/payments/merchant-account", t, admin, PERMS, null);
        for (ResponseEntity<JsonNode> r : List.of(saved, tested, read)) {
            assertThat(r.getBody().toString()).doesNotContain(SECRET_TEXT).doesNotContain(KEY_TEXT);
            assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        }
        assertThat(new java.util.ArrayList<>(read.getBody().propertyNames()))
                .noneMatch(f -> f.contains("secret") || f.equals("consumer_key"));
        // The stored boxes are ciphertext: neither value appears in any column, and the audit log is clean.
        Map<String, Object> raw = TestDatabase.owner()
                .sql("SELECT * FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .query()
                .singleRow();
        assertThat(raw.toString()).doesNotContain(SECRET_TEXT).doesNotContain(KEY_TEXT);
        assertThat(new String((byte[]) raw.get("consumer_secret_enc"), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(SECRET_TEXT);
        assertThat(new String((byte[]) raw.get("consumer_key_enc"), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(KEY_TEXT);
        assertThat(raw.get("data_key_id")).isNotNull();
        assertThat(raw.get("ipn_token_hash").toString())
                .hasSize(64)
                .doesNotContain(fake.registeredUrl(PaymentGateway.Account.tenant(t.tenantId()))
                        .substring(fake.registeredUrl(PaymentGateway.Account.tenant(t.tenantId()))
                                        .lastIndexOf('/')
                                + 1));
        List<String> audit = TestDatabase.owner()
                .sql(
                        "SELECT action || ' ' || data::text FROM audit_log WHERE tenant_id = ? AND action LIKE 'core.payments.account.%'")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(audit).hasSize(2);
        assertThat(String.join(" ", audit)).doesNotContain(SECRET_TEXT).doesNotContain(KEY_TEXT);
        // The credentials are readable only by the code that decrypts them for the tenant: the gateway sees them.
        assertThat(String.join(" ", audit))
                .contains("core.payments.account.saved")
                .contains("core.payments.account.verified");
    }

    @Test
    void onlyAnAdministratorMayChangeTheAccountAndAnInvalidValueIsRefused() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-perm", true);
        UUID admin = user(t);
        String readOnly = "core.settings.read,core.payments.read";
        assertThat(as(
                                HttpMethod.PUT,
                                "/api/v1/payments/merchant-account",
                                t,
                                admin,
                                readOnly,
                                creds(KEY_TEXT, SECRET_TEXT))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(as(HttpMethod.POST, "/api/v1/payments/merchant-account/test", t, admin, readOnly, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(as(
                                HttpMethod.DELETE,
                                "/api/v1/payments/merchant-account",
                                t,
                                admin,
                                readOnly,
                                Map.of("totp_code", "000000"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(as(HttpMethod.GET, "/api/v1/payments/merchant-account", t, admin, "core.payments.read", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(as(HttpMethod.GET, "/api/v1/payments/merchant-account", t, admin, readOnly, null)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("not_connected");
        for (Map<String, Object> bad :
                List.of(creds("short", SECRET_TEXT), creds(KEY_TEXT, "has a space in the secret"), creds("", ""))) {
            ResponseEntity<JsonNode> r = as(HttpMethod.PUT, "/api/v1/payments/merchant-account", t, admin, PERMS, bad);
            assertThat(r.getStatusCode().value()).isEqualTo(422);
            assertThat(r.getBody().toString()).doesNotContain(SECRET_TEXT);
        }
        // Testing with nothing saved is a conflict, not a call.
        long before = fake.calls().size();
        assertThat(as(HttpMethod.POST, "/api/v1/payments/merchant-account/test", t, admin, PERMS, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
        assertThat(fake.calls()).hasSize((int) before);
    }

    @Test
    void aFailedTestMarksTheAccountFailedStopsCollectingAndAPassingTestRestoresIt() {
        fake.failRegister(
                new GatewayException(GatewayException.Kind.AUTH, 200, "invalid_consumer_key_or_secret_provided"));
        ResponseEntity<JsonNode> failed =
                call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", BOUND, staff, null, null);
        assertThat(failed.getStatusCode().value()).isEqualTo(200);
        assertThat(failed.getBody().get("status").asString()).isEqualTo("failed");
        assertThat(failed.getBody().get("failure_reason").asString()).isEqualTo("invalid_credentials");
        assertThat(failed.getBody().get("last_checked_at").isNull()).isFalse();
        // A failed account collects nothing, and says so in plain terms; the platform's account is never tried.
        ResponseEntity<JsonNode> refused = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                BOUND,
                staff,
                Map.of("purpose", PURPOSE, "subject_id", subject, "amount_minor", 1500, "payer_phone", "0700000123"),
                "key-" + UUID.randomUUID());
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("merchant_account_not_verified");
        assertThat(fake.creates()).isZero();
        // The operator sees the status and nothing else.
        assertThat(operatorStatus(BOUND).status()).isEqualTo("failed");
        // A second test within half a minute is refused, whatever the first said.
        fake.failRegister(null);
        ResponseEntity<JsonNode> tooSoon =
                call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", BOUND, staff, null, null);
        assertThat(tooSoon.getStatusCode().value()).isEqualTo(429);
        assertThat(tooSoon.getBody().get("code").asString()).isEqualTo("test_too_soon");
        // A passing test restores it.
        merchantAccounts.forgetTests();
        ResponseEntity<JsonNode> ok =
                call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", BOUND, staff, null, null);
        assertThat(ok.getBody().get("status").asString()).isEqualTo("verified");
        assertThat(operatorStatus(BOUND).status()).isEqualTo("verified");
        assertThat(create(1500).get("status").asString()).isEqualTo("pending");
    }

    String tokenFromFake(TestDatabase.Fixture t) {
        String url = fake.registeredUrl(PaymentGateway.Account.tenant(t.tenantId()));
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Autowired
    MerchantAccountOperatorController operator;

    MerchantAccountOperatorController.Status operatorStatus(TestDatabase.Fixture t) {
        return operator.get(t.tenantId(), new org.springframework.mock.web.MockHttpServletResponse());
    }

    @Test
    void aProviderThatCannotBeReachedMarksNothingAndALendersBadSecretNeverOpensTheSharedBreaker() {
        fake.failRegister(new GatewayException(GatewayException.Kind.TRANSIENT, 0, "timeout"));
        ResponseEntity<JsonNode> down =
                call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", BOUND, staff, null, null);
        assertThat(down.getStatusCode().value()).isEqualTo(502);
        assertThat(down.getBody().get("code").asString()).isEqualTo("provider_unreachable");
        // Nothing changed: the account is still verified and still collects.
        assertThat(call(HttpMethod.GET, "/api/v1/payments/merchant-account", BOUND, staff, null, null)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("verified");
        fake.failRegister(null);
        assertThat(create(1500).get("status").asString()).isEqualTo("pending");
    }

    @Test
    void credentialsAreNotReplacedOrRemovedWhileAPaymentNeedsThemUnlessOnlyTheSecretChanges() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-inflight", true);
        UUID admin = user(t);
        TestDatabase.owner()
                .sql(
                        "INSERT INTO payment_methods (id, tenant_id, method_key, gl_account_id) VALUES (?, ?, 'gateway', ?)")
                .params(UUID.randomUUID(), t.tenantId(), t.account("gateway_clearing"))
                .update();
        UUID s = UUID.randomUUID();
        handler.branches.put(s, t.headOffice());
        connect(t, admin);
        setSettings(t, admin, Map.of("collections_enabled", true, "daily_ceiling_minor", 100000000L), 200);
        ResponseEntity<JsonNode> made = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                t,
                admin,
                Map.of("purpose", PURPOSE, "subject_id", s, "amount_minor", 1500, "payer_phone", "0700000123"),
                "key-" + UUID.randomUUID());
        assertThat(made.getStatusCode().value()).isEqualTo(201);
        String suffix = t.slug().substring(t.slug().length() - 8);
        // Another merchant account while an order is pending: refused.
        ResponseEntity<JsonNode> other = as(
                HttpMethod.PUT,
                "/api/v1/payments/merchant-account",
                t,
                admin,
                PERMS,
                creds("another-key-0000001", "another-secret-0000001"));
        assertThat(other.getStatusCode().value()).isEqualTo(409);
        assertThat(other.getBody().get("code").asString()).isEqualTo("payments_in_flight");
        assertThat(as(
                                HttpMethod.DELETE,
                                "/api/v1/payments/merchant-account",
                                t,
                                admin,
                                PERMS,
                                Map.of("totp_code", "000000"))
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
        // The same consumer key with a new secret (a rotation at the provider) is allowed, and needs a new test.
        ResponseEntity<JsonNode> rotated = as(
                HttpMethod.PUT,
                "/api/v1/payments/merchant-account",
                t,
                admin,
                PERMS,
                creds("invented-key-" + suffix, "a-new-invented-secret-" + suffix));
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        assertThat(rotated.getBody().get("status").asString()).isEqualTo("not_connected");
        // With nothing in flight (the order is cancelled) the account can be removed.
        String id = made.getBody().get("id").asString();
        assertThat(call(HttpMethod.POST, "/api/v1/payments/intents/" + id + "/cancel", t, admin, null, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        ResponseEntity<JsonNode> removed = as(
                HttpMethod.DELETE, "/api/v1/payments/merchant-account", t, admin, PERMS, Map.of("totp_code", "000000"));
        assertThat(removed.getStatusCode().value()).isEqualTo(200);
        assertThat(removed.getBody().get("status").asString()).isEqualTo("not_connected");
        assertThat(removed.getBody().get("credentials_saved").asBoolean()).isFalse();
    }

    @Test
    void anIntentOfThePlatformScopeCanNeverCarryALendersPurposeAndTheCodeRefusesThePlatformAccountForIt() {
        // The database: a platform-scope intent must have a platform purpose.
        UUID fresh = UUID.randomUUID();
        String insert = """
                INSERT INTO payment_intents (id, tenant_id, branch_id, purpose, subject_type, subject_id, payer_phone_e164,
                                             amount_minor, currency, scope, merchant_reference, status, initiated_by,
                                             idempotency_key, expires_at)
                VALUES (?, ?, ?, ?, 'test_subject', ?, '+256700000001', 1500, 'UGX', 'platform', ?, 'created', ?,
                        'scope-guard-key', now() + interval '30 minutes')
                """;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql(insert)
                        .params(
                                fresh,
                                BOUND.tenantId(),
                                BOUND.headOffice(),
                                "loans.repayment",
                                UUID.randomUUID(),
                                "BMS-DV-" + fresh.toString().replace("-", ""),
                                staff)
                        .update())
                .rootCause()
                .hasMessageContaining("payment_intents_scope_purpose");
        UUID platformOk = UUID.randomUUID();
        assertThat(TestDatabase.owner()
                        .sql(insert)
                        .params(
                                platformOk,
                                BOUND.tenantId(),
                                BOUND.headOffice(),
                                "platform.subscription",
                                UUID.randomUUID(),
                                "BMS-DV-" + platformOk.toString().replace("-", ""),
                                staff)
                        .update())
                .isEqualTo(1);
        // The code: the account for a stored intent is the tenant's own; the platform's is for platform purposes only.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> PaymentGateway.Account.forIntent("platform", BOUND.tenantId(), PURPOSE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(PaymentGateway.Account.forIntent("tenant", BOUND.tenantId(), PURPOSE))
                .isEqualTo(PaymentGateway.Account.tenant(BOUND.tenantId()));
        // Everything the fake saw for this tenant was its own account.
        create(1500);
        assertThat(fake.accountsUsed()).allMatch(a -> a.scope() == PaymentGateway.Scope.TENANT);
    }

    @Autowired
    AccountKeyRotation rotation;

    @Test
    void aRowSealedWithARetiredKeyIsResealedWithTheCurrentOneAndStillOpens() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-rotate", true);
        UUID admin = user(t);
        connect(t, admin);
        // The stored key id says "old": what a row sealed before a rotation looks like to the job's worklist.
        TestDatabase.owner()
                .sql("UPDATE payment_gateway_accounts SET data_key_id = 'old-key' WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        // This tenant only: rows left by other runs of a shared database were sealed by another process's ephemeral
        // key.
        assertThat(tenantJobs.callForTenant(t.tenantId(), () -> rotation.resealHere(currentKeyId())))
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT data_key_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isNotEqualTo("old-key");
        // A second pass finds nothing for this tenant, and the credentials still open for the gateway.
        assertThat(TestDatabase.owner()
                        .sql("SELECT app_tenants_with_sealed_secrets_to_reseal(?, 10)")
                        .param(currentKeyId())
                        .query(UUID.class)
                        .list())
                .doesNotContain(t.tenantId());
    }

    @Autowired
    com.rincoltech.bms.core.identity.DataKeys dataKeys;

    String currentKeyId() {
        return dataKeys.currentKeyId();
    }

    @Test
    void theTenantTokenIsStoredOnlyAsADigestAndSelectsOnlyItsOwnTenant() {
        String stored = TestDatabase.owner()
                .sql("SELECT ipn_token_hash FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(BOUND.tenantId())
                .query(String.class)
                .single();
        assertThat(stored).isEqualTo(AccountTokens.digest(token())).isNotEqualTo(token());
        JdbcClientHolder h = new JdbcClientHolder();
        assertThat(h.resolve(stored)).isEqualTo(BOUND.tenantId());
        assertThat(h.resolve(AccountTokens.digest("a-token-nobody-ever-registered-0001")))
                .isNull();
    }

    /** The definer function the callback host uses, called as the application role. */
    static final class JdbcClientHolder {
        UUID resolve(String digest) {
            return org.springframework.jdbc.core.simple.JdbcClient.create(TestDatabase.appDataSource())
                    .sql("SELECT tenant_id FROM app_resolve_payment_account('pesapal', ?)")
                    .param(digest)
                    .query(UUID.class)
                    .optional()
                    .orElse(null);
        }
    }

    // ---- the review of #308: what the surrounding code must also hold
    // -------------------------------------------------

    @Autowired
    StubSecurity security;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient appJdbc;

    @Test
    void theAccountsNeedTheTenantAdminsPermissionAndASecondFactorCode() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-stepup", true);
        UUID admin = user(t);
        String manager = "core.payments.read,core.settings.read,core.settings.manage";
        // The general settings permission is not enough to redirect where repayments are paid.
        assertThat(as(
                                HttpMethod.PUT,
                                "/api/v1/payments/merchant-account",
                                t,
                                admin,
                                manager,
                                creds(KEY_TEXT, SECRET_TEXT))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(as(HttpMethod.POST, "/api/v1/payments/merchant-account/test", t, admin, manager, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        // A wrong second-factor code saves nothing; a right one does.
        Map<String, Object> wrong =
                Map.of("consumer_key", KEY_TEXT, "consumer_secret", SECRET_TEXT, "totp_code", "123456");
        ResponseEntity<JsonNode> refused =
                as(HttpMethod.PUT, "/api/v1/payments/merchant-account", t, admin, PERMS, wrong);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(security.rejected).contains("123456");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_gateway_accounts WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        Map<String, Object> missing = Map.of("consumer_key", KEY_TEXT, "consumer_secret", SECRET_TEXT);
        assertThat(as(HttpMethod.PUT, "/api/v1/payments/merchant-account", t, admin, PERMS, missing)
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        // Removing needs the code too.
        connect(t, admin);
        assertThat(as(
                                HttpMethod.DELETE,
                                "/api/v1/payments/merchant-account",
                                t,
                                admin,
                                PERMS,
                                Map.of("totp_code", "999999"))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        // Someone who may only read settings sees neither the key's last four characters nor the host's state.
        JsonNode readerView = as(
                        HttpMethod.GET, "/api/v1/payments/merchant-account", t, admin, "core.settings.read", null)
                .getBody();
        assertThat(readerView.get("status").asString()).isEqualTo("verified");
        assertThat(readerView.get("key_hint").isNull()).isTrue();
        assertThat(readerView.get("host_ready").asBoolean()).isFalse();
    }

    @Test
    void everyChangeTellsTheOtherAdministratorsAndNoNoticeCarriesAKeyOrSecret() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-notice", true);
        UUID admin = user(t);
        security.holders = List.of(new com.rincoltech.bms.core.identity.AdminSecurity.Holder(
                UUID.randomUUID(), "other-admin@example.test"));
        try {
            connect(t, admin);
            List<String> rows = TestDatabase.owner()
                    .sql(
                            "SELECT recipient || ' ' || template_key || ' ' || coalesce(params::text, '') FROM notification_outbox WHERE tenant_id = ? AND template_key = 'payments.account_changed'")
                    .param(t.tenantId())
                    .query(String.class)
                    .list();
            assertThat(rows).hasSize(2);
            assertThat(String.join(" ", rows))
                    .contains("other-admin@example.test")
                    .contains("saved")
                    .doesNotContain("invented-key")
                    .doesNotContain("invented-secret");
            assertThat(TestDatabase.owner()
                            .sql(
                                    "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'core.payments.account.%'")
                            .param(t.tenantId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(2);
        } finally {
            security.holders = List.of();
        }
    }

    @Test
    void theConsumerKeyOfThePlatformOrOfAnotherLenderIsRefused() {
        TestDatabase.Fixture a = TestDatabase.tenant("pay-dup-a", true);
        TestDatabase.Fixture b = TestDatabase.tenant("pay-dup-b", true);
        UUID adminA = user(a);
        UUID adminB = user(b);
        String shared = "shared-merchant-key-" + a.slug().substring(a.slug().length() - 8);
        // The platform's own key (the property of this test context) is refused before anything is stored.
        ResponseEntity<JsonNode> platform = as(
                HttpMethod.PUT,
                "/api/v1/payments/merchant-account",
                a,
                adminA,
                PERMS,
                creds("invented-platform-key", SECRET_TEXT));
        assertThat(platform.getStatusCode().value()).isEqualTo(409);
        assertThat(platform.getBody().get("code").asString()).isEqualTo("merchant_account_in_use");
        assertThat(platform.getBody().get("detail").asString()).contains("already connected elsewhere");
        // One lender takes a key; another lender cannot take the same one, whatever tenant it is.
        assertThat(as(HttpMethod.PUT, "/api/v1/payments/merchant-account", a, adminA, PERMS, creds(shared, SECRET_TEXT))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        ResponseEntity<JsonNode> clash =
                as(HttpMethod.PUT, "/api/v1/payments/merchant-account", b, adminB, PERMS, creds(shared, SECRET_TEXT));
        assertThat(clash.getStatusCode().value()).isEqualTo(409);
        assertThat(clash.getBody().get("code").asString()).isEqualTo("merchant_account_in_use");
        // Issue #309: a key that differs only in letter case is the same account as far as these checks go, for another
        // lender
        // and for the platform's own key; what Pesapal is sent is never changed.
        for (String variant : new String[] {shared.toUpperCase(java.util.Locale.ROOT), "Invented-Platform-Key"}) {
            ResponseEntity<JsonNode> cased = as(
                    HttpMethod.PUT, "/api/v1/payments/merchant-account", b, adminB, PERMS, creds(variant, SECRET_TEXT));
            assertThat(cased.getStatusCode().value()).as(variant).isEqualTo(409);
            assertThat(cased.getBody().get("code").asString()).isEqualTo("merchant_account_in_use");
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_gateway_accounts WHERE tenant_id = ?")
                        .param(b.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        // The same tenant saving its own key again is not a clash.
        assertThat(as(
                                HttpMethod.PUT,
                                "/api/v1/payments/merchant-account",
                                a,
                                adminA,
                                PERMS,
                                creds(shared, "a-newer-secret-0002"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void
            oneLendersTokenWithAnotherLendersReferenceIsAScopeMismatchAndATenantWithNoAccountNeverUsesThePlatformAccount() {
        TestDatabase.Fixture none = TestDatabase.tenant("pay-noacct", true);
        UUID adminNone = user(none);
        TestDatabase.owner()
                .sql(
                        "INSERT INTO payment_methods (id, tenant_id, method_key, gl_account_id) VALUES (?, ?, 'gateway', ?)")
                .params(UUID.randomUUID(), none.tenantId(), none.account("gateway_clearing"))
                .update();
        UUID s = UUID.randomUUID();
        handler.branches.put(s, none.headOffice());
        // Collections cannot even be switched on with no account of its own, and a payment cannot be started.
        JsonNode refused = setSettings(
                none, adminNone, Map.of("collections_enabled", true, "daily_ceiling_minor", 100000000L), 409);
        assertThat(refused.get("code").asString()).isEqualTo("merchant_account_not_verified");
        ResponseEntity<JsonNode> r = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                none,
                adminNone,
                Map.of("purpose", PURPOSE, "subject_id", s, "amount_minor", 1500, "payer_phone", "0700000123"),
                "key-" + UUID.randomUUID());
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        // Even a forced intent row is never made an order of, nor checked with, the platform account.
        assertThat(fake.accountsUsed()).noneMatch(a -> a.scope() == PaymentGateway.Scope.PLATFORM);
        // Tenant A's own token with tenant B's merchant reference is a scope mismatch and matches nothing.
        JsonNode mine = create(1500);
        String myRef = mine.get("merchant_reference").asString();
        String fakeRef = "BMS-DV-" + UUID.randomUUID().toString().replace("-", "");
        long before = unmatched("scope_mismatch") + unmatched("unknown_reference");
        ipn(token(), "aaaaaaaa-0000-4000-8000-0000000000bb", fakeRef);
        assertThat(unmatched("scope_mismatch") + unmatched("unknown_reference")).isGreaterThan(before);
        assertThat(myRef).isNotEqualTo(fakeRef);
    }

    @Test
    void anIntentWhoseAccountHasNoReadableCredentialsIsSkippedAndNeverClosed() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(BEFORE_MIDNIGHT.minusSeconds(5)), UUID.fromString(id))
                .update();
        // The real adapter reports "not_configured" for an account with no credentials; the fake is told the same.
        fake.failQuery(new GatewayException(GatewayException.Kind.UNAVAILABLE, 0, "not_configured"));
        // Past the whole window in which an unreachable provider would close the intent as unpaid.
        clock.set(BEFORE_MIDNIGHT.plus(Duration.ofDays(5)));
        tick();
        assertThat(statusOf(id)).isIn("pending", "expired");
        assertThat(row(id).get("check_failures")).isEqualTo(0);
        assertThat(row(id).get("next_check_at")).isNotNull();
        // The credentials come back (the account is connected): the same intent is checked and booked as paid.
        fake.failQuery(null);
        pay(intent, 1500, "UGX", "CONF-NOCRED-1");
        clock.set(BEFORE_MIDNIGHT.plus(Duration.ofDays(5)).plus(Duration.ofHours(2)));
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(BEFORE_MIDNIGHT), UUID.fromString(id))
                .update();
        tick();
        assertThat(statusOf(id)).isIn("paid", "booked");
    }

    @Autowired
    AccountCredentials credentials;

    @Test
    void theStoredCredentialsAreReadThroughTheDatabaseForTheBoundTenantOnly() {
        // The adapter's own read (a transaction of its own, bound to the tenant of the thread): the decrypted pair of
        // the
        // bound tenant, and nothing for another tenant's account asked for from this tenant's thread.
        String suffix = BOUND.slug().substring(BOUND.slug().length() - 8);
        var own = tenantJobs.callAs(
                BOUND.tenantId(), () -> credentials.find(PaymentGateway.Account.tenant(BOUND.tenantId())));
        assertThat(own).isPresent();
        assertThat(own.get().key()).isEqualTo("invented-key-" + suffix);
        assertThat(own.get().ipnId()).isNotBlank();
        var theirs = tenantJobs.callAs(
                BOUND.tenantId(), () -> credentials.find(PaymentGateway.Account.tenant(OTHER.tenantId())));
        assertThat(theirs).isEmpty();
        // With no tenant bound at all, nothing either (and no error).
        assertThat(credentials.find(PaymentGateway.Account.tenant(BOUND.tenantId())))
                .isEmpty();
    }

    @Test
    void theOperatorsStatusAndReleaseAnswerOnlyToAConnectionWithNoTenantBound() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-operator", true);
        UUID admin = user(t);
        connect(t, admin);
        org.springframework.jdbc.core.simple.JdbcClient app =
                org.springframework.jdbc.core.simple.JdbcClient.create(TestDatabase.appDataSource());
        assertThat(app.sql("SELECT status FROM app_payment_account_status(?)")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("verified");
        // From inside a tenant's own transaction (any tenant) the same functions return nothing and delete nothing.
        try (java.sql.Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + BOUND.tenantId() + "', true)");
                try (java.sql.ResultSet rs =
                        st.executeQuery("SELECT * FROM app_payment_account_status('" + t.tenantId() + "')")) {
                    assertThat(rs.next()).isFalse();
                }
                try (java.sql.ResultSet rs =
                        st.executeQuery("SELECT app_release_payment_account('" + t.tenantId() + "')")) {
                    assertThat(rs.next() && rs.getString(1) == null).isTrue();
                }
            }
            c.rollback();
        }
        assertThat(count(t)).isEqualTo(1);
        // The platform's connection releases it and is told what it was.
        assertThat(app.sql("SELECT app_release_payment_account(?)")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("verified");
        assertThat(count(t)).isZero();
        assertThat(app.sql("SELECT app_release_payment_account(?)")
                        .param(t.tenantId())
                        .query(String.class)
                        .optional())
                .isEmpty();
    }

    long count(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void aProviderRefusalOfTheLendersCredentialsNeverClosesOrCancelsAnIntentTheBorrowerMayHavePaid() {
        JsonNode intent = create(1500);
        String id = intent.get("id").asString();
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(BEFORE_MIDNIGHT.minusSeconds(5)), UUID.fromString(id))
                .update();
        // The lender rotated its secret at Pesapal: every status query is refused as an authentication error.
        fake.failQuery(
                new GatewayException(GatewayException.Kind.AUTH, 200, "invalid_consumer_key_or_secret_provided"));
        // Past the 48 hours after which an unreachable provider would end the intent unpaid and cancel the order.
        clock.set(BEFORE_MIDNIGHT.plus(Duration.ofDays(5)));
        tick();
        assertThat(statusOf(id)).isIn("pending", "expired");
        assertThat(row(id).get("next_check_at")).isNotNull();
        assertThat(fake.calls()).noneMatch(c -> c.startsWith("cancel:"));
        // The same after the backoff the adapter reports on the next pass.
        fake.failQuery(new GatewayException(GatewayException.Kind.AUTH, 0, "auth_backoff"));
        clock.set(BEFORE_MIDNIGHT.plus(Duration.ofDays(5)).plus(Duration.ofHours(2)));
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(BEFORE_MIDNIGHT), UUID.fromString(id))
                .update();
        tick();
        assertThat(statusOf(id)).isIn("pending", "expired");
        assertThat(fake.calls()).noneMatch(c -> c.startsWith("cancel:"));
        // The credentials work again: the payment the borrower made is found and booked.
        fake.failQuery(null);
        pay(intent, 1500, "UGX", "CONF-AUTH-1");
        clock.set(BEFORE_MIDNIGHT.plus(Duration.ofDays(6)));
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(BEFORE_MIDNIGHT), UUID.fromString(id))
                .update();
        tick();
        assertThat(statusOf(id)).isIn("paid", "booked");
    }

    @Test
    void aKeyOnTheOperatorsRefusedListIsRefusedEvenThoughItIsNotThePlatformPairAndOnlyAfterTheCode() {
        // "rincol-owned-key-0000001" is on the list (its SHA-256) but is not the configured platform pair.
        TestDatabase.Fixture t = TestDatabase.tenant("pay-refused", true);
        UUID admin = user(t);
        ResponseEntity<JsonNode> refused = as(
                HttpMethod.PUT,
                "/api/v1/payments/merchant-account",
                t,
                admin,
                PERMS,
                creds("rincol-owned-key-0000001", SECRET_TEXT));
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("merchant_account_in_use");
        assertThat(count(t)).isZero();
        // Someone who has not passed the second factor is told nothing about the key.
        ResponseEntity<JsonNode> noCode = as(
                HttpMethod.PUT,
                "/api/v1/payments/merchant-account",
                t,
                admin,
                PERMS,
                Map.of(
                        "consumer_key",
                        "rincol-owned-key-0000001",
                        "consumer_secret",
                        SECRET_TEXT,
                        "totp_code",
                        "111111"));
        assertThat(noCode.getStatusCode().value()).isEqualTo(422);
        assertThat(noCode.getBody().get("code").asString()).isEqualTo("invalid_code");
    }

    @Test
    void aKeyHeldByAnotherTenantUnderARetiredKeysDigestIsStillACLash() {
        // A row sealed before a rotation keeps the digest of its old key: the lookup passes the digests under the
        // current
        // key and under each retired one, and finds it.
        TestDatabase.Fixture a = TestDatabase.tenant("pay-rot-a", true);
        UUID adminA = user(a);
        String oldDigest = "ab".repeat(32);
        TestDatabase.owner()
                .sql("""
                        INSERT INTO payment_gateway_accounts (id, tenant_id, consumer_key_enc, consumer_secret_enc,
                                                              consumer_key_hmac, key_hint, data_key_id, status, updated_by)
                        VALUES (?, ?, ?, ?, ?, 'abcd', 'old-key', 'saved', ?)
                        """)
                .params(UUID.randomUUID(), a.tenantId(), new byte[40], new byte[40], oldDigest, adminA)
                .update();
        org.springframework.jdbc.core.simple.JdbcClient app =
                org.springframework.jdbc.core.simple.JdbcClient.create(TestDatabase.appDataSource());
        UUID someoneElse = UUID.randomUUID();
        String currentDigest = "cd".repeat(32);
        assertThat(app.sql("SELECT app_payment_account_key_in_use('pesapal', ARRAY[?, ?], ?)")
                        .params(currentDigest, oldDigest, someoneElse)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(app.sql("SELECT app_payment_account_key_in_use('pesapal', ARRAY[?], ?)")
                        .params(currentDigest, someoneElse)
                        .query(Boolean.class)
                        .single())
                .isFalse();
        // Its own tenant is never a clash with itself.
        assertThat(app.sql("SELECT app_payment_account_key_in_use('pesapal', ARRAY[?], ?)")
                        .params(oldDigest, a.tenantId())
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    @Test
    void testingAnAccountThatIsAlreadyRegisteredKeepsItsNotificationAddressAndMakesNoNewRegistration() {
        String hashBefore = TestDatabase.owner()
                .sql("SELECT ipn_token_hash FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(BOUND.tenantId())
                .query(String.class)
                .single();
        String ipnBefore = TestDatabase.owner()
                .sql("SELECT ipn_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(BOUND.tenantId())
                .query(String.class)
                .single();
        fake.reset();
        merchantAccounts.forgetTests();
        ResponseEntity<JsonNode> again =
                call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", BOUND, staff, null, null);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().get("status").asString()).isEqualTo("verified");
        // Only the credentials were checked: no new address was registered, and the one orders were made with still
        // stands.
        assertThat(fake.calls()).contains("check").noneMatch(c -> c.startsWith("register:"));
        assertThat(TestDatabase.owner()
                        .sql("SELECT ipn_token_hash FROM payment_gateway_accounts WHERE tenant_id = ?")
                        .param(BOUND.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo(hashBefore);
        assertThat(TestDatabase.owner()
                        .sql("SELECT ipn_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                        .param(BOUND.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo(ipnBefore);
        // The address still reaches its order.
        JsonNode intent = create(1500);
        ipn(token(), tracking(intent), intent.get("merchant_reference").asString());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_callbacks WHERE intent_id = ?")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void anIntentIsBoundToTheMerchantAccountItWasMadeUnderAndIsNeverCheckedWithAnothersCredentials() {
        JsonNode intent = create(1500);
        UUID merchant = TestDatabase.owner()
                .sql("SELECT merchant_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(BOUND.tenantId())
                .query(UUID.class)
                .single();
        assertThat(row(intent.get("id").asString()).get("merchant_id")).isEqualTo(merchant);
        // While the account is still that merchant account the credentials resolve for the intent's account...
        PaymentGateway.Account forIntent =
                PaymentGateway.Account.forIntent("tenant", BOUND.tenantId(), PURPOSE, merchant);
        assertThat(tenantJobs.callAs(BOUND.tenantId(), () -> credentials.find(forIntent)))
                .isPresent();
        // ...and once the stored account is another merchant account (a different consumer key, or after a release and
        // a
        // new connection) they do not: "no credentials", so the intent is skipped, never checked with the new
        // merchant's.
        UUID before = merchant;
        TestDatabase.owner()
                .sql("UPDATE payment_gateway_accounts SET merchant_id = gen_random_uuid() WHERE tenant_id = ?")
                .param(BOUND.tenantId())
                .update();
        try {
            assertThat(tenantJobs.callAs(BOUND.tenantId(), () -> credentials.find(forIntent)))
                    .isEmpty();
            // A current order (no merchant given) resolves the tenant's present account as before.
            assertThat(tenantJobs.callAs(
                            BOUND.tenantId(), () -> credentials.find(PaymentGateway.Account.tenant(BOUND.tenantId()))))
                    .isPresent();
        } finally {
            TestDatabase.owner()
                    .sql("UPDATE payment_gateway_accounts SET merchant_id = ? WHERE tenant_id = ?")
                    .params(before, BOUND.tenantId())
                    .update();
        }
    }

    @Test
    void aReleaseNeedsAReasonOfRealLength() {
        jakarta.validation.Validator v =
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(v.validate(new MerchantAccountOperatorController.ReleaseRequest("short")))
                .isNotEmpty();
        assertThat(v.validate(new MerchantAccountOperatorController.ReleaseRequest(" ")))
                .isNotEmpty();
        assertThat(v.validate(new MerchantAccountOperatorController.ReleaseRequest(null)))
                .isNotEmpty();
        assertThat(v.validate(new MerchantAccountOperatorController.ReleaseRequest("The merchant key was exposed.")))
                .isEmpty();
    }

    @Autowired
    VerifiedAccounts verifiedAccounts;

    /** A verified account row for a tenant, with a consumer key sealed with the data key, inserted as the owner. */
    void insertVerified(TestDatabase.Fixture t, UUID user, String consumerKey, UUID merchantId) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO payment_gateway_accounts (id, tenant_id, merchant_id, consumer_key_enc, consumer_secret_enc,
                                consumer_key_hmac, key_hint, data_key_id, status, ipn_id, ipn_token_hash, ipn_registered_at,
                                last_checked_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'verified', ?, ?, now(), now(), ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        merchantId,
                        dataKeys.seal(consumerKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        dataKeys.seal("an-invented-secret-0001".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", ""),
                        consumerKey.substring(consumerKey.length() - 4),
                        dataKeys.currentKeyId(),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", ""),
                        user)
                .update();
    }

    @Test
    void aKeySavedBeforeTheRefusedListNamedItIsRefusedWhenTestedAndWhenUsed() {
        // "rincol-owned-key-0000001" is on the operator's refused list of this context. Saved while the list was empty
        // (or the
        // gateway off) it was accepted; now that the list names it, nothing may use it.
        TestDatabase.Fixture t = TestDatabase.tenant("pay-early", true);
        UUID admin = user(t);
        insertVerified(t, admin, "rincol-owned-key-0000001", UUID.randomUUID());
        // Used: not a verified account any more (so collections cannot be switched on and no intent can be made)...
        assertThat(tenantJobs.callForTenant(t.tenantId(), () -> verifiedAccounts.merchant()))
                .isEmpty();
        assertThat(tenantJobs.callForTenant(t.tenantId(), () -> verifiedAccounts.verified()))
                .isFalse();
        // ...its credentials resolve to nothing, so no order is made or checked with it...
        assertThat(tenantJobs.callAs(t.tenantId(), () -> credentials.find(PaymentGateway.Account.tenant(t.tenantId()))))
                .isEmpty();
        // ...switching collections on is refused...
        TestDatabase.owner()
                .sql(
                        "INSERT INTO payment_methods (id, tenant_id, method_key, gl_account_id) VALUES (?, ?, 'gateway', ?)")
                .params(UUID.randomUUID(), t.tenantId(), t.account("gateway_clearing"))
                .update();
        assertThat(setSettings(t, admin, Map.of("collections_enabled", true, "daily_ceiling_minor", 100000000L), 409)
                        .get("code")
                        .asString())
                .isEqualTo("merchant_account_not_verified");
        // ...and a test marks it failed, with no call to the provider.
        long before = fake.calls().size();
        ResponseEntity<JsonNode> tested =
                as(HttpMethod.POST, "/api/v1/payments/merchant-account/test", t, admin, PERMS, null);
        assertThat(tested.getStatusCode().value()).isEqualTo(200);
        assertThat(tested.getBody().get("status").asString()).isEqualTo("failed");
        assertThat(tested.getBody().get("failure_reason").asString()).isEqualTo("account_not_allowed");
        assertThat(fake.calls()).hasSize((int) before);
        // A control: an ordinary key on the same kind of row is a verified account.
        TestDatabase.Fixture ok = TestDatabase.tenant("pay-early-ok", true);
        insertVerified(ok, user(ok), "an-ordinary-key-0000001", UUID.randomUUID());
        assertThat(tenantJobs.callForTenant(ok.tenantId(), () -> verifiedAccounts.verified()))
                .isTrue();
    }

    @Test
    void anAccountWhoseKeyCannotBeOpenedIsTreatedAsRefusedNotAsAllowed() {
        // Issue #309: refused() used to fail open when the box could not be opened.
        TestDatabase.Fixture t = TestDatabase.tenant("pay-unopenable", true);
        UUID admin = user(t);
        TestDatabase.owner()
                .sql("""
                        INSERT INTO payment_gateway_accounts (id, tenant_id, merchant_id, consumer_key_enc, consumer_secret_enc,
                                consumer_key_hmac, key_hint, data_key_id, status, ipn_id, ipn_token_hash, ipn_registered_at,
                                last_checked_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, 'abcd', 'dropped-key', 'verified', ?, ?, now(), now(), ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        UUID.randomUUID(),
                        new byte[40],
                        new byte[40],
                        UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", ""),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", ""),
                        admin)
                .update();
        assertThat(tenantJobs.callForTenant(t.tenantId(), () -> verifiedAccounts.merchant()))
                .isEmpty();
        assertThat(tenantJobs.callForTenant(t.tenantId(), () -> verifiedAccounts.verified()))
                .isFalse();
    }

    @Test
    void anIntentWaitsForAKeyChangeInProgressAndThenSeesTheNewState() throws Exception {
        // Issue #309: intent creation reads the verified merchant FOR SHARE, so it cannot read the old merchant while a
        // key
        // change (which holds the row FOR UPDATE) is still in progress.
        java.sql.Connection change = TestDatabase.ownerDataSource().getConnection();
        change.setAutoCommit(false);
        try (var st =
                change.prepareStatement("SELECT 1 FROM payment_gateway_accounts WHERE tenant_id = ? FOR UPDATE")) {
            st.setObject(1, BOUND.tenantId());
            assertThat(st.executeQuery().next()).isTrue();
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ResponseEntity<JsonNode>> attempt = pool.submit(() -> call(
                    HttpMethod.POST,
                    "/api/v1/payments/intents",
                    BOUND,
                    staff,
                    Map.of(
                            "purpose",
                            PURPOSE,
                            "subject_id",
                            subject,
                            "amount_minor",
                            1500L,
                            "payer_phone",
                            "0700000123"),
                    "key-" + UUID.randomUUID()));
            Thread.sleep(1500);
            assertThat(attempt.isDone())
                    .as("the intent waits for the key change instead of reading the old merchant")
                    .isFalse();
            try (var st = change.prepareStatement(
                    "UPDATE payment_gateway_accounts SET status = 'saved' WHERE tenant_id = ?")) {
                st.setObject(1, BOUND.tenantId());
                st.executeUpdate();
            }
            change.commit();
            ResponseEntity<JsonNode> r = attempt.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(422);
            assertThat(r.getBody().get("code").asString()).isEqualTo("merchant_account_not_verified");
        } finally {
            change.rollback();
            change.close();
            pool.shutdownNow();
            TestDatabase.owner()
                    .sql("UPDATE payment_gateway_accounts SET status = 'verified' WHERE tenant_id = ?")
                    .param(BOUND.tenantId())
                    .update();
        }
    }

    @Test
    void theSameKeyIsTheSameMerchantAfterAReleaseAndAnotherKeyRetiresTheOldMerchantsIntentsInsteadOfBlockingForEver() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-merchant", true);
        UUID admin = user(t);
        String key = "merchant-key-" + t.slug().substring(t.slug().length() - 8);
        String path = "/api/v1/payments/merchant-account";
        java.util.function.Supplier<UUID> merchant = () -> TestDatabase.owner()
                .sql("SELECT merchant_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        assertThat(as(HttpMethod.PUT, path, t, admin, PERMS, creds(key, "first-secret-0000001"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        UUID first = merchant.get();
        // Test it (a new registration), then change only the secret: the registered address stays, the account needs no
        // new registration, and the test only checks the credentials.
        assertThat(as(HttpMethod.POST, path + "/test", t, admin, PERMS, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        String ipnBefore = TestDatabase.owner()
                .sql("SELECT ipn_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(as(HttpMethod.PUT, path, t, admin, PERMS, creds(key, "second-secret-000002"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(merchant.get()).isEqualTo(first);
        assertThat(TestDatabase.owner()
                        .sql("SELECT ipn_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .as("the same merchant keeps its notification address across a new secret")
                .isEqualTo(ipnBefore);
        merchantAccounts.forgetTests();
        fake.reset();
        assertThat(as(HttpMethod.POST, path + "/test", t, admin, PERMS, null)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("verified");
        assertThat(fake.calls()).noneMatch(c -> c.startsWith("register:"));

        // An order made under the first merchant, still scheduled.
        UUID intent = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO payment_intents (id, tenant_id, branch_id, purpose, subject_type, subject_id, payer_phone_e164,
                                amount_minor, currency, merchant_reference, status, initiated_by, idempotency_key, expires_at,
                                provider_reference, redirect_url, next_check_at, merchant_id)
                        VALUES (?, ?, ?, 'test.payment', 'test_subject', ?, '+256700000001', 1500, 'UGX', ?, 'pending', ?, 'merchant-key-1',
                                now() + interval '30 minutes', ?, 'https://pay.fake.example.test/x', now() + interval '1 hour', ?)
                        """)
                .params(
                        intent,
                        t.tenantId(),
                        t.headOffice(),
                        UUID.randomUUID(),
                        "BMS-DV-" + intent.toString().replace("-", ""),
                        admin,
                        UUID.randomUUID().toString(),
                        first)
                .update();
        // While it is scheduled, another merchant account is refused (it could not check it)...
        assertThat(as(HttpMethod.PUT, path, t, admin, PERMS, creds("another-merchant-" + key, "third-secret-0000003"))
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
        // ...the operator releases the account (the owner stands in for the console)...
        TestDatabase.owner()
                .sql("DELETE FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        // ...and the SAME key again is the same merchant, so that order is still its own and still checked.
        assertThat(as(HttpMethod.PUT, path, t, admin, PERMS, creds(key, "fourth-secret-000004"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(merchant.get()).isEqualTo(first);
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM payment_intents WHERE id = ?")
                        .param(intent)
                        .query(String.class)
                        .single())
                .isEqualTo("pending");
        // Another key after another release: the old merchant's order is handed to a person, not left waiting for
        // ever...
        TestDatabase.owner()
                .sql("DELETE FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        assertThat(as(HttpMethod.PUT, path, t, admin, PERMS, creds("another-merchant-" + key, "fifth-secret-0000005"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        Map<String, Object> retired = TestDatabase.owner()
                .sql("SELECT status, refusal_code, next_check_at FROM payment_intents WHERE id = ?")
                .param(intent)
                .query()
                .singleRow();
        assertThat(retired.get("status")).isEqualTo("needs_attention");
        assertThat(retired.get("refusal_code")).isEqualTo("merchant_changed");
        assertThat(retired.get("next_check_at")).isNull();
        // ...and it no longer blocks a later change of key.
        assertThat(as(HttpMethod.PUT, path, t, admin, PERMS, creds("yet-another-" + key, "sixth-secret-0000006"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void aSuspendedTenantsAdministratorsStillHearOfAnOperatorRelease() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-suspended", true);
        security.holders = List.of(new com.rincoltech.bms.core.identity.AdminSecurity.Holder(
                UUID.randomUUID(), "suspended-admin@example.test"));
        try {
            TestDatabase.owner()
                    .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                    .param(t.tenantId())
                    .update();
            merchantAccounts.notifyReleased(t.tenantId(), UUID.randomUUID());
            assertThat(TestDatabase.owner()
                            .sql(
                                    "SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key = 'payments.account_changed' AND recipient = 'suspended-admin@example.test'")
                            .param(t.tenantId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            security.holders = List.of();
        }
    }
}
