package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.identity.TenantAdmins;
import com.rincoltech.bms.core.platform.internal.PlatformController.CreateTenantRequest;
import com.rincoltech.bms.core.platform.internal.PlatformController.CreatedTenantResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.FirstAdminInvitation;
import com.rincoltech.bms.core.platform.internal.PlatformController.PlanResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.TenantResponse;
import com.rincoltech.bms.core.tenancy.ModuleManifest;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant lifecycle for platform operators. Every change is written to {@code platform_audit_log};
 * tenant creation also writes the tenant's own first audit row.
 */
@Service
class PlatformService {

    private final JdbcClient jdbc;
    private final TenantAdmins tenantAdmins;
    private final PlatformHost hosts;
    private final AuditLog audit;
    private final PlatformAuditLog platformAudit;
    private final TransactionTemplate transactions;
    private final Set<String> moduleKeys;

    PlatformService(
            JdbcClient jdbc,
            TenantAdmins tenantAdmins,
            PlatformHost hosts,
            AuditLog audit,
            PlatformAuditLog platformAudit,
            PlatformTransactionManager transactionManager,
            List<ModuleManifest> manifests) {
        this.jdbc = jdbc;
        this.tenantAdmins = tenantAdmins;
        this.hosts = hosts;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.moduleKeys = manifests.stream().map(ModuleManifest::key).collect(Collectors.toUnmodifiableSet());
    }

    @Transactional(readOnly = true)
    List<PlanResponse> plans() {
        return jdbc.sql("""
                        SELECT code, name, max_branches, max_staff_users, max_active_members, allowed_modules
                          FROM plans WHERE is_active ORDER BY code
                        """)
                .query((rs, n) -> new PlanResponse(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getObject("max_branches", Integer.class),
                        rs.getObject("max_staff_users", Integer.class),
                        rs.getObject("max_active_members", Integer.class),
                        strings(rs.getArray("allowed_modules"))))
                .list();
    }

    @Transactional(readOnly = true)
    List<TenantResponse> tenants() {
        return jdbc.sql("SELECT * FROM platform_list_tenants()")
                .query(PlatformService::tenant)
                .list();
    }

