package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.PaymentCollections;
import com.rincoltech.bms.core.payments.PaymentCollections.CreateIntent;
import com.rincoltech.bms.core.payments.PaymentCollections.IntentView;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/payments/intents} (chapter 7 section 7.11.10, FR-PAY-01): start a collection, read it (the screen polls
 * every three seconds), cancel it. Starting needs {@code core.payments.collect} in the branch of the subject and an
 * {@code Idempotency-Key}; reading is for the initiator and for holders of {@code core.payments.read}, and anyone else
 * is told the intent does not exist. The answer is the intent as it stands: normally {@code pending} with the
 * {@code redirect_url} the payer opens (a link, a QR code on the cashier's screen).
 */
@RestController
@RequestMapping(path = "/api/v1/payments/intents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "payments")
class IntentsController {

    private final PaymentCollections collections;

    IntentsController(PaymentCollections collections) {
        this.collections = collections;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.payments.collect")
    @Operation(
            summary =
                    "Start a collection by mobile money or card: writes the intent, asks the provider for the order (FR-PAY-01)",
            operationId = "createPaymentIntent")
    ResponseEntity<IntentView> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateIntent request,
            HttpServletResponse response) {
        NoStore.apply(response);
        IntentView created = collections.create(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/payments/intents/" + created.id()))
                .body(created);
    }

    @GetMapping("/{intent_id}")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "One intent: its status and the redirect url; the initiator, or a holder of core.payments.read",
            operationId = "getPaymentIntent")
    IntentView get(@PathVariable("intent_id") UUID intentId, HttpServletResponse response) {
        NoStore.apply(response);
        return collections.get(intentId);
    }

    @PostMapping("/{intent_id}/cancel")
    @RequiresPermission("core.payments.collect")
    @Operation(
            summary = "Cancel an intent that is not paid; the order is cancelled at the provider first",
            operationId = "cancelPaymentIntent")
    IntentView cancel(@PathVariable("intent_id") UUID intentId, HttpServletResponse response) {
        NoStore.apply(response);
        return collections.cancel(intentId);
    }
}
