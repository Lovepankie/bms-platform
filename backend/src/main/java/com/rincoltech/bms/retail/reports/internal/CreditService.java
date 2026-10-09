package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.Window;
import com.rincoltech.bms.retail.reports.internal.CreditApi.Ageing;
import com.rincoltech.bms.retail.reports.internal.CreditApi.Buyer;
import com.rincoltech.bms.retail.reports.internal.CreditApi.Overdue;
import com.rincoltech.bms.retail.reports.internal.CreditApi.PaymentDay;
import com.rincoltech.bms.retail.reports.internal.CreditApi.PaymentMethod;
import com.rincoltech.bms.retail.reports.internal.CreditApi.Report;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.Buckets;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.BuyerRow;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.OverdueRow;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.Payment;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The credit control report of issue #149: who owes, how late, and what was paid in a range. */
@Service
class CreditService {

    private final CreditRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    CreditService(CreditRepository repo, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Report report(List<UUID> branchIds, LocalDate from, LocalDate to, Integer topParam, String sortParam) {
        String sort = sortParam == null ? "amount" : sortParam;
        if (!sort.equals("amount") && !sort.equals("age")) {
            throw ApiException.validation(List.of(new FieldProblem("sort", "invalid", "sort is amount or age.")));
        }
        var principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.sale.read", branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        Window w = AnalyticsSupport.window(from, to, today);
        int top = AnalyticsSupport.top(topParam);

        BuyerRow total = repo.totals(filter, today);
        List<Buyer> buyers = repo.buyers(filter, today, top).stream()
                .map(b -> new Buyer(b.customerId(), b.name(), b.sales(), ageing(b.buckets()), b.oldestDays()))
                .toList();
        List<OverdueRow> overdue = repo.overdue(filter, today, sort.equals("age"), top);

        Map<String, long[]> byMethod = new LinkedHashMap<>();
        List<PaymentDay> days = repo.payments(filter, w.from(), w.to()).stream()
                .peek(p -> {
                    long[] m = byMethod.computeIfAbsent(p.method(), k -> new long[2]);
                    m[0] += p.count();
                    m[1] = Math.addExact(m[1], p.amount());
                })
                .map((Payment p) -> new PaymentDay(p.date(), p.method(), p.count(), p.amount()))
                .toList();
        List<PaymentMethod> methods = byMethod.entrySet().stream()
                .map(e -> new PaymentMethod(e.getKey(), (int) e.getValue()[0], e.getValue()[1]))
                .toList();
        return new Report(
                w.from(),
                w.to(),
                today,
                tenant.profile().currency(),
                top,
                ageing(total.buckets()),
                total.buyers(),
                buyers,
                sort,
                overdue.isEmpty() ? 0 : overdue.getFirst().count(),
                overdue.stream()
                        .map(o -> new Overdue(
                                o.saleId(),
                                o.saleNo(),
                                o.branchId(),
                                o.customerId(),
                                o.buyerName(),
                                o.saleDate(),
                                o.dueDate(),
                                o.daysOverdue(),
                                o.total(),
                                o.outstanding()))
                        .toList(),
                days,
                methods,
                methods.stream().mapToLong(PaymentMethod::amountMinor).sum());
    }

    private static Ageing ageing(Buckets b) {
        return new Ageing(b.owed(), b.notDue(), b.d30(), b.d60(), b.d90(), b.over90());
    }
}
