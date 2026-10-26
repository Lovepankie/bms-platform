package com.rincoltech.bms.core.help.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** No business data leaves the server in a question (ADR-096 decision 3). Fabricated names and numbers only. */
class QuestionScrubberTest {

    final QuestionScrubber scrubber = new QuestionScrubber(
            List.of("How do I invite someone? Open Staff and tap Invite someone. Send on WhatsApp. Record a sale."));

    @Test
    void aPlainQuestionIsKept() {
        assertThat(scrubber.scrub("How do I invite a cashier?")).isEqualTo("How do I invite a cashier?");
        assertThat(scrubber.scrub("Where is Staff? Can I send it on WhatsApp in 3 days?"))
                .isEqualTo("Where is Staff? Can I send it on WhatsApp in 3 days?");
    }

    @Test
    void phonesIdsEmailsAndFiguresAreReplaced() {
        assertThat(scrubber.scrub("invite 0772 123 456 and +256 700 000 091 now"))
                .isEqualTo("invite [phone] and [phone] now");
        assertThat(scrubber.scrub("member with id cm90010000000x?")).isEqualTo("member with id [id]?");
        assertThat(scrubber.scrub("email test.person@example.test please")).isEqualTo("email [email] please");
        assertThat(scrubber.scrub("record a sale of 1,250,000 on loan 00042"))
                .isEqualTo("record a sale of [number] on loan [number]");
        assertThat(scrubber.scrub("loan 5a0c2f1e-1111-4222-8333-944455556666")).isEqualTo("loan [id]");
        // Full-width digits and a zero-width space cannot hide a phone number.
        assertThat(scrubber.scrub("call ０７７２​１２３４５６")).isEqualTo("call [phone]");
    }

    @Test
    void namesAreReplacedWhereverTheyStand() {
        assertThat(scrubber.scrub("How do I invite Zorvex Quillam as a cashier?"))
                .isEqualTo("How do I invite [name] as a cashier?");
        assertThat(scrubber.scrub("Brontu wants to record a sale")).isEqualTo("[name] wants to record a sale");
        assertThat(scrubber.scrub("why can't Flimzy Test-Person approve?")).isEqualTo("why can't [name] approve?");
    }

    @Test
    void theCacheReadsOneShapeOfAQuestion() {
        assertThat(QuestionScrubber.normalised("  How do I   invite a CASHIER?? "))
                .isEqualTo(QuestionScrubber.normalised("how do i invite a cashier"));
        assertThat(QuestionScrubber.normalised("[name] wants to record a sale."))
                .isEqualTo("[name] wants to record a sale");
        assertThat(QuestionScrubber.normalised("invite [name]")).isEqualTo("invite [name]");
    }
}
