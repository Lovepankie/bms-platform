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

    record Posting(
            UUID branchId,
            LocalDate date,
            String reference,
            String memo,
            String sourceType,
            UUID sourceId,
            String idempotencyKey,
            List<Leg> legs) {

        public Posting {
            legs = List.copyOf(legs);
        }
    }

    record Leg(String systemKey, boolean debit, long amountMinor, String subledgerType, UUID subledgerId) {

        public static Leg debit(String systemKey, long amountMinor) {
            return new Leg(systemKey, true, amountMinor, null, null);
        }

        public static Leg credit(String systemKey, long amountMinor) {
            return new Leg(systemKey, false, amountMinor, null, null);
        }

        public Leg withSubledger(String type, UUID id) {
            return new Leg(systemKey, debit, amountMinor, type, id);
        }
    }
}
