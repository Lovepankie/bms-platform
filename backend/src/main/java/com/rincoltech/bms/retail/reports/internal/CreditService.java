package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.Window;
import com.rincoltech.bms.retail.reports.internal.CreditApi.Ageing;
import com.rincoltech.bms.retail.reports.internal.CreditApi.AgeingCustomer;
import com.rincoltech.bms.retail.reports.internal.CreditApi.Buyer;
import com.rincoltech.bms.retail.reports.internal.CreditApi.CustomerReport;
import com.rincoltech.bms.retail.reports.internal.CreditApi.Overdue;
import com.rincoltech.bms.retail.reports.internal.CreditApi.PaymentDay;
import com.rincoltech.bms.retail.reports.internal.CreditApi.PaymentMethod;
import com.rincoltech.bms.retail.reports.internal.CreditApi.Report;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.Buckets;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.BuyerRow;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.OverdueRow;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.Payment;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
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
    private final RetailCapabilities capabilities;

    CreditService(CreditRepository repo, CurrentTenant tenant, BusinessClock clock, RetailCapabilities capabilities) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
        this.capabilities = capabilities;
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
        // Issue #196: the credit limit joins the buyer's row only while customer accounts are on; off, the row is as
        // before.
        boolean accounts = capabilities.enabled(RetailCapabilities.CUSTOMER_ACCOUNTS);
        List<Buyer> buyers = repo.buyers(filter, today, top).stream()
                .map(b -> new Buyer(
                        b.customerId(),
                        b.name(),
                        b.sales(),
                        ageing(b.buckets()),
                        b.oldestDays(),
                        accounts ? b.creditLimitMinor() : null,
                        // The limit is measured across every branch: "over limit" is said only when the report is.
                        accounts && filter == null && b.creditLimitMinor() != null
                                ? b.buckets().owed() > b.creditLimitMinor()
                                : null))
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

    /**
     * Issue #196, spec 5.2: the open balance of each credit buyer with a record in buckets by days past the due date, as of
     * today in the tenant's time zone, with the limit and the terms. It reads the same sales as the credit control report, so
     * the two agree; imported credit sales are in it, as they are in the balance.
     */
    @Transactional(readOnly = true)
    CustomerReport customerAgeing(List<UUID> branchIds, Integer topParam) {
        capabilities.require(RetailCapabilities.CUSTOMER_ACCOUNTS);
        var principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.sale.read", branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        int top = AnalyticsSupport.top(topParam);
        // The limit is measured across every branch: "over limit" is said only to a caller who sees every branch.
        boolean wholeBook = filter == null;
        BuyerRow total = repo.customerTotals(filter, today);
        List<AgeingCustomer> rows = repo.customers(filter, today, top).stream()
                .map(b -> new AgeingCustomer(
                        b.customerId(),
                        b.name(),
                        b.creditLimitMinor(),
                        b.termsDays(),
                        b.sales(),
                        ageing(b.buckets()),
                        b.oldestDays(),
                        wholeBook && b.creditLimitMinor() != null ? b.buckets().owed() > b.creditLimitMinor() : null))
                .toList();
        return new CustomerReport(
                today, tenant.profile().currency(), top, ageing(total.buckets()), total.buyers(), rows);
    }

    private static Ageing ageing(Buckets b) {
        return new Ageing(b.owed(), b.notDue(), b.d30(), b.d60(), b.d90(), b.over90());
    }
}
