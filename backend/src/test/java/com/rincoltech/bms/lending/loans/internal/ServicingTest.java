package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.loans.internal.Servicing.Allocation;
import com.rincoltech.bms.lending.loans.internal.Servicing.Applied;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Payment;
import com.rincoltech.bms.lending.loans.internal.Servicing.Position;
import com.rincoltech.bms.lending.loans.internal.Servicing.Quote;
import com.rincoltech.bms.lending.loans.internal.Servicing.Replay;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden tests of R-ALLOC, R-PAYOFF, R-DPD and the FR-REP-05 re-allocation on fabricated schedules
 * built by the product calculator, every figure checked to the minor unit (chapter 3 section 3.4,
 * worked examples B, C and D).
 */
class ServicingTest {

    static final List<String> ORDER = Servicing.DEFAULT_ORDER;

    /** Worked example B: 1,200,000 at 1000 bp per month flat, 3 monthly items, disbursed 15 January 2026. */
    static List<Item> exampleB() {
        return items(
                new ScheduleCalculator.Terms("flat", 1000, "per_month", "month", 3, "instalments", "monthly"),
                1_200_000,
                LocalDate.of(2026, 1, 15));
    }

    /** Worked example C: 1,000,000 at 1000 bp per month declining, 3 monthly items, disbursed 31 January 2026. */
    static List<Item> exampleC() {
        return items(
                new ScheduleCalculator.Terms("declining", 1000, "per_month", "month", 3, "instalments", "monthly"),
                1_000_000,
                LocalDate.of(2026, 1, 31));
    }

    static List<Item> items(ScheduleCalculator.Terms t, long principal, LocalDate on) {
        return ScheduleCalculator.schedule(t, principal, 0, on).stream()
                .map(i -> new Item(
                        UUID.randomUUID(), i.no(), i.dueDate(), i.principalMinor(), i.interestMinor(), i.feeMinor(), 0))
                .toList();
    }

    static long sum(List<Allocation> rows) {
        return rows.stream().mapToLong(Allocation::amountMinor).sum();
    }

    /** FR-REP-02, worked example D: 300,000 settles interest 120,000 then principal 180,000 of item 1. */
    @Test
    void workedExampleD_partialRepaymentAllocatesInterestThenPrincipalOfTheOldestItem() {
        List<Item> items = exampleB();
        Applied a = Servicing.apply(items, 300_000, LocalDate.of(2026, 2, 15), "flat", false, ORDER);

        assertThat(a.payoff()).isFalse();
        assertThat(a.allocations())
                .containsExactly(
                        new Allocation(items.get(0).id, "interest", 120_000),
                        new Allocation(items.get(0).id, "principal", 180_000));
        assertThat(items.get(0).unpaid("principal")).isEqualTo(220_000);
        assertThat(items.get(1).paidTotal()).isZero();
        assertThat(items.get(2).paidTotal()).isZero();
        assertThat(sum(a.allocations())).isEqualTo(300_000);
    }

    /** R-ALLOC step 2: the product's order decides the components within an item. */
    @Test
    void theProductsAllocationOrderDecidesTheComponents() {
        List<Item> items = exampleB();
        Applied a = Servicing.apply(
                items,
                450_000,
                LocalDate.of(2026, 2, 15),
                "flat",
                false,
                List.of("principal", "interest", "fee", "penalty"));

        assertThat(a.allocations())
                .containsExactly(
                        new Allocation(items.get(0).id, "principal", 400_000),
                        new Allocation(items.get(0).id, "interest", 50_000));
    }

    /** R-ALLOC step 2: a later item is reached only once every earlier item is fully paid (prepayment). */
    @Test
    void prepaymentReachesTheNextItemOnlyAfterTheEarlierOneIsSettled() {
        List<Item> items = exampleB();
        Applied a = Servicing.apply(items, 600_000, LocalDate.of(2026, 1, 20), "flat", false, ORDER);

        assertThat(a.allocations())
                .containsExactly(
                        new Allocation(items.get(0).id, "interest", 120_000),
                        new Allocation(items.get(0).id, "principal", 400_000),
                        new Allocation(items.get(1).id, "interest", 80_000));
        assertThat(items.get(0).settled()).isTrue();
        assertThat(items.get(0).paidOn).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(items.get(1).status(LocalDate.of(2026, 1, 20))).isEqualTo("partially_paid");
    }

