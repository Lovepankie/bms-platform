package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Principal.BranchScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

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
        if (!principal.may(permission, branch) || branches.findActive(branch).isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem(field, "unknown_branch", "No such active branch in your scope.")));
        }
        return branch;
    }
}
