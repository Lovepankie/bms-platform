package com.rincoltech.bms.retail.sales.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the customer account routes (customer_accounts capability; ADR-033, issue #196;
 * docs/specs/trade-capabilities.md section 5). Amounts are integer minor units. Nothing here carries cost or profit.
 */
final class AccountsApi {

    private AccountsApi() {}

    @Schema(
            name = "RetailCustomerAccountRequest",
            description =
                    "Replaces the credit limit and the payment terms, sent under If-Match with the customer's version."
                            + " Each of the two is either given or cleared on purpose: a value, or its clear flag"
                            + " set to true, never neither and never both (422), so a body of {} changes nothing")
    record AccountRequest(
            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "The most the buyer may owe on credit sales, all branches together")
            Long creditLimitMinor,

            @Schema(description = "true removes the limit; do not send it with credit_limit_minor")
            Boolean clearCreditLimit,

            @Min(0)
            @Max(365)
            @Schema(description = "Days after the sale a credit sale falls due; 0 is the day of the sale")
            Integer termsDays,

            @Schema(description = "true removes the terms; do not send it with terms_days")
            Boolean clearTermsDays) {}

    @Schema(name = "RetailCustomerAccount")
    record Account(
            UUID customerId,
            String name,
            String currency,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Absent means no limit")
            Long creditLimitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Absent means no terms")
            Integer termsDays,

            @Schema(
                    description =
                            "Owed on credit sales in the caller's branch scope, historical imports included: what a"
                                    + " payment on account can settle")
            long balanceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #385: delivery fees of customer orders left on the account and not yet paid, owed"
                                    + " besides balance_minor and paid on each order")
            Long deliveryFeesOwedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) List<SalesApi.FeeOwed> deliveryFees,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "What the limit is measured against: owed on credit sales in every branch. Present only"
                                    + " when the caller's scope covers every branch, so no other branch's sales leak")
            Long exposureMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "The limit less the exposure, never below zero; present with a limit and exposure_minor")
            Long availableMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The exposure is above the limit; present with a limit and exposure_minor")
            Boolean overLimit,

            @Schema(description = "Send as If-Match when updating the account")
            int version) {}

    // ---- Statement ---------------------------------------------------------------------------

    @Schema(
            name = "RetailStatementLine",
            description = "kind is sale, payment (against one sale), account_payment (a receipt, one line for all its"
                    + " allocations in scope), reversal (of a receipt) or void (of a sale)")
    record StatementLine(
            LocalDate date,
            String kind,
            String reference,

            @JsonInclude(JsonInclude.Include.NON_NULL) UUID saleId,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID receiptId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String method,
            long debitMinor,
            long creditMinor,
            long balanceMinor) {}

    @Schema(name = "RetailStatementHistoricalSale")
    record HistoricalSale(UUID saleId, String saleNo, UUID branchId, LocalDate saleDate, long totalMinor) {}

    @Schema(
            name = "RetailStatementBeforeSystemStart",
            description =
                    "Imported credit sales. They were never posted to the ledger (issue #96), so the statement lists"
                            + " them apart and the ledger-supported closing balance leaves them out")
    record BeforeSystemStart(List<HistoricalSale> sales, long totalMinor) {}

    @Schema(name = "RetailCustomerStatement")
    record Statement(
            UUID customerId,
            String name,
            String currency,
            LocalDate from,
            LocalDate to,

            @Schema(description = "Owed at the start of from, on the sales and payments the ledger holds")
            long openingMinor,

            List<StatementLine> lines,

            @Schema(
                    description = "Opening plus the lines: what the ledger holds for the buyer's sales in the caller's"
                            + " branch scope (trade debtors by sale), as of the end of to")
            long closingMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) BeforeSystemStart beforeSystemStart,

            @Schema(
                    description = "closing_minor plus the imported credit sales before system start: equals GET"
                            + " /customers/{id}/balance in the caller's branch scope when to is today or later")
            long balanceMinor) {}

    // ---- Payment on account -------------------------------------------------------------------

    @Schema(name = "RetailAccountAllocationRequest")
    record AllocationRequest(
            @NotNull UUID saleId,

            @NotNull @Positive @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long amountMinor) {}

    @Schema(name = "RetailAccountPaymentRequest")
    record PaymentRequest(
            @Schema(description = "The branch that received the money; defaults to the caller's one branch")
            UUID branchId,

            @NotNull @Positive @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long amountMinor,

            @NotNull @Pattern(regexp = "cash|mobile_money|bank")
            String method,

            @Schema(description = "Defaults to today; not in the future")
            LocalDate paidOn,

            @Size(max = 200)
            @Schema(
                    description =
                            "Absent: the amount is allocated to the oldest open credit sales first. Present: exactly"
                                    + " these sales and amounts, which must add up to amount_minor")
            List<@Valid @NotNull AllocationRequest> allocations) {}

    @Schema(name = "RetailAccountAllocation")
    record Allocation(UUID saleId, String saleNo, UUID branchId, long amountMinor, UUID salePaymentId) {}

    @Schema(name = "RetailAccountPayment")
    record AccountPayment(
            UUID id,
            String receiptNo,
            UUID customerId,
            UUID branchId,
            long amountMinor,
            String currency,
            String method,
            LocalDate paidOn,
            @Schema(description = "oldest_first or manual") String allocation,
            @Schema(description = "posted or reversed") String status,
            List<Allocation> allocations,
            UUID journalEntryId,
            Instant createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID createdBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant reversedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID reversedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reverseReason) {}

    @Schema(name = "RetailAccountPaymentList")
    record AccountPaymentList(List<AccountPayment> items, String nextCursor) {}

    @Schema(name = "RetailAccountPaymentReverseRequest")
    record ReverseRequest(@NotBlank @Size(max = 300) String reason) {}
}
