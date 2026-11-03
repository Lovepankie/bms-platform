package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.stock.internal.UsageSummaryRepository.Spec;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Past usage and damage grouped by shop, month, category and item (issue #816, as the owner's old app did), from the
 * same stock movements and with the same branch scope and days as the list (GET /retail/stock/movements with kind usage
 * and damage), so a group's figures are the sum of the rows it holds even when the list is paged. The request names the
 * chain in {@code group_by} and the level it wants in {@code level}; what was opened above arrives as filters. The cost
 * of what was taken out needs {@code retail.profit.read} in every branch the figures cover; the counts and the
 * quantities are for everyone who may read the list.
 */
@Service
class UsageSummaryService {

    static final String COST_ONLY = "Present only with retail.profit.read in every branch the figures cover";
    static final int MONTHS = 12;
    static final int OTHERS = 100;

    private static final String READ = "retail.stock.read";

    private final UsageSummaryRepository repo;
    private final CurrentTenant tenant;

    UsageSummaryService(UsageSummaryRepository repo, CurrentTenant tenant) {
        this.repo = repo;
        this.tenant = tenant;
    }

    @Schema(name = "RetailUsageSummaryGroup")
    record Group(
            @Schema(
                    description = "A branch id, a category id, a product id, or the month (2026-10); pass it back as"
                            + " branch_id, category_id or item_id, or use from and to for a month")
            String key,

            @Schema(description = "The branch, category or item name, or the month key")
            String label,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "First calendar day of the month; month level only")
            LocalDate from,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Last calendar day of the month; month level only")
            LocalDate to,

            @Schema(description = "Records in the group: one per item taken out, as the list shows them")
            long recordsCount,

            @Schema(description = "Records of stock used in the shop")
            long usedCount,

            @Schema(description = "Records of stock damaged")
            long damagedCount,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + ": what was taken out, at the cost each record carries")
            Long costMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Units taken out, without a sign; item level only")
            String quantity,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The item's unit; item level only")
            String unit) {}

    @Schema(name = "RetailUsageSummary")
    record Summary(
            @Schema(description = "The level these groups are: one of available_levels")
            String groupBy,

            @Schema(description = "The chain the request asked for, in order")
            List<String> levels,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The level under this one in the chain; absent at the end of the chain")
            String nextLevel,

            @Schema(description = "Every level group_by accepts: branch, month, category and item")
            List<String> availableLevels,

            @Schema(description = "The tenant's currency; a retail tenant keeps its books in one currency")
            String currency,

            List<Group> items,

            @Schema(description = "True when more groups remain: next_to (month) or next_offset (the rest)")
            boolean hasMore,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Month level: the day before the oldest month shown; ask again with to = next_to")
            LocalDate nextTo,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Branch, category and item: ask again with offset = next_offset")
            Integer nextOffset) {}

    /** The filters of the request, as the controller read them. */
    record Query(
            String groupBy,
            String level,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID categoryId,
            UUID itemId,
            Integer offset) {}

    @Transactional(readOnly = true)
    Summary summary(Query q) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(READ, q.branchIds());
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
        String currency = tenant.profile().currency();
        int at = chain.indexOf(level);
        String next = at + 1 < chain.size() ? chain.get(at + 1) : null;
        if (filter != null && filter.isEmpty()) {
            return new Summary(
                    level, chain, next, UsageSummaryRepository.LEVELS, currency, List.of(), false, null, null);
        }
        boolean month = level.equals("month");
        int size = month ? MONTHS : OTHERS;
        int offset = month || q.offset() == null ? 0 : q.offset();
        List<Group> rows = repo.groups(new Spec(
                level,
                costVisible(principal, filter),
                filter,
                q.from(),
                q.to(),
                q.categoryId(),
                q.itemId(),
                tenant.profile().timezone().getId(),
                offset,
                size + 1));
        boolean more = rows.size() > size;
        List<Group> items = more ? rows.subList(0, size) : rows;
        LocalDate nextTo = more && month ? items.getLast().from().minusDays(1) : null;
        Integer nextOffset = more && !month ? offset + size : null;
        return new Summary(
                level, chain, next, UsageSummaryRepository.LEVELS, currency, items, more, nextTo, nextOffset);
    }

    /** The chain of {@code group_by}: known levels only, each once, in the order given. */
    static List<String> chain(String groupBy) {
        List<String> chain = groupBy == null
                ? List.of()
                : Arrays.stream(groupBy.split(",", -1)).map(String::trim).toList();
        Set<String> seen = new HashSet<>();
        boolean ok = !chain.isEmpty();
        for (String level : chain) {
            ok &= UsageSummaryRepository.LEVELS.contains(level) && seen.add(level);
        }
        if (!ok) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "group_by", "invalid", "group_by is a list of branch, month, category or item, each once.")));
        }
        return chain;
    }

    /** The cost of what was taken out needs {@code retail.profit.read} in every branch the figures cover (ADR-017). */
    static boolean costVisible(Principal principal, List<UUID> filter) {
        if (filter == null) {
            return principal
                    .scopeOf(StockService.PROFIT_READ)
                    .map(Principal.BranchScope::all)
                    .orElse(false);
        }
        return filter.stream().allMatch(b -> principal.may(StockService.PROFIT_READ, b));
    }
}
