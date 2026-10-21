package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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

/**
 * {@code /api/v1/platform/plans}: the price book (ADR-024 decision 3 as amended by ADR-056). A plan carries the limits and,
 * in {@code plan_prices}, its monthly price and the months charged for a year. Every price is entered by a platform operator
 * here; none is seeded or kept in the repository. Platform operators only, on the platform host.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/plans", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class PriceBookController {

    private final PriceBookService service;

    PriceBookController(PriceBookService service) {
        this.service = service;
    }

    @Schema(
            name = "PlanPrice",
            description = "A price of a plan from a date; a later row replaces it from its own date")
    record PlanPriceResponse(
            UUID id,
            String currency,

            @Schema(description = "The monthly price in minor units (whole shillings for UGX)")
            long monthlyMinor,

            @Schema(description = "Months charged for a year, for example 10")
            int annualMonths,

            @Schema(description = "monthly_minor times annual_months")
            long annualMinor,

            LocalDate effectiveFrom,
            String reason,
            String createdByName,
            Instant createdAt) {}

    @Schema(name = "Plan", description = "Limits, what the plan includes, and its price in force today")
    record PlanResponse(
            String code,
            String name,
            String description,

            @Schema(description = "Shops and stores; null is unlimited")
            Integer maxBranches,

            @Schema(description = "Staff users, invited or active; null is unlimited")
            Integer maxStaffUsers,

            @Schema(description = "Active lending members; null is unlimited")
            Integer maxActiveMembers,

            List<String> allowedModules,

            @Schema(description = "False: no new business and no move onto it")
            boolean active,

            int sortOrder,

            @Schema(description = "The price in force today, or null when none was entered")
            PlanPriceResponse currentPrice,

            @Schema(description = "A price entered for a later date, or null")
            PlanPriceResponse nextPrice,

            @Schema(description = "Businesses on this plan") long tenantCount) {}

    @Schema(name = "PlanList")
    record PlanList(List<PlanResponse> items) {}

    @Schema(name = "PlanDetail")
    record PlanDetail(
            PlanResponse plan,

            @Schema(description = "Every price the plan has had, newest first")
            List<PlanPriceResponse> prices) {}

    @Schema(name = "SavePlanRequest", description = "Creates the plan with this code, or changes it")
    record SavePlanRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description,
            @Min(0) @Max(100000) Integer maxBranches,
            @Min(0) @Max(100000) Integer maxStaffUsers,
            @Min(0) @Max(10000000) Integer maxActiveMembers,
            @NotEmpty @Size(max = 5) List<@Pattern(regexp = "^[a-z]{2,30}$") String> allowedModules,
            @NotNull Boolean active,
            @Min(0) @Max(1000) Integer sortOrder,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why, kept in the platform audit log")
            String reason,

            @Schema(
                    description =
                            "True when the operator was warned that businesses on the plan already use more than a lowered limit, and keeps it (422 plan_limit_below_use otherwise)")
            Boolean confirmBelowUse) {}

    @Schema(name = "AddPlanPriceRequest")
    record AddPriceRequest(
            @Pattern(regexp = "^[A-Z]{3}$") @Schema(description = "Default UGX")
            String currency,

            @NotNull @Min(0) @Max(PriceBookService.MAX_MINOR)
            Long monthlyMinor,

            @NotNull @Min(1) @Max(12) Integer annualMonths,

            @Schema(description = "Default today; never before today, so a price never changes the past")
            LocalDate effectiveFrom,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why, kept with the price and in the audit log")
            String reason,

            @Schema(
                    description =
                            "True when the operator checked a monthly price more than 25% away from the one in force (422 price_change_unusual otherwise)")
            Boolean confirmLargeChange) {}

    @GetMapping
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "Plans with their limits and the price in force today (FR-TEN-04, FR-SUB-01)",
            operationId = "platformListPlans")
    PlanList plans(@RequestParam(name = "include_inactive", required = false) Boolean includeInactive) {
        return new PlanList(service.plans(Boolean.TRUE.equals(includeInactive)));
    }

    @GetMapping("/{plan_code}")
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "One plan with its price history (FR-SUB-01)", operationId = "platformGetPlan")
    PlanDetail plan(@PathVariable("plan_code") String code) {
        return service.detail(code);
    }

    @PutMapping("/{plan_code}")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Create the plan with this code, or change it; the same request again changes nothing (FR-SUB-01)",
            operationId = "platformSavePlan")
    ResponseEntity<PlanDetail> save(
            @PathVariable("plan_code") String code, @Valid @RequestBody SavePlanRequest request) {
        PriceBookService.Saved saved = service.save(code, request);
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(saved.detail());
    }

    @PostMapping("/{plan_code}/prices")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Set the plan's price from a date; the price already in force on that date again changes nothing (FR-SUB-01)",
            operationId = "platformAddPlanPrice")
    PlanDetail addPrice(@PathVariable("plan_code") String code, @Valid @RequestBody AddPriceRequest request) {
        return service.addPrice(code, request);
    }
}
