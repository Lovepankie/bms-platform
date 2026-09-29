package com.rincoltech.bms.core.ledger.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-004 balance rules, before the database is touched (chapter 15 section 15.6). */
class EntryRulesTest {

    static final UUID CASH = UUID.randomUUID();
    static final UUID LOANS = UUID.randomUUID();

    @Test
    void aBalancedEntryPasses() {
        assertThatCode(() -> EntryRules.check(List.of(
                        Line.debit(LOANS, Money.of(1_000_000, "UGX")), Line.credit(CASH, Money.of(1_000_000, "UGX")))))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnbalancedEntryIsRejected() {
        assertCode(
                List.of(Line.debit(LOANS, Money.of(1_000_000, "UGX")), Line.credit(CASH, Money.of(999_999, "UGX"))),
                "unbalanced_entry");
    }

    @Test
    void balanceIsPerCurrency() {
        assertCode(
                List.of(
                        Line.debit(LOANS, Money.of(100, "UGX")),
                        Line.credit(CASH, Money.of(100, "KES")),
                        Line.debit(LOANS, Money.of(100, "KES")),
                        Line.credit(CASH, Money.of(50, "UGX"))),
                "unbalanced_entry");
    }

    @Test
    void aSingleLineIsRejected() {
        assertCode(List.of(Line.debit(LOANS, Money.of(100, "UGX"))), "invalid_journal_line");
    }

    @Test
    void aLineWithBothOrNeitherSideIsRejected() {
        assertCode(
                List.of(new Line(LOANS, 100, 100, "UGX", null, null), Line.credit(CASH, Money.of(0, "UGX"))),
                "invalid_journal_line");
        assertCode(
                List.of(new Line(LOANS, -100, 0, "UGX", null, null), Line.credit(CASH, Money.of(-100, "UGX"))),
                "invalid_journal_line");
    }

    private static void assertCode(List<Line> lines, String code) {
        assertThatThrownBy(() -> EntryRules.check(lines))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                .isEqualTo(code));
    }
}
