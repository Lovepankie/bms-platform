package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.Agg;
import com.rincoltech.bms.retail.purchasing.CashPurchases;
import com.rincoltech.bms.retail.sales.CashSales;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The derived read model of the cash book (ADR-022 decisions 9 and 10; chapter 6 section 6.11.5):
 * per branch and business date, never stored. Every record counts on its own date whether or not it
 * is voided later; a void shows on the day it is made as a separate line, so a past day is never
 * rewritten and a day's closing agrees with the ledger as of that day. This class applies no
 * permission: callers decide which fields a principal may see.
 */
@Component
class CashFigures {

    private final CashbookRepository repo;
    private final CashSales sales;
    private final CashPurchases purchases;
    private final LedgerAccounts ledger;
    private final CurrentTenant tenant;

    CashFigures(
            CashbookRepository repo,
            CashSales sales,
            CashPurchases purchases,
            LedgerAccounts ledger,
            CurrentTenant tenant) {
        this.repo = repo;
        this.sales = sales;
        this.purchases = purchases;
        this.ledger = ledger;
        this.tenant = tenant;
    }

    /** One branch and day. Amounts are minor units. */
    record Day(
            LocalDate date,
            long cashTakings,
            long cashSaleVoids,
            long totalSold,
            long cashPurchases,
            long savings,
            long savingsVoids,
            long expenses,
            long expenseVoids,
            long advancesOut,
            long advanceVoids,
            long repaymentsIn,
            long repaymentVoids,
            long withdrawalsIn,
            long withdrawalVoids,
            long banked,
            long bankingVoids,
            boolean historical) {

        /**
         * Takings less cash sale voids, expenses and advances paid out, plus cash repayments, with the
         * expense, advance and repayment voids of the day: before cash purchases and before savings,
         * so it carries no cost and no profit.
         */
        long cashExpected() {
            return cashTakings
                    - cashSaleVoids
                    - expenses
                    - advancesOut
                    + repaymentsIn
                    + expenseVoids
                    + advanceVoids
                    - repaymentVoids;
        }

        /** The expected amount to bank: {@link #cashExpected()} less cash purchases and savings (and the savings voids). */
        long expectedToBank() {
            return cashExpected() - cashPurchases - savings + savingsVoids;
        }

        /** Banked on the day's own records, less the bankings voided on the day. */
        long bankedNet() {
            return banked - bankingVoids;
        }

        /** The net movement of cash on hand the listed lines explain. */
        long movement() {
            return expectedToBank() - bankedNet() + withdrawalsIn - withdrawalVoids;
        }
    }

    /** A day with the ledger's cash position, for the daily summary. */
    record Position(Day day, long openingMinor, long closingMinor, long otherMovementsMinor) {}

    private static long get(Map<LocalDate, Agg> m, LocalDate d) {
        return m.getOrDefault(d, Agg.ZERO).sum();
    }

    private static boolean hist(Map<LocalDate, Agg> m, LocalDate d) {
        return m.getOrDefault(d, Agg.ZERO).historical();
    }

