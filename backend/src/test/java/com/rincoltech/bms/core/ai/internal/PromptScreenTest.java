package com.rincoltech.bms.core.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.ai.internal.PromptScreen.Finding;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The gateway's input screen (spec 5.4, 5.12): shapes that personal data takes are refused whatever the
 * caller meant, and the figures a brief really contains pass. Every number, name and address is invented.
 */
class PromptScreenTest {

    static Optional<Finding> check(String text) {
        return PromptScreen.check(text);
    }

    @Test
    void phoneNumbersInEverySpacingAreRefused() {
        for (String phone : List.of(
                "+256 700 000 001",
                "+256700000001",
                "0700000001",
                "0700-000-001",
                "0700 000 001",
                "07 00 00 00 01",
                "(0700) 000 001",
                "256 700 000 001",
                "+256 (700) 000-001",
                "+1 202 555 0143",
                "+44 20 7946 0958")) {
            assertThat(check("Call " + phone + " today")).as(phone).isPresent();
        }
    }

    @Test
    void aRunOfSixOrMoreDigitsIsRefused() {
        assertThat(check("account 123456")).contains(Finding.DIGITS);
        assertThat(check("amount 1250000")).contains(Finding.DIGITS);
        assertThat(check("account 12345")).isEmpty();
    }

    @Test
    void emailAddressesAreRefused() {
        assertThat(check("write to test.officer@example.test please")).contains(Finding.EMAIL);
        assertThat(check("a@b.co")).contains(Finding.EMAIL);
    }

    @Test
    void aNationalIdNumberShapeIsRefusedInAnyCase() {
        assertThat(check("NIN CM00000000ABCD")).contains(Finding.ID_NUMBER);
        assertThat(check("nin cf00000000abcd")).contains(Finding.ID_NUMBER);
        assertThat(check("CMTEST0000001A")).contains(Finding.ID_NUMBER);
    }

    @Test
    void theFiguresOfABriefPass() {
        for (String text : List.of(
                "Branch 1 PAR30 is 4.1 percent, up 0.6 from last week.",
                "Collections 1,250,000 UGX, 87.5 percent of the 1,430,000 due.",
                "As of 2026-10-14 08:00 the portfolio is 3,200,000,000 UGX.",
                "Product B: 12 loans, 4.1 then 3.2 then 2.8 then 1.9 then 1.2 percent.",
                "Fewer than 5 loans in Branch 2.",
                "Week 41 of 2026, day 287.")) {
            assertThat(check(text)).as(text).isEmpty();
        }
    }

    @Test
    void aNumberDressedUpInUnicodeIsStillRefused() {
        String nbsp = "\u00A0";
        String zwsp = "\u200B";
        for (String text : List.of(
                "0772" + nbsp + "123" + nbsp + "456",
                "0772" + zwsp + "123" + zwsp + "456",
                "07" + zwsp + "72123456",
                "\uFF10\uFF17\uFF17\uFF12\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16",
                "0772/123/456",
                "0772_123_456",
                "0772\t123\t456",
                "0772\u2009123\u2009456",
                "+256\u00A0772\u00A0123\u00A0456",
                "\uFF0B256 772 123 456",
                "\u0660\u0667\u0667\u0662\u0661\u0662\u0663\u0664\u0665\u0666",
                "\u00AD0772\uFEFF123 456",
                "mail test.officer\uFF20example.test")) {
            assertThat(check("Call " + text + " today")).as(text).isPresent();
        }
        assertThat(check("mail test.officer\uFF20example.test")).contains(Finding.EMAIL);
        assertThat(check("\uFF10\uFF17\uFF17\uFF12\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16"))
                .contains(Finding.PHONE);
        assertThat(check("account \uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17")).contains(Finding.DIGITS);
    }

    @Test
    void datesUuidsAndMaskedIdentifiersAreNotPersonalData() {
        for (String text : List.of(
                "On 07-10-2026 14 loans were disbursed.",
                "On 07.10.2026 12 loans were disbursed.",
                "On 7/10/2026 12 loans were disbursed.",
                "As of 2026-10-14 the book is 3,200,000 UGX.",
                "As of 20261014 the book is 3,200,000 UGX.",
                "Run 123e4567-e89b-12d3-a456-446655440000 finished.",
                "Member ****1234 and ID ******5678 are masked.",
                "Account \u2022\u2022\u2022\u20224321 reviewed.")) {
            assertThat(check(text)).as(text).isEmpty();
        }
    }

    @Test
    void aRealPhoneNextToThoseShapesIsStillRefused() {
        assertThat(check("On 07-10-2026 call 0772 123 456")).contains(Finding.PHONE);
        assertThat(check("0772 123 456 on 07.10.2026")).contains(Finding.PHONE);
        assertThat(check("Run 123e4567-e89b-12d3-a456-446655440000 owner +256 772 123 456"))
                .contains(Finding.PHONE);
        assertThat(check("As of 2026-10-14 account 1234567")).contains(Finding.DIGITS);
        assertThat(check("As of 20261014 mail a.b@example.test")).contains(Finding.EMAIL);
        assertThat(check("****1234 and 0772123456")).contains(Finding.PHONE);
        // Digits that are not a date are not excused by one nearby.
        assertThat(check("2026-10-14 and 99999999")).contains(Finding.DIGITS);
        assertThat(check("20261345")).contains(Finding.DIGITS);
    }

    @Test
    void everyMessageOfARequestIsScreenedNotOnlyTheLast() {
        com.rincoltech.bms.core.ai.LlmClient.Request request = new com.rincoltech.bms.core.ai.LlmClient.Request(
                List.of(
                        new com.rincoltech.bms.core.ai.LlmClient.Message("system", "Reminder: +256 700 000 001"),
                        new com.rincoltech.bms.core.ai.LlmClient.Message("user", "Fine text")),
                100,
                false);
        assertThat(PromptScreen.check(request)).contains(Finding.PHONE);
    }
}
