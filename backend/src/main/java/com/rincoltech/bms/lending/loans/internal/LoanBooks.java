package com.rincoltech.bms.lending.loans.internal;

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
 * Lending's posting rules (chapter 6 section 6.6.3) go through here to {@code post_entry}:
 * accounts are named by {@code system_key}, a payment method resolves to its account by the
 * default mapping of ADR-026, lines on system-controlled accounts carry the loan as subledger, and
 * every entry is for the loan's branch, in the caller's transaction.
 */
@Component
class LoanBooks {

    static final String SOURCE_MODULE = "lending";
    static final String SOURCE_TYPE = "loan_transaction";
    static final String SUBLEDGER = "lending.loan";

    static final String LOANS_RECEIVABLE = "loans_receivable";
    static final String INTEREST_INCOME = "loan_interest_income";
    static final String FEE_INCOME = "loan_fee_income";
    static final String PENALTY_INCOME = "loan_penalty_income";
    static final String OVERPAYMENTS = "member_overpayments";
    static final String WRITE_OFF_EXPENSE = "loan_write_off_expense";
    static final String BAD_DEBT_RECOVERED = "bad_debt_recovered";

    /**
     * ADR-026: until the payment method mapping of FR-GL-08 is built, each method posts to the
     * seeded account with this system key (chapter 6 section 6.6.2).
     */
    static final Map<String, String> METHOD_ACCOUNTS = Map.of(
            "cash", "cash_on_hand",
            "bank", "bank",
            "mtn_momo", "mobile_money_mtn",
            "airtel_money", "mobile_money_airtel");

    /** The account each allocation component credits on a repayment. */
    static final Map<String, String> COMPONENT_ACCOUNTS = Map.of(
            "principal", LOANS_RECEIVABLE,
            "interest", INTEREST_INCOME,
            "fee", FEE_INCOME,
            "penalty", PENALTY_INCOME,
            "overpayment", OVERPAYMENTS);

    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;

    LoanBooks(LedgerPosting ledger, LedgerAccounts accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /** One leg by system key; {@code loanId} is set on lines of system-controlled accounts. */
    record Leg(String systemKey, boolean debit, long amountMinor, UUID loanId) {

        static Leg debit(String systemKey, long amountMinor) {
            return new Leg(systemKey, true, amountMinor, null);
        }

        static Leg credit(String systemKey, long amountMinor) {
            return new Leg(systemKey, false, amountMinor, null);
        }

        Leg forLoan(UUID id) {
            return new Leg(systemKey, debit, amountMinor, id);
        }
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

    /** Posts one balanced entry; legs of zero are left out. */
    PostedEntry post(
            UUID branchId,
            LocalDate date,
            String reference,
            String memo,
            UUID txnId,
            String idempotencyKey,
            List<Leg> legs) {
        List<Line> lines = legs.stream()
                .filter(l -> l.amountMinor() != 0)
                .map(this::resolve)
                .toList();
        return ledger.post(new EntryRequest(
                branchId, date, reference, memo, SOURCE_MODULE, SOURCE_TYPE, txnId, idempotencyKey, lines));
    }

    /** The mirror of a posted entry (ADR-004: corrections are reversals). */
    PostedEntry reverse(UUID entryId, LocalDate date, String reference, String idempotencyKey) {
        return ledger.reverse(new ReversalRequest(entryId, date, reference, "Repayment reversal", idempotencyKey));
    }

    private Line resolve(Leg leg) {
        if (leg.amountMinor() < 0) {
            throw new IllegalArgumentException("a leg is never negative");
        }
        LedgerAccounts.Account account = accounts.bySystemKey(leg.systemKey())
                .orElseThrow(() -> ApiException.rule(
                        "lending_chart_missing",
                        "The tenant has no active account with system key " + leg.systemKey() + "."));
        Line line = new Line(
                account.id(),
                leg.debit() ? leg.amountMinor() : 0,
                leg.debit() ? 0 : leg.amountMinor(),
                account.currency(),
                null,
                null);
        return leg.loanId() == null ? line : line.withSubledger(SUBLEDGER, leg.loanId());
    }
}
