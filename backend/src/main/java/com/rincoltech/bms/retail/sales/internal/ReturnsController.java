package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnList;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnOutcome;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnRequest;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.SaleReturn;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.VoidReturnRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
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
 * {@code /api/v1/retail} customer return routes (returns capability; ADR-033, issue #201; chapter 7 section 7.11.20).
 * Each answers 404 {@code capability_not_enabled} while the capability is off.
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-returns")
class ReturnsController {

    private final ReturnsService service;

    ReturnsController(ReturnsService service) {
        this.service = service;
    }

    @PostMapping("/sales/{sale_id}/returns")
    @RequiresPermission("retail.sale.return")
    @Operation(
            summary = "Take goods back against a sale: stock, entries and a numbered credit note (issue #201); M."
                    + " 201 when recorded, 202 when it waits for a checker",
            operationId = "createRetailSaleReturn")
    ResponseEntity<ReturnOutcome> create(
            @PathVariable("sale_id") UUID saleId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReturnRequest request) {
        Outcome<ReturnOutcome> outcome = service.create(idempotencyKey, saleId, request);
        ResponseEntity.BodyBuilder response;
        if (outcome.body().executed()) {
            response = ResponseEntity.created(URI.create(
                    "/api/v1/retail/returns/" + outcome.body().saleReturn().id()));
        } else {
            response = ResponseEntity.status(HttpStatus.ACCEPTED);
        }
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/sales/{sale_id}/returns")
    @RequiresPermission("retail.sale.return")
    @Operation(summary = "The returns made against a sale (issue #201)", operationId = "listRetailSaleReturns")
    List<SaleReturn> forSale(@PathVariable("sale_id") UUID saleId) {
        return service.forSale(saleId);
    }

    @GetMapping("/returns")
    @RequiresPermission("retail.sale.return")
    @Operation(
            summary = "Credit notes in the caller's branch scope, newest first (issue #201)",
            operationId = "listRetailReturns")
    ReturnList list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "customer_id", required = false) UUID customerId,
            @RequestParam(name = "sale_id", required = false) UUID saleId,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(branchIds, from, to, customerId, saleId, q, status, limit, cursor);
    }

    @GetMapping("/returns/{return_id}")
    @RequiresPermission("retail.sale.return")
    @Operation(summary = "One return, the credit note, with its lines (issue #201)", operationId = "getRetailReturn")
    SaleReturn get(@PathVariable("return_id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/returns/{return_id}/void")
    @RequiresPermission("retail.sale.return")
    @Operation(
            summary =
                    "Void a return as a whole by reversal (issue #201); M. 200 when voided, 202 when it waits for a checker",
            operationId = "voidRetailReturn")
    ResponseEntity<ReturnOutcome> voidReturn(
            @PathVariable("return_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VoidReturnRequest request) {
        Outcome<ReturnOutcome> outcome = service.voidReturn(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response =
                outcome.body().executed() ? ResponseEntity.ok() : ResponseEntity.status(HttpStatus.ACCEPTED);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
