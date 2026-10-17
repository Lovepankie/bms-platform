package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.Facts;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Extraction;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Sign;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Token;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Unit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the extractor reads as a stated number (spec 5.6): the forms, the readings of an ambiguous separator, the sign,
 * the unit, and what it sets aside. These are the extractor's own rules; whether a number is derivable from a fact is the
 * validator's, in {@code NumericValidatorTest}.
 */
class NumberExtractorTest {

    static final Set<LocalDate> DATES = Set.of(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 1));

    static Extraction extract(String text) {
        return NumberExtractor.extract(Text.normalise(text), DATES, Aliases.none(), List.of());
    }

    static List<BigDecimal> values(String text) {
        return extract(text).tokens().stream()
                .flatMap(t -> t.values().stream().map(v -> v.multiply(t.magnitude())))
                .toList();
    }

    static Token only(String text) {
        List<Token> tokens = extract(text).tokens();
        assertThat(tokens).as(text).hasSize(1);
        return tokens.get(0);
    }

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void separatorsAreReadAsThousandsOrDecimalsByTheirShape() {
        assertThat(only("1,200,000").values()).containsExactly(d("1200000"));
        assertThat(only("1,200,000.50").values()).containsExactly(d("1200000.50"));
        assertThat(only("1 200 000").values()).containsExactly(d("1200000"));
        assertThat(only("1.200.000").values()).containsExactly(d("1200000"));
        assertThat(only("1.200.000,5").values()).containsExactly(d("1200000.5"));
        assertThat(only("1200000").values()).containsExactly(d("1200000"));
        assertThat(only("12.5").values()).containsExactly(d("12.5"));
        assertThat(only("12,5").values()).containsExactly(d("12.5"));
        assertThat(only(".5").values()).containsExactly(d("0.5"));
        assertThat(only("0.40").values().get(0).scale()).isEqualTo(2);
    }

    @Test
    void aSingleDotAndThreeDigitsHasTwoReadings() {
        // 1.200 is 1.2 in English and 1,200 in much of Europe: both are offered and either may match a fact.
        assertThat(only("1.200").values()).containsExactly(d("1.200"), d("1200"));
        assertThat(only("12.345").values()).containsExactly(d("12.345"), d("12345"));
        // Two dots is only ever thousands; one dot and two digits only ever a decimal.
        assertThat(only("1.234.567").values()).containsExactly(d("1234567"));
        assertThat(only("1.25").values()).containsExactly(d("1.25"));
    }

    @Test
    void compactFormsCarryTheirFactor() {
        assertThat(values("1.2m")).containsExactly(d("1200000.0"));
        assertThat(values("1.2M")).containsExactly(d("1200000.0"));
        assertThat(values("UGX 1.2 million")).containsExactly(d("1200000.0"));
        assertThat(values("500k")).containsExactly(d("500000"));
        assertThat(values("2 thousand")).containsExactly(d("2000"));
        assertThat(values("3bn")).containsExactly(d("3000000000"));
        assertThat(values("3 billion")).containsExactly(d("3000000000"));
        assertThat(only("1.2m").compact()).isTrue();
        assertThat(only("1200000").compact()).isFalse();
        // A letter after the number that begins a word is not a factor.
        assertThat(only("5 months").compact()).isFalse();
        assertThat(only("12 kilometres").compact()).isFalse();
        assertThat(only("3 bps").unit()).isEqualTo(Unit.BASIS_POINTS);
        assertThat(only("3 bps").compact()).isFalse();
    }

    @Test
    void unitsAreReadFromBothSidesOfTheNumber() {
        assertThat(only("12.5%").unit()).isEqualTo(Unit.PERCENT);
        assertThat(only("12.5 percent").unit()).isEqualTo(Unit.PERCENT);
        assertThat(only("12.5 per cent").unit()).isEqualTo(Unit.PERCENT);
        assertThat(only("0.4 percentage points").unit()).isEqualTo(Unit.PERCENT);
        assertThat(only("0.4 points").unit()).isEqualTo(Unit.PERCENT);
        assertThat(only("40 bp").unit()).isEqualTo(Unit.BASIS_POINTS);
        assertThat(only("40 basis points").unit()).isEqualTo(Unit.BASIS_POINTS);
        assertThat(only("UGX 1,000").unit()).isEqualTo(Unit.MONEY);
        assertThat(only("UGX 1,000").currency()).isEqualTo("UGX");
        assertThat(only("UGX1000").unit()).isEqualTo(Unit.MONEY);
        assertThat(only("1,000 UGX").currency()).isEqualTo("UGX");
        assertThat(only("1,000 shillings").currency()).isEqualTo("SHILLING");
        assertThat(only("Shs 1,000").currency()).isEqualTo("UGX");
        assertThat(only("KES 1,000").currency()).isEqualTo("KES");
        assertThat(only("$1,000").currency()).isEqualTo("USD");
        assertThat(only("12 days").unit()).isEqualTo(Unit.DAYS);
        assertThat(only("1 day").unit()).isEqualTo(Unit.DAYS);
        assertThat(only("5 hours").unit()).isEqualTo(Unit.HOURS);
        assertThat(only("312 loans").unit()).isEqualTo(Unit.COUNT);
        assertThat(only("312 active loans").unit()).isEqualTo(Unit.COUNT);
        assertThat(only("312").unit()).isEqualTo(Unit.NONE);
        assertThat(only("1.2m UGX").currency()).isEqualTo("UGX");
        assertThat(only("1.2 million shillings").unit()).isEqualTo(Unit.MONEY);
    }

    @Test
    void aSignIsOnlyASignWhenItStandsAtTheStartOfTheNumber() {
        assertThat(only("-3.2").sign()).isEqualTo(Sign.MINUS);
        assertThat(only("+3.2").sign()).isEqualTo(Sign.PLUS);
        assertThat(only("fell -3.2 points").sign()).isEqualTo(Sign.MINUS);
        assertThat(only("(-3.2)").sign()).isEqualTo(Sign.MINUS);
        assertThat(only("-UGX 500").sign()).isEqualTo(Sign.MINUS);
        assertThat(only("UGX -500").sign()).isEqualTo(Sign.MINUS);
        assertThat(only("+UGX 500").sign()).isEqualTo(Sign.PLUS);
        assertThat(only(Facts.cp(0x2212) + "3.2").sign()).isEqualTo(Sign.MINUS);
        // A hyphen between two numbers is a range, a spaced hyphen a dash: neither is a sign.
        List<Token> range = extract("between 5-10 days").tokens();
        assertThat(range).extracting(Token::sign).containsExactly(Sign.NONE, Sign.NONE);
        assertThat(extract("PAR 30 - 3.2 points").tokens())
                .extracting(Token::sign)
                .containsExactly(Sign.NONE);
        assertThat(only("3.2").sign()).isEqualTo(Sign.NONE);
    }

    @Test
    void theNormalisationTurnsMinusSignsAndDashesIntoAHyphenAndDigitsIntoAscii() {
        assertThat(Text.normalise(Facts.cp(0x2212) + "3")).isEqualTo("-3");
        assertThat(Text.normalise(Facts.cp(0x2013) + "3")).isEqualTo("-3");
        assertThat(Text.normalise(Facts.cp(0x661, 0x662, 0x66C, 0x663, 0x664, 0x665)))
                .isEqualTo("12,345");
        assertThat(Text.normalise(Facts.cp(0xFF11, 0xFF12))).isEqualTo("12");
        assertThat(Text.normalise("1" + Facts.cp(0x200B) + "2" + Facts.cp(0x200D) + "3" + Facts.cp(0xAD) + "4"
                        + Facts.cp(0xFEFF) + "5"))
                .isEqualTo("12345");
        assertThat(Text.normalise("1" + Facts.cp(0xA0) + "200" + Facts.cp(0x202F) + "000"))
                .isEqualTo("1 200 000");
        assertThat(Text.display("1" + Facts.cp(0x200B) + "2 " + Facts.cp(0x663)))
                .isEqualTo("12 3");
        assertThat(Text.display("  x  ")).isEqualTo("x");
    }

    @Test
    void numberWordsBecomeNumbers() {
        assertThat(values("twenty-five")).containsExactly(d("25"));
        assertThat(values("twenty five")).containsExactly(d("25"));
        assertThat(values("one hundred and twenty")).containsExactly(d("120"));
        assertThat(values("two thousand five hundred")).containsExactly(d("2500"));
        assertThat(values("two million")).containsExactly(d("2000000"));
        assertThat(values("a hundred loans")).containsExactly(d("100"));
        assertThat(values("a thousand loans")).containsExactly(d("1000"));
        assertThat(values("three dozen")).containsExactly(d("36"));
        assertThat(values("zero")).containsExactly(d("0"));
        assertThat(values("nineteen and ninety")).containsExactly(d("19"), d("90"));
        assertThat(values("nine ten")).containsExactly(d("9"), d("10"));
        assertThat(values("one million two hundred and fifty thousand")).containsExactly(d("1250000"));
        assertThat(values("two thousand and five")).containsExactly(d("2005"));
        assertThat(values("thirteen hundred")).containsExactly(d("1300"));
        assertThat(only("twenty percent").unit()).isEqualTo(Unit.PERCENT);
        assertThat(only("UGX two million").currency()).isEqualTo("UGX");
        // Not number words: letters inside other words.
        assertThat(extract("someone stonewalled tension").tokens()).isEmpty();
        assertThat(extract("Tenant seventhly").tokens()).isEmpty();
    }

    @Test
    void oneIsANumberOnlyBeforeSomethingCounted() {
        assertThat(values("one loan")).containsExactly(d("1"));
        assertThat(values("one new member")).containsExactly(d("1"));
        assertThat(values("one branch")).containsExactly(d("1"));
        assertThat(extract("no one knows").tokens()).isEmpty();
        assertThat(extract("one of the branches").tokens()).isEmpty();
        assertThat(extract("the one with arrears").tokens()).isEmpty();
        assertThat(values("one hundred")).containsExactly(d("100"));
    }

    @Test
    void fractionsAndMultipliersAreTokensOfTheirOwn() {
        Token half = only("half of the loans");
        assertThat(half.fraction()).isTrue();
        assertThat(half.values().get(0)).isEqualByComparingTo("0.5");
        assertThat(only("a third").values().get(0)).isEqualByComparingTo("0.333333");
        assertThat(only("two thirds").values().get(0)).isEqualByComparingTo("0.666667");
        assertThat(only("three quarters").values().get(0)).isEqualByComparingTo("0.75");
        assertThat(only("a quarter").values().get(0)).isEqualByComparingTo("0.25");
        // Without "a" or a number a quarter or a third is a period or an ordinal.
        assertThat(extract("this quarter").tokens()).isEmpty();
        assertThat(extract("the third branch").tokens()).isEmpty();
        assertThat(only("doubled").multiplier()).isTrue();
        assertThat(only("doubled").values().get(0)).isEqualByComparingTo("2");
        assertThat(only("tripled").values().get(0)).isEqualByComparingTo("3");
        assertThat(only("twice").values().get(0)).isEqualByComparingTo("2");
    }

    @Test
    void whatIsSetAsideIsNeverAFigure() {
        // List markers and ordinals.
        assertThat(extract("1. First point").tokens()).isEmpty();
        assertThat(extract("(2) Second point").tokens()).isEmpty();
        assertThat(extract("12) Twelfth point").tokens()).isEmpty();
        assertThat(extract("the 3rd and 21st and 4th").tokens()).isEmpty();
        // Codes glued to letters.
        assertThat(extract("Q3 and B2B and P30").tokens()).isEmpty();
        // PAR names.
        assertThat(extract("PAR 1, PAR 30, PAR 60, PAR 90, PAR30, par-90").tokens())
                .isEmpty();
        assertThat(values("PAR 45")).containsExactly(d("45"));
        // Dates the answer may name, in every form; others stay as numbers.
        assertThat(extract("on 14 October 2026").tokens()).isEmpty();
        assertThat(extract("on 2026-10-14 and 1/10/2026 and Oct 1st").tokens()).isEmpty();
        assertThat(values("on 15 October")).containsExactly(d("15"));
        assertThat(values("on 2026-10-15")).containsExactly(d("2026"), d("10"), d("15"));
    }

    @Test
    void aYearOfAnAllowedDateIsMarkedYearLike() {
        assertThat(only("in 2026").yearLike()).isTrue();
        assertThat(only("in 2025").yearLike()).isFalse();
        assertThat(only("in 2026%").yearLike()).isFalse();
        assertThat(only("2,026").yearLike()).isFalse();
    }

    @Test
    void aliasesAndLabelsAreSetAsideAndUnknownAliasesAreReported() {
        Aliases aliases = Aliases.of(Facts.standard());
        Extraction e = NumberExtractor.extract(
                Text.normalise("Branch 1 and Product A beat Branch 3, Product B and Branch 12 on PAR 30"),
                DATES,
                aliases,
                List.of("PAR 30"));
        assertThat(e.tokens()).isEmpty();
        assertThat(e.unknownAliases()).containsExactly("Branch 3", "Product B", "Branch 12");
        // A label of a fact may carry digits and is not a figure.
        Extraction labelled = NumberExtractor.extract(
                Text.normalise("Expected next 7 days (estimate) is 5"),
                DATES,
                Aliases.none(),
                List.of("Expected next 7 days (estimate)"));
        assertThat(labelled.tokens()).hasSize(1);
        assertThat(labelled.tokens().get(0).values()).containsExactly(d("5"));
    }

    @Test
    void positionsAndNeighbouringWordsDoNotMergeSeparateNumbers() {
        assertThat(values("5 loans and 100 members")).containsExactly(d("5"), d("100"));
        assertThat(values("12.5% and 0.4 points")).containsExactly(d("12.5"), d("0.4"));
        assertThat(values("1,250,000 and 312")).containsExactly(d("1250000"), d("312"));
        assertThat(values("from 100 to 200")).containsExactly(d("100"), d("200"));
        assertThat(values("a, 5,6")).containsExactly(d("5.6"));
    }
}
