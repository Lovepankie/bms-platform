package com.rincoltech.bms.retail.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The barcode formats of ADR-070 (FR-RET-68): shapes, check digits and the lookup key. Fabricated codes only. */
class BarcodesTest {

    @Test
    void frRet68AnEan13WithItsCheckDigitIsAcceptedAndAWrongOneRefused() {
        assertThat(Barcodes.check("4006381333931", null).checked().format()).isEqualTo(Barcodes.Format.EAN13);
        assertThat(Barcodes.check(" 4006381333931 ", null).checked().code()).isEqualTo("4006381333931");
        Barcodes.Result wrong = Barcodes.check("4006381333932", null);
        assertThat(wrong.ok()).isFalse();
        assertThat(wrong.problem()).contains("last digit");
    }

    @Test
    void frRet68AnEan8IsCheckedToo() {
        assertThat(Barcodes.check("96385074", null).checked().format()).isEqualTo(Barcodes.Format.EAN8);
        assertThat(Barcodes.check("96385075", null).ok()).isFalse();
    }

    @Test
    void frRet68AUpcAIsCheckedAndKeyedAsTheEan13AScannerMayReport() {
        assertThat(Barcodes.check("036000291452", null).checked().format()).isEqualTo(Barcodes.Format.UPCA);
        assertThat(Barcodes.check("036000291453", null).ok()).isFalse();
        assertThat(Barcodes.key("036000291452")).isEqualTo(Barcodes.key("0036000291452"));
    }

    @Test
    void frRet68Code128TakesPrintableTextWithoutACheckDigitInTheText() {
        assertThat(Barcodes.check("TEST-ITEM-01", null).checked().format()).isEqualTo(Barcodes.Format.CODE128);
        assertThat(Barcodes.check("12345", null).checked().format()).isEqualTo(Barcodes.Format.CODE128);
        // Asked for by name, thirteen digits are Code 128 and their last digit is not a check digit.
        assertThat(Barcodes.check("4006381333932", "code128").ok()).isTrue();
        assertThat(Barcodes.check("with space", null).ok()).isFalse();
        assertThat(Barcodes.check("A/B", null).ok()).isFalse();
        assertThat(Barcodes.check("x".repeat(49), null).ok()).isFalse();
        assertThat(Barcodes.check("", null).ok()).isFalse();
    }

    @Test
    void frRet68AFormatAskedForByNameMustFitIt() {
        assertThat(Barcodes.check("12345", "ean13").problem()).contains("13 digits");
        assertThat(Barcodes.check("4006381333931", "qr").ok()).isFalse();
    }

    @Test
    void generatedCodesAreCode128WithTheInternalPrefix() {
        assertThat(Barcodes.generated(42)).isEqualTo("IN00000042");
        assertThat(Barcodes.inferred(Barcodes.generated(42))).isEqualTo(Barcodes.Format.CODE128);
    }
}
