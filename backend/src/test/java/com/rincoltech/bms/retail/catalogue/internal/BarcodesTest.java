package com.rincoltech.bms.retail.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
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
        assertThat(Barcodes.key("036000291452", null)).isEqualTo(Barcodes.key("0036000291452", null));
        assertThat(Barcodes.scanKeys("036000291452")).containsExactly("0036000291452", "036000291452");
    }

    @Test
    void frRet68TwelveDigitsInCode128KeepTheirOwnKey() {
        assertThat(Barcodes.key("036000291452", "code128")).isEqualTo("036000291452");
        assertThat(Barcodes.key(new Barcodes.Checked("036000291452", Barcodes.Format.CODE128)))
                .isEqualTo("036000291452");
    }

    @Test
    void frRet68Code128TakesPrintableTextWithoutACheckDigitInTheText() {
        assertThat(Barcodes.check("TEST-ITEM-01", null).checked().format()).isEqualTo(Barcodes.Format.CODE128);
        assertThat(Barcodes.check("12345", null).checked().format()).isEqualTo(Barcodes.Format.CODE128);
        // Asked for by name, thirteen digits are Code 128 and their last digit is not a check digit.
        assertThat(Barcodes.check("4006381333932", "code128").ok()).isTrue();
        assertThat(Barcodes.check("with space", null).ok()).isFalse();
        for (String dots : new String[] {".", "..", "..."}) {
            assertThat(Barcodes.check(dots, "code128").problem()).contains("only of dots");
        }
        assertThat(Barcodes.check("A.B", null).ok()).isTrue();
        for (String sign : new String[] {"/", "\\", "%", ";", "?", "#"}) {
            assertThat(Barcodes.check("A" + sign + "B", null).problem())
                    .as(sign)
                    .contains("cannot hold");
            assertThat(Barcodes.check("A" + sign + "B", "code128").ok())
                    .as(sign)
                    .isFalse();
        }
        assertThat(Barcodes.check("x".repeat(49), null).ok()).isFalse();
        assertThat(Barcodes.check("", null).ok()).isFalse();
    }

    @Test
    void frRet68AFormatAskedForByNameMustFitIt() {
        assertThat(Barcodes.check("12345", "ean13").problem()).contains("13 digits");
        assertThat(Barcodes.check("4006381333931", "qr").ok()).isFalse();
    }

    @Test
    void frRet68ACodeASpreadsheetMayHaveDamagedIsRefusedUnlessCode128IsAskedFor() {
        for (String code : new String[] {
            "123456", "1234567", "123456789", "1234567890", "36000291452", "12345678901231", "4.00638E+12", "4,00638e12"
        }) {
            Barcodes.Result r = Barcodes.check(code, null);
            assertThat(r.ok()).as(code).isFalse();
            assertThat(r.problem())
                    .as(code)
                    .contains("the spreadsheet may have dropped a leading zero or turned the code into a number");
            assertThat(Barcodes.check(code, "code128").ok()).as(code).isTrue();
        }
        // Lengths a barcode format has, short codes and letters are not suspected.
        assertThat(Barcodes.check("12345", null).ok()).isTrue();
        assertThat(Barcodes.check("TEST-E12", null).ok()).isTrue();
        // Scientific notation from a spreadsheet has a decimal separator; a code like 12E34 is a real Code 128.
        assertThat(Barcodes.check("12E34", null).checked().format()).isEqualTo(Barcodes.Format.CODE128);
        assertThat(Barcodes.check("4006381333931", null).ok()).isTrue();
    }

    @Test
    void issue524AMadeCodeIsAnEan13InTheRestrictedRangeWithItsCheckDigit() {
        assertThat(Barcodes.generated(42)).isEqualTo("2100000000425");
        assertThat(Barcodes.generated(1)).isEqualTo("2100000000012");
        assertThat(Barcodes.generated(999_999_999)).startsWith("210999999999").hasSize(13);
        for (long n : new long[] {1, 7, 42, 123_456_789, 999_999_999}) {
            String code = Barcodes.generated(n);
            assertThat(code).matches("210\\d{10}");
            assertThat(Barcodes.checkDigitOk(code)).isTrue();
            Barcodes.Result read = Barcodes.check(code, null);
            assertThat(read.checked().format()).isEqualTo(Barcodes.Format.EAN13);
            assertThat(Barcodes.key(read.checked())).isEqualTo(code);
        }
    }

    @Test
    void issue524TheCheckDigitIsTheGs1One() {
        assertThat(Barcodes.checkDigit("400638133393")).isEqualTo(1);
        assertThat(Barcodes.checkDigit("03600029145")).isEqualTo(2);
        assertThat(Barcodes.checkDigit("9638507")).isEqualTo(4);
    }

    @Test
    void issue524TheNineDigitsNeverOverflowIntoAnotherCode() {
        assertThatThrownBy(() -> Barcodes.generated(1_000_000_000L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Barcodes.generated(0)).isInstanceOf(ApiException.class);
    }

    @Test
    void issue524TheInCodesOfBeforeStillReadAsCode128() {
        assertThat(Barcodes.check("IN00000042", null).checked().format()).isEqualTo(Barcodes.Format.CODE128);
        assertThat(Barcodes.scanKeys("IN00000042")).containsExactly("IN00000042");
    }
}
