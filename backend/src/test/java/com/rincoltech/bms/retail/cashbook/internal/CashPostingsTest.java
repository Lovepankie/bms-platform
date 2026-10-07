package com.rincoltech.bms.retail.cashbook.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-022 decision 8 and chapter 6 section 6.11.5: the cash book posting table, rule by rule. */
class CashPostingsTest {

    static final UUID BRANCH = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    static final UUID ID = UUID.fromString("12345678-0000-4000-8000-000000000001");
    static final LocalDate DAY = LocalDate.of(2026, 1, 15);

    static void balanced(Posting p, long amount) {
        long debits =
                p.legs().stream().filter(Leg::debit).mapToLong(Leg::amountMinor).sum();
        long credits = p.legs().stream()
                .filter(l -> !l.debit())
                .mapToLong(Leg::amountMinor)
                .sum();
        assertThat(debits).isEqualTo(amount).isEqualTo(credits);
        assertThat(p.branchId()).isEqualTo(BRANCH);
        assertThat(p.date()).isEqualTo(DAY);
    }

    static String debit(Posting p) {
        return p.legs().stream().filter(Leg::debit).findFirst().orElseThrow().systemKey();
    }

    static String credit(Posting p) {
        return p.legs().stream()
                .filter(l -> !l.debit())
                .findFirst()
                .orElseThrow()
                .systemKey();
    }

    @Test
    void savingsMoveCashToTheReserveAndAreNotAnExpense() {
        Posting p = CashPostings.savings(BRANCH, DAY, ID, 5_000);
        balanced(p, 5_000);
        assertThat(debit(p)).isEqualTo("savings_reserve");
        assertThat(credit(p)).isEqualTo("cash_on_hand");
        assertThat(p.idempotencyKey()).isEqualTo("retail.savings:" + ID);
        assertThat(p.legs()).noneMatch(l -> "operating_expenses".equals(l.systemKey()));
    }

    @Test
    void bankingAndWithdrawalAreOppositeTransfersBetweenCashAndBank() {
        Posting banked = CashPostings.banking(BRANCH, DAY, ID, 7_000);
        Posting withdrawn = CashPostings.withdrawal(BRANCH, DAY, ID, 7_000);
        balanced(banked, 7_000);
        balanced(withdrawn, 7_000);
        assertThat(debit(banked)).isEqualTo("bank");
        assertThat(credit(banked)).isEqualTo("cash_on_hand");
        assertThat(debit(withdrawn)).isEqualTo("cash_on_hand");
        assertThat(credit(withdrawn)).isEqualTo("bank");
        assertThat(banked.idempotencyKey()).isEqualTo("retail.banking:" + ID);
        assertThat(withdrawn.idempotencyKey()).isEqualTo("retail.withdrawal:" + ID);
    }

    @Test
    void anExpenseDebitsItsCategoryAccountOrOperatingExpenses() {
        UUID account = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
        Posting mapped = CashPostings.expense(BRANCH, DAY, ID, 900, account);
        Posting unmapped = CashPostings.expense(BRANCH, DAY, ID, 900, null);
        balanced(mapped, 900);
        balanced(unmapped, 900);
        assertThat(mapped.legs().getFirst().accountId()).isEqualTo(account);
        assertThat(mapped.legs().getFirst().systemKey()).isNull();
        assertThat(debit(unmapped)).isEqualTo("operating_expenses");
        assertThat(credit(unmapped)).isEqualTo("cash_on_hand");
        assertThat(unmapped.idempotencyKey()).isEqualTo("retail.expense:" + ID);
    }

    @Test
    void anAdvanceAndItsRepaymentCarryTheAdvanceAsSubledger() {
        Posting out = CashPostings.advance(BRANCH, DAY, ID, 10_000);
        balanced(out, 10_000);
        assertThat(debit(out)).isEqualTo("owner_advances");
        assertThat(credit(out)).isEqualTo("cash_on_hand");
        assertThat(out.legs().getFirst().subledgerId()).isEqualTo(ID);

        UUID repayment = UUID.fromString("12345678-0000-4000-8000-000000000002");
        for (String method : new String[] {"cash", "mobile_money", "bank"}) {
            Posting back = CashPostings.repayment(BRANCH, DAY, repayment, ID, method, 4_000);
            balanced(back, 4_000);
            assertThat(credit(back)).isEqualTo("owner_advances");
            assertThat(back.legs().getLast().subledgerId()).isEqualTo(ID);
            assertThat(back.idempotencyKey()).isEqualTo("retail.advance_repayment:" + repayment);
        }
        assertThat(debit(CashPostings.repayment(BRANCH, DAY, repayment, ID, "cash", 1)))
                .isEqualTo("cash_on_hand");
        assertThat(debit(CashPostings.repayment(BRANCH, DAY, repayment, ID, "mobile_money", 1)))
                .isEqualTo("mobile_money");
        assertThat(debit(CashPostings.repayment(BRANCH, DAY, repayment, ID, "bank", 1)))
                .isEqualTo("bank");
    }

    @Test
    void aVoidKeyIsTheRecordKeyWithASuffix() {
        assertThat(CashPostings.voidKey(CashPostings.SAVINGS, ID)).isEqualTo("retail.savings:" + ID + ":void");
    }
}
