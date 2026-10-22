package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.CancelRequest;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ExistingLoan;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ExistingLoanPage;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.GoLive;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ImportRequest;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ImportResult;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.RecordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
 * {@code /api/v1/lending/existing-loans}: loans brought in from a paper register with their real dates and past
 * repayments (chapter 7 section 7.11.13; FR-EXL-01 to FR-EXL-08, ADR-071). Recording needs an {@code Idempotency-Key};
 * a check ({@code dry_run=true}) saves nothing.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/existing-loans", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-loans")
class ExistingLoanController {

    private final ExistingLoanService service;
    private final ExistingLoanImport importer;

    ExistingLoanController(ExistingLoanService service, ExistingLoanImport importer) {
        this.service = service;
        this.importer = importer;
    }

    @GetMapping("/go-live")
    @RequiresPermission(ExistingLoanService.PERMISSION)
    @Operation(
            summary =
                    "The business's go-live date: set by the Owner or the operator, else the first administrator sign-in (ADR-071)",
            operationId = "getLendingGoLiveDate")
    GoLive goLive() {
        return service.goLive();
    }

    @PostMapping
    @RequiresPermission(ExistingLoanService.PERMISSION)
    @Operation(
            summary = "Record an existing loan with its real disbursement date and past repayments; 201 brought_in and"
                    + " waiting for a checker, or with dry_run=true 200 and the position it would have, saving nothing"
                    + " (FR-EXL-01 to FR-EXL-03)",
            operationId = "recordExistingLoan")
    ResponseEntity<ExistingLoan> record(
            @RequestParam(name = "dry_run", required = false, defaultValue = "false") boolean dryRun,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RecordRequest request) {
        Outcome<ExistingLoan> o = service.record(request, dryRun, key);
        if (dryRun) {
            return ResponseEntity.ok(o.body());
        }
        var response = ResponseEntity.created(
                URI.create("/api/v1/lending/existing-loans/" + o.body().loanId()));
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }

    @GetMapping
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary =
                    "Loans brought in from the register, newest first (at most 200); status filters, for example brought_in",
            operationId = "listExistingLoans")
    ExistingLoanPage list(@RequestParam(name = "status", required = false) List<String> statuses) {
        return service.list(statuses);
    }

    @GetMapping("/{loan_id}")
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary = "An existing loan as recorded: its repayments, schedule and position (FR-EXL-02)",
            operationId = "getExistingLoan")
    ExistingLoan get(@PathVariable("loan_id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/{loan_id}/cancel")
    @RequiresPermission(ExistingLoanService.PERMISSION)
    @Operation(
            summary = "Withdraw an existing loan still waiting for its checker, with a reason; it ends cancelled",
            operationId = "cancelExistingLoan")
    ExistingLoan cancel(@PathVariable("loan_id") UUID id, @Valid @RequestBody CancelRequest request) {
        return service.cancel(id, request.reason());
    }

    @PostMapping("/{loan_id}/approval")
    @RequiresPermission(ExistingLoanService.PERMISSION)
    @Operation(
            summary = "Ask for approval again after a rejected, expired or stale request; checked against today's"
                    + " go-live date (FR-EXL-04)",
            operationId = "requestExistingLoanApproval")
    ResponseEntity<ExistingLoan> askAgain(@PathVariable("loan_id") UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.askAgain(id));
    }

    @PostMapping("/{loan_id}/reversal")
    @RequiresPermission(ExistingLoanService.PERMISSION)
    @Operation(
            summary =
                    "Ask a second person to reverse an approved bring-in, with a reason: the opening entry is mirrored,"
                            + " the historic transactions are marked reversed and the loan is cancelled; refused once the loan"
                            + " has a transaction in the app or a penalty charge (FR-EXL-08)",
            operationId = "requestExistingLoanReversal")
    ResponseEntity<ExistingLoan> requestReversal(
            @PathVariable("loan_id") UUID id, @Valid @RequestBody CancelRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requestReversal(id, request.reason()));
    }

    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(ExistingLoanService.PERMISSION)
    @Operation(
            summary = "Import existing loans and their repayments from CSV: check first (dry_run=true, the default),"
                    + " then commit; a commit with any error records nothing (FR-EXL-06)",
            operationId = "importExistingLoans")
    ImportResult importLoans(
            @RequestParam(name = "dry_run", required = false, defaultValue = "true") boolean dryRun,
            @Valid @RequestBody ImportRequest request) {
        return importer.run(request.csv(), dryRun);
    }
}
