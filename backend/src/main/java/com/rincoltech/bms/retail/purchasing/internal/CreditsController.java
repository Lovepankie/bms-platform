package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.Credit;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.CreditList;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.CreditRequest;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.VoidRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * {@code /api/v1/retail/supplier-credits} (purchasing capability; ADR-033, issue #199; chapter 7 section 7.11.20): supplier
 * credit notes against a supplier invoice, for goods sent back or for a price adjustment. Each answers 404
 * {@code capability_not_enabled} while the capability is off, and needs {@code retail.payable.manage} in the branch of the
 * invoice and of every received line it credits.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/supplier-credits", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-payables")
class CreditsController {

    private final CreditsService service;

    CreditsController(CreditsService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "Record a supplier credit note against an invoice: goods sent back to the supplier or a price"
                    + " adjustment; it takes the amount off what the invoice leaves to pay (issue #199); M",
            operationId = "createRetailSupplierCredit")
    ResponseEntity<Credit> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreditRequest request) {
        Outcome<Credit> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/supplier-credits/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "Supplier credit notes in the caller's branch scope, newest first, without lines (issue #199)",
            operationId = "listRetailSupplierCredits")
    CreditList list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId,
            @RequestParam(name = "bill_id", required = false) UUID billId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(branchIds, supplierId, billId, status, from, to, q, limit, cursor);
    }

    @GetMapping("/{credit_id}")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "One supplier credit note with its lines (issue #199)",
            operationId = "getRetailSupplierCredit")
    Credit get(@PathVariable("credit_id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/{credit_id}/void")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "Void a credit note as a whole by reversal, once; goods go back into stock; refused when a price credit's"
                            + " goods have moved on (issue #199); M",
            operationId = "voidRetailSupplierCredit")
    ResponseEntity<Credit> voidCredit(
            @PathVariable("credit_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VoidRequest request) {
        Outcome<Credit> outcome = service.voidCredit(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
