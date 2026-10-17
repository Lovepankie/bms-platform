package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.SmsOptOuts.Scope;
import com.rincoltech.bms.core.notifications.internal.SmsOptOutService.ImportResult;
import com.rincoltech.bms.core.notifications.internal.SmsOptOutService.Item;
import com.rincoltech.bms.core.notifications.internal.SmsOptOutService.Page;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/sms/opt-outs} (chapter 7 section 7.11.7, ADR-036 decision 8, spec 4.9): the administrator's list of
 * who is not to be texted. Rows about a member are made on the member's own screen
 * ({@code PUT /lending/members/{id}/sms-opt-out}); here an administrator also adds a bare number, pastes a list of
 * numbers, lifts any row, and everyone allowed to read notifications sees the list. A number is shown masked and is
 * never stored.
 */
@RestController
@RequestMapping(path = "/api/v1/sms/opt-outs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "notifications")
class SmsOptOutController {

    private final SmsOptOutService service;

    SmsOptOutController(SmsOptOutService service) {
        this.service = service;
    }

    @Schema(name = "SmsOptOut")
    record ItemResponse(
            UUID id,

            @Schema(
                    description =
                            "member (follows the member to any number) or number (this number, whoever it is about)")
            String kind,

            UUID memberId,

            @Schema(description = "reminders (reminders and arrears) or all (every SMS, receipts included)")
            String scope,

            @Schema(description = "staff (member screen), number (added by an administrator) or import (a pasted list)")
            String source,

            @Schema(description = "+256*****1234: the number is never stored")
            String recipientMasked,

            java.time.Instant createdAt,
            UUID createdBy) {

        static ItemResponse of(Item i) {
            return new ItemResponse(
                    i.id(),
                    i.kind(),
                    i.memberId(),
                    i.scope(),
                    i.source(),
                    i.recipientMasked(),
                    i.createdAt(),
                    i.createdBy());
        }
    }

    @Schema(name = "SmsOptOutPage")
    record PageResponse(List<ItemResponse> items, String nextCursor) {}

    @Schema(name = "AddSmsOptOutRequest")
    record AddRequest(
            @NotBlank
            @Size(max = 30)
            @Schema(
                    description =
                            "A Uganda mobile number; anything we cannot read without guessing is 422 invalid_phone")
            String phone,

            @NotBlank String scope,

            @Size(max = 200)
            @Schema(
                    description =
                            "Required (3 to 200 characters, kept in the audit row) when this narrows a number from all to reminders; ignored otherwise")
            String reason) {}

    @Schema(name = "ImportSmsOptOutsRequest")
    record ImportRequest(
            @NotBlank
            @Size(max = SmsOptOutService.MAX_IMPORT_CHARS)
            @Schema(description = "Numbers separated by new lines, commas or semicolons; at most 2000")
            String numbers,

            @NotBlank String scope,

            @Schema(description = "Default true: report what would happen and change nothing")
            Boolean dryRun) {}

    @GetMapping
    @RequiresPermission("core.notifications.read")
    @Operation(summary = "The SMS opt-out list, searched and paged (ADR-036, ADR-039)", operationId = "listSmsOptOuts")
    PageResponse list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        if (scope != null && Scope.of(scope).isEmpty()
                || kind != null && !List.of("member", "number").contains(kind)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Unknown filter value.");
        }
        Page page = service.list(q, scope, kind, limit, cursor);
        return new PageResponse(page.items().stream().map(ItemResponse::of).toList(), page.nextCursor());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(summary = "Add a number to the SMS opt-out list, audited", operationId = "addSmsOptOut")
    ResponseEntity<ItemResponse> add(@Valid @RequestBody AddRequest request) {
        SmsOptOutService.Added added = service.addNumber(request.phone(), scope(request.scope()), request.reason());
        // 201 for a new row; 200 when the number was already on the list (its scope is set as asked).
        return ResponseEntity.status(added.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ItemResponse.of(added.item()));
    }

    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Paste a list of numbers into the SMS opt-out list; a dry run first (the default), audited",
            operationId = "importSmsOptOuts")
    ImportResult importNumbers(@Valid @RequestBody ImportRequest request) {
        return service.importNumbers(
                request.numbers(), scope(request.scope()), !Boolean.FALSE.equals(request.dryRun()));
    }

    @Schema(name = "LiftSmsOptOutRequest")
    record LiftRequest(
            @NotBlank
            @Size(min = 3, max = 200)
            @Schema(
                    description =
                            "Why it is lifted, 3 to 200 characters, kept in the audit row. Not for names or numbers")
            String reason) {}

    @DeleteMapping(path = "/{opt_out_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Lift one SMS opt-out with a reason, audited (the tenant administrator only)",
            operationId = "removeSmsOptOut")
    ResponseEntity<Void> remove(@PathVariable("opt_out_id") UUID id, @Valid @RequestBody LiftRequest request) {
        service.remove(id, request.reason());
        return ResponseEntity.noContent().build();
    }

    private static Scope scope(String key) {
        return Scope.of(key)
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("scope", "invalid", "Use reminders or all."))));
    }
}
