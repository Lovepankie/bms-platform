package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.identity.TenantAdmins;
import com.rincoltech.bms.core.platform.TenantProvisioning;
import com.rincoltech.bms.core.platform.internal.PlatformController.CreateTenantRequest;
import com.rincoltech.bms.core.platform.internal.PlatformController.CreatedTenantResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.FirstAdminInvitation;
import com.rincoltech.bms.core.platform.internal.PlatformController.TenantResponse;
import com.rincoltech.bms.core.tenancy.GoLiveDate;
import com.rincoltech.bms.core.tenancy.ModuleManifest;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class PlatformService implements TenantProvisioning {

    private static final Steps NO_STEPS = new Steps() {
        @Override
        public boolean before() {
            return true;
        }

        @Override
        public void after(Created created) {}
    };

    private final JdbcClient jdbc;
    private final TenantAdmins tenantAdmins;
    private final PlatformHost hosts;
    private final AuditLog audit;
    private final PlatformAuditLog platformAudit;
    private final TransactionTemplate transactions;
    private final Set<String> moduleKeys;
    private final GoLiveDate goLive;

    PlatformService(
            JdbcClient jdbc,
            TenantAdmins tenantAdmins,
            PlatformHost hosts,
            AuditLog audit,
            PlatformAuditLog platformAudit,
            PlatformTransactionManager transactionManager,
            List<ModuleManifest> manifests,
            GoLiveDate goLive) {
        this.goLive = goLive;
        this.jdbc = jdbc;
        this.tenantAdmins = tenantAdmins;
        this.hosts = hosts;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.moduleKeys = manifests.stream().map(ModuleManifest::key).collect(Collectors.toUnmodifiableSet());
    }

    private static final List<String> STATUSES = List.of("trial", "active", "past_due", "suspended", "cancelled");

    /**
     * Every tenant from {@code platform_tenant_overview()}, filtered: {@code q} matches the name, the slug or the owner's
     * email (any case, as typed, no wildcards), {@code statuses} the subscription status, {@code module} a module switched on.
     */
    @Transactional(readOnly = true)
    List<TenantResponse> tenants(String q, List<String> statuses, String module) {
        StringBuilder sql = new StringBuilder("SELECT * FROM platform_tenant_overview(NULL) o WHERE true");
        List<Object> params = new ArrayList<>();
        String typed = q == null ? "" : q.trim();
        if (typed.length() > 100) {
            throw ApiException.validation(List.of(new FieldProblem("q", "too_long", "Type at most 100 characters.")));
        }
        if (!typed.isEmpty()) {
            String like = "%" + typed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (o.name ILIKE ? OR o.slug ILIKE ? OR coalesce(o.owner_email, '') ILIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        List<String> wanted =
                statuses == null ? List.of() : statuses.stream().distinct().toList();
        for (String status : wanted) {
            if (!STATUSES.contains(status)) {
                throw ApiException.validation(
                        List.of(new FieldProblem("status", "invalid", "Unknown status " + status + ".")));
            }
        }
        if (!wanted.isEmpty()) {
            sql.append(" AND o.subscription_status = ANY (?)");
            params.add(wanted.toArray(String[]::new));
        }
        if (module != null && !module.isBlank()) {
            if (!moduleKeys.contains(module)) {
                throw ApiException.validation(
                        List.of(new FieldProblem("module", "invalid", "Unknown module " + module + ".")));
            }
            sql.append(" AND ? = ANY (o.modules)");
            params.add(module);
        }
        sql.append(" ORDER BY o.name, o.slug");
        return jdbc.sql(sql.toString()).params(params).query(this::tenantRow).list();
    }

    @Transactional(readOnly = true)
    TenantResponse tenant(UUID tenantId) {
        return jdbc.sql("SELECT * FROM platform_tenant_overview(?)")
                .param(tenantId)
                .query(this::tenantRow)
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    /** FR-TEN-01 through the platform API: {@link #create(NewTenant, Steps)} with no extra steps. */
    CreatedTenantResponse create(CreateTenantRequest request) {
        Created created = create(
                        new NewTenant(
                                request.name(),
                                request.slug(),
                                request.planCode(),
                                request.currency(),
                                request.timezone(),
                                request.modules() == null ? List.of() : request.modules(),
                                request.headOffice().code(),
                                request.headOffice().name(),
                                request.admin().fullName(),
                                request.admin().email(),
                                request.admin().phone(),
                                "trial"),
                        NO_STEPS)
                .orElseThrow();
        return new CreatedTenantResponse(
                transactions.execute(status -> tenant(created.tenantId())),
                created.headOfficeBranchId(),
                new FirstAdminInvitation(
                        created.adminUserId(), created.invitationUrl(), created.invitationExpiresAt()));
    }

    /**
     * FR-TEN-01, in one transaction bound to the new tenant: the tenant, its subscription, settings,
     * head office and modules (with the chart of accounts of each), the first tenant admin with a
     * 72 hour invitation, and the audit rows, with the caller's steps before and after. The
     * transaction opens inside {@code TenantContext.callAs}, so the transaction manager binds the
     * new tenant as it binds any other; the tenant id is generated here and never read from the
     * request.
     */
    @Override
    public Optional<Created> create(NewTenant request, Steps steps) {
        UUID operator = CurrentPrincipal.require().userId();
        String slug = request.slug() == null ? "" : request.slug().trim();
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
        if (request.adminEmail() == null && request.adminPhone() == null) {
            throw ApiException.validation(
                    List.of(new FieldProblem("admin.email", "required", "Give the admin's email or phone.")));
        }
        String subscription = request.subscriptionStatus() == null ? "trial" : request.subscriptionStatus();
        if (!subscription.equals("trial") && !subscription.equals("active")) {
            throw new IllegalArgumentException("a new tenant starts as trial or active");
        }

        UUID tenantId = UUID.randomUUID();
        UUID headOffice = UUID.randomUUID();
        return Optional.ofNullable(TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    if (!steps.before()) {
                        return null;
                    }
                    boolean taken = jdbc.sql("SELECT count(*) FROM platform_list_tenants() WHERE slug = ?")
                                    .param(slug)
                                    .query(Long.class)
                                    .single()
                            > 0;
                    if (taken) {
                        throw new ApiException(
                                HttpStatus.CONFLICT, "duplicate_slug", "Slug taken", "Another tenant has this slug.");
                    }
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
                                        request.headOfficeCode(),
                                        request.headOfficeName().trim(),
                                        operator)
                                .query()
                                .singleRow();
                        if (subscription.equals("active")) {
                            jdbc.sql("SELECT platform_set_subscription_status(?, 'active', NULL, ?)")
                                    .params(tenantId, operator)
                                    .query(String.class)
                                    .single();
                        }
                    } catch (DataIntegrityViolationException e) {
                        // A tenant created concurrently with the same slug passes the count above
                        // and meets the unique constraint here (review N7).
                        if (String.valueOf(e.getMessage()).contains("tenants_slug_key")) {
                            throw new ApiException(
                                    HttpStatus.CONFLICT,
                                    "duplicate_slug",
                                    "Slug taken",
                                    "Another tenant has this slug.");
                        }
                        throw ApiException.rule(
                                "invalid_tenant", "The plan, currency or modules are not valid for a new tenant.");
                    }
                    TenantAdmins.Invitation admin = tenantAdmins.inviteFirstAdmin(
                            request.adminFullName(), request.adminEmail(), request.adminPhone(), operator);
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("slug", slug);
                    after.put("plan", request.planCode());
                    after.put("modules", modules);
                    after.put("head_office_branch_id", headOffice);
                    after.put("subscription_status", subscription);
                    audit.record(
                            AuditLog.Entry.created("core.tenant.created", "core.tenant", tenantId, headOffice, after),
                            operator,
                            "platform");
                    platformAudit.record("platform.tenant.created", operator, tenantId, after);
                    Created created = new Created(
                            tenantId,
                            slug,
                            headOffice,
                            admin.userId(),
                            admin.url(),
                            admin.expiresAt(),
                            admin.twoStepRequired());
                    steps.after(created);
                    return created;
                })));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean slugAvailable(String slug) {
        return slug != null
                && hosts.isValidSlug(slug)
                && jdbc.sql("SELECT count(*) FROM platform_list_tenants() WHERE slug = ?")
                                .param(slug)
                                .query(Long.class)
                                .single()
                        == 0;
    }

    @Transactional
    TenantResponse setModules(UUID tenantId, List<String> requested, String reason) {
        TenantResponse before = tenant(tenantId);
        List<String> modules = checkModules(requested);
        if (modules.equals(before.modules().stream().sorted().toList())) {
            return before;
        }
        switchModules(tenantId, modules, CurrentPrincipal.require().userId(), reason);
        return tenant(tenantId);
    }

    /**
     * FR-TEN-03: switches the tenant's modules to exactly these through {@code platform_set_tenant_modules} (data kept), in
     * the caller's transaction, and audits the change. {@code operator} is null when the Owner's own change or the nightly
     * job switches them (ADR-073).
     */
    @Transactional
    void switchModules(UUID tenantId, List<String> requested, UUID operator, String reason) {
        TenantResponse before = tenant(tenantId);
        List<String> modules = checkModules(requested);
        try {
            jdbc.sql("SELECT platform_set_tenant_modules(?, ?, ?)")
                    .params(tenantId, modules.toArray(String[]::new), operator)
                    .query()
                    .singleRow();
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("payment_methods_remap_blocks_retail")) {
                throw ApiException.rule(
                        "payment_methods_remap_blocks_retail",
                        "Retail posts cash and bank to the seeded accounts. Map cash and bank back to them"
                                + " (clear the payment method mappings) before switching retail on.");
            }
            throw ApiException.rule("module_not_allowed", "The tenant's plan does not allow these modules.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", before.modules());
        data.put("after", modules);
        data.put("reason", reason.trim());
        platformAudit.record("platform.tenant.modules_changed", operator, tenantId, data);
    }

    /**
     * FR-TEN-06: a suspended or cancelled tenant goes back to the status it had when it was suspended (kept in
     * {@code subscriptions.suspended_from}; a free month stays a free month with the same last day), or to active when that
     * is not known. A tenant that is not suspended or cancelled changes nothing.
     */
    @Transactional
    TenantResponse resume(UUID tenantId, String reason) {
        TenantResponse current = tenant(tenantId);
        String status = current.subscriptionStatus();
        if (!"suspended".equals(status) && !"cancelled".equals(status)) {
            return current;
        }
        String back = current.suspendedFrom() == null ? "active" : current.suspendedFrom();
        return setSubscription(tenantId, back, null, reason);
    }

    /**
     * FR-TEN-05, FR-TEN-06: moves the subscription with the operator's reason. A move to the status it already has (with no
     * new next change date) changes nothing and writes no audit row, so a repeated suspend or resume is harmless.
     */
    @Transactional
    TenantResponse setSubscription(UUID tenantId, String status, LocalDate nextChange, String reason) {
        TenantResponse current = tenant(tenantId);
        if (status.equals(current.subscriptionStatus())
                && (nextChange == null || nextChange.equals(current.nextStatusChangeOn()))) {
            return current;
        }
        String previous = jdbc.sql("SELECT platform_set_subscription_status(?, ?, ?, ?)")
                .params(tenantId, status, nextChange, CurrentPrincipal.require().userId())
                .query(String.class)
                .single();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", previous);
        data.put("after", status);
        data.put("next_status_change_on", nextChange == null ? null : nextChange.toString());
        data.put("reason", reason == null ? null : reason.trim());
        platformAudit.record(
                "platform.tenant.subscription_changed",
                CurrentPrincipal.require().userId(),
                tenantId,
                data);
        return tenant(tenantId);
    }

    /** FR-IAM-12, platform path: for a tenant whose only admin lost their second factor. */
    void resetAdminMfa(UUID tenantId, UUID userId, String reason) {
        UUID operator = CurrentPrincipal.require().userId();
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    tenantAdmins.resetAdminMfa(userId, operator);
                    platformAudit.record(
                            "platform.tenant_admin.mfa_reset",
                            operator,
                            tenantId,
                            Map.of("user_id", userId, "reason", reason.trim()));
                    return null;
                }));
    }

    /**
     * Lifts the sign-in lock of one staff user of the tenant (ADR-059), with a reason: audited in the tenant's log and in the
     * platform log. A platform operator is not a tenant user, so this never unlocks an operator.
     */
    void unlockSignIn(UUID tenantId, UUID userId, String reason) {
        UUID operator = CurrentPrincipal.require().userId();
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    tenantAdmins.unlockSignIn(userId, operator, reason.trim());
                    platformAudit.record(
                            "platform.tenant_user.sign_in_unlocked",
                            operator,
                            tenantId,
                            Map.of("user_id", userId, "reason", reason.trim()));
                    return null;
                }));
    }

    /** Read-only: what {@link #resetAdminMfa} would refuse, checked before the operator's authenticator code is spent. */
    void requireTenantAdmin(UUID tenantId, UUID userId) {
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    tenantAdmins.requireTenantAdmin(userId);
                    return null;
                }));
    }

    /** ADR-071: the operator sets a business's go-live date, audited in the tenant's log and the platform's. */
    void setGoLive(UUID tenantId, LocalDate date, String reason) {
        UUID operator = CurrentPrincipal.require().userId();
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    goLive.setByOperator(date, operator, reason);
                    platformAudit.record(
                            "platform.tenant.go_live_set",
                            operator,
                            tenantId,
                            Map.of("go_live_date", date.toString(), "reason", reason));
                    return null;
                }));
    }

    /** Read-only: what {@link #transferOwnership} would refuse, checked before the operator's authenticator code is spent. */
    void requireOwnerCandidate(UUID tenantId, UUID userId) {
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    tenantAdmins.requireOwnerCandidate(userId);
                    return null;
                }));
    }

    /**
     * ADR-062, FR-IAM-18: makes an active administrator the Owner of the business, with a reason, for an Owner who lost access.
     * Audited in the tenant's log (by the operator) and in the platform log, with the previous and the new Owner.
     */
    void transferOwnership(UUID tenantId, UUID userId, String reason) {
        UUID operator = CurrentPrincipal.require().userId();
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    UUID previous = tenantAdmins.transferOwnership(userId, operator, reason.trim());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("before", previous);
                    data.put("after", userId);
                    data.put("reason", reason.trim());
                    platformAudit.record("platform.tenant.owner_transferred", operator, tenantId, data);
                    return null;
                }));
    }

    /** Read-only: what {@link #reissueFirstAdminInvitation} would refuse, before the operator's code is spent. */
    void requireInvitedFirstAdmin(UUID tenantId, UUID userId) {
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    tenantAdmins.requireInvitedFirstAdmin(userId);
                    return null;
                }));
    }

    /** ADR-062: a new link for the first administrator (the Owner) who never joined; audited in both logs with the reason. */
    TenantAdmins.Invitation reissueFirstAdminInvitation(UUID tenantId, UUID userId, String reason) {
        UUID operator = CurrentPrincipal.require().userId();
        transactions.execute(status -> tenant(tenantId));
        return TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    TenantAdmins.Invitation invitation = tenantAdmins.reissueFirstAdminInvitation(userId, operator);
                    platformAudit.record(
                            "platform.tenant_owner.invitation_reissued",
                            operator,
                            tenantId,
                            Map.of("user_id", userId, "reason", reason.trim()));
                    return invitation;
                }));
    }

    /** Read-only: what {@link #resetAdminSignIn} would refuse, checked before the operator's authenticator code is spent. */
    void requireResettableAdmin(UUID tenantId, UUID userId) {
        transactions.execute(status -> tenant(tenantId));
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    tenantAdmins.requireResettableAdmin(userId);
                    return null;
                }));
    }

    /** FR-IAM-15, platform path: a tenant admin who forgot the password; nobody in the tenant may reset an admin. */
    TenantAdmins.ResetLink resetAdminSignIn(UUID tenantId, UUID userId, String reason) {
        UUID operator = CurrentPrincipal.require().userId();
        transactions.execute(status -> tenant(tenantId));
        return TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    TenantAdmins.ResetLink link = tenantAdmins.resetAdminSignIn(userId, operator);
                    platformAudit.record(
                            "platform.tenant_admin.sign_in_reset",
                            operator,
                            tenantId,
                            Map.of("user_id", userId, "reason", reason.trim()));
                    return link;
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

    private TenantResponse tenantRow(ResultSet rs, int n) throws SQLException {
        String slug = rs.getString("slug");
        return new TenantResponse(
                rs.getObject("id", UUID.class),
                slug,
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("plan_code"),
                rs.getString("plan_name"),
                rs.getString("currency"),
                rs.getString("timezone"),
                rs.getString("subscription_status"),
                rs.getObject("next_status_change_on", LocalDate.class),
                rs.getObject("trial_ends_on", LocalDate.class),
                rs.getObject("paid_from", LocalDate.class),
                rs.getObject("paid_until", LocalDate.class),
                strings(rs.getArray("modules")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("owner_name"),
                rs.getString("owner_email"),
                instant(rs.getTimestamp("last_sign_in_at")),
                hosts.tenantOrigin(slug),
                rs.getString("suspended_from"),
                rs.getBoolean("is_pilot"));
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
