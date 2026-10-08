package com.rincoltech.bms.retail.cashbook.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the {@code /retail} cash book routes (chapter 7 section 7.11.21; ADR-022). A field
 * marked {@link #PROFIT_ONLY} is absent, not null, without {@code retail.profit.read} in the
 * record's branch: it is a savings amount, the suggestion, a cash restock total or a figure that
 * embeds one of them (ADR-022 decision 13).
 */
final class CashbookApi {

    static final String PROFIT_ONLY = "Present only with retail.profit.read in the record's branch";
    static final long MAX_MINOR = 10_000_000_000_000L;

    private CashbookApi() {}

    // ------------------------------------------------------------------------------------ setup

    @Schema(name = "RetailCashExpenseItem")
    record ExpenseItem(UUID id, String name, boolean requiresExplanation, boolean active, int version) {}

    @Schema(name = "RetailCashExpenseCategory")
    record ExpenseCategory(
            UUID id,
            String name,
            UUID expenseAccountId,
            boolean active,
            int sortOrder,
            int version,
            List<ExpenseItem> items) {}

    @Schema(name = "RetailCashExpenseCategoryList")
    record ExpenseCategoryList(List<ExpenseCategory> items) {}

    @Schema(name = "RetailCashCategoryRequest")
    record CategoryRequest(@NotBlank @Size(max = 100) String name, UUID expenseAccountId) {}

    @Schema(name = "RetailCashCategoryPatch")
    record CategoryPatch(
            @Size(max = 100) String name,

            @Schema(description = "A postable expense account; send the nil UUID to go back to operating expenses")
            UUID expenseAccountId,

            Boolean active,
            Integer sortOrder) {}

    @Schema(name = "RetailCashItemRequest")
    record ItemRequest(@NotBlank @Size(max = 100) String name, Boolean requiresExplanation) {}

    @Schema(name = "RetailCashItemPatch")
    record ItemPatch(@Size(max = 100) String name, Boolean requiresExplanation, Boolean active) {}

    @Schema(name = "RetailCashParty")
    record Party(UUID id, String name, String contact, String kind, boolean active) {}

    @Schema(name = "RetailCashPartyPage")
    record PartyPage(List<Party> items, String nextCursor) {}

    @Schema(name = "RetailCashPartyRequest")
    record PartyRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String contact,

            @NotBlank @Schema(allowableValues = {"owner", "staff", "related_entity", "supplier", "other"})
            String kind) {}

    @Schema(name = "RetailCashVoidRequest")
    record VoidRequest(@NotBlank @Size(max = 300) String reason) {}

    // ---------------------------------------------------------------------------------- savings

    @Schema(name = "RetailSavingsSuggestion")
    record SavingsSuggestion(
            UUID branchId,
            LocalDate businessDate,
            long totalSoldMinor,

            @Schema(description = "Opaque; echo it on the create call", requiredMode = Schema.RequiredMode.REQUIRED)
            String suggestionToken,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long suggestedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long dailyProfitMinor,

            UUID existingId) {}

    @Schema(name = "RetailSavingsRequest")
    record SavingsRequest(
            UUID branchId,
            LocalDate businessDate,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String suggestionToken,

            @PositiveOrZero @Max(MAX_MINOR) @Schema(description = "Needs retail.profit.read; omit to take the default")
            Long amountMinor,

            @Size(max = 300) String overwriteReason) {}

    @Schema(name = "RetailSavings")
    record Savings(
            UUID id,
            UUID branchId,
            LocalDate businessDate,
            String currency,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long amountMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long suggestedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Boolean overwritten,

            long totalSoldMinor,
            UUID by,
            String byName,
            Instant at,
            boolean voided,
            Instant voidedAt,
            String voidReason,
            boolean historical) {}

    @Schema(name = "RetailSavingsPage")
    record SavingsPage(List<Savings> items, String nextCursor) {}

    // --------------------------------------------------------------------------------- banking

    @Schema(name = "RetailBankingExpected")
    record BankingExpected(
            UUID branchId,
            LocalDate businessDate,
            long cashTakingsMinor,
            long cashSaleVoidsMinor,
            long expenseVoidsMinor,
            long advanceVoidsMinor,
            long repaymentVoidsMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long cashPurchasesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long savingsMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long savingsVoidsMinor,

            long expensesMinor,
            long advancesOutMinor,
            long repaymentsInMinor,

            @Schema(
                    description = "Takings less voids, expenses and advances paid out, plus repayments; before"
                            + " cash purchases and savings, so it carries no cost and no profit")
            long cashExpectedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long expectedMinor,

            long bankedSoFarMinor) {}

    @Schema(name = "RetailBankingRequest")
    record BankingRequest(
            UUID branchId,
            LocalDate businessDate,
            @NotNull @Positive @Max(MAX_MINOR) Long amountMinor,
            Instant bankedAt,
            @Size(max = 100) String reference) {}

    @Schema(name = "RetailBanking")
    record Banking(
            UUID id,
            UUID branchId,
            LocalDate businessDate,
            String currency,
            long amountMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long expectedMinor,

            Instant bankedAt,
            String reference,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long differenceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = PROFIT_ONLY,
                    allowableValues = {"ok", "shortfall", "surplus", "not_banked"})
            String flag,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            List<String> warnings,

            UUID by,
            String byName,
            Instant at,
            boolean voided,
            Instant voidedAt,
            String voidReason,
            boolean historical) {}

    @Schema(name = "RetailBankingPage")
    record BankingPage(List<Banking> items, String nextCursor) {}

    // ----------------------------------------------------------------------------- withdrawals

    @Schema(name = "RetailWithdrawalRequest")
    record WithdrawalRequest(
            UUID branchId,
            LocalDate businessDate,
            @NotNull @Positive @Max(MAX_MINOR) Long amountMinor,
            Instant withdrawnAt,
            @Size(max = 300) String purpose) {}

    @Schema(name = "RetailWithdrawal")
    record Withdrawal(
            UUID id,
            UUID branchId,
            LocalDate businessDate,
            String currency,
            long amountMinor,
            Instant withdrawnAt,
            String purpose,
            List<String> warnings,
            UUID by,
            String byName,
            Instant at,
            boolean voided,
            Instant voidedAt,
            String voidReason,
            boolean historical) {}

    @Schema(name = "RetailWithdrawalPage")
    record WithdrawalPage(List<Withdrawal> items, String nextCursor) {}

    // ---------------------------------------------------------------------------------- expenses

    @Schema(name = "RetailExpenseRequest")
    record ExpenseRequest(
            UUID branchId,
            LocalDate businessDate,
            @NotNull UUID categoryId,
            @NotNull UUID itemId,
            UUID partyId,
            @NotNull @Positive @Max(MAX_MINOR) Long amountMinor,
            @Size(max = 500) String explanation,
            UUID receiptDocumentId) {}

    @Schema(name = "RetailExpense")
    record Expense(
            UUID id,
            UUID branchId,
            LocalDate businessDate,
            UUID categoryId,
            String categoryName,
            UUID itemId,
            String itemName,
            UUID partyId,
            String partyName,
            String currency,
            long amountMinor,
            String explanation,
            UUID receiptDocumentId,
            UUID by,
            String byName,
            Instant at,
            boolean voided,
            Instant voidedAt,
            String voidReason,
            boolean historical) {}

    @Schema(name = "RetailExpensePage")
    record ExpensePage(List<Expense> items, String nextCursor) {}

    // ---------------------------------------------------------------------------------- advances

    @Schema(name = "RetailAdvanceRequest")
    record AdvanceRequest(
            UUID branchId,
            LocalDate businessDate,
            @NotNull UUID partyId,
            UUID takenByPartyId,
            @NotNull @Positive @Max(MAX_MINOR) Long principalMinor,
            @Size(max = 300) String purpose) {}

    @Schema(name = "RetailRepaymentRequest")
    record RepaymentRequest(
            @Schema(description = "The branch that receives the money; defaults to the advance's branch")
            UUID branchId,

            @NotNull @Positive @Max(MAX_MINOR) Long amountMinor,

            @NotBlank @Schema(allowableValues = {"cash", "mobile_money", "bank"})
            String method,

            LocalDate paidOn) {}

    @Schema(name = "RetailRepayment")
    record Repayment(
            UUID id,
            UUID advanceId,
            UUID branchId,
            String currency,
            long amountMinor,
            String method,
            LocalDate paidOn,

            @Schema(description = "The advance's balance after this repayment, on the create call only")
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Long balanceMinor,

            UUID by,
            String byName,
            Instant at,
            boolean voided,
            Instant voidedAt,
            String voidReason,
            boolean historical) {}

    @Schema(name = "RetailAdvance")
    record Advance(
            UUID id,
            String advanceNo,
            UUID branchId,
            LocalDate businessDate,
            UUID partyId,
            String partyName,
            UUID takenByPartyId,
            String takenByName,
            String currency,
            long principalMinor,
            long repaidMinor,
            long balanceMinor,
            String purpose,
            String note,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Repayment> repayments,
            UUID by,
            String byName,
            Instant at,
            boolean voided,
            Instant voidedAt,
            String voidReason,
            boolean historical) {}

    @Schema(name = "RetailAdvancePage")
    record AdvancePage(List<Advance> items, String nextCursor) {}

    // ----------------------------------------------------------------------------------- reports

    @Schema(name = "RetailCashDailyRow")
    record CashDay(
            UUID branchId,
            LocalDate businessDate,
            boolean ledgerBasis,
            boolean historical,
            long cashTakingsMinor,
            long cashSaleVoidsMinor,
            long expenseVoidsMinor,
            long advanceVoidsMinor,
            long repaymentVoidsMinor,
            long bankingVoidsMinor,
            long withdrawalVoidsMinor,
            long expensesMinor,
            long advancesOutMinor,
            long repaymentsInMinor,
            long withdrawalsInMinor,
            long bankedMinor,
            long cashExpectedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long cashPurchasesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long savingsMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long savingsVoidsMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long openingMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long closingMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long otherMovementsMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long expectedToBankMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long unbankedRunningMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long dailyProfitMinor) {}

    @Schema(name = "RetailCashDailyReport")
    record CashDailyReport(LocalDate from, LocalDate to, String currency, List<CashDay> items) {}

    @Schema(name = "RetailBankingEntry")
    record BankingEntry(UUID id, long amountMinor, Instant bankedAt, UUID by, String byName, boolean voided) {}

    @Schema(name = "RetailBankingDay")
    record BankingDay(
            UUID branchId,
            LocalDate businessDate,
            boolean historical,
            long cashExpectedMinor,
            long bankedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long expectedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long differenceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = PROFIT_ONLY,
                    allowableValues = {"ok", "shortfall", "surplus", "not_banked"})
            String flag,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = PROFIT_ONLY + "; absent on an imported day")
            Long unbankedRunningMinor,

            List<BankingEntry> entries) {}

    @Schema(name = "RetailBankingReport")
    record BankingReport(LocalDate from, LocalDate to, String currency, List<BankingDay> items) {}

    @Schema(name = "RetailExpenseGroup")
    record ExpenseGroup(String key, String label, UUID branchId, long totalMinor, long count) {}

    @Schema(name = "RetailExpenseReport")
    record ExpenseReport(
            LocalDate from,
            LocalDate to,
            String currency,
            String groupBy,
            long totalMinor,
            long count,
            List<ExpenseGroup> items) {}

    @Schema(name = "RetailSavingsDay")
    record SavingsDay(
            UUID branchId,
            LocalDate businessDate,
            long totalSoldMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long amountMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long suggestedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Boolean overwritten,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long dailyProfitMinor) {}

    @Schema(name = "RetailSavingsReport")
    record SavingsReport(LocalDate from, LocalDate to, String currency, List<SavingsDay> items) {}

    @Schema(name = "RetailAdvanceParty")
    record AdvanceParty(
            UUID partyId,
            String partyName,
            long count,
            long principalMinor,
            long repaidMinor,
            long balanceMinor,
            LocalDate oldestAdvanceDate) {}

    @Schema(name = "RetailAdvanceReport")
    record AdvanceReport(String currency, long balanceMinor, List<AdvanceParty> items) {}
}
