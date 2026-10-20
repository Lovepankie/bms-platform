package com.rincoltech.bms.retail.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.retail.reports.internal.CreditRecognition.Event;
import com.rincoltech.bms.retail.reports.internal.ReportsRepository.DayFigures;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The arithmetic of credit sales counted when the buyer pays (ADR-043), worked by hand with fabricated figures. */
class CreditRecognitionTest {

    static final UUID BRANCH = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    static final UUID SALE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    static final LocalDate D2 = LocalDate.of(2026, 10, 2);
    static final LocalDate D3 = LocalDate.of(2026, 10, 3);

    /** A sale of 3,000 that cost 1,800: a margin of 40 percent. */
    static Event pay(LocalDate day, long amount) {
        return new Event(SALE, BRANCH, 3_000, 1_800, day, amount, 0, 0, 0);
    }

    static long profit(List<DayFigures> rows) {
        return rows.stream().mapToLong(r -> r.sales() - r.costOfSales()).sum();
    }

    static DayFigures on(List<DayFigures> rows, LocalDate day) {
        return rows.stream().filter(r -> r.date().equals(day)).findFirst().orElseThrow();
    }

    @Test
    void aPartPaymentGivesTheShareOfTheProfitOfTheMoneyReceived() {
        List<DayFigures> rows = CreditRecognition.deltas(List.of(pay(D1, 1_000)), D1, D1);
        assertThat(on(rows, D1).sales()).isEqualTo(1_000);
        assertThat(on(rows, D1).costOfSales()).isEqualTo(600);
        assertThat(profit(rows)).isEqualTo(400);
    }

    @Test
    void severalPaymentsAcrossDaysAddUpToTheWholeProfitOnce() {
        List<DayFigures> rows = CreditRecognition.deltas(List.of(pay(D1, 1_000), pay(D2, 500), pay(D3, 1_500)), D1, D3);
        assertThat(profit(List.of(on(rows, D1)))).isEqualTo(400);
        assertThat(profit(List.of(on(rows, D2)))).isEqualTo(200);
        assertThat(profit(List.of(on(rows, D3)))).isEqualTo(600);
        assertThat(profit(rows)).isEqualTo(1_200);
    }

    @Test
    void aWindowAfterTheFirstPaymentSeesOnlyItsOwnDaysAndTheSameFigures() {
        List<DayFigures> later = CreditRecognition.deltas(List.of(pay(D1, 1_000), pay(D2, 500)), D2, D2);
        assertThat(later).hasSize(1);
        assertThat(profit(later)).isEqualTo(200);
    }

    @Test
    void roundingIsOnTheCumulativeCostSoThePiecesAlwaysAddUpExactly() {
        // 1,000 sold at a cost of 333: thirds of it paid one by one must total the whole, to the unit.
        Event a = new Event(SALE, BRANCH, 1_000, 333, D1, 333, 0, 0, 0);
        Event b = new Event(SALE, BRANCH, 1_000, 333, D2, 333, 0, 0, 0);
        Event c = new Event(SALE, BRANCH, 1_000, 333, D3, 334, 0, 0, 0);
        List<DayFigures> rows = CreditRecognition.deltas(List.of(a, b, c), D1, D3);
        assertThat(rows.stream().mapToLong(DayFigures::sales).sum()).isEqualTo(1_000);
        assertThat(rows.stream().mapToLong(DayFigures::costOfSales).sum()).isEqualTo(333);
        assertThat(profit(rows)).isEqualTo(667);
    }

    @Test
    void aReversedPaymentTakesItsShareBackOnTheDayOfTheReversal() {
        List<DayFigures> rows = CreditRecognition.deltas(List.of(pay(D1, 3_000), pay(D2, -3_000)), D1, D2);
        assertThat(profit(List.of(on(rows, D1)))).isEqualTo(1_200);
        assertThat(profit(List.of(on(rows, D2)))).isEqualTo(-1_200);
        assertThat(profit(rows)).isZero();
    }

    @Test
    void aReturnOfUnpaidGoodsChangesNothingUntilThePaymentsFollowTheSmallerSale() {
        Event returned = new Event(SALE, BRANCH, 3_000, 1_800, D1, 0, 1_000, 600, 0);
        Event paid = new Event(SALE, BRANCH, 3_000, 1_800, D2, 2_000, 0, 0, 0);
        List<DayFigures> rows = CreditRecognition.deltas(List.of(returned, paid), D1, D2);
        assertThat(rows.stream().anyMatch(r -> r.date().equals(D1) && (r.sales() != 0 || r.costOfSales() != 0)))
                .isFalse();
        assertThat(on(rows, D2).sales()).isEqualTo(2_000);
        assertThat(on(rows, D2).costOfSales()).isEqualTo(1_200);
    }

    @Test
    void aReturnRefundedInMoneyGivesBackItsShare() {
        Event paid = pay(D1, 3_000);
        Event refund = new Event(SALE, BRANCH, 3_000, 1_800, D2, 0, 1_000, 600, 1_000);
        List<DayFigures> rows = CreditRecognition.deltas(List.of(paid, refund), D1, D2);
        assertThat(profit(List.of(on(rows, D2)))).isEqualTo(-400);
        assertThat(profit(rows)).isEqualTo(800);
    }

    @Test
    void aSaleReturnedInFullHoldsNothing() {
        CreditRecognition.State s = CreditRecognition.state(3_000, 1_800, 3_000, 3_000, 1_800, 3_000);
        assertThat(s.revenue()).isZero();
        assertThat(s.cost()).isZero();
    }

