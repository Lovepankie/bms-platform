package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.GatewayException.Kind;
import com.rincoltech.bms.core.payments.PaymentGateway.IpnRegistration;
import com.rincoltech.bms.core.payments.PaymentGateway.IpnType;
import com.rincoltech.bms.core.payments.PaymentGateway.OrderRequest;
import com.rincoltech.bms.core.payments.PaymentGateway.OrderResult;
import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.core.payments.PaymentGateway.Status;
import com.rincoltech.bms.core.payments.PaymentGateway.StatusResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Pesapal adapter against a local HTTP server that replays the recorded fixtures of slice #211
 * ({@code src/test/resources/pesapal}), with no network (spec 8 "local HTTP stubs", 3.12). It proves the contract the
 * spike confirmed: token cache and expiry, the business error that arrives as http 200 with a body status of 500, the
 * wrappers of 401, 404, 405 and the throttle, the status mapping on the numeric code, fractions of a shilling refused,
 * the original order handed back for a repeated reference, the RegisterIPN spacing, the breaker, and that no key, secret,
 * token or phone number reaches an exception message or a log line. Time is a clock the test moves and the pauses are
 * recorded, so nothing here sleeps. Every key, secret, token and number is invented.
 */
class PesapalGatewayContractTest {

    static final Path FIXTURES = Path.of("src/test/resources/pesapal");
    static final String KEY = "invented-consumer-key-0001";
    static final String SECRET = "invented-consumer-secret-0001";
    static final String PLATFORM_KEY = "invented-platform-key-0002";
    static final String IPN_ID = "aaaaaaaa-0000-4000-8000-000000000001";
    static final String RETURN_URL = "https://callbacks.example.test/api/v1/payments/return/pesapal";
    static final String PHONE = "+256700000123";
    static final String D1 = "aaaaaaaa-0000-4000-8000-0000000000d1";
    static final String REF_D1 = "BMS-ST-000000000000000000000000000000d1";
    static final String REF_1 = "BMS-ST-00000000000000000000000000000001";
    static final Instant T0 = Instant.parse("2026-10-13T20:00:00Z");

