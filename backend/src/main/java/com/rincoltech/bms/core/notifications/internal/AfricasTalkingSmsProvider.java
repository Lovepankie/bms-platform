package com.rincoltech.bms.core.notifications.internal;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Africa's Talking over its REST messaging endpoint (ADR-036 decision 1, spec section 4.3): a form
 * POST of the account username, the recipient, the message and an optional sender id, the API key in
 * the {@code apiKey} header, the JSON answer read per recipient (a status code, a message id and a
 * cost string such as {@code UGX 32.0000}). There is no idempotency key, so the adapter decides
 * between "certainly not sent" and "maybe sent":
 *
 * <ul>
 *   <li>a connection that could not be made, a TLS failure, or an error answer (a gateway or
 *       authentication error, no balance) is {@link Transient}: a retry is safe;
 *   <li>a request written and then no usable answer (the total time ran out, the connection broke,
 *       an unreadable 2xx, a 502 or 504 from a proxy in front) is {@link Ambiguous}: the row is
 *       recorded unknown and never retried by the machine.
 * </ul>
 *
 * The total time per message, connection and answer together, is {@link #TOTAL_TIMEOUT}, a test
 * keeps it under a quarter of the outbox lease so a slow provider cannot hand a second sender the
 * same row. The API key, the recipient and the text never appear in a log line or an exception.
 * Provider facts here come from its public reference and are confirmed against the sandbox by the
 * manual contract job (spec section 8).
 */
final class AfricasTalkingSmsProvider implements SmsProvider {

    static final String NAME = "africastalking";
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(15);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern MESSAGE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,100}$");

    private final URI endpoint;
    private final String username;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private final Duration total;

    AfricasTalkingSmsProvider(URI base, String username, String apiKey, ObjectMapper mapper) {
        this(base, username, apiKey, mapper, TOTAL_TIMEOUT);
    }

    /** The total time is a parameter only so a test can cut a slow server off in a fraction of a second. */
    AfricasTalkingSmsProvider(URI base, String username, String apiKey, ObjectMapper mapper, Duration total) {
        this.endpoint = URI.create(base + "/version1/messaging");
        this.username = username;
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.total = total;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome send(String to, String body, String senderId, String clientRef) throws Transient, Ambiguous {
        StringBuilder form = new StringBuilder()
                .append("username=")
                .append(enc(username))
                .append("&to=")
                .append(enc(to))
                .append("&message=")
                .append(enc(body));
        if (senderId != null && !senderId.isBlank()) {
            form.append("&from=").append(enc(senderId));
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(total)
                .header("apiKey", apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            // The request timeout covers the answer's headers; this bounds connection, answer and body together.
            response = call.get(total.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new Ambiguous("provider_timeout");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new Ambiguous("provider_interrupted");
        } catch (ExecutionException e) {
            return classify(e.getCause());
        }
        return read(response.statusCode(), response.body());
    }

    /** A failure before the request could have been written is clean; any other may have reached the provider. */
    static Outcome classify(Throwable cause) throws Transient, Ambiguous {
        if (cause instanceof ConnectException
                || cause instanceof HttpConnectTimeoutException
                || cause instanceof UnknownHostException
                || cause instanceof UnresolvedAddressException
                // Only a failed handshake is certainly before the request was written; an SSL error while
                // reading the answer may follow a message the provider already took.
                || cause instanceof SSLHandshakeException) {
            throw new Transient("provider_unreachable");
        }
        throw new Ambiguous(cause instanceof IOException ? "provider_io_error" : "provider_error");
    }

    Outcome read(int http, String body) throws Transient, Ambiguous {
        if (http == 401 || http == 403) {
            throw new Transient("provider_auth_failed");
        }
        if (http == 429 || http == 503) {
            throw new Transient("provider_http_" + http);
        }
        // 500 comes after the request was written: the provider may have queued the message first.
        if (http == 500 || http == 502 || http == 504) {
            throw new Ambiguous("provider_http_" + http);
        }
        if (http == 400) {
            return rejected("provider_http_400");
        }
        if (http == 409) {
            // Do Not Disturb: the recipient does not take messages; final.
            return rejected("provider_dnd");
        }
        if (http / 100 != 2) {
            return rejected("provider_http_" + http);
        }
        JsonNode recipient;
        try {
            JsonNode recipients = mapper.readTree(body).path("SMSMessageData").path("Recipients");
            if (!recipients.isArray()) {
                throw new Ambiguous("provider_unreadable_answer");
            }
            if (recipients.isEmpty()) {
                return rejected("provider_no_recipient");
            }
            recipient = recipients.get(0);
        } catch (RuntimeException e) {
            throw new Ambiguous("provider_unreadable_answer");
        }
        int code = recipient.path("statusCode").asInt(-1);
        return switch (code) {
            case 100, 101, 102 -> {
                String id = recipient.path("messageId").asString("");
                JsonNode parts = recipient.path("messageParts");
                String cost = recipient.path("cost").asString("");
                yield new Outcome(
                        NAME,
                        Status.ACCEPTED,
                        MESSAGE_ID.matcher(id).matches() ? id : null,
                        parts.isInt() && parts.asInt() > 0 ? parts.asInt() : null,
                        cost.isBlank() || cost.length() > 40 ? null : cost,
                        null);
            }
            // The provider's per recipient codes, each mapped on purpose. 100 Processed, 101 Sent, 102 Queued: taken.
            // Refused for good, nothing sent: 401 RiskHold, 402 InvalidSenderId, 403 InvalidPhoneNumber,
            // 404 UnsupportedNumberType, 406 UserInBlacklist, 407 CouldNotRoute, 502 RejectedByGateway.
            case 401, 402, 403, 404, 406, 407, 502 -> rejected("provider_" + code);
            // 409 DoNotDisturbRejection: the recipient does not take messages; final, not retried and not unknown.
            case 409 -> rejected("provider_dnd");
            // 405 InsufficientBalance: refused before sending; a retry after a top up is safe.
            case 405 -> throw new Transient("provider_insufficient_balance");
            // A gateway error after the request was taken: the message may still go.
            case 500, 501 -> throw new Ambiguous("provider_" + code);
            default -> throw new Ambiguous("provider_unknown_status");
        };
    }

    private static Outcome rejected(String reason) {
        return new Outcome(NAME, Status.REJECTED, null, null, null, reason);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
