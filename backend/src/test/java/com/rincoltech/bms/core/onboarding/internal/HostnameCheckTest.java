package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The hostname check on the Activate form (issue #155): a real HTTP server on the loopback stands in for the business's own
 * address, so each outcome is proven against an actual answer, not a mock of the HTTP client.
 */
class HostnameCheckTest {

    /** Tenant hosts under a fabricated pattern; the origin points at the test server. */
    static final class Hosts implements PlatformHost {
        String origin = "http://127.0.0.1:1";
        final List<String> asked = new ArrayList<>();

        @Override
        public boolean servesPlatform(String host) {
            return false;
        }

        @Override
        public boolean servesTenant(String host) {
            return false;
        }

        @Override
        public boolean isValidSlug(String slug) {
            return slug.matches("^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])$");
        }

        @Override
        public String tenantOrigin(String slug) {
            asked.add(slug);
            return origin;
        }

        @Override
        public String platformOrigin() {
            return "http://localhost";
        }
    }

    final Hosts hosts = new Hosts();
    final HostnameCheck check = new HostnameCheck(hosts, new ObjectMapper(), Duration.ofSeconds(3));
    HttpServer server;

    /** A server whose {@code /version} answers with this status and body. */
    void serve(int status, String contentType, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            if (status == 302) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        hosts.origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void thisAppsVersionAnswerMeansTheAddressAnswers() throws IOException {
        serve(200, "application/json", "{\"version\":\"0.1.0\",\"git_sha\":\"abc1234\"}");

        HostnameCheck.Result result = check.check("test-shop-one");

        assertThat(result.outcome()).isEqualTo("answers");
        assertThat(result.host()).isEqualTo("127.0.0.1");
        assertThat(result.httpStatus()).isNull();
        assertThat(hosts.asked).containsExactly("test-shop-one");
    }

    @Test
    void aNotFoundPageIsAWrongAnswerWithItsStatus() throws IOException {
        // The tunnel's catch-all rule answers 404 for a host it has no entry for.
        serve(404, "text/plain", "Not Found");

        assertThat(check.check("test-shop-one")).isEqualTo(new HostnameCheck.Result("127.0.0.1", "wrong_answer", 404));
    }

    @Test
    void anotherSiteAnsweringIsAWrongAnswer() throws IOException {
        serve(200, "text/html", "<html><body>Parked domain</body></html>");

        assertThat(check.check("test-shop-one").outcome()).isEqualTo("wrong_answer");
    }

    @Test
    void aRedirectIsNotFollowed() throws IOException {
        serve(302, "text/plain", "");

        assertThat(check.check("test-shop-one")).isEqualTo(new HostnameCheck.Result("127.0.0.1", "wrong_answer", 302));
    }

    @Test
    void nothingListeningIsNoAnswer() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        hosts.origin = "http://127.0.0.1:" + port;

        assertThat(check.check("test-shop-one").outcome()).isEqualTo("no_answer");
    }

    @Test
    void aNameWithNoDnsRecordIsNotFound() {
        // RFC 6761: .invalid never resolves.
        hosts.origin = "http://test-shop-one-bms-staging.invalid";

        HostnameCheck.Result result = check.check("test-shop-one");

        assertThat(result.host()).isEqualTo("test-shop-one-bms-staging.invalid");
        assertThat(result.outcome()).isEqualTo("not_found");
    }

    // JUSTIFICATION-A3: a new test case for must-fix 1 of the #787 review, a stalled body cut off by the one deadline.
    @Test
    void headersThenAStalledBodyIsNoAnswerWithinTheDeadline() throws IOException {
        ExecutorService handlers = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write("{\"git_sha\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        hosts.origin = "http://127.0.0.1:" + server.getAddress().getPort();
        HostnameCheck quick = new HostnameCheck(hosts, new ObjectMapper(), Duration.ofSeconds(1));

        long start = System.nanoTime();
        HostnameCheck.Result result = quick.check("test-shop-one");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.outcome()).isEqualTo("no_answer");
        assertThat(took).isLessThan(Duration.ofSeconds(5));
        handlers.shutdownNow();
    }

    @Test
    void anUnresolvedAddressIsTellableFromAFailedConnection() {
        assertThat(HostnameCheck.unresolved(new java.net.ConnectException())).isFalse();
        java.net.ConnectException unresolved = new java.net.ConnectException();
        unresolved.initCause(new java.nio.channels.UnresolvedAddressException());
        assertThat(HostnameCheck.unresolved(unresolved)).isTrue();
        assertThat(HostnameCheck.unresolved(new java.net.UnknownHostException("test.invalid")))
                .isTrue();
    }

    @Test
    void anInvalidSlugIsRefusedBeforeAnythingIsAsked() {
        for (String slug : new String[] {"", "-bad", "a", "has space", "x.example.test"}) {
            assertThatThrownBy(() -> check.check(slug))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo("invalid_slug");
        }
        assertThat(hosts.asked).isEmpty();
    }

    @Test
    void theSlugIsTrimmedAndLowerCased() throws IOException {
        serve(200, "application/json", "{\"git_sha\":\"abc1234\"}");

        assertThat(check.check("  Test-Shop-One ").outcome()).isEqualTo("answers");
        assertThat(hosts.asked).containsExactly("test-shop-one");
        assertThat(URI.create(hosts.origin).getHost()).isEqualTo("127.0.0.1");
    }
}
