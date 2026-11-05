package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.internal.SmsProvider.DeliveryReport;
import com.rincoltech.bms.core.notifications.internal.SmsProvider.UnreadableReport;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.RequestContext;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/channels/sms/africastalking/delivery-reports/{token}} on the callback host (issue #215, ADR-036
 * decision 7, spec section 4.7, chapter 7 section 7.11.10). Public by design: the provider calls it with no session and no
 * business, and the tenant filter does not resolve one for {@code /api/v1/channels/}. It trusts nothing it has not
 * checked:
 *
 * <ul>
 *   <li>The path token is {@code BMS_SMS_CALLBACK_TOKEN}, compared in constant time; with it unset (or shorter than 32
 *       characters) the route answers 404 for every request. A wrong token is answered 404 and counted
 *       ({@code bad_token}), inside a bucket of its own per address, so a flood of guesses can never use up the
 *       provider's budget and, past its bucket, costs no database write.
 *   <li>A rate limit per address and overall applies before the body is read (429, counted {@code throttled} inside a
 *       bucket of its own, so past it a flood costs no database write, issue #808). The edge rule and the provider's source
 *       address allow-list in the proxy come first (chapter 9 section 9.13).
 *   <li>A body of unknown length (chunked) is refused with 411 before it is read ({@code no_length}, issue #808), so the
 *       16 KB check always sees the size.
 *   <li>Only the message id, the status and the failure reason are read; the recipient's number the provider sends is
 *       never read, kept or logged. A body without a usable id or status is 400 ({@code malformed}).
 *   <li>The row is found by the provider's message id through a definer function and its status moves forward only;
 *       an unknown id is acknowledged and ignored. A forged report can at worst change a log status: nothing money-related
 *       reads it.
 * </ul>
 *
 * The answer is 200 with no body once the report is recorded or deliberately ignored, so the provider does not retry it,
 * and 500 when the database could not record it, so the provider tries again. Off for every business by default: no row
 * exists for a business until the operator's switch and its own are both on (spec 4.16).
 */
@RestController
@RequestMapping("/api/v1/channels/sms")
@Hidden
class SmsDeliveryReportController {

    private static final Logger log = LoggerFactory.getLogger(SmsDeliveryReportController.class);

    /** A delivery report is a few short form fields; anything larger is not one. */
    static final int MAX_BODY = 16 * 1024;
    /** A path token shorter than this is treated as unset: the route stays off rather than guessable. */
    static final int MIN_TOKEN = 32;

    private final SmsDeliveryReports reports;
    private final Limiter limiter;
    private final byte[] expected;

    SmsDeliveryReportController(
            SmsDeliveryReports reports,
            BusinessClock clock,
            @Value("${bms.sms.callback-token:}") String token,
            @Value("${bms.sms.callback-ip-per-minute:3000}") int perAddress,
            @Value("${bms.sms.callback-global-per-minute:6000}") int overall) {
        this.reports = reports;
        this.limiter = new Limiter(clock, perAddress, overall);
        if (token != null && !token.isBlank() && token.strip().length() < MIN_TOKEN) {
            log.warn("BMS_SMS_CALLBACK_TOKEN is shorter than {} characters; SMS delivery reports are off", MIN_TOKEN);
        }
        this.expected = token == null || token.strip().length() < MIN_TOKEN ? new byte[0] : digest(token.strip());
    }

    @PostMapping("/africastalking/delivery-reports/{token}")
    @PublicEndpoint
    ResponseEntity<Void> africasTalking(@PathVariable("token") String token, HttpServletRequest request) {
        if (expected.length == 0) {
            throw ApiException.notFound();
        }
        String address = RequestContext.clientIp();
        if (!MessageDigest.isEqual(expected, digest(token))) {
            // Counted only inside the bad token bucket: past it a guess costs a counter in memory and nothing else.
            if (!limiter.tryAcquire(Bucket.BAD_TOKEN, address)) {
                return tooMany();
            }
            reports.count("bad_token");
            throw ApiException.notFound();
        }
        if (!limiter.tryAcquire(Bucket.REPORT, address)) {
            // Issue #808: counted only inside a bucket of its own, as a wrong token is, so a flood past the limit costs
            // a counter in memory and no database write.
            if (limiter.tryAcquire(Bucket.THROTTLED, address)) {
                reports.count("throttled");
            }
            return tooMany();
        }
        if (request.getContentLengthLong() < 0) {
            // Issue #808: a body of unknown length (chunked) would skip the size check below and be read up to the
            // container's form limit, so it is refused before it is read. The provider sends a short form with its
            // length.
            reports.count("no_length");
            throw new ApiException(
                    HttpStatus.LENGTH_REQUIRED, "length_required", "Length required", "This is not a delivery report.");
        }
        if (request.getContentLengthLong() > MAX_BODY) {
            reports.count("oversized");
            throw new ApiException(
                    HttpStatus.CONTENT_TOO_LARGE, "report_too_large", "Too large", "This is not a delivery report.");
        }
        DeliveryReport report;
        try {
            report = AfricasTalkingSmsProvider.readDeliveryReport(request::getParameter);
        } catch (UnreadableReport e) {
            reports.count(e.getMessage());
            if (e.getMessage().equals("unknown_status")) {
                // A status word this build does not know: acknowledged, so the provider does not retry, and counted.
                return ResponseEntity.ok().build();
            }
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_report", "Malformed", "This is not a delivery report.");
        }
        String outcome;
        try {
            outcome = reports.apply(AfricasTalkingSmsProvider.NAME, report);
        } catch (RuntimeException e) {
            // Only the type: a database message can quote a value. 500 tells the provider to try again.
            log.error(
                    "SMS delivery report could not be recorded: {}",
                    e.getClass().getSimpleName());
            reports.count("store_failed");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        // An interim report of a known message never moves it, whatever its row says: counted as what it is.
        boolean interim =
                report.status().equals("sent") && (outcome.equals("not_forward") || outcome.equals("repeated"));
        reports.count(interim ? "interim" : outcome);
        log.debug("SMS delivery report {}: {}", report.status(), outcome);
        return ResponseEntity.ok().build();
    }

    private static ResponseEntity<Void> tooMany() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .build();
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    enum Bucket {
        REPORT,
        /** Requests with a wrong path token: they fill their own bucket and nobody else's. */
        BAD_TOKEN,
        /** The throttled requests that are still counted: past this bucket a throttled request writes nothing. */
        THROTTLED
    }

    /**
     * The application's own limit, a fixed window of one minute per address and for all addresses together, as the payment
     * callbacks have (ADR-035 decision 4); what still holds when the edge rule is not there. The wrong token bucket is a
     * tenth of the report bucket. An address table past its bound is treated as full.
     */
    static final class Limiter {

        static final int MAX_ADDRESSES = 20_000;

        private final BusinessClock clock;
        private final Map<Bucket, int[]> limits = new EnumMap<>(Bucket.class);
        private final Map<Bucket, Map<String, Integer>> counts = new EnumMap<>(Bucket.class);
        private final Map<Bucket, Integer> totals = new EnumMap<>(Bucket.class);
        private long window = Long.MIN_VALUE;

        Limiter(BusinessClock clock, int perAddress, int overall) {
            this.clock = clock;
            limits.put(Bucket.REPORT, new int[] {perAddress, overall});
            limits.put(Bucket.BAD_TOKEN, new int[] {Math.max(1, perAddress / 10), Math.max(1, overall / 10)});
            limits.put(Bucket.THROTTLED, new int[] {Math.max(1, perAddress / 10), Math.max(1, overall / 10)});
        }

        synchronized boolean tryAcquire(Bucket bucket, String address) {
            long minute = clock.now().getEpochSecond() / 60;
            if (minute != window) {
                window = minute;
                counts.clear();
                totals.clear();
            }
            int total = totals.getOrDefault(bucket, 0);
            if (total >= limits.get(bucket)[1]) {
                return false;
            }
            Map<String, Integer> perAddress = counts.computeIfAbsent(bucket, b -> new HashMap<>());
            String key = address == null ? "unknown" : address;
            if (!perAddress.containsKey(key) && perAddress.size() >= MAX_ADDRESSES) {
                return false;
            }
            if (perAddress.merge(key, 1, Integer::sum) > limits.get(bucket)[0]) {
                return false;
            }
            totals.put(bucket, total + 1);
            return true;
        }
    }
}
