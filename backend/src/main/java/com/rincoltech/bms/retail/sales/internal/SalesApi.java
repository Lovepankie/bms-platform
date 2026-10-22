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
import java.util.Map;
import java.util.UUID;

/**
 * Request and response bodies of the retail sales and customer routes (chapter 7 section
 * 7.11.20). Cost snapshot and profit fields are absent without {@code retail.profit.read}.
 */
final class SalesApi {

    static final String COST_ONLY = "Present only with retail.profit.read";
    static final String SALE_METHODS = "cash|mobile_money|bank|credit";

    private SalesApi() {}

    @Schema(name = "RetailSaleLineRequest")
    record SaleLineRequest(
            @NotNull UUID productId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "2.500")
            BigDecimal qty,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(
                    description = "Per unit_id when one is given, otherwise per base unit. Defaults to the product's"
                            + " sell price (times the pack factor, rounded half up once, for a pack)")
            Long unitPriceMinor,

            @Schema(
                    description = "A pack unit of the product (units capability): qty and unit_price_minor are then"
                            + " in that unit and stock moves in base units. Absent or the base unit: as before")
            UUID unitId,

            @Size(max = 300)
            @Schema(
                    description =
                            "Why the unit price differs from the price the price list resolved; required for such a"
                                    + " price (retail.price.override) while the price_lists capability is on, ignored otherwise")
            String priceOverrideReason) {}

    @Schema(name = "RetailSaleRequest")
    record SaleRequest(
            @Schema(description = "Defaults to the caller's one branch")
            UUID branchId,

            @Schema(description = "Defaults to today; not in the future")
            LocalDate saleDate,

            @NotNull @Pattern(regexp = SALE_METHODS) String paymentMethod,
            UUID customerId,
            @Size(max = 200) String buyerName,
            @Size(max = 100) String buyerContact,

            @Schema(description = "Proposed payment date of a credit sale")
            LocalDate dueDate,

            @Schema(
                    description = "Price the sale from this list instead of the customer's own or the customer group's;"
                            + " needs retail.pricelist.use unless it is one of those (price_lists capability)")
            UUID priceListId,

            @Size(max = 300)
            @Schema(
                    description = "Why a credit sale above the buyer's credit limit goes ahead; needed (at least five"
                            + " characters) from a user who holds retail.credit.override, ignored when the sale is"
                            + " within the limit or the customer_accounts capability is off")
            String creditOverrideReason,

            @NotEmpty @Size(max = 200) List<@Valid @NotNull SaleLineRequest> lines,

            @Schema(
                    description = "ADR-067: the id the device made for this sale, unique per business. A second request"
                            + " with it, by the same person, answers the sale already recorded (Idempotent-Replayed),"
                            + " after the Idempotency-Key has expired too; by another person it is 422"
                            + " client_sale_id_reused")
            UUID clientSaleId,

            @Schema(
                    description = "ADR-067: when the sale happened on the device; the sale is dated that day in the"
                            + " business's time zone. Up to five minutes ahead is recorded as now; further ahead is 422"
                            + " occurred_at_in_future, or for a sale recorded offline now with a clock_ahead clash")
            Instant occurredAt,

            @Schema(
                    description = "ADR-067: the sale was taken with no network and its money is already in hand. Needs"
                            + " client_sale_id and occurred_at; never a credit sale. A short stock, an item switched off,"
                            + " a changed price or a price not above cost then records the sale with a clash instead of"
                            + " refusing it")
            Boolean recordedOffline,

            @Schema(description = "ADR-067: when the device last refreshed its prices, for the words of a price clash")
            Instant pricesAsOf) {

        /** A sale request of the server's own (a quotation or an order becoming a sale): no device fields. */
        SaleRequest(
                UUID branchId,
                LocalDate saleDate,
                String paymentMethod,
                UUID customerId,
                String buyerName,
                String buyerContact,
                LocalDate dueDate,
                UUID priceListId,
                String creditOverrideReason,
                List<SaleLineRequest> lines) {
            this(
                    branchId,
                    saleDate,
                    paymentMethod,
                    customerId,
                    buyerName,
                    buyerContact,
                    dueDate,
                    priceListId,
                    creditOverrideReason,
                    lines,
                    null,
                    null,
                    null,
                    null);
        }

        boolean offline() {
            return Boolean.TRUE.equals(recordedOffline);
        }
    }

    @Schema(
            name = "RetailSaleLinePrice",
            description =
                    "How the price of a line was chosen; absent on a line made without the price_lists capability")
    record LinePrice(
            @Schema(
                    description = "retail (the product's sell price), list (a price list item), override (the seller's"
                            + " price instead of the resolved one) or order_agreed (the price agreed on a customer"
                            + " order that today's list would price otherwise, issue #385)")
            String source,

            @JsonInclude(JsonInclude.Include.NON_NULL) UUID priceListId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String priceList,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The quantity break of the list item that applied, in the sale unit")
            String breakMinQty,

            @Schema(
                    description =
                            "What the system resolved, per sale unit (per base unit when no pack), before any override")
            long resolvedUnitPriceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) String overrideReason) {}

    @Schema(name = "RetailSaleLine")
    record SaleLine(
            UUID id,
            int lineNo,
            UUID productId,
            String code,
            String description,
            @Schema(description = "In base units, always") String qty,

            @Schema(description = "Per base unit; for a pack line the derived, rounded display value")
            long unitPriceMinor,

            long lineTotalMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long unitCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long lineCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The unit the line was entered in; absent means the base unit")
            UUID saleUnitId,

            @JsonInclude(JsonInclude.Include.NON_NULL) String saleUnit,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Quantity in sale_unit")
            String saleQty,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Price per sale_unit; line_total_minor = sale_qty x this, rounded half up once")
            Long saleUnitPriceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Base units in one sale_unit when the line was made")
            String unitFactor,

            @JsonInclude(JsonInclude.Include.NON_NULL) LinePrice price) {}

    @Schema(name = "RetailSale")
    record Sale(
            UUID id,
            String saleNo,
            UUID branchId,
            LocalDate saleDate,
            String paymentMethod,
            UUID customerId,
            String buyerName,
            String buyerContact,
            LocalDate dueDate,
            String status,
            String currency,
            long totalMinor,
            long paidMinor,
            long balanceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costTotalMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long profitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The price list the sale was priced from, when one applied")
            UUID priceListId,

            @JsonInclude(JsonInclude.Include.NON_NULL) String priceList,
            List<SaleLine> lines,
            boolean historical,
            Instant createdAt,
            UUID createdBy,
            Instant voidedAt,
            UUID voidedBy,
            String voidReason,
            int version,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Present when the unpaid part of this credit sale was written off (ADR-043)")
            WriteOffInfo writeOff,

            @Schema(
                    description =
                            "True for a credit sale counted when the buyer pays (ADR-043): the later of its date and the day it was"
                                    + " entered is on or after the first day of the rule on_payment, so a backdated sale entered after the"
                                    + " start counts too; its payments are dated the day they are entered")
            boolean countedWhenPaid,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #385: the delivery fee of the customer order this credit sale came from, left on the"
                                    + " buyer's account and not yet paid: owed besides balance_minor, and written off with the sale")
            Long deliveryFeeOwedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "ADR-067: present when the sale carried a client sale id")
            Device device) {

        Sale withDevice(Device d) {
            return new Sale(
                    id,
                    saleNo,
                    branchId,
                    saleDate,
                    paymentMethod,
                    customerId,
                    buyerName,
                    buyerContact,
                    dueDate,
                    status,
                    currency,
                    totalMinor,
                    paidMinor,
                    balanceMinor,
                    costTotalMinor,
                    profitMinor,
                    priceListId,
                    priceList,
                    lines,
                    historical,
                    createdAt,
                    createdBy,
                    voidedAt,
                    voidedBy,
                    voidReason,
                    version,
                    writeOff,
                    countedWhenPaid,
                    deliveryFeeOwedMinor,
                    d);
        }
    }

    @Schema(name = "RetailSaleDevice", description = "How a sale came from a device (ADR-067)")
    record Device(
            UUID clientSaleId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "When the sale happened on the device, as recorded")
            Instant occurredAt,

            boolean recordedOffline,
            List<Clash> clashes) {}

    @Schema(name = "RetailSaleClash", description = "Something that changed while a sale was offline (ADR-067)")
    record Clash(
            UUID id,
            UUID saleId,

            @JsonInclude(JsonInclude.Include.NON_NULL) String saleNo,
            UUID branchId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The sale line; absent for clock_ahead and clock_behind")
            Integer lineNo,

            @JsonInclude(JsonInclude.Include.NON_NULL) UUID productId,

            @Schema(
                    description = "stock_short, item_inactive, pack_inactive, price_changed, price_typed, below_cost,"
                            + " clock_ahead or clock_behind")
            String kind,

            @Schema(description = "What happened, in plain words; never a cost")
            String words,

            Instant createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant resolvedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID resolvedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) String resolutionNote) {}

    @Schema(name = "RetailSaleClashPage")
    record ClashPage(List<Clash> items) {}

    @Schema(name = "RetailSaleClashResolve")
    record ClashResolveRequest(
            @NotBlank @Size(min = 5, max = 300) String note) {}

    @Schema(name = "RetailOfflineFailureRequest", description = "A sale made offline that the server refused (ADR-067)")
    record FailureRequest(
            @NotNull UUID clientSaleId,
            @NotNull UUID branchId,
            @NotNull Instant occurredAt,
            @NotNull @Pattern(regexp = SALE_METHODS) String paymentMethod,

            @NotNull @PositiveOrZero @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long totalMinor,

            @NotNull @Schema(description = "The sale request exactly as the device holds it")
            Map<String, Object> request,

            @NotBlank @Size(max = 60) String code,
            @NotBlank @Size(max = 500) String message) {}

    @Schema(name = "RetailOfflineFailure")
    record Failure(
            UUID id,
            UUID clientSaleId,
            UUID branchId,
            UUID madeBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) String madeByName,
            Instant occurredAt,
            String paymentMethod,
            long totalMinor,
            Map<String, Object> request,
            String code,
            String message,

            @Schema(description = "open, retry, discarded or recorded")
            String status,

            @JsonInclude(JsonInclude.Include.NON_NULL) UUID decidedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant decidedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String discardReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID saleId,
            Instant createdAt) {}

    @Schema(name = "RetailOfflineFailurePage")
    record FailurePage(List<Failure> items) {}

    @Schema(name = "RetailOfflineFailureDiscard")
    record DiscardRequest(
            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailSaleWriteOffInfo", description = "A credit sale whose unpaid part was written off as a loss")
    record WriteOffInfo(
            LocalDate writtenOffOn,

            @Schema(description = "Everything the write-off cleared, a delivery fee on the account included")
            long unpaidMinor,

            String reason,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Issue #385: the part of unpaid_minor that was a delivery fee left on the account")
            Long deliveryFeeMinor) {}

    @Schema(name = "RetailSalePage")
    record SalePage(List<Sale> items, String nextCursor) {}

    @Schema(name = "RetailVoidRequest")
    record VoidRequest(@NotBlank @Size(max = 300) String reason) {}

    @Schema(name = "RetailCustomerRequest")
    record CustomerRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String contact) {}

    @Schema(name = "RetailCustomerUpdate", description = "Omitted fields are unchanged; an empty contact clears it")
    record UpdateCustomerRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 100) String contact) {}

    @Schema(name = "RetailCustomer")
    record Customer(
            UUID id,
            String name,
            String contact,
            Instant createdAt,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "In the list only: owed on credit sales in the caller's branch scope")
            Long balanceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The customer group; present only while the price_lists capability is on")
            UUID customerGroupId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The customer's own price list; present only while the price_lists capability is on")
            UUID priceListId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "The credit limit, when the buyer has one; present only while the customer_accounts capability is on")
            Long creditLimitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "The payment terms in days, when the buyer has them; present only while the customer_accounts capability is on")
            Integer termsDays,

            @Schema(description = "Send as If-Match when updating")
            int version) {}

    @Schema(name = "RetailCustomerList")
    record CustomerList(List<Customer> items, String nextCursor) {}

    @Schema(name = "RetailOpenSale")
    record OpenSale(
            UUID saleId,
            String saleNo,
            UUID branchId,
            LocalDate saleDate,
            LocalDate dueDate,
            long totalMinor,
            long paidMinor,
            long balanceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "True for an imported credit sale (never posted to the ledger, skipped by payment on account);"
                                    + " present only while the customer_accounts capability is on, and only when true")
            Boolean historical,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "True for a credit sale counted when the buyer pays (ADR-043): a payment that pays it is dated today;"
                                    + " present only while the customer_accounts capability is on, and only when true")
            Boolean countedWhenPaid) {}

    @Schema(name = "RetailCustomerBalance", description = "Credit sales in the caller's branch scope")
    record CustomerBalance(
            UUID customerId,
            String currency,

            @Schema(description = "Owed on the open credit sales: what a payment on account can settle")
            long balanceMinor,

            List<OpenSale> openSales,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #385: delivery fees of customer orders left on the account and not yet paid, owed"
                                    + " besides balance_minor and paid on each order (POST /retail/orders/{order_id}/fee-payment)")
            Long deliveryFeesOwedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) List<FeeOwed> deliveryFees) {}

    @Schema(
            name = "RetailDeliveryFeeOwed",
            description = "A delivery fee left on the buyer's account, paid on its order")
    record FeeOwed(
            UUID orderId,
            String orderNo,
            UUID saleId,
            String saleNo,
            UUID branchId,
            LocalDate deliveredOn,
            long feeMinor) {}

    @Schema(name = "RetailPaymentRequest")
    record PaymentRequest(
            @NotNull @Positive @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long amountMinor,

            @NotNull @Pattern(regexp = "cash|mobile_money|bank")
            String method,

            @Schema(description = "Defaults to today; not before the sale, not in the future")
            LocalDate paidOn) {}

    @Schema(name = "RetailPayment")
    record Payment(
            UUID id,
            UUID saleId,
            long amountMinor,
            String currency,
            String method,
            LocalDate paidOn,
            UUID journalEntryId,
            Instant createdAt,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Who took it: shown to the owner (retail.profit.read at the branch) and to the person (ADR-055)")
            UUID createdBy) {}

    @Schema(name = "RetailPaymentResult")
    record PaymentResult(Payment payment, long salePaidMinor, long saleBalanceMinor) {}

    @Schema(name = "RetailPaymentList")
    record PaymentList(List<Payment> items) {}

    @Schema(
            name = "RetailCustomerPricing",
            description =
                    "Replaces the customer's group and own price list; an absent field clears it (price_lists capability)")
    record CustomerPricingRequest(UUID customerGroupId, UUID priceListId) {}

    @Schema(name = "RetailPriceResolveLineRequest")
    record ResolveLineRequest(
            @NotNull UUID productId,

            @Schema(description = "A pack unit of the product; absent or the base unit means the base unit")
            UUID unitId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "2.500")
            BigDecimal qty) {}

    @Schema(name = "RetailPriceResolveRequest")
    record ResolveRequest(
            @Schema(description = "Defaults to the caller's one branch")
            UUID branchId,

            UUID customerId,

            @Schema(
                    description =
                            "As on a sale: needs retail.pricelist.use unless it is the customer's own or group's list")
            UUID priceListId,

            @Schema(description = "Defaults to today; not in the future")
            LocalDate saleDate,

            @NotEmpty @Size(max = 200) List<@Valid @NotNull ResolveLineRequest> lines) {}

    @Schema(name = "RetailPriceResolveLine")
    record ResolvedLine(
            UUID productId,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID unitId,
            String qty,

            @Schema(description = "Per unit_id, or per base unit when none")
            long unitPriceMinor,

            long lineTotalMinor,
            @Schema(description = "retail or list") String priceSource,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID priceListId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String breakMinQty,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "no_price_in_list when a strict list has no price for the line; the sale would be refused")
            String problem) {}

    @Schema(name = "RetailPriceResolution", description = "Price and source per line; never the cost")
    record Resolution(
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID priceListId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String priceList,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Why this list: request, customer or customer_group")
            String origin,

            List<ResolvedLine> lines) {}
}
