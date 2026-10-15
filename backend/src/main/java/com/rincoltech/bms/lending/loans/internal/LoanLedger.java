package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.ServicingApi.AllocationRow;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.LoanTransaction;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ScheduleRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * The loan account as the member sees it (FR-DOC-01, ADR-034), computed from the loan's own records
 * and nothing else: its transactions, their allocation rows and the contracted schedule. Pure: no
 * database, no clock.
 *
 * <p>What the member owes is the principal, the interest and fees on the schedule, and the penalties
 * charged so far, less what has been applied to them. The statement charges the whole schedule when
 * the loan is disbursed, because that is what the loan's outstanding balance counts from then on,
 * each penalty on the business date it was charged (R-PEN, ADR-034), and every
 * later line moves the balance by exactly what the servicing code moved it by: a repayment by the
 * sum of its own allocation rows, a reversal by the sum of the rows it wrote (which include the
 * re-allocation of later repayments), a write-off by the amount written off on the schedule items.
 * So the closing balance is the loan's total outstanding, which the tests assert.
 *
 * <p>Money that does not move the balance (fees deducted from the proceeds, an upfront fee, a
 * recovery after a write-off) is shown as a note line without a balance.
 */
final class LoanLedger {

    private LoanLedger() {}

    /** One statement line. A note has no amounts and no balance of its own. */
    record Entry(
            LocalDate date,
            String number,
            String description,
            long chargedMinor,
            long creditedMinor,
            long balanceMinor,
            boolean note) {}

    record Statement(List<Entry> entries, long closingMinor) {}

    /** The fees taken out of the proceeds at disbursement, by name, for the disbursement lines. */
    record Deduction(String name, long amountMinor) {}

    /**
     * One penalty charge (R-PEN) on schedule item {@code itemNo}: the business date it is charged for,
     * and when the nightly run recorded it, which places it among the transactions for a receipt.
     */
    record PenaltyCharge(int itemNo, LocalDate date, Instant recordedAt, long amountMinor) {}

    /** The loan as it stood right after one transaction. */
    record After(long outstandingMinor, long creditMinor, LocalDate nextDue, boolean writtenOff) {}

