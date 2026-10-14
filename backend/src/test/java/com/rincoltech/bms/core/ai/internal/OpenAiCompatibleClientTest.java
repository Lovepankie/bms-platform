package com.rincoltech.bms.core.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.ai.LlmException.Kind;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAI-compatible adapter against a local server (FR-AIA-05, spec 5.7): the request it sends, the
 * retries on 429 and 5xx with their pauses, the total budget, the refusal to follow a redirect, and
 * that nothing the provider says (an error body quoting a fabricated secret) reaches an exception. Time
 * is a fake: a pause advances the fake clock and sleeps nothing, so no test waits.
 */
class OpenAiCompatibleClientTest {

    static final String KEY = "test-key-0000-fabricated";
    static final String CANARY = "CANARY-PROVIDER-TEXT-9931";
    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String OK_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"summary\\\":\\\"Fine.\\\"}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30}}";

    HttpServer server;
    final ExecutorService handlers = Executors.newCachedThreadPool();
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final List<Reply> script = new CopyOnWriteArrayList<>();
    final AtomicInteger next = new AtomicInteger();
    final AtomicLong fakeNanos = new AtomicLong();
    final List<Duration> pauses = new ArrayList<>();
    /** The random share (0 to 1) the next pause adds; zero keeps the pauses exact. */
    double jitter = 0.0;

    record Seen(String authorization, String contentType, String body) {}

    record Reply(int status, String body, String retryAfter, long delayMillis) {
        Reply(int status, String body) {
            this(status, body, null, 0);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        // Handlers run on their own threads, so one that sleeps never holds up the server's own stop.
        server.setExecutor(handlers);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(
                ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("Content-Type"),
                body));
        Reply reply = script.get(Math.min(next.getAndIncrement(), script.size() - 1));
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (reply.retryAfter() != null) {
            ex.getResponseHeaders().add("Retry-After", reply.retryAfter());
        }
        try {
            ex.sendResponseHeaders(reply.status(), bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException gone) {
            // The client gave up (a timeout test); nothing to answer.
        } finally {
            ex.close();
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    OpenAiCompatibleClient client(String model, Duration budget) {
        return client(model, budget, fakeNanos::get);
    }

    OpenAiCompatibleClient client(String model, Duration budget, LongSupplier clock) {
        return new OpenAiCompatibleClient(
                OpenAiCompatibleClient.endpointOf(baseUrl()),
                KEY,
                model,
                budget,
                MAPPER,
                clock,
                pause -> {
                    pauses.add(pause);
                    fakeNanos.addAndGet(pause.toNanos());
                },
                () -> jitter);
    }

    static LlmClient.Request request() {
        return new LlmClient.Request(
                List.of(
                        new LlmClient.Message("system", "Answer in JSON about the supplied figures."),
                        new LlmClient.Message("user", "Fabricated figure: Branch 1 PAR30 is 4.1 percent.")),
                400,
                true);
    }

    // ---- the request and the answer -------------------------------------------------------------

    @Test
    void sendsTheConfiguredModelTheCapTheKeyAndAJsonFormat() throws Exception {
        script.add(new Reply(200, OK_BODY));
        LlmClient.Result result =
                client("example-cheap-chat", Duration.ofSeconds(30)).complete(request());

        assertThat(result.text()).isEqualTo("{\"summary\":\"Fine.\"}");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(30);
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).authorization()).isEqualTo("Bearer " + KEY);
        JsonNode sent = MAPPER.readTree(seen.get(0).body());
        assertThat(sent.get("model").asString()).isEqualTo("example-cheap-chat");
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(400);
        assertThat(sent.get("stream").asBoolean()).isFalse();
        assertThat(sent.get("temperature").asInt()).isZero();
        assertThat(sent.get("response_format").get("type").asString()).isEqualTo("json_object");
        assertThat(sent.get("messages")).hasSize(2);
        // The key is a header, never part of the body.
        assertThat(seen.get(0).body()).doesNotContain(KEY);
    }

    @Test
    void swappingTheModelNameIsAConfigurationChangeOnly() throws Exception {
        script.add(new Reply(200, OK_BODY));
        client("model-one", Duration.ofSeconds(30)).complete(request());
        client("model-two", Duration.ofSeconds(30)).complete(request());
        assertThat(MAPPER.readTree(seen.get(0).body()).get("model").asString()).isEqualTo("model-one");
        assertThat(MAPPER.readTree(seen.get(1).body()).get("model").asString()).isEqualTo("model-two");
    }

    @Test
    void aPlainTextRequestOmitsTheResponseFormat() throws Exception {
        script.add(new Reply(200, OK_BODY));
        client("m", Duration.ofSeconds(30))
                .complete(new LlmClient.Request(List.of(new LlmClient.Message("user", "Hello")), 50, false));
        assertThat(MAPPER.readTree(seen.get(0).body()).has("response_format")).isFalse();
    }

