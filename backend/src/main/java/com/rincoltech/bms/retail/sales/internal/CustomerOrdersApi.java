package com.rincoltech.bms.retail.sales.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
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
 * Request and response bodies of the customer order routes (issue #385, ADR-052; chapter 7 section 7.11.20). An order carries
 * the prices agreed with the customer and never a cost.
 */
final class CustomerOrdersApi {

    static final String SOURCES = "whatsapp|instagram|facebook|phone|walk_in|other";
    static final String MONEY = "cash|mobile_money|bank";

    private CustomerOrdersApi() {}

    @Schema(name = "RetailOrderLineRequest")
    record OrderLineRequest(
            @NotNull UUID productId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "2")
            BigDecimal qty,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(
                    description = "The price agreed per unit_id (per base unit when none); defaults to the price a sale"
                            + " would take today")
            Long unitPriceMinor,

            @Schema(description = "A pack unit of the product (units capability); absent means the base unit")
            UUID unitId,

            @Size(max = 300)
            @Schema(description = "As on a sale: why the price differs from the price list's (price_lists capability)")
            String priceOverrideReason) {}

    @Schema(name = "RetailOrderRequest")
    record OrderRequest(
            @Schema(description = "Defaults to the caller's one branch; a shop, never a store")
            UUID branchId,

            @NotNull
            @Pattern(regexp = SOURCES)
            @Schema(description = "Where the order came from: whatsapp, instagram, facebook, phone, walk_in or other")
            String source,

            @Schema(description = "A credit buyer on the list; then customer_name defaults to theirs")
            UUID customerId,

            @Size(max = 200) String customerName,
            @Size(max = 100) String customerPhone,

            @Size(max = 300) @Schema(description = "Where to deliver; absent means the customer collects at the shop")
            String deliveryPlace,

            @Schema(description = "The day it should be delivered; not in the past")
            LocalDate deliveryDate,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "Charged on top of the items and paid on delivery; defaults to 0")
            Long deliveryFeeMinor,

            @Size(max = 500) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull OrderLineRequest> lines) {}

    @Schema(
            name = "RetailOrderChangeRequest",
            description = "A field left out keeps its value; an empty text clears it")
    record ChangeRequest(
            @Size(max = 200) String customerName,
            @Size(max = 100) String customerPhone,
            @Size(max = 300) String deliveryPlace,
            LocalDate deliveryDate,

            @PositiveOrZero @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long deliveryFeeMinor,

            @Size(max = 500) String notes,

            @Schema(description = "True takes the delivery day off the order")
            Boolean clearDeliveryDate) {}

    @Schema(name = "RetailOrderStepRequest")
    record StepRequest(@Size(max = 500) String note) {}

    @Schema(name = "RetailOrderSendRequest")
    record SendRequest(
            @NotBlank @Size(max = 120) @Schema(description = "Who takes it out, for example the rider's name")
            String deliveredBy,

            @Size(max = 500) String note) {}

    @Schema(name = "RetailOrderDeliverRequest")
    record DeliverRequest(
            @NotNull
            @Pattern(regexp = SalesApi.SALE_METHODS)
            @Schema(
                    description =
                            "cash, mobile_money or bank: everything paid at the door; credit: the goods on credit, with"
                                    + " paid_now_minor taken at the door (at least the delivery fee)")
            String paymentMethod,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(
                    description =
                            "Credit only: the money taken at the door; the delivery fee first, the rest on the sale")
            Long paidNowMinor,

            @Pattern(regexp = MONEY) @Schema(description = "Credit only: how the money taken at the door was paid")
            String paidNowMethod,

            @Schema(description = "Credit only: when the customer will pay the rest")
            LocalDate dueDate,

            @Size(max = 300) @Schema(description = "As on a credit sale above the buyer's limit")
            String creditOverrideReason,

            @Schema(
                    description =
                            "Credit only, for a credit buyer on the list: the delivery fee goes on the buyer's account"
                                    + " (trade debtors, against the sale) instead of being paid at the door; paid later with"
                                    + " POST /retail/orders/{order_id}/fee-payment")
            Boolean feeOnAccount,

            @Size(max = 500) String note) {}

    @Schema(name = "RetailOrderFeePaymentRequest")
    record FeePaymentRequest(
            @NotNull @Pattern(regexp = MONEY) String method) {}

    @Schema(name = "RetailOrderCancelRequest")
    record CancelRequest(@NotBlank @Size(min = 3, max = 300) String reason) {}

    @Schema(name = "RetailOrderLine")
    record OrderLine(
            int lineNo,
            UUID productId,
            String code,
            String description,
            @Schema(description = "In unit") String qty,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID unitId,
            String unit,

            @Schema(description = "The price agreed per unit")
            long unitPriceMinor,

            long lineTotalMinor,

            @Schema(description = "What the order keeps aside, in the product's base unit")
            String baseQty) {}

    @Schema(name = "RetailOrderEvent")
    record OrderEvent(
            String step,
            @JsonInclude(JsonInclude.Include.NON_NULL) String note,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID byUser,
            @JsonInclude(JsonInclude.Include.NON_NULL) String byName,
            Instant at) {}

    @Schema(name = "RetailOrder")
    record Order(
            UUID id,
            String orderNo,
            UUID branchId,
            LocalDate orderedOn,
            String source,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID customerId,
            String customerName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String customerPhone,
            @JsonInclude(JsonInclude.Include.NON_NULL) String deliveryPlace,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate deliveryDate,
            long deliveryFeeMinor,
            String currency,
            long itemsTotalMinor,

            @Schema(description = "items_total_minor plus delivery_fee_minor")
            long totalMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) String notes,

            @Schema(description = "ordered, packed, out_for_delivery, delivered or cancelled")
            String status,

            @JsonInclude(JsonInclude.Include.NON_NULL) String deliveredBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant packedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant sentAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant deliveredAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate deliveredOn,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID saleId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String saleNo,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "cash, mobile_money, bank (paid at the door) or account (on the credit buyer's account)")
            String feeMethod,

            @JsonInclude(JsonInclude.Include.NON_NULL) Instant cancelledAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String cancelReason,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "When a fee on account was paid")
            LocalDate feePaidOn,

            @JsonInclude(JsonInclude.Include.NON_NULL) String feePaidMethod,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "True when the sale this order became was voided (its delivery fee was reversed with it)")
            Boolean saleVoided,

            Instant createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID createdBy,
            int version,
            List<OrderLine> lines,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Who did what, when; on a single order only")
            List<OrderEvent> history) {}

    @Schema(name = "RetailOrderPage")
    record OrderPage(List<Order> items, String nextCursor) {}

    @Schema(
            name = "RetailOrderBoard",
            description = "How many open orders are at each step, and the deliveries of today")
    record Board(
            LocalDate today,
            long ordered,
            long packed,
            long outForDelivery,

            @Schema(description = "Open orders to deliver today")
            long dueToday,

            @Schema(description = "Open orders whose delivery day has passed")
            long overdue,

            long deliveredToday) {}

    @Schema(name = "RetailOrderDelivered")
    record Delivered(
            Order order,
            UUID saleId,
            String saleNo,

            @Schema(description = "Money taken at the door, delivery fee included")
            long paidNowMinor,

            @Schema(description = "Left on credit") long owedMinor) {}

    @Schema(
            name = "RetailOrderCustomerLookup",
            description = "What the last orders of a phone number say about its customer")
    record Lookup(
            boolean found,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "A credit buyer whose contact is this number")
            UUID customerId,

            @JsonInclude(JsonInclude.Include.NON_NULL) String customerName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String customerPhone,
            @JsonInclude(JsonInclude.Include.NON_NULL) String deliveryPlace,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long deliveryFeeMinor,

            @Schema(description = "Orders this number made before, in the caller's branches")
            long orders) {}

    @Schema(name = "RetailOrderReportRow")
    record ReportRow(String key, String label, long orders, long itemsMinor, long feesMinor) {}

    @Schema(
            name = "RetailOrderFeesReport",
            description = "Delivered orders and the delivery fees taken, by day, source or rider")
    record FeesReport(
            LocalDate from,
            LocalDate to,
            String groupBy,
            String currency,
            long orders,
            long itemsMinor,
            long feesMinor,
            List<ReportRow> rows) {}

    @Schema(name = "RetailCancelledOrder")
    record CancelledOrder(
            UUID id,
            String orderNo,
            UUID branchId,
            LocalDate cancelledOn,
            String source,
            String customerName,
            long itemsTotalMinor,
            String reason,
            UUID cancelledBy) {}

    @Schema(name = "RetailOrderCancellationsReport", description = "Orders cancelled, and why")
    record CancellationsReport(
            LocalDate from,
            LocalDate to,
            String currency,
            long orders,
            long itemsMinor,

            @Schema(description = "By source; fees_minor is 0")
            List<ReportRow> bySource,

            @Schema(description = "At most 500, newest first")
            List<CancelledOrder> items) {}
}
