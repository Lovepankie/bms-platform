package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AmountInWordsTest {

    @Test
    void shillingsHaveNoMinorUnit() {
        assertThat(AmountInWords.of(600_000, "UGX")).isEqualTo("Six hundred thousand Uganda shillings only");
        assertThat(AmountInWords.of(1, "UGX")).isEqualTo("One Uganda shilling only");
        assertThat(AmountInWords.of(0, "UGX")).isEqualTo("Zero Uganda shillings only");
        assertThat(AmountInWords.of(1_250_075, "UGX"))
                .isEqualTo("One million, two hundred and fifty thousand and seventy-five Uganda shillings only");
        assertThat(AmountInWords.of(402_115, "UGX"))
                .isEqualTo("Four hundred and two thousand, one hundred and fifteen Uganda shillings only");
    }

    @Test
    void twoDecimalCurrenciesReadCents() {
        assertThat(AmountInWords.of(150_050, "USD"))
                .isEqualTo("One thousand, five hundred US dollars and fifty cents only");
        assertThat(AmountInWords.of(101, "KES")).isEqualTo("One Kenya shilling and one cent only");
    }

    @Test
    void largeAndEdgeNumbers() {
        assertThat(AmountInWords.number(1_000_000_000_000_000L)).isEqualTo("one quadrillion");
        assertThat(AmountInWords.number(2_000_010)).isEqualTo("two million and ten");
        assertThat(AmountInWords.number(100)).isEqualTo("one hundred");
        assertThat(AmountInWords.number(19)).isEqualTo("nineteen");
    }

    @Test
    void refusesWhatItCannotSay() {
        assertThatThrownBy(() -> AmountInWords.of(-1, "UGX")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmountInWords.of(1, "EUR")).isInstanceOf(IllegalArgumentException.class);
        // Beyond what the scales name: a clear error, not an index error.
        assertThatThrownBy(() -> AmountInWords.number(1_000_000_000_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmountInWords.of(Long.MAX_VALUE, "UGX")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AmountInWords.number(999_999_999_999_999_999L))
                .startsWith("nine hundred and ninety-nine quadrillion");
    }
}
