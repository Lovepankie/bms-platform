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

            @NotEmpty @Size(max = 200) List<@Valid @NotNull SaleLineRequest> lines) {}

    @Schema(
            name = "RetailSaleLinePrice",
            description =
                    "How the price of a line was chosen; absent on a line made without the price_lists capability")
    record LinePrice(
            @Schema(
                    description =
                            "retail (the product's sell price), list (a price list item) or override (the seller's"
                                    + " price instead of the resolved one)")
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
            int version) {}

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
            Boolean historical) {}

    @Schema(name = "RetailCustomerBalance", description = "Credit sales in the caller's branch scope")
    record CustomerBalance(UUID customerId, String currency, long balanceMinor, List<OpenSale> openSales) {}

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