    /**
     * The statement as at a date. {@code outstandingNow} is the loan's recorded total outstanding: the
     * statement reconciles to it, and when something the ledger does not know of (a transaction type a
     * later increment adds) leaves a difference, an explicit adjustment line shows it rather than a
     * closing balance that disagrees with the loan.
     */
    static Statement statement(
            List<LoanTransaction> txns,
            List<ScheduleRow> items,
            List<PenaltyCharge> penalties,
            List<Deduction> deductions,
            LocalDate asAt,
            long outstandingNow,
            LongFunction<String> money) {
        long interest = items.stream().mapToLong(ScheduleRow::interestDueMinor).sum();
        long fees = items.stream().mapToLong(ScheduleRow::feesDueMinor).sum();
        long writtenOff = items.stream().mapToLong(ScheduleRow::writtenOffMinor).sum();
        Map<UUID, String> numbers = new LinkedHashMap<>();
        Map<UUID, LoanTransaction> byId = new LinkedHashMap<>();
        for (LoanTransaction t : txns) {
            byId.put(t.id(), t);
            numbers.put(t.id(), t.receiptNo());
        }
        List<LoanTransaction> printed = new ArrayList<>(txns);
        printed.sort(Comparator.comparing(LoanTransaction::valueDate)
                .thenComparing(LoanTransaction::createdAt)
                .thenComparingInt(LoanLedger::rank)
                .thenComparing(LoanTransaction::id));

        List<PenaltyCharge> unprinted = new ArrayList<>(penalties);
        unprinted.sort(Comparator.comparing(PenaltyCharge::date));
        List<Entry> entries = new ArrayList<>();
        long[] balance = {0};
        for (LoanTransaction t : printed) {
            if (!t.txnType().equals("disbursement")) {
                // The nightly run charges at the start of the business date, before that day's payments.
                penaltiesUpTo(entries, balance, unprinted, t.valueDate());
            }
            String number = t.receiptNo() == null ? "" : t.receiptNo();
            switch (t.txnType()) {
                case "disbursement" -> {
                    long deducted = deductions.stream()
                            .mapToLong(Deduction::amountMinor)
                            .sum();
                    charge(entries, balance, t.valueDate(), number, "Disbursement (gross)", t.amountMinor());
                    for (Deduction d : deductions) {
                        note(
                                entries,
                                balance,
                                t.valueDate(),
                                "",
                                "Less " + d.name() + " deducted from the proceeds: " + money.apply(d.amountMinor()));
                    }
                    note(
                            entries,
                            balance,
                            t.valueDate(),
                            "",
                            "Net paid to the member: " + money.apply(t.amountMinor() - deducted));
                    charge(entries, balance, t.valueDate(), "", "Interest charged (schedule)", interest);
                    charge(entries, balance, t.valueDate(), "", "Fees added (schedule)", fees);
                }
                case "repayment" -> {
                    long cash = rows(t, false);
                    long rebate = component(t, Servicing.REBATE);
                    long excess = component(t, Servicing.OVERPAYMENT);
                    credit(entries, balance, t.valueDate(), number, "Repayment", cash);
                    if (rebate != 0) {
                        credit(entries, balance, t.valueDate(), number, "Early settlement rebate", rebate);
                    }
                    if (excess > 0) {
                        note(
                                entries,
                                balance,
                                t.valueDate(),
                                "",
                                "Of the amount received, " + money.apply(excess)
                                        + " is held as credit for the member, not part of the balance");
                    }
                }
                case "recovery" ->
                    note(
                            entries,
                            balance,
                            t.valueDate(),
                            number,
                            "Recovery received after the write-off: " + money.apply(t.amountMinor())
                                    + ", not part of the balance");
                case "fee_upfront" ->
                    note(
                            entries,
                            balance,
                            t.valueDate(),
                            number,
                            "Fee paid before disbursement: " + money.apply(t.amountMinor())
                                    + ", not part of the balance");
                case "reversal" -> {
                    LoanTransaction reversed = byId.get(t.reversesTxnId());
                    String of = reversed == null || reversed.receiptNo() == null ? "a payment" : reversed.receiptNo();
                    if (reversed != null && reversed.txnType().equals("recovery")) {
                        note(
                                entries,
                                balance,
                                t.valueDate(),
                                "",
                                "Reversal of recovery " + of + ", not part of the balance");
                    } else {
                        long applied = rows(t, true);
                        // The rows are negative where money is taken back, so the balance goes up.
                        if (applied <= 0) {
                            charge(entries, balance, t.valueDate(), "", "Reversal of " + of, -applied);
                        } else {
                            credit(entries, balance, t.valueDate(), "", "Reversal of " + of, applied);
                        }
                    }
                }
                case "write_off" -> {
                    long amount = writtenOff > 0 ? writtenOff : t.amountMinor();
                    credit(entries, balance, t.valueDate(), "", "Written off", amount);
                    note(
                            entries,
                            balance,
                            t.valueDate(),
                            "",
                            "Principal " + money.apply(t.amountMinor()) + ", interest and fees "
                                    + money.apply(amount - t.amountMinor()));
                }
                default ->
                    note(
                            entries,
                            balance,
                            t.valueDate(),
                            number,
                            t.txnType().replace('_', ' ') + ": " + money.apply(t.amountMinor()));
            }
        }
        penaltiesUpTo(entries, balance, unprinted, LocalDate.MAX);
        long difference = outstandingNow - balance[0];
        if (difference > 0) {
            charge(entries, balance, asAt, "", "Other adjustments, see the loan's audit trail", difference);
        } else if (difference < 0) {
            credit(entries, balance, asAt, "", "Other adjustments, see the loan's audit trail", -difference);
        }
        return new Statement(List.copyOf(entries), balance[0]);
    }

