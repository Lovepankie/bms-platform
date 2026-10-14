package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.rincoltech.bms.lending.loans.internal.LoanLedger.After;
import com.rincoltech.bms.lending.loans.internal.LoanLedger.Deduction;
import com.rincoltech.bms.lending.loans.internal.LoanLedger.Entry;
import com.rincoltech.bms.lending.loans.internal.LoanLedger.PenaltyEvent;
import com.rincoltech.bms.lending.loans.internal.LoanLedger.Statement;
import com.rincoltech.bms.lending.loans.internal.Servicing.Allocation;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Payment;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.AllocationRow;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.LoanTransaction;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ScheduleRow;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The loan account of the statement and the "loan after this payment" of a receipt (ADR-034),
 * driven by the real servicing rules: the allocation rows come from {@link Servicing} exactly as
 * {@code LoanServicer} writes them, so the ledger is checked against the position the servicing code
 * itself reaches. A loan with a fee added to it, a fee deducted from the proceeds and one paid
 * upfront, partial payments, a reversal that re-allocates a later payment, a write-off and a recovery.
 */
class LoanLedgerTest {

    static final LocalDate D0 = LocalDate.of(2026, 1, 15);
    static final ScheduleCalculator.Terms TERMS =
            new ScheduleCalculator.Terms("flat", 1000, "per_month", "month", 3, "instalments", "monthly");

    /** The book being built: the contracted items, the items as paid, and the transactions in recording order. */
    static final class Book {
        final List<Item> contracted;
        final List<Item> paid;
        final List<LoanTransaction> txns = new ArrayList<>();
        final List<Payment> active = new ArrayList<>();
        final Map<UUID, List<Allocation>> rowsByRepayment = new LinkedHashMap<>();
        final Map<UUID, UUID> reversedBy = new LinkedHashMap<>();
        int clock;
        long creditHeld;
        int receipts;

        Book() {
            contracted = ScheduleCalculator.schedule(TERMS, 1_200_000, 30_000, D0).stream()
                    .map(i -> new Item(
                            UUID.randomUUID(),
                            i.no(),
                            i.dueDate(),
                            i.principalMinor(),
                            i.interestMinor(),
                            i.feeMinor(),
                            0))
                    .toList();
            paid = new ArrayList<>(contracted.stream().map(Item::fresh).toList());
        }

        Instant next() {
            return Instant.parse("2026-01-15T08:00:00Z").plusSeconds(60L * clock++);
        }

        List<AllocationRow> rows(List<Allocation> rows) {
            Map<UUID, Integer> numbers = new LinkedHashMap<>();
            contracted.forEach(i -> numbers.put(i.id, i.no));
            return rows.stream()
                    .map(a -> new AllocationRow(
                            null, a.itemId() == null ? null : numbers.get(a.itemId()), a.component(), a.amountMinor()))
                    .toList();
        }

        LoanTransaction add(
                String type,
                long amount,
                LocalDate on,
                String receipt,
                UUID reverses,
                UUID id,
                List<AllocationRow> rows) {
            LoanTransaction t = new LoanTransaction(
                    id, type, amount, "UGX", on, "cash", null, receipt, null, reverses, null, null, null, null, next(),
                    rows);
            txns.add(t);
            return t;
        }

        void disburse() {
            add("disbursement", 1_200_000, D0, "VC-HQ-000001", null, UUID.randomUUID(), List.of());
            add("fee_upfront", 5_000, D0, "RC-HQ-000001", null, UUID.randomUUID(), List.of());
        }

        UUID repay(long amount, LocalDate on) {
            UUID id = UUID.randomUUID();
            Servicing.Applied applied = Servicing.apply(paid, amount, on, "flat", false, Servicing.DEFAULT_ORDER);
            rowsByRepayment.put(id, applied.allocations());
            active.add(new Payment(id, amount, on));
            creditHeld += applied.excessMinor();
            add(
                    "repayment",
                    amount,
                    on,
                    "RC-HQ-" + String.format("%06d", 2 + receipts++),
                    null,
                    id,
                    rows(applied.allocations()));
            return id;
        }

