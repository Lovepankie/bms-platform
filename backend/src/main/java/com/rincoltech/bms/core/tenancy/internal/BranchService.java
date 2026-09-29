package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.BranchDeactivationGuard;
import com.rincoltech.bms.core.tenancy.PlanLimits;
import com.rincoltech.bms.core.tenancy.internal.BranchController.BranchResponse;
import com.rincoltech.bms.core.tenancy.internal.BranchController.CreateBranchRequest;
import com.rincoltech.bms.core.tenancy.internal.BranchController.UpdateBranchRequest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch administration (FR-BR-01). Reads are filtered by the caller's scope for
 * {@code core.branches.read}: a branch outside it is indistinguishable from a missing one
 * (NFR-ISO-04).
 */
@Service
class BranchService {

    private static final String COLUMNS =
            "id, code, name, location, is_head_office, status, created_at, updated_at, version";
    private static final String READ = "core.branches.read";

    private final JdbcClient jdbc;
    private final PlanLimits planLimits;
    private final AuditLog audit;
    private final List<BranchDeactivationGuard> guards;

    BranchService(JdbcClient jdbc, PlanLimits planLimits, AuditLog audit, List<BranchDeactivationGuard> guards) {
        this.jdbc = jdbc;
        this.planLimits = planLimits;
        this.audit = audit;
        this.guards = List.copyOf(guards);
    }

    @Transactional(readOnly = true)
    List<BranchResponse> list() {
        Principal principal = CurrentPrincipal.require();
        return jdbc
                .sql("SELECT " + COLUMNS + " FROM branches ORDER BY is_head_office DESC, code")
                .query(BranchService::map)
                .list()
                .stream()
                .filter(b -> principal.may(READ, b.id()))
                .toList();
    }

    @Transactional(readOnly = true)
    BranchResponse get(UUID branchId) {
        Principal principal = CurrentPrincipal.require();
        return find(branchId).filter(b -> principal.may(READ, b.id())).orElseThrow(ApiException::notFound);
    }

    @Transactional
    BranchResponse create(CreateBranchRequest request) {
        long active = jdbc.sql("SELECT count(*) FROM branches WHERE status = 'active'")
                .query(Long.class)
                .single();
        planLimits.checkRoomFor(PlanLimits.MAX_BRANCHES, active);
        boolean codeTaken = jdbc.sql("SELECT count(*) FROM branches WHERE code = ?")
                        .param(request.code())
                        .query(Long.class)
                        .single()
                > 0;
        if (codeTaken) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "duplicate_branch_code", "Duplicate branch code", "The code is in use.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO branches (id, tenant_id, code, name, location)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """)
                .params(id, request.code(), request.name().trim(), request.location())
                .update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", request.code());
        after.put("name", request.name().trim());
        after.put("location", request.location());
        audit.record(AuditLog.Entry.created("core.branch.created", "core.branch", id, id, after));
        return find(id).orElseThrow();
    }

    @Transactional
    BranchResponse update(UUID branchId, String ifMatch, UpdateBranchRequest request) {
        int expected = Versions.fromIfMatch(ifMatch);
        BranchResponse before = lockForUpdate(branchId);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = request.name() == null ? before.name() : request.name().trim();
        String location = request.location() == null ? before.location() : request.location();
        jdbc.sql("UPDATE branches SET name = ?, location = ?, updated_at = now(), version = version + 1 WHERE id = ?")
                .params(name, location, branchId)
                .update();
        audit.record(new AuditLog.Entry(
                "core.branch.updated",
                "core.branch",
                branchId,
                branchId,
                values(before.name(), before.location()),
                values(name, location)));
        return find(branchId).orElseThrow();
    }

    @Transactional
    BranchResponse deactivate(UUID branchId) {
        BranchResponse before = lockForUpdate(branchId);
        if (before.isHeadOffice()) {
            throw ApiException.rule("head_office_required", "The head office cannot be deactivated.");
        }
        if (!"active".equals(before.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The branch is already inactive.");
        }
        if (guards.stream().anyMatch(g -> g.hasOpenAccounts(branchId))) {
            throw ApiException.rule(
                    "branch_has_open_accounts", "The branch still has open loans, savings or investments.");
        }
        jdbc.sql("UPDATE branches SET status = 'inactive', updated_at = now(), version = version + 1 WHERE id = ?")
                .param(branchId)
                .update();
        audit.record(new AuditLog.Entry(
                "core.branch.deactivated",
                "core.branch",
                branchId,
                branchId,
                Map.of("status", "active"),
                Map.of("status", "inactive")));
        return find(branchId).orElseThrow();
    }

    private BranchResponse lockForUpdate(UUID branchId) {
        Principal principal = CurrentPrincipal.require();
        return jdbc.sql("SELECT " + COLUMNS + " FROM branches WHERE id = ? FOR UPDATE")
                .param(branchId)
                .query(BranchService::map)
                .optional()
                .filter(b -> principal.may("core.branches.manage", b.id()))
                .orElseThrow(ApiException::notFound);
    }

    private Optional<BranchResponse> find(UUID branchId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM branches WHERE id = ?")
                .param(branchId)
                .query(BranchService::map)
                .optional();
    }

    private static Map<String, Object> values(String name, String location) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("location", location);
        return m;
    }

    private static BranchResponse map(ResultSet rs, int n) throws SQLException {
        return new BranchResponse(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("location"),
                rs.getBoolean("is_head_office"),
                rs.getString("status"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getInt("version"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
