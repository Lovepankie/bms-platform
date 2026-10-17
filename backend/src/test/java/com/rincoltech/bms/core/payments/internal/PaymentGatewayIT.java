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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
            "bms.pesapal.ipn-token=" + PaymentGatewayIT.TOKEN
        })
class PaymentGatewayIT extends IntegrationTest {

    static final String TOKEN = "invented-ipn-path-token-for-the-it-0001";
    static final String PERMS = "core.payments.read,core.payments.collect,core.settings.read,core.settings.manage";
    static final TestDatabase.Fixture BOUND = TestDatabase.tenant("pay-bound", true);
    static final TestDatabase.Fixture OTHER = TestDatabase.tenant("pay-other", true);
    static final String PURPOSE = "test.payment";

    @DynamicPropertySource
    static void bind(DynamicPropertyRegistry registry) {
        registry.add("bms.pesapal.tenant-id", () -> BOUND.tenantId().toString());
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

    @TestConfiguration
    static class Config {
        @Bean
        TestHandler testHandler() {
            return new TestHandler();
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
    MovableClock clock;

    FakePaymentGateway fake;
    UUID staff;
    UUID staffOther;
    UUID subject;

    @BeforeEach
    void setUp() {
        fake = (FakePaymentGateway) gateway;
        fake.reset();
        handler.reset();
        clock.set(MovableClockConfig.BEFORE_MIDNIGHT);
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

    void ensureOn() {
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
        assertThat(Duration.between(
                        MovableClockConfig.BEFORE_MIDNIGHT,
                        ((java.sql.Timestamp) row(id).get("expires_at")).toInstant()))
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
            ResponseEntity<JsonNode> r = ipn(TOKEN, tracking(intent), ref);
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
                "/api/v1/payments/callbacks/pesapal/" + TOKEN + "?OrderTrackingId=" + tracking(intent)
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
        ipn(TOKEN, tracking(intent), ref);
        clock.set(clock.instant().plusSeconds(30));
        tick();
        assertThat(statusOf(intent.get("id").asString())).isEqualTo("failed");
        assertThat(handler.calls).hasValue(0);
    }

    @Test
    void anUnknownReferenceIsAcknowledgedCountedAndMakesNoCallToTheProvider() {
        String unknown = "BMS-DV-" + UUID.randomUUID().toString().replace("-", "");
        int before = fake.queryCount();
        ResponseEntity<JsonNode> r = ipn(TOKEN, "aaaaaaaa-0000-4000-8000-0000000000aa", unknown);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("status").asInt()).isEqualTo(200);
        ipn(TOKEN, "aaaaaaaa-0000-4000-8000-0000000000aa", unknown);
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
        ipn(TOKEN, "aaaaaaaa-0000-4000-8000-0000000000ee", ref);
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
                                "/api/v1/payments/callbacks/pesapal/" + TOKEN,
                                HttpMethod.GET,
                                HttpEntity.EMPTY,
                                JsonNode.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(ipn(TOKEN, "not-a-guid", ref).getStatusCode().value()).isEqualTo(400);
        HttpHeaders json = new HttpHeaders();
        json.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        String big = "{\"OrderTrackingId\":\"" + tracking(intent) + "\",\"pad\":\"" + "x".repeat(6000) + "\"}";
        ResponseEntity<JsonNode> huge = http.exchange(
                "/api/v1/payments/callbacks/pesapal/" + TOKEN,
                HttpMethod.POST,
                new HttpEntity<>(big, json),
                JsonNode.class);
        assertThat(huge.getStatusCode().value()).isEqualTo(413);
        // A POST with the JSON body is read whatever its content type says.
        String ok = "{\"OrderNotificationType\":\"IPNCHANGE\",\"OrderTrackingId\":\"" + tracking(intent)
                + "\",\"OrderMerchantReference\":\"" + ref + "\"}";
        ResponseEntity<JsonNode> posted = http.exchange(
                "/api/v1/payments/callbacks/pesapal/" + TOKEN,
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
                "/api/v1/payments/callbacks/pesapal/" + TOKEN + "?OrderTrackingId=" + tracking(intent)
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
        ipn(TOKEN, tracking(usd), usd.get("merchant_reference").asString());
        tick();
        // The adapter reads only whole shillings in UGX; the fake answers what a confused provider might.
        assertThat(statusOf(usd.get("id").asString())).isIn("needs_attention", "pending");
        assertThat(handler.calls).hasValue(0);

        JsonNode other = create(1500);
        pay(other, 1500, "UGX", "CONF-0004");
        fake.reportReference(tracking(other), "BMS-DV-" + "0".repeat(32));
        ipn(TOKEN, tracking(other), other.get("merchant_reference").asString());
        tick();
        assertThat(statusOf(other.get("id").asString())).isEqualTo("needs_attention");
        assertThat(row(other.get("id").asString()).get("refusal_code")).isEqualTo("reference_mismatch");
        assertThat(handler.calls).hasValue(0);
    }

    @Test
    void aPartialAndAnOverPaymentAreBookedAtTheConfirmedAmountWithAFlag() {
        JsonNode less = create(2000);
        pay(less, 1500, "UGX", "CONF-0005");
        ipn(TOKEN, tracking(less), less.get("merchant_reference").asString());
        tick();
        JsonNode more = create(2000);
        pay(more, 2600, "UGX", "CONF-0006");
        ipn(TOKEN, tracking(more), more.get("merchant_reference").asString());
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
                ipn(TOKEN, track, ref);
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
        assertThat(handler.bookings.getFirst().valueDate()).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void anyOtherBusinessRefusalNeverStrandsTheMoneyItEndsNeedsAttentionWithTheCode() {
        handler.refuse = "loan_closed";
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-0010");
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        String id = intent.get("id").asString();
        assertThat(statusOf(id)).isEqualTo("needs_attention");
        assertThat(row(id).get("refusal_code")).isEqualTo("loan_closed");
        assertThat(row(id).get("paid_amount_minor")).isEqualTo(1500L);
        assertThat(handler.bookings).isEmpty();
        // Not retried for ever: nothing is due.
        assertThat(row(id).get("next_check_at")).isNull();
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
        ipn(TOKEN, tracking(intent), intent.get("merchant_reference").asString());
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        tick();
        assertThat(statusOf(id)).isEqualTo("reversed_pending_review");
        assertThat(handler.bookings).hasSize(1);
    }

    // JUSTIFICATION-A3: new tests the attack review asked for (contradicting answers, ceiling, return page); they add
    // cases, no mechanism.
    // ---- answers that contradict the intent -------------------------------------------------------------

    private void notifyAndCheck(JsonNode intent) {
        ipn(TOKEN, tracking(intent), intent.get("merchant_reference").asString());
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

    @Test
    void aStaleAnswerNeverClearsTheBookingRetryOfAPaidIntent() {
        handler.explode = true;
        JsonNode intent = create(1500);
        pay(intent, 1500, "UGX", "CONF-STALE001");
        String id = intent.get("id").asString();
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        tick();
        assertThat(statusOf(id)).isEqualTo("paid");
        assertThat(row(id).get("next_check_at")).as("the retry stays scheduled").isNotNull();
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
    void aPaidIntentCannotBeCancelledAndGetsAConflictNotAServerError() {
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
        setSettings(BOUND, staff, Map.of("clear_daily_ceiling", true), 422);
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
            ipn(TOKEN, tracking(intent), ref);
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
        ipn(TOKEN, tracking(intent), intent.get("merchant_reference").asString());
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
        assertThat(refused.get("code").asString()).isEqualTo("merchant_not_bound_to_tenant");
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
                        "SELECT coalesce(sum(amount_minor), 0) FROM payment_intents WHERE tenant_id = ? AND status IN ('created','pending','paid','booked','booked_unallocated','needs_attention') AND created_at >= '2026-10-14T21:00:00Z' AND created_at < '2026-10-15T21:00:00Z'")
                .param(BOUND.tenantId())
                .query(Long.class)
                .single();
        setSettings(BOUND, staff, Map.of("daily_ceiling_minor", askedToday + 5000), 200);
        try {
            clock.set(MovableClockConfig.BEFORE_MIDNIGHT);
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
            clock.set(MovableClockConfig.AFTER_MIDNIGHT);
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
        ipn(TOKEN, tracking(intent), intent.get("merchant_reference").asString());
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
}
