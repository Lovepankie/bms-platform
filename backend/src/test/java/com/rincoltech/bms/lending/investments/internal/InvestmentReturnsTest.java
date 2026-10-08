package com.rincoltech.bms.lending.investments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.EarlySettlement;
import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.Period;
import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.Terms;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden return calculations to the unit (R-INV-1 to R-INV-6; FR-INV-03, FR-INV-04, FR-INV-06):
 * flat and compounding schedules, month-end clamping in leap and common years, partial periods by
 * actual days in the calendar year, and each early withdrawal rule. Every figure was worked by
 * hand with exact fractions; all amounts fabricated.
 */
class InvestmentReturnsTest {

    static List<Long> returns(List<Period> periods) {
        return periods.stream().map(Period::returnMinor).toList();
    }

    @Test
    void flatMonthlyReturnIsTheRateOverTwelveEveryMonth() {
        List<Period> s = InvestmentReturns.schedule(
                new Terms(1_000_000, 1_200, "flat", 12, "monthly"), LocalDate.of(2027, 3, 10));
        assertThat(returns(s)).containsOnly(10_000L).hasSize(12);
        assertThat(InvestmentReturns.total(s)).isEqualTo(120_000);
        assertThat(s).allMatch(Period::payout);
        assertThat(s.getLast().end()).isEqualTo(LocalDate.of(2028, 3, 10));
    }

    /** FR-INV-03 rounds the total once; the months take cumulative differences so they sum to it exactly. */
    @Test
    void flatRoundingResidueNeverDrifts() {
        List<Period> s = InvestmentReturns.schedule(
                new Terms(1_000_000, 1_000, "flat", 3, "at_maturity"), LocalDate.of(2027, 1, 1));
        assertThat(returns(s)).containsExactly(8_333L, 8_334L, 8_333L);
        assertThat(InvestmentReturns.total(s)).isEqualTo(25_000).isEqualTo(InvestmentReturns.flat(1_000_000, 1_000, 3));
        assertThat(s.stream().map(Period::payout).toList()).containsExactly(false, false, true);
    }

    @Test
    void compoundingEarnsOnTheReturnAlreadyEarnedEachMonth() {
        List<Period> s = InvestmentReturns.schedule(
                new Terms(1_000_000, 1_200, "compound", 3, "at_maturity"), LocalDate.of(2027, 1, 1));
        assertThat(returns(s)).containsExactly(10_000L, 10_100L, 10_201L);
        assertThat(s.stream().map(Period::openingBalanceMinor).toList())
                .containsExactly(1_000_000L, 1_010_000L, 1_020_100L);
        assertThat(InvestmentReturns.total(s)).isEqualTo(30_301);
    }

    @Test
    void compoundingRoundsHalfUpEachMonth() {
        List<Period> s = InvestmentReturns.schedule(
                new Terms(1_234_567, 1_000, "compound", 3, "at_maturity"), LocalDate.of(2027, 1, 1));
        // 10,288.058 then 10,373.79 then 10,460.24.
        assertThat(returns(s)).containsExactly(10_288L, 10_374L, 10_460L);
        assertThat(InvestmentReturns.total(s)).isEqualTo(31_122);
    }

    @Test
    void compoundingOverTwoYears() {
        List<Period> s = InvestmentReturns.schedule(
                new Terms(5_000_000, 1_500, "compound", 24, "at_maturity"), LocalDate.of(2027, 6, 30));
        assertThat(InvestmentReturns.total(s)).isEqualTo(1_736_752);
        assertThat(s.stream().filter(Period::payout).count()).isEqualTo(1);
    }

