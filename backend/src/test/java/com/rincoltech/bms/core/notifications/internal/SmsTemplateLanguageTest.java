package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Event;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Problem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The placeholder language of a tenant's own SMS wording (FR-NTF-03, spec 4.10): what it accepts, every refusal with its
 * code, that rendering is one plain pass that never executes or re-reads a value, and that the segment counts match the
 * encodings. Pure: no database, no clock. Fabricated names throughout.
 */
class SmsTemplateLanguageTest {

    static final Event DEPOSIT = SmsTemplateEvents.find("savings.deposit").orElseThrow();

    static final Map<String, String> VALUES = Map.of(
            "tenant_name", "Test Lender",
            "currency", "UGX",
            "amount", "5,000",
            "account_no", "SV000001",
            "receipt_no", "RC-1");

    static String code(String text) {
        return ((Problem) catchThrowable(() -> SmsTemplateLanguage.validate(DEPOSIT, text))).code();
    }

    static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected a Problem");
    }

    @Test
    void everyDefaultIsAValidTemplateOfItsOwnEventAndFitsOneSegmentWithSampleData() {
        assertThat(SmsTemplateEvents.ALL)
                .extracting(Event::key)
                .containsExactly("savings.deposit", "savings.withdrawal");
        for (Event event : SmsTemplateEvents.ALL) {
            Set<String> used = SmsTemplateLanguage.validate(event, event.defaultText());
            // The language reads exactly the placeholders the default template of OutboxTemplates uses.
            assertThat(used).isEqualTo(OutboxTemplates.placeholders("sms", event.key()));
            String sample = SmsTemplateLanguage.render(
                    event, event.defaultText(), SmsTemplateLanguage.sampleValues(event, null));
            assertThat(sample).doesNotContain("{").doesNotContain("}");
            SmsSegments.Count count = SmsSegments.count(sample);
            assertThat(count.encoding()).isEqualTo(SmsSegments.Encoding.GSM7);
            assertThat(count.segments()).as(event.key()).isEqualTo(1);
            // And the worst case (every value at its longest, outside the SMS alphabet) stays within the limit.
            assertThat(SmsSegments.count(SmsTemplateLanguage.worstCase(event, event.defaultText()))
                            .segments())
                    .as(event.key())
                    .isLessThanOrEqualTo(SmsTemplateLanguage.MAX_WORST_SEGMENTS);
        }
    }

    @Test
    void renderingTheDefaultThroughTheLanguageGivesTheSameTextTheChannelSendsWithoutAnOverride() {
        OutboxTemplates templates = new OutboxTemplates();
        for (Event event : SmsTemplateEvents.ALL) {
            String viaLanguage = SmsTemplateLanguage.render(event, event.defaultText(), VALUES);
            String viaDefault = templates.render("sms", event.key(), VALUES).text();
            assertThat(viaLanguage).isEqualTo(viaDefault);
        }
    }

    @Test
    void anUnknownPlaceholderIsRefusedAtSaveWithItsCode() {
        // Acceptance (issue #218): saving {unknown} fails with unknown_placeholder.
        assertThat(code("Paid {unknown} on {account_no} {amount}")).isEqualTo("unknown_placeholder");
        // The balance is not offered to an override, even though a default can carry it.
        assertThat(code("Paid {amount} on {account_no}. Balance {balance}")).isEqualTo("unknown_placeholder");
        // Names are exact: no case folding, no expression, no path.
        assertThat(code("{Amount} {account_no}")).isEqualTo("malformed_placeholder");
        assertThat(code("{amount.length} {account_no}")).isEqualTo("malformed_placeholder");
        assertThat(code("{amount|upper} {account_no}")).isEqualTo("malformed_placeholder");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Paid {amount on {account_no}",
                "Paid amount} {account_no}",
                "Paid {{amount}} {account_no}",
                "Paid {} {amount} {account_no}",
                "Paid { amount } {account_no}",
                "Paid ${amount} {account_no}x}",
            })
    void aBraceThatIsNotAWholePlaceholderIsRefused(String text) {
        assertThat(code(text)).isEqualTo("malformed_placeholder");
    }

    @Test
    void thePlaceholdersAnEventNeedsAreRequired() {
        assertThat(code("Thank you for banking with us")).isEqualTo("missing_required_placeholder");
        assertThat(code("Paid {amount}")).isEqualTo("missing_required_placeholder");
        assertThat(code("On {account_no}")).isEqualTo("missing_required_placeholder");
        assertThat(SmsTemplateLanguage.validate(DEPOSIT, "Paid {amount} on {account_no}"))
                .containsExactlyInAnyOrder("amount", "account_no");
    }

    @Test
    void blankLongInvisibleAndControlCharacterTextsAreRefused() {
        assertThat(code("   ")).isEqualTo("blank");
        assertThat(code("")).isEqualTo("blank");
        assertThat(code("{amount} {account_no} " + "x".repeat(SmsTemplateLanguage.MAX_CHARS)))
                .isEqualTo("too_long");
        // Zero width space, right-to-left override, byte order mark, a bell, a tab, a carriage return.
        for (String hidden : new String[] {"​", "‮", "﻿", "\u0007", "\t", "\r", " "}) {
            assertThat(code("{amount} {account_no}" + hidden + "ok"))
                    .as("U+%04X", (int) hidden.charAt(0))
                    .isEqualTo("invalid_character");
        }
        // A lone surrogate is not text.
        assertThat(code("{amount} {account_no}\ud800")).isEqualTo("invalid_character");
        // A line feed is allowed in a template.
        assertThat(SmsTemplateLanguage.validate(DEPOSIT, "Paid {amount}\non {account_no}"))
                .hasSize(2);
    }

    @Test
    void aTextThatCouldCostMoreThanFourSegmentsInTheWorstCaseIsRefused() {
        // 272 plain characters are two segments in the sample (GSM 7), but with a long name in another script the whole
        // message becomes UCS-2 and takes five: the worst case, not the sample, decides.
        String tooLong = "a".repeat(250) + " {amount} {account_no}";
        assertThat(SmsSegments.count(SmsTemplateLanguage.render(DEPOSIT, tooLong, VALUES))
                        .segments())
                .isEqualTo(2);
        assertThat(code(tooLong)).isEqualTo("too_many_segments");
        // A comfortable text is accepted: well within 4 segments even in UCS-2 at the longest values.
        String fine = "Deposit {amount} on {account_no}. Thank you.";
        assertThat(SmsTemplateLanguage.validate(DEPOSIT, fine)).hasSize(2);
        assertThat(SmsSegments.count(SmsTemplateLanguage.worstCase(DEPOSIT, fine))
                        .segments())
                .isLessThanOrEqualTo(SmsTemplateLanguage.MAX_WORST_SEGMENTS);
    }

    /** Escaping: an SMS is plain text. Nothing is HTML escaped and no value is read for placeholders again. */
    @Test
    void valuesAreInsertedAsPlainTextWithNoEscapingAndNoSecondPass() {
        Map<String, String> values = new LinkedHashMap<>(VALUES);
        for (String name : new String[] {
            "A & B <Sacco> \"Quoted\" 'Single'",
            "Zawadi Wetu Jumuiya",
            "مشروع الأمل",
            "幸福銀行",
            "Café Ñandú",
            "{amount} {unknown} $1 \\1 %s",
        }) {
            values.put("tenant_name", name);
            String out = SmsTemplateLanguage.render(DEPOSIT, "{tenant_name}: paid {amount} on {account_no}", values);
            // Inserted exactly as it is (the longest a name may be is 40 characters; none of these is longer).
            assertThat(out).isEqualTo(name + ": paid 5,000 on SV000001");
        }
        // Not escaped as markup, in particular.
        values.put("tenant_name", "A & B <Sacco>");
        assertThat(SmsTemplateLanguage.render(DEPOSIT, "{tenant_name} {amount} {account_no}", values))
                .contains("A & B <Sacco>")
                .doesNotContain("&amp;")
                .doesNotContain("&lt;");
    }

    @Test
    void aValueLosesItsLineBreaksAndInvisibleCharacters() {
        Map<String, String> values = new LinkedHashMap<>(VALUES);
        values.put("tenant_name", "Line one\nLine two\r\nLine\tthree​‮tail");
        String out = SmsTemplateLanguage.render(DEPOSIT, "{tenant_name}|{amount}|{account_no}", values);
        assertThat(out)
                .doesNotContain("\n")
                .doesNotContain("\r")
                .doesNotContain("\t")
                .doesNotContain("​");
        assertThat(out).startsWith("Line one Line two  Line three").contains("tail|5,000|SV000001");
    }

    /** Review finding 4: a value is never shortened. A longer one refuses the render, so the default text is sent instead. */
    @Test
    void aValueOverItsLimitRefusesTheRenderAndIsNeverCutShort() {
        Map<String, String> values = new LinkedHashMap<>(VALUES);
        String template = "{tenant_name}|{amount}|{account_no}";
        // At the limit (100 units for a name) it is inserted whole; one over is refused.
        values.put("tenant_name", "x".repeat(100));
        assertThat(SmsTemplateLanguage.render(DEPOSIT, template, values))
                .isEqualTo("x".repeat(100) + "|5,000|SV000001");
        values.put("tenant_name", "x".repeat(101));
        assertThat(codeOf(() -> SmsTemplateLanguage.render(DEPOSIT, template, values)))
                .isEqualTo("value_too_long");
        // An amount is the figure that matters most: 24 characters fit, 25 do not, and no digit is ever dropped.
        values.put("tenant_name", "Test Lender");
        values.put("amount", "1".repeat(24));
        assertThat(SmsTemplateLanguage.render(DEPOSIT, template, values)).contains("1".repeat(24) + "|");
        values.put("amount", "1".repeat(25));
        assertThat(codeOf(() -> SmsTemplateLanguage.render(DEPOSIT, template, values)))
                .isEqualTo("value_too_long");
    }

    /** Review finding 5: the limit is in UTF-16 units, as the segment counter counts, so an emoji (two units) cannot get past it. */
    @Test
    void theWorstCaseHoldsForAnEmojiNameBecauseTheLimitCountsUtf16Units() {
        Map<String, String> values = new LinkedHashMap<>(VALUES);
        String template = "{tenant_name}: paid {currency} {amount} on {account_no}. Ref {receipt_no}";
        // 50 emoji are 100 units: the limit, accepted, and not a segment more than the worst case says.
        values.put("tenant_name", "😀".repeat(50));
        String ok = SmsTemplateLanguage.render(DEPOSIT, template, values);
        assertThat(ok).startsWith("😀".repeat(50));
        assertThat(SmsSegments.count(ok).encoding()).isEqualTo(SmsSegments.Encoding.UCS2);
        assertThat(SmsSegments.count(ok).segments())
                .isLessThanOrEqualTo(SmsSegments.count(SmsTemplateLanguage.worstCase(DEPOSIT, template))
                        .segments())
                .isLessThanOrEqualTo(SmsTemplateLanguage.MAX_WORST_SEGMENTS);
        // 51 emoji are 102 units: refused. (By code points, 51 looks under 100; by units it is over.)
        values.put("tenant_name", "😀".repeat(51));
        assertThat(codeOf(() -> SmsTemplateLanguage.render(DEPOSIT, template, values)))
                .isEqualTo("value_too_long");
        // And the longest every placeholder may be, with an emoji name, fits the segments the default is validated for.
        for (Event event : SmsTemplateEvents.ALL) {
            Map<String, String> longest = new LinkedHashMap<>();
            event.placeholders().forEach(p -> longest.put(p.name(), "😀".repeat(p.maxLength() / 2)));
            String sent = SmsTemplateLanguage.render(event, event.defaultText(), longest);
            assertThat(SmsSegments.count(sent).segments())
                    .as(event.key())
                    .isLessThanOrEqualTo(SmsTemplateLanguage.MAX_WORST_SEGMENTS);
        }
    }

    private static String codeOf(Runnable render) {
        return ((Problem) catchThrowable(render)).code();
    }

    @Test
    void aMissingValueIsAnErrorNotAHalfFilledMessage() {
        Map<String, String> values = new LinkedHashMap<>(VALUES);
        values.remove("receipt_no");
        assertThatThrownBy(() -> SmsTemplateLanguage.render(DEPOSIT, "{amount} {account_no} {receipt_no}", values))
                .isInstanceOfSatisfying(Problem.class, p -> assertThat(p.code()).isEqualTo("missing_value"));
    }

    /** GSM 7 against UCS-2: one non-GSM character changes the encoding of the whole message. */
    @Test
    void theSegmentCountSwitchesToUcs2ForANonLatinName() {
        String text = "{tenant_name}: paid {amount} on {account_no}";
        String latin = SmsTemplateLanguage.render(DEPOSIT, text, VALUES);
        assertThat(SmsSegments.count(latin).encoding()).isEqualTo(SmsSegments.Encoding.GSM7);

        Map<String, String> arabic = new LinkedHashMap<>(VALUES);
        arabic.put("tenant_name", "مشروع الأمل");
        SmsSegments.Count count = SmsSegments.count(SmsTemplateLanguage.render(DEPOSIT, text, arabic));
        assertThat(count.encoding()).isEqualTo(SmsSegments.Encoding.UCS2);
        assertThat(count.segments()).isEqualTo(1);

        // The same words that fit one GSM segment (160) need two in UCS-2 once a name brings a non-Latin letter.
        arabic.put("tenant_name", "م".repeat(40));
        arabic.put("amount", "123,456,789");
        String longer =
                SmsTemplateLanguage.render(DEPOSIT, "{tenant_name} {tenant_name} {amount} {account_no}", arabic);
        assertThat(SmsSegments.count(longer).encoding()).isEqualTo(SmsSegments.Encoding.UCS2);
        assertThat(SmsSegments.count(longer).segments()).isEqualTo(2);
    }
}
