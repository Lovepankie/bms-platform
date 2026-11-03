package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.internal.StatementApi.RowView;
import com.rincoltech.bms.core.payments.internal.StatementApi.StatementDetail;
import com.rincoltech.bms.core.payments.internal.StatementApi.StatementList;
import com.rincoltech.bms.core.payments.internal.StatementService.Uploaded;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/v1/payments/statements} (chapter 7 section 7.11.10, FR-PAY-11): the operator's merchant statement as a CSV, matched
 * to the business's own orders, with the fee of each clean line posted once. Needs {@code core.payments.manage}. The same file twice
 * answers 200 with the first statement and posts nothing; a new statement answers 201. A file with any unreadable line is refused
 * whole (422, the first twenty problems with their line numbers) and nothing is stored.
 */
@RestController
@RequestMapping(path = "/api/v1/payments/statements", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "payments")
class StatementsController {

    private final StatementService service;

    StatementsController(StatementService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("core.payments.manage")
    @Operation(
            summary =
                    "Upload the merchant statement (CSV, up to 1 MB and 5,000 lines): match its lines to our orders and post each clean line's fee once (FR-PAY-11)",
            operationId = "uploadPaymentStatement")
    ResponseEntity<StatementDetail> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "covered_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate coveredFrom,
            @RequestParam(name = "covered_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate coveredTo,
            HttpServletResponse response) {
        NoStore.apply(response);
        byte[] bytes;
        try {
            if (file.getSize() > StatementCsv.MAX_BYTES) {
                throw ApiException.rule("file_too_large", "The statement is larger than 1 MB.");
            }
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "The upload could not be read.");
        }
        Uploaded uploaded = service.upload(file.getOriginalFilename(), bytes, coveredFrom, coveredTo);
        var answer = ResponseEntity.status(uploaded.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (uploaded.replayed()) {
            answer.header("Idempotent-Replayed", "true");
        }
        return answer.body(uploaded.detail());
    }

    @GetMapping
    @RequiresPermission("core.payments.manage")
    @Operation(summary = "The statements uploaded, newest first", operationId = "listPaymentStatements")
    StatementList list(HttpServletResponse response) {
        NoStore.apply(response);
        return service.list();
    }

    @PostMapping(path = "/{statement_id}/rows/{row_no}/outside-system", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.payments.manage")
    @Operation(
            summary =
                    "Mark a statement line no order of ours has as money for something outside this system, so it is no longer reported (audited)",
            operationId = "markStatementLineOutsideSystem")
    RowView outside(
            @PathVariable("statement_id") UUID id,
            @PathVariable("row_no") int rowNo,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody OutsideRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.markOutside(id, rowNo, request.note());
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "StatementLineOutsideRequest")
    record OutsideRequest(
            @jakarta.validation.constraints.Size(max = 300) String note) {}

    @GetMapping("/{statement_id}")
    @RequiresPermission("core.payments.manage")
    @Operation(summary = "One statement with how each line matched", operationId = "getPaymentStatement")
    StatementDetail get(@PathVariable("statement_id") UUID id, HttpServletResponse response) {
        NoStore.apply(response);
        return service.get(id);
    }
}
