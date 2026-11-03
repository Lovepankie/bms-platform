package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Does the business's own address already answer (issue #155)? Before Activate the operator makes the tenant host reachable
 * by hand (docs/runbooks/onboard-tenant.md, "Make the tenant host reachable"); this asks that host for the public
 * {@code GET /version}, which is tenant free, so it answers before the tenant exists. Nothing in DNS or the tunnel is changed
 * here. The host is built from the slug and the tenant host pattern only, never from the request, redirects are not followed,
 * the whole exchange has one deadline, at most a few kilobytes of the answer are kept, and only the outcome is returned (no
 * exception text, no body).
 */
@Component
class HostnameCheck {

    static final int MAX_BODY = 4096;

    /** What the operator reads: the host asked and what happened. */
    @Schema(name = "HostnameCheck")
    record Result(
            @Schema(description = "The business's own host, from the slug and the tenant host pattern")
            String host,

            @Schema(
                    description = "answers: this app answered /version there; not_found: the name has no DNS record;"
                            + " no_answer: the name exists but nothing answered in time or the connection failed;"
                            + " wrong_answer: something answered, but not this app (http_status says what)")
            String outcome,

            @Schema(description = "The HTTP status of a wrong answer; null otherwise")
            Integer httpStatus) {}

    private final PlatformHost hosts;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration timeout;

    @Autowired
    HostnameCheck(PlatformHost hosts, ObjectMapper mapper) {
        this(hosts, mapper, Duration.ofSeconds(8));
    }

    HostnameCheck(PlatformHost hosts, ObjectMapper mapper, Duration timeout) {
        this.hosts = hosts;
        this.mapper = mapper;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                // HTTP/1.1 as the tunnel and the proxy serve it: no h2c upgrade on a plain http origin (review of
                // #787).
                .version(HttpClient.Version.HTTP_1_1)
                // Straight to the host, never through a JVM proxy setting, so a missing DNS record reads as one.
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    Result check(String rawSlug) {
        String slug = rawSlug == null ? "" : rawSlug.trim().toLowerCase(Locale.ROOT);
        if (!hosts.isValidSlug(slug)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_slug",
                    "Invalid address name",
                    "Use 3 to 63 lower case letters, digits and hyphens, not starting or ending with a hyphen.");
        }
        URI origin = URI.create(hosts.tenantOrigin(slug));
        return probe(origin);
    }

    Result probe(URI origin) {
        String host = origin.getHost();
        HttpRequest request = HttpRequest.newBuilder(origin.resolve("/version"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        // One deadline for the whole exchange, body included (review of #787): a host that sends headers and then
        // stalls or
        // trickles the body is cut off at the timeout and cancelled, never holding the request thread. Only the first
        // MAX_BODY bytes are kept, whatever the host sends.
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        CompletableFuture<HttpResponse<Void>> exchange = http.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk -> chunk.ifPresent(bytes -> keep(head, bytes))));
        HttpResponse<Void> response;
        try {
            response = exchange.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            return new Result(host, "no_answer", null);
        } catch (ExecutionException e) {
            return new Result(host, unresolved(e.getCause()) ? "not_found" : "no_answer", null);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            return new Result(host, "no_answer", null);
        }
        byte[] body;
        synchronized (head) {
            body = head.toByteArray();
        }
        if (response.statusCode() == 200 && isVersion(body)) {
            return new Result(host, "answers", null);
        }
        return new Result(host, "wrong_answer", response.statusCode());
    }

    private static void keep(ByteArrayOutputStream head, byte[] bytes) {
        synchronized (head) {
            int room = MAX_BODY - head.size();
            if (room > 0) {
                head.write(bytes, 0, Math.min(room, bytes.length));
            }
        }
    }

    /** This app's {@code /version}: a JSON object with a git_sha. */
    private boolean isVersion(byte[] body) {
        try {
            JsonNode json = mapper.readTree(body);
            return json != null && json.isObject() && json.path("git_sha").isString();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when the name itself could not be found: no DNS record. */
    static boolean unresolved(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return true;
            }
        }
        return false;
    }
}
