package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.Group;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.Summary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sales list grouped by year, month or day (issue #328), under the list's own branch scope and filters. */
@Service
class SalesSummaryService {

    /**
     * Periods per request by level: years 20, months 12, days 31. This bounds the response, not the work: the LIMIT
     * applies after the grouping, so a query with no {@code from} reads every sale in its filter (a covering index on
     * the date, branch, status and amounts would make that an index-only scan; no migration is made for it here).
     */
    static final int YEARS = 20;

    static final int MONTHS = 12;
    static final int DAYS = 31;

    private final SalesSummaryRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    SalesSummaryService(SalesSummaryRepository repo, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Summary summary(
            String groupBy,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            String paymentMethod,
            UUID productId,
            String buyer,
            String owing) {
        if (groupBy == null || !SalesSummaryRepository.UNITS.contains(groupBy)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("group_by", "invalid", "group_by is year, month or day.")));
        }
        if (owing != null && !SalesService.OWING_FILTERS.contains(owing)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("owing", "invalid", "owing is owing, overdue or paid.")));
        }
        if (paymentMethod != null && !SalesService.PAYMENT_METHODS.contains(paymentMethod)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "payment_method", "invalid", "payment_method is cash, mobile_money, bank or credit.")));
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.validation(List.of(new FieldProblem("from", "invalid", "from is after to.")));
        }
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.sale.read", branchIds);
        String currency = tenant.profile().currency();
        if (filter != null && filter.isEmpty()) {
            return new Summary(groupBy, currency, List.of(), false, null);
        }
        int size = switch (groupBy) {
            case "year" -> YEARS;
            case "month" -> MONTHS;
            default -> DAYS;
        };
        LocalDate today = owing == null ? null : clock.today(tenant.profile().timezone());
        List<Group> rows = repo.groups(
                groupBy,
                profitVisible(principal, filter),
                filter,
                from,
                to,
                customerId,
                paymentMethod,
                productId,
                Search.parse(buyer),
                owing,
                today,
                size + 1);
        boolean more = rows.size() > size;
        List<Group> items = more ? rows.subList(0, size) : rows;
        LocalDate nextTo = more ? items.getLast().from().minusDays(1) : null;
        return new Summary(groupBy, currency, items, more, nextTo);
    }

    /**
     * Profit is a sum over the sales shown, so it is offered only when the caller holds {@code retail.profit.read}
     * in every branch the figures cover (ADR-017); a partly covered sum would read as the whole.
     */
    private static boolean profitVisible(Principal principal, List<UUID> filter) {
        if (filter == null) {
            return principal
                    .scopeOf("retail.profit.read")
                    .map(Principal.BranchScope::all)
                    .orElse(false);
        }
        return filter.stream().allMatch(b -> principal.may("retail.profit.read", b));
    }
}
