package com.rincoltech.bms.lending.savings.internal;

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
 * Request and response bodies of savings (chapter 7 section 7.11.15; FR-SAV-01 to FR-SAV-07).
 * Money is integer minor units in the account's currency; rates are basis points per year.
 */
final class SavingsApi {

    /** Payment methods money moves by; each maps to a ledger account by system key (ADR-026). */
    static final String METHODS = "cash|bank|mtn_momo|airtel_money";

    static final long MAX_MONEY_MINOR = 10_000_000_000_000L;

    private SavingsApi() {}

    // ---- Products (FR-SAV-01) ---------------------------------------------------------------

    @Schema(name = "SavingsProductTerms", description = "FR-SAV-01: the product's rules")
    record Terms(
            @NotBlank @Size(max = 100) String name,

            @NotNull @Min(0) @Max(10_000) @Schema(description = "Per year, in basis points; 0 with interest_calc none")
            Integer interestRateBp,

            @NotNull @Pattern(regexp = "none|daily_balance|minimum_monthly_balance")
            String interestCalc,

            @NotNull @Pattern(regexp = "monthly|quarterly|yearly")
            String interestPosting,

            @PositiveOrZero
            @Max(MAX_MONEY_MINOR)
            @Schema(description = "A day or month below this balance earns nothing; 0 when omitted")
            Long minBalanceForInterestMinor,

            @PositiveOrZero @Max(MAX_MONEY_MINOR) @Schema(description = "The first deposit's minimum; 0 when omitted")
            Long minOpeningBalanceMinor,

            @PositiveOrZero
            @Max(MAX_MONEY_MINOR)
            @Schema(description = "A withdrawal never takes the balance below this; 0 when omitted")
            Long minBalanceMinor,

            @PositiveOrZero @Max(MAX_MONEY_MINOR) @Schema(description = "Flat fee per withdrawal; 0 when omitted")
            Long withdrawalFeeMinor,

            @Positive @Max(MAX_MONEY_MINOR) @Schema(description = "The most one withdrawal may take; none when omitted")
            Long maxWithdrawalMinor,

            @Positive @Max(1000) @Schema(description = "Withdrawals allowed per calendar month; none when omitted")
            Integer maxWithdrawalsPerMonth,

            @Positive
            @Max(3650)
            @Schema(
                    description =
                            "Days without a member deposit or withdrawal before the account is dormant (FR-SAV-06)")
            Integer dormancyDays) {}

    @Schema(name = "CreateSavingsProductRequest")
    record CreateProductRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{1,19}")
            String code,

            @Pattern(regexp = "[A-Z]{3}") @Schema(description = "The tenant's currency when omitted")
            String currency,

