package com.rincoltech.bms.retail.reports.internal;

import static com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.PROFIT_READ;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.Window;
import com.rincoltech.bms.retail.reports.internal.MarginApi.BelowTarget;
import com.rincoltech.bms.retail.reports.internal.MarginApi.PriceChange;
import com.rincoltech.bms.retail.reports.internal.MarginApi.PriceChanges;
import com.rincoltech.bms.retail.reports.internal.MarginApi.Report;
import com.rincoltech.bms.retail.reports.internal.MarginApi.Row;
import com.rincoltech.bms.retail.reports.internal.MarginApi.Totals;
import com.rincoltech.bms.retail.reports.internal.MarginRepository.Impact;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Agg;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Dimension;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Order;
import com.rincoltech.bms.retail.stock.Quantities;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The margin report of issue #149: profit and margin by item and category, items under a target, price change impact. */
@Service
class MarginService {

    static final int DEFAULT_TARGET_BP = 2_000;

    private final SalesAnalysisRepository repo;
    private final MarginRepository prices;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    MarginService(SalesAnalysisRepository repo, MarginRepository prices, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.prices = prices;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Report margins(List<UUID> branchIds, LocalDate from, LocalDate to, Integer topParam, Integer targetBpParam) {
        int target = targetBpParam == null ? DEFAULT_TARGET_BP : targetBpParam;
        if (target < 0 || target > 10_000) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "target_bp", "invalid", "target_bp is from 0 to 10000 basis points (2000 is 20 percent).")));
        }
        Principal principal = CurrentPrincipal.require();
        // Cost and profit are the whole report: scope is that of retail.profit.read (ADR-017).
        List<UUID> filter = principal.branchFilter(PROFIT_READ, branchIds);
        ZoneId zone = tenant.profile().timezone();
        Window w = AnalyticsSupport.window(from, to, clock.today(zone));
        int top = AnalyticsSupport.top(topParam);

        Agg total = repo.total(filter, w.from(), w.to());
        Totals totals = new Totals(
                total.sales(),
                total.cost(),
                total.sales() - total.cost(),
                AnalyticsSupport.marginBp(total.sales() - total.cost(), total.sales()));
        List<Row> byProduct = rows(Dimension.PRODUCT, filter, w, Order.PROFIT, null, top);
        List<Row> byCategory = rows(Dimension.CATEGORY, filter, w, Order.PROFIT, null, top);
        List<Agg> below = repo.grouped(Dimension.PRODUCT, filter, w.from(), w.to(), Order.MARGIN, target, top);

        List<Impact> impacts = prices.priceChanges(
                filter,
                w.from(),
                w.to(),
                zone.getId(),
                w.from().atStartOfDay(zone).toInstant(),
                w.to().plusDays(1).atStartOfDay(zone).toInstant(),
                top);
        return new Report(
                w.from(),
                w.to(),
                tenant.profile().currency(),
                top,
                target,
                totals,
                byProduct,
                byCategory,
                new BelowTarget(
                        below.stream().map(MarginService::row).toList(),
                        below.isEmpty() ? 0 : below.getFirst().total()),
                new PriceChanges(
                        impacts.stream().map(i -> change(i, w)).toList(),
                        impacts.isEmpty() ? 0 : impacts.getFirst().total()));
    }

    private List<Row> rows(Dimension d, List<UUID> filter, Window w, Order order, Integer target, int top) {
        return repo.grouped(d, filter, w.from(), w.to(), order, target, top).stream()
                .map(MarginService::row)
                .toList();
    }

    private static Row row(Agg a) {
        return new Row(
                a.id(),
                a.code(),
                a.label(),
                a.unit(),
                Quantities.format(a.qty()),
                a.sales(),
                a.cost(),
                a.sales() - a.cost(),
                AnalyticsSupport.marginBp(a.sales() - a.cost(), a.sales()));
    }

    private static PriceChange change(Impact i, Window w) {
        return new PriceChange(
                i.productId(),
                i.code(),
                i.description(),
                i.unit(),
                i.changedOn(),
                i.oldSell(),
                i.newSell(),
                (int) ChronoUnit.DAYS.between(w.from(), i.changedOn()),
                (int) ChronoUnit.DAYS.between(i.changedOn(), w.to()) + 1,
                Quantities.format(i.qtyBefore()),
                Quantities.format(i.qtyAfter()),
                i.salesBefore(),
                i.salesAfter(),
                AnalyticsSupport.marginBp(i.salesBefore() - i.costBefore(), i.salesBefore()),
                AnalyticsSupport.marginBp(i.salesAfter() - i.costAfter(), i.salesAfter()));
    }
}
