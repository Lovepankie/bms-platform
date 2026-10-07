package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.ledger.LedgerPosting.ReversalRequest;
import com.rincoltech.bms.kernel.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The savings posting rules of chapter 6 section 6.6.3 go through here to {@code post_entry}.
 * Member savings are a liability (2010 {@code member_savings}): a deposit credits it, a
 * withdrawal, a fee and a reversed deposit debit it, and every line on it carries the savings
 * account as subledger, so the account balances reconcile to the ledger line by line.
 *
 * <ul>
 *   <li>Deposit: Dr payment method, Cr {@code member_savings}.
 *   <li>Withdrawal: Dr {@code member_savings}, Cr payment method.
 *   <li>Withdrawal fee: Dr {@code member_savings}, Cr {@code savings_fee_income}.
 *   <li>Interest posting: Dr {@code savings_interest_expense}, Cr {@code member_savings}.
 *   <li>Reversal: the mirror of the original entry (ADR-004).
 * </ul>
 */
@Component
class SavingsBooks {

    static final String SOURCE_MODULE = "lending";
    static final String SOURCE_TYPE = "savings_transaction";
    static final String SUBLEDGER = "lending.savings_account";

    static final String MEMBER_SAVINGS = "member_savings";
    static final String FEE_INCOME = "savings_fee_income";
    static final String INTEREST_EXPENSE = "savings_interest_expense";

    /** ADR-026: until the payment method mapping of FR-GL-08 is built, each method posts to this account. */
    static final Map<String, String> METHOD_ACCOUNTS = Map.of(
            "cash", "cash_on_hand",
            "bank", "bank",
            "mtn_momo", "mobile_money_mtn",
            "airtel_money", "mobile_money_airtel");

    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;

    SavingsBooks(LedgerPosting ledger, LedgerAccounts accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /** FR-GL-08: refused with {@code payment_method_unmapped} before anything is written. */
    String methodAccount(String paymentMethodKey) {
        String key = METHOD_ACCOUNTS.get(paymentMethodKey);
        if (key == null || accounts.bySystemKey(key).isEmpty()) {
            throw ApiException.rule(
                    "payment_method_unmapped",
                    "The payment method " + paymentMethodKey + " has no active ledger account.");
        }
        return key;
    }

    /**
     * Posts one balanced two-line entry: {@code debitKey} and {@code creditKey} by system key; the
     * {@code member_savings} side carries the account as subledger.
     */
    PostedEntry post(
            UUID branchId,
            LocalDate date,
            String reference,
            String memo,
            UUID txnId,
            UUID accountId,
            String debitKey,
            String creditKey,
            long amountMinor) {
        return ledger.post(new EntryRequest(
                branchId,
                date,
                reference,
                memo,
                SOURCE_MODULE,
                SOURCE_TYPE,
                txnId,
                "lending.savings.txn:" + txnId,
                List.of(line(debitKey, true, amountMinor, accountId), line(creditKey, false, amountMinor, accountId))));
    }

    /** The mirror of a posted entry, dated {@code date}. */
    PostedEntry reverse(UUID entryId, LocalDate date, String reference, UUID reversalTxnId) {
        return ledger.reverse(new ReversalRequest(
                entryId, date, reference, "Savings reversal", "lending.savings.txn:" + reversalTxnId));
    }

    /** Whether an entry dated {@code date} would be accepted by its accounting period. */
    boolean periodOpen(LocalDate date) {
        return ledger.periodOpen(date);
    }

    private Line line(String systemKey, boolean debit, long amountMinor, UUID accountId) {
        LedgerAccounts.Account account = accounts.bySystemKey(systemKey)
                .orElseThrow(() -> ApiException.rule(
                        "lending_chart_missing",
                        "The tenant has no active account with system key " + systemKey + "."));
        Line line = new Line(
                account.id(), debit ? amountMinor : 0, debit ? 0 : amountMinor, account.currency(), null, null);
        return systemKey.equals(MEMBER_SAVINGS) ? line.withSubledger(SUBLEDGER, accountId) : line;
    }
}
