package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.internal.TransferApi.Transfer;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferPage;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferRequest;
import com.rincoltech.bms.retail.stock.internal.TransferApi.VoidRequest;
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

/** {@code /api/v1/retail/transfers} (chapter 7 section 7.11.20; FR-RET-16). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class TransferController {

    private final TransferService service;

    TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/transfers")
    @RequiresPermission("retail.stock.transfer")
    @Operation(
            summary = "Move stock from one branch to another at cost, in one transaction (FR-RET-16); M",
            operationId = "createRetailTransfer")
    ResponseEntity<Transfer> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        Outcome<Transfer> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/transfers/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/transfers")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Transfers from or to a branch in the caller's scope, newest first",
            operationId = "listRetailTransfers")
    TransferPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(branchIds, productId, from, to, limit, cursor);
    }

    @GetMapping("/transfers/{transfer_id}")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "A transfer with its lines", operationId = "getRetailTransfer")
    Transfer get(@PathVariable("transfer_id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/transfers/{transfer_id}/void")
    @RequiresPermission("retail.stock.transfer")
    @Operation(
            summary = "Void a transfer while the destination still holds its stock (FR-RET-16)",
            operationId = "voidRetailTransfer")
    Transfer voidTransfer(@PathVariable("transfer_id") UUID id, @Valid @RequestBody VoidRequest request) {
        return service.voidTransfer(id, request);
    }
}
