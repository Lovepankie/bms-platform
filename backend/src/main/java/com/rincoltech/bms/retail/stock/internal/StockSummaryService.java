package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.tenancy.Branches.Branch;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.internal.StockSummaryApi.Group;
import com.rincoltech.bms.retail.stock.internal.StockSummaryApi.Summary;
import com.rincoltech.bms.retail.stock.internal.StockSummaryRepository.Spec;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock list grouped by a chain of shop and category (issue #350), under the list's own branch scope and filters.
 * The branches are those the all-branches list shows (a closed one that still holds stock only when asked for), cut to
 * the one chosen above when a shop was opened. Cost needs {@code retail.profit.read} in every branch the figures cover,
 * the rule of the lists.
 */
@Service
class StockSummaryService {

    static final int BOUND = 100;

    private final StockSummaryRepository repo;
    private final StockRepository stock;
    private final RetailBranchContext branches;
    private final CurrentTenant tenant;

    StockSummaryService(
            StockSummaryRepository repo, StockRepository stock, RetailBranchContext branches, CurrentTenant tenant) {
        this.repo = repo;
        this.stock = stock;
        this.branches = branches;
        this.tenant = tenant;
    }

    /** The filters of the request, as the controller read them. */
    record Query(
            String groupBy,
            String level,
            List<UUID> branchIds,
            String query,
            UUID categoryId,
            boolean negativeOnly,
            String stockLevel,
            boolean includeClosed,
            Integer offset) {}

    @Transactional(readOnly = true)
    Summary summary(Query q) {
        Principal principal = CurrentPrincipal.require();
        List<String> chain = chain(q.groupBy());
        String level = q.level() == null ? chain.getFirst() : q.level();
        if (!chain.contains(level)) {
            throw ApiException.validation(List.of(new FieldProblem("level", "invalid", "level is one of group_by.")));
        }
        if (q.offset() != null && q.offset() < 0) {
            throw ApiException.validation(List.of(new FieldProblem("offset", "invalid", "offset is zero or more.")));
        }
        Long atMost = StockService.atMostOf(q.stockLevel());
        Set<UUID> closedHolding = stock.branchesHoldingBalance(branches.inactiveInScope("retail.stock.read"));
        List<UUID> ids = branches.visible("retail.stock.read", q.includeClosed() ? closedHolding : Set.of()).stream()
                .map(Branch::id)
                .filter(id -> q.branchIds() == null
                        || q.branchIds().isEmpty()
                        || q.branchIds().contains(id))
                .toList();
        String currency = tenant.profile().currency();
        int at = chain.indexOf(level);
        String next = at + 1 < chain.size() ? chain.get(at + 1) : null;
        if (ids.isEmpty()) {
            return new Summary(level, chain, next, StockSummaryRepository.LEVELS, currency, List.of(), false, null);
        }
        int offset = q.offset() == null ? 0 : q.offset();
        boolean cost = ids.stream().allMatch(b -> principal.may(StockService.PROFIT_READ, b));
        List<Group> rows = repo.groups(new Spec(
                level,
                cost,
                ids,
                Search.parse(q.query()),
                q.categoryId(),
                q.negativeOnly(),
                atMost,
                offset,
                BOUND + 1));
        boolean more = rows.size() > BOUND;
        List<Group> items = more ? rows.subList(0, BOUND) : rows;
        return new Summary(
                level, chain, next, StockSummaryRepository.LEVELS, currency, items, more, more ? offset + BOUND : null);
    }

    /** The chain of {@code group_by}: known levels only, each once, in the order given. */
    static List<String> chain(String groupBy) {
        List<String> chain = groupBy == null
                ? List.of()
                : Arrays.stream(groupBy.split(",", -1)).map(String::trim).toList();
        Set<String> seen = new HashSet<>();
        boolean ok = !chain.isEmpty();
        for (String level : chain) {
            ok &= StockSummaryRepository.LEVELS.contains(level) && seen.add(level);
        }
        if (!ok) {
            throw ApiException.validation(List.of(
                    new FieldProblem("group_by", "invalid", "group_by is a list of branch or category, each once.")));
        }
        return chain;
    }
}
