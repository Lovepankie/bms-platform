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
import com.rincoltech.bms.retail.sales.internal.SalesSummaryRepository.Spec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sales list grouped by a chain of levels (issues #328 and #341), under the list's own branch scope and filters.
 * The request names the whole chain in {@code group_by} and the level it wants answered in {@code level} (the first by
 * default); what the user opened above is passed as filters, so every level is one cheap query.
 */
@Service
class SalesSummaryService {

    /**
     * Groups per request by level: years 20, months 12, days 31, branches 100, and 50 of each person, category and
     * item. This bounds the response, not the work: the LIMIT applies after the grouping, so a query with no
     * {@code from} reads every sale in its filter (a covering index on the date, branch, status and amounts would make
     * that an index-only scan; no migration is made for it here).
     */
    static final int YEARS = 20;

    static final int MONTHS = 12;
    static final int DAYS = 31;
    static final int BRANCHES = 100;
    static final int OTHERS = 50;

    private final SalesSummaryRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    SalesSummaryService(SalesSummaryRepository repo, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
    }

    /** The filters of the request, as the controller read them. */
    record Query(
            String groupBy,
            String level,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            String paymentMethod,
            UUID productId,
            String buyer,
            String owing,
            UUID sellerId,
            UUID categoryId,
            UUID itemId,
            Integer offset) {}

    @Transactional(readOnly = true)
    Summary summary(Query q) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.sale.read", q.branchIds());
        // A ranking of colleagues is management information: the salesperson level needs profit read in every branch
        // the
        // figures cover, the rule of profit_minor.
        boolean sellers = profitVisible(principal, filter);
        // ADR-055: without retail.sale.all_sellers in every branch shown, every figure is the caller's own sales, debts
        // included: a colleague's debt is found in the list to take its payment, never summed here.
        UUID own = ownSellerOnly(principal, filter, q.sellerId());
        // Issue #384: the variant level is offered only to a tenant that has an item with sizes and colours.
        boolean variants = repo.anyItemWithVariants();
        List<String> available = SalesSummaryRepository.LEVELS.stream()
                .filter(l -> (sellers && own == null) || !l.equals("seller"))
                .filter(l -> variants || !l.equals("variant"))
                .toList();
        List<String> chain = chain(q.groupBy());
        if ((!sellers || own != null) && (chain.contains("seller") || (own == null && q.sellerId() != null))) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "The salesperson level is for people who may read profit in every branch shown.");
        }
        String level = q.level() == null ? chain.getFirst() : q.level();
        if (!chain.contains(level)) {
            throw ApiException.validation(List.of(new FieldProblem("level", "invalid", "level is one of group_by.")));
        }
        if (q.owing() != null && !SalesService.OWING_FILTERS.contains(q.owing())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("owing", "invalid", "owing is owing, overdue or paid.")));
        }
        if (q.paymentMethod() != null && !SalesService.PAYMENT_METHODS.contains(q.paymentMethod())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "payment_method", "invalid", "payment_method is cash, mobile_money, bank or credit.")));
        }
        if (q.from() != null && q.to() != null && q.from().isAfter(q.to())) {
            throw ApiException.validation(List.of(new FieldProblem("from", "invalid", "from is after to.")));
        }
        if (q.offset() != null && q.offset() < 0) {
            throw ApiException.validation(List.of(new FieldProblem("offset", "invalid", "offset is zero or more.")));
        }
        String currency = tenant.profile().currency();
        int at = chain.indexOf(level);
        String next = at + 1 < chain.size() ? chain.get(at + 1) : null;
        if (filter != null && filter.isEmpty()) {
            return new Summary(level, chain, next, available, currency, List.of(), false, null, null);
        }
        boolean time = SalesSummaryRepository.TIME_LEVELS.contains(level);
        int size = switch (level) {
            case "year" -> YEARS;
            case "month" -> MONTHS;
            case "day" -> DAYS;
            case "branch" -> BRANCHES;
            default -> OTHERS;
        };
        int offset = time || q.offset() == null ? 0 : q.offset();
        LocalDate today =
                q.owing() == null ? null : clock.today(tenant.profile().timezone());
        List<Group> rows = repo.groups(new Spec(
                level,
                profitVisible(principal, filter),
                filter,
                q.from(),
                q.to(),
                q.customerId(),
                q.paymentMethod(),
                q.productId(),
                Search.parse(q.buyer()),
                q.owing(),
                today,
                own != null ? own : q.sellerId(),
                q.categoryId(),
                q.itemId(),
                offset,
                size + 1));
        boolean more = rows.size() > size;
        List<Group> items = more ? rows.subList(0, size) : rows;
        LocalDate nextTo = more && time ? items.getLast().from().minusDays(1) : null;
        Integer nextOffset = more && !time ? offset + size : null;
        return new Summary(level, chain, next, available, currency, items, more, nextTo, nextOffset);
    }

    /** The chain of {@code group_by}: known levels only, each once, in the order given. */
    static List<String> chain(String groupBy) {
        List<String> chain = groupBy == null
                ? List.of()
                : Arrays.stream(groupBy.split(",", -1)).map(String::trim).toList();
        Set<String> seen = new HashSet<>();
        boolean ok = !chain.isEmpty();
        for (String level : chain) {
            ok &= SalesSummaryRepository.LEVELS.contains(level) && seen.add(level);
        }
        if (!ok) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "group_by",
                    "invalid",
                    "group_by is a list of branch, year, month, seller, day, category, item or variant, each once.")));
        }
        return chain;
    }

    /** ADR-055: see the sales of every seller, not only one's own (the tenant administrator's by default). */
    static final String ALL_SELLERS = "retail.sale.all_sellers";

    /**
     * ADR-055: the debts still owed at the caller's branches (the {@code owing} and {@code overdue} filters) are listed whoever
     * made the sale, so whoever is at the till can take a buyer's payment; a debt is the buyer's figure, not a colleague's
     * takings. Only the sales list uses it: the grouped summary stays the caller's own, so no colleague's debts are summed.
     */
    static boolean debtsOnly(String owing) {
        return "owing".equals(owing) || "overdue".equals(owing);
    }

    /** ADR-055: a credit sale that still owes opens for any seller of its branch, to take the buyer's payment. */
    static boolean stillOwed(SalesApi.Sale s) {
        return "credit".equals(s.paymentMethod())
                && "completed".equals(s.status())
                && s.writeOff() == null
                && s.totalMinor() > s.paidMinor();
    }

    /**
     * ADR-055: a caller who does not hold {@code retail.sale.all_sellers} in every branch the figures cover sees only the
     * sales they made, so the list, the summary and the totals are their own. Returns the caller's id to filter by, or
     * null when every seller's sales may be shown. A {@code seller_id} other than the caller's own is refused (403).
     */
    static UUID ownSellerOnly(Principal principal, List<UUID> filter, UUID sellerId) {
        boolean everyone = filter == null
                ? principal.scopeOf(ALL_SELLERS).map(Principal.BranchScope::all).orElse(false)
                : filter.stream().allMatch(b -> principal.may(ALL_SELLERS, b));
        if (everyone) {
            return null;
        }
        if (sellerId != null && !sellerId.equals(principal.userId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You see your own sales only; another person's sales are for the owner.");
        }
        return principal.userId();
    }

    /**
     * Profit is a sum over the sales shown, so it is offered only when the caller holds {@code retail.profit.read}
     * in every branch the figures cover (ADR-017); a partly covered sum would read as the whole.
     */
    static boolean profitVisible(Principal principal, List<UUID> filter) {
        if (filter == null) {
            return principal
                    .scopeOf("retail.profit.read")
                    .map(Principal.BranchScope::all)
                    .orElse(false);
        }
        return filter.stream().allMatch(b -> principal.may("retail.profit.read", b));
    }
}
