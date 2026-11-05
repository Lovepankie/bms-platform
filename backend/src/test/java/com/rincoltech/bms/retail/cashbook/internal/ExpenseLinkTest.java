package com.rincoltech.bms.retail.cashbook.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The words an edited expense carries to name the one it replaces (issue #838): the link is always whole, an old expense with
 * no item name still reads plainly, and past 500 characters only the typed part is cut. Every name is fabricated.
 */
class ExpenseLinkTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-02");

    @Test
    void theTypedWordsComeFirstAndTheLinkAfterThem() {
        assertThat(ExpenseService.linked("  Two jerrycans  ", "Test Fuel", DAY))
                .isEqualTo("Two jerrycans Replaces the Test Fuel entry of 2026-11-02, edited.");
        assertThat(ExpenseService.linked(null, "Test Fuel", DAY))
                .isEqualTo("Replaces the Test Fuel entry of 2026-11-02, edited.");
        assertThat(ExpenseService.linked("   ", "Test Fuel", DAY))
                .isEqualTo("Replaces the Test Fuel entry of 2026-11-02, edited.");
    }

    @Test
    void anOldExpenseWithNoItemNameIsNamedTheExpenseEntryNeverNull() {
        assertThat(ExpenseService.linked(null, null, DAY))
                .isEqualTo("Replaces the expense entry of 2026-11-02, edited.");
        assertThat(ExpenseService.linked("Paid in cash", " ", DAY))
                .isEqualTo("Paid in cash Replaces the expense entry of 2026-11-02, edited.");
    }

    @Test
    void pastFiveHundredCharactersTheTypedPartIsCutAndTheLinkIsKeptWhole() {
        String link = "Replaces the Test Fuel entry of 2026-11-02, edited.";
        String typed = "a".repeat(ExpenseService.MAX_EXPLANATION);

        String all = ExpenseService.linked(typed, "Test Fuel", DAY);

        assertThat(all).hasSize(ExpenseService.MAX_EXPLANATION).endsWith(" " + link);
        assertThat(all).startsWith("a".repeat(ExpenseService.MAX_EXPLANATION - link.length() - 1));
        // The longest item name (100 characters) still leaves the link whole.
        String longest = "b".repeat(100);
        assertThat(ExpenseService.linked(typed, longest, DAY))
                .hasSize(ExpenseService.MAX_EXPLANATION)
                .endsWith("Replaces the " + longest + " entry of 2026-11-02, edited.");
        // A typed part that fits is kept as it is.
        String fits = "c".repeat(ExpenseService.MAX_EXPLANATION - link.length() - 1);
        assertThat(ExpenseService.linked(fits, "Test Fuel", DAY)).isEqualTo(fits + " " + link);
    }

    @Test
    void theCutNeverSplitsAnEmojiIntoALoneHalf() {
        String link = "Replaces the Test Fuel entry of 2026-11-02, edited.";
        int room = ExpenseService.MAX_EXPLANATION - link.length() - 1;
        // The emoji's two UTF-16 units straddle the cut: it is dropped whole.
        String typed = "a".repeat(room - 1) + "😀" + "bbb";

        String all = ExpenseService.linked(typed, "Test Fuel", DAY);

        assertThat(all).isEqualTo("a".repeat(room - 1) + " " + link);
        assertThat(all.chars().noneMatch(c -> Character.isSurrogate((char) c))).isTrue();
    }
}
