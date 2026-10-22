package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Clash;
import com.rincoltech.bms.retail.sales.internal.SalesApi.ClashPage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.ClashResolveRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.DiscardRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Failure;
import com.rincoltech.bms.retail.sales.internal.SalesApi.FailurePage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.FailureRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.WaitingAccepted;
import com.rincoltech.bms.retail.sales.internal.SalesApi.WaitingPage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.WaitingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Clashes of sales recorded offline and refused offline sales (ADR-067; chapter 7 section 7.11.20; FR-RET-67). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-sales")
class OfflineSalesController {

    private final OfflineSalesService service;

    OfflineSalesController(OfflineSalesService service) {
        this.service = service;
    }

    @GetMapping("/sale-clashes")
    @RequiresPermission(OfflineSalesService.RESOLVE)
    @Operation(
            summary = "Clashes of sales recorded offline in the caller's branch scope, newest first (ADR-067)",
            operationId = "listRetailSaleClashes")
    ClashPage clashes(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds) {
        return service.clashes(status, branchIds);
    }

    @PostMapping("/sale-clashes/{clash_id}/resolve")
    @RequiresPermission(OfflineSalesService.RESOLVE)
    @Operation(
            summary = "Resolve a clash with a note, once; audited (ADR-067). Nothing else changes",
            operationId = "resolveRetailSaleClash")
    Clash resolve(@PathVariable("clash_id") UUID clashId, @Valid @RequestBody ClashResolveRequest request) {
        return service.resolve(clashId, request.note());
    }

    @PostMapping("/offline-failures")
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary =
                    "Report a sale made offline that the server refused, for a manager to decide (ADR-067); idempotent on"
                            + " the client sale id",
            operationId = "reportRetailOfflineFailure")
    Failure report(@Valid @RequestBody FailureRequest request) {
        return service.report(request);
    }

    @GetMapping("/offline-failures/mine")
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary =
                    "The caller's own reported offline sales: these client sale ids, or every one open or waiting to be"
                            + " sent again (ADR-067)",
            operationId = "listMyRetailOfflineFailures")
    FailurePage mine(@RequestParam(name = "client_sale_id", required = false) List<UUID> clientSaleIds) {
        return service.mine(clientSaleIds);
    }

    @GetMapping("/offline-failures")
    @RequiresPermission(OfflineSalesService.RESOLVE)
    @Operation(
            summary = "Reported offline sales in the caller's branch scope, open and retry by default (ADR-067)",
            operationId = "listRetailOfflineFailures")
    FailurePage failures(
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds) {
        return service.failures(statuses, branchIds);
    }

    @PostMapping("/offline-failures/{failure_id}/retry")
    @RequiresPermission(OfflineSalesService.RESOLVE)
    @Operation(
            summary = "Ask the device that reported it to send the sale again (ADR-067); audited",
            operationId = "retryRetailOfflineFailure")
    Failure retry(@PathVariable("failure_id") UUID id) {
        return service.retry(id);
    }

    @PostMapping("/offline-waiting")
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary = "A phone tells which sales other people left queued on it, in short, so managers see them waiting"
                    + " (ADR-067); upserted on the client sale id",
            operationId = "reportRetailOfflineWaiting")
    WaitingAccepted waiting(@Valid @RequestBody WaitingRequest request) {
        return service.reportWaiting(request);
    }

    @GetMapping("/offline-waiting")
    @RequiresPermission(OfflineSalesService.RESOLVE)
    @Operation(
            summary = "Sales waiting on a phone in the caller's branch scope, oldest first (ADR-067)",
            operationId = "listRetailOfflineWaiting")
    WaitingPage waitingList(@RequestParam(name = "branch_id", required = false) List<UUID> branchIds) {
        return service.waiting(branchIds);
    }

    @PostMapping("/offline-failures/{failure_id}/discard")
    @RequiresPermission(OfflineSalesService.RESOLVE)
    @Operation(
            summary =
                    "Discard a refused offline sale with a reason; it is never recorded; audited (ADR-067). The caller's own"
                            + " sale only by an administrator, with self_discard_reason",
            operationId = "discardRetailOfflineFailure")
    Failure discard(@PathVariable("failure_id") UUID id, @Valid @RequestBody DiscardRequest request) {
        return service.discard(id, request.reason(), request.selfDiscardReason());
    }
}