    @Test
    void withoutAUsageBlockTheTokensAreEstimatedSoTheCapsStillCount() throws Exception {
        script.add(new Reply(
                200, "{\"choices\":[{\"message\":{\"content\":\"abcdefghi\"},\"finish_reason\":\"length\"}]}"));
        LlmClient.Result result = client("m", Duration.ofSeconds(30)).complete(request());
        assertThat(result.inputTokens()).isEqualTo(request().characters() / 3);
        assertThat(result.outputTokens()).isEqualTo(3);
        assertThat(result.finishReason()).isEqualTo("length");
    }

    // ---- retries and pauses -----------------------------------------------------------------------

    @Test
    void aServerErrorIsRetriedWithADoublingPauseThenSucceeds() throws Exception {
        script.add(new Reply(503, "{\"error\":\"" + CANARY + "\"}"));
        script.add(new Reply(500, "{}"));
        script.add(new Reply(200, OK_BODY));
        LlmClient.Result result = client("m", Duration.ofSeconds(30)).complete(request());
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(pauses).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
        assertThat(seen).hasSize(3);
    }

    @Test
    void rateLimitingFollowsAShortRetryAfter() throws Exception {
        script.add(new Reply(429, "{}", "2", 0));
        script.add(new Reply(200, OK_BODY));
        client("m", Duration.ofSeconds(30)).complete(request());
        assertThat(pauses).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void aLongRetryAfterIsCappedAtTenSeconds() throws Exception {
        script.add(new Reply(429, "{}", "3600", 0));
        script.add(new Reply(200, OK_BODY));
        client("m", Duration.ofSeconds(30)).complete(request());
        assertThat(pauses).containsExactly(Duration.ofSeconds(10));
    }

    @Test
    void aRetryAfterOfZeroStillPausesForTheMinimum() throws Exception {
        script.add(new Reply(429, "{}", "0", 0));
        script.add(new Reply(429, "{}", "0", 0));
        script.add(new Reply(200, OK_BODY));
        client("m", Duration.ofSeconds(30)).complete(request());
        assertThat(pauses).containsExactly(Duration.ofMillis(250), Duration.ofMillis(250));
    }

    @Test
    void everyPauseCarriesUpToAQuarterOfRandomJitter() throws Exception {
        jitter = 1.0;
        script.add(new Reply(503, "{}"));
        script.add(new Reply(503, "{}"));
        script.add(new Reply(200, OK_BODY));
        client("m", Duration.ofSeconds(30)).complete(request());
        // 500 ms and 1000 ms, each plus the full quarter.
        assertThat(pauses).containsExactly(Duration.ofMillis(625), Duration.ofMillis(1250));
        // The share is clamped: a source that misbehaves cannot lengthen a pause beyond a quarter.
        pauses.clear();
        jitter = 7.5;
        assertThat(client("m", Duration.ofSeconds(30)).pauseBefore(1, null)).isEqualTo(Duration.ofMillis(625));
        jitter = -3.0;
        assertThat(client("m", Duration.ofSeconds(30)).pauseBefore(1, null)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void threeServerErrorsEndInAProviderErrorAfterThreeAttempts() {
        script.add(new Reply(502, "{\"error\":\"" + CANARY + "\"}"));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.PROVIDER_ERROR);
                    assertThat(e.httpStatus()).isEqualTo(502);
                    assertThat(e.attempts()).isEqualTo(3);
                    assertThat(e.kind().countsAgainstBreaker()).isTrue();
                });
        assertThat(seen).hasSize(3);
    }

