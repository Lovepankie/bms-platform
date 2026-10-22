package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** FR-CLN-05 without a database. */
class PromisesTest {

    static final LocalDate DATE = LocalDate.of(2026, 3, 10);

    @Test
    void keptAsSoonAsThePaymentsReachTheAmountEvenBeforeTheDate() {
        assertThat(Promises.settle(100_000, 100_000, DATE, DATE.minusDays(2))).isEqualTo("kept");
        assertThat(Promises.settle(150_000, 100_000, DATE, DATE.plusDays(5))).isEqualTo("kept");
    }

    @Test
    void openUntilTheDayAfterTheDateThenBroken() {
        assertThat(Promises.settle(99_999, 100_000, DATE, DATE)).isNull();
        assertThat(Promises.settle(99_999, 100_000, DATE, DATE.plusDays(1))).isEqualTo("broken");
    }
}
