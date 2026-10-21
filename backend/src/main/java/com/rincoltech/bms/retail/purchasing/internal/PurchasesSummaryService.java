package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.purchasing.internal.PurchasesSummaryApi.Group;
import com.rincoltech.bms.retail.purchasing.internal.PurchasesSummaryApi.Summary;
import com.rincoltech.bms.retail.purchasing.internal.PurchasesSummaryRepository.Spec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The restocks list grouped by a chain of levels (issue #350), under the list's own branch scope. The request names
 * the whole chain in {@code group_by} and the level it wants answered in {@code level} (the first by default); what
 * the user opened above is passed as filters, so every level is one cheap query. What was bought, at cost, is a
 * figure of the cost permission: it is present only for a caller holding {@code retail.profit.read} in every branch
 * the figures cover.
 */
@Service
class PurchasesSummaryService {

    static final int YEARS = 20;
    static final int MONTHS = 12;
    static final int DAYS = 31;
    static final int BRANCHES = 100;
    static final int OTHERS = 50;

    private static final String COST_READ = "retail.profit.read";

    private final PurchasesSummaryRepository repo;
    private final CurrentTenant tenant;

    PurchasesSummaryService(PurchasesSummaryRepository repo, CurrentTenant tenant) {
        this.repo = repo;
        this.tenant = tenant;
    }

    /** The filters of the request, as the controller read them. */
    record Query(
            String groupBy,
            String level,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID supplierId,
            UUID categoryId,
            UUID itemId,
            String status,
            Integer offset) {}

    @Transactional(readOnly = true)
    Summary summary(Query q) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(PurchasingService.PERMISSION, q.branchIds());
        List<String> chain = chain(q.groupBy());
        String level = q.level() == null ? chain.getFirst() : q.level();
        if (!chain.contains(level)) {
            throw ApiException.validation(List.of(new FieldProblem("level", "invalid", "level is one of group_by.")));
        }
        if (q.from() != null && q.to() != null && q.from().isAfter(q.to())) {
            throw ApiException.validation(List.of(new FieldProblem("from", "invalid", "from is after to.")));
        }
        if (q.offset() != null && q.offset() < 0) {
            throw ApiException.validation(List.of(new FieldProblem("offset", "invalid", "offset is zero or more.")));
        }
        if (q.status() != null && !q.status().equals("posted") && !q.status().equals("voided")) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is posted or voided.")));
        }
        String currency = tenant.profile().currency();
        int at = chain.indexOf(level);
        String next = at + 1 < chain.size() ? chain.get(at + 1) : null;
        // Issue #384: the variant level is offered only to a tenant that has an item with sizes and colours.
        boolean variants = repo.anyItemWithVariants();
        List<String> available = PurchasesSummaryRepository.LEVELS.stream()
                .filter(l -> variants || !l.equals("variant"))
                .toList();
        if (filter != null && filter.isEmpty()) {
            return new Summary(level, chain, next, available, currency, List.of(), false, null, null);
        }
        boolean time = PurchasesSummaryRepository.TIME_LEVELS.contains(level);
        int size = switch (level) {
            case "year" -> YEARS;
            case "month" -> MONTHS;
            case "day" -> DAYS;
            case "branch" -> BRANCHES;
            default -> OTHERS;
        };
        int offset = time || q.offset() == null ? 0 : q.offset();
        List<Group> rows = repo.groups(new Spec(
                level,
                costVisible(principal, filter),
                filter,
                q.from(),
                q.to(),
                q.supplierId(),
                q.categoryId(),
                q.itemId(),
                q.status(),
                offset,
                size + 1,
                PurchasingService.ownRecordsOnly()));
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
            ok &= PurchasesSummaryRepository.LEVELS.contains(level) && seen.add(level);
        }
        if (!ok) {
            throw ApiException.validation(
                    List.of(
                            new FieldProblem(
                                    "group_by",
                                    "invalid",
                                    "group_by is a list of branch, year, month, supplier, day, category, item or variant, each once.")));
        }
        return chain;
    }

    /** What was bought, at cost, needs {@code retail.profit.read} in every branch the figures cover (ADR-017). */
    static boolean costVisible(Principal principal, List<UUID> filter) {
        if (filter == null) {
            return principal.scopeOf(COST_READ).map(Principal.BranchScope::all).orElse(false);
        }
        return filter.stream().allMatch(b -> principal.may(COST_READ, b));
    }
}