        /** FR-REP-05 as {@code LoanServicer.reverse} does it: the reversed rows negated, the others' differences. */
        void reverse(UUID repayment, LocalDate on) {
            List<Payment> survivors =
                    active.stream().filter(p -> !p.txnId().equals(repayment)).toList();
            Servicing.Replay replay = Servicing.replay(contracted, survivors, "flat", false, Servicing.DEFAULT_ORDER);
            List<AllocationRow> rows = new ArrayList<>();
            rows.addAll(rows(Servicing.difference(rowsByRepayment.get(repayment), List.of())));
            for (Payment s : survivors) {
                rows.addAll(rows(Servicing.difference(
                        rowsByRepayment.get(s.txnId()), replay.allocations().get(s.txnId()))));
                rowsByRepayment.put(s.txnId(), replay.allocations().get(s.txnId()));
            }
            UUID reversal = UUID.randomUUID();
            long amount = active.stream()
                    .filter(p -> p.txnId().equals(repayment))
                    .findFirst()
                    .orElseThrow()
                    .amountMinor();
            add("reversal", amount, on, null, repayment, reversal, rows);
            active.removeIf(p -> p.txnId().equals(repayment));
            reversedBy.put(repayment, reversal);
            paid.clear();
            paid.addAll(replay.items());
        }

        /** FR-LCL-02: the principal outstanding is the transaction's amount; every unpaid item is written off. */
        void writeOff(LocalDate on) {
            long principal = Servicing.position(paid, on).principalOutstandingMinor();
            add("write_off", principal, on, null, null, UUID.randomUUID(), List.of());
        }

        void recover(long amount, LocalDate on) {
            add(
                    "recovery",
                    amount,
                    on,
                    "RC-HQ-" + String.format("%06d", 2 + receipts++),
                    null,
                    UUID.randomUUID(),
                    List.of());
        }

        /** The transactions as the API returns them: each repayment knows what reversed it. */
        List<LoanTransaction> transactions() {
            return txns.stream()
                    .map(t -> new LoanTransaction(
                            t.id(),
                            t.txnType(),
                            t.amountMinor(),
                            t.currency(),
                            t.valueDate(),
                            t.paymentMethodKey(),
                            t.externalReference(),
                            t.receiptNo(),
                            t.reason(),
                            t.reversesTxnId(),
                            reversedBy.get(t.id()),
                            t.journalEntryId(),
                            t.approvalRequestId(),
                            t.recordedBy(),
                            t.createdAt(),
                            t.allocations()))
                    .toList();
        }

        long outstanding() {
            return Servicing.position(paid, LocalDate.of(2026, 12, 31)).principalOutstandingMinor()
                    + Servicing.position(paid, LocalDate.of(2026, 12, 31)).interestOutstandingMinor()
                    + Servicing.position(paid, LocalDate.of(2026, 12, 31)).feesOutstandingMinor();
        }

        /** The schedule rows of the API: contracted amounts, and what was written off on each item. */
        List<ScheduleRow> scheduleRows(boolean writtenOff) {
            List<ScheduleRow> rows = new ArrayList<>();
            for (int n = 0; n < contracted.size(); n++) {
                Item c = contracted.get(n);
                Item p = paid.get(n);
                long off = writtenOff ? p.unpaidTotal() : 0;
                rows.add(new ScheduleRow(
                        c.no,
                        c.dueDate,
                        c.principalDue,
                        c.interestDue,
                        c.feesDue,
                        0,
                        c.principalDue + c.interestDue + c.feesDue,
                        p.principalPaid,
                        p.interestPaid,
                        p.feesPaid,
                        0,
                        p.paidTotal(),
                        p.waivedTotal(),
                        off,
                        0,
                        "pending",
                        null));
            }
            return rows;
        }
    }

