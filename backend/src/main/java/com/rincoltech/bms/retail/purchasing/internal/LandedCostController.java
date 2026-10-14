package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.LandedCost;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.LandedCostList;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.LandedCostRequest;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.Preview;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.VoidRequest;
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
 * {@code /api/v1/retail/landed-costs} (purchasing capability; ADR-033, issue #199; chapter 7 section 7.11.20): freight, duty,
 * clearing and other charges spread over received lines. Each answers 404 {@code capability_not_enabled} while the
 * capability is off, and needs {@code retail.payable.manage} (the administrator's) in the branch of every received line.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/landed-costs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-payables")
class LandedCostController {

    private final LandedCostService service;

    LandedCostController(LandedCostService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "Record a landed cost: charges of one payee spread over received lines by value, quantity, weight or"
                            + " typed amounts; adds to inventory for goods on hand and to cost of goods sold for goods"
                            + " already sold, moves the product's cost when the receipt is still its latest cost event, and"
                            + " creates the payee's invoice (issue #199); M",
            operationId = "createRetailLandedCost")
    ResponseEntity<LandedCost> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LandedCostRequest request) {
        Outcome<LandedCost> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/landed-costs/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @PostMapping("/preview")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "How a landed cost would be spread and what it would do, without recording it (issue #199)",
            operationId = "previewRetailLandedCost")
    Preview preview(@Valid @RequestBody LandedCostRequest request) {
        return service.preview(request);
    }

    @GetMapping
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "Landed costs in the caller's branch scope, newest first, without charges or lines (issue #199)",
            operationId = "listRetailLandedCosts")
    LandedCostList list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId,
            @RequestParam(name = "receipt_id", required = false) UUID receiptId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(branchIds, supplierId, receiptId, status, from, to, q, limit, cursor);
    }

    @GetMapping("/{landed_cost_id}")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "One landed cost with its charges and its allocation (issue #199)",
            operationId = "getRetailLandedCost")
    LandedCost get(@PathVariable("landed_cost_id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/{landed_cost_id}/void")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "Void a landed cost as a whole by reversal, once; refused while a payment stands against the payee's"
                            + " invoice, while a credit note touches its lines, or when the goods have moved on"
                            + " (issue #199); M",
            operationId = "voidRetailLandedCost")
    ResponseEntity<LandedCost> voidCost(
            @PathVariable("landed_cost_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VoidRequest request) {
        Outcome<LandedCost> outcome = service.voidCost(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
