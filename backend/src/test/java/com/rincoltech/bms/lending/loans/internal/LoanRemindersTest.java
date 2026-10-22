package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.rincoltech.bms.lending.loans.internal.LoanNotices.Reminder;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #227 without a database: which reminders and arrears notices a loan's position calls for. */
class LoanRemindersTest {

    static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    static Item item(int no, LocalDate due) {
        return new Item(UUID.randomUUID(), no, due, 400_000, 120_000, 0, 0);
    }

    static List<Reminder> on(List<Item> items, LocalDate today) {
        return LoanNotices.reminders(items, Servicing.position(items, today), today);
    }

    @Test
    void anUnpaidInstalmentIsRemindedFromThreeDaysAwayAndOnItsDayButNotBefore() {
        Item i = item(1, TODAY.plusDays(3));
        assertThat(on(List.of(i), TODAY.minusDays(1))).isEmpty();
        assertThat(on(List.of(i), TODAY))
                .extracting(Reminder::templateKey, Reminder::category, Reminder::amountMinor, Reminder::dueDate)
                .containsExactly(tuple(LoanNotices.DUE_IN_3_DAYS, "reminder", 520_000L, TODAY.plusDays(3)));
        assertThat(on(List.of(i), TODAY.plusDays(1)))
                .extracting(Reminder::templateKey)
                .containsExactly(LoanNotices.DUE_IN_3_DAYS);
        assertThat(on(List.of(i), TODAY.plusDays(3)))
                .extracting(Reminder::templateKey)
                .containsExactly(LoanNotices.DUE_TODAY);
    }

    @Test
    void whatIsLeftIsRemindedAndAPaidInstalmentIsNot() {
        Item part = item(1, TODAY.plusDays(2));
        part.principalPaid = 100_000;
        assertThat(on(List.of(part), TODAY)).extracting(Reminder::amountMinor).containsExactly(420_000L);
        Item paid = item(1, TODAY.plusDays(2));
        paid.principalPaid = 400_000;
        paid.interestPaid = 120_000;
        assertThat(on(List.of(paid), TODAY)).isEmpty();
    }

    @Test
    void theArrearsStepGoesOnTheNightItIsReachedAboutTheOldestUnpaidInstalment() {
        Item first = item(1, TODAY.minusDays(40));
        Item second = item(2, TODAY.minusDays(10));
        List<Item> both = List.of(second, first);
        assertThat(on(both, TODAY.minusDays(39)))
                .extracting(Reminder::templateKey, Reminder::itemId, Reminder::amountMinor)
                .containsExactly(tuple(LoanNotices.OVERDUE_1, first.id, 520_000L));
        assertThat(on(both, TODAY.minusDays(38)))
                .as("the night after, a missed night caught up")
                .extracting(Reminder::templateKey)
                .containsExactly(LoanNotices.OVERDUE_1);
        assertThat(on(both, TODAY.minusDays(37))).as("between steps nothing").isEmpty();
        assertThat(on(both, TODAY.minusDays(33)))
                .extracting(Reminder::templateKey)
                .containsExactly(LoanNotices.OVERDUE_7);
        assertThat(on(both, TODAY.minusDays(10)))
                .filteredOn(Reminder::arrears)
                .extracting(Reminder::templateKey, Reminder::category, Reminder::itemId, Reminder::dpd)
                .containsExactly(tuple(LoanNotices.OVERDUE_30, "arrears", first.id, 30));
        assertThat(on(both, TODAY)).as("DPD 40 is no step").isEmpty();

        // Once the oldest is paid, the next one is the oldest unpaid and the steps start again from its due date.
        first.principalPaid = 400_000;
        first.interestPaid = 120_000;
        assertThat(on(both, TODAY.minusDays(3)))
                .extracting(Reminder::templateKey, Reminder::itemId, Reminder::dueDate)
                .containsExactly(tuple(LoanNotices.OVERDUE_7, second.id, TODAY.minusDays(10)));
    }

    /** The second review of #290: the first night never texts a backlog, a loan 400 days late gets nothing. */
    @Test
    void aLoanFarInArrearsIsNotTextedABacklog() {
        assertThat(on(List.of(item(1, TODAY.minusDays(400))), TODAY)).isEmpty();
        assertThat(LoanNotices.arrearsStep(400)).isNull();
        assertThat(LoanNotices.arrearsStep(31)).isEqualTo(LoanNotices.OVERDUE_30);
        assertThat(LoanNotices.arrearsStep(32)).isNull();
    }
}
