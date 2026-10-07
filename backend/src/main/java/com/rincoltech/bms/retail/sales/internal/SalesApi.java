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
            @Schema(description = "Defaults to the product's sell price")
            Long unitPriceMinor) {}

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

            @NotEmpty @Size(max = 200) List<@Valid @NotNull SaleLineRequest> lines) {}

    @Schema(name = "RetailSaleLine")
    record SaleLine(
            UUID id,
            int lineNo,
            UUID productId,
            String code,
            String description,
            String qty,
            long unitPriceMinor,
            long lineTotalMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long unitCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long lineCostMinor) {}

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
            Long balanceMinor) {}

    @Schema(name = "RetailCustomerList")
    record CustomerList(List<Customer> items) {}

    @Schema(name = "RetailOpenSale")
    record OpenSale(
            UUID saleId,
            String saleNo,
            UUID branchId,
            LocalDate saleDate,
            LocalDate dueDate,
            long totalMinor,
            long paidMinor,
            long balanceMinor) {}

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
}
