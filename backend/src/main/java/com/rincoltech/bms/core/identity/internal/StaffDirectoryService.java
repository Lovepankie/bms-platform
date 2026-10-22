package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.StaffDirectory;
import com.rincoltech.bms.kernel.Principal.BranchScope;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** {@link StaffDirectory} over the same grants that sign-in builds a principal from. */
@Service
class StaffDirectoryService implements StaffDirectory {

    private final JdbcClient jdbc;
    private final Grants grants;

    StaffDirectoryService(JdbcClient jdbc, Grants grants) {
        this.jdbc = jdbc;
        this.grants = grants;
    }

    @Override
    public boolean mayAct(UUID userId, String permission, UUID branchId) {
        boolean active = jdbc.sql("SELECT count(*) FROM users WHERE id = ? AND kind = 'staff' AND status = 'active'")
                        .param(userId)
                        .query(Long.class)
                        .single()
                == 1;
        if (!active) {
            return false;
        }
        BranchScope scope = grants.scopes(userId).get(permission);
        return scope != null && scope.covers(branchId);
    }
}
