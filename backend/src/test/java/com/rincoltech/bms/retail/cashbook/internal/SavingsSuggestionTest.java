package com.rincoltech.bms.retail.cashbook.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** FR-RET-18: the suggestion is the day's profit times the rate, half up, never below zero. Fabricated figures. */
class SavingsSuggestionTest {

    @Test
    void halfOfTheProfitAtTheDefaultRate() {
        assertThat(SavingsService.suggested(5_000, 5_000)).isEqualTo(2_500);
        assertThat(SavingsService.suggested(123_456, 5_000)).isEqualTo(61_728);
    }

    @Test
    void roundsHalfUp() {
        assertThat(SavingsService.suggested(5_001, 5_000)).isEqualTo(2_501);
        assertThat(SavingsService.suggested(1, 5_000)).isEqualTo(1);
        assertThat(SavingsService.suggested(3, 3_333)).isEqualTo(1);
        assertThat(SavingsService.suggested(7, 1_000)).isEqualTo(1);
    }

    @Test
    void aLossOrAZeroDayIsZero() {
        assertThat(SavingsService.suggested(-4_500, 5_000)).isZero();
        assertThat(SavingsService.suggested(0, 5_000)).isZero();
    }

    @Test
    void followsAChangedRate() {
        assertThat(SavingsService.suggested(10_000, 3_000)).isEqualTo(3_000);
        assertThat(SavingsService.suggested(10_000, 0)).isZero();
    }
}
