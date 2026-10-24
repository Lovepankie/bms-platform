package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Principal.BranchScope;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The branch a retail event happens in (FR-RET-04): the {@code branch_id} the caller sent, which
 * must be active and inside the scope of the route's permission (ADR-017), or, when none was sent,
 * the one branch that permission is scoped to. A caller scoped to several branches, or to all of
 * them, must name one ({@code branch_required}).
 */
@Component
public class RetailBranchContext {

    private final Branches branches;

    RetailBranchContext(Branches branches) {
        this.branches = branches;
    }

    /** The active branches the permission covers, head office first: the columns of an all-branches read. */
    public List<Branches.Branch> visible(String permission) {
        return visible(permission, Set.of());
    }

    /** Ids of the inactive branches the permission covers: the only ones a balance can keep in a column. */
    public List<UUID> inactiveInScope(String permission) {
        Principal principal = CurrentPrincipal.require();
        return branches.all().stream()
                .filter(b -> !b.active() && principal.may(permission, b.id()))
                .map(Branches.Branch::id)
                .toList();
    }

    /**
     * As {@link #visible(String)}, and also an inactive branch named in {@code holding}: a closed
     * branch that still holds a non-zero balance stays a column so the totals agree with the valuation.
     */
    public List<Branches.Branch> visible(String permission, Set<UUID> holding) {
        Principal principal = CurrentPrincipal.require();
        return branches.all().stream()
                .filter(b -> (b.active() || holding.contains(b.id())) && principal.may(permission, b.id()))
                .toList();
    }

    public UUID resolve(String permission, UUID requested) {
        return resolve(permission, requested, "branch_id");
    }

    /**
     * As {@link #resolve(String, UUID)} for an event that moves or counts goods (a restock, a transfer, an adjustment): the
     * branch must also keep goods. A branch marked as not stocking, such as a head office that only keeps accounts, is refused
     * with 422 {@code branch_not_stocking} (issue #329, ADR-041).
     */
    public UUID resolveStocking(String permission, UUID requested) {
        return resolveStocking(permission, requested, "branch_id");
    }

    /** As {@link #resolveStocking(String, UUID)}, naming the request field the branch came from. */
    public UUID resolveStocking(String permission, UUID requested, String field) {
        UUID branch = resolve(permission, requested, field);
        requireStocks(branch, field);
        return branch;
    }

    /** Refuses a branch that is closed or keeps no goods, reading the branch row under a share lock (see {@link #active}). */
    public void requireStocks(UUID branch, String field) {
        Branches.Branch found = active(branch)
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem(field, "unknown_branch", "No such active branch in your scope."))));
        refuseIfNotStocking(found, field);
    }

    /**
     * As {@link #requireStocks} for goods that physically arrive (a transfer received): a closed branch may still receive what is
     * already on the road to it (ADR-041), but a branch that keeps no goods may not.
     */
    public void requireStocksReceiving(UUID branch, String field) {
        Branches.Branch found = writing()
                ? branches.lockBranch(branch).orElse(null)
                : branches.findActive(branch).orElse(null);
        if (found != null) {
            refuseIfNotStocking(found, field);
        }
    }

    // JUSTIFICATION-A3: the one place every selling path asks "may this branch sell"; it sits beside requireStocks,
    // whose lock it shares.
    /**
     * Refuses a Store, a branch that holds stock but does not sell (issue #366, ADR-048), with 422 {@code branch_does_not_sell}.
     * Called by every path that makes a sale, takes a payment at a till or pays a refund out of one; never by a restock, a goods
     * receipt, a transfer or a stock-take, which a Store takes like any branch. The branch row is read under the share lock the
     * administrator's change waits for, as {@link #requireStocks} does.
     */
    public void requireSells(UUID branch, String field) {
        Branches.Branch found = writing()
                ? branches.lockBranch(branch).orElse(null)
                : branches.findActive(branch).orElse(null);
        if (found != null && !found.sellsGoods()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "branch_does_not_sell",
                    "This branch does not sell",
                    found.name()
                            + " is a store: it holds stock and takes transfers and purchases, but nothing is sold, paid or"
                            + " refunded there. Choose a shop.",
                    List.of(new FieldProblem(
                            field, "branch_does_not_sell", "This branch is a store and does not sell.")));
        }
    }

    private static void refuseIfNotStocking(Branches.Branch found, String field) {
        if (!found.stocksGoods()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "branch_not_stocking",
                    "This branch does not stock goods",
                    found.name()
                            + " is marked as not stocking goods, so nothing can be sold, bought in, received or counted"
                            + " there. Choose a shop or a store.",
                    List.of(new FieldProblem(field, "branch_not_stocking", "This branch does not stock goods.")));
        }
    }

    private static boolean writing() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    /**
     * The active branch. In a write transaction the row is read FOR SHARE, so a write queues behind an administrator's close or
     * not-stocking change (which holds it FOR UPDATE) and sees the result, and the change waits for a write already past its check
     * (issue #329, ADR-041). A read only transaction reads it plainly.
     */
    private Optional<Branches.Branch> active(UUID branch) {
        return writing() ? branches.lockBranch(branch).filter(Branches.Branch::active) : branches.findActive(branch);
    }

    /** As {@link #resolve(String, UUID)}, naming the request field the branch came from. */
    public UUID resolve(String permission, UUID requested, String field) {
        Principal principal = CurrentPrincipal.require();
        UUID branch = requested;
        if (branch == null) {
            BranchScope scope = principal.scopeOf(permission).orElse(null);
            if (scope == null || scope.all() || scope.branchIds().size() != 1) {
                throw ApiException.validation(List.of(new FieldProblem(
                        field, "branch_required", "Name the branch; your role covers more than one.")));
            }
            branch = scope.branchIds().iterator().next();
        }
        if (!principal.may(permission, branch) || active(branch).isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem(field, "unknown_branch", "No such active branch in your scope.")));
        }
        return branch;
    }
}
