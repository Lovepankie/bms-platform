package com.rincoltech.bms.core.referrals.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The CSV cells of the operator's export (FR-REF-08): formula characters neutralised, numbers left as numbers. */
class ReferralServiceTest {

    @Test
    void aTextStartingWithAFormulaCharacterIsNeutralised() {
        assertThat(ReferralService.cell("=SUM(A1)")).isEqualTo("\"'=SUM(A1)\"");
        assertThat(ReferralService.cell("+256700000000")).isEqualTo("\"'+256700000000\"");
        assertThat(ReferralService.cell("-1+1")).isEqualTo("\"'-1+1\"");
        assertThat(ReferralService.cell("@cmd")).isEqualTo("\"'@cmd\"");
        assertThat(ReferralService.cell("\tTest Shop")).isEqualTo("\"'\tTest Shop\"");
        assertThat(ReferralService.cell("\rTest Shop")).isEqualTo("\"'\rTest Shop\"");
    }

    @Test
    void plainTextAndNumbersAreWrittenAsTheyAre() {
        assertThat(ReferralService.cell(null)).isEmpty();
        assertThat(ReferralService.cell("Test Shop")).isEqualTo("Test Shop");
        assertThat(ReferralService.cell("Shop, Kampala")).isEqualTo("\"Shop, Kampala\"");
        assertThat(ReferralService.cell("Say \"hi\"")).isEqualTo("\"Say \"\"hi\"\"\"");
        assertThat(ReferralService.cell(-1500L)).isEqualTo("-1500");
        assertThat(ReferralService.cell(900L)).isEqualTo("900");
    }
}
