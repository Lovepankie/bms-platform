package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link PushSender} (ADR-050): RFC 8291 encryption, a VAPID header (RFC 8292) and one HTTPS POST to the push
 * service the subscription names. Redirects are never followed (a push service that redirects is not one this server trusts
 * with a signed request), the call has a ten second budget, and the endpoint is checked against the allowed hosts again here.
 * The log carries the outcome only: never the endpoint, a key or the payload.
 */
final class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    private static final Duration BUDGET = Duration.ofSeconds(10);
    /** A VAPID token may live 24 hours at most; 12 leaves room for a clock that is off. */
    private static final long TOKEN_SECONDS = 12 * 3600L;

    private final VapidKeys keys;
    private final PushEndpoints endpoints;
    private final BusinessClock clock;
    private final HttpClient http;

    WebPushSender(VapidKeys keys, PushEndpoints endpoints, BusinessClock clock) {
        this.keys = keys;
        this.endpoints = endpoints;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Outcome send(Target target, String payload, int ttlSeconds) {
        if (!keys.available() || !endpoints.allowed(target.endpoint())) {
            return Outcome.REJECTED;
        }
        try {
            URI uri = URI.create(target.endpoint());
            String audience = uri.getScheme() + "://" + uri.getHost();
            String token = WebPushCrypto.vapidToken(
                    keys.privateKey(), audience, clock.now().getEpochSecond() + TOKEN_SECONDS, keys.subject());
            byte[] body = WebPushCrypto.encrypt(
                    payload.getBytes(StandardCharsets.UTF_8),
                    WebPushCrypto.decode(target.p256dh()),
                    WebPushCrypto.decode(target.auth()));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(BUDGET)
                    .header("Authorization", "vapid t=" + token + ", k=" + keys.publicKeyText())
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", Integer.toString(Math.max(0, ttlSeconds)))
                    .header("Urgency", "normal")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            int status =
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return Outcome.SENT;
            }
            if (status == 404 || status == 410) {
                return Outcome.GONE;
            }
            if (status == 429 || status >= 500) {
                log.warn("push service answered {}: retry later", status);
                return Outcome.TRANSIENT;
            }
            log.warn("push service refused a subscription with {}", status);
            return Outcome.REJECTED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.TRANSIENT;
        } catch (IOException e) {
            log.warn("push service not reachable: {}", e.getClass().getSimpleName());
            return Outcome.TRANSIENT;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A key the browser gave us that cannot encrypt: this subscription can never work.
            log.warn("push subscription could not be used: {}", e.getClass().getSimpleName());
            return Outcome.REJECTED;
        }
    }
}
