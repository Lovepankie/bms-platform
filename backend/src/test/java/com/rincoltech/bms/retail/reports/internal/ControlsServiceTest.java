package com.rincoltech.bms.retail.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The arithmetic of the Controls page (issue #357; ADR-047): rates, shares and the "more than twice the shop" rule. */
class ControlsServiceTest {

    @Test
    void aRateIsPer100OneDecimalHalfUpAndAbsentWithoutSales() {
        assertThat(ControlsService.rate(6, 48)).isEqualTo("12.5");
        assertThat(ControlsService.rate(1, 30)).isEqualTo("3.3");
        assertThat(ControlsService.rate(2, 3)).isEqualTo("66.7");
        assertThat(ControlsService.rate(0, 10)).isEqualTo("0.0");
        assertThat(ControlsService.rate(5, 0)).isNull();
    }

    @Test
    void aShareIsInBasisPointsAndAHugeValueIsCappedNotRefused() {
        assertThat(ControlsService.shareBp(125, 10_000)).isEqualTo(125);
        assertThat(ControlsService.shareBp(1, 0)).isNull();
        // A voided value is not part of the completed total, so it can be many times it: the summary still answers.
        assertThat(ControlsService.shareBp(Long.MAX_VALUE, 1)).isEqualTo(Integer.MAX_VALUE);
        assertThat(ControlsService.shareBp(10_000_000_000_000L, 1_000)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aPatternNeedsEnoughCasesEnoughSalesAndMoreThanTwiceTheShopsRate() {
        // 5 of 13 against 6 of 43: 2.8 times.
        assertThat(ControlsService.flagged(5, 13, 6, 43)).isTrue();
        assertThat(ControlsService.times(5, 13, 6, 43)).isEqualTo("2.8");
        // Exactly twice is not more than twice: 3 of 15 against 10 of 100 is 2.0 times; one more case is.
        assertThat(ControlsService.flagged(3, 15, 10, 100)).isFalse();
        assertThat(ControlsService.flagged(4, 15, 10, 100)).isTrue();
        // Too few cases, or too few sales, never shout.
        assertThat(ControlsService.flagged(2, 12, 2, 100)).isFalse();
        assertThat(ControlsService.flagged(3, 9, 3, 100)).isFalse();
        // A shop with nothing to compare with flags nobody.
        assertThat(ControlsService.flagged(5, 13, 0, 43)).isFalse();
        assertThat(ControlsService.flagged(5, 13, 6, 0)).isFalse();
    }

    @Test
    void theGroupingChainTakesTheKnownLevelsEachOnce() {
        assertThat(ControlsService.chain("branch,user,day,reason,approver"))
                .containsExactly("branch", "user", "day", "reason", "approver");
    }
}
