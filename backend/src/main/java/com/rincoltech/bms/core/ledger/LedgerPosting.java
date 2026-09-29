package com.rincoltech.bms.core.ledger;

import com.rincoltech.bms.kernel.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code post_entry} (ADR-004). Posts one balanced journal entry for one branch, inside the
 * caller's transaction (it refuses to run without one), so the entry commits or rolls back with
 * the business event that caused it.
 *
 * <p>Refused before anything is written, with a 422 problem: fewer than two lines
 * ({@code invalid_journal_line}), a line that is not exactly one positive debit or one positive
 * credit ({@code invalid_journal_line}), debits not equal to credits in any currency
 * ({@code unbalanced_entry}), an unknown, inactive or header account
 * ({@code account_not_postable}), a line currency that differs from its account
 * ({@code currency_mismatch}), a closed period ({@code period_closed}). The database checks the
 * balance again at commit with a deferred constraint trigger, so raw SQL cannot bypass it.
 */
public interface LedgerPosting {

    PostedEntry post(EntryRequest request);

    record EntryRequest(
            UUID branchId,
            LocalDate entryDate,
            String reference,
            String memo,
            String sourceModule,
            String sourceType,
            UUID sourceId,
            String idempotencyKey,
            List<Line> lines) {

        public EntryRequest {
            lines = List.copyOf(lines);
        }
    }

    record Line(UUID accountId, long debit, long credit, String currency, String subledgerType, UUID subledgerId) {

        public static Line debit(UUID accountId, Money amount) {
            return new Line(accountId, amount.minor(), 0, amount.currency(), null, null);
        }

        public static Line credit(UUID accountId, Money amount) {
            return new Line(accountId, 0, amount.minor(), amount.currency(), null, null);
        }

        public Line withSubledger(String type, UUID id) {
            return new Line(accountId, debit, credit, currency, type, id);
        }
    }

    record PostedEntry(UUID entryId, String entryNo, UUID periodId) {}
}
