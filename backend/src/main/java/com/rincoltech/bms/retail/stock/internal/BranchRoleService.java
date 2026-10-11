package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.stock.BranchRole;
import com.rincoltech.bms.retail.stock.BranchRoles;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch roles (ADR-033, docs/specs/trade-capabilities.md section 2; table {@code
 * retail_branch_roles}). A missing row means {@code both}. Roles are read and changed only with
 * the {@code branch_roles} capability on; the sales path asks {@link #requireMaySell} and nothing
 * else in retail is gated by a role.
 */
@Service
class BranchRoleService implements BranchRoles {

    static final String PERMISSION = "retail.branch_role.manage";

    private final JdbcClient jdbc;
    private final RetailCapabilities capabilities;
    private final RetailBranchContext context;
    private final Branches branches;
    private final AuditLog audit;

    BranchRoleService(
            JdbcClient jdbc,
            RetailCapabilities capabilities,
            RetailBranchContext context,
            Branches branches,
            AuditLog audit) {
        this.jdbc = jdbc;
        this.capabilities = capabilities;
        this.context = context;
        this.branches = branches;
        this.audit = audit;
    }

    record Item(UUID branchId, String code, String name, String role, boolean canSell) {}

    @Override
    @Transactional(readOnly = true)
    public BranchRole roleOf(UUID branchId) {
        if (!capabilities.enabled(RetailCapabilities.BRANCH_ROLES)) {
            return BranchRole.BOTH;
        }
        return stored(branchId);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireMaySell(UUID branchId) {
        if (!roleOf(branchId).sells()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "branch_role_forbids_sales",
                    "This branch does not sell",
                    "The branch is a store, so it cannot make a sale. Sell from a shop.",
                    List.of(new FieldProblem("branch_id", "branch_role_forbids_sales", "This branch is a store.")));
        }
    }

    /** The active branches the caller may read stock in, with their roles. */
    @Transactional(readOnly = true)
    List<Item> list() {
        capabilities.require(RetailCapabilities.BRANCH_ROLES);
        Map<UUID, BranchRole> roles = new HashMap<>();
        jdbc.sql("SELECT branch_id, role FROM retail_branch_roles")
                .query((rs, n) -> {
                    UUID branch = rs.getObject("branch_id", UUID.class);
                    BranchRole.parse(rs.getString("role")).ifPresent(r -> roles.put(branch, r));
                    return 0;
                })
                .list();
        return context.visible("retail.stock.read").stream()
                .filter(Branches.Branch::active)
                .map(b -> {
                    BranchRole r = roles.getOrDefault(b.id(), BranchRole.BOTH);
                    return new Item(b.id(), b.code(), b.name(), r.wire(), r.sells());
                })
                .toList();
    }

    @Transactional
    Item change(UUID branchId, String role) {
        capabilities.require(RetailCapabilities.BRANCH_ROLES);
        if (!CurrentPrincipal.require().may(PERMISSION, branchId)) {
            throw ApiException.notFound();
        }
        Branches.Branch branch = branches.all().stream()
                .filter(b -> b.id().equals(branchId))
                .findFirst()
                .orElseThrow(ApiException::notFound);
        BranchRole next = BranchRole.parse(role)
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("role", "invalid", "Use store, shop or both."))));
        BranchRole before = stored(branchId);
        if (before != next) {
            if (hasOpenWork(branchId)) {
                throw ApiException.rule("branch_has_open_work", "A transfer touching this branch is not yet received.");
            }
            jdbc.sql("""
                            INSERT INTO retail_branch_roles (tenant_id, branch_id, role, changed_by, changed_at)
                            VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, now())
                            ON CONFLICT (tenant_id, branch_id)
                            DO UPDATE SET role = EXCLUDED.role, changed_by = EXCLUDED.changed_by, changed_at = now()
                            """)
                    .params(branchId, next.wire(), CurrentPrincipal.require().userId())
                    .update();
            audit.record(new AuditLog.Entry(
                    "retail.branch_role.changed",
                    "retail.branch_role",
                    branchId,
                    branchId,
                    Map.of("role", before.wire()),
                    Map.of("role", next.wire())));
        }
        return new Item(branch.id(), branch.code(), branch.name(), next.wire(), next.sells());
    }

    private BranchRole stored(UUID branchId) {
        return jdbc.sql("SELECT role FROM retail_branch_roles WHERE branch_id = ?")
                .param(branchId)
                .query(String.class)
                .optional()
                .flatMap(BranchRole::parse)
                .orElse(BranchRole.BOTH);
    }

    /** A dispatched, unreceived transfer (the two-step transfer of a later slice) pins the branch's role. */
    private boolean hasOpenWork(UUID branchId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_transfers
                                        WHERE status = 'in_transit' AND (from_branch_id = ? OR to_branch_id = ?))
                        """).params(branchId, branchId).query(Boolean.class).single();
    }
}