    static String money(long minor) {
        return String.format("%,d", minor);
    }

    static Statement statement(Book b, boolean writtenOff, long outstandingNow) {
        return statement(b, writtenOff, outstandingNow, List.of());
    }

    static Statement statement(Book b, boolean writtenOff, long outstandingNow, List<PenaltyEvent> penalties) {
        return LoanLedger.statement(
                b.transactions(),
                b.scheduleRows(writtenOff),
                penalties,
                List.of(new Deduction("Processing fee", 24_000)),
                LocalDate.of(2026, 12, 31),
                outstandingNow,
                LoanLedgerTest::money);
    }

    /** Every printed balance follows from the one before it and the line's own amounts. */
    static void assertRunningBalance(Statement st) {
        long running = 0;
        for (Entry e : st.entries()) {
            running += e.chargedMinor() - e.creditedMinor();
            assertThat(e.balanceMinor()).as(e.description()).isEqualTo(running);
        }
        assertThat(st.closingMinor()).isEqualTo(running);
        assertThat(st.entries().stream().mapToLong(Entry::chargedMinor).sum()
                        - st.entries().stream().mapToLong(Entry::creditedMinor).sum())
                .as("sum of the lines is the closing balance")
                .isEqualTo(st.closingMinor());
    }

    @Test
    void theClosingBalanceIsTheLoansOutstandingThroughPaymentsAReversalAndAWriteOff() {
        Book b = new Book();
        b.disburse();
        UUID p1 = b.repay(300_000, LocalDate.of(2026, 2, 15));
        b.repay(200_000, LocalDate.of(2026, 2, 20));
        b.reverse(p1, LocalDate.of(2026, 3, 1));
        b.repay(150_000, LocalDate.of(2026, 3, 2));

        // 1,200,000 principal + 360,000 flat interest + 30,000 fee added to the loan, less 200,000 and 150,000 paid.
        assertThat(b.outstanding()).isEqualTo(1_240_000);
        Statement open = statement(b, false, b.outstanding());
        assertThat(open.closingMinor()).isEqualTo(b.outstanding());
        assertRunningBalance(open);
        assertThat(open.entries()).noneMatch(e -> e.description().startsWith("Other adjustments"));

        // The disbursement shows the gross, the fee deducted and the net the member received.
        assertThat(open.entries().get(0).description()).isEqualTo("Disbursement (gross)");
        assertThat(open.entries().get(0).chargedMinor()).isEqualTo(1_200_000);
        assertThat(open.entries())
                .anyMatch(e ->
                        e.note() && e.description().equals("Less Processing fee deducted from the proceeds: 24,000"))
                .anyMatch(e -> e.note() && e.description().equals("Net paid to the member: 1,176,000"));
        // Interest and the added fee are charged; the reversal puts the payment back on the account.
        assertThat(open.entries())
                .anyMatch(e -> e.description().equals("Interest charged (schedule)") && e.chargedMinor() == 360_000)
                .anyMatch(e -> e.description().equals("Fees added (schedule)") && e.chargedMinor() == 30_000)
                .anyMatch(e -> e.description().startsWith("Reversal of RC-HQ-000002") && e.chargedMinor() == 300_000);
        // The upfront fee is money in but not on the loan account.
        assertThat(open.entries())
                .anyMatch(e -> e.note() && e.description().startsWith("Fee paid before disbursement"));

        // Write-off: everything unpaid is written off, so the account closes at nothing; a recovery is a note.
        long writtenOff = b.outstanding();
        b.writeOff(LocalDate.of(2026, 6, 1));
        b.recover(50_000, LocalDate.of(2026, 6, 10));
        Statement closed = statement(b, true, 0);
        assertRunningBalance(closed);
        assertThat(closed.closingMinor()).isZero();
        assertThat(closed.entries())
                .anyMatch(e -> e.description().equals("Written off") && e.creditedMinor() == writtenOff)
                .anyMatch(e -> e.note() && e.description().startsWith("Recovery received after the write-off: 50,000"));
    }

