package com.rincoltech.bms.lending.products;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.products.ScheduleCalculator.Item;
import com.rincoltech.bms.lending.products.ScheduleCalculator.Terms;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The schedule rules of chapter 3 section 3.4. Worked examples A, B and C are the required exact
 * cases; the property checks run over seeded random terms (chapter 15 section 15.6). All figures
 * are fabricated round amounts.
 */
class ScheduleCalculatorTest {

    static final LocalDate D = LocalDate.of(2026, 3, 16);

    static Terms terms(String method, int bp, String rateUnit, String unit, int count, String pattern, String freq) {
        return new Terms(method, bp, rateUnit, unit, count, pattern, freq);
    }

    /** Worked example A: 500,000 at 2000 bp per term, 1 month, bullet, disbursed 16 March 2026. */
    @Test
    void workedExampleA() {
        List<Item> s =
                ScheduleCalculator.schedule(terms("flat", 2000, "per_term", "month", 1, "bullet", null), 500_000, 0, D);
        assertThat(s).containsExactly(new Item(1, LocalDate.of(2026, 4, 16), 500_000, 100_000, 0));
        assertThat(s.getFirst().totalMinor()).isEqualTo(600_000);
    }

    /** Worked example B: 1,200,000 at 1000 bp per month, 3 months, monthly instalments, flat. */
    @Test
    void workedExampleB() {
        List<Item> s = ScheduleCalculator.schedule(
                terms("flat", 1000, "per_month", "month", 3, "instalments", "monthly"), 1_200_000, 0, D);
        assertThat(s)
                .containsExactly(
                        new Item(1, LocalDate.of(2026, 4, 16), 400_000, 120_000, 0),
                        new Item(2, LocalDate.of(2026, 5, 16), 400_000, 120_000, 0),
                        new Item(3, LocalDate.of(2026, 6, 16), 400_000, 120_000, 0));
    }

    /** Worked example C: 1,000,000 at 1000 bp per month, 3 months, monthly, declining balance. */
    @Test
    void workedExampleC() {
        List<Item> s = ScheduleCalculator.schedule(
                terms("declining", 1000, "per_month", "month", 3, "instalments", "monthly"), 1_000_000, 0, D);
        assertThat(s)
                .extracting(Item::interestMinor, Item::principalMinor, Item::totalMinor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(100_000L, 302_115L, 402_115L),
                        org.assertj.core.groups.Tuple.tuple(69_789L, 332_326L, 402_115L),
                        org.assertj.core.groups.Tuple.tuple(36_556L, 365_559L, 402_115L));
    }

    /** R-FLAT: the last item takes the remainders, so principal and interest sum exactly. */
    @Test
    void flatRemaindersGoToTheLastItem() {
        List<Item> s = ScheduleCalculator.schedule(
                terms("flat", 1000, "per_term", "week", 3, "instalments", "weekly"), 1_000_000, 1_000, D);
        assertThat(s).extracting(Item::principalMinor).containsExactly(333_333L, 333_333L, 333_334L);
        assertThat(s).extracting(Item::interestMinor).containsExactly(33_333L, 33_333L, 33_334L);
        assertThat(s).extracting(Item::feeMinor).containsExactly(333L, 333L, 334L);
    }

