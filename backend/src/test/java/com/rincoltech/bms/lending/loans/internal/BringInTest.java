package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.loans.internal.Servicing.Allocation;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FR-EXL-02 golden tests on worked example B (1,200,000 at 1000 bp a month flat over three months, disbursed 15 January
 * 2026, so each item is 400,000 principal and 120,000 interest): the register's repayments applied in date order by the
 * allocation order, and the position on a later business date. Every figure to the minor unit.
 */
class BringInTest {

    static final LocalDate DISBURSED = LocalDate.of(2026, 1, 15);
    static final LocalDate TODAY = LocalDate.of(2026, 5, 1);

    static List<Item> exampleB() {
        return BringIn.items(ScheduleCalculator.schedule(
                new ScheduleCalculator.Terms("flat", 1000, "per_month", "month", 3, "instalments", "monthly"),
                1_200_000,
                0,
                DISBURSED));
    }

    static BringIn.Plan plan(List<String> order, BringIn.Line... lines) {
        return BringIn.apply(exampleB(), List.of(lines), "flat", false, order, TODAY);
    }

    /** Entered out of order, applied in date order: the earlier repayment pays item 1 whatever line it was on. */
    @Test
    void frExl02_repaymentsApplyInValueDateOrderThenEntryOrder() {
        BringIn.Plan p = plan(
                Servicing.DEFAULT_ORDER,
                new BringIn.Line(1, LocalDate.of(2026, 3, 20), 300_000, "cash", null),
                new BringIn.Line(2, LocalDate.of(2026, 2, 10), 520_000, "cash", null),
                new BringIn.Line(3, LocalDate.of(2026, 3, 20), 100_000, "bank", null));
        assertThat(p.paid().stream().map(x -> x.line().lineNo())).containsExactly(2, 1, 3);
        // The default order is penalty, fee, interest, principal: line 1 meets item 2's interest first.
        assertThat(p.paid().get(1).applied().allocations())
                .containsExactly(
                        new Allocation(p.items().get(1).id, "interest", 120_000),
                        new Allocation(p.items().get(1).id, "principal", 180_000));
        assertThat(p.position().principalOutstandingMinor()).isEqualTo(1_200_000 - 400_000 - 180_000 - 100_000);
        assertThat(p.totalPaidMinor()).isEqualTo(920_000);
        assertThat(p.lastRepaymentOn()).isEqualTo(LocalDate.of(2026, 3, 20));
        assertThat(p.overpaid()).isNull();
    }

    /** The product's own order: principal first moves the same money to principal before interest. */
    @Test
    void frExl02_theProductsAllocationOrderDecidesTheComponents() {
        BringIn.Plan p = plan(
                List.of("principal", "interest", "fee", "penalty"),
                new BringIn.Line(1, LocalDate.of(2026, 2, 10), 520_000, "cash", null),
                new BringIn.Line(2, LocalDate.of(2026, 3, 20), 300_000, "cash", null));
        assertThat(p.paid().get(1).applied().allocations())
                .containsExactly(new Allocation(p.items().get(1).id, "principal", 300_000));
        assertThat(p.position().principalOutstandingMinor()).isEqualTo(500_000);
        assertThat(p.position().interestOutstandingMinor()).isEqualTo(240_000);
    }

    /**
     * R-DPD on the business date: item 2 (due 15 March) and item 3 (due 15 April) are unpaid on 1 May, so days past due
     * count from 15 March, arrears are both items' unpaid contract, and item 2 is next due. Nothing is charged as a
     * penalty: the plan carries none for the time before the bring-in.
     */
    @Test
    void frExl02_thePositionCountsDaysPastDueFromTheRealDueDate() {
        BringIn.Plan p =
                plan(Servicing.DEFAULT_ORDER, new BringIn.Line(1, LocalDate.of(2026, 2, 10), 520_000, "cash", null));
        assertThat(p.position().daysPastDue()).isEqualTo(47);
        assertThat(p.position().arrearsMinor()).isEqualTo(1_040_000);
        assertThat(p.position().nextDueDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(p.position().penaltiesOutstandingMinor()).isZero();
        assertThat(p.items().getFirst().status(TODAY)).isEqualTo("paid");
        assertThat(p.items().get(1).status(TODAY)).isEqualTo("overdue");
        assertThat(p.position().settled()).isFalse();
        assertThat(p.settledOn()).isNull();
    }

    /** Repaid in full: settled on the last repayment's date; a line beyond that is flagged as an overpayment. */
    @Test
    void frExl02_aFullRepaymentSettlesAndMoreIsAnOverpayment() {
        BringIn.Plan full =
                plan(Servicing.DEFAULT_ORDER, new BringIn.Line(1, LocalDate.of(2026, 4, 15), 1_560_000, "cash", null));
        assertThat(full.position().settled()).isTrue();
        assertThat(full.settledOn()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(full.overpaid()).isNull();
        BringIn.Plan over = plan(
                Servicing.DEFAULT_ORDER,
                new BringIn.Line(1, LocalDate.of(2026, 4, 15), 1_560_000, "cash", null),
                new BringIn.Line(2, LocalDate.of(2026, 4, 16), 1, "cash", null));
        assertThat(over.overpaid().lineNo()).isEqualTo(2);
    }

    /** The agreed total of a register: the difference over the principal spread as flat interest, remainder last. */
    @Test
    void frExl02_anAgreedTotalSpreadsTheDifferenceAsFlatInterest() {
        List<ScheduleCalculator.Item> s = ScheduleCalculator.scheduleForTotal(
                new ScheduleCalculator.Terms("declining", 1000, "per_month", "month", 3, "instalments", "monthly"),
                1_000_000,
                1_300_001,
                DISBURSED);
        assertThat(s)
                .containsExactly(
                        new ScheduleCalculator.Item(1, LocalDate.of(2026, 2, 15), 333_333, 100_000, 0),
                        new ScheduleCalculator.Item(2, LocalDate.of(2026, 3, 15), 333_333, 100_000, 0),
                        new ScheduleCalculator.Item(3, LocalDate.of(2026, 4, 15), 333_334, 100_001, 0));
    }
}
