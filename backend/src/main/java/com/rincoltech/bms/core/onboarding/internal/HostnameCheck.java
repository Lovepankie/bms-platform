package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
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
 * one deadline covers the DNS lookup, the connection and the body, at most {@link #MAX_BODY} bytes of the answer are read
 * before the connection is dropped, and only the outcome is returned (no exception text, no body).
 */
@Component
class HostnameCheck {

    static final int MAX_BODY = 4096;

    /** The DNS lookup, a seam so a test can stand in for a name with no record (review of #787). */
    interface Resolver {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

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

    /** Lookups run here, so a resolver that hangs never holds the request thread past the deadline. */
    private static final Executor LOOKUPS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("hostname-check-", 0).factory());

    private final PlatformHost hosts;
    private final ObjectMapper mapper;
    private final Resolver resolver;
    private final HttpClient http;
    private final Duration timeout;

    @Autowired
    HostnameCheck(PlatformHost hosts, ObjectMapper mapper) {
        this(hosts, mapper, Duration.ofSeconds(8), InetAddress::getAllByName);
    }

    HostnameCheck(PlatformHost hosts, ObjectMapper mapper, Duration timeout, Resolver resolver) {
        this.hosts = hosts;
        this.mapper = mapper;
        this.timeout = timeout;
        this.resolver = resolver;
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
        long deadline = System.nanoTime() + timeout.toNanos();
        // The lookup first, inside the deadline: a missing record is not_found, a resolver that hangs is no_answer. The
        // client then finds the name in the JVM's address cache.
        CompletableFuture<InetAddress[]> lookup = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return resolver.lookup(host);
                    } catch (UnknownHostException e) {
                        throw new UnresolvedAddressException();
                    }
                },
                LOOKUPS);
        Outcome<InetAddress[]> resolved = await(lookup, deadline);
        if (resolved.result == null) {
            return new Result(host, resolved.outcome, null);
        }
        HttpRequest request = HttpRequest.newBuilder(origin.resolve("/version"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        Outcome<HttpResponse<byte[]>> sent = await(http.sendAsync(request, info -> new FirstBytes(MAX_BODY)), deadline);
        if (sent.result == null) {
            return new Result(host, sent.outcome, null);
        }
        HttpResponse<byte[]> response = sent.result;
        if (response.statusCode() == 200 && isVersion(response.body())) {
            return new Result(host, "answers", null);
        }
        return new Result(host, "wrong_answer", response.statusCode());
    }

    private record Outcome<T>(T result, String outcome) {}

    /** Waits for {@code step} until the deadline: its result, or the outcome to report instead. */
    private static <T> Outcome<T> await(CompletableFuture<T> step, long deadline) {
        try {
            long left = Math.max(0, deadline - System.nanoTime());
            return new Outcome<>(step.get(left, TimeUnit.NANOSECONDS), null);
        } catch (TimeoutException e) {
            step.cancel(true);
            return new Outcome<>(null, "no_answer");
        } catch (ExecutionException e) {
            return new Outcome<>(null, unresolved(e.getCause()) ? "not_found" : "no_answer");
        } catch (InterruptedException e) {
            step.cancel(true);
            Thread.currentThread().interrupt();
            return new Outcome<>(null, "no_answer");
        }
    }

    /**
     * Keeps the first {@code limit} bytes of the body and then cancels the subscription, which drops the connection: a host
     * that streams a large body is not read to its end.
     */
    static final class FirstBytes implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        FirstBytes(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int take = Math.min(buffer.remaining(), limit - kept.size());
                byte[] bytes = new byte[take];
                buffer.get(bytes);
                kept.write(bytes, 0, take);
            }
            if (kept.size() >= limit) {
                subscription.cancel();
                body.complete(kept.toByteArray());
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(kept.toByteArray());
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
