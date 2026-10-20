package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.retail.reports.internal.ReportsRepository.DayFigures;
import com.rincoltech.bms.retail.stock.CreditPeriods;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place that decides how credit sales count in the profit figures (ADR-043), so the daily profit report, the
 * cash book's daily savings can never disagree (the dashboard cards use the same recognition arithmetic and show gross profit). With the tenant setting {@code on_sale} (the
 * default) the figures are exactly what {@link ReportsRepository#daily} has always returned. With {@code on_payment}
 * they are the money received: sales paid at the sale in full on the sale date, and each payment on a credit sale on the
 * day it was received with its share of the sale's profit ({@link CreditRecognition}).
 */
@Component
class ProfitFigures {

    private final ReportsRepository repo;
    private final CreditRecognitionRepository credit;
    private final RetailCapabilities capabilities;

    ProfitFigures(ReportsRepository repo, CreditRecognitionRepository credit, RetailCapabilities capabilities) {
        this.repo = repo;
        this.credit = credit;
        this.capabilities = capabilities;
    }

    /** The start day of the credit sales counted when the buyer pays; none for a tenant that never chose it. */
    CreditPeriods periods() {
        return capabilities.onPaymentPeriods();
    }

    /** True when any credit sale is counted when the buyer pays, now or in the past (ADR-043). */
    boolean usesOnPayment() {
        return !periods().isEmpty();
    }

    /** The rule in force on {@code day}: {@code on_payment} once its first business day has come, else {@code on_sale}. */
    String modeOn(java.time.LocalDate day) {
        return capabilities.creditRecognitionOn(day);
    }

    /** The first business day of the rule {@code on_payment}, or null when it was never chosen (it may lie in the future). */
    java.time.LocalDate startsOn() {
        return periods().from();
    }

    /**
     * Per branch and day: sales, cost of sales, usage and the loss of debts written off, under the tenant's setting. A
     * write-off is a dated loss in both: the sale it clears was counted or not by the setting, and the loss it carries says so.
     */
    List<DayFigures> daily(List<UUID> branchIds, LocalDate from, LocalDate to) {
        List<DayFigures> base = usesOnPayment() ? received(branchIds, from, to) : repo.daily(branchIds, from, to);
        return withBadDebt(base, branchIds, from, to);
    }

    private List<DayFigures> withBadDebt(List<DayFigures> base, List<UUID> branchIds, LocalDate from, LocalDate to) {
        List<DayFigures> losses = credit.badDebt(branchIds, from, to);
        if (losses.isEmpty()) {
            return base;
        }
        Map<String, long[]> merged = new LinkedHashMap<>();
        Map<String, DayFigures> keys = new LinkedHashMap<>();
        base.forEach(d -> add(merged, keys, d));
        losses.forEach(d -> add(merged, keys, d));
        return rows(merged, keys);
    }

    /** The money-received figures whatever the setting is, for the sold and received comparison. */
    List<DayFigures> received(List<UUID> branchIds, LocalDate from, LocalDate to) {
        Map<String, long[]> merged = new LinkedHashMap<>();
        Map<String, DayFigures> keys = new LinkedHashMap<>();
        CreditPeriods periods = periods();
        for (DayFigures d : repo.daily(branchIds, from, to, periods)) {
            add(merged, keys, d);
        }
        Set<UUID> scope = branchIds == null ? null : Set.copyOf(branchIds);
        for (DayFigures d : CreditRecognition.deltas(credit.events(branchIds, from, to, periods), from, to, scope)) {
            add(merged, keys, d);
        }
        return rows(merged, keys);
    }

    private static List<DayFigures> rows(Map<String, long[]> merged, Map<String, DayFigures> keys) {
        List<DayFigures> out = new ArrayList<>();
        merged.forEach((key, f) -> {
            DayFigures k = keys.get(key);
            out.add(new DayFigures(k.branchId(), k.date(), f[0], f[1], f[2], f[3]));
        });
        out.sort(java.util.Comparator.comparing(DayFigures::date).thenComparing(DayFigures::branchId));
        return out;
    }

    private static void add(Map<String, long[]> merged, Map<String, DayFigures> keys, DayFigures d) {
        String key = d.date() + "|" + d.branchId();
        keys.putIfAbsent(key, d);
        long[] f = merged.computeIfAbsent(key, k -> new long[4]);
        f[0] = Math.addExact(f[0], d.sales());
        f[1] = Math.addExact(f[1], d.costOfSales());
        f[2] = Math.addExact(f[2], d.usageCost());
        f[3] = Math.addExact(f[3], d.badDebt());
    }
}