    /** FR-REP-06, flat without the rebate: every unpaid scheduled interest is due on early settlement. */
    @Test
    void flatPayoffChargesTheWholeContractedInterest() {
        List<Item> items = exampleB();
        Servicing.apply(items, 300_000, LocalDate.of(2026, 2, 15), "flat", false, ORDER);

        Quote q = Servicing.payoff(items, LocalDate.of(2026, 2, 20), "flat", false);

        assertThat(q.principalMinor()).isEqualTo(1_020_000);
        assertThat(q.interestMinor()).isEqualTo(240_000);
        assertThat(q.rebateMinor()).isZero();
        assertThat(q.totalMinor()).isEqualTo(1_260_000);
    }

    /** FR-REP-06, flat with the rebate: interest due to date plus the next item's; later interest rebated. */
    @Test
    void flatPayoffWithTheRebateChargesOnlyTheCurrentPeriodsInterest() {
        List<Item> items = exampleB();
        Servicing.apply(items, 300_000, LocalDate.of(2026, 2, 15), "flat", true, ORDER);

        Quote q = Servicing.payoff(items, LocalDate.of(2026, 2, 20), "flat", true);

        assertThat(q.principalMinor()).isEqualTo(1_020_000);
        assertThat(q.interestMinor()).isEqualTo(120_000);
        assertThat(q.rebateMinor()).isEqualTo(120_000);
        assertThat(q.rebates()).containsOnlyKeys(items.get(2).id);
        assertThat(q.totalMinor()).isEqualTo(1_140_000);
    }

    /**
     * FR-REP-06, declining (worked example C): on 1 March item 1 (due 28 February) is unpaid, item 2
     * is the current period, item 3's 36,556 is rebated.
     */
    @Test
    void decliningPayoffChargesInterestToTheCurrentPeriod() {
        List<Item> items = exampleC();
        assertThat(items).extracting(i -> i.interestDue).containsExactly(100_000L, 69_789L, 36_556L);
        assertThat(items)
                .extracting(i -> i.dueDate)
                .containsExactly(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));

        Quote q = Servicing.payoff(items, LocalDate.of(2026, 3, 1), "declining", false);

