package com.rincoltech.bms.kernel;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated user of the current request: who, which kind, which session, and for each
 * permission the branches it applies in (chapter 8 section 8.3.1). A user may hold several roles,
 * each with its own branch scope; the effective permission set is the union, but the branch scope
 * applies per permission: a user who is branch manager at A and loan officer at B holds
 * {@code lending.loans.approve} at A only.
 *
 * @param kind {@code staff}, {@code member} or {@code platform}
 * @param sessionId the {@code auth_sessions} row of a signed-in user; {@code null} for the
 *     development stub
 * @param scopes permission key to the branches it applies in
 */
public record Principal(UUID userId, String kind, UUID sessionId, Map<String, BranchScope> scopes) {

    public Principal {
        scopes = Map.copyOf(scopes);
    }

    /** Every permission in the same scope (the development stub of chapter 7 section 7.4.3). */
    public static Principal uniform(
            UUID userId, String kind, Set<String> permissions, boolean allBranches, Set<UUID> branchIds) {
        BranchScope scope = new BranchScope(allBranches, allBranches ? Set.of() : branchIds);
        Map<String, BranchScope> scopes = new LinkedHashMap<>();
        permissions.forEach(p -> scopes.put(p, scope));
        return new Principal(userId, kind, null, scopes);
    }

    public Set<String> permissions() {
        return scopes.keySet();
    }

    public boolean hasPermission(String permission) {
        return scopes.containsKey(permission);
    }

    public Optional<BranchScope> scopeOf(String permission) {
        return Optional.ofNullable(scopes.get(permission));
    }

    /** True when the permission is held in that branch. */
    public boolean may(String permission, UUID branchId) {
        BranchScope scope = scopes.get(permission);
        return scope != null && scope.covers(branchId);
    }

    /** True when some permission applies in every branch. */
    public boolean allBranches() {
        return scopes.values().stream().anyMatch(BranchScope::all);
    }

    /** The union of the branches named by any permission's scope. */
    public Set<UUID> branchIds() {
        Set<UUID> ids = new HashSet<>();
        scopes.values().forEach(s -> ids.addAll(s.branchIds()));
        return Set.copyOf(ids);
    }

    public boolean canSeeBranch(UUID branchId) {
        return allBranches() || branchIds().contains(branchId);
    }

    /**
     * The branch filter of a list query for one permission (chapter 6 section 6.3.3): {@code null}
     * means every branch (an all-branch scope that asked for nothing); otherwise the requested
     * branches intersected with the scope, or the whole scope when nothing was requested. An empty
     * list means no rows.
     */
    public List<UUID> branchFilter(String permission, List<UUID> requested) {
        List<UUID> asked = requested == null ? List.of() : requested;
        BranchScope scope = scopes.get(permission);
        if (scope == null) {
            return List.of();
        }
        if (scope.all()) {
            return asked.isEmpty() ? null : asked;
        }
        if (asked.isEmpty()) {
            return List.copyOf(scope.branchIds());
        }
        return asked.stream().filter(scope::covers).toList();
    }

    /** Where one permission applies: every branch, or the listed ones. */
    public record BranchScope(boolean all, Set<UUID> branchIds) {

        public BranchScope {
            branchIds = Set.copyOf(branchIds);
        }

        public boolean covers(UUID branchId) {
            return all || branchIds.contains(branchId);
        }

        public BranchScope union(BranchScope other) {
            if (all || other.all) {
                return new BranchScope(true, Set.of());
            }
            Set<UUID> ids = new HashSet<>(branchIds);
            ids.addAll(other.branchIds);
            return new BranchScope(false, ids);
        }
    }
}
