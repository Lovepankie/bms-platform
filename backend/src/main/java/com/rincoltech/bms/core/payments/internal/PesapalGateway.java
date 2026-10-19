package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.GatewayException.Kind;
import com.rincoltech.bms.core.payments.PaymentGateway;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Pesapal API 3.0 adapter (ADR-035, spec 3.3 and the confirmed contract of 3.12). What the sandbox spike showed, and
 * how this class reads it:
 *
 * <ul>
 *   <li><b>Errors.</b> A business error is http 200 with a body {@code status} of {@code "500"} and an {@code error}
 *       object; 401, 404 and 405 come as a wrapper {@code {"message": "<escaped JSON>"}} or a plain message; a garbage
 *       token is http 500; RegisterIPN is throttled with http 409. {@link #unwrap} reads the wrapper, {@link #classify}
 *       reads the status, and the body is read before the http code is believed.
 *   <li><b>Status.</b> Decided on the numeric status code. Code 0 with {@code payment_details_not_found} is how an unpaid
 *       order reads, so it is {@code PENDING}; any other code 0 is an anomaly; the description is never read.
 *   <li><b>Money.</b> The provider carries fractions of a shilling, so every amount in and out goes through
 *       {@link MinorUnits}, which refuses a fraction.
 *   <li><b>Accounts.</b> Every call names the {@link Account} it is made for and the credentials are looked up from it
 *       alone ({@link Credentials}): the platform's pair from the environment, a lender's own from its stored, encrypted
 *       row, never one for the other. A lender whose credentials the provider refuses never counts against the shared
 *       breaker, so one lender's wrong secret cannot stop the others from collecting.
 *   <li><b>Token.</b> Cached per account (and per credential value, so a replaced secret is used at once) until a minute before the {@code expiryDate} the provider returned
 *       (3600 seconds in the sandbox although the reference says five minutes); a refused token is dropped and the call
 *       repeated once; a failed token request is not repeated for 30 seconds.
 *   <li><b>Safety.</b> Redirects are never followed, bodies are capped, the redirect URL of an order must be on the
 *       provider's own domain, and nothing the provider wrote (a message, a body) or any credential goes into an
 *       exception, a log line or a stored reason: only closed kinds and codes from a safe alphabet.
 * </ul>
 */
final class PesapalGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PesapalGateway.class);

    /** Pauses; replaced in tests so no test sleeps. */
    interface Sleeper {
        void sleep(Duration pause) throws InterruptedException;
    }

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration CALL_BUDGET = Duration.ofSeconds(15);
    static final int MAX_BODY_BYTES = 1_048_576;
    static final Duration TOKEN_MARGIN = Duration.ofSeconds(60);
    static final Duration TOKEN_LIFE_CAP = Duration.ofHours(24);
    static final Duration AUTH_BACKOFF = Duration.ofSeconds(30);
    /** The sandbox refused a second RegisterIPN within about five seconds; the registration waits twice that. */
    static final Duration REGISTER_SPACING = Duration.ofSeconds(10);

    private static final Pattern GUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9._:-]{1,50}$");
    private static final Pattern METHOD = Pattern.compile("[^A-Za-z0-9 _-]");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final String PAYMENT_PENDING = "payment_details_not_found";

    /** Where the credentials of an account come from; empty means the account has none that can be used. */
    interface Credentials {
        Optional<PesapalSettings.Pair> find(Account account);
    }

    private record Token(String value, Instant expires, String fingerprint) {}

    private record Backoff(Instant until, String fingerprint) {}

    private record Reply(int http, JsonNode json) {}

    private final URI base;
    private final Credentials credentials;
    private final String payerReturnUrl;
    private final ObjectMapper mapper;
    private final Supplier<Instant> clock;
    private final CircuitBreaker breaker;
    private final Sleeper sleeper;
    private final HttpClient http;
    private final Map<Account, Token> tokens = new ConcurrentHashMap<>();
    private final Map<Account, Backoff> authBackoff = new ConcurrentHashMap<>();
    private final Map<Account, Object> locks = new ConcurrentHashMap<>();
    private Instant lastRegistration;

    private final Duration callBudget;

    PesapalGateway(
            URI base,
            Credentials credentials,
            String payerReturnUrl,
            ObjectMapper mapper,
            Supplier<Instant> clock,
            CircuitBreaker breaker,
            Sleeper sleeper) {
        this(base, credentials, payerReturnUrl, mapper, clock, breaker, sleeper, CALL_BUDGET);
    }

    /** As above with a shorter total time per call, for the tests of a provider that does not answer. */
    PesapalGateway(
            URI base,
            Credentials credentials,
            String payerReturnUrl,
            ObjectMapper mapper,
            Supplier<Instant> clock,
            CircuitBreaker breaker,
            Sleeper sleeper,
            Duration callBudget) {
        this.callBudget = callBudget;
        this.base = URI.create(base.toString().replaceAll("/+$", ""));
        this.credentials = credentials;
        this.payerReturnUrl = payerReturnUrl;
        this.mapper = mapper;
        this.clock = clock;
        this.breaker = breaker;
        this.sleeper = sleeper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String providerKey() {
        return "pesapal";
    }

    @Override
    public boolean available(Account account) {
        return payerReturnUrl != null
                && credentials.find(account).filter(PesapalGateway::orderable).isPresent();
    }

    /** Credentials that can make an order: a key, a secret and the registration of a notification url. */
    private static boolean orderable(PesapalSettings.Pair pair) {
        return pair.usable() && present(pair.ipnId());
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    // ---- the four calls -------------------------------------------------------------------------

    @Override
    public OrderResult createOrder(Account account, OrderRequest request) throws GatewayException {
        PesapalSettings.Pair pair = requirePair(account);
        if (!orderable(pair)) {
            throw new GatewayException(Kind.UNAVAILABLE, 0, "not_configured");
        }
        if (request.merchantReference() == null
                || !REFERENCE.matcher(request.merchantReference()).matches()) {
            throw new GatewayException(Kind.REFUSED, 0, "invalid_reference");
        }
        if (request.amountMinor() <= 0 || !MinorUnits.supported(request.currency())) {
            throw new GatewayException(Kind.REFUSED, 0, "invalid_amount");
        }
        if (request.payerPhoneE164() == null || request.payerPhoneE164().isBlank()) {
            throw new GatewayException(Kind.REFUSED, 0, "missing_billing_address");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", request.merchantReference());
        body.put("currency", request.currency());
        // The whole-shilling rule lives in MinorUnits: a long of minor units becomes an integral decimal here.
        body.put("amount", MinorUnits.toDecimal(request.amountMinor(), request.currency()));
        body.put("description", description(request.description()));
        body.put("callback_url", payerReturnUrl);
        body.put("notification_id", pair.ipnId());
        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("phone_number", request.payerPhoneE164());
        billing.put("country_code", "UG");
        body.put("billing_address", billing);

        JsonNode reply = authenticated(account, pair, "POST", "/api/Transactions/SubmitOrderRequest", body);
        requireOk(reply, "order_refused");
        String tracking = str(reply.get("order_tracking_id"));
        String reference = str(reply.get("merchant_reference"));
        String redirect = str(reply.get("redirect_url"));
        if (tracking == null || !GUID.matcher(tracking).matches() || reference == null || !providerRedirect(redirect)) {
            throw new GatewayException(Kind.BAD_RESPONSE, 200, "malformed_order");
        }
        return new OrderResult(tracking.toLowerCase(Locale.ROOT), reference, redirect);
    }

    @Override
    public StatusResult queryStatus(Account account, String providerReference) throws GatewayException {
        PesapalSettings.Pair pair = requirePair(account);
        if (providerReference == null || !GUID.matcher(providerReference).matches()) {
            throw new GatewayException(Kind.REFUSED, 0, "invalid_provider_reference");
        }
        String path = "/api/Transactions/GetTransactionStatus?orderTrackingId="
                + URLEncoder.encode(providerReference, StandardCharsets.UTF_8);
        JsonNode reply = authenticated(account, pair, "GET", path, null);
        return statusOf(reply, providerReference);
    }

    @Override
    public void cancelOrder(Account account, String providerReference) throws GatewayException {
        PesapalSettings.Pair pair = requirePair(account);
        if (providerReference == null || !GUID.matcher(providerReference).matches()) {
            throw new GatewayException(Kind.REFUSED, 0, "invalid_provider_reference");
        }
        JsonNode reply = authenticated(
                account, pair, "POST", "/api/Transactions/CancelOrder", Map.of("order_tracking_id", providerReference));
        requireOk(reply, "cancel_refused");
    }

    @Override
    public void checkCredentials(Account account) throws GatewayException {
        PesapalSettings.Pair pair = requirePair(account);
        // A fresh token, never the cached one: this answers "do the credentials work now?", and the cache must not.
        // Under the
        // account's own lock, as the token fetch is, so a check never interleaves with another fetch for the same
        // account.
        synchronized (locks.computeIfAbsent(account, a -> new Object())) {
            tokens.remove(account);
            authBackoff.remove(account);
            token(account, pair);
        }
    }

    @Override
    public synchronized IpnRegistration registerIpn(Account account, String url, IpnType type) throws GatewayException {
        PesapalSettings.Pair pair = requirePair(account);
        URI target;
        try {
            target = URI.create(url);
        } catch (RuntimeException e) {
            throw new GatewayException(Kind.REFUSED, 0, "ipn_url_invalid");
        }
        // The provider accepted http and localhost urls in the spike; the guard against a url it cannot reach is ours.
        if (!PesapalSettings.publicHttps(target)) {
            throw new GatewayException(Kind.REFUSED, 0, "ipn_url_not_public");
        }
        for (int attempt = 1; ; attempt++) {
            if (lastRegistration != null) {
                Duration wait = REGISTER_SPACING.minus(Duration.between(lastRegistration, clock.get()));
                if (!wait.isNegative() && !wait.isZero()) {
                    pause(wait);
                }
            }
            lastRegistration = clock.get();
            try {
                JsonNode reply = authenticated(
                        account,
                        pair,
                        "POST",
                        "/api/URLSetup/RegisterIPN",
                        Map.of("url", url, "ipn_notification_type", type.name()));
                requireOk(reply, "ipn_refused");
                String id = str(reply.get("ipn_id"));
                if (id == null || !GUID.matcher(id).matches()) {
                    throw new GatewayException(Kind.BAD_RESPONSE, 200, "malformed_ipn");
                }
                return new IpnRegistration(id.toLowerCase(Locale.ROOT), url, type);
            } catch (GatewayException e) {
                if (e.kind() != Kind.THROTTLED || attempt >= 4) {
                    throw e;
                }
                pause(REGISTER_SPACING);
            }
        }
    }

    // ---- reading the provider's answer ----------------------------------------------------------

    private StatusResult statusOf(JsonNode r, String requested) {
        JsonNode codeNode = r.get("status_code");
        Integer code = null;
        if (codeNode != null && codeNode.isIntegralNumber() && codeNode.canConvertToInt()) {
            code = codeNode.intValue();
        } else if (codeNode != null
                && codeNode.isString()
                && codeNode.asString().trim().matches("^\\d{1,3}$")) {
            code = Integer.parseInt(codeNode.asString().trim());
        }
        String errorCode = errorCode(r);
        if (code == null) {
            return anomaly(requested, errorCode != null ? errorCode : "missing_status_code");
        }
        if (code == 0) {
            if (!PAYMENT_PENDING.equals(errorCode)) {
                return anomaly(
                        requested, errorCode != null ? GatewayException.safe(errorCode) : "invalid_without_error");
            }
        } else if (code < 0 || code > 3) {
            return anomaly(requested, "unknown_status_code");
        }
        String tracking = str(r.get("order_tracking_id"));
        if (code != 0 && (tracking == null || !tracking.equalsIgnoreCase(requested))) {
            return anomaly(requested, "tracking_mismatch");
        }
        String currency = str(r.get("currency"));
        if (code != 2
                && (currency == null || !CURRENCY.matcher(currency).matches() || !MinorUnits.supported(currency))) {
            return anomaly(requested, "currency_unreadable");
        }
        Long minor = null;
        JsonNode amount = r.get("amount");
        if (code != 2) {
            BigDecimal decimal = decimal(amount);
            Optional<Long> parsed = decimal == null ? Optional.empty() : MinorUnits.fromDecimal(decimal, currency);
            if (parsed.isEmpty()) {
                return anomaly(
                        requested, decimal != null && decimal.signum() > 0 ? "fractional_amount" : "amount_unreadable");
            }
            minor = parsed.get();
        }
        String reference = str(r.get("merchant_reference"));
        if (reference == null) {
            return anomaly(requested, "missing_merchant_reference");
        }
        String method = str(r.get("payment_method"));
        method = method == null ? "" : METHOD.matcher(method).replaceAll("");
        method = method.substring(0, Math.min(40, method.length()));
        String confirmation = str(r.get("confirmation_code"));
        confirmation = confirmation == null ? "" : confirmation.substring(0, Math.min(100, confirmation.length()));
        Status status = switch (code) {
            case 0 -> Status.PENDING;
            case 1 -> Status.COMPLETED;
            case 2 -> Status.FAILED;
            default -> Status.REVERSED;
        };
        return new StatusResult(
                status, requested.toLowerCase(Locale.ROOT), reference, minor, currency, method, confirmation, null);
    }

    private static StatusResult anomaly(String requested, String word) {
        return new StatusResult(
                Status.ANOMALY,
                requested == null ? null : requested.toLowerCase(Locale.ROOT),
                null,
                null,
                null,
                null,
                null,
                GatewayException.safe(word));
    }

    /** A business error in a body that carries no status code: the body {@code status} and the {@code error} object. */
    private static void requireOk(JsonNode reply, String fallbackCode) throws GatewayException {
        String status = str(reply.get("status"));
        String code = errorCode(reply);
        if ("200".equals(status) && code == null) {
            return;
        }
        if (code != null && credentialCode(code)) {
            throw new GatewayException(Kind.AUTH, 200, code);
        }
        if (status == null && code == null) {
            throw new GatewayException(Kind.BAD_RESPONSE, 200, "no_status");
        }
        throw new GatewayException(Kind.REFUSED, 200, code != null ? code : fallbackCode);
    }

    private static boolean credentialCode(String code) {
        return code.equals("invalid_consumer_key_or_secret_provided")
                || code.equals("invalid_api_credentials_provided")
                || code.equals("authentication_error");
    }

    private static String errorCode(JsonNode r) {
        JsonNode error = r.get("error");
        return error == null || !error.isObject() ? null : str(error.get("code"));
    }

    private static String str(JsonNode n) {
        if (n == null || !n.isString()) {
            return null;
        }
        String s = n.asString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(JsonNode n) {
        try {
            if (n == null) {
                return null;
            }
            if (n.isNumber()) {
                return n.decimalValue();
            }
            return n.isString() ? new BigDecimal(n.asString().trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String description(String text) {
        String clean =
                text == null ? "Payment" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (clean.isEmpty()) {
            clean = "Payment";
        }
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    private static boolean providerRedirect(String redirect) {
        if (redirect == null) {
            return false;
        }
        try {
            URI uri = URI.create(redirect);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getRawUserInfo() == null
                    && (host.equals("pesapal.com") || host.endsWith(".pesapal.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- the wire -------------------------------------------------------------------------------

    /** The credentials of the account, or an unavailable answer: no call is made, and no other account is ever tried. */
    private PesapalSettings.Pair requirePair(Account account) throws GatewayException {
        PesapalSettings.Pair pair = credentials.find(account).orElse(null);
        if (pair == null || !pair.usable() || payerReturnUrl == null) {
            throw new GatewayException(Kind.UNAVAILABLE, 0, "not_configured");
        }
        return pair;
    }

    /** One authenticated call; a refused token is dropped and the call repeated once with a fresh one. */
    private JsonNode authenticated(Account account, PesapalSettings.Pair pair, String method, String path, Object body)
            throws GatewayException {
        for (int attempt = 0; ; attempt++) {
            String bearer = token(account, pair);
            try {
                return exchange(account, method, path, bearer, body);
            } catch (GatewayException e) {
                if (e.kind() == Kind.AUTH && attempt == 0) {
                    tokens.remove(account);
                    continue;
                }
                throw e;
            }
        }
    }

    private String token(Account account, PesapalSettings.Pair pair) throws GatewayException {
        String fingerprint = fingerprint(pair);
        Token cached = tokens.get(account);
        if (usable(cached, fingerprint)) {
            return cached.value();
        }
        synchronized (locks.computeIfAbsent(account, a -> new Object())) {
            cached = tokens.get(account);
            if (usable(cached, fingerprint)) {
                return cached.value();
            }
            Backoff blocked = authBackoff.get(account);
            if (blocked != null
                    && blocked.fingerprint().equals(fingerprint)
                    && clock.get().isBefore(blocked.until())) {
                throw new GatewayException(Kind.AUTH, 0, "auth_backoff");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("consumer_key", pair.key());
            body.put("consumer_secret", pair.secret());
            Token fresh;
            try {
                JsonNode reply = exchange(account, "POST", "/api/Auth/RequestToken", null, body);
                requireOk(reply, "auth_refused");
                String value = str(reply.get("token"));
                if (value == null) {
                    throw new GatewayException(Kind.BAD_RESPONSE, 200, "no_token");
                }
                fresh = new Token(value, expiry(str(reply.get("expiryDate"))), fingerprint);
            } catch (GatewayException e) {
                if (e.kind() == Kind.AUTH) {
                    authBackoff.put(account, new Backoff(clock.get().plus(AUTH_BACKOFF), fingerprint));
                }
                throw e;
            }
            authBackoff.remove(account);
            tokens.put(account, fresh);
            return fresh.value();
        }
    }

    private boolean usable(Token token, String fingerprint) {
        return token != null
                && token.fingerprint().equals(fingerprint)
                && clock.get().isBefore(token.expires().minus(TOKEN_MARGIN));
    }

    /** A digest of the credential values, kept in memory only: a token is valid for the credentials that fetched it. */
    private static String fingerprint(PesapalSettings.Pair pair) {
        // Length-prefixed, so no pair of values can give the same input as another (a credential may contain any
        // character).
        return java.util.HexFormat.of()
                .formatHex(PesapalSettings.sha256(pair.key().length() + ":" + pair.key() + pair.secret()));
    }

    /** The provider's {@code expiryDate} (UTC, with {@code Z}), capped; a missing or odd one gives a minute. */
    private Instant expiry(String expiryDate) {
        Instant now = clock.get();
        if (expiryDate == null) {
            return now.plus(TOKEN_MARGIN);
        }
        try {
            Instant expires = Instant.parse(expiryDate);
            Instant cap = now.plus(TOKEN_LIFE_CAP);
            return expires.isAfter(cap) ? cap : expires;
        } catch (DateTimeParseException e) {
            return now.plus(TOKEN_MARGIN);
        }
    }

    /**
     * One http exchange, under the breaker. Returns the unwrapped JSON body of an http 200. Everything else is turned into
     * a {@link GatewayException} from the status and the body. The breaker is told the provider's health, not the
     * business: an unreachable host, a 5xx, a 404 or 405 (our configuration is wrong) and a refused token count
     * against it; a 200 with a business error, a 409 throttle and a good answer do not.
     */
    private JsonNode exchange(Account account, String method, String path, String bearer, Object body)
            throws GatewayException {
        Optional<CircuitBreaker.Permit> permit = breaker.tryAcquire();
        if (permit.isEmpty()) {
            throw new GatewayException(Kind.UNAVAILABLE, 0, "circuit_open");
        }
        boolean settled = false;
        try {
            Reply reply;
            try {
                reply = send(method, path, bearer, body);
            } catch (GatewayException e) {
                breaker.recordFailure(permit.get());
                settled = true;
                log.warn("pesapal call failed: {} kind={} code={}", routeOf(path), e.kind(), e.code());
                throw e;
            }
            GatewayException failure = classify(reply);
            if (failure == null) {
                breaker.recordSuccess(permit.get());
            } else if (failure.kind() == Kind.THROTTLED
                    || failure.kind() == Kind.REFUSED
                    || (failure.kind() == Kind.AUTH && account.scope() == Scope.TENANT)) {
                // The provider answered. A lender's own credentials being refused is that lender's problem, never the
                // shared breaker's: one wrong secret must not stop every other lender from collecting.
                breaker.recordSuccess(permit.get());
            } else {
                breaker.recordFailure(permit.get());
            }
            settled = true;
            if (failure != null) {
                log.warn(
                        "pesapal call failed: {} kind={} http={} code={}",
                        routeOf(path),
                        failure.kind(),
                        failure.httpStatus(),
                        failure.code());
                throw failure;
            }
            return reply.json();
        } finally {
            if (!settled) {
                breaker.release(permit.get());
            }
        }
    }

    private static String routeOf(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private Reply send(String method, String path, String bearer, Object body) throws GatewayException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(callBudget)
                .header("Accept", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(
                builder.build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BODY_BYTES));
        HttpResponse<byte[]> response;
        try {
            // The request timeout covers the headers; this covers the body too.
            response = future.get(callBudget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new GatewayException(Kind.TRANSIENT, 0, "timeout");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new GatewayException(Kind.TRANSIENT, 0, "interrupted");
        } catch (ExecutionException e) {
            // Only the type of the cause is read: its message can quote the URL.
            Throwable cause = e.getCause();
            if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException) {
                throw new GatewayException(Kind.TRANSIENT, 0, "timeout");
            }
            if (cause instanceof ConnectException) {
                throw new GatewayException(Kind.TRANSIENT, 0, "connect");
            }
            if (cause instanceof SSLException) {
                throw new GatewayException(Kind.TRANSIENT, 0, "tls");
            }
            throw new GatewayException(Kind.TRANSIENT, 0, "network");
        }
        return new Reply(response.statusCode(), parse(response.statusCode(), response.body()));
    }

    /** The JSON of a body with the wrapper opened; an unreadable body is an empty object, judged by {@link #classify}. */
    private JsonNode parse(int http, byte[] bytes) {
        JsonNode root;
        try {
            root = bytes == null || bytes.length == 0 ? null : mapper.readTree(bytes);
        } catch (RuntimeException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            return mapper.createObjectNode().put("_unreadable", true);
        }
        return unwrap(root);
    }

    /** {@code {"message": "<escaped JSON>"}}, as 401 and the throttle answer, is opened; a plain message is kept. */
    private JsonNode unwrap(JsonNode root) {
        JsonNode message = root.get("message");
        if (root.size() == 1 && message != null && message.isString()) {
            try {
                JsonNode inner = mapper.readTree(message.asString());
                if (inner != null && inner.isObject()) {
                    return inner;
                }
            } catch (RuntimeException e) {
                // A plain message: not JSON, nothing to open.
            }
        }
        return root;
    }

    /** The failure the http status and body amount to, or null for a usable http 200. */
    private static GatewayException classify(Reply reply) {
        int http = reply.http();
        JsonNode json = reply.json();
        String code = errorCode(json);
        if (http == 200) {
            return json.has("_unreadable") ? new GatewayException(Kind.BAD_RESPONSE, 200, "unreadable") : null;
        }
        if (http == 401 || http == 403) {
            return new GatewayException(Kind.AUTH, http, code);
        }
        if (http == 409) {
            return new GatewayException(Kind.THROTTLED, http, code);
        }
        if (http == 404 || http == 405) {
            return new GatewayException(Kind.BAD_RESPONSE, http, "http_" + http);
        }
        if (http >= 500) {
            return "invalid_api_credentials_provided".equals(code)
                    ? new GatewayException(Kind.AUTH, http, code)
                    : new GatewayException(Kind.TRANSIENT, http, "http_" + http);
        }
        if (http >= 400) {
            return new GatewayException(Kind.REFUSED, http, "http_" + http);
        }
        return new GatewayException(Kind.BAD_RESPONSE, http, "http_" + http);
    }

    private void pause(Duration d) throws GatewayException {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(Kind.TRANSIENT, 0, "interrupted");
        }
    }

    @Override
    public String toString() {
        return "PesapalGateway[host=" + base.getHost() + "]";
    }
}