    record Recorded(String route, String query, Map<String, String> headers, String body) {}

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(String body) {
            return new Reply(200, body, Map.of());
        }
    }

    final ObjectMapper mapper = JsonMapper.builder().build();
    final AtomicReference<Instant> clock = new AtomicReference<>(T0);
    final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    final List<Recorded> requests = new CopyOnWriteArrayList<>();
    final Map<String, Function<Recorded, Reply>> routes = new ConcurrentHashMap<>();
    final AtomicInteger authCalls = new AtomicInteger();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    HttpServer server;
    ExecutorService serverThreads;
    CircuitBreaker breaker;
    PesapalGateway gateway;
    URI base;

    @BeforeEach
    void start() throws IOException {
        serverThreads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/", this::handle);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/pesapalv3");
        routes.put("POST /pesapalv3/api/Auth/RequestToken", r -> {
            int n = authCalls.incrementAndGet();
            return Reply.json(fixture("auth-token-ok").replace("FABRICATED.TOKEN.NOT.A.REAL.JWT", "FAB.TOKEN." + n));
        });
        breaker = new CircuitBreaker(5, Duration.ofSeconds(60), clock::get);
        gateway = gateway(Duration.ofSeconds(3));
        Logger logger = (Logger) LoggerFactory.getLogger(PesapalGateway.class);
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        ((Logger) LoggerFactory.getLogger(PesapalGateway.class)).detachAppender(logs);
        logs.stop();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    PesapalGateway gateway(Duration budget) {
        return new PesapalGateway(
                base,
                new PesapalSettings.Pair(KEY, SECRET, IPN_ID, "invented-tenant-path-token-0001"),
                new PesapalSettings.Pair(null, null, null, null),
                RETURN_URL,
                mapper,
                clock::get,
                breaker,
                d -> {
                    sleeps.add(d);
                    clock.updateAndGet(t -> t.plus(d));
                },
                budget);
    }

    /** A gateway with a breaker of its own, for a test that makes many of the calls that count against one. */
    PesapalGateway fresh() {
        breaker = new CircuitBreaker(5, Duration.ofSeconds(60), clock::get);
        return gateway(Duration.ofSeconds(3));
    }

    void handle(HttpExchange ex) throws IOException {
        byte[] in = ex.getRequestBody().readAllBytes();
        Map<String, String> headers = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        Recorded rec = new Recorded(
                ex.getRequestMethod() + " " + ex.getRequestURI().getPath(),
                ex.getRequestURI().getRawQuery(),
                headers,
                new String(in, StandardCharsets.UTF_8));
        requests.add(rec);
        Reply reply = routes.getOrDefault(
                        rec.route(), r -> new Reply(404, "{\"message\":\"No HTTP resource was found\"}", Map.of()))
                .apply(rec);
        if (reply == null) {
            // The test's handler dropped the connection without an answer.
            ex.close();
            return;
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
        ex.close();
    }

    static String fixture(String name) {
        try {
            return Files.readString(FIXTURES.resolve(name + ".json"));
        } catch (IOException e) {
            throw new AssertionError("missing fixture " + name, e);
        }
    }

    void route(String route, String fixtureName) {
        String body = fixture(fixtureName);
        routes.put(route, r -> Reply.json(body));
    }

    void status(String fixtureBody) {
        routes.put("GET /pesapalv3/api/Transactions/GetTransactionStatus", r -> Reply.json(fixtureBody));
    }

    long hits(String route) {
        return requests.stream().filter(r -> r.route().equals(route)).count();
    }

    static OrderRequest order() {
        return new OrderRequest(UUID.randomUUID(), REF_1, 1500, "UGX", PHONE, "Payment to Test Tenant");
    }

    StatusResult statusOf(String fixtureName, String requested) throws GatewayException {
        status(fixture(fixtureName));
        return gateway.queryStatus(Scope.TENANT, requested);
    }

    // ---- the token ----------------------------------------------------------------------------------------------

    @Test
    void theTokenIsCachedUntilAMinuteBeforeTheExpiryTheProviderReturnedAndThenRefreshed() throws Exception {
        route("GET /pesapalv3/api/Transactions/GetTransactionStatus", "status-pending");
        gateway.queryStatus(Scope.TENANT, "aaaaaaaa-0000-4000-8000-000000000003");
        gateway.queryStatus(Scope.TENANT, "aaaaaaaa-0000-4000-8000-000000000003");
        // The reference says a token lives five minutes at most; the sandbox said 3600 seconds. The expiry date rules.
        clock.set(T0.plus(Duration.ofMinutes(30)));
        gateway.queryStatus(Scope.TENANT, "aaaaaaaa-0000-4000-8000-000000000003");
        assertThat(authCalls).hasValue(1);
        assertThat(requests.getLast().headers()).containsEntry("authorization", "Bearer FAB.TOKEN.1");

        // expiryDate is 20:47:46.93Z: a minute before it, the token is still used; inside the last minute it is not.
        clock.set(Instant.parse("2026-10-13T20:46:46Z"));
        gateway.queryStatus(Scope.TENANT, "aaaaaaaa-0000-4000-8000-000000000003");
        assertThat(authCalls).hasValue(1);
        clock.set(Instant.parse("2026-10-13T20:46:48Z"));
        gateway.queryStatus(Scope.TENANT, "aaaaaaaa-0000-4000-8000-000000000003");
        assertThat(authCalls).hasValue(2);
        assertThat(requests.getLast().headers()).containsEntry("authorization", "Bearer FAB.TOKEN.2");
    }

    @Test
    void aTokenLifeIsCappedAtADayAndAnUnreadableExpiryGivesAMinute() throws Exception {
        route("GET /pesapalv3/api/Transactions/GetTransactionStatus", "status-pending");
        routes.put("POST /pesapalv3/api/Auth/RequestToken", r -> {
            authCalls.incrementAndGet();
            return Reply.json(fixture("auth-token-ok").replace("2026-10-13T20:47:46.933358Z", "2099-01-01T00:00:00Z"));
        });
        String pending = "aaaaaaaa-0000-4000-8000-000000000003";
        gateway.queryStatus(Scope.TENANT, pending);
        clock.set(T0.plus(Duration.ofHours(23)));
        gateway.queryStatus(Scope.TENANT, pending);
        assertThat(authCalls).hasValue(1);
        clock.set(T0.plus(Duration.ofHours(25)));
        gateway.queryStatus(Scope.TENANT, pending);
        assertThat(authCalls).hasValue(2);

        authCalls.set(0);
        routes.put("POST /pesapalv3/api/Auth/RequestToken", r -> {
            authCalls.incrementAndGet();
            return Reply.json(fixture("auth-token-ok").replace("2026-10-13T20:47:46.933358Z", "tomorrow-ish"));
        });
        clock.set(T0);
        PesapalGateway second = fresh();
        second.queryStatus(Scope.TENANT, pending);
        clock.set(T0.plus(Duration.ofSeconds(30)));
        second.queryStatus(Scope.TENANT, pending);
        assertThat(authCalls)
                .as("an unreadable expiry gives a token no longer than the safety margin, so it is never reused")
                .hasValue(2);
    }

    @Test
    void callersAtTheSameTimeShareOneTokenRequest() throws Exception {
        route("GET /pesapalv3/api/Transactions/GetTransactionStatus", "status-pending");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<StatusResult>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return gateway.queryStatus(Scope.TENANT, "aaaaaaaa-0000-4000-8000-000000000003");
            }));
        }
        go.countDown();
        for (Future<StatusResult> f : results) {
            assertThat(f.get(10, TimeUnit.SECONDS).status()).isEqualTo(Status.PENDING);
        }
        pool.shutdownNow();
        assertThat(authCalls).hasValue(1);
    }

    @Test
    void badCredentialsAreAnAuthFailureThatIsNotRepeatedForThirtySeconds() {
        route("POST /pesapalv3/api/Auth/RequestToken", "auth-invalid-credentials");
        assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.AUTH);
                    assertThat(e.code()).isEqualTo("invalid_consumer_key_or_secret_provided");
                });
        long afterFirst = hits("POST /pesapalv3/api/Auth/RequestToken");
        assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(
                        GatewayException.class, e -> assertThat(e.code()).isEqualTo("auth_backoff"));
        assertThat(hits("POST /pesapalv3/api/Auth/RequestToken")).isEqualTo(afterFirst);
        clock.set(T0.plusSeconds(31));
        assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, D1)).isInstanceOf(GatewayException.class);
        assertThat(hits("POST /pesapalv3/api/Auth/RequestToken")).isEqualTo(afterFirst + 1);
        // No order call was ever made without a token.
        assertThat(hits("GET /pesapalv3/api/Transactions/GetTransactionStatus")).isZero();
    }

    @Test
    void anUnconfiguredScopeMakesNoCallAtAll() {
        assertThat(gateway.available(Scope.TENANT)).isTrue();
        assertThat(gateway.available(Scope.PLATFORM)).isFalse();
        assertThatThrownBy(() -> gateway.queryStatus(Scope.PLATFORM, D1))
                .isInstanceOfSatisfying(
                        GatewayException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        assertThat(requests).isEmpty();
    }

    // ---- creating the order -------------------------------------------------------------------------------------

    @Test
    void anOrderIsSentWithAWholeShillingAmountTheIpnIdAndTheReturnUrlAndNothingElseAboutThePayer() throws Exception {
        route("POST /pesapalv3/api/Transactions/SubmitOrderRequest", "order-submit-ok");
        OrderResult result = gateway.createOrder(Scope.TENANT, order());

        assertThat(result.providerReference()).isEqualTo("aaaaaaaa-0000-4000-8000-000000000003");
        assertThat(result.merchantReference()).isEqualTo(REF_1);
        assertThat(result.redirectUrl()).startsWith("https://cybqa.pesapal.com/pesapaliframe/");

        Recorded sent = requests.getLast();
        assertThat(sent.headers()).containsEntry("authorization", "Bearer FAB.TOKEN.1");
        assertThat(sent.headers().get("content-type")).startsWith("application/json");
        JsonNode body = mapper.readTree(sent.body());
        assertThat(body.get("id").asString()).isEqualTo(REF_1);
        assertThat(body.get("currency").asString()).isEqualTo("UGX");
        assertThat(body.get("amount").isIntegralNumber())
                .as("a whole number, not a float")
                .isTrue();
        assertThat(sent.body()).contains("\"amount\":1500").doesNotContain("1500.0");
        assertThat(body.get("notification_id").asString()).isEqualTo(IPN_ID);
        assertThat(body.get("callback_url").asString()).isEqualTo(RETURN_URL);
        assertThat(body.get("description").asString()).isEqualTo("Payment to Test Tenant");
        assertThat(body.get("billing_address").get("phone_number").asString()).isEqualTo(PHONE);
        assertThat(body.properties().stream().map(Map.Entry::getKey))
                .containsExactlyInAnyOrder(
                        "id",
                        "currency",
                        "amount",
                        "description",
                        "callback_url",
                        "notification_id",
                        "billing_address");
        // The token request carried the credentials and nothing else did.
        assertThat(requests.stream().filter(r -> r.body().contains(SECRET)).map(Recorded::route))
                .containsExactly("POST /pesapalv3/api/Auth/RequestToken");
    }

    @Test
    void aRepeatedReferenceReturnsTheOriginalOrderAndIsNotAnError() throws Exception {
        route("POST /pesapalv3/api/Transactions/SubmitOrderRequest", "order-submit-duplicate-reference");
        OrderResult again = gateway.createOrder(
                Scope.TENANT, new OrderRequest(UUID.randomUUID(), REF_1, 999, "UGX", PHONE, "Payment"));
        assertThat(again.providerReference()).isEqualTo("aaaaaaaa-0000-4000-8000-000000000003");
        assertThat(again.merchantReference()).isEqualTo(REF_1);
    }

    @Test
    void everyBusinessRefusalThatArrivesAsHttp200WithBodyStatus500IsARefusalWithTheProvidersCode() throws Exception {
        List<String> names = List.of(
                "order-submit-invalid-amount",
                "order-submit-invalid-currency",
                "order-submit-invalid-ipn-id",
                "order-submit-invalid-reference-characters",
                "order-submit-missing-billing-address",
                "order-submit-missing-callback-url",
                "order-submit-reference-too-long");
        for (String name : names) {
            route("POST /pesapalv3/api/Transactions/SubmitOrderRequest", name);
            String expected =
                    mapper.readTree(fixture(name)).get("error").get("code").asString();
            assertThatThrownBy(() -> gateway.createOrder(Scope.TENANT, order()))
                    .as(name)
                    .isInstanceOfSatisfying(GatewayException.class, e -> {
                        assertThat(e.kind()).isEqualTo(Kind.REFUSED);
                        assertThat(e.httpStatus()).isEqualTo(200);
                        assertThat(e.code()).isEqualTo(expected);
                        assertThat(e.outcomeUnknown()).isFalse();
                    });
        }
        // A refusal is not the provider being ill: ten of them leave the breaker closed.
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void anOrderAnswerWithoutAGoodTrackingIdOrAnOffDomainRedirectIsRejected() {
        String ok = fixture("order-submit-ok");
        for (String bad : List.of(
                ok.replace("aaaaaaaa-0000-4000-8000-000000000003\",\n  \"merchant", "not-a-guid\",\n  \"merchant"),
                ok.replace("https://cybqa.pesapal.com/", "https://evil.test/"),
                ok.replace("https://cybqa.pesapal.com/", "http://cybqa.pesapal.com/"),
                ok.replace("cybqa.pesapal.com", "cybqa.pesapal.com.evil.test"),
                ok.replace("\"merchant_reference\": \"" + REF_1 + "\",", ""),
                ok.replace("\"status\": \"200\"", "\"status\": \"500\""))) {
            routes.put("POST /pesapalv3/api/Transactions/SubmitOrderRequest", r -> Reply.json(bad));
            assertThatThrownBy(() -> gateway.createOrder(Scope.TENANT, order()))
                    .as(bad)
                    .isInstanceOf(GatewayException.class);
        }
    }

    @Test
    void aRequestThatBreaksTheProvidersRulesIsRefusedBeforeAnyCallIsMade() {
        OrderRequest good = order();
        List<OrderRequest> bad = List.of(
                new OrderRequest(good.intentId(), "x".repeat(51), 1500, "UGX", PHONE, "p"),
                new OrderRequest(good.intentId(), "BMS ST with space", 1500, "UGX", PHONE, "p"),
                new OrderRequest(good.intentId(), "BMS-ST-/slash", 1500, "UGX", PHONE, "p"),
                new OrderRequest(good.intentId(), REF_1, 0, "UGX", PHONE, "p"),
                new OrderRequest(good.intentId(), REF_1, -5, "UGX", PHONE, "p"),
                new OrderRequest(good.intentId(), REF_1, 1500, "USD", PHONE, "p"),
                new OrderRequest(good.intentId(), REF_1, 1500, "ugx", PHONE, "p"),
                new OrderRequest(good.intentId(), REF_1, 1500, "UGX", " ", "p"),
                new OrderRequest(good.intentId(), null, 1500, "UGX", PHONE, "p"));
        for (OrderRequest r : bad) {
            assertThatThrownBy(() -> gateway.createOrder(Scope.TENANT, r))
                    .as("%s", r)
                    .isInstanceOfSatisfying(
                            GatewayException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REFUSED));
        }
        for (String id : new String[] {
            null, "", "not-a-guid", "../admin", D1 + "/../x", D1 + "?x=1", " " + D1, D1.replace('a', 'g')
        }) {
            assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, id)).isInstanceOf(GatewayException.class);
            assertThatThrownBy(() -> gateway.cancelOrder(Scope.TENANT, id)).isInstanceOf(GatewayException.class);
        }
        assertThat(requests).as("nothing went to the provider").isEmpty();
    }

    @Test
    void theDescriptionHasNoControlCharactersAndIsAtMostAHundredCharacters() throws Exception {
        route("POST /pesapalv3/api/Transactions/SubmitOrderRequest", "order-submit-ok");
        gateway.createOrder(
                Scope.TENANT,
                new OrderRequest(UUID.randomUUID(), REF_1, 1500, "UGX", PHONE, "Line\none\u0007" + "x".repeat(200)));
        String description =
                mapper.readTree(requests.getLast().body()).get("description").asString();
        assertThat(description).hasSize(100).doesNotContain("\n").doesNotContain("\u0007");
    }

    // ---- reading the status -------------------------------------------------------------------------------------

    @Test
    void anUnpaidOrderReadsStatusCodeZeroWithPaymentDetailsNotFoundAndThatIsPendingNotAFailure() throws Exception {
        StatusResult r = statusOf("status-pending", "aaaaaaaa-0000-4000-8000-000000000003");
        assertThat(r.status()).isEqualTo(Status.PENDING);
        assertThat(r.amountMinor()).isEqualTo(1000);
        assertThat(r.currency()).isEqualTo("UGX");
        assertThat(r.merchantReference()).isEqualTo(REF_1);
        assertThat(r.anomaly()).isNull();
    }

    @Test
    void theNumericStatusCodeDecidesAndTheDescriptionIsNeverRead() throws Exception {
        StatusResult completed = statusOf("status-completed-mobile-money", D1);
        assertThat(completed.status()).isEqualTo(Status.COMPLETED);
        assertThat(completed.amountMinor()).isEqualTo(1000);
        assertThat(completed.currency()).isEqualTo("UGX");
        assertThat(completed.paymentMethod()).isEqualTo("MTN");
        assertThat(completed.confirmationCode()).isEqualTo("FAKECONF0002");
        assertThat(completed.merchantReference()).isEqualTo(REF_D1);

        assertThat(statusOf("status-completed-card", D1).status()).isEqualTo(Status.COMPLETED);
        assertThat(statusOf("status-completed-overpaid", D1).amountMinor()).isEqualTo(1500);
        assertThat(statusOf("status-failed", D1).status()).isEqualTo(Status.FAILED);
        assertThat(statusOf("status-reversed", D1).status()).isEqualTo(Status.REVERSED);

        String completedBody = fixture("status-completed-mobile-money");
        // Whatever the casing or wording of the description, the code is what counts (the reference shows "Failed").
        for (String description : List.of("Failed", "FAILED", "failed", "COMPLETED", "INVALID", "Pending Payment")) {
            status(completedBody
                    .replace("\"status_code\": 1", "\"status_code\": 2")
                    .replace("\"COMPLETED\"", "\"" + description + "\""));
            assertThat(gateway.queryStatus(Scope.TENANT, D1).status())
                    .as(description)
                    .isEqualTo(Status.FAILED);
        }
        status(completedBody.replace("\"COMPLETED\"", "\"INVALID\""));
        assertThat(gateway.queryStatus(Scope.TENANT, D1).status()).isEqualTo(Status.COMPLETED);
        status(completedBody.replace("\"status_code\": 1", "\"status_code\": \"1\""));
        assertThat(gateway.queryStatus(Scope.TENANT, D1).status())
                .as("a numeric string is read")
                .isEqualTo(Status.COMPLETED);
    }

    @Test
    void anythingElseIsAnAnomalyThatNeverBooksNorFails() throws Exception {
        assertThat(statusOf("status-invalid", D1)).satisfies(r -> {
            assertThat(r.status()).isEqualTo(Status.ANOMALY);
            assertThat(r.anomaly()).isEqualTo("invalid_without_error");
        });
        assertThat(statusOf("status-unknown-order", "00000000-0000-0000-0000-000000000000")
                        .anomaly())
                .isEqualTo("invalid_order_tracking_id");
        assertThat(statusOf("status-other-credential-pair", D1).anomaly())
                .isEqualTo("invalid_consumer_key_or_secret_provided");

        String completed = fixture("status-completed-mobile-money");
        Map<String, String> mutations = Map.ofEntries(
                Map.entry("fractional_amount", completed.replace("\"amount\": 1000,", "\"amount\": 1000.55,")),
                Map.entry("amount_unreadable", completed.replace("\"amount\": 1000,", "\"amount\": 0,")),
                Map.entry("currency_unreadable", completed.replace("\"currency\": \"UGX\"", "\"currency\": \"USD\"")),
                Map.entry(
                        "tracking_mismatch",
                        completed.replace(
                                "\"order_tracking_id\": \"" + D1,
                                "\"order_tracking_id\": \"aaaaaaaa-0000-4000-8000-0000000000d2")),
                Map.entry("unknown_status_code", completed.replace("\"status_code\": 1", "\"status_code\": 7")),
                Map.entry("missing_status_code", completed.replace("\"status_code\": 1,", "")),
                Map.entry(
                        "missing_merchant_reference",
                        completed.replace("\"merchant_reference\": \"" + REF_D1 + "\",", "")));
        mutations.forEach((word, body) -> {
            status(body);
            try {
                StatusResult r = gateway.queryStatus(Scope.TENANT, D1);
                assertThat(r.status()).as(word).isEqualTo(Status.ANOMALY);
                assertThat(r.anomaly()).as(word).isEqualTo(word);
            } catch (GatewayException e) {
                throw new AssertionError(word, e);
            }
        });
    }

    @Test
    void theProviderCarriesFractionsOfAShillingSoTheAdapterRefusesThemWhateverTheFormat() throws Exception {
        String completed = fixture("status-completed-mobile-money");
        for (String amount : List.of("1000.55", "0.5", "1000.01", "999.999", "\"1000.5\"")) {
            status(completed.replace("\"amount\": 1000,", "\"amount\": " + amount + ","));
            StatusResult r = gateway.queryStatus(Scope.TENANT, D1);
            assertThat(r.status()).as(amount).isEqualTo(Status.ANOMALY);
            assertThat(r.amountMinor()).as(amount).isNull();
        }
        for (String amount : List.of("1000", "1000.0", "1000.00", "\"1000\"", "1E+3")) {
            status(completed.replace("\"amount\": 1000,", "\"amount\": " + amount + ","));
            assertThat(gateway.queryStatus(Scope.TENANT, D1).amountMinor())
                    .as(amount)
                    .isEqualTo(1000);
        }
    }

    @Test
    void thePaymentMethodAndConfirmationCodeAreCleanedAndCapped() throws Exception {
        String completed = fixture("status-completed-mobile-money")
                .replace("\"MTN\"", "\"<script>alert(1)</script> MTN " + "x".repeat(80) + "\"")
                .replace("FAKECONF0002", "C".repeat(300));
        status(completed);
        StatusResult r = gateway.queryStatus(Scope.TENANT, D1);
        assertThat(r.paymentMethod())
                .hasSizeLessThanOrEqualTo(40)
                .doesNotContain("<")
                .doesNotContain(">")
                .doesNotContain("(");
        assertThat(r.confirmationCode()).hasSize(100);
    }

    // ---- cancelling ---------------------------------------------------------------------------------------------

    @Test
    void aPendingOrderIsCancelledAndASecondCancelOrAnUnknownOrderIsARefusal() throws Exception {
        route("POST /pesapalv3/api/Transactions/CancelOrder", "cancel-ok");
        gateway.cancelOrder(Scope.TENANT, D1);
        assertThat(mapper.readTree(requests.getLast().body())
                        .get("order_tracking_id")
                        .asString())
                .isEqualTo(D1);
        for (String name : List.of("cancel-already-cancelled", "cancel-unknown-order")) {
            route("POST /pesapalv3/api/Transactions/CancelOrder", name);
            assertThatThrownBy(() -> gateway.cancelOrder(Scope.TENANT, D1))
                    .as(name)
                    .isInstanceOfSatisfying(GatewayException.class, e -> {
                        assertThat(e.kind()).isEqualTo(Kind.REFUSED);
                        assertThat(e.code()).isEqualTo("cancel_refused");
                    });
        }
    }

    // ---- registering the IPN url --------------------------------------------------------------------------------

    @Test
    void anIpnRegistrationReturnsTheIdAndCarriesTheUrlAndTheType() throws Exception {
        route("POST /pesapalv3/api/URLSetup/RegisterIPN", "ipn-register-get-ok");
        IpnRegistration r = gateway.registerIpn(
                Scope.TENANT, "https://callbacks.example.test/api/v1/payments/callbacks/pesapal/TOKEN", IpnType.GET);
        assertThat(r.ipnId()).isEqualTo("aaaaaaaa-0000-4000-8000-000000000001");
        JsonNode sent = mapper.readTree(requests.getLast().body());
        assertThat(sent.get("url").asString()).endsWith("/callbacks/pesapal/TOKEN");
        assertThat(sent.get("ipn_notification_type").asString()).isEqualTo("GET");
    }

    @Test
    void theThrottleOnRegisterIpnIsWaitedOutAndNotCountedAgainstTheBreaker() throws Exception {
        String ok = fixture("ipn-register-get-ok");
        String throttled = fixture("ipn-register-rate-limited");
        AtomicInteger calls = new AtomicInteger();
        routes.put(
                "POST /pesapalv3/api/URLSetup/RegisterIPN",
                r -> calls.incrementAndGet() <= 2 ? new Reply(409, throttled, Map.of()) : Reply.json(ok));
        IpnRegistration r = gateway.registerIpn(Scope.TENANT, "https://callbacks.example.test/x", IpnType.GET);
        assertThat(r.ipnId()).isNotBlank();
        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // It never loops for ever.
        sleeps.clear();
        routes.put("POST /pesapalv3/api/URLSetup/RegisterIPN", rq -> new Reply(409, throttled, Map.of()));
        assertThatThrownBy(() -> gateway.registerIpn(Scope.TENANT, "https://callbacks.example.test/x", IpnType.GET))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.THROTTLED);
                    assertThat(e.code()).isEqualTo("general_system_decline_error");
                });
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void twoRegistrationsAreSpacedByTenSecondsOnTheClockAndAnOldEnoughOneWaitsNot() throws Exception {
        route("POST /pesapalv3/api/URLSetup/RegisterIPN", "ipn-register-get-ok");
        gateway.registerIpn(Scope.TENANT, "https://callbacks.example.test/a", IpnType.GET);
        assertThat(sleeps).isEmpty();
        clock.set(T0.plusSeconds(4));
        gateway.registerIpn(Scope.TENANT, "https://callbacks.example.test/b", IpnType.POST);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(6));
        sleeps.clear();
        clock.set(T0.plusSeconds(60));
        gateway.registerIpn(Scope.TENANT, "https://callbacks.example.test/c", IpnType.GET);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void theAdapterRefusesANonPublicOrPlainHttpIpnUrlItselfBecauseTheProviderAcceptsThem() {
        for (String url : List.of(
                "http://callbacks.example.test/x",
                "https://localhost/x",
                "https://127.0.0.1/x",
                "https://10.1.2.3/x",
                "https://192.168.0.7/x",
                "not a url",
                "https://u:p@callbacks.example.test/x")) {
            assertThatThrownBy(() -> gateway.registerIpn(Scope.TENANT, url, IpnType.GET))
                    .as(url)
                    .isInstanceOfSatisfying(
                            GatewayException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REFUSED));
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void aRefusedRegistrationKeepsTheProvidersCode() {
        for (String name : List.of("ipn-register-invalid-url", "ipn-register-invalid-type")) {
            route("POST /pesapalv3/api/URLSetup/RegisterIPN", name);
            assertThatThrownBy(() -> gateway.registerIpn(Scope.TENANT, "https://callbacks.example.test/x", IpnType.GET))
                    .as(name)
                    .isInstanceOfSatisfying(
                            GatewayException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REFUSED));
        }
    }

    // ---- the errors that are not 200 ---------------------------------------------------------------------------

    @Test
    void theWrappersOf401404And405AreOpenedAndAGarbageTokenIsAnAuthFailureAfterOneRefresh() {
        String path = "GET /pesapalv3/api/Transactions/GetTransactionStatus";

        routes.put(path, r -> new Reply(401, fixture("error-missing-token"), Map.of()));
        assertThatThrownBy(() -> fresh().queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.AUTH);
                    assertThat(e.code()).isEqualTo("invalid_api_credentials_provided");
                });
        assertThat(authCalls)
                .as("the refused token was dropped and one fresh one tried")
                .hasValue(2);
        assertThat(hits(path)).isEqualTo(2);

        routes.put(path, r -> new Reply(500, fixture("error-invalid-token"), Map.of()));
        assertThatThrownBy(() -> fresh().queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(
                        GatewayException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH));

        routes.put(path, r -> new Reply(404, fixture("error-not-found"), Map.of()));
        assertThatThrownBy(() -> fresh().queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE);
                    assertThat(e.code()).isEqualTo("http_404");
                });

        routes.put(path, r -> new Reply(405, fixture("error-method-not-allowed"), Map.of()));
        assertThatThrownBy(() -> fresh().queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(
                        GatewayException.class, e -> assertThat(e.code()).isEqualTo("http_405"));
    }

    @Test
    void aServerErrorOrAnUnreadableAnswerIsTransientAndMeansTheOutcomeIsUnknown() {
        String path = "POST /pesapalv3/api/Transactions/SubmitOrderRequest";
        routes.put(path, r -> new Reply(503, "{\"message\":\"down\"}", Map.of()));
        assertThatThrownBy(() -> fresh().createOrder(Scope.TENANT, order()))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
                    assertThat(e.httpStatus()).isEqualTo(503);
                    assertThat(e.outcomeUnknown()).isTrue();
                });
        for (String body :
                new String[] {"", "not json", "{\"order_tracking_id\":", "[]", "\"text\"", "<html>Bad gateway</html>"
                }) {
            routes.put(path, r -> Reply.json(body));
            assertThatThrownBy(() -> fresh().createOrder(Scope.TENANT, order()))
                    .as(body)
                    .isInstanceOfSatisfying(GatewayException.class, e -> {
                        assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE);
                        assertThat(e.outcomeUnknown()).isTrue();
                    });
        }
        routes.put(path, r -> Reply.json("{\"order_tracking_id\":\"" + "x".repeat(2_000_000) + "\"}"));
        assertThatThrownBy(() -> fresh().createOrder(Scope.TENANT, order()))
                .as("a body over 1 MiB is cut and so unreadable")
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void aRedirectIsNeverFollowed() {
        String path = "POST /pesapalv3/api/Transactions/SubmitOrderRequest";
        routes.put(path, r -> new Reply(302, "", Map.of("Location", "/pesapalv3/elsewhere")));
        routes.put("POST /pesapalv3/elsewhere", r -> Reply.json("{}"));
        assertThatThrownBy(() -> gateway.createOrder(Scope.TENANT, order()))
                .isInstanceOfSatisfying(
                        GatewayException.class, e -> assertThat(e.code()).isEqualTo("http_302"));
        assertThat(hits("POST /pesapalv3/elsewhere")).isZero();
        assertThat(hits("GET /pesapalv3/elsewhere")).isZero();
    }

    @Test
    void aProviderThatDoesNotAnswerIsATimeoutAndOneThatHangsUpIsANetworkError() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        routes.put("GET /pesapalv3/api/Transactions/GetTransactionStatus", r -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Reply.json(fixture("status-pending"));
        });
        PesapalGateway impatient = gateway(Duration.ofMillis(300));
        assertThatThrownBy(() -> impatient.queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
                    assertThat(e.code()).isEqualTo("timeout");
                    assertThat(e.outcomeUnknown()).isTrue();
                });
        release.countDown();

        routes.put("GET /pesapalv3/api/Transactions/GetTransactionStatus", r -> null);
        assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(
                        GatewayException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));

        server.stop(0);
        assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
                    assertThat(e.code()).isEqualTo("connect");
                });
    }

    // ---- the breaker --------------------------------------------------------------------------------------------

    @Test
    void fiveFailuresInARowOpenTheBreakerForAMinuteThenOneProbeDecides() throws Exception {
        String path = "GET /pesapalv3/api/Transactions/GetTransactionStatus";
        routes.put(path, r -> new Reply(503, "{}", Map.of()));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, D1)).isInstanceOf(GatewayException.class);
        }
        long before = requests.size();
        assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, D1))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("circuit_open");
                    assertThat(e.outcomeUnknown()).isFalse();
                });
        assertThat(requests.size()).as("no call while open").isEqualTo(before);

        clock.set(T0.plusSeconds(61));
        // The probe fails: open again for another minute.
        assertThatThrownBy(() -> gateway.queryStatus(Scope.TENANT, D1)).isInstanceOf(GatewayException.class);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.set(T0.plusSeconds(125));
        route(path, "status-pending");
        assertThat(gateway.queryStatus(Scope.TENANT, "aaaaaaaa-0000-4000-8000-000000000003")
                        .status())
                .isEqualTo(Status.PENDING);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---- nothing secret leaves -----------------------------------------------------------------------------------

    @Test
    void noKeySecretTokenOrPhoneNumberAppearsInAnyExceptionOrLogLineOfAnyFailure() throws Exception {
        List<Throwable> failures = new ArrayList<>();
        Runnable[] shapes = new Runnable[] {
            () -> route("POST /pesapalv3/api/Auth/RequestToken", "auth-invalid-credentials"),
            () -> {
                routes.put(
                        "POST /pesapalv3/api/Transactions/SubmitOrderRequest",
                        r -> new Reply(503, "{\"echo\":\"" + SECRET + " " + PHONE + "\"}", Map.of()));
            },
            () -> {
                routes.put(
                        "POST /pesapalv3/api/Transactions/SubmitOrderRequest",
                        r -> new Reply(401, "{\"message\":\"" + KEY + " " + SECRET + " FAB.TOKEN\"}", Map.of()));
            },
            () -> {
                routes.put(
                        "POST /pesapalv3/api/Transactions/SubmitOrderRequest",
                        r -> Reply.json("{\"status\":\"500\",\"error\":{\"code\":\"" + SECRET
                                + " with spaces\",\"message\":\"" + PHONE + "\"}}"));
            },
            () -> {
                routes.put(
                        "POST /pesapalv3/api/Transactions/SubmitOrderRequest",
                        r -> Reply.json("not json " + SECRET + PHONE));
            }
        };
        for (Runnable shape : shapes) {
            shape.run();
            clock.set(clock.get().plusSeconds(120));
            try {
                fresh().createOrder(Scope.TENANT, order());
                throw new AssertionError("expected a failure");
            } catch (GatewayException e) {
                failures.add(e);
            }
        }
        failures.add(new GatewayException(Kind.REFUSED, 200, SECRET + " has spaces"));
        for (Throwable t : failures) {
            for (String secret : List.of(KEY, SECRET, PHONE, "FAB.TOKEN", "700000123")) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(secret);
                assertThat(t.toString()).doesNotContain(secret);
                assertThat(t.getCause()).isNull();
            }
        }
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage();
            for (String secret : List.of(KEY, SECRET, PHONE, "FAB.TOKEN", "700000123", "Bearer")) {
                assertThat(line).as("log line").doesNotContain(secret);
            }
        }
        assertThat(gateway.toString()).doesNotContain(KEY).doesNotContain(SECRET);
        assertThat(order().toString()).doesNotContain(PHONE);
    }

    @Test
    void aCodeFromTheProviderOutsideTheSafeAlphabetIsNeverCarried() {
        assertThat(new GatewayException(Kind.REFUSED, 200, "has spaces and <tags>").code())
                .isEqualTo("unspecified");
        assertThat(new GatewayException(Kind.REFUSED, 200, "x".repeat(65)).code())
                .isEqualTo("unspecified");
        assertThat(new GatewayException(Kind.REFUSED, 200, null).code()).isEqualTo("unspecified");
        assertThat(new GatewayException(Kind.REFUSED, 200, "INVALID_REFERENCE_02").code())
                .isEqualTo("INVALID_REFERENCE_02");
    }
}
