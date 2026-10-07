package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The posting rules of the cash book (ADR-022 decision 8; chapter 6 section 6.11.5). Every event is
 * one balanced entry in the record's own branch, dated the record's business date, with the
 * idempotency key {@code retail.<kind>:<id>}. A void posts the reversal under {@code <key>:void}.
 * Entries of amount zero are left out by the books (a savings amount of zero is a valid record).
 */
final class CashPostings {

    static final String SAVINGS = "retail.savings";
    static final String BANKING = "retail.banking";
    static final String WITHDRAWAL = "retail.withdrawal";
    static final String EXPENSE = "retail.expense";
    static final String ADVANCE = "retail.advance";
    static final String REPAYMENT = "retail.advance_repayment";
    static final String OPENING = "retail.cash_opening";
    static final String ADVANCE_SUBLEDGER = "retail.advance";

    private CashPostings() {}

    static String key(String kind, UUID id) {
        return kind + ":" + id;
    }

    static String voidKey(String kind, UUID id) {
        return key(kind, id) + ":void";
    }

    static String ref(String label, UUID id) {
        return label + " " + id.toString().substring(0, 8);
    }

    /** Debit the savings reserve, credit cash on hand: money moved, not spent, so profit is unchanged. */
    static Posting savings(UUID branch, LocalDate date, UUID id, long amountMinor) {
        return new Posting(
                branch,
                date,
                ref("Savings", id),
                "Daily savings set aside",
                SAVINGS,
                id,
                key(SAVINGS, id),
                List.of(Leg.debit("savings_reserve", amountMinor), Leg.credit("cash_on_hand", amountMinor)));
    }

    /** Debit bank, credit cash on hand. */
    static Posting banking(UUID branch, LocalDate date, UUID id, long amountMinor) {
        return new Posting(
                branch,
                date,
                ref("Banked", id),
                "Cash banked",
                BANKING,
                id,
                key(BANKING, id),
                List.of(Leg.debit("bank", amountMinor), Leg.credit("cash_on_hand", amountMinor)));
    }

    /** The reverse of banking: debit cash on hand, credit bank. */
    static Posting withdrawal(UUID branch, LocalDate date, UUID id, long amountMinor) {
        return new Posting(
                branch,
                date,
                ref("Withdrawal", id),
                "Cash withdrawn from the bank",
                WITHDRAWAL,
                id,
                key(WITHDRAWAL, id),
                List.of(Leg.debit("cash_on_hand", amountMinor), Leg.credit("bank", amountMinor)));
    }

    /** Debit the category's expense account (or operating expenses when none is mapped), credit cash on hand. */
    static Posting expense(UUID branch, LocalDate date, UUID id, long amountMinor, UUID categoryAccountId) {
        Leg debit = categoryAccountId == null
                ? Leg.debit("operating_expenses", amountMinor)
                : Leg.debitAccount(categoryAccountId, amountMinor);
        return new Posting(
                branch,
                date,
                ref("Expense", id),
                "Company expense",
                EXPENSE,
                id,
                key(EXPENSE, id),
                List.of(debit, Leg.credit("cash_on_hand", amountMinor)));
    }

    /** An advance paid out of the source branch's till: debit the receivable with the advance as subledger. */
    static Posting advance(UUID branch, LocalDate date, UUID id, long amountMinor) {
        return new Posting(
                branch,
                date,
                ref("Advance", id),
                "Advance to owner or related party",
                ADVANCE,
                id,
                key(ADVANCE, id),
                List.of(
                        Leg.debit("owner_advances", amountMinor).withSubledger(ADVANCE_SUBLEDGER, id),
                        Leg.credit("cash_on_hand", amountMinor)));
    }

    /**
     * A repayment: debit cash on hand, mobile money or bank by method in the branch that receives the
     * money, credit the receivable in the same branch with the advance as subledger.
     */
    static Posting repayment(UUID branch, LocalDate date, UUID id, UUID advanceId, String method, long amountMinor) {
        String account = switch (method) {
            case "cash" -> "cash_on_hand";
            case "mobile_money" -> "mobile_money";
            case "bank" -> "bank";
            default -> throw new IllegalArgumentException("unknown method " + method);
        };
        return new Posting(
                branch,
                date,
                ref("Repayment", id),
                "Repayment of an advance",
                REPAYMENT,
                id,
                key(REPAYMENT, id),
                List.of(
                        Leg.debit(account, amountMinor),
                        Leg.credit("owner_advances", amountMinor).withSubledger(ADVANCE_SUBLEDGER, advanceId)));
    }
}