    @Transactional(readOnly = true)
    TenantResponse tenant(UUID tenantId) {
        return jdbc.sql("SELECT * FROM platform_list_tenants() WHERE id = ?")
                .param(tenantId)
                .query(PlatformService::tenant)
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    /**
     * FR-TEN-01, in one transaction bound to the new tenant: the tenant, its subscription, settings,
     * head office and modules (with the chart of accounts of each), the first tenant admin with a
     * 72 hour invitation, and the audit rows. The transaction opens inside
     * {@code TenantContext.callAs}, so the transaction manager binds the new tenant as it binds any
     * other; the tenant id is generated here and never read from the request.
     */
    CreatedTenantResponse create(CreateTenantRequest request) {
        UUID operator = CurrentPrincipal.require().userId();
        String slug = request.slug().trim();
        if (!hosts.isValidSlug(slug)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "slug", "invalid_slug", "3 to 63 lower case letters, digits or hyphens; not a reserved name.")));
        }
        String timezone = request.timezone() == null ? "Africa/Kampala" : request.timezone();
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.validation(List.of(new FieldProblem("timezone", "invalid", "Unknown time zone.")));
        }
        List<String> modules = checkModules(request.modules() == null ? List.of() : request.modules());
        if (request.admin().email() == null && request.admin().phone() == null) {
            throw ApiException.validation(
                    List.of(new FieldProblem("admin.email", "required", "Give the admin's email or phone.")));
        }
        boolean taken =
                transactions.execute(status -> jdbc.sql("SELECT count(*) FROM platform_list_tenants() WHERE slug = ?")
                                .param(slug)
                                .query(Long.class)
                                .single())
                        > 0;
        if (taken) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "duplicate_slug", "Slug taken", "Another tenant has this slug.");
        }

        UUID tenantId = UUID.randomUUID();
        UUID headOffice = UUID.randomUUID();
        TenantAdmins.Invitation invitation = TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    try {
                        jdbc.sql("SELECT platform_create_tenant(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                                .params(
                                        tenantId,
                                        slug,
                                        request.name().trim(),
                                        request.planCode(),
                                        request.currency() == null ? "UGX" : request.currency(),
                                        timezone,
                                        modules.toArray(String[]::new),
                                        headOffice,
                                        request.headOffice().code(),
                                        request.headOffice().name().trim(),
                                        operator)
                                .query()
                                .singleRow();
                    } catch (DataIntegrityViolationException e) {
                        throw ApiException.rule(
                                "invalid_tenant", "The plan, currency or modules are not valid for a new tenant.");
                    }
                    TenantAdmins.Invitation admin = tenantAdmins.inviteFirstAdmin(
                            request.admin().fullName(),
                            request.admin().email(),
                            request.admin().phone(),
                            operator);
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("slug", slug);
                    after.put("plan", request.planCode());
                    after.put("modules", modules);
                    after.put("head_office_branch_id", headOffice);
                    audit.record(
                            AuditLog.Entry.created("core.tenant.created", "core.tenant", tenantId, headOffice, after),
                            operator,
                            "platform");
                    platformAudit.record("platform.tenant.created", operator, tenantId, after);
                    return admin;
                }));
        return new CreatedTenantResponse(
                transactions.execute(status -> tenant(tenantId)),
                headOffice,
                new FirstAdminInvitation(invitation.userId(), invitation.url(), invitation.expiresAt()));
    }

    @Transactional
    TenantResponse setModules(UUID tenantId, List<String> requested) {
        TenantResponse before = tenant(tenantId);
        List<String> modules = checkModules(requested);
        try {
            jdbc.sql("SELECT platform_set_tenant_modules(?, ?, ?)")
                    .params(
                            tenantId,
                            modules.toArray(String[]::new),
                            CurrentPrincipal.require().userId())
                    .query()
                    .singleRow();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.rule("module_not_allowed", "The tenant's plan does not allow these modules.");
        }
        platformAudit.record(
                "platform.tenant.modules_changed",
                CurrentPrincipal.require().userId(),
                tenantId,
                Map.of("before", before.modules(), "after", modules));
        return tenant(tenantId);
    }

    @Transactional
    TenantResponse setSubscription(UUID tenantId, String status, LocalDate nextChange) {
        tenant(tenantId);
        String previous = jdbc.sql("SELECT platform_set_subscription_status(?, ?, ?, ?)")
                .params(tenantId, status, nextChange, CurrentPrincipal.require().userId())
                .query(String.class)
                .single();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", previous);
        data.put("after", status);
        data.put("next_status_change_on", nextChange == null ? null : nextChange.toString());
        platformAudit.record(
                "platform.tenant.subscription_changed",
                CurrentPrincipal.require().userId(),
                tenantId,
                data);
        return tenant(tenantId);
    }

    /** FR-IAM-12, platform path: for a tenant whose only admin lost their second factor. */
    void resetAdminMfa(UUID tenantId, UUID userId) {
        UUID operator = CurrentPrincipal.require().userId();
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    tenantAdmins.resetAdminMfa(userId, operator);
                    platformAudit.record(
                            "platform.tenant_admin.mfa_reset", operator, tenantId, Map.of("user_id", userId));
                    return null;
                }));
    }

    private List<String> checkModules(List<String> modules) {
        List<String> unique = modules.stream().distinct().sorted().toList();
        for (String module : unique) {
            if (!moduleKeys.contains(module)) {
                throw ApiException.validation(
                        List.of(new FieldProblem("modules", "invalid", "Unknown module " + module + ".")));
            }
        }
        return unique;
    }

    private static TenantResponse tenant(ResultSet rs, int n) throws SQLException {
        return new TenantResponse(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("plan_code"),
                rs.getString("currency"),
                rs.getString("timezone"),
                rs.getString("subscription_status"),
                rs.getObject("next_status_change_on", LocalDate.class),
                strings(rs.getArray("modules")),
                rs.getTimestamp("created_at").toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