    /** R-TERM: month ends clamp from the disbursement date and never drift. */
    @Test
    void monthEndsClampWithoutDrift() {
        List<Item> s = ScheduleCalculator.schedule(
                terms("flat", 0, "per_term", "month", 3, "instalments", "monthly"),
                300_000,
                0,
                LocalDate.of(2026, 1, 31));
        assertThat(s)
                .extracting(Item::dueDate)
                .containsExactly(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));
    }

    /** R-TERM: fortnightly needs an even number of weeks, weekly needs a multiple of seven days. */
    @Test
    void frequenciesMustDivideTheTerm() {
        assertThat(ScheduleCalculator.instalments(
                        terms("flat", 0, "per_term", "week", 4, "instalments", "fortnightly")))
                .isEqualTo(2);
        assertThat(ScheduleCalculator.instalments(terms("flat", 0, "per_term", "day", 14, "instalments", "weekly")))
                .isEqualTo(2);
        assertThat(ScheduleCalculator.instalments(terms("flat", 0, "per_term", "day", 30, "bullet", null)))
                .isEqualTo(1);
        for (Terms bad : List.of(
                terms("flat", 0, "per_term", "week", 3, "instalments", "fortnightly"),
                terms("flat", 0, "per_term", "day", 10, "instalments", "weekly"),
                terms("flat", 0, "per_term", "month", 3, "instalments", "weekly"),
                terms("flat", 0, "per_term", "week", 4, "instalments", "monthly"))) {
            assertThatThrownBy(() -> ScheduleCalculator.instalments(bad))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_term_frequency"));
        }
        assertThat(ScheduleCalculator.frequencyFitsUnit("week", "fortnightly")).isTrue();
        assertThat(ScheduleCalculator.frequencyFitsUnit("month", "daily")).isFalse();
    }

    /** R-RATE: per-period rates need the matching term unit; per-year rates prorate by days or months. */
    @Test
    void rateUnits() {
        assertThat(ScheduleCalculator.termRate(terms("flat", 1000, "per_week", "week", 4, "bullet", null)))
                .isEqualByComparingTo("0.4");
        assertThat(ScheduleCalculator.termRate(terms("flat", 2400, "per_year", "month", 6, "bullet", null)))
                .isEqualByComparingTo("0.12");
        // 36.5 percent a year over 10 days is exactly 1 percent; over 2 weeks, 1.4 percent.
        assertThat(ScheduleCalculator.termRate(terms("flat", 3650, "per_year", "day", 10, "bullet", null)))
                .isEqualByComparingTo("0.01");
        assertThat(ScheduleCalculator.termRate(terms("flat", 3650, "per_year", "week", 2, "bullet", null)))
                .isEqualByComparingTo("0.014");
        for (Terms bad : List.of(
                terms("flat", 1000, "per_month", "week", 4, "bullet", null),
                terms("flat", 1000, "per_day", "month", 1, "bullet", null),
                terms("flat", 1000, "per_decade", "month", 1, "bullet", null))) {
            assertThatThrownBy(() -> ScheduleCalculator.termRate(bad))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_rate_unit"));
        }
    }

    /** R-DECL with a zero rate is equal principal; a declining bullet is the flat schedule. */
    @Test
    void decliningEdgeCases() {
        List<Item> zero = ScheduleCalculator.schedule(
                terms("declining", 0, "per_month", "month", 3, "instalments", "monthly"), 1_000_000, 0, D);
        assertThat(zero).extracting(Item::principalMinor).containsExactly(333_333L, 333_333L, 333_334L);
        assertThat(zero).extracting(Item::interestMinor).containsOnly(0L);

        Terms bullet = terms("declining", 2000, "per_term", "month", 1, "bullet", null);
        assertThat(ScheduleCalculator.schedule(bullet, 500_000, 0, D))
                .isEqualTo(ScheduleCalculator.schedule(
                        terms("flat", 2000, "per_term", "month", 1, "bullet", null), 500_000, 0, D));
        assertThatThrownBy(() -> ScheduleCalculator.schedule(bullet, 0, 0, D))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Properties over seeded random terms: principals sum to P; flat interest sums to round(P x
     * r_term); a declining balance ends at zero with no negative line; due dates strictly increase.
     */
    @Test
    void propertiesHoldOverRandomTerms() {
        Random random = new Random(20260316);
        String[][] shapes = {
            {"month", "monthly", "per_month"},
            {"week", "weekly", "per_week"},
            {"week", "fortnightly", "per_term"},
            {"day", "daily", "per_day"},
            {"day", "weekly", "per_year"}
        };
        for (int run = 0; run < 2_000; run++) {
            String[] shape = shapes[random.nextInt(shapes.length)];
            int n = 1 + random.nextInt(36);
            int count = switch (shape[1]) {
                case "fortnightly" -> n * 2;
                case "weekly" -> shape[0].equals("day") ? n * 7 : n;
                default -> n;
            };
            String method = random.nextBoolean() ? "flat" : "declining";
            // Kept so the rate per instalment period is realistic (at most a few percent).
            int bp = shape[2].equals("per_year")
                    ? random.nextInt(6_000)
                    : random.nextInt(shape[2].equals("per_term") ? 6_000 : 400);
            long principal = 1_000 + (long) random.nextInt(50_000_000);
            long fees = random.nextInt(100_000);
            Terms t = terms(method, bp, shape[2], shape[0], count, "instalments", shape[1]);

            List<Item> s = ScheduleCalculator.schedule(t, principal, fees, D);

            assertThat(s).as("%s", t).hasSize(n);
            assertThat(s.stream().mapToLong(Item::principalMinor).sum())
                    .as("%s", t)
                    .isEqualTo(principal);
            assertThat(s.stream().mapToLong(Item::feeMinor).sum()).isEqualTo(fees);
            assertThat(s).allSatisfy(i -> {
                assertThat(i.principalMinor()).as("%s item %s", t, i).isNotNegative();
                assertThat(i.interestMinor()).isNotNegative();
            });
            if (method.equals("flat") || n == 1) {
                long expected = ScheduleCalculator.round(
                        java.math.BigDecimal.valueOf(principal).multiply(ScheduleCalculator.termRate(t)));
                assertThat(s.stream().mapToLong(Item::interestMinor).sum()).isEqualTo(expected);
            }
            for (int k = 1; k < n; k++) {
                assertThat(s.get(k).dueDate()).isAfter(s.get(k - 1).dueDate());
            }
            assertThat(s.getFirst().dueDate()).isAfter(D);
        }
    }
}