        assertThat(q.principalMinor()).isEqualTo(1_000_000);
        assertThat(q.interestMinor()).isEqualTo(169_789);
        assertThat(q.rebateMinor()).isEqualTo(36_556);
        assertThat(q.totalMinor()).isEqualTo(1_169_789);
        // A bullet or a loan quoted before its first due date: the next item's interest is due in full.
        assertThat(Servicing.payoff(exampleC(), LocalDate.of(2026, 2, 1), "declining", false)
                        .interestMinor())
                .isEqualTo(100_000);
    }

    /** R-ALLOC steps 1 and 4: a payoff settles everything, rebates, and holds the excess as an overpayment. */
    @Test
    void aPayoffSettlesEverythingRebatesAndHoldsTheExcess() {
        List<Item> items = exampleC();
        Applied a = Servicing.apply(items, 1_200_000, LocalDate.of(2026, 3, 1), "declining", false, ORDER);

        assertThat(a.payoff()).isTrue();
        assertThat(a.excessMinor()).isEqualTo(30_211);
        assertThat(a.allocations())
                .containsExactly(
                        new Allocation(items.get(0).id, "interest", 100_000),
                        new Allocation(items.get(0).id, "principal", 302_115),
                        new Allocation(items.get(1).id, "interest", 69_789),
                        new Allocation(items.get(1).id, "principal", 332_326),
                        new Allocation(items.get(2).id, "principal", 365_559),
                        new Allocation(items.get(2).id, "interest_rebate", 36_556),
                        new Allocation(null, "overpayment", 30_211));
        long cash = a.allocations().stream()
                .filter(r -> !r.component().equals("interest_rebate"))
                .mapToLong(Allocation::amountMinor)
                .sum();
        assertThat(cash).isEqualTo(1_200_000);
        assertThat(items).allMatch(Item::settled);
        assertThat(items.get(2).status(LocalDate.of(2026, 3, 1))).isEqualTo("paid");
        assertThat(Servicing.position(items, LocalDate.of(2026, 3, 1)).settled())
                .isTrue();
    }

    /** An exact payoff leaves no excess; a payment on a settled loan is all overpayment. */
    @Test
    void anExactPayoffLeavesNoCreditAndALaterPaymentIsAllCredit() {
        List<Item> items = exampleB();
        Applied exact = Servicing.apply(items, 1_560_000, LocalDate.of(2026, 1, 20), "flat", false, ORDER);
        assertThat(exact.payoff()).isTrue();
        assertThat(exact.excessMinor()).isZero();

        Applied after = Servicing.apply(items, 5_000, LocalDate.of(2026, 1, 21), "flat", false, ORDER);
        assertThat(after.payoff()).isFalse();
        assertThat(after.allocations()).containsExactly(new Allocation(null, "overpayment", 5_000));
    }

    /** R-DPD: DPD from the oldest overdue item; arrears are unpaid principal, interest and fee on overdue items. */
    @Test
    void daysPastDueAndArrearsFollowTheOldestOverdueItem() {
        List<Item> items = exampleB();
        Servicing.apply(items, 300_000, LocalDate.of(2026, 2, 15), "flat", false, ORDER);

        Position p = Servicing.position(items, LocalDate.of(2026, 3, 20));

        assertThat(p.daysPastDue()).isEqualTo(33);
        assertThat(p.arrearsMinor()).isEqualTo(220_000 + 520_000);
        assertThat(p.principalOutstandingMinor()).isEqualTo(1_020_000);
        assertThat(p.interestOutstandingMinor()).isEqualTo(240_000);
        assertThat(p.nextDueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(items)
                .extracting(i -> i.status(LocalDate.of(2026, 3, 20)))
                .containsExactly("overdue", "overdue", "pending");
        assertThat(items.get(2).status(LocalDate.of(2026, 4, 15))).isEqualTo("due");
        assertThat(Servicing.position(items, LocalDate.of(2026, 2, 15)).daysPastDue())
                .as("due today is not overdue")
                .isZero();
    }

    /**
     * FR-REP-05: reversing the first of two repayments re-allocates the second as if the first had
     * never been there; the differences per repayment are exactly the rows a reversal writes.
     */
    @Test
    void reversingAnEarlierRepaymentReallocatesTheLaterOne() {
        List<Item> items = exampleB();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Applied a = Servicing.apply(items, 300_000, LocalDate.of(2026, 2, 15), "flat", false, ORDER);
        Applied b = Servicing.apply(items, 520_000, LocalDate.of(2026, 3, 15), "flat", false, ORDER);
        // Before: the second paid item 1's remaining 220,000 principal and 300,000 of item 2.
        assertThat(b.allocations())
                .containsExactly(
                        new Allocation(items.get(0).id, "principal", 220_000),
                        new Allocation(items.get(1).id, "interest", 120_000),
                        new Allocation(items.get(1).id, "principal", 180_000));

        Replay r = Servicing.replay(
                items, List.of(new Payment(second, 520_000, LocalDate.of(2026, 3, 15))), "flat", false, ORDER);

        assertThat(r.allocations().get(second))
                .containsExactly(
                        new Allocation(items.get(0).id, "interest", 120_000),
                        new Allocation(items.get(0).id, "principal", 400_000));
        assertThat(Servicing.difference(a.allocations(), List.of()))
                .containsExactly(
                        new Allocation(items.get(0).id, "interest", -120_000),
                        new Allocation(items.get(0).id, "principal", -180_000));
        List<Allocation> moved =
                Servicing.difference(b.allocations(), r.allocations().get(second));
        assertThat(moved)
                .containsExactlyInAnyOrder(
                        new Allocation(items.get(0).id, "principal", 180_000),
                        new Allocation(items.get(1).id, "interest", -120_000),
                        new Allocation(items.get(1).id, "principal", -180_000),
                        new Allocation(items.get(0).id, "interest", 120_000));
        assertThat(sum(moved)).as("a re-allocated repayment keeps its amount").isZero();
        assertThat(r.items().get(0).settled()).isTrue();
        assertThat(r.items().get(1).paidTotal()).isZero();
        // The replay works on copies: the live items are untouched until the caller saves them.
        assertThat(items.get(1).paidTotal()).isEqualTo(300_000);
    }

    /** R-ROUND carried through: a schedule with a residue on the last item still sums to the minor unit. */
    @Test
    void residuesOnTheLastItemAreSettledToTheMinorUnit() {
        List<Item> items = items(
                new ScheduleCalculator.Terms("flat", 1000, "per_month", "month", 3, "instalments", "monthly"),
                1_000_001,
                LocalDate.of(2026, 1, 15));
        assertThat(items).extracting(i -> i.principalDue).containsExactly(333_333L, 333_333L, 333_335L);
        assertThat(items).extracting(i -> i.interestDue).containsExactly(100_000L, 100_000L, 100_000L);

        Quote q = Servicing.payoff(items, LocalDate.of(2026, 1, 16), "flat", false);
        assertThat(q.totalMinor()).isEqualTo(1_300_001);
        Applied a = Servicing.apply(items, q.totalMinor(), LocalDate.of(2026, 1, 16), "flat", false, ORDER);
        assertThat(sum(a.allocations())).isEqualTo(1_300_001);
        assertThat(items).allMatch(Item::settled);
    }
}
