package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.EmailAttachments;
import com.rincoltech.bms.kernel.BusinessClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Email through the Resend HTTP API (issue #333, ADR-117), the other choice beside SMTP behind the same outbox channel
 * ({@code email}): on while {@code BMS_MAIL_PROVIDER=resend} and {@code BMS_RESEND_API_KEY} and {@code BMS_MAIL_FROM} are
 * set. Only one of the two email senders is ever on, and the dispatcher keeps only the senders that are on before it
 * picks one by name, so which bean comes first never matters. The message is the one {@link SmtpEmailChannel} builds (the
 * same plain and HTML parts, the same attachment, the business's name as the from-name, the same staging allow-list),
 * sent as one {@code POST /emails}.
 *
 * <p>Resend's {@code Idempotency-Key} is the environment, the outbox row id and the row's key
 * ({@code bms:<environment>:<row id>:<key>}), so staging and production, which share the Resend account, never share a key, and a retry after a lost answer is never a
 * second email (Resend keeps a key for 24 hours). Every failure to get an answer is a clean failure the dispatcher retries.
 * A {@code 429} gives the attempt back and waits; a request Resend refuses as invalid ({@code 400}, {@code 422}) or too
 * large ({@code 413}) is refused and not retried, except that a {@code 422} naming the From of a business is sent once more
 * with the platform's From; a {@code 409} for the same key with another body (an earlier try reached Resend, then the
 * business's name changed) is refused ({@code resend_payload_changed}), never retried, since the first one may have been
 * delivered. The row id is also the tag {@code outbox_id}. The API key travels only in the {@code Authorization} header and is never logged; no
 * recipient, text or answer body is logged or stored.
 */
@Component
class ResendEmailChannel implements OutboxChannel {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailChannel.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** The time for a request with no attachment; a larger body gets one more second per 256 KB, up to the cap. */
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(20);
    /** Below the dispatcher's two minute lease, so a slow upload never overlaps a second claim of the same row. */
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(90);
    /** The least time left in the budget for the from-name's second request to be tried at all. */
    static final Duration MIN_SECOND_TRY = Duration.ofSeconds(5);
    /** Resend's limit on a whole email with its attachments once encoded; a larger one is refused before it is sent. */
    static final long MAX_ENCODED_BYTES = 40L * 1024 * 1024;
    /** The {@code from} field named in an error message, in backticks or quotes. */
    static final java.util.regex.Pattern FROM_FIELD = java.util.regex.Pattern.compile("[`'\"]from[`'\"]");
    /** How long a {@code 429} waits when Resend does not say: its default limit is a few requests a second. */
    static final Duration RATE_LIMIT_WAIT = Duration.ofSeconds(30);

    private final SmtpEmailChannel email;
    private final NotificationProperties.Smtp smtp;
    private final NotificationProperties.Mail mail;
    private final ObjectMapper mapper;
    private final BusinessClock clock;
    private final String keyPrefix;
    private final String envTag;
    private final Duration baseTimeout;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    ResendEmailChannel(
            SmtpEmailChannel email,
            NotificationProperties properties,
            ObjectMapper mapper,
            BusinessClock clock,
            @Value("${bms.environment:}") String environment) {
        this(email, properties, mapper, clock, environment, CONNECT_TIMEOUT, TOTAL_TIMEOUT);
    }

    /** As above with other timeouts, for a test that must not wait twenty seconds. */
    ResendEmailChannel(
            SmtpEmailChannel email,
            NotificationProperties properties,
            ObjectMapper mapper,
            BusinessClock clock,
            String environment,
            Duration connectTimeout,
            Duration baseTimeout) {
        this.email = email;
        this.smtp = properties.smtp();
        this.mail = properties.mail();
        this.mapper = mapper;
        this.clock = clock;
        // At most 20 characters of the environment, so a long value can never push the row id out of the key.
        this.envTag = environmentTag(environment);
        this.keyPrefix = "bms:" + envTag + ":";
        this.baseTimeout = baseTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /**
     * {@code BMS_ENVIRONMENT} as the idempotency key and the {@code env} tag carry it: lower case, letters, digits and
     * dashes, at most 20 characters, {@code local} when unset. The webhook drops events whose tag differs.
     */
    static String environmentTag(String environment) {
        String env = environment == null || environment.isBlank()
                ? "local"
                : environment.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        return env.length() > 20 ? env.substring(0, 20) : env;
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public boolean enabled() {
        return mail.resendConfigured(smtp);
    }

    @Override
    public Result send(Delivery delivery) throws Exception {
        // The staging allow-list and the suppressed addresses, as the SMTP sender checks them (ADR-117 decisions 5, 9).
        String refused = email.refusal(delivery.recipient());
        if (refused != null) {
            return Result.refused(refused);
        }
        RowContext context = delivery.context() == null ? RowContext.NONE : delivery.context();
        SmtpEmailChannel.Body body = email.body(delivery, email.brand(context));
        EmailAttachments.Attachment attachment = email.attachment(context);
        if (attachment != null && (attachment.bytes().length + 2L) / 3 * 4 > MAX_ENCODED_BYTES) {
            // Over Resend's limit once encoded: refused here, never uploaded to be refused or to time out.
            return Result.refused("resend_too_large");
        }
        String from = email.sender(context);
        String key = idempotencyKey(delivery, context);
        // Both requests of one send share one budget, so the two together stay inside the dispatcher's lease.
        Instant started = clock.now();
        Answer answer;
        try {
            answer = post(from, delivery, body, attachment, context, key, MAX_TIMEOUT);
        } catch (SendFailure e) {
            // Resend could not even be reached (no connection, so it cannot have sent anything): with SMTP still set on
            // the host, the same email goes that way now. Any other failure is retried as before, since Resend may
            // already hold it and SMTP has no idempotency key.
            if ("resend_unreachable".equals(e.getMessage()) && smtp.configured()) {
                log.warn("outbox row {}: resend unreachable; sent by smtp instead", context.outboxId());
                // The refusals were checked above; the dispatcher records that SMTP carried it.
                Result bySmtp = email.transmit(delivery);
                return bySmtp instanceof Result.Sent ? Result.sentVia("smtp") : bySmtp;
            }
            throw e;
        }
        Duration left = MAX_TIMEOUT.minus(Duration.between(started, clock.now()));
        if (answer.status() == 422
                && !from.equals(smtp.from())
                && answer.namesFrom()
                && left.compareTo(MIN_SECOND_TRY) >= 0) {
            // The business's name as the from-name was refused: send once more with the platform's From, under a key of
            // its own (the body differs, so the first key would answer 409).
            log.warn(
                    "outbox row {}: resend refused the business from-name; sent with the platform's",
                    context.outboxId());
            answer = post(smtp.from(), delivery, body, attachment, context, key + ":platform-from", left);
        }
        Result result = read(answer, clock);
        if (result instanceof Result.Sent) {
            log.info("outbox email accepted by resend: outbox={} id={}", context.outboxId(), answer.id());
        }
        return result;
    }

    /**
     * Resend's idempotency key: {@code bms:<environment>:<outbox row id>:<row key>}, the same on every try of one row and
     * never the same for two rows or two environments. Cut to 240 characters, so with the {@code :platform-from} suffix it
     * stays inside Resend's 256; the environment (at most 20) and the row id come first, so the cut only ever shortens the
     * row key. A delivery without a row id is refused: every row the dispatcher sends has one.
     */
    String idempotencyKey(Delivery delivery, RowContext context) throws SendFailure {
        if (context.outboxId() == null) {
            throw new SendFailure("resend_no_row_id");
        }
        String key = keyPrefix + context.outboxId() + ":" + delivery.idempotencyKey();
        return key.length() <= 240 ? key : key.substring(0, 240);
    }

    /** The whole time for one request: the base, one more second per 256 KB of body, at most {@link #MAX_TIMEOUT}. */
    Duration timeoutFor(int bodyChars) {
        Duration scaled = baseTimeout.plusSeconds(bodyChars / (256L * 1024));
        return scaled.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : scaled;
    }

    /** What Resend answered: the status, its error name, whether the error is about the From, the email's id. */
    record Answer(int status, String errorName, boolean namesFrom, String id, Duration retryAfter) {}

    private Answer post(
            String from,
            Delivery delivery,
            SmtpEmailChannel.Body body,
            EmailAttachments.Attachment attachment,
            RowContext context,
            String idempotencyKey,
            Duration budget)
            throws SendFailure {
        String json = mapper.writeValueAsString(request(from, delivery, body, attachment, context, envTag));
        Duration scaled = timeoutFor(json.length());
        Duration timeout = scaled.compareTo(budget) > 0 ? budget : scaled;
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(mail.resendUrl()) + "/emails"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + mail.resendApiKey())
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            // One clock for the answer: the request's own timeout; this outer wait is only a backstop a second later.
            response = call.get(timeout.plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new SendFailure("resend_timeout");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new SendFailure("resend_interrupted");
        } catch (ExecutionException e) {
            throw new SendFailure(failure(e.getCause()));
        }
        return answer(
                response.statusCode(),
                response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    // JUSTIFICATION-A3: the no-request-reached-Resend test the #759 lead set for the SMTP fallback (third review, 3).
    /**
     * The label of a request that got no answer (ADR-117 decisions 1 and 3). {@code resend_unreachable} only when no
     * request can have reached Resend: the connection was refused, had no route or name ({@link java.net.ConnectException}),
     * timed out while connecting, or its TLS handshake failed, all before a byte of the request was sent. Only this label
     * falls back to SMTP. A request sent and not answered in time is {@code resend_timeout}; a connection lost once made
     * ({@code resend_connection_lost}) may have delivered the request. Both are retried under the idempotency key, never
     * sent by SMTP.
     */
    static String failure(Throwable cause) {
        if (cause instanceof HttpConnectTimeoutException
                || cause instanceof java.net.ConnectException
                || cause instanceof javax.net.ssl.SSLHandshakeException
                || cause instanceof java.nio.channels.UnresolvedAddressException) {
            return "resend_unreachable";
        }
        if (cause instanceof HttpTimeoutException) {
            return "resend_timeout";
        }
        return "resend_connection_lost";
    }

    /** Reads only the error name, whether its message is about {@code from}, and the id; nothing else is kept. */
    Answer answer(int status, String body, String retryAfter) {
        String name = "";
        boolean namesFrom = false;
        String id = "unknown";
        try {
            var tree = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            name = tree.path("name").asString("");
            // Only the field itself, quoted as Resend quotes field names ("Invalid `from` field"), never the word.
            namesFrom = FROM_FIELD.matcher(tree.path("message").asString("")).find();
            String given = tree.path("id").asString("");
            id = given.matches("^[A-Za-z0-9-]{1,64}$") ? given : "unknown";
        } catch (RuntimeException e) {
            // An answer that is not JSON keeps the defaults.
        }
        Duration wait = RATE_LIMIT_WAIT;
        if (retryAfter != null && retryAfter.strip().matches("^\\d{1,4}$")) {
            wait = Duration.ofSeconds(Math.clamp(Long.parseLong(retryAfter.strip()), 1, 600));
        }
        return new Answer(status, name, namesFrom, id, wait);
    }

    /**
     * What an answer comes to: 2xx sent; 400, 413 or 422 refused (the request is invalid or too large and will be again);
     * 429 deferred, the attempt given back; 409 for the same key with another body refused; 401, 403, a 409 of
     * two requests at once and anything else retried.
     */
    static Result read(Answer answer, BusinessClock clock) throws SendFailure {
        int status = answer.status();
        if (status >= 200 && status < 300) {
            return Result.SENT;
        }
        return switch (status) {
            case 400, 422 -> Result.refused("resend_rejected");
            case 413 -> Result.refused("resend_too_large");
            case 429 -> Result.deferred(clock.now().plus(answer.retryAfter()));
            case 409 ->
                "invalid_idempotent_request".equals(answer.errorName())
                        ? Result.refused("resend_payload_changed")
                        : fail("resend_idempotency_conflict");
            case 401, 403 -> fail("resend_unauthorized");
            default -> fail("resend_unavailable");
        };
    }

    private static Result fail(String reason) throws SendFailure {
        throw new SendFailure(reason);
    }

    /** The JSON body of {@code POST /emails}. */
    static Map<String, Object> request(
            String from,
            Delivery delivery,
            SmtpEmailChannel.Body body,
            EmailAttachments.Attachment attachment,
            RowContext context,
            String envTag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", List.of(delivery.recipient()));
        out.put("subject", SmtpEmailChannel.oneLine(delivery.subject()));
        out.put("text", body.text());
        out.put("html", body.html());
        if (attachment != null) {
            out.put(
                    "attachments",
                    List.of(Map.of(
                            "filename", SmtpEmailChannel.oneLine(attachment.fileName()),
                            "content", Base64.getEncoder().encodeToString(attachment.bytes()),
                            "content_type", attachment.contentType())));
        }
        List<Map<String, String>> tags = new ArrayList<>();
        if (context.outboxId() != null) {
            tags.add(Map.of("name", "outbox_id", "value", context.outboxId().toString()));
        }
        // The environment that sent it: another environment's webhook on the same Resend account ignores the event.
        tags.add(Map.of("name", "env", "value", envTag));
        if (context.tenantId() != null) {
            // So a bounce or complaint that comes back names the business (ResendWebhookController).
            tags.add(Map.of("name", "tenant_id", "value", context.tenantId().toString()));
        }
        if (context.templateKey() != null) {
            // A tag value takes letters, digits, underscores and dashes only.
            tags.add(Map.of("name", "template", "value", context.templateKey().replaceAll("[^A-Za-z0-9_-]", "_")));
        }
        if (!tags.isEmpty()) {
            out.put("tags", tags);
        }
        return out;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
