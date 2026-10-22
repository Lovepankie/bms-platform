package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.notifications.internal.SmsSegments.Encoding;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pure parts of the SMS channel, golden tested (FR-NTF-02, FR-NTF-04, FR-NTF-15, spec section
 * 8): the segment counter, the cost parser, the sending window, and the default templates against
 * the one segment budget. No clock, no database, no network.
 */
class SmsPureTest {

    // ---- Segments ------------------------------------------------------------------------------

    @Test
    void gsm7FitsOneSegmentUpTo160AndThen153EachWithTheExtensionTableCostingTwo() {
        assertThat(SmsSegments.count("")).isEqualTo(new SmsSegments.Count(Encoding.GSM7, 0, 1));
        assertThat(SmsSegments.count("a".repeat(160))).isEqualTo(new SmsSegments.Count(Encoding.GSM7, 160, 1));
        assertThat(SmsSegments.count("a".repeat(161))).isEqualTo(new SmsSegments.Count(Encoding.GSM7, 161, 2));
        assertThat(SmsSegments.count("a".repeat(306))).isEqualTo(new SmsSegments.Count(Encoding.GSM7, 306, 2));
        assertThat(SmsSegments.count("a".repeat(307))).isEqualTo(new SmsSegments.Count(Encoding.GSM7, 307, 3));
        // The extension characters take two septets: 80 euro signs are 160 septets, 81 are 162.
        assertThat(SmsSegments.count("€".repeat(80))).isEqualTo(new SmsSegments.Count(Encoding.GSM7, 160, 1));
        assertThat(SmsSegments.count("€".repeat(81))).isEqualTo(new SmsSegments.Count(Encoding.GSM7, 162, 2));
        assertThat(SmsSegments.count("{[a]}|~^\\").units()).isEqualTo(2 * 8 + 1);
        // The default alphabet includes accents the table has and newlines.
        assertThat(SmsSegments.count("éèùüñ£$\n").encoding()).isEqualTo(Encoding.GSM7);
    }

    @Test
    void anyOtherCharacterSwitchesTheWholeBodyToUcs2AtSeventyAndSixtySeven() {
        // A curly quote is not in the GSM alphabet.
        assertThat(SmsSegments.count("It’s")).isEqualTo(new SmsSegments.Count(Encoding.UCS2, 4, 1));
        assertThat(SmsSegments.count("’" + "a".repeat(69))).isEqualTo(new SmsSegments.Count(Encoding.UCS2, 70, 1));
        assertThat(SmsSegments.count("’" + "a".repeat(70))).isEqualTo(new SmsSegments.Count(Encoding.UCS2, 71, 2));
        assertThat(SmsSegments.count("’" + "a".repeat(133))).isEqualTo(new SmsSegments.Count(Encoding.UCS2, 134, 2));
        assertThat(SmsSegments.count("’" + "a".repeat(134))).isEqualTo(new SmsSegments.Count(Encoding.UCS2, 135, 3));
        // An emoji is two UTF-16 code units.
        assertThat(SmsSegments.count("😀").units()).isEqualTo(2);
        // The escape code point itself is not a character a body may hold.
        assertThat(SmsSegments.count("a\u001bb").encoding()).isEqualTo(Encoding.UCS2);
    }

    /** Property: segments never decrease as a body grows, and the single segment limit is exact. */
    @Test
    void segmentsGrowMonotonicallyWithLength() {
        Random random = new Random(212);
        String alphabet = "abcXYZ 0123456789.,:-@€{}";
        StringBuilder body = new StringBuilder();
        int last = 1;
        for (int i = 0; i < 700; i++) {
            body.append(alphabet.charAt(random.nextInt(alphabet.length())));
            SmsSegments.Count count = SmsSegments.count(body.toString());
            assertThat(count.segments()).isGreaterThanOrEqualTo(last);
            assertThat(count.segments() > 1).isEqualTo(count.units() > 160);
            last = count.segments();
        }
    }

