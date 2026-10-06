package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The brand colour rule of FR-TEN-08; the frontend's contrast.test.ts holds the same reference values. */
class BrandColourTest {

    @Test
    void blackOnWhiteIs21AndTheLuminancesAreTheWcagOnes() {
        assertThat(BrandColour.luminance("#FFFFFF")).isCloseTo(1.0, within(1e-9));
        assertThat(BrandColour.luminance("#000000")).isCloseTo(0.0, within(1e-9));
        assertThat(BrandColour.contrast("#000000", "#FFFFFF")).isCloseTo(21.0, within(1e-9));
        assertThat(BrandColour.contrast("#FFFFFF", "#FFFFFF")).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void knownReferenceRatios() {
        // #767676 is the lightest grey that reaches 4.5 on white (4.54); #777777 is just under (4.48).
        assertThat(BrandColour.contrast("#767676", "#FFFFFF")).isCloseTo(4.54, within(0.01));
        assertThat(BrandColour.contrast("#777777", "#FFFFFF")).isCloseTo(4.48, within(0.01));
    }

    @Test
    void theBetterTextColourIsChosen() {
        assertThat(BrandColour.readableText("#0D5C75")).contains("#FFFFFF");
        assertThat(BrandColour.readableText("#FFD54F")).contains("#111111");
        assertThat(BrandColour.readableText("#ffffff")).contains("#111111");
        assertThat(BrandColour.readableText("#000000")).contains("#FFFFFF");
    }

    @Test
    void aColourWithNoReadableTextIsRefused() {
        // A narrow band of mid greys: under 4.5 against white and against near-black alike.
        assertThat(BrandColour.readableText("#777777")).isEmpty();
        assertThat(BrandColour.readableText("#7A7A7A")).isEmpty();
        assertThat(BrandColour.readableText("#808080")).contains("#111111");
    }

    @Test
    void onlySixDigitHexIsAColour() {
        for (String bad :
                new String[] {null, "", "red", "#FFF", "0D5C75", "#0D5C7", "#0D5C75F", "#GGGGGG", " #0D5C75"}) {
            assertThat(BrandColour.readableText(bad)).as("%s", bad).isEmpty();
        }
    }
}
