package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralDetail;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralPage;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralResponse;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CreateCollateralRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.EventRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.ReleaseOutcome;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.ReleaseRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.UpdateCollateralRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.Valuation;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.ValuationRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralService.CollateralDocument;
import com.rincoltech.bms.lending.collateral.internal.CollateralService.CollateralDocumentList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /api/v1/lending/collateral} (chapter 7 section 7.11.14; FR-COL-01 to FR-COL-05). */
@RestController
@RequestMapping(path = "/api/v1/lending/collateral", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-collateral")
class CollateralController {

    private final CollateralService service;

    CollateralController(CollateralService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("lending.collateral.read")
    @Operation(summary = "List collateral in the caller's branch scope", operationId = "listCollateral")
    CollateralPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "member_id", required = false) UUID memberId,
            @RequestParam(name = "type", required = false) List<String> types,
            @RequestParam(name = "custody_status", required = false) List<String> statuses,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(
                branchIds,
                memberId,
                types == null ? List.of() : types,
                statuses == null ? List.of() : statuses,
                limit,
                cursor);
    }

    @PostMapping
    @RequiresPermission("lending.collateral.manage")
    @Operation(summary = "Register a collateral item (FR-COL-01)", operationId = "createCollateral")
    ResponseEntity<CollateralResponse> create(@Valid @RequestBody CreateCollateralRequest request) {
        CollateralResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/lending/collateral/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/{collateral_id}")
    @RequiresPermission("lending.collateral.read")
    @Operation(summary = "An item with its valuations and custody timeline", operationId = "getCollateral")
    ResponseEntity<CollateralDetail> get(@PathVariable("collateral_id") UUID id) {
        CollateralDetail detail = service.get(id);
        return ResponseEntity.ok().eTag(String.valueOf(detail.item().version())).body(detail);
    }

    @PatchMapping("/{collateral_id}")
    @RequiresPermission("lending.collateral.manage")
    @Operation(summary = "Edit a collateral item", operationId = "updateCollateral")
    ResponseEntity<CollateralResponse> update(
            @PathVariable("collateral_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateCollateralRequest request) {
        CollateralResponse c = service.update(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(c.version())).body(c);
    }

    @PostMapping("/{collateral_id}/valuations")
    @RequiresPermission("lending.collateral.manage")
    @Operation(summary = "Record a valuation (FR-COL-02)", operationId = "addCollateralValuation")
    ResponseEntity<Valuation> addValuation(
            @PathVariable("collateral_id") UUID id, @Valid @RequestBody ValuationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addValuation(id, request));
    }

    @PostMapping("/{collateral_id}/events")
    @RequiresPermission("lending.collateral.manage")
    @Operation(
            summary = "Record a custody change other than release (FR-COL-03)",
            operationId = "recordCollateralEvent")
    ResponseEntity<CollateralDetail> recordEvent(
            @PathVariable("collateral_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody EventRequest request) {
        CollateralDetail detail = service.recordEvent(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(detail.item().version())).body(detail);
    }

    @PostMapping("/{collateral_id}/release")
    @RequiresPermission("lending.collateral.release_request")
    @Operation(summary = "Request release, decided by a checker (FR-COL-04)", operationId = "requestCollateralRelease")
    ResponseEntity<ReleaseOutcome> release(
            @PathVariable("collateral_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ReleaseRequest request) {
        ReleaseOutcome outcome = service.requestRelease(id, ifMatch, request);
        return ResponseEntity.status(outcome.executed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(outcome);
    }

    @GetMapping("/{collateral_id}/documents")
    @RequiresPermission("lending.collateral.read")
    @Operation(summary = "List an item's photos and scans", operationId = "listCollateralDocuments")
    CollateralDocumentList listDocuments(@PathVariable("collateral_id") UUID id) {
        return service.listDocuments(id);
    }

    @PostMapping(path = "/{collateral_id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("lending.collateral.manage")
    @Operation(
            summary = "Upload a photo or scan: JPEG, PNG or PDF, 5 MB (FR-COL-01)",
            operationId = "uploadCollateralDocument")
    ResponseEntity<CollateralDocument> uploadDocument(
            @PathVariable("collateral_id") UUID id, @RequestPart("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "The upload could not be read.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.uploadDocument(id, bytes));
    }
}
