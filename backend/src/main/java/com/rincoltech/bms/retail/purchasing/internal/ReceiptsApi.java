package com.rincoltech.bms.retail.purchasing.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the goods received routes (purchasing capability; ADR-033, issue #198). Amounts are integer minor units;
 * quantities are decimal strings with three places.
 */
final class ReceiptsApi {

    private ReceiptsApi() {}

    @Schema(name = "RetailGoodsReceiptLineRequest")
    record ReceiptLineRequest(
            @Schema(
                    description =
                            "The order line this receives. Required on a receipt against an order; absent on a blind receipt")
            UUID orderLineId,

            @Schema(description = "Required on a blind receipt; on an order line it is the line's own product")
            UUID productId,

            @Schema(
                    description =
                            "The unit qty and cost are in. On an order line, absent means the unit the line was ordered in;"
                                    + " on a blind receipt absent means the base unit. Naming the base unit is the base unit")
            UUID unitId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "10")
            BigDecimal qty,

            @PositiveOrZero
            @Max(OrdersApi.MAX_MINOR)
            @Schema(
                    description =
                            "The cost of one unit as entered. Absent on an order line in its own unit means the ordered cost;"
                                    + " required otherwise")
            Long costMinor,

            @PositiveOrZero
            @Max(OrdersApi.MAX_MINOR)
            @Schema(
                    description =
                            "A new selling price per base unit, set on the product with this receipt; absent keeps it")
            Long sellMinor) {}

    @Schema(name = "RetailGoodsReceiptRequest")
    record ReceiptRequest(
            @Schema(
                    description =
                            "The order received against; absent for a blind receipt, which then needs supplier_id")
            UUID orderId,

            UUID supplierId,

            @Schema(
                    description =
                            "The branch or store the goods go into; absent means the one branch the caller's scope covers")
            UUID branchId,

            @Schema(description = "Absent means today in the business time zone")
            LocalDate receivedOn,

            @Size(max = 60) @Schema(description = "The supplier's delivery note number")
            String deliveryRef,

            @Size(max = 300) String note,

            @NotEmpty @Size(max = OrdersApi.MAX_LINES) @Valid
            List<ReceiptLineRequest> lines) {}

    @Schema(name = "RetailGoodsReceiptVoidRequest")
    record VoidRequest(@NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailGoodsReceiptLine")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReceiptLine(
            UUID id,
            int lineNo,
            UUID orderLineId,
            UUID productId,
            String code,
            String description,
            @Schema(description = "Received, in base units") String qty,
            UUID unitId,
            String unit,

            @Schema(description = "Base units in one pack of the line's unit")
            String unitFactor,

            @Schema(description = "Received, in the unit it was entered in")
            String unitQty,

            @Schema(description = "Cost of one unit as entered")
            long enteredCostMinor,

            @Schema(description = "Cost of one base unit, which the stock movement carries")
            long costMinor,

            Long sellMinor,

            @Schema(description = "The value as entered: what goes to goods received not invoiced")
            long lineTotalMinor,

            @Schema(
                    description = "Billed so far on supplier invoices that stand, in base units; present with"
                            + " retail.payable.manage")
            String billedQty) {}

    @Schema(name = "RetailGoodsReceipt")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Receipt(
            UUID id,
            String receiptNo,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            UUID orderId,
            String orderNo,
            LocalDate receivedOn,
            String currency,
            long totalMinor,
            String deliveryRef,
            String note,
            @Schema(allowableValues = {"posted", "voided"}) String status,
            int lineCount,
            @Schema(description = "A list carries no lines") List<ReceiptLine> lines,
            Instant createdAt,
            UUID createdBy,
            Instant voidedAt,
            LocalDate voidedOn,
            UUID voidedBy,
            String voidReason,

            @Schema(description = "Send as If-Match where a route takes it")
            int version) {}

    @Schema(name = "RetailGoodsReceiptList")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReceiptList(List<Receipt> items, String nextCursor) {}
}
