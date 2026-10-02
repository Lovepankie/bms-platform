package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.loans.internal.LoanApi.CreateLoanRequest;
import com.rincoltech.bms.lending.loans.internal.LoanApi.GuarantorsRequest;
import com.rincoltech.bms.lending.loans.internal.LoanApi.LoanPage;
import com.rincoltech.bms.lending.loans.internal.LoanApi.LoanResponse;
import com.rincoltech.bms.lending.loans.internal.LoanApi.NoteRequest;
import com.rincoltech.bms.lending.loans.internal.LoanApi.PledgesRequest;
import com.rincoltech.bms.lending.loans.internal.LoanApi.StatusHistory;
import com.rincoltech.bms.lending.loans.internal.LoanApi.UpdateLoanRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/lending/loans}: origination (chapter 7 section 7.11.13; FR-ORG-01 to FR-ORG-03). */
@RestController
@RequestMapping(path = "/api/v1/lending/loans", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-loans")
class LoanController {

    private final LoanService service;
    private final AppraisalService appraisals;

    LoanController(LoanService service, AppraisalService appraisals) {
        this.service = service;
        this.appraisals = appraisals;
    }

    @PostMapping("/{loan_id}/appraisals")
    @RequiresPermission("lending.loans.appraise")
    @Operation(
            summary = "Appraise: score, exposure and an immutable snapshot (FR-ORG-04, FR-ORG-05)",
            operationId = "appraiseLoan")
    ResponseEntity<AppraisalService.Appraisal> appraise(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody AppraisalService.AppraisalRequest request) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(appraisals.appraise(id, ifMatch, request));
    }

    @GetMapping("/{loan_id}/appraisals")
    @RequiresPermission("lending.loans.read")
    @Operation(summary = "A loan's appraisals, newest first", operationId = "listLoanAppraisals")
    AppraisalService.AppraisalList appraisals(@PathVariable("loan_id") UUID id) {
        return appraisals.list(id);
    }

    @GetMapping
    @RequiresPermission("lending.loans.read")
    @Operation(summary = "List loans in the caller's branch scope", operationId = "listLoans")
    LoanPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "member_id", required = false) UUID memberId,
            @RequestParam(name = "officer_user_id", required = false) UUID officerId,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(
                branchIds, statuses == null ? List.of() : statuses, memberId, officerId, productId, limit, cursor);
    }

    @PostMapping
    @RequiresPermission("lending.loans.create")
    @Operation(summary = "Create a draft application (FR-ORG-01)", operationId = "createLoan")
    ResponseEntity<LoanResponse> create(@Valid @RequestBody CreateLoanRequest request) {
        LoanResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/lending/loans/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/{loan_id}")
    @RequiresPermission("lending.loans.read")
    @Operation(summary = "A loan with guarantors, collateral and its provisional schedule", operationId = "getLoan")
    ResponseEntity<LoanResponse> get(@PathVariable("loan_id") UUID id) {
        return withETag(service.get(id));
    }

    @GetMapping("/{loan_id}/status-history")
    @RequiresPermission("lending.loans.read")
    @Operation(summary = "Every status move of a loan", operationId = "getLoanStatusHistory")
    StatusHistory history(@PathVariable("loan_id") UUID id) {
        return service.history(id);
    }

    @PatchMapping("/{loan_id}")
    @RequiresPermission("lending.loans.create")
    @Operation(summary = "Edit a draft application", operationId = "updateLoan")
    ResponseEntity<LoanResponse> update(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateLoanRequest request) {
        return withETag(service.update(id, ifMatch, request));
    }

    @PutMapping("/{loan_id}/guarantors")
    @RequiresPermission("lending.loans.create")
    @Operation(summary = "Set a draft's guarantors (FR-ORG-02)", operationId = "setLoanGuarantors")
    ResponseEntity<LoanResponse> guarantors(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody GuarantorsRequest request) {
        return withETag(service.setGuarantors(id, ifMatch, request.guarantors()));
    }

    @PutMapping("/{loan_id}/collateral")
    @RequiresPermission("lending.loans.create")
    @Operation(summary = "Set a draft's pledged collateral (FR-ORG-02)", operationId = "setLoanCollateral")
    ResponseEntity<LoanResponse> collateral(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody PledgesRequest request) {
        return withETag(service.setPledges(id, ifMatch, request.collateral()));
    }

    @PostMapping("/{loan_id}/submit")
    @RequiresPermission("lending.loans.create")
    @Operation(summary = "Submit a draft, freezing its terms (FR-ORG-03)", operationId = "submitLoan")
    ResponseEntity<LoanResponse> submit(
            @PathVariable("loan_id") UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withETag(service.submit(id, ifMatch));
    }

    @PostMapping("/{loan_id}/return")
    @RequiresPermission("lending.loans.approve")
    @Operation(summary = "Return an application to draft with a note", operationId = "returnLoan")
    ResponseEntity<LoanResponse> returnForCorrection(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody NoteRequest request) {
        return withETag(service.returnForCorrection(id, ifMatch, request.note()));
    }

    @PostMapping("/{loan_id}/cancel")
    @RequiresPermission("lending.loans.cancel")
    @Operation(summary = "Cancel an application with a reason", operationId = "cancelLoan")
    ResponseEntity<LoanResponse> cancel(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody NoteRequest request) {
        return withETag(service.cancel(id, ifMatch, request.note()));
    }

    private static ResponseEntity<LoanResponse> withETag(LoanResponse l) {
        return ResponseEntity.ok().eTag(String.valueOf(l.version())).body(l);
    }
}