    /**
     * The loan right after {@code txnId} was recorded: what a receipt shows under "Loan after this
     * payment". Only the transactions recorded up to and including that one, and the penalties recorded
     * before it, count, so it is the same whenever the receipt is first opened, and a later payment,
     * reversal or penalty never changes it.
     */
    static After after(List<LoanTransaction> txns, List<ScheduleRow> items, List<PenaltyCharge> penalties, UUID txnId) {
        List<LoanTransaction> recorded = new ArrayList<>(txns);
        recorded.sort(Comparator.comparing(LoanTransaction::createdAt)
                .thenComparingInt(LoanLedger::rank)
                .thenComparing(LoanTransaction::id));
        Map<Integer, Long> remaining = new LinkedHashMap<>();
        items.forEach(i -> remaining.put(i.no(), i.principalDueMinor() + i.interestDueMinor() + i.feesDueMinor()));
        Instant at = txns.stream()
                .filter(t -> t.id().equals(txnId))
                .findFirst()
                .orElseThrow()
                .createdAt();
        for (PenaltyCharge p : penalties) {
            if (!p.recordedAt().isAfter(at)) {
                remaining.merge(p.itemNo(), p.amountMinor(), Long::sum);
            }
        }
        long credit = 0;
        boolean writtenOff = false;
        for (LoanTransaction t : recorded) {
            for (AllocationRow a : t.allocations()) {
                if (a.component().equals(Servicing.OVERPAYMENT)) {
                    credit += a.amountMinor();
                } else if (a.itemNo() != null) {
                    remaining.merge(a.itemNo(), -a.amountMinor(), Long::sum);
                }
            }
            writtenOff |= t.txnType().equals("write_off");
            if (t.id().equals(txnId)) {
                break;
            }
        }
        long outstanding = writtenOff
                ? 0
                : remaining.values().stream().mapToLong(Long::longValue).sum();
        LocalDate next = null;
        if (!writtenOff) {
            for (ScheduleRow i : items) {
                if (remaining.get(i.no()) > 0 && (next == null || i.dueDate().isBefore(next))) {
                    next = i.dueDate();
                }
            }
        }
        return new After(outstanding, credit, next, writtenOff);
    }

    /** Prints, one line per date, the penalties charged on or before {@code until}, and removes them. */
    private static void penaltiesUpTo(
            List<Entry> entries, long[] balance, List<PenaltyCharge> unprinted, LocalDate until) {
        while (!unprinted.isEmpty() && !unprinted.getFirst().date().isAfter(until)) {
            LocalDate date = unprinted.getFirst().date();
            long sum = 0;
            while (!unprinted.isEmpty() && unprinted.getFirst().date().equals(date)) {
                sum = Math.addExact(sum, unprinted.removeFirst().amountMinor());
            }
            charge(entries, balance, date, "", "Penalties charged", sum);
        }
    }

    /** The sum of a transaction's own allocation rows that move the balance; with {@code rebate} the rebate rows too. */
    private static long rows(LoanTransaction t, boolean rebate) {
        long sum = 0;
        for (AllocationRow a : t.allocations()) {
            boolean moves = !a.component().equals(Servicing.OVERPAYMENT)
                    && (rebate || !a.component().equals(Servicing.REBATE));
            if (moves) {
                sum += a.amountMinor();
            }
        }
        return sum;
    }

    private static long component(LoanTransaction t, String component) {
        return t.allocations().stream()
                .filter(a -> a.component().equals(component))
                .mapToLong(AllocationRow::amountMinor)
                .sum();
    }

    /** A disbursement before its fee payment, and either before the repayments of the same instant. */
    private static int rank(LoanTransaction t) {
        return switch (t.txnType()) {
            case "disbursement" -> 0;
            case "fee_upfront" -> 1;
            default -> 2;
        };
    }

    private static void charge(
            List<Entry> out, long[] balance, LocalDate date, String number, String text, long amount) {
        if (amount == 0) {
            return;
        }
        balance[0] += amount;
        out.add(new Entry(date, number, text, amount, 0, balance[0], false));
    }

    private static void credit(
            List<Entry> out, long[] balance, LocalDate date, String number, String text, long amount) {
        balance[0] -= amount;
        out.add(new Entry(date, number, text, 0, amount, balance[0], false));
    }

    private static void note(List<Entry> out, long[] balance, LocalDate date, String number, String text) {
        out.add(new Entry(date, number, text, 0, 0, balance[0], true));
    }
}