    @Test
    void compoundingWithAPeriodicPayoutIsRefused() {
        assertThatThrownBy(() -> InvestmentReturns.schedule(
                        new Terms(1_000_000, 1_200, "compound", 6, "monthly"), LocalDate.of(2027, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** R-TERM month rule: every date from the start, clamped, so 31 January never drifts to the 28th. */
    @Test
    void periodsClampToMonthEndInALeapYearWithoutDrift() {
        List<Period> s = InvestmentReturns.schedule(
                new Terms(1_000_000, 1_200, "flat", 3, "monthly"), LocalDate.of(2028, 1, 31));
        assertThat(s.stream().map(Period::end).toList())
                .containsExactly(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 31), LocalDate.of(2028, 4, 30));
        assertThat(s.get(1).start()).isEqualTo(LocalDate.of(2028, 2, 29));
        List<Period> common = InvestmentReturns.schedule(
                new Terms(1_000_000, 1_200, "flat", 2, "monthly"), LocalDate.of(2027, 1, 31));
        assertThat(common.getFirst().end()).isEqualTo(LocalDate.of(2027, 2, 28));
        // Whole months earn the same in either year: the rate is per month, not per day.
        assertThat(returns(s)).containsOnly(10_000L);
    }

    @Test
    void quarterlyPayoutEveryThirdMonthAndAtMaturity() {
        List<Period> s =
                InvestmentReturns.schedule(new Terms(600_000, 1_200, "flat", 7, "quarterly"), LocalDate.of(2027, 1, 1));
        assertThat(s.stream().map(Period::payout).toList())
                .containsExactly(false, false, true, false, false, true, true);
    }

    /** R-INV-5: a partial month counts its days over the days of their calendar year. */
    @Test
    void partialPeriodInALeapYearCountsThreeHundredSixtySixDays() {
        // 1 month plus 15 days (15 to 29 February 2028 and 1 March): 10,000 + 120,000 x 15 / 366 = 14,918.03.
        assertThat(InvestmentReturns.simple(1_000_000, 1_200, LocalDate.of(2028, 1, 15), LocalDate.of(2028, 3, 1)))
                .isEqualTo(14_918);
        // The same dates a year earlier: 14 days over 365, 10,000 + 4,602.74.
        assertThat(InvestmentReturns.simple(1_000_000, 1_200, LocalDate.of(2027, 1, 15), LocalDate.of(2027, 3, 1)))
                .isEqualTo(14_603);
    }

    @Test
    void partialPeriodAcrossTheYearEndSplitsByYear() {
        // 12 days of 2027 over 365 and 9 days of 2028 over 366: 3,945.21 + 2,950.82 = 6,896.03.
        assertThat(InvestmentReturns.simple(1_000_000, 1_200, LocalDate.of(2027, 12, 20), LocalDate.of(2028, 1, 10)))
                .isEqualTo(6_896);
        assertThat(InvestmentReturns.held(LocalDate.of(2027, 12, 20), LocalDate.of(2028, 1, 10))
                        .months())
                .isZero();
    }

    @Test
    void wholeMonthsHeldMatchTheScheduleRule() {
        InvestmentReturns.Held h = InvestmentReturns.held(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 3, 30));
        assertThat(h.months()).isEqualTo(1);
        assertThat(h.monthsEnd()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(h.days()).isEqualTo(30);
        assertThat(InvestmentReturns.simple(1_000_000, 1_200, LocalDate.of(2027, 5, 5), LocalDate.of(2027, 9, 5)))
                .isEqualTo(InvestmentReturns.flat(1_000_000, 1_200, 4))
                .isEqualTo(40_000);
    }

    /** FR-INV-06 worked example 1: forfeit_return claws back the returns already paid, then the penalty. */
    @Test
    void forfeitReturnWithPaidReturnsAndAPenalty() {
        EarlySettlement s = InvestmentReturns.early(
                1_000_000,
                "forfeit_return",
                null,
                200,
                LocalDate.of(2027, 1, 10),
                LocalDate.of(2027, 5, 20),
                40_000,
                40_000);
        assertThat(s.earnedMinor()).isZero();
        assertThat(s.penaltyMinor()).isEqualTo(20_000);
        assertThat(s.returnNowMinor()).isEqualTo(-40_000);
        assertThat(s.cashMinor()).isEqualTo(940_000);
    }

    /** FR-INV-06 worked example 2: reduced_rate earns the lower rate for the time held, the accrual trued down. */
    @Test
    void reducedRateForTheTimeHeld() {
        EarlySettlement s = InvestmentReturns.early(
                1_000_000, "reduced_rate", 600, 0, LocalDate.of(2027, 1, 10), LocalDate.of(2027, 5, 10), 40_000, 0);
        assertThat(s.earnedMinor()).isEqualTo(20_000);
        assertThat(s.accruedMinor() - s.earnedMinor()).isEqualTo(20_000);
        assertThat(s.cashMinor()).isEqualTo(1_020_000);
    }

    @Test
    void reducedRateWithPartialDaysAndAPenalty() {
        // 2 months and 19 days of 2028 at 600 bp: 10,000 + 60,000 x 19 / 366 = 13,114.75; penalty 1% of principal.
        EarlySettlement s = InvestmentReturns.early(
                1_000_000, "reduced_rate", 600, 100, LocalDate.of(2028, 1, 10), LocalDate.of(2028, 3, 29), 20_000, 0);
        assertThat(s.earnedMinor()).isEqualTo(13_115);
        assertThat(s.penaltyMinor()).isEqualTo(10_000);
        assertThat(s.cashMinor()).isEqualTo(1_003_115);
    }

    @Test
    void thePenaltyNeverTakesTheCashBelowZero() {
        EarlySettlement s = InvestmentReturns.early(
                100, "forfeit_return", null, 5_000, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 1), 60, 60);
        assertThat(s.penaltyMinor()).isEqualTo(40);
        assertThat(s.cashMinor()).isZero();
    }
}