    @Test
    void aPayoffShowsTheRebateAndTheCreditHeld() {
        Book b = new Book();
        b.disburse();
        // Pays everything and 40,000 more: the excess is credit for the member, not a lower balance.
        b.repay(1_590_000 + 40_000, LocalDate.of(2026, 2, 15));
        assertThat(b.outstanding()).isZero();
        Statement st = statement(b, false, 0);
        assertRunningBalance(st);
        assertThat(st.closingMinor()).isZero();
        assertThat(st.entries())
                .anyMatch(e -> e.description().equals("Repayment") && e.creditedMinor() == 1_590_000)
                .anyMatch(e -> e.note() && e.description().contains("40,000 is held as credit"));
        assertThat(b.creditHeld).isEqualTo(40_000);
    }

    @Test
    void aDifferenceTheLedgerCannotExplainShowsAsAnAdjustmentNotAsAWrongClosingBalance() {
        Book b = new Book();
        b.disburse();
        Statement st = statement(b, false, b.outstanding() - 1_000);
        assertThat(st.closingMinor()).isEqualTo(b.outstanding() - 1_000);
        assertThat(st.entries())
                .anyMatch(e -> e.description().startsWith("Other adjustments") && e.creditedMinor() == 1_000);
        assertRunningBalance(st);
    }

    /** The receipt of a payment shows the loan right after it, however late it is first opened. */
    @Test
    void theLoanAfterAPaymentIgnoresEverythingRecordedLater() {
        Book b = new Book();
        b.disburse();
        UUID p1 = b.repay(300_000, LocalDate.of(2026, 2, 15));
        List<LoanTransaction> justAfterP1 = b.transactions();
        List<ScheduleRow> itemsThen = b.scheduleRows(false);
        After then = LoanLedger.after(justAfterP1, itemsThen, List.of(), p1);
        assertThat(then.outstandingMinor()).isEqualTo(1_590_000 - 300_000);
        assertThat(then.nextDue()).isEqualTo(b.contracted.get(0).dueDate);
        assertThat(then.creditMinor()).isZero();

        // A month later: more payments, one of them clearing the first instalment, the first payment
        // itself reversed, and the loan written off.
        b.repay(500_000, LocalDate.of(2026, 3, 1));
        b.reverse(p1, LocalDate.of(2026, 3, 5));
        List<LoanTransaction> later = b.transactions();
        List<ScheduleRow> itemsLater = b.scheduleRows(false);
        assertThat(LoanLedger.after(later, itemsLater, List.of(), p1))
                .as("the same after the later payment, the reversal and a changed schedule")
                .isEqualTo(then);
        assertThat(LoanLedger.after(later, itemsLater, List.of(), later.get(3).id())
                        .outstandingMinor())
                .as("the second payment, recorded after the first")
                .isEqualTo(1_590_000 - 300_000 - 500_000);
    }

    @Test
    void theLoanAfterAnOverpaymentHoldsTheCreditAndHasNoNextDueDate() {
        Book b = new Book();
        b.disburse();
        UUID payoff = b.repay(1_590_000 + 25_000, LocalDate.of(2026, 2, 15));
        After a = LoanLedger.after(b.transactions(), b.scheduleRows(false), List.of(), payoff);
        assertThat(a.outstandingMinor()).isZero();
        assertThat(a.creditMinor()).isEqualTo(25_000);
        assertThat(a.nextDue()).isNull();
    }

