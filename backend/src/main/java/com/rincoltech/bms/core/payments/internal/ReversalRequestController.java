package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.internal.GatewayReversals.Raised;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/payments/intents/{intent_id}/reversal-request} (spec 3.16): a person asks for the reversal of a payment the
 * provider reported reversed to be put to a checker again, when the platform handed it over (three requests that still stood, or a
 * request a checker rejected). The person who asks is the request's maker, so can never decide it, and cannot be the one who
 * rejected an earlier request; the platform's own requests are made by the tenant's system user. Asking never reverses anything.
 * The caller needs {@code core.payments.allocate_approve} in the payment's branch. A request already waiting is returned as it
 * is. Only an order waiting for a reversal decision is touched: the provider is asked about it (at most once in ten seconds)
 * and nothing else is done to any other order.
 */
@RestController
@RequestMapping(path = "/api/v1/payments/intents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "payments")
class ReversalRequestController {

    @Schema(name = "ReversalRequestOutcome")
    record Outcome(UUID intentId, UUID approvalRequestId) {}

    private final IntentStore store;
    private final GatewayReversals reversals;
    private final StatusChecker checker;
    private final BusinessClock clock;
    private final TransactionTemplate tx;

    ReversalRequestController(
            IntentStore store,
            GatewayReversals reversals,
            StatusChecker checker,
            BusinessClock clock,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.reversals = reversals;
        this.checker = checker;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @PostMapping("/{intent_id}/reversal-request")
    @RequiresPermission("core.payments.allocate_approve")
    @Operation(
            summary =
                    "Put the reversal of a payment the provider reversed to a checker again (a person asking; 202 with the request)",
            operationId = "requestPaymentReversalAgain")
    @ApiResponse(responseCode = "202", description = "The request, new or already waiting")
    @ApiResponse(
            responseCode = "409",
            description = "try_again, completed_again, provider_disagrees or not_in_review: nothing was raised")
    @ApiResponse(responseCode = "422", description = "nothing_to_reverse")
    ResponseEntity<Outcome> ask(@PathVariable("intent_id") UUID intentId, HttpServletResponse response) {
        NoStore.apply(response);
        Principal principal = CurrentPrincipal.require();
        // Under the order's row lock: only an order waiting for a reversal decision is asked about, and the provider is
        // asked
        // at most once in ten seconds (CheckSchedule.THROTTLE) whoever asks. The asker who finds no recent answer
        // claims the
        // slot by stamping last_checked_at in the same locked transaction, so two asks at the same moment cannot both
        // call the
        // provider; the second uses the first's slot, as any answer of a moment ago is used, so a caller in a loop
        // cannot
        // spend the shared account's allowance. Only a reversed payment is ever in review, so a person may still raise
        // a
        // request while the claimed answer is on its way; the provider's answer, when it comes, moves the order as
        // usual.
        Instant now = clock.now();
        boolean recent = Boolean.TRUE.equals(tx.execute(status -> {
            Intent locked = store.lock(intentId)
                    .filter(i -> principal.may("core.payments.allocate_approve", i.branchId()))
                    .orElseThrow(ApiException::notFound);
            requireInReview(locked);
            if (locked.lastCheckedAt() != null
                    && locked.lastCheckedAt().plus(CheckSchedule.THROTTLE).isAfter(now)) {
                if (locked.checkFailures() > 0) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "try_again",
                            "Try again",
                            "The payment provider could not be asked just now, so nothing was raised. Try again in a moment.");
                }
                return true;
            }
            store.update(intentId, "reversed_pending_review", null, now, Map.of("last_checked_at", now));
            return false;
        }));
        // The provider is asked outside any transaction: a payment it now reports completed is not reversed, and a
        // handed
        // over order is not polled, so its answer may be old. If it cannot be asked, nothing is raised.
        StatusChecker.Asked answer = recent ? StatusChecker.Asked.REVERSED : checker.verifyInReview(intentId);
        if (answer == StatusChecker.Asked.UNREACHABLE) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "try_again",
                    "Try again",
                    "The payment provider could not be asked just now, so nothing was raised. Try again in a moment.");
        }
        UUID request = tx.execute(status -> {
            Intent intent = store.lock(intentId).orElseThrow(ApiException::notFound);
            requireInReview(intent);
            if (answer == StatusChecker.Asked.COMPLETED) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "completed_again",
                        "The payment is reported completed",
                        "The payment provider now reports this payment completed, so it was not reversed and nothing was raised.");
            }
            if (answer == StatusChecker.Asked.OTHER) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "provider_disagrees",
                        "The provider does not report a reversal",
                        "The payment provider does not report this payment reversed just now, so nothing was raised. A person should look at it with the provider.");
            }
            Raised raised = reversals.raise(intent, true);
            if (raised.waiting().isPresent()) {
                return raised.waiting().get();
            }
            if (!raised.fin()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "try_again",
                        "Try again",
                        "The payment could not be read just now, or a checker is deciding the earlier request. Try again.");
            }
            throw ApiException.rule(
                    "nothing_to_reverse",
                    "Nothing stands in the books for this payment, so there is nothing to reverse.");
        });
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Outcome(intentId, request));
    }

    private static void requireInReview(Intent intent) {
        if ("reversed_pending_review".equals(intent.status())) {
            return;
        }
        if ("booked".equals(intent.status()) || "booked_unallocated".equals(intent.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "completed_again",
                    "The payment is reported completed",
                    "The payment provider reports this payment completed, so it is not waiting for a reversal.");
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "not_in_review",
                "Not waiting for a reversal",
                "The payment is " + intent.status() + ", not waiting for a reversal to be decided.");
    }
}
