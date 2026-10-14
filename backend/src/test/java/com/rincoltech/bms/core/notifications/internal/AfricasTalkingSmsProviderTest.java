package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.notifications.internal.SmsProvider.Ambiguous;
import com.rincoltech.bms.core.notifications.internal.SmsProvider.Outcome;
import com.rincoltech.bms.core.notifications.internal.SmsProvider.Status;
import com.rincoltech.bms.core.notifications.internal.SmsProvider.Transient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The Africa's Talking adapter against a local HTTP stub that replays fabricated answers (spec
 * section 8: no network, no real account). The request shape, the answer mapping, the line between a
 * clean failure and an ambiguous one, the total time cap, and that no key, number or text ever
 * reaches an exception message.
 */
class AfricasTalkingSmsProviderTest {

    static final String KEY = "test-api-key-0001";
    static final String TO = "+256700000001";
    static final String BODY = "Test Lender: Deposit of UGX 5,000. Secret-words-here";

    record Seen(String method, String path, Map<String, String> headers, String body) {}

    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();

    URI start(Consumer<HttpExchange> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.of(
                            "apikey",
                                    String.valueOf(exchange.getRequestHeaders().getFirst("apiKey")),
                            "content-type",
                                    String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                            "accept",
                                    String.valueOf(exchange.getRequestHeaders().getFirst("Accept"))),
                    body));
            handler.accept(exchange);
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static void reply(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String answer(int statusCode, String status, String cost, String id) {
        return """
                {"SMSMessageData":{"Message":"Sent to 1/1 Total Cost: %s","Recipients":[
                  {"statusCode":%d,"number":"+256700000001","cost":"%s","status":"%s","messageId":"%s"}]}}
                """.formatted(cost, statusCode, cost, status, id);
    }

    AfricasTalkingSmsProvider provider(URI base) {
        return new AfricasTalkingSmsProvider(base, "sandbox", KEY, new ObjectMapper());
    }

    Outcome send(URI base) throws Transient, Ambiguous {
        return provider(base).send(TO, BODY, null, "key-1");
    }

    /** What an exception may carry: a short code, never the key, the number or the text. */
    static void assertSafe(Exception e) {
        assertThat(e.getMessage())
                .matches("^[a-z][a-z0-9_]*$")
                .doesNotContain(KEY)
                .doesNotContain("700000001");
        assertThat(e.toString()).doesNotContain(KEY).doesNotContain("700000001").doesNotContain("Secret");
    }

    @Test
    void theRequestIsAFormPostWithTheKeyInTheHeader() throws Exception {
        URI base = start(e -> reply(e, 201, answer(101, "Success", "UGX 32.0000", "ATXid_test0001")));
        Outcome outcome = provider(base).send(TO, BODY, "TESTCO", "key-1");

        assertThat(seen).hasSize(1);
        Seen request = seen.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/version1/messaging");
        assertThat(request.headers()).containsEntry("apikey", KEY).containsEntry("accept", "application/json");
        assertThat(request.headers().get("content-type")).startsWith("application/x-www-form-urlencoded");
        assertThat(request.body())
                .contains("username=sandbox")
                .contains("to=%2B256700000001")
                .contains("from=TESTCO")
                .contains("message=Test+Lender%3A+Deposit+of+UGX+5%2C000.+Secret-words-here");
        // The key is a header, never in the body or the URL.
        assertThat(request.body()).doesNotContain(KEY);
        assertThat(outcome.status()).isEqualTo(Status.ACCEPTED);
        assertThat(outcome.provider()).isEqualTo("africastalking");
        assertThat(outcome.messageId()).isEqualTo("ATXid_test0001");
        assertThat(outcome.costRaw()).isEqualTo("UGX 32.0000");
        // No sender id: the shared sender, no `from` at all.
        seen.clear();
        provider(base).send(TO, BODY, null, "key-2");
        assertThat(seen.getFirst().body()).doesNotContain("from=");
    }

    @Test
    void statusCodesMapToAcceptedRejectedOrFailure() throws Exception {
        for (int code : new int[] {100, 101, 102}) {
            URI base = start(e -> reply(e, 201, answer(code, "Success", "UGX 32.0000", "ATXid_x")));
            assertThat(send(base).status()).as("code %d", code).isEqualTo(Status.ACCEPTED);
            stop();
        }
        for (int code : new int[] {401, 402, 403, 404, 406, 407, 502}) {
            URI base = start(e -> reply(e, 201, answer(code, "Failed", "None", "None")));
            Outcome outcome = send(base);
            assertThat(outcome.status()).as("code %d", code).isEqualTo(Status.REJECTED);
            assertThat(outcome.failureReason()).isEqualTo("provider_" + code);
            assertThat(outcome.messageId()).isNull();
            stop();
        }
        // Do Not Disturb arrives as a per recipient code inside a 2xx answer: final, not unknown.
        URI dndInside = start(e -> reply(e, 201, answer(409, "DoNotDisturbRejection", "None", "None")));
        Outcome dnd = send(dndInside);
        assertThat(dnd.status()).isEqualTo(Status.REJECTED);
        assertThat(dnd.failureReason()).isEqualTo("provider_dnd");
        assertThat(dnd.messageId()).isNull();
        stop();
        // No balance: certainly not sent, so a retry is safe.
        URI noBalance = start(e -> reply(e, 201, answer(405, "Failed", "None", "None")));
        assertThatThrownBy(() -> send(noBalance))
                .isInstanceOf(Transient.class)
                .satisfies(e -> assertSafe((Exception) e));
        stop();
        // An internal or gateway error after the request was taken: the message may still go, so no retry.
        for (int code : new int[] {500, 501}) {
            URI base = start(e -> reply(e, 201, answer(code, "Failed", "None", "None")));
            assertThatThrownBy(() -> send(base)).as("code %d", code).isInstanceOf(Ambiguous.class);
            stop();
        } // A code we do not know in an answer that otherwise looks right: do not retry.
        URI base = start(e -> reply(e, 201, answer(999, "Strange", "None", "None")));
        assertThatThrownBy(() -> send(base)).isInstanceOf(Ambiguous.class);
    }

    @Test
    void httpStatusesSplitIntoCleanAndAmbiguousFailures() throws Exception {
        for (int http : new int[] {401, 403, 429, 503}) {
            URI base = start(e -> reply(e, http, "{}"));
            assertThatThrownBy(() -> send(base)).as("http %d", http).isInstanceOf(Transient.class);
            stop();
        }
        // A proxy that timed out in front of the provider: the provider may have taken it.
        for (int http : new int[] {500, 502, 504}) {
            URI base = start(e -> reply(e, http, "{}"));
            assertThatThrownBy(() -> send(base)).as("http %d", http).isInstanceOf(Ambiguous.class);
            stop();
        }
        // Do Not Disturb: final, not retried.
        URI dnd = start(e -> reply(e, 409, "Do Not Disturb"));
        Outcome refused = send(dnd);
        assertThat(refused.status()).isEqualTo(Status.REJECTED);
        assertThat(refused.failureReason()).isEqualTo("provider_dnd");
        stop();
        URI base = start(e -> reply(e, 400, "The request is malformed"));
        Outcome rejected = send(base);
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
        assertThat(rejected.failureReason()).isEqualTo("provider_http_400");
    }

    @Test
    void anAnswerWeCannotReadAfterASuccessStatusIsAmbiguousAndNoRecipientIsRejected() throws Exception {
        URI html = start(e -> reply(e, 200, "<html>ok</html>"));
        assertThatThrownBy(() -> send(html)).isInstanceOf(Ambiguous.class).satisfies(e -> assertSafe((Exception) e));
        stop();
        URI empty = start(
                e -> reply(e, 201, "{\"SMSMessageData\":{\"Message\":\"InvalidPhoneNumber\",\"Recipients\":[]}}"));
        Outcome none = send(empty);
        assertThat(none.status()).isEqualTo(Status.REJECTED);
        assertThat(none.failureReason()).isEqualTo("provider_no_recipient");
        stop();
        URI noArray = start(e -> reply(e, 201, "{\"SMSMessageData\":{}}"));
        assertThatThrownBy(() -> send(noArray)).isInstanceOf(Ambiguous.class);
    }

    /** The message id is written to a log and a column: only the plain id shape is kept. */
    @Test
    void aStrangeMessageIdOrCostIsDroppedNotStored() throws Exception {
        URI base = start(e -> reply(e, 201, answer(101, "Success", "UGX 32.0000", "id with spaces and <tags>")));
        Outcome outcome = send(base);
        assertThat(outcome.status()).isEqualTo(Status.ACCEPTED);
        assertThat(outcome.messageId()).isNull();
    }

    /** Acceptance: an SMS call slower than the cap is cut off, as unknown, well inside the lease. */
    @Test
    void aSlowProviderIsCutOffAndTheOutcomeIsAmbiguous() throws Exception {
        URI base = start(e -> {
            try {
                Thread.sleep(5000);
                reply(e, 201, answer(101, "Success", "UGX 32.0000", "ATXid_late"));
            } catch (InterruptedException | RuntimeException ignored) {
                // the client has gone
            }
        });
        AfricasTalkingSmsProvider slow =
                new AfricasTalkingSmsProvider(base, "sandbox", KEY, new ObjectMapper(), Duration.ofMillis(400));
        long started = System.nanoTime();
        assertThatThrownBy(() -> slow.send(TO, BODY, null, "key-1"))
                .isInstanceOf(Ambiguous.class)
                .satisfies(e -> assertSafe((Exception) e));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void theRealCapIsUnderAQuarterOfTheLeaseSoASlowProviderCannotHandTheRowToAnotherSender() {
        assertThat(AfricasTalkingSmsProvider.TOTAL_TIMEOUT).isEqualTo(Duration.ofSeconds(15));
        assertThat(AfricasTalkingSmsProvider.TOTAL_TIMEOUT).isLessThan(OutboxDispatcher.LEASE.dividedBy(4));
        assertThat(AfricasTalkingSmsProvider.CONNECT_TIMEOUT).isLessThan(AfricasTalkingSmsProvider.TOTAL_TIMEOUT);
    }

    /** An SSL error while reading the answer may follow a message the provider took; only a failed handshake is clean. */
    @Test
    void onlyAFailedHandshakeIsCleanAmongTheSslErrors() {
        assertThatThrownBy(() -> AfricasTalkingSmsProvider.classify(new javax.net.ssl.SSLHandshakeException("x")))
                .isInstanceOf(Transient.class);
        assertThatThrownBy(() -> AfricasTalkingSmsProvider.classify(new javax.net.ssl.SSLException("Connection reset")))
                .isInstanceOf(Ambiguous.class);
        assertThatThrownBy(() -> AfricasTalkingSmsProvider.classify(new java.net.http.HttpTimeoutException("read")))
                .isInstanceOf(Ambiguous.class);
        assertThatThrownBy(() -> AfricasTalkingSmsProvider.classify(new java.net.ConnectException("refused")))
                .isInstanceOf(Transient.class);
    }

    @Test
    void aConnectionThatCannotBeMadeIsCleanButOneThatBreaksAfterTheRequestIsAmbiguous() throws Exception {
        // A port nothing listens on: the request never left.
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        assertThatThrownBy(() -> send(URI.create("http://127.0.0.1:" + port)))
                .isInstanceOf(Transient.class)
                .satisfies(e -> assertSafe((Exception) e));
        // The server reads the request and drops the connection without an answer.
        URI base = start(HttpExchange::close);
        assertThatThrownBy(() -> send(base)).isInstanceOf(Ambiguous.class).satisfies(e -> assertSafe((Exception) e));
    }
}
