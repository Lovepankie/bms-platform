package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Retail's posting rules go through here to {@code post_entry} (ADR-020 decision 7, FR-RET-11):
 * accounts are named by {@code system_key} (chapter 6 section 6.11.1), amounts are in the tenant
 * currency, the source module is {@code retail}, and the entry is for one branch. Runs in the
 * caller's transaction.
 */
public interface RetailBooks {

    /**
     * Posts one balanced entry. Legs of zero are left out; when every leg is zero nothing is posted
     * and the result is empty.
     */
    Optional<PostedEntry> post(Posting posting);

    /** Posts the reversal of an entry (a void), in the original entry's branch. */
    PostedEntry reverse(UUID entryId, LocalDate date, String reference, String idempotencyKey);

    /**
     * Where a leg naming {@code systemKey} posts at this branch, as a {@code LedgerAccounts.byRef} reference (FR-GL-08,
     * ADR-105): the till ({@code cash_on_hand}) and the bank ({@code bank}) go to the account the tenant maps the
     * payment method {@code cash} or {@code bank} to (the branch's mapping, else the tenant's), and to the seeded account
     * while the method is unmapped or switched off; any other key is itself. The cash book reads the same account.
     */
    String accountRef(String systemKey, UUID branchId);

    record Posting(
            UUID branchId,
            LocalDate date,
            String reference,
            String memo,
            String sourceType,
            UUID sourceId,
            String idempotencyKey,
            List<Leg> legs,
            boolean closedBranchOk) {

        public Posting {
            legs = List.copyOf(legs);
        }

        /** A posting to an open branch, which is every posting but a transfer receipt at a branch that has since closed. */
        public Posting(
                UUID branchId,
                LocalDate date,
                String reference,
                String memo,
                String sourceType,
                UUID sourceId,
                String idempotencyKey,
                List<Leg> legs) {
            this(branchId, date, reference, memo, sourceType, sourceId, idempotencyKey, legs, false);
        }
    }

    /**
     * One side of an entry, naming its account by {@code systemKey} (resolved by {@link #accountRef} at the posting's
     * branch) or, for an account the tenant chose (an expense category's, ADR-022 decision 5), by {@code accountId}
     * with the key null.
     */
    record Leg(
            String systemKey, UUID accountId, boolean debit, long amountMinor, String subledgerType, UUID subledgerId) {

        public static Leg debit(String systemKey, long amountMinor) {
            return new Leg(systemKey, null, true, amountMinor, null, null);
        }

        public static Leg credit(String systemKey, long amountMinor) {
            return new Leg(systemKey, null, false, amountMinor, null, null);
        }

        /** A debit to a specific account of the tenant. */
        public static Leg debitAccount(UUID accountId, long amountMinor) {
            return new Leg(null, accountId, true, amountMinor, null, null);
        }

        public Leg withSubledger(String type, UUID id) {
            return new Leg(systemKey, accountId, debit, amountMinor, type, id);
        }
    }
}
