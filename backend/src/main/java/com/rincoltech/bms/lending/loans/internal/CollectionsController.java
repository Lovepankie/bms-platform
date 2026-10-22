package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.Action;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ActionList;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ActionRequest;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ArrearsList;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.DueList;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.OfficerRequest;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.PromiseList;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ReassignRequest;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ReassignResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Collections (chapter 7 section 7.11.17 and the loan's officer and actions, FR-CLN-01 to FR-CLN-04). */
@RestController
@RequestMapping(path = "/api/v1/lending", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-collections")
class CollectionsController {

    private final CollectionsService service;

    CollectionsController(CollectionsService service) {
        this.service = service;
    }

    @PutMapping("/loans/{loan_id}/officer")
    @RequiresPermission("lending.collections.assign")
    @Operation(summary = "Reassign a loan's responsible officer (FR-CLN-01)", operationId = "setLoanOfficer")
    ReassignResult setOfficer(@PathVariable("loan_id") UUID id, @Valid @RequestBody OfficerRequest request) {
        return service.reassign(List.of(id), request.officerUserId(), request.reason());
    }

    @PostMapping("/collections/reassign")
    @RequiresPermission("lending.collections.assign")
    @Operation(summary = "Reassign many loans to one officer, all or none (FR-CLN-01)", operationId = "reassignLoans")
    ReassignResult reassign(@Valid @RequestBody ReassignRequest request) {
        return service.reassign(request.loanIds(), request.officerUserId(), request.reason());
    }

    @GetMapping("/loans/{loan_id}/collection-actions")
    @RequiresPermission("lending.collections.read")
    @Operation(summary = "A loan's collection actions, newest first (FR-CLN-04)", operationId = "listCollectionActions")
    ActionList actions(@PathVariable("loan_id") UUID id) {
        return service.actions(id);
    }

    @PostMapping("/loans/{loan_id}/collection-actions")
    @RequiresPermission("lending.collections.log_action")
    @Operation(
            summary = "Log a call, visit, SMS, letter, promise to pay or other action (FR-CLN-04)",
            operationId = "logCollectionAction")
    ResponseEntity<Action> log(@PathVariable("loan_id") UUID id, @Valid @RequestBody ActionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.logAction(id, request));
    }

    @GetMapping("/collections/due-list")
    @RequiresPermission("lending.collections.read")
    @Operation(
            summary = "Every item of an active loan due in a range, today by default (FR-CLN-02)",
            operationId = "getDueList")
    DueList dueList(
            @RequestParam(name = "date_from", required = false) LocalDate from,
            @RequestParam(name = "date_to", required = false) LocalDate to,
            @RequestParam(name = "officer_user_id", required = false) UUID officerId,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.dueList(from, to, officerId, branchIds, limit);
    }

    @GetMapping("/collections/arrears")
    @RequiresPermission("lending.collections.read")
    @Operation(summary = "Loans in arrears by PAR bucket (FR-CLN-03)", operationId = "getArrearsList")
    ArrearsList arrears(
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "officer_user_id", required = false) UUID officerId,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.arrears(bucket, officerId, branchIds, limit);
    }

    @GetMapping("/collections/promises")
    @RequiresPermission("lending.collections.read")
    @Operation(summary = "Promises to pay by status, pending by default (FR-CLN-05)", operationId = "listPromises")
    PromiseList promises(
            @RequestParam(name = "promise_status", required = false) String status,
            @RequestParam(name = "officer_user_id", required = false) UUID officerId,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.promises(status, officerId, branchIds, limit);
    }
}
