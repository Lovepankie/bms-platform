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
 * Bodies of the purchase order routes (purchasing capability; ADR-033, issue #198;
 * docs/specs/trade-capabilities.md section 7). Amounts are integer minor units; quantities are decimal strings with
 * three places.
 */
final class OrdersApi {

    static final int MAX_LINES = 200;
    static final long MAX_MINOR = 10_000_000_000_000L;

    private OrdersApi() {}

    @Schema(name = "RetailPurchaseOrderLineRequest")
    record OrderLineRequest(
            @NotNull UUID productId,

            @Schema(
                    description =
                            "The unit qty and cost are in: a pack unit of the product (units capability) or the base unit."
                                    + " Absent means the base unit")
            UUID unitId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "10")
            BigDecimal qty,

            @NotNull
            @PositiveOrZero
            @Max(MAX_MINOR)
            @Schema(description = "Cost of one unit as entered (one pack for a pack line)")
            Long costMinor) {}

    @Schema(name = "RetailPurchaseOrderRequest")
    record OrderRequest(
            @NotNull UUID supplierId,

            @Schema(
                    description =
                            "The branch that expects the goods and whose series numbers the order; absent means the one branch"
                                    + " the caller's scope covers. Fixed once the order exists")
            UUID branchId,

            @Schema(description = "Absent means today in the business time zone")
            LocalDate orderedOn,

            LocalDate expectedOn,
            @Size(max = 300) String note,
            @NotEmpty @Size(max = MAX_LINES) @Valid List<OrderLineRequest> lines) {}

    @Schema(name = "RetailPurchaseOrderCancelRequest")
    record CancelRequest(@NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailPurchaseOrderLine")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OrderLine(
            UUID id,
            int lineNo,
            UUID productId,
            String code,
            String description,
            @Schema(description = "Ordered, in base units") String qty,
            UUID unitId,
            String unit,

            @Schema(description = "Base units in one pack of the line's unit")
            String unitFactor,

            @Schema(description = "Ordered, in the unit it was entered in")
            String unitQty,

            @Schema(description = "Cost of one base unit") long costMinor,

            @Schema(description = "Cost of one unit as entered")
            Long unitCostMinor,

            long lineTotalMinor,

            @Schema(description = "Received so far on receipts that stand, in base units; present on one order only")
            String receivedQty,

            @Schema(description = "The value of what was received, as entered")
            Long receivedMinor,

            @Schema(
                    description = "Billed so far on supplier invoices that stand, in base units; present with"
                            + " retail.payable.manage")
            String billedQty,

            @Schema(description = "What the supplier billed for it; present with retail.payable.manage")
            Long billedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "D23, issue #554: received so far in the line's own unit, for a line ordered in a pack:"
                                    + " packs received in it count whatever each held, other units count in packs of the size"
                                    + " ordered. The order is received in full when this reaches unit_qty")
            String receivedUnitQty,

            @Schema(description = "The unit the item is counted in (piece), whatever unit the line was ordered in")
            String baseUnit) {}

    @Schema(name = "RetailPurchaseOrderReceiptRef")
    record ReceiptRef(UUID id, String receiptNo, LocalDate receivedOn, String status, long totalMinor) {}

    @Schema(name = "RetailPurchaseOrder", description = "A purchase order. A list carries no lines")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Order(
            UUID id,
            String orderNo,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            LocalDate orderedOn,
            LocalDate expectedOn,
            String currency,
            long totalMinor,
            String note,

            @Schema(allowableValues = {"draft", "sent", "partly_received", "received", "cancelled"})
            String status,

            int lineCount,
            List<OrderLine> lines,
            List<ReceiptRef> receipts,
            Instant createdAt,
            UUID createdBy,

            @Schema(description = "Who last changed the draft; absent if it was never edited")
            UUID updatedBy,

            Instant sentAt,
            UUID sentBy,
            UUID approvalId,
            UUID approvedBy,
            Instant cancelledAt,
            UUID cancelledBy,
            String cancelReason,

            @Schema(description = "Send as If-Match when editing")
            int version) {}

    @Schema(name = "RetailPurchaseOrderList")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OrderList(List<Order> items, String nextCursor) {}

    @Schema(
            name = "RetailPurchaseOrderOutcome",
            description =
                    "200 with the order when it was sent; 202 with approval_id when its value reached the tenant's approval"
                            + " threshold for retail_purchase_order and it waits for a checker (retail.purchase.approve)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OrderOutcome(boolean executed, UUID approvalId, Order order) {}
}
