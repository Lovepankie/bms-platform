package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.kernel.Principal.BranchScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A staff user's permissions with the branches each applies in (chapter 8 section 8.3.1): the
 * union over the user's active role assignments, each assignment's branch scope applied to the
 * permissions of its role. An assignment to an inactive branch grants nothing.
 */
@Component
class Grants {

    private final JdbcClient jdbc;

    Grants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Assignment(String roleKey, String roleName, UUID branchId) {}

    List<Assignment> assignments(UUID userId) {
        return jdbc.sql("""
                        SELECT a.role_key, r.name, a.branch_id FROM user_role_assignments a
                          JOIN roles r ON r.key = a.role_key
                         WHERE a.user_id = ? AND a.revoked_at IS NULL
                         ORDER BY a.role_key, a.branch_id NULLS FIRST
                        """)
                .param(userId)
                .query((rs, n) -> new Assignment(
                        rs.getString("role_key"), rs.getString("name"), rs.getObject("branch_id", UUID.class)))
                .list();
    }

    Map<String, BranchScope> scopes(UUID userId) {
        Map<String, BranchScope> scopes = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT rp.permission_key, a.branch_id
                          FROM user_role_assignments a
                          JOIN role_permissions rp ON rp.role_key = a.role_key
                         WHERE a.user_id = ? AND a.revoked_at IS NULL
                           AND (a.branch_id IS NULL
                                OR EXISTS (SELECT 1 FROM branches b WHERE b.id = a.branch_id AND b.status = 'active'))
                        """)
                .param(userId)
                .query((rs, n) -> {
                    UUID branch = rs.getObject("branch_id", UUID.class);
                    BranchScope scope =
                            branch == null ? new BranchScope(true, Set.of()) : new BranchScope(false, Set.of(branch));
                    scopes.merge(rs.getString("permission_key"), scope, BranchScope::union);
                    return null;
                })
                .list();
        return scopes;
    }
}
