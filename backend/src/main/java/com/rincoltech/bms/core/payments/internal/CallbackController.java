package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.core.payments.internal.NotificationReader.Malformed;
import com.rincoltech.bms.core.payments.internal.NotificationReader.Notification;
import com.rincoltech.bms.core.payments.internal.NotificationReader.TooLarge;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.RequestContext;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The public notification endpoints on the callback host (chapter 7 section 7.11.10, spec 3.6, FR-PAY-02, FR-PAY-08):
 * {@code GET} or {@code POST /api/v1/payments/callbacks/pesapal/{token}} for the provider's IPN and
 * {@code GET /api/v1/payments/return/pesapal} for the payer coming back to the order's callback url. Public by design,
 * no session, no tenant from the host (the tenant comes from the merchant reference). They do not trust the request:
 * the path token only keeps noise out (it selects the credential pair, compared in constant time), two identifiers are
 * read and shape checked and nothing else, a body over the limit is refused, the rate limit comes first, an unknown
 * order is acknowledged and counted without any outbound call, and the answer to a notification is always the exact
 * acknowledgement the contract documents, so the provider has no reason to retry what we have recorded. A request
 * that is not a notification gets a plain problem answer; a store that failed gets the acknowledgement with status
 * 500, which tells the provider to try again. The decision whether money moved is never made here.
 *
 * <p>The payer's return page carries no token and any browser can call it, so it is held to less: it has its own rate
 * limit bucket (it can never use up the provider's), it writes nothing for a reference it does not know (no platform
 * queue row, no alert counter), and it serves tenant-scope orders only (the platform pair's subscription payments, slice
 * #228, will need the scope read from the order). Both paths are on the callback host: the proxy forwards
 * {@code /api/v1/payments/callbacks/*} and {@code /api/v1/payments/return/*} there (deploy/caddy/Caddyfile).
 */
@RestController
@RequestMapping("/api/v1/payments")
@Hidden
class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    private static final String PAYER_PAGE = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Payment received</title></head>"
            + "<body><p>Thank you. Your payment is being confirmed. You can close this page.</p></body></html>";

    private final PesapalSettings settings;
    private final CallbackService service;
    private final CallbackLimiter limiter;
    private final PaymentsProperties properties;
    private final PaymentCounters counters;
    private final ObjectMapper mapper;

    CallbackController(
            PesapalSettings settings,
            CallbackService service,
            CallbackLimiter limiter,
            PaymentsProperties properties,
            PaymentCounters counters,
            ObjectMapper mapper) {
        this.settings = settings;
        this.service = service;
        this.limiter = limiter;
        this.properties = properties;
        this.counters = counters;
        this.mapper = mapper;
    }

    @RequestMapping(
            path = "/callbacks/pesapal/{token}",
            method = {RequestMethod.GET, RequestMethod.POST})
    @PublicEndpoint
    ResponseEntity<Map<String, Object>> ipn(@PathVariable("token") String token, HttpServletRequest request) {
        // The token is checked in memory first, so a request with a wrong one can never take a slot of the provider's
        // bucket.
        java.util.Optional<Scope> selected = settings.scopeOfToken(token);
        if (selected.isEmpty()) {
            if (!limiter.tryAcquire(CallbackLimiter.Bucket.BAD_TOKEN, RequestContext.clientIp())) {
                return tooMany();
            }
            throw ApiException.notFound();
        }
        if (!limiter.tryAcquire(CallbackLimiter.Bucket.IPN, RequestContext.clientIp())) {
            return tooMany();
        }
        Scope scope = selected.get();
        Notification n = read(request, "IPNCHANGE");
        try {
            service.receive(scope, "ipn", n.type(), n.trackingId(), n.reference());
        } catch (RuntimeException e) {
            // Only the type is logged: a database message can quote a value. 500 tells the provider to try again.
            log.error(
                    "payment notification could not be recorded: {}",
                    e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ack(n, 500));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(ack(n, 200));
    }

    /** The payer's browser returns here from the hosted page; it gets a plain page and the order gets a check. */
    @GetMapping(path = "/return/pesapal")
    @PublicEndpoint
    ResponseEntity<String> payerReturn(HttpServletRequest request) {
        if (!limiter.tryAcquire(CallbackLimiter.Bucket.RETURN, RequestContext.clientIp())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Too many requests.");
        }
        try {
            Notification n = NotificationReader.read(
                    request, mapper, properties.maxBodyBytes(), settings.environment(), "CALLBACKURL");
            service.receive(Scope.TENANT, "return", n.type(), n.trackingId(), n.reference());
        } catch (Malformed | TooLarge e) {
            // The payer sees the same page; nothing is recorded.
        } catch (RuntimeException e) {
            log.error("payer return could not be recorded: {}", e.getClass().getSimpleName());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(PAYER_PAGE);
    }

    private Notification read(HttpServletRequest request, String defaultType) {
        try {
            return NotificationReader.read(
                    request, mapper, properties.maxBodyBytes(), settings.environment(), defaultType);
        } catch (Malformed e) {
            counters.count("malformed");
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "malformed_notification",
                    "Malformed",
                    "This is not a payment notification.");
        } catch (TooLarge e) {
            counters.count("oversized");
            throw new ApiException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "notification_too_large",
                    "Too large",
                    "This notification is too large.");
        }
    }

    /** The documented acknowledgement: the three names in camelCase and a numeric status. */
    private static Map<String, Object> ack(Notification n, int status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNotificationType", n.type());
        body.put("orderTrackingId", n.trackingId());
        body.put("orderMerchantReference", n.reference());
        body.put("status", status);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> tooMany() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "rate_limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