    /** Every day in {@code from} to {@code to} on which the branch has any cash movement, oldest first. */
    NavigableMap<LocalDate, Day> days(UUID branch, LocalDate from, LocalDate to) {
        ZoneId zone = tenant.profile().timezone();
        Map<LocalDate, CashSales.Day> s = sales.days(branch, from, to, zone);
        Map<LocalDate, Long> p = purchases.byDay(branch, from, to);
        Set<LocalDate> historicalPurchases = purchases.historicalDays(branch, from, to);
        Map<LocalDate, Agg> savings =
                repo.sumByDate("retail_daily_savings", "business_date", "amount_minor", "", branch, from, to);
        Map<LocalDate, Agg> expenses =
                repo.sumByDate("retail_expenses", "business_date", "amount_minor", "", branch, from, to);
        Map<LocalDate, Agg> advances =
                repo.sumByDate("retail_advances", "business_date", "principal_minor", "", branch, from, to);
        Map<LocalDate, Agg> repayments = repo.sumByDate(
                "retail_advance_repayments", "paid_on", "amount_minor", " AND method = 'cash'", branch, from, to);
        Map<LocalDate, Agg> withdrawals =
                repo.sumByDate("retail_cash_withdrawals", "business_date", "amount_minor", "", branch, from, to);
        Map<LocalDate, Agg> bankings =
                repo.sumByDate("retail_cash_bankings", "business_date", "amount_minor", "", branch, from, to);
        Map<LocalDate, Long> savingsVoids =
                repo.sumByVoidDate("retail_daily_savings", "amount_minor", "", branch, from, to, zone);
        Map<LocalDate, Long> expenseVoids =
                repo.sumByVoidDate("retail_expenses", "amount_minor", "", branch, from, to, zone);
        Map<LocalDate, Long> advanceVoids =
                repo.sumByVoidDate("retail_advances", "principal_minor", "", branch, from, to, zone);
        Map<LocalDate, Long> repaymentVoids = repo.sumByVoidDate(
                "retail_advance_repayments", "amount_minor", " AND method = 'cash'", branch, from, to, zone);
        Map<LocalDate, Long> withdrawalVoids =
                repo.sumByVoidDate("retail_cash_withdrawals", "amount_minor", "", branch, from, to, zone);
        Map<LocalDate, Long> bankingVoids =
                repo.sumByVoidDate("retail_cash_bankings", "amount_minor", "", branch, from, to, zone);

        Set<LocalDate> dates = new TreeSet<>();
        dates.addAll(s.keySet());
        dates.addAll(p.keySet());
        dates.addAll(savings.keySet());
        dates.addAll(expenses.keySet());
        dates.addAll(advances.keySet());
        dates.addAll(repayments.keySet());
        dates.addAll(withdrawals.keySet());
        dates.addAll(bankings.keySet());
        dates.addAll(savingsVoids.keySet());
        dates.addAll(expenseVoids.keySet());
        dates.addAll(advanceVoids.keySet());
        dates.addAll(repaymentVoids.keySet());
        dates.addAll(withdrawalVoids.keySet());
        dates.addAll(bankingVoids.keySet());

        NavigableMap<LocalDate, Day> out = new TreeMap<>();
        for (LocalDate d : dates) {
            CashSales.Day sd = s.get(d);
            out.put(
                    d,
                    new Day(
                            d,
                            sd == null ? 0 : sd.cashTakingsMinor(),
                            sd == null ? 0 : sd.cashSaleVoidsMinor(),
                            sd == null ? 0 : sd.totalSoldMinor(),
                            p.getOrDefault(d, 0L),
                            get(savings, d),
                            savingsVoids.getOrDefault(d, 0L),
                            get(expenses, d),
                            expenseVoids.getOrDefault(d, 0L),
                            get(advances, d),
                            advanceVoids.getOrDefault(d, 0L),
                            get(repayments, d),
                            repaymentVoids.getOrDefault(d, 0L),
                            get(withdrawals, d),
                            withdrawalVoids.getOrDefault(d, 0L),
                            get(bankings, d),
                            bankingVoids.getOrDefault(d, 0L),
                            (sd != null && sd.hasHistorical())
                                    || historicalPurchases.contains(d)
                                    || hist(savings, d)
                                    || hist(expenses, d)
                                    || hist(advances, d)
                                    || hist(repayments, d)
                                    || hist(withdrawals, d)
                                    || hist(bankings, d)));
        }
        return out;
    }

    /** One day, including a day with no movement at all (every figure zero). */
    Day day(UUID branch, LocalDate date) {
        Day found = days(branch, date, date).get(date);
        return found != null ? found : new Day(date, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    /**
     * The ledger's cash on hand for the branch around each of the given days: opening is the
     * balance at the end of the previous day, closing is the listed movements added to it, and
     * the remainder to the balance at the end of the day is {@code otherMovements} (a manual
     * journal or an unlisted source), never hidden.
     */
    Map<LocalDate, Position> positions(UUID branch, NavigableMap<LocalDate, Day> days, LocalDate from, LocalDate to) {
        long base = ledger.balanceByBranch("cash_on_hand", from.minusDays(1)).getOrDefault(branch, 0L);
        Map<LocalDate, Long> movement = ledger.movementByDay("cash_on_hand", branch, from, to);
        Map<LocalDate, Position> out = new TreeMap<>();
        long running = base;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long opening = running;
            long ledgerMove = movement.getOrDefault(d, 0L);
            running += ledgerMove;
            Day day = days.get(d);
            if (day == null) {
                continue;
            }
            long closing = opening + day.movement();
            out.put(d, new Position(day, opening, closing, running - closing));
        }
        return out;
    }

    /** Cumulative expected less banked over the branch's live days up to each day; imported days carry none. */
    Map<LocalDate, Long> unbankedRunning(UUID branch, LocalDate firstLive, NavigableMap<LocalDate, Day> days) {
        Map<LocalDate, Long> out = new TreeMap<>();
        if (firstLive == null) {
            return out;
        }
        long running = 0;
        for (Day d : days.values()) {
            if (d.date().isBefore(firstLive) || d.historical()) {
                continue;
            }
            running += d.expectedToBank() - d.bankedNet();
            out.put(d.date(), running);
        }
        return out;
    }

    /** The cash on hand now, from the ledger, for one branch. */
    long cashOnHand(UUID branch, LocalDate asOf) {
        return ledger.balanceByBranch("cash_on_hand", asOf).getOrDefault(branch, 0L);
    }

    /** The bank balance across every branch, from the ledger. */
    long bankBalance(LocalDate asOf) {
        return ledger.balanceByBranch("bank", asOf).values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }
}