    /**
     * R-PEN with ADR-034: a penalty is on the statement on the business date it was charged, never at
     * disbursement, and a receipt counts only the penalties recorded before its payment.
     */
    @Test
    void penaltiesArePlacedOnTheDateTheyWereCharged() {
        Book b = new Book();
        b.disburse();
        LocalDate due = b.contracted.get(0).dueDate;
        List<PenaltyEvent> penalties = new ArrayList<>();
        penalties.add(new PenaltyEvent(1, due.plusDays(1), b.next(), 2_000, false));
        penalties.add(new PenaltyEvent(1, due.plusDays(2), b.next(), 2_000, false));
        penalties.add(new PenaltyEvent(2, due.plusDays(2), b.next(), 1_000, false));
        UUID p1 = b.repay(300_000, due.plusDays(2));
        penalties.add(new PenaltyEvent(1, due.plusDays(3), b.next(), 2_000, false));

        long outstanding = b.outstanding() + 7_000;
        Statement st = statement(b, false, outstanding, penalties);
        assertRunningBalance(st);
        assertThat(st.closingMinor()).isEqualTo(outstanding);
        assertThat(st.entries()).noneMatch(e -> e.description().startsWith("Other adjustments"));
        List<Entry> charged = st.entries().stream()
                .filter(e -> e.description().equals("Penalties charged"))
                .toList();
        assertThat(charged)
                .extracting(Entry::date, Entry::chargedMinor)
                .containsExactly(
                        tuple(due.plusDays(1), 2_000L), tuple(due.plusDays(2), 3_000L), tuple(due.plusDays(3), 2_000L));
        // The charges of a date come before that date's payment: the nightly run charges first.
        List<String> order = st.entries().stream()
                .filter(e -> !e.note())
                .map(e -> e.date() + " " + e.description())
                .toList();
        assertThat(order.indexOf(due.plusDays(2) + " Penalties charged"))
                .isLessThan(order.indexOf(due.plusDays(2) + " Repayment"));

        assertThat(LoanLedger.after(b.transactions(), b.scheduleRows(false), penalties, p1)
                        .outstandingMinor())
                .as("the three penalties recorded before the payment count, the later one does not")
                .isEqualTo(1_590_000 + 5_000 - 300_000);
    }

    /** FR-ARR-04: a waiver is a credit on its own date, and a receipt counts it only when recorded before it. */
    @Test
    void aWaiverIsCreditedOnItsDateAndCountsOnlyForLaterReceipts() {
        Book b = new Book();
        b.disburse();
        LocalDate due = b.contracted.get(0).dueDate;
        List<PenaltyEvent> penalties = new ArrayList<>();
        penalties.add(new PenaltyEvent(1, due.plusDays(1), b.next(), 2_000, false));
        penalties.add(new PenaltyEvent(1, due.plusDays(2), b.next(), 2_000, false));
        UUID p1 = b.repay(100_000, due.plusDays(3));
        penalties.add(new PenaltyEvent(1, due.plusDays(3), b.next(), 1_500, true));
        UUID p2 = b.repay(100_000, due.plusDays(3));

        long outstanding = b.outstanding() + 4_000 - 1_500;
        Statement st = statement(b, false, outstanding, penalties);
        assertRunningBalance(st);
        assertThat(st.closingMinor()).isEqualTo(outstanding);
        List<String> order = st.entries().stream()
                .filter(e -> !e.note())
                .map(Entry::description)
                .toList();
        assertThat(order.subList(order.size() - 3, order.size()))
                .as("the waiver sits between the two payments of its day, by when it was recorded")
                .containsExactly("Repayment", "Penalties waived", "Repayment");
        assertThat(st.entries())
                .anyMatch(e -> e.description().equals("Penalties waived") && e.creditedMinor() == 1_500);

        assertThat(LoanLedger.after(b.transactions(), b.scheduleRows(false), penalties, p1)
                        .outstandingMinor())
                .isEqualTo(1_590_000 + 4_000 - 100_000);
        assertThat(LoanLedger.after(b.transactions(), b.scheduleRows(false), penalties, p2)
                        .outstandingMinor())
                .isEqualTo(1_590_000 + 4_000 - 1_500 - 200_000);
    }
}
