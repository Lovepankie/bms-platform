package com.rincoltech.bms.retail.purchasing.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the landed cost routes (purchasing capability; ADR-033, issue #199; docs/specs/trade-capabilities.md
 * section 7.2). Amounts are integer minor units; quantities and weights are decimal strings with three places. The cost
 * columns of a line (the part for inventory and for cost of goods sold, the product's cost before and after) are present
 * only for a caller who holds {@code retail.profit.read} in the line's branch.
 */
final class LandedCostApi {

    private LandedCostApi() {}

    @Schema(name = "RetailLandedCostChargeRequest")
    record ChargeRequest(
            @NotNull
            @Pattern(regexp = "freight|duty|clearing|insurance|other")
            @Schema(allowableValues = {"freight", "duty", "clearing", "insurance", "other"})
            String kind,

            @Size(max = 120) String description,

            @NotNull @Positive @Max(OrdersApi.MAX_MINOR) Long amountMinor) {}

    @Schema(name = "RetailLandedCostLineRequest")
    record LineRequest(
            @NotNull @Schema(description = "The received line the charges are spread over")
            UUID receiptLineId,

            @Positive
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    example = "120.5",
                    description =
                            "The line's total weight, in any one unit used on every line. Required for method weight")
            BigDecimal weight,

            @Positive
            @Max(OrdersApi.MAX_MINOR)
            @Schema(
                    description =
                            "This line's share, in minor units. Required for method manual; the shares must add to the total")
            Long amountMinor) {}

    @Schema(name = "RetailLandedCostRequest")
    record LandedCostRequest(
            @NotNull
            @Schema(description = "Who the charges are owed to: the forwarder, carrier or agent, a supplier row")
            UUID supplierId,

            @NotBlank @Size(max = 60) @Schema(description = "The payee's own invoice number; unique per supplier")
            String supplierRef,

            @Schema(description = "The date of the charge; absent means today in the business time zone")
            LocalDate costDate,

            @NotNull LocalDate dueDate,

            @Pattern(regexp = "value|quantity|weight|manual")
            @Schema(
                    allowableValues = {"value", "quantity", "weight", "manual"},
                    description = "How the total is spread over the lines; absent means value")
            String method,

            @Schema(
                    description =
                            "A supplier invoice for goods that the charges belong to; every line must be billed by it")
            UUID goodsBillId,

            @NotNull
            @Positive
            @Max(OrdersApi.MAX_MINOR)
            @Schema(description = "The total as invoiced. It must equal the charges: a typing check")
            Long totalMinor,

            @Size(max = 300) String note,

            @NotEmpty @Size(max = 20) @Valid List<ChargeRequest> charges,

            @NotEmpty @Size(max = OrdersApi.MAX_LINES) @Valid
            List<LineRequest> lines) {}

    @Schema(name = "RetailLandedCostVoidRequest")
    record VoidRequest(@NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailLandedCostCharge")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Charge(int lineNo, String kind, String description, long amountMinor) {}

    @Schema(name = "RetailLandedCostLine")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Line(
            int lineNo,
            UUID receiptLineId,
            UUID receiptId,
            String receiptNo,
            UUID branchId,
            UUID productId,
            String code,
            String description,
            @Schema(description = "Received, in base units") String qty,

            @Schema(
                    description =
                            "What the spread used: the value in minor units, the quantity or the weight; needs retail.profit.read")
            String basis,

            String weight,

            @Schema(
                    description =
                            "This line's share of the total, which is the landed cost of the line; needs retail.profit.read")
            Long allocatedMinor,

            @Schema(
                    description =
                            "The units of the line still on hand in its branch when the cost was added; needs retail.profit.read")
            String onHandQty,

            @Schema(description = "The share for goods still on hand, added to inventory; needs retail.profit.read")
            Long toInventoryMinor,

            @Schema(
                    description =
                            "The share for goods already sold, used or moved, posted to cost of goods sold; needs retail.profit.read")
            Long toCogsMinor,

            @Schema(description = "The product's cost per base unit before; needs retail.profit.read")
            Long costBeforeMinor,

            @Schema(
                    description =
                            "The product's cost per base unit after; equal to before when not applied; needs retail.profit.read")
            Long costAfterMinor,

            Boolean costApplied,

            @Schema(description = "Why the cost was not moved, when it was not")
            String costNote) {}

    @Schema(name = "RetailLandedCost")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LandedCost(
            UUID id,
            String landedNo,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            UUID billId,
            String billNo,
            String supplierRef,
            UUID goodsBillId,

            @Schema(allowableValues = {"value", "quantity", "weight", "manual"})
            String method,

            LocalDate costDate,
            LocalDate dueDate,
            String currency,
            long totalMinor,
            String note,
            @Schema(allowableValues = {"posted", "voided"}) String status,

            @Schema(description = "Paid on the payee's invoice by payments that stand")
            long paidMinor,

            int lineCount,

            @Schema(description = "A list carries neither charges nor lines")
            List<Charge> charges,

            List<Line> lines,
            Instant createdAt,
            UUID createdBy,
            Instant voidedAt,
            LocalDate voidedOn,
            UUID voidedBy,
            String voidReason,
            int version) {}

    @Schema(name = "RetailLandedCostList")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LandedCostList(List<LandedCost> items, String nextCursor) {}

    @Schema(
            name = "RetailLandedCostPreview",
            description =
                    "How a landed cost would be spread and what it would do, worked out without recording anything")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Preview(
            @Schema(description = "The charges added up") long totalMinor,

            @Schema(description = "True when the typed total differs from the charges")
            boolean totalMismatch,

            String method,
            List<Line> lines) {}
}
