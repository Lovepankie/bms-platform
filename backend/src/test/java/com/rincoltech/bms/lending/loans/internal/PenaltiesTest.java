package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.loans.internal.Penalties.Charge;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.products.ProductCatalog.PenaltyRule;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** R-PEN, chapter 3 section 3.4, on fabricated items. */
class PenaltiesTest {

    static final LocalDate DUE = LocalDate.of(2026, 4, 16);

    /** Penalties start long before these items fall due, as for a business that has run arrears for a year. */
    static final LocalDate EARLY = LocalDate.of(2025, 1, 1);

    /** Worked example B's item 1: principal 400,000 and interest 120,000, due 16 April. */
    static Item item() {
        return new Item(UUID.randomUUID(), 1, DUE, 400_000, 120_000, 0, 0);
    }

    @Test
    void flatPerDayAfterGraceDays() {
        PenaltyRule rule = new PenaltyRule("flat_per_period", 3, "day", 2_000L, null, null);
        // Grace ends 19 April; period 1 is 20 April; on 22 April three periods are due.
        List<Charge> charges = Penalties.due(item(), rule, LocalDate.of(2026, 4, 22), EARLY, Set.of(), 0);
        assertThat(charges).extracting(Charge::periodNo).containsExactly(1, 2, 3);
        assertThat(charges)
                .extracting(Charge::chargeDate)
                .containsExactly(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 4, 21), LocalDate.of(2026, 4, 22));
        assertThat(charges).extracting(Charge::amountMinor).containsOnly(2_000L);
        assertThat(Penalties.due(item(), rule, LocalDate.of(2026, 4, 19), EARLY, Set.of(), 0))
                .as("inside grace")
                .isEmpty();
    }

    @Test
    void aRerunChargesOnlyMissingPeriods() {
        PenaltyRule rule = new PenaltyRule("flat_per_period", 0, "day", 2_000L, null, null);
        List<Charge> again = Penalties.due(item(), rule, LocalDate.of(2026, 4, 19), EARLY, Set.of(1, 2), 4_000);
        assertThat(again).extracting(Charge::periodNo).containsExactly(3);
        assertThat(Penalties.due(item(), rule, LocalDate.of(2026, 4, 19), EARLY, Set.of(1, 2, 3), 6_000))
                .isEmpty();
    }

    @Test
    void percentOfOverduePrincipalAndInterestPerWeek() {
        PenaltyRule rule = new PenaltyRule("percent_of_overdue_per_period", 0, "week", null, 100, null);
        Item partlyPaid = item();
        partlyPaid.interestPaid = 120_000;
        partlyPaid.principalPaid = 100_000;
        // 1% of the 300,000 still unpaid, on 17 and 24 April.
        List<Charge> charges = Penalties.due(partlyPaid, rule, LocalDate.of(2026, 4, 24), EARLY, Set.of(), 0);
        assertThat(charges).extracting(Charge::amountMinor).containsExactly(3_000L, 3_000L);
    }

    @Test
    void theCapStopsFurtherCharges() {
        // Cap 1% of 520,000 = 5,200: 2,000 + 2,000 + 1,200, then nothing.
        PenaltyRule rule = new PenaltyRule("flat_per_period", 0, "day", 2_000L, null, 100);
        List<Charge> charges = Penalties.due(item(), rule, LocalDate.of(2026, 4, 30), EARLY, Set.of(), 0);
        assertThat(charges).extracting(Charge::amountMinor).containsExactly(2_000L, 2_000L, 1_200L);
    }

    @Test
    void nothingWhenPaidNotDueOrNoRule() {
        PenaltyRule rule = new PenaltyRule("flat_per_period", 0, "day", 2_000L, null, null);
        Item paid = item();
        paid.principalPaid = 400_000;
        paid.interestPaid = 120_000;
        assertThat(Penalties.due(paid, rule, LocalDate.of(2026, 4, 30), EARLY, Set.of(), 0))
                .isEmpty();
        assertThat(Penalties.due(item(), rule, DUE, EARLY, Set.of(), 0))
                .as("not overdue on its due date")
                .isEmpty();
        assertThat(Penalties.due(
                        item(),
                        new PenaltyRule("none", 0, null, null, null, null),
                        LocalDate.of(2026, 5, 1),
                        EARLY,
                        Set.of(),
                        0))
                .isEmpty();
    }

    @Test
    void monthlyPeriodsStepByCalendarMonth() {
        PenaltyRule rule = new PenaltyRule("flat_per_period", 0, "month", 5_000L, null, null);
        List<Charge> charges = Penalties.due(item(), rule, LocalDate.of(2026, 6, 20), EARLY, Set.of(), 0);
        assertThat(charges)
                .extracting(Charge::chargeDate)
                .containsExactly(LocalDate.of(2026, 4, 17), LocalDate.of(2026, 5, 17), LocalDate.of(2026, 6, 17));
    }

    /** The review of #270, finding 2: a period dated before the first arrears run is never charged. */
    @Test
    void nothingBeforeTheFirstRun() {
        PenaltyRule rule = new PenaltyRule("flat_per_period", 0, "day", 2_000L, null, null);
        LocalDate firstRun = LocalDate.of(2026, 4, 29);
        List<Charge> charges = Penalties.due(item(), rule, LocalDate.of(2026, 4, 30), firstRun, Set.of(), 0);
        assertThat(charges).extracting(Charge::chargeDate).containsExactly(firstRun, LocalDate.of(2026, 4, 30));
        assertThat(charges).extracting(Charge::periodNo).containsExactly(13, 14);
    }

    /** A percent that rounds to nothing charges nothing (amount_minor is always positive). */
    @Test
    void aPercentThatRoundsToNothingChargesNothing() {
        PenaltyRule rule = new PenaltyRule("percent_of_overdue_per_period", 0, "day", null, 1, null);
        Item small = new Item(UUID.randomUUID(), 1, DUE, 40, 0, 0, 0);
        assertThat(Penalties.due(small, rule, LocalDate.of(2026, 4, 30), EARLY, Set.of(), 0))
                .isEmpty();
    }

    /**
     * The review of #270, finding 1 (critical): an instalment of 100,000 at 5% a week; week 1 charged 5,000 on its
     * date; two days later 80,000 is recorded with a value date before week 1. The base on week 1's date was 20,000,
     * so week 1 should stand at 1,000 and the 4,000 is cancelled.
     */
    @Test
    void aBackDatedPartialPaymentRecomputesAPercentPenaltyOnTheSmallerBase() {
        Item item = new Item(UUID.randomUUID(), 1, DUE, 80_000, 20_000, 0, 5_000);
        PenaltyRule rule = new PenaltyRule("percent_of_overdue_per_period", 0, "week", null, 500, null);
        LocalDate week1 = DUE.plusDays(1);
        java.util.NavigableMap<LocalDate, long[]> paid = new java.util.TreeMap<>();
        assertThat(Penalties.targets(item, rule, List.of(new Penalties.Period(1, week1, 5_000, 5_000, 0)), paid))
                .containsEntry(1, 5_000L);
        paid.put(DUE, new long[] {80_000, 0});
        assertThat(Penalties.targets(item, rule, List.of(new Penalties.Period(1, week1, 5_000, 5_000, 0)), paid))
                .as("1,000 owed, 4,000 to cancel")
                .containsEntry(1, 1_000L);
        paid.put(DUE, new long[] {100_000, 0});
        assertThat(Penalties.targets(item, rule, List.of(new Penalties.Period(1, week1, 5_000, 5_000, 0)), paid))
                .as("paid up before the period: nothing owed")
                .containsEntry(1, 0L);
        assertThat(Penalties.targets(item, rule, List.of(new Penalties.Period(1, week1, 5_000, 5_000, 500)), paid))
                .as("never below what was waived")
                .containsEntry(1, 500L);
        paid.clear();
        paid.put(week1, new long[] {100_000, 0});
        assertThat(Penalties.targets(item, rule, List.of(new Penalties.Period(1, week1, 5_000, 0, 0)), paid))
                .as("paid on the period's own date, after the run charged it: it stands, and a reversal brings it back")
                .containsEntry(1, 5_000L);
    }
}
