package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/platform/find-sign-in}: staff of a business ask the platform home page for their sign-in page (issue
 * #383). No sign-in; served only on the platform host. Always the same 202 and the same words after about the same time,
 * whether or not the address belongs to staff anywhere, so it cannot be used to find out who works where. A failure inside
 * (the database or the outbox) is logged and audited and still gets that 202: a 500 that only some addresses reach would
 * tell the asker which addresses matched.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/find-sign-in", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "sign-up")
class FindSignInController {

    private static final Logger log = LoggerFactory.getLogger(FindSignInController.class);

    static final String ANSWER =
            "If this email is used by staff of a business, we have sent the sign-in page of that business to it."
                    + " Check your inbox and your spam folder.";

    private final FindSignInService service;
    private final SignUpRateLimiter limiter;
    private final long minResponseMillis;

    FindSignInController(
            FindSignInService service,
            SignUpRateLimiter limiter,
            @Value("${bms.auth.reset-min-response-ms:400}") long minResponseMillis) {
        this.service = service;
        this.limiter = limiter;
        this.minResponseMillis = minResponseMillis;
    }

    @Schema(name = "FindSignInRequest")
    record FindSignInRequest(
            @NotBlank
            @Email
            @Size(max = 254)
            @Pattern(
                    regexp = "^[\\x21-\\x7E]+$",
                    message = "Use an address in plain letters, digits and symbols (no accents).")
            String email) {}

    @Schema(name = "FindSignInAccepted", description = "The same words whether or not the address matched")
    record Accepted(String message) {}

    @PostMapping
    @PublicEndpoint
    @Operation(
            summary =
                    "Email the sign-in page of every business where this address is an active staff user; the answer never says"
                            + " whether any matched (issue #383)",
            operationId = "findStaffSignIn")
    ResponseEntity<Accepted> find(@Valid @RequestBody FindSignInRequest request) {
        limiter.acquire(SignUpRateLimiter.FIND_PER_IP, RequestContext.clientIp());
        long started = System.nanoTime();
        try {
            service.request(request.email());
        } catch (RuntimeException e) {
            // Never the address in the log: the audit row keeps it masked.
            log.error("Find your sign-in page failed; the asker got the usual answer", e);
            service.recordFailure(request.email());
        } finally {
            pad(started);
        }
        return ResponseEntity.accepted().body(new Accepted(ANSWER));
    }

    private void pad(long startedNanos) {
        long remaining = minResponseMillis - (System.nanoTime() - startedNanos) / 1_000_000;
        if (remaining > 0) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