            @NotNull @Valid Terms terms) {}

    @Schema(
            name = "UpdateSavingsProductRequest",
            description = "The interest fields cannot change once an account uses the product")
    record UpdateProductRequest(
            @NotNull @Valid Terms terms,
            @NotNull @Pattern(regexp = "active|archived") String status) {}

    @Schema(name = "SavingsProduct")
    record Product(
            UUID id,
            int version,
            String code,
            String name,
            String currency,
            int interestRateBp,
            String interestCalc,
            String interestPosting,
            long minBalanceForInterestMinor,
            long minOpeningBalanceMinor,
            long minBalanceMinor,
            long withdrawalFeeMinor,
            Long maxWithdrawalMinor,
            Integer maxWithdrawalsPerMonth,
            Integer dormancyDays,
            String status,

            @Schema(description = "Accounts on the product; the interest fields are locked when above zero")
            long accounts) {}

    @Schema(name = "SavingsProductList")
    record ProductList(List<Product> items) {}

    // ---- Accounts (FR-SAV-02) ---------------------------------------------------------------

    @Schema(name = "OpenSavingsAccountRequest")
    record OpenAccountRequest(
            @NotNull UUID memberId,
            @NotNull UUID productId,

            @Schema(description = "The member's home branch when omitted (FR-BR-05)")
            UUID branchId) {}

    @Schema(name = "SavingsAccount")
    record Account(
            UUID id,
            int version,
            String accountNo,
            UUID branchId,
            UUID memberId,
            String memberNo,
            String memberName,
            UUID productId,
            String productCode,
            String productName,
            String currency,

            @Schema(description = "active, dormant, frozen or closed")
            String status,

            String statusReason,
            long balanceMinor,
            long holdMinor,
            long minBalanceMinor,
            long withdrawalFeeMinor,

            @Schema(description = "The most a withdrawal may take now: balance less hold, minimum balance and fee")
            long availableMinor,

            int interestRateBp,
            String interestCalc,
            String interestPosting,

            @Schema(
                    description = "Interest earned since the last posting through balances_through, an estimate rounded"
                            + " for display; the posting rounds once over the whole period")
            long accruedInterestMinor,

            LocalDate openedOn,
            LocalDate closedOn,
            LocalDate lastMemberTxnOn,
            LocalDate lastInterestPostedTo,
            LocalDate balancesThrough) {}

    @Schema(name = "SavingsAccountPage")
    record AccountPage(List<Account> items, String nextCursor) {}

    // ---- Movements (FR-SAV-03, FR-SAV-04) ----------------------------------------------------

    @Schema(name = "SavingsMoneyRequest")
    record MoneyRequest(
            @NotNull @Positive @Max(MAX_MONEY_MINOR) Long amountMinor,

            @Schema(description = "Today when omitted; today or earlier, after the last day the nightly job has closed")
            LocalDate valueDate,

            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference) {}

    @Schema(
            name = "SavingsWithdrawalRequest",
            description = "Dated the day it executes: today, or the day a checker approves it")
    record WithdrawalRequest(
            @NotNull @Positive @Max(MAX_MONEY_MINOR) Long amountMinor,
            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference) {}

    @Schema(name = "SavingsCloseRequest")
    record CloseRequest(
            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference,
            @Size(max = 500) String reason) {}

    @Schema(name = "SavingsReasonRequest")
    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "SavingsTransaction")
    record Transaction(
            UUID id,
            int seq,

            @Schema(description = "deposit, withdrawal, interest, fee, transfer_in, transfer_out or reversal")
            String txnType,

            long amountMinor,
            String currency,

            @Schema(description = "True when the money went into the account")
            boolean credit,

            long balanceAfterMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            String receiptNo,
            String reason,
            UUID relatedTxnId,
            UUID reversesTxnId,
            UUID reversedByTxnId,
            UUID approvalRequestId,
            String source,
            Instant recordedAt) {}

    @Schema(name = "SavingsTransactionPage")
    record TransactionPage(List<Transaction> items, String nextCursor) {}

    @Schema(name = "SavingsDepositResult")
    record DepositResult(Transaction transaction, Account account) {}

    @Schema(
            name = "SavingsActionOutcome",
            description =
                    "executed: the action took effect now (below the tenant's threshold); otherwise it waits for a"
                            + " checker as approval_request_id. transaction is the withdrawal when executed")
    record ActionOutcome(
            UUID accountId, boolean executed, UUID approvalRequestId, Transaction transaction, Account account) {}

    // ---- Statement and reports ----------------------------------------------------------------

    @Schema(name = "SavingsStatementLine")
    record StatementLine(
            int seq,
            LocalDate valueDate,
            String txnType,
            String receiptNo,
            String description,
            long debitMinor,
            long creditMinor,
            long balanceMinor) {}

    @Schema(
            name = "SavingsStatement",
            description = "Movements recorded in the range by value date, in recording order")
    record Statement(
            UUID accountId,
            String accountNo,
            String memberNo,
            String memberName,
            String productName,
            String currency,
            LocalDate from,
            LocalDate to,
            long openingBalanceMinor,
            long totalCreditsMinor,
            long totalDebitsMinor,
            long closingBalanceMinor,
            List<StatementLine> lines) {}

    @Schema(name = "SavingsBalanceRow")
    record BalanceRow(
            UUID accountId,
            String accountNo,
            UUID branchId,
            String memberNo,
            String memberName,
            String productCode,
            String status,
            long balanceMinor,
            LocalDate lastMemberTxnOn) {}

    @Schema(name = "SavingsTotal")
    record Total(UUID branchId, String productCode, long accounts, long balanceMinor) {}

    @Schema(
            name = "SavingsBalancesReport",
            description =
                    "lending.savings_balances (chapter 14 section 14.5): balances as at the end of a day; totals per"
                            + " product and branch reconcile to member_savings")
    record BalancesReport(
            LocalDate asAt, String currency, List<BalanceRow> rows, List<Total> totals, long totalMinor) {}

    @Schema(name = "SavingsMovementRow")
    record MovementRow(
            UUID branchId,
            String productCode,
            long openingMinor,
            long depositsMinor,
            long withdrawalsMinor,
            long interestMinor,
            long feesMinor,
            long reversalsInMinor,
            long reversalsOutMinor,
            long netMinor,
            long closingMinor) {}

    @Schema(
            name = "SavingsMovementsReport",
            description = "lending.savings_movements (chapter 14 section 14.5): movements by value date in the range")
    record MovementsReport(LocalDate from, LocalDate to, String currency, List<MovementRow> rows, MovementRow total) {}
}