    @Test
    void anOverpaidFigureIsCappedAtWhatTheSaleIsWorth() {
        CreditRecognition.State s = CreditRecognition.state(3_000, 1_800, 4_000, 0, 0, 0);
        assertThat(s.revenue()).isEqualTo(3_000);
        assertThat(s.cost()).isEqualTo(1_800);
    }

    @Test
    void salesAreKeptApartAndSummedPerBranchAndDay() {
        UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
        Event second = new Event(other, BRANCH, 1_000, 500, D1, 1_000, 0, 0, 0);
        List<DayFigures> rows = CreditRecognition.deltas(List.of(pay(D1, 3_000), second), D1, D1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).sales()).isEqualTo(4_000);
        assertThat(rows.get(0).costOfSales()).isEqualTo(2_300);
    }

    @Test
    void aPaymentIsCreditedToTheBranchThatTookItAndTheRunningFiguresAreTheSales() {
        UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
        Event atFirst = new Event(SALE, BRANCH, 3_000, 1_800, D1, 1_000, 0, 0, 0);
        Event atOther = new Event(SALE, other, 3_000, 1_800, D2, 500, 0, 0, 0);
        List<DayFigures> all = CreditRecognition.deltas(List.of(atFirst, atOther), D1, D2);
        assertThat(all.stream()
                        .filter(r -> r.branchId().equals(BRANCH))
                        .mapToLong(r -> r.sales() - r.costOfSales())
                        .sum())
                .isEqualTo(400);
        assertThat(all.stream()
                        .filter(r -> r.branchId().equals(other))
                        .mapToLong(r -> r.sales() - r.costOfSales())
                        .sum())
                .isEqualTo(200);
        List<DayFigures> onlyOther =
                CreditRecognition.deltas(List.of(atFirst, atOther), D1, D2, java.util.Set.of(other));
        assertThat(onlyOther).hasSize(1);
        assertThat(profit(onlyOther))
                .as("the same 200, with the first payment still counted in the running figures")
                .isEqualTo(200);
    }

    @Test
    void thePaymentsAndTheirCostSharesOfOneBranchAndDaySumTogether() {
        Event a = new Event(SALE, BRANCH, 3_000, 1_800, D1, 1_000, 0, 0, 0);
        Event b = new Event(
                UUID.fromString("00000000-0000-4000-8000-0000000000a3"), BRANCH, 1_000, 500, D1, 1_000, 0, 0, 0);
        List<DayFigures> rows = CreditRecognition.deltas(List.of(a, b), D1, D1, java.util.Set.of(BRANCH));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).sales()).isEqualTo(2_000);
        assertThat(rows.get(0).costOfSales()).isEqualTo(1_100);
    }

    @Test
    void aReturnCreditedToTheBalanceIsSpreadOverTheBranchesThatTookTheMoney() {
        UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
        Event first = new Event(SALE, BRANCH, 3_000, 1_800, D1, 1_500, 0, 0, 0);
        Event second = new Event(SALE, other, 3_000, 1_800, D1, 500, 0, 0, 0);
        // Goods worth 1,000 that cost 900 come back and are credited to the unpaid balance: no cash goes out.
        Event credit = new Event(SALE, BRANCH, 3_000, 1_800, D2, 0, 1_000, 900, 0);
        List<DayFigures> rows = CreditRecognition.deltas(List.of(first, second, credit), D2, D2);
        assertThat(rows).hasSize(2);
        assertThat(profit(rows.stream().filter(r -> r.branchId().equals(BRANCH)).toList()))
                .isEqualTo(225);
        assertThat(profit(rows.stream().filter(r -> r.branchId().equals(other)).toList()))
                .isEqualTo(75);
        assertThat(profit(rows)).as("the whole change, once").isEqualTo(300);
    }

    @Test
    void theSharesOfASpreadAlwaysAddUpToTheAmount() {
        UUID a = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
        UUID b = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
        UUID c = UUID.fromString("00000000-0000-4000-8000-0000000000c3");
        List<java.util.Map.Entry<UUID, Long>> w =
                List.of(java.util.Map.entry(a, 1L), java.util.Map.entry(b, 1L), java.util.Map.entry(c, 1L));
        assertThat(java.util.Arrays.stream(CreditRecognition.spread(100, w, 3)).sum())
                .isEqualTo(100);
        assertThat(java.util.Arrays.stream(CreditRecognition.spread(-100, w, 3)).sum())
                .isEqualTo(-100);
    }

    @Test
    void theResultDoesNotDependOnTheOrderOfTheBranchIds() {
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
        // The same day, entered in this order: a payment at X, a payment at Y, then a cash refund from Y.
        for (UUID[] ids : new UUID[][] {{low, high}, {high, low}}) {
            UUID x = ids[0];
            UUID y = ids[1];
            List<Event> events = List.of(
                    new Event(SALE, x, 3_000, 1_800, D1, 1_000, 0, 0, 0),
                    new Event(SALE, y, 3_000, 1_800, D1, 2_000, 0, 0, 0),
                    new Event(SALE, y, 3_000, 1_800, D1, 0, 3_000, 1_800, 3_000));
            List<DayFigures> rows = CreditRecognition.deltas(events, D1, D1);
            assertThat(profit(rows.stream().filter(r -> r.branchId().equals(x)).toList()))
                    .isEqualTo(400);
            assertThat(profit(rows.stream().filter(r -> r.branchId().equals(y)).toList()))
                    .isEqualTo(-400);
        }
    }
}
