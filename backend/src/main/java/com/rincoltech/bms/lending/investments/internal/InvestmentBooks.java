package com.rincoltech.bms.lending.investments.internal;

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
 * The investment posting rules of chapter 6 section 6.6.3 go through here to {@code post_entry}
 * (ADR-004): investor funds are the liability {@code investments_payable}, returns are the expense
 * {@code investment_return_expense} accrued into the liability {@code investment_returns_payable},
 * early withdrawal penalties are {@code investment_penalty_income}. Lines on the two controlled
 * liabilities carry the investment as subledger, so each investment's balances reconcile to the
 * ledger. Every entry is for the investment's branch, in the caller's transaction.
 */
@Component
class InvestmentBooks {

    static final String SOURCE_MODULE = "lending";
    static final String SOURCE_TYPE = "investment_transaction";
    static final String SUBLEDGER = "lending.investment";

    static final String PAYABLE = "investments_payable";
    static final String RETURNS_PAYABLE = "investment_returns_payable";
    static final String RETURN_EXPENSE = "investment_return_expense";
    static final String PENALTY_INCOME = "investment_penalty_income";

    /** ADR-026: each payment method posts to the seeded account with this system key until FR-GL-08's mapping exists. */
    static final Map<String, String> METHOD_ACCOUNTS = Map.of(
            "cash", "cash_on_hand",
            "bank", "bank",
            "mtn_momo", "mobile_money_mtn",
            "airtel_money", "mobile_money_airtel");

    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;

    InvestmentBooks(LedgerPosting ledger, LedgerAccounts accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /** One leg by system key; {@code investmentId} is set on lines of the controlled liabilities. */
    record Leg(String systemKey, boolean debit, long amountMinor, UUID investmentId) {

        static Leg debit(String systemKey, long amountMinor) {
            return new Leg(systemKey, true, amountMinor, null);
        }

        static Leg credit(String systemKey, long amountMinor) {
            return new Leg(systemKey, false, amountMinor, null);
        }

        Leg forInvestment(UUID id) {
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
        return ledger.reverse(new ReversalRequest(entryId, date, reference, "Investment reversal", idempotencyKey));
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
        return leg.investmentId() == null ? line : line.withSubledger(SUBLEDGER, leg.investmentId());
    }
}
