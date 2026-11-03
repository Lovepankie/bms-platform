package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI assistant add-on (ADR-112, issue #423): its price, entered by a platform operator and never seeded, and the switch per
 * business. Platform operators only, on the platform host.
 */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class AddonController {

    private final AddonService service;

    AddonController(AddonService service) {
        this.service = service;
    }

    @Schema(name = "AddonPrice")
    record AddonPriceResponse(
            UUID id,
            String currency,

            @Schema(description = "The monthly price in minor units (whole shillings for UGX)")
            long monthlyMinor,

            LocalDate effectiveFrom,
            String reason,
            String createdByName,
            Instant createdAt) {}

    @Schema(name = "AddonDetail", description = "The add-on, its price in force today and every price it has had")
    record AddonDetail(
            String code,
            String name,

            @Schema(description = "The price in force today in UGX, or null when none was entered")
            AddonPriceResponse priceToday,

            @Schema(description = "Every price, newest first")
            List<AddonPriceResponse> prices,

            @Schema(description = "Businesses that have the add-on on")
            long tenantsOn) {}

    @Schema(name = "AddAddonPriceRequest")
    record AddAddonPriceRequest(
            @Pattern(regexp = "^[A-Z]{3}$") @Schema(description = "Default UGX")
            String currency,

            @NotNull @Min(0) @Max(PriceBookService.MAX_MINOR)
            Long monthlyMinor,

            @Schema(description = "Default today; never before today, so a price never changes the past")
            LocalDate effectiveFrom,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why, kept with the price and in the audit log")
            String reason,

            @Schema(
                    description =
                            "True when the operator checked a monthly price more than 25% away from the one in force (422 price_change_unusual otherwise)")
            Boolean confirmLargeChange) {}

    @Schema(name = "TenantAddon", description = "The AI assistant add-on on one business")
    record TenantAddonResponse(
            String code,

            @Schema(description = "The operator switched it on: the business may use the assistant")
            boolean on,

            @Schema(description = "On without charge: a demo or a pilot")
            boolean noCharge,

            @Schema(
                    description =
                            "It adds to what the business owes: on, not no charge, not a pilot (nothing is charged in the free month)")
            boolean charged,

            @Schema(description = "The add-on's monthly price today, or null when none was entered")
            Long monthlyMinor) {}

    @Schema(name = "TenantAddonRequest")
    record TenantAddonRequest(
            @NotNull Boolean on,

            @Schema(description = "On without charge (a demo or a pilot); ignored when switching off")
            Boolean noCharge,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in the platform audit log")
            String reason) {}

    @GetMapping("/addons/ai_assistant")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "The AI assistant add-on with its price history (issue #423)",
            operationId = "platformGetAiAddon")
    AddonDetail addon() {
        return service.detail();
    }

    @PostMapping("/addons/ai_assistant/prices")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Set the AI assistant add-on's monthly price from a date; the price already in force on that date again changes nothing",
            operationId = "platformAddAiAddonPrice")
    AddonDetail addPrice(@Valid @RequestBody AddAddonPriceRequest request) {
        return service.addPrice(request);
    }

    @PutMapping("/tenants/{tenant_id}/ai-addon")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Switch the AI assistant add-on on or off for a business, charged or not, with a reason; the same state again changes nothing",
            operationId = "platformSetTenantAiAddon")
    TenantAddonResponse set(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody TenantAddonRequest request) {
        return service.set(tenantId, request.on(), Boolean.TRUE.equals(request.noCharge()), request.reason());
    }
}
