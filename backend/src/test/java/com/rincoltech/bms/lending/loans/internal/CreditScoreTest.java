package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.loans.internal.CreditScore.Inputs;
import com.rincoltech.bms.lending.loans.internal.CreditScore.Result;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Chapter 3 section 3.18.1, each case hand-computed from the rules with the default weights. */
class CreditScoreTest {

    static final Map<String, Integer> DEFAULT =
            Map.of("repayment_history", 40, "affordability", 30, "collateral_cover", 20, "exposure", 10);

    static int points(Result r, String component) {
        return (int) ((Map<?, ?>) r.components().get(component)).get("points");
    }

    /**
     * A first-time borrower: history 20 (no prior loans); q = (100,000 + 50,000) / 600,000 = 0.25,
     * so affordability 30; cover 1,000,000 / 500,000 = 2.0, so 20; exposure 10. Total 80, band A.
     */
    @Test
    void aFirstTimeBorrowerWithGoodCover() {
        Result r = CreditScore.score(
                new Inputs(0, 0, 0, 100_000, 600_000L, 50_000, 1_000_000, 500_000, false, false, 0, false, null),
                DEFAULT);
        assertThat(points(r, "repayment_history")).isEqualTo(20);
        assertThat(points(r, "affordability")).isEqualTo(30);
        assertThat(points(r, "collateral_cover")).isEqualTo(20);
        assertThat(points(r, "exposure")).isEqualTo(10);
        assertThat(r.score()).isEqualTo(80);
        assertThat(r.band()).isEqualTo("A");
        assertThat(r.recommendation()).isEqualTo("approve");
        assertThat(r.flags()).isEmpty();
    }

    /**
     * History: 3 of 4 closed loans on time = 30, minus 10 for one write-off = 20. q = 270,000 /
     * 600,000 = 0.45: 30 x (0.60 - 0.45) / 0.30 = 15. Cover 375,000 / 500,000 = 0.75: 20 x 0.75 /
     * 1.5 = 10. Exposure 10 - 5 (own loan in arrears) = 5. Total 50, band C, review.
     */
    @Test
    void aMixedHistoryLandsInBandC() {
        Result r = CreditScore.score(
                new Inputs(4, 3, 1, 220_000, 600_000L, 50_000, 375_000, 500_000, true, false, 1, false, 15_000),
                DEFAULT);
        assertThat(points(r, "repayment_history")).isEqualTo(20);
        assertThat(points(r, "affordability")).isEqualTo(15);
        assertThat(points(r, "collateral_cover")).isEqualTo(10);
        assertThat(points(r, "exposure")).isEqualTo(5);
        assertThat(r.score()).isEqualTo(50);
        assertThat(r.band()).isEqualTo("C");
        assertThat(r.recommendation()).isEqualTo("review");
        assertThat(r.flags())
                .containsExactly(
                        "COLLATERAL_BELOW_PRODUCT_MINIMUM", "EXISTING_LOAN_IN_ARREARS", "MULTIPLE_ACTIVE_LOANS");
    }

    /** No declared income: affordability 0 and the flag; no collateral; linked party over 30 DPD; new member. */
    @Test
    void noIncomeAndNoCollateralFallToBandD() {
        Result r = CreditScore.score(
                new Inputs(0, 0, 0, 100_000, null, 0, 0, 500_000, false, true, 0, true, null), DEFAULT);
        assertThat(points(r, "affordability")).isZero();
        assertThat(points(r, "collateral_cover")).isZero();
        assertThat(points(r, "exposure")).isEqualTo(5);
        assertThat(r.score()).isEqualTo(25);
        assertThat(r.band()).isEqualTo("D");
        assertThat(r.recommendation()).isEqualTo("decline");
        assertThat(r.flags()).containsExactly("INCOME_NOT_DECLARED", "LINKED_PARTY_IN_ARREARS", "NEW_MEMBER");
    }

    /** Write-offs cannot take history below zero; q at 0.60 or above scores nothing. */
    @Test
    void floorsHold() {
        Result r = CreditScore.score(
                new Inputs(1, 0, 3, 400_000, 600_000L, 0, 0, 500_000, true, true, 2, false, null), DEFAULT);
        assertThat(points(r, "repayment_history")).isZero();
        assertThat(points(r, "affordability")).isZero();
        assertThat(points(r, "exposure")).isZero();
        assertThat(r.score()).isZero();
    }

    /** Tenant weights set the maximum of each component. */
    @Test
    void weightsScaleTheComponents() {
        Map<String, Integer> weights =
                Map.of("repayment_history", 20, "affordability", 50, "collateral_cover", 20, "exposure", 10);
        Result r = CreditScore.score(
                new Inputs(0, 0, 0, 100_000, 600_000L, 50_000, 1_000_000, 500_000, false, false, 0, false, null),
                weights);
        assertThat(points(r, "repayment_history")).isEqualTo(10);
        assertThat(points(r, "affordability")).isEqualTo(50);
        assertThat(r.score()).isEqualTo(90);
    }

    /** The monthly conversions of section 3.18.1. */
    @Test
    void monthlyConversions() {
        assertThat(CreditScore.monthly(10_000, "instalments", "daily", "day", 30))
                .isEqualTo(300_000);
        assertThat(CreditScore.monthly(120_000, "instalments", "weekly", "week", 4))
                .isEqualTo(520_000);
        assertThat(CreditScore.monthly(120_000, "instalments", "fortnightly", "week", 4))
                .isEqualTo(260_000);
        assertThat(CreditScore.monthly(520_000, "instalments", "monthly", "month", 3))
                .isEqualTo(520_000);
        assertThat(CreditScore.monthly(600_000, "bullet", null, "month", 3)).isEqualTo(200_000);
        // A two-week bullet is under a month: divided by 1, not by a fraction.
        assertThat(CreditScore.monthly(600_000, "bullet", null, "week", 2)).isEqualTo(600_000);
    }
}
