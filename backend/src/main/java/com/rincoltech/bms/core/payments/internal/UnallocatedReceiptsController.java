package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.ActionOutcome;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.AllocateRequest;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.ReceiptPage;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.ReceiptView;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.RefundRequest;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/payments/unallocated} (chapter 7 section 7.11.10, FR-PAY-04): the queue of money the provider confirmed and
 * the loan or account would not take, with the two ways out. Reading needs {@code core.payments.read} in the receipt's branch;
 * asking to allocate or to refund needs {@code core.payments.allocate} and an {@code Idempotency-Key}, and the answer is always
 * 202 with the approval request a holder of {@code core.payments.allocate_approve} decides: nothing moves until then, and
 * the receipt ends exactly once whatever is asked or approved twice. Every response is {@code no-store}.
 */
@RestController
@RequestMapping(path = "/api/v1/payments/unallocated", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "payments")
class UnallocatedReceiptsController {

    private final ReceiptService service;

    UnallocatedReceiptsController(ReceiptService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("core.payments.read")
    @Operation(
            summary =
                    "The queue of unallocated receipts, oldest first (open by default; status open, allocated, refunded, reversed or all)",
            operationId = "listUnallocatedReceipts")
    ReceiptPage list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.list(status, branchIds, limit, cursor);
    }

    @GetMapping("/{receipt_id}")
    @RequiresPermission("core.payments.read")
    @Operation(summary = "One unallocated receipt with its requests", operationId = "getUnallocatedReceipt")
    ReceiptView get(@PathVariable("receipt_id") UUID id, HttpServletResponse response) {
        NoStore.apply(response);
        return service.get(id);
    }

    @PostMapping(path = "/{receipt_id}/allocate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.payments.allocate")
    @Operation(
            summary = "Ask to put a receipt's money on a loan or a savings account (maker-checker, 202)",
            operationId = "allocateUnallocatedReceipt")
    ResponseEntity<ActionOutcome> allocate(
            @PathVariable("receipt_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AllocateRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return accepted(service.requestAllocation(id, key, request));
    }

    @PostMapping(path = "/{receipt_id}/refund", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.payments.allocate")
    @Operation(
            summary = "Ask to record that a receipt's money was sent back to the payer (maker-checker, 202)",
            operationId = "refundUnallocatedReceipt")
    ResponseEntity<ActionOutcome> refund(
            @PathVariable("receipt_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RefundRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return accepted(service.requestRefund(id, key, request));
    }

    private static ResponseEntity<ActionOutcome> accepted(Outcome<ActionOutcome> o) {
        var response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }
}
