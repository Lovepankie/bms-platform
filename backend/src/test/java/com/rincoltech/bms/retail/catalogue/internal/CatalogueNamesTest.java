package com.rincoltech.bms.retail.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The edit distance and the allowance of the near-duplicate warning (issue #352). */
class CatalogueNamesTest {

    @Test
    void editDistanceCountsSingleLetterChanges() {
        assertThat(CatalogueService.editDistance("double socket 13a", "double soket 13a"))
                .isEqualTo(1);
        assertThat(CatalogueService.editDistance("abc", "")).isEqualTo(3);
        assertThat(CatalogueService.editDistance("", "abc")).isEqualTo(3);
        assertThat(CatalogueService.editDistance("kitten", "sitting")).isEqualTo(3);
        assertThat(CatalogueService.editDistance("same", "same")).isZero();
    }

    @Test
    void aShortNameAllowsNoDifferenceAndALongOneAllowsAFew() {
        assertThat(CatalogueService.allowedDistance("nail", "nails")).isEqualTo(1);
        assertThat(CatalogueService.allowedDistance("tap", "top")).isZero();
        assertThat(CatalogueService.allowedDistance("double socket", "double soket"))
                .isEqualTo(2);
        assertThat(CatalogueService.allowedDistance("double socket 13a white", "double soket 13a white"))
                .isEqualTo(3);
    }
}