    @Test
    void everyDefaultTemplateFitsOneSegmentOrDeclaresTwo() {
        OutboxTemplates templates = new OutboxTemplates();
        // Fabricated values at a realistic length: a 30 character business name, a 9 digit amount.
        Map<String, String> params = Map.ofEntries(
                Map.entry("tenant_name", "Test Lender and Savings Society"),
                Map.entry("currency", "UGX"),
                Map.entry("amount", "123,456,789"),
                Map.entry("account_no", "SV000001"),
                Map.entry("receipt_no", "RC-HQ-000001"),
                Map.entry("balance", "987,654,321"),
                Map.entry("loan_no", "LN-HQ-000123"),
                Map.entry("date", "15/10/2026"),
                Map.entry("next_amount", "123,456,789"),
                Map.entry("next_due_date", "15/11/2026"),
                Map.entry("maturity_date", "15/10/2027"),
                Map.entry("voucher_no", "VC-HQ-000001"),
                Map.entry("due_date", "15/11/2026"),
                Map.entry("arrears", "123,456,789"),
                Map.entry("dpd", "365"));
        // The templates that may take two segments: the ones that carry a balance.
        for (String key : OutboxTemplates.keys("sms")) {
            int segments = SmsSegments.count(
                            templates.render("sms", key, params).text())
                    .segments();
            assertThat(segments)
                    .as("template %s", key)
                    .isLessThanOrEqualTo(key.endsWith(OutboxTemplates.WITH_BALANCE) ? 2 : 1);
        }
        assertThat(OutboxTemplates.keys("sms"))
                .contains("savings.deposit", "savings.deposit.with_balance", "savings.withdrawal");
        // The default carries no balance and no name; the variant is chosen by the parameter alone.
        String plain = templates
                .render(
                        "sms",
                        "savings.deposit",
                        Map.of(
                                "tenant_name", "Test Lender",
                                "currency", "UGX",
                                "amount", "5,000",
                                "account_no", "SV000001",
                                "receipt_no", "RC-1"))
                .text();
        assertThat(plain)
                .doesNotContain("Balance")
                .isEqualTo("Test Lender: Deposit of UGX 5,000 on account SV000001. Receipt RC-1.");
        assertThat(templates.render("sms", "savings.deposit", params).text()).contains("Balance UGX 987,654,321");
    }

    // ---- Cost ----------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "UGX 32.0000, UGX, 32",
        "UGX 32, UGX, 32",
        "UGX 31.5000, UGX, 32",
        "UGX 31.4999, UGX, 31",
        "UGX 0.0000, UGX, 0",
        "KES 0.8000, KES, 80",
        "KES 1.2345, KES, 123",
        "USD 0.0075, USD, 1",
        "UGX 1234567.0000, UGX, 1234567"
    })
    void theProvidersCostStringBecomesIntegerMinorUnits(String raw, String currency, long minor) {
        assertThat(SmsCost.parse(raw)).contains(new SmsCost.Cost(currency, minor));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "32",
                "UGX",
                "UGX -1.0",
                "UGX 1e3",
                "UGX 1,000",
                "ugx 32",
                "UGX  32",
                "XXX 5.0000",
                "UGX 99999999999999999999",
                "UGX 3.2.1"
            })
    void anythingElseIsKeptRawAndNotGuessed(String raw) {
        assertThat(SmsCost.parse(raw)).isEmpty();
    }

    @Test
    void aNullCostIsEmpty() {
        assertThat(SmsCost.parse(null)).isEmpty();
    }

    // ---- Window --------------------------------------------------------------------------------

    /** 2031-03-10 is a fixed date; Africa/Kampala is UTC+3 all year. */
    static Instant kampala(String time) {
        return Instant.parse("2031-03-10T" + time + ":00Z").minusSeconds(3 * 3600);
    }

    @Test
    void theDefaultWindowIsEightToTwentyInKampala() {
        SmsWindow w = SmsWindow.standard();
        assertThat(w.isOpen(kampala("07:59"))).isFalse();
        assertThat(w.isOpen(kampala("08:00"))).isTrue();
        assertThat(w.isOpen(kampala("19:59"))).isTrue();
        assertThat(w.isOpen(kampala("20:00"))).isFalse();
        // FR-NTF-04, tested with a clock at 21:00: it waits for 08:00 the next day.
        assertThat(w.nextOpen(kampala("21:00"))).isEqualTo(kampala("08:00").plusSeconds(86400));
        assertThat(w.nextOpen(kampala("05:00"))).isEqualTo(kampala("08:00"));
        assertThat(w.nextOpen(kampala("12:00"))).isEqualTo(kampala("12:00"));
        assertThat(w.nextDayStart(kampala("12:00"))).isEqualTo(kampala("08:00").plusSeconds(86400));
        // A reminder expires at the end of its window day (FR-NTF-17).
        assertThat(w.endOfDay(kampala("10:00"))).isEqualTo(kampala("20:00"));
    }

    @Test
    void aTenantsOwnWindowIsHonoured() {
        SmsWindow w = SmsWindow.of(LocalTime.of(9, 30), LocalTime.of(17, 0));
        assertThat(w.isOpen(kampala("09:29"))).isFalse();
        assertThat(w.isOpen(kampala("09:30"))).isTrue();
        assertThat(w.nextOpen(kampala("18:00"))).isEqualTo(kampala("09:30").plusSeconds(86400));
        // The day is the Kampala day, not the UTC day: 23:30 Kampala is 20:30 UTC the same date.
        assertThat(w.endOfDay(kampala("23:30"))).isEqualTo(kampala("17:00"));
    }

    @Test
    void smsAmountsUseTheCurrencyExponent() {
        assertThat(SmsEventsService.format(1_234_567, 0)).isEqualTo("1,234,567");
        assertThat(SmsEventsService.format(1_234_567, 2)).isEqualTo("12,345.67");
        assertThat(SmsEventsService.format(0, 2)).isEqualTo("0.00");
        assertThat(SmsEventsService.format(5, 2)).isEqualTo("0.05");
    }
}
