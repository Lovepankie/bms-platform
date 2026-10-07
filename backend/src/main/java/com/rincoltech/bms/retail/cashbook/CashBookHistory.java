package com.rincoltech.bms.retail.cashbook;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Imported cash book history (ADR-022 decision 18, FR-RET-30; ADR-020 decision 9), in the caller's
 * transaction. A historical row is flagged {@code historical}, is recorded by
 * {@link com.rincoltech.bms.retail.stock.RetailHistory#IMPORT_ACTOR}, carries the source's instant
 * as {@code created_at} and {@code occurred_at}, is never voided and posts no journal. The one
 * journal of the import is {@link #postOpening}, one per branch.
 */
public interface CashBookHistory {

    /** The expense category with this name ignoring case, created (as written) when there is none. */
    Ensured ensureCategory(String name);

    /**
     * The category and the item under it, each matched by name ignoring case and created as written
     * when missing. An existing item is never changed: {@code requiresExplanation} is only the value
     * a new item takes, and the result carries the stored one.
     */
    ExpenseItem ensureExpenseItem(String category, String item, boolean requiresExplanation);

    /** The party of this kind and name ignoring case, created with the contact when there is none. */
    Ensured ensureParty(String kind, String name, String contact);

    /** The first party with this name ignoring case, of one of the kinds (any kind when empty). */
    Optional<Party> findParty(String name, Collection<String> kinds);

    /**
     * A savings record, with no suggestion and never overwritten.
     *
     * @return the id, or empty when the branch already has an active savings record for the date
     *     (never merged)
     */
    Optional<UUID> importSavings(UUID branchId, LocalDate date, long amountMinor, long totalSoldMinor, Instant at);

    /** @return the expense's id */
    UUID importExpense(Expense expense);

    /**
     * Cash banked. {@code expected_minor} is computed here from the history of the branch and day
     * with the formula of the cash book's read model (expected to bank), so the savings, expenses,
     * advances and repayments of that day must be imported first.
     */
    Banked importBanking(UUID branchId, LocalDate date, long amountMinor, Instant bankedAt);

    /** @return the withdrawal's id */
    UUID importWithdrawal(UUID branchId, LocalDate date, long amountMinor, Instant at);

    /**
     * An advance, which takes the next value of the live advance number sequence; the caller imports
     * advances in business date then source id order.
     */
    Advanced importAdvance(Advance advance);

    /**
     * A repayment into the advance's branch. Raises {@code repaid_minor} by the amount in the same
     * transaction.
     *
     * @return the repayment's id, or empty when the amount is above the remaining principal
     */
    Optional<UUID> importRepayment(UUID advanceId, long amountMinor, String method, LocalDate paidOn, Instant at);

    /**
     * The opening entry of one branch dated {@code day} (the day before the first live day): debit
     * cash on hand, bank and savings reserve as given, one owner advances line per outstanding
     * historical advance of the branch (the advance as subledger), credit opening balance equity.
     * Empty when every amount is zero and no advance is outstanding.
     */
    Optional<OpeningPosted> postOpening(UUID branchId, LocalDate day, long cashMinor, long bankMinor, long savingsMinor);

    record Ensured(UUID id, boolean created) {}

    record Party(UUID id, String name, String kind) {}

    record ExpenseItem(
            UUID categoryId,
            UUID itemId,
            String categoryName,
            String itemName,
            boolean requiresExplanation,
            boolean categoryCreated,
            boolean itemCreated) {}

    record Expense(
            UUID branchId,
            LocalDate date,
            ExpenseItem item,
            UUID partyId,
            long amountMinor,
            String explanation,
            Instant at) {}

    record Banked(UUID id, long expectedMinor) {}

    record Advance(
            UUID branchId,
            LocalDate date,
            UUID partyId,
            UUID takenByPartyId,
            long principalMinor,
            String purpose,
            String note,
            Instant at) {}

    record Advanced(UUID id, String advanceNo) {}

    record OpeningPosted(
            UUID entryId,
            String entryNo,
            long cashMinor,
            long bankMinor,
            long savingsMinor,
            int advances,
            long advancesMinor) {

        public long totalMinor() {
            return cashMinor + bankMinor + savingsMinor + advancesMinor;
        }
    }
}
