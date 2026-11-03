package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Savings;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsSuggestion;
import com.rincoltech.bms.retail.cashbook.internal.SavingsService.Caller;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The day's savings for sales staff (issue #799, ADR-115). The till's savings routes need {@code retail.savings.record} and
 * {@code retail.till.close}, which the Sales role does not hold; in a shop where the sales people put the money aside, the
 * Owner's switch "Salespeople can see and change the savings amount" lets a caller who may sell at a branch open that
 * branch's savings, see and change the suggested amount, save it, and see this month's records with their amounts. These
 * routes answer only while the switch is on (403 {@code permission_denied} otherwise); after the branch the checks, their
 * order and what is sent are those of the till's routes, so the profit, the expenses and the cap are never sent, and
 * replacing a saved amount still needs {@code retail.savings.overwrite}. Kept apart from {@link CashbookController}, whose
 * till check runs before every one of its routes.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/savings/sales-staff", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-cashbook")
class SalesStaffSavingsController {

    private static final String WHEN =
            "Issue #799, ADR-115: only while the Owner's setting salespeople_see_savings_amount is on, else 403 permission_denied;"
                    + " the branch must be one where the caller may sell (retail.sale.create).";

    private final SavingsService savings;

    SalesStaffSavingsController(SavingsService savings) {
        this.savings = savings;
    }

    @GetMapping("/suggestion")
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary = "The day's savings suggestion for sales staff (FR-RET-18)",
            operationId = "getRetailSalesStaffSavingsSuggestion",
            description = WHEN)
    SavingsSuggestion suggestion(
            @RequestParam(name = "branch_id", required = false) UUID branchId,
            @RequestParam(name = "date", required = false) LocalDate date) {
        return savings.suggestion(Caller.SALES_STAFF, branchId, date);
    }

    @PostMapping
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary = "Record the day's savings as sales staff (FR-RET-18 to FR-RET-20); M",
            operationId = "createRetailSalesStaffSavings",
            description = WHEN)
    ResponseEntity<Savings> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody SavingsRequest request) {
        Outcome<Savings> outcome = savings.create(Caller.SALES_STAFF, key, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/savings/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary = "This month's savings records for sales staff, newest first",
            operationId = "listRetailSalesStaffSavings",
            description = WHEN + " Only records dated this month, at the branches where the caller may sell.")
    SavingsPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "include_voided", required = false, defaultValue = "false") boolean includeVoided,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return savings.list(Caller.SALES_STAFF, branchIds, from, to, includeVoided, limit, cursor);
    }
}
