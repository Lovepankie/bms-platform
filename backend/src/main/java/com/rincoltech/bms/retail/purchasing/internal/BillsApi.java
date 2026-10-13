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
 * Bodies of the supplier invoice routes (purchasing capability; ADR-033, issue #198; docs/specs/trade-capabilities.md
 * sections 7.1 and 12.3). Amounts are integer minor units; quantities are decimal strings with three places.
 */
final class BillsApi {

    private BillsApi() {}

    @Schema(name = "RetailSupplierBillLineRequest")
    record BillLineRequest(
            @NotNull @Schema(description = "The receipt line this bills")
            UUID receiptLineId,

            @NotNull
            @Positive
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    example = "10",
                    description =
                            "The quantity billed, in the unit the receipt line was received in (a pack for a pack line); at most"
                                    + " what was received and not yet billed")
            BigDecimal qty,

            @NotNull
            @PositiveOrZero
            @Max(OrdersApi.MAX_MINOR)
            @Schema(description = "The supplier's price for one unit as entered (one pack for a pack line)")
            Long unitPriceMinor) {}

    @Schema(name = "RetailSupplierBillRequest")
    record BillRequest(
            @NotNull UUID supplierId,

            @NotBlank @Size(max = 60) @Schema(description = "The supplier's own invoice number; unique per supplier")
            String supplierRef,

            @NotNull LocalDate billDate,
            @NotNull LocalDate dueDate,

            @NotNull
            @Positive
            @Max(OrdersApi.MAX_MINOR)
            @Schema(description = "The invoice total as printed. It must equal the sum of the lines: a typing check")
            Long totalMinor,

            @Size(max = 300)
            @Schema(
                    description =
                            "Why the invoice differs from the receipts it bills. Required, at least 5 characters, when the"
                                    + " difference is above the tenant's tolerance")
            String varianceReason,

            @Size(max = 300) String note,

            @NotEmpty @Size(max = OrdersApi.MAX_LINES) @Valid
            List<BillLineRequest> lines) {}

    @Schema(name = "RetailSupplierBillVoidRequest")
    record VoidRequest(@NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailSupplierBillLine")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BillLine(
            UUID id,
            int lineNo,
            UUID receiptLineId,
            UUID receiptId,
            String receiptNo,
            UUID productId,
            String code,
            String description,
            @Schema(description = "Billed, in base units") String qty,
            UUID unitId,
            String unit,

            @Schema(description = "Billed, in the unit it was received in")
            String unitQty,

            long unitPriceMinor,

            @Schema(description = "What the supplier billed for the line")
            long lineTotalMinor,

            @Schema(description = "The part of the receipt line's value this quantity clears")
            long clearedMinor,

            @Schema(description = "Billed less cleared: the price difference, posted to purchase price variance")
            long varianceMinor) {}

    @Schema(name = "RetailSupplierBill")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Bill(
            UUID id,
            String billNo,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            String supplierRef,
            LocalDate billDate,
            LocalDate dueDate,
            String currency,

            @Schema(allowableValues = {"goods", "landed_cost"})
            String kind,

            long totalMinor,
            long clearedMinor,
            long varianceMinor,
            String varianceReason,
            String note,
            @Schema(allowableValues = {"posted", "voided"}) String status,

            @Schema(description = "Paid by payments that stand")
            long paidMinor,

            @Schema(description = "Total less paid; zero once voided")
            long openMinor,

            int lineCount,
            @Schema(description = "A list carries no lines") List<BillLine> lines,
            Instant createdAt,
            UUID createdBy,
            Instant voidedAt,
            LocalDate voidedOn,
            UUID voidedBy,
            String voidReason,
            int version) {}

    @Schema(name = "RetailSupplierBillList")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BillList(List<Bill> items, String nextCursor) {}

    @Schema(
            name = "RetailSupplierBillMatch",
            description =
                    "What a supplier invoice comes to against the receipts it bills, worked out without recording anything:"
                            + " the three-way view of ordered, received and billed")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Match(
            long totalMinor,
            long clearedMinor,
            long varianceMinor,

            @Schema(description = "The difference the tenant's tolerance allows without a reason, in minor units")
            long toleranceMinor,

            @Schema(description = "True when the difference is above the tolerance, so a reason is required")
            boolean reasonRequired,

            @Schema(description = "True when the typed invoice total differs from the lines")
            boolean totalMismatch,

            List<BillLine> lines) {}

    @Schema(
            name = "RetailUnbilledReceiptLine",
            description = "A received line that a supplier invoice has not billed in full")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Unbilled(
            UUID receiptLineId,
            UUID receiptId,
            String receiptNo,
            UUID branchId,
            LocalDate receivedOn,
            String orderNo,
            UUID productId,
            String code,
            String description,
            UUID unitId,
            String unit,

            @Schema(description = "Received and not yet billed, in the unit it was received in")
            String unbilledQty,

            @Schema(description = "The cost of one unit as received, which a matching invoice repeats")
            long unitCostMinor,

            @Schema(description = "The value of what is unbilled, as entered: what sits in goods received not invoiced")
            long unbilledMinor) {}

    @Schema(name = "RetailUnbilledReceiptList")
    record UnbilledList(UUID supplierId, String supplierName, List<Unbilled> items, long totalMinor) {}
}
