package com.rincoltech.bms.lending.savings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FR-SAV-05 golden examples, worked by hand with fabricated balances and checked to the minor unit:
 * one rounding per posting (R-ROUND), half up.
 */
class InterestTest {

    /** End-of-day balances: {@code value} from {@code from} through {@code to}. */
    static void fill(Map<LocalDate, Long> m, String from, String to, long value) {
        for (LocalDate d = LocalDate.parse(from); !d.isAfter(LocalDate.parse(to)); d = d.plusDays(1)) {
            m.put(d, value);
        }
    }

    static final LocalDate JUNE_1 = LocalDate.parse("2026-06-01");
    static final LocalDate JUNE_30 = LocalDate.parse("2026-06-30");

    static Map<LocalDate, Long> june() {
        Map<LocalDate, Long> m = new LinkedHashMap<>();
        fill(m, "2026-06-01", "2026-06-10", 100_000);
        fill(m, "2026-06-11", "2026-06-20", 250_000);
        fill(m, "2026-06-21", "2026-06-30", 175_000);
        return m;
    }

    /**
     * daily_balance, 12% a year, June: 10 days at 100,000, 10 at 250,000, 10 at 175,000. Sum of
     * balances 5,250,000; x 1,200 bp = 6,300,000,000; / (10,000 x 365) = 1,726.03; rounds to 1,726.
     */
    @Test
    void frSav05_dailyBalanceSumsEveryDayAndRoundsOnce() {
        assertThat(Interest.forPeriod("daily_balance", 1_200, 0, JUNE_1, JUNE_1, JUNE_30, june()))
                .isEqualTo(1_726);
    }

    /** The same month with a 150,000 minimum for interest: the first ten days earn nothing; 1,397.26 rounds to 1,397. */
    @Test
    void frSav05_daysBelowTheMinimumForInterestEarnNothing() {
        assertThat(Interest.forPeriod("daily_balance", 1_200, 150_000, JUNE_1, JUNE_1, JUNE_30, june()))
                .isEqualTo(1_397);
    }

    /** One day at 18,250 and 1% is exactly half a shilling: it rounds up; 18,249 rounds down. */
    @Test
    void frSav05_halfAMinorUnitRoundsUpOnce() {
        LocalDate d = LocalDate.parse("2026-01-31");
        assertThat(Interest.forPeriod("daily_balance", 100, 0, d, d, d, Map.of(d, 18_250L)))
                .isEqualTo(1);
        assertThat(Interest.forPeriod("daily_balance", 100, 0, d, d, d, Map.of(d, 18_249L)))
                .isZero();
        // Thirty days of the second balance would each round to zero; summed first, they earn 15.
        Map<LocalDate, Long> m = new LinkedHashMap<>();
        fill(m, "2026-06-01", "2026-06-30", 18_249);
        assertThat(Interest.forPeriod("daily_balance", 100, 0, JUNE_1, JUNE_1, JUNE_30, m))
                .isEqualTo(15);
    }

    static Map<LocalDate, Long> quarter() {
        Map<LocalDate, Long> m = new LinkedHashMap<>();
        fill(m, "2026-07-01", "2026-07-31", 200_000);
        fill(m, "2026-08-01", "2026-08-31", 180_000);
        m.put(LocalDate.parse("2026-08-17"), 150_000L);
        fill(m, "2026-09-01", "2026-09-30", 300_000);
        return m;
    }

    /**
     * minimum_monthly_balance, 8% a year, the third quarter: lowest balances 200,000, 150,000 (one
     * day's dip in August) and 300,000. (650,000 x 800) / (10,000 x 12) = 4,333.33; rounds to 4,333.
     */
    @Test
    void frSav05_minimumMonthlyBalanceTakesTheLowestDayOfEachMonth() {
        LocalDate start = LocalDate.parse("2026-07-01");
        LocalDate end = LocalDate.parse("2026-09-30");
        assertThat(Interest.forPeriod("minimum_monthly_balance", 800, 0, start, start, end, quarter()))
                .isEqualTo(4_333);
    }

    /**
     * A month the account was not open on every day of earns nothing (opened 15 July: 450,000 x 800 /
     * 120,000 = 3,000), nor does a month the period does not cover to its end (closed on 20 September:
     * 350,000 x 800 / 120,000 = 2,333.33, rounds to 2,333), nor a month below the minimum for interest.
     */
    @Test
    void frSav05_partMonthsAndMonthsBelowTheMinimumEarnNothing() {
        Map<LocalDate, Long> q = quarter();
        LocalDate opened = LocalDate.parse("2026-07-15");
        LocalDate end = LocalDate.parse("2026-09-30");
        assertThat(Interest.forPeriod("minimum_monthly_balance", 800, 0, opened, opened, end, q))
                .isEqualTo(3_000);
        LocalDate start = LocalDate.parse("2026-07-01");
        LocalDate closed = LocalDate.parse("2026-09-20");
        assertThat(Interest.forPeriod("minimum_monthly_balance", 800, 0, start, start, closed, q))
                .isEqualTo(2_333);
        assertThat(Interest.forPeriod("minimum_monthly_balance", 800, 160_000, start, start, end, q))
                .isEqualTo(3_333);
    }

    @Test
    void frSav05_noneEarnsNothingAndAMissingDayIsRefused() {
        assertThat(Interest.forPeriod("none", 0, 0, JUNE_1, JUNE_1, JUNE_30, Map.of()))
                .isZero();
        Map<LocalDate, Long> gap = june();
        gap.remove(LocalDate.parse("2026-06-15"));
        assertThatThrownBy(() -> Interest.forPeriod("daily_balance", 1_200, 0, JUNE_1, JUNE_1, JUNE_30, gap))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2026-06-15");
    }

    @Test
    void frSav05_periodEnds() {
        assertThat(Interest.periodEnd("monthly", LocalDate.parse("2028-02-10")))
                .isEqualTo(LocalDate.parse("2028-02-29"));
        assertThat(Interest.periodEnd("quarterly", LocalDate.parse("2026-02-10")))
                .isEqualTo(LocalDate.parse("2026-03-31"));
        assertThat(Interest.periodEnd("quarterly", LocalDate.parse("2026-12-01")))
                .isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(Interest.periodEnd("yearly", LocalDate.parse("2026-06-15")))
                .isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(Interest.isPeriodEnd("quarterly", LocalDate.parse("2026-06-30")))
                .isTrue();
        assertThat(Interest.isPeriodEnd("quarterly", LocalDate.parse("2026-05-31")))
                .isFalse();
    }

    @Test
    void smsAmountsUseTheCurrencyExponent() {
        assertThat(SavingsNotices.format(1_234_567, 0)).isEqualTo("1,234,567");
        assertThat(SavingsNotices.format(1_234_567, 2)).isEqualTo("12,345.67");
    }
}
