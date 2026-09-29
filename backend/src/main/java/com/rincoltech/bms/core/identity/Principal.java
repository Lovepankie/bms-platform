package com.rincoltech.bms.core.identity;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated user of the current request: who, which kind, which permissions, and which
 * branches (chapter 8 section 8.3). {@code allBranches} is true when some role assignment has no
 * branch (NULL {@code branch_id}, "all branches of the tenant").
 */
public record Principal(UUID userId, String kind, Set<String> permissions, boolean allBranches, Set<UUID> branchIds) {

    public Principal {
        permissions = Set.copyOf(permissions);
        branchIds = Set.copyOf(branchIds);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean canSeeBranch(UUID branchId) {
        return allBranches || branchIds.contains(branchId);
    }
}
