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
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the supplier credit note routes (purchasing capability; ADR-033, issue #199; docs/specs/trade-capabilities.md
 * sections 7.1 and 12.3). A credit note is against a supplier invoice that stands and reduces what it leaves to pay. Amounts
 * are integer minor units; quantities are decimal strings with three places. The cost columns of a line need
 * {@code retail.profit.read} in the line's branch.
 */
final class CreditsApi {

    private CreditsApi() {}

    @Schema(name = "RetailSupplierCreditLineRequest")
    record LineRequest(
            @NotNull @Schema(description = "The line of the invoice this credits")
            UUID billLineId,

            @Positive
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    example = "2",
                    description =
                            "Goods credit: the quantity sent back, in the unit the line was received in; at most what the"
                                    + " invoice billed and no credit note has returned. Absent on a price credit")
            BigDecimal qty,

            @PositiveOrZero
            @Max(OrdersApi.MAX_MINOR)
            @Schema(
                    description =
                            "Goods credit: the credit for one unit as entered; absent means the price the invoice billed. Absent"
                                    + " on a price credit")
            Long unitCreditMinor,

            @Positive
            @Max(OrdersApi.MAX_MINOR)
            @Schema(description = "Price credit: the amount taken off the line. Absent on a goods credit")
            Long amountMinor) {}

    @Schema(name = "RetailSupplierCreditRequest")
    record CreditRequest(
            @NotNull @Schema(description = "The supplier invoice this credit note is against; it must still stand")
            UUID billId,

            @NotNull
            @Pattern(regexp = "goods|price")
            @Schema(
                    allowableValues = {"goods", "price"},
                    description = "goods: stock goes back to the supplier. price: an adjustment")
            String kind,

            @NotBlank
            @Size(max = 60)
            @Schema(description = "The supplier's own credit note number; unique per supplier")
            String supplierRef,

            @Schema(description = "Absent means today in the business time zone")
            LocalDate creditDate,

            @NotNull
            @Positive
            @Max(OrdersApi.MAX_MINOR)
            @Schema(description = "The credit as printed. It must equal the sum of the lines: a typing check")
            Long totalMinor,

            @NotBlank @Size(min = 5, max = 300) String reason,
            @Size(max = 300) String note,

            @NotEmpty @Size(max = OrdersApi.MAX_LINES) @Valid
            List<LineRequest> lines) {}

    @Schema(name = "RetailSupplierCreditVoidRequest")
    record VoidRequest(@NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailSupplierCreditLine")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Line(
            UUID id,
            int lineNo,
            UUID billLineId,
            UUID receiptLineId,
            String receiptNo,
            UUID branchId,
            UUID productId,
            String code,
            String description,

            @Schema(description = "Goods credit: sent back, in base units")
            String qty,

            UUID unitId,
            String unit,

            @Schema(description = "Goods credit: sent back, in the unit it was received in")
            String unitQty,

            @Schema(description = "Goods credit: the credit for one unit as entered")
            Long unitCreditMinor,

            @Schema(description = "The credit for the line") long lineTotalMinor,

            @Schema(description = "The part credited to inventory; needs retail.profit.read")
            Long toInventoryMinor,

            @Schema(
                    description =
                            "Price credit: the part for goods already sold, to cost of goods sold; needs retail.profit.read")
            Long toCogsMinor,

            @Schema(description = "The part posted to purchase price variance; needs retail.profit.read")
            Long varianceMinor,

            @Schema(
                    description =
                            "Price credit: the product's cost per base unit before and after; needs retail.profit.read")
            Long costBeforeMinor,

            Long costAfterMinor,
            Boolean costApplied,
            String costNote) {}

    @Schema(name = "RetailSupplierCredit")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Credit(
            UUID id,
            String creditNo,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            UUID billId,
            String billNo,
            String billSupplierRef,
            String supplierRef,
            LocalDate creditDate,
            String currency,
            @Schema(allowableValues = {"goods", "price"}) String kind,
            long totalMinor,
            String reason,
            String note,
            @Schema(allowableValues = {"posted", "voided"}) String status,
            int lineCount,
            @Schema(description = "A list carries no lines") List<Line> lines,
            Instant createdAt,
            UUID createdBy,
            Instant voidedAt,
            LocalDate voidedOn,
            UUID voidedBy,
            String voidReason,
            int version) {}

    @Schema(name = "RetailSupplierCreditList")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreditList(List<Credit> items, String nextCursor) {}
}