    @Test
    void threeRateLimitsEndInRateLimited() {
        script.add(new Reply(429, "{}"));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
                    assertThat(e.attempts()).isEqualTo(3);
                });
    }

    @Test
    void aRejectedKeyIsNotRetried() {
        script.add(new Reply(401, "{\"error\":\"" + CANARY + "\"}"));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.AUTH);
                    assertThat(e.attempts()).isEqualTo(1);
                });
        assertThat(seen).hasSize(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void aBadRequestIsNotRetriedAndDoesNotCountAgainstTheBreaker() {
        script.add(new Reply(422, "{\"error\":\"" + CANARY + "\"}"));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST);
                    assertThat(e.kind().countsAgainstBreaker()).isFalse();
                });
        assertThat(seen).hasSize(1);
    }

    @Test
    void noProviderTextReachesAnExceptionOrItsStringForm() {
        script.add(new Reply(400, "{\"error\":{\"message\":\"" + CANARY + " " + KEY + "\"}}"));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(CANARY).doesNotContain(KEY);
                    assertThat(e.toString()).doesNotContain(CANARY).doesNotContain(KEY);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void anAnswerThatIsNotAChatCompletionIsABadResponse() {
        script.add(new Reply(200, "{\"choices\":[]," + "\"note\":\"" + CANARY + "\"}"));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).doesNotContain(CANARY);
                });
        script.clear();
        next.set(0);
        script.add(new Reply(200, "this is not json " + CANARY));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).doesNotContain(CANARY);
                });
    }

    @Test
    void aBadAnswerStillHandsBackTheUsageTheProviderBilled() {
        script.add(new Reply(
                200, "{\"choices\":[{\"message\":{}}],\"usage\":{\"prompt_tokens\":70,\"completion_tokens\":30}}"));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE);
                    assertThat(e.reportedInputTokens()).isEqualTo(70);
                    assertThat(e.reportedOutputTokens()).isEqualTo(30);
                });
        // Without a usage block there is nothing to hand back, and the caller keeps the reservation.
        script.clear();
        next.set(0);
        script.add(new Reply(200, "{\"choices\":[]}"));
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.reportedInputTokens()).isEqualTo(-1);
                    assertThat(e.reportedOutputTokens()).isEqualTo(-1);
                });
    }

    @Test
    void aRedirectIsNeverFollowedSoTheKeyCannotBeForwarded() throws Exception {
        HttpServer elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger hits = new AtomicInteger();
        elsewhere.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        elsewhere.start();
        try {
            server.removeContext("/v1/chat/completions");
            server.createContext("/v1/chat/completions", ex -> {
                ex.getResponseHeaders()
                        .add(
                                "Location",
                                "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            });
            assertThatThrownBy(() -> client("m", Duration.ofSeconds(30)).complete(request()))
                    .isInstanceOfSatisfying(
                            LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
            assertThat(hits).hasValue(0);
        } finally {
            elsewhere.stop(0);
        }
    }

    // ---- the budget ---------------------------------------------------------------------------------

    @Test
    void aPauseThatWouldUseTheRestOfTheBudgetStopsTheRetries() {
        script.add(new Reply(503, "{}"));
        // One second in all: the first pause (0.5 s) fits, the second (1 s) does not.
        assertThatThrownBy(() -> client("m", Duration.ofSeconds(1)).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.PROVIDER_ERROR);
                    assertThat(e.attempts()).isEqualTo(2);
                });
        assertThat(pauses).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void aSlowAnswerEndsInATimeoutWithinTheBudgetAndIsNotRetried() {
        // The server holds the answer far longer than any bound below, so the test cannot pass by being fast.
        script.add(new Reply(200, OK_BODY, null, 20_000));
        long started = System.nanoTime();
        assertThatThrownBy(() ->
                        client("m", Duration.ofMillis(300), System::nanoTime).complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TIMEOUT);
                    assertThat(e.attempts()).isEqualTo(1);
                });
        // Generous: the budget is 300 ms; a slow machine still comes in far under the server's 20 seconds.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertThat(pauses).isEmpty();
    }

    @Test
    void aClosedPortIsANetworkFailureRetriedThreeTimes() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        OpenAiCompatibleClient dead = new OpenAiCompatibleClient(
                URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"),
                KEY,
                "m",
                Duration.ofSeconds(30),
                MAPPER,
                fakeNanos::get,
                pause -> {
                    pauses.add(pause);
                    fakeNanos.addAndGet(pause.toNanos());
                },
                () -> 0.0);
        assertThatThrownBy(() -> dead.complete(request())).isInstanceOfSatisfying(LlmException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.NETWORK);
            assertThat(e.attempts()).isEqualTo(3);
            assertThat(e.getMessage()).doesNotContain("127.0.0.1").doesNotContain(String.valueOf(port));
        });
        assertThat(pauses).hasSize(2);
    }

    @Test
    void theBudgetIsNeverMoreThanThirtySecondsWhateverIsConfigured() {
        AiProperties huge = new AiProperties(
                "openai_compatible", null, null, null, 600, null, null, null, null, null, null, null, null, null, null);
        assertThat(huge.budgetSeconds()).isEqualTo(30);
        // Zero or less is a mistake and fails startup; it never becomes the default.
        assertThatThrownBy(() -> new AiProperties(
                        "openai_compatible",
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theEndpointIsTheBaseUrlWithTheChatPathAppended() {
        assertThat(OpenAiCompatibleClient.endpointOf("https://llm.example.test"))
                .hasToString("https://llm.example.test/chat/completions");
        assertThat(OpenAiCompatibleClient.endpointOf(" https://llm.example.test/v1/ "))
                .hasToString("https://llm.example.test/v1/chat/completions");
    }

    @Test
    void printingARequestOrAnAnswerShowsSizesNeverText() {
        LlmClient.Request request = request();
        assertThat(request.toString()).doesNotContain("Fabricated figure").doesNotContain("Answer in JSON");
        assertThat(new LlmClient.Result("secret answer text", 1, 2, "stop").toString())
                .doesNotContain("secret answer text");
        assertThat(new LlmClient.Message("user", "private words").toString()).doesNotContain("private words");
        assertThat(new AiProperties(
                                "openai_compatible",
                                "https://llm.example.test",
                                KEY,
                                "m",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .toString())
                .doesNotContain(KEY);
    }
}
