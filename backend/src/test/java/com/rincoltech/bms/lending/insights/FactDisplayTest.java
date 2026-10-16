package com.rincoltech.bms.lending.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import org.junit.jupiter.api.Test;

/** The display forms of the facts (ADR-004: integers only, no floating point), which the validator reads back. */
class FactDisplayTest {

    @Test
    void amountsAreGroupedInThreesFromIntegerMinorUnits() {
        assertThat(FactDisplay.money(0, "UGX")).isEqualTo("UGX 0");
        assertThat(FactDisplay.money(999, "UGX")).isEqualTo("UGX 999");
        assertThat(FactDisplay.money(1_000, "UGX")).isEqualTo("UGX 1,000");
        assertThat(FactDisplay.money(1_250_000, "UGX")).isEqualTo("UGX 1,250,000");
        assertThat(FactDisplay.money(-1_250_000, "UGX")).isEqualTo("-UGX 1,250,000");
        assertThat(FactDisplay.money(Long.MAX_VALUE, "UGX")).isEqualTo("UGX 9,223,372,036,854,775,807");
        assertThat(FactDisplay.money(125_050, "KES")).isEqualTo("KES 1,250.50");
        assertThat(FactDisplay.money(5, "KES")).isEqualTo("KES 0.05");
        assertThat(FactDisplay.money(-5, "USD")).isEqualTo("-USD 0.05");
        assertThat(FactDisplay.exponent("UGX")).isZero();
        assertThat(FactDisplay.exponent("KES")).isEqualTo(2);
        assertThat(FactDisplay.exponent("XYZ")).isZero();
    }

    @Test
    void ratesAreRoundedHalfUpToOneDecimalFromBasisPoints() {
        assertThat(FactDisplay.percent(0)).isEqualTo("0.0 percent");
        assertThat(FactDisplay.percent(4)).isEqualTo("0.0 percent");
        assertThat(FactDisplay.percent(5)).isEqualTo("0.1 percent");
        assertThat(FactDisplay.percent(1254)).isEqualTo("12.5 percent");
        assertThat(FactDisplay.percent(1255)).isEqualTo("12.6 percent");
        assertThat(FactDisplay.percent(10_000)).isEqualTo("100.0 percent");
        assertThat(FactDisplay.percent(25)).isEqualTo("0.3 percent");
    }

    @Test
    void countsDaysAndHoursCarryTheirUnitAndSeparators() {
        assertThat(FactDisplay.count(1_234_567)).isEqualTo("1,234,567");
        assertThat(FactDisplay.count(-1234)).isEqualTo("-1,234");
        assertThat(FactDisplay.of(Kind.DAYS, 1, "UGX")).isEqualTo("1 day");
        assertThat(FactDisplay.of(Kind.DAYS, 45, "UGX")).isEqualTo("45 days");
        assertThat(FactDisplay.of(Kind.HOURS, 1, "UGX")).isEqualTo("1 hour");
        assertThat(FactDisplay.of(Kind.HOURS, 26, "UGX")).isEqualTo("26 hours");
        assertThat(FactDisplay.of(Kind.COUNT, 312, "UGX")).isEqualTo("312");
        assertThat(FactDisplay.of(Kind.MONEY, 1000, "UGX")).isEqualTo("UGX 1,000");
        assertThat(FactDisplay.of(Kind.BASIS_POINTS, 8750, "UGX")).isEqualTo("87.5 percent");
    }

    @Test
    void aChangeCarriesItsSignAndIsExactForRates() {
        assertThat(FactDisplay.change(Kind.BASIS_POINTS, 40, "UGX")).isEqualTo("+0.40 points");
        assertThat(FactDisplay.change(Kind.BASIS_POINTS, -40, "UGX")).isEqualTo("-0.40 points");
        assertThat(FactDisplay.change(Kind.BASIS_POINTS, 1, "UGX")).isEqualTo("+0.01 points");
        assertThat(FactDisplay.change(Kind.BASIS_POINTS, -1234, "UGX")).isEqualTo("-12.34 points");
        assertThat(FactDisplay.change(Kind.BASIS_POINTS, 0, "UGX")).isEqualTo("no change");
        assertThat(FactDisplay.change(Kind.MONEY, 250_000, "UGX")).isEqualTo("+UGX 250,000");
        assertThat(FactDisplay.change(Kind.MONEY, -120_000, "UGX")).isEqualTo("-UGX 120,000");
        assertThat(FactDisplay.change(Kind.MONEY, -150, "KES")).isEqualTo("-KES 1.50");
        assertThat(FactDisplay.change(Kind.COUNT, 3, "UGX")).isEqualTo("+3");
        assertThat(FactDisplay.change(Kind.COUNT, -1200, "UGX")).isEqualTo("-1,200");
        assertThat(FactDisplay.change(Kind.DAYS, 1, "UGX")).isEqualTo("+1 day");
        assertThat(FactDisplay.change(Kind.DAYS, -2, "UGX")).isEqualTo("-2 days");
        assertThat(FactDisplay.change(Kind.HOURS, 5, "UGX")).isEqualTo("+5 hours");
        assertThat(FactDisplay.change(Kind.COUNT, 0, "UGX")).isEqualTo("no change");
    }
}
