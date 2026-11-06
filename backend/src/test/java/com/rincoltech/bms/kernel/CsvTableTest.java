package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #870 (review of #871): {@link CsvTable#amountMinor} takes a comma only between groups of three digits, the rule of
 * #867. Every import that reads amounts through it (the retail items import and the lending existing loans import) refuses a
 * comma used as a decimal point instead of reading 1,5 as 15. Fabricated amounts.
 */
class CsvTableTest {

    @ParameterizedTest
    @CsvSource({
        "1500, 0, 1500",
        "'12,500', 0, 12500",
        "'1,250,000', 0, 1250000",
        "' 3,000 ', 0, 3000",
        "12.50, 2, 1250",
        "'1,200.75', 2, 120075",
        "7, 2, 700"
    })
    void readsWholeAmountsAndThousandsCommas(String text, int exponent, long minor) {
        assertThat(CsvTable.amountMinor(text, exponent)).isEqualTo(minor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,5", "0,250", "12,50", "1000,000", "12,5000", ",500", "1,,000", "1,000,00"})
    void refusesACommaThatIsNotBetweenThousands(String text) {
        assertThat(CsvTable.amountMinor(text, 0)).as(text).isNull();
        assertThat(CsvTable.amountMinor(text, 2)).as(text).isNull();
    }

    @Test
    void namesACommaUsedAsADecimalPointSoTheImportCanSaySo() {
        assertThat(CsvTable.commaDecimal("1,5")).isTrue();
        assertThat(CsvTable.commaDecimal("0,250")).isTrue();
        assertThat(CsvTable.commaDecimal("12,500")).isFalse();
        assertThat(CsvTable.commaDecimal("1500")).isFalse();
    }

    @Test
    void stillRefusesWhatItRefusedBefore() {
        assertThat(CsvTable.amountMinor("12.5", 0)).isNull();
        assertThat(CsvTable.amountMinor("-5", 0)).isNull();
        assertThat(CsvTable.amountMinor("ten", 0)).isNull();
        assertThat(CsvTable.amountMinor("", 0)).isNull();
    }
}
