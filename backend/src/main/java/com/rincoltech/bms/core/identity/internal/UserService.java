package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.identity.TenantAdmins;
import com.rincoltech.bms.core.identity.internal.UserApi.InvitationLink;
import com.rincoltech.bms.core.identity.internal.UserApi.InviteUserRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.InvitedUserResponse;
import com.rincoltech.bms.core.identity.internal.UserApi.RoleAssignment;
import com.rincoltech.bms.core.identity.internal.UserApi.RoleAssignmentRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.UpdateUserRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.UserPage;
import com.rincoltech.bms.core.identity.internal.UserApi.UserResponse;
import com.rincoltech.bms.core.notifications.Notifier;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlanLimits;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.PhoneNumbers;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff user administration (FR-IAM-01, FR-IAM-02, FR-IAM-08, FR-IAM-12): invitations with the
 * one-time link shown to the inviting admin, role assignments with branch scope, deactivation
 * that ends every session, and the admin reset of a lost second factor. Every change is audited
 * in its own transaction.
 */
@Service
class UserService implements TenantAdmins {

    static final Duration INVITATION_TTL = Duration.ofHours(72);
    static final String TENANT_ADMIN = "tenant_admin";
    private static final String MANAGE = "core.users.manage";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcClient jdbc;
    private final Grants grants;
    private final StaffAccounts accounts;
    private final Passwords passwords;
    private final Branches branches;
    private final PlanLimits planLimits;
    private final CurrentTenant tenant;
    private final PlatformHost hosts;
    private final Notifier notifier;
    private final AuditLog audit;
    private final BusinessClock clock;

    UserService(
            JdbcClient jdbc,
            Grants grants,
            StaffAccounts accounts,
            Passwords passwords,
            Branches branches,
            PlanLimits planLimits,
            CurrentTenant tenant,
            PlatformHost hosts,
            Notifier notifier,
            AuditLog audit,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.grants = grants;
        this.accounts = accounts;
        this.passwords = passwords;
        this.branches = branches;
        this.planLimits = planLimits;
        this.tenant = tenant;
        this.hosts = hosts;
        this.notifier = notifier;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- Invitations (FR-IAM-01) ----------------------------------------------------------

    @Transactional
    InvitedUserResponse invite(InviteUserRequest request) {
        Principal admin = CurrentPrincipal.require();
        List<RoleAssignmentRequest> roles = checkRoles(admin, request.roles());
        UUID id = createInvitedUser(request.fullName(), request.email(), request.phone());
        for (RoleAssignmentRequest role : roles) {
            insertAssignment(id, role, admin.userId());
        }
        UserResponse user = find(id).orElseThrow();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("full_name", user.fullName());
        after.put("email", user.email());
        after.put("phone_e164", user.phoneE164());
        after.put("roles", rolesForAudit(user.roles()));
        audit.record(AuditLog.Entry.created("core.user.invited", "core.user", id, null, after));
        InvitationLink link = issueInvitation(id, admin.userId(), user.email(), user.phoneE164());
        return new InvitedUserResponse(user, link);
    }

    /** A new link for a user still invited; the previous link stops working. */
    @Transactional
    InvitationLink reissueInvitation(UUID userId) {
        Principal admin = CurrentPrincipal.require();
        UserResponse user = lockUser(userId);
        if (!"invited".equals(user.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "Only a user who has not accepted an invitation can get a new one.");
        }
        return issueInvitation(userId, admin.userId(), user.email(), user.phoneE164());
    }

    /**
     * FR-IAM-01: the invitee sets a password with the one-time token and becomes active. Returns
     * the user, whom the caller then signs in through {@link AuthFlow#afterInvitation}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    UUID acceptInvitation(String token, String password) {
        Instant now = clock.now();
        record Pending(UUID id, UUID userId, Instant expiresAt, Instant acceptedAt, Instant revokedAt) {}
        Pending invitation = jdbc.sql("""
                        SELECT id, user_id, expires_at, accepted_at, revoked_at FROM user_invitations
                         WHERE token_hash = ? FOR UPDATE
                        """)
                .param(Secrets.sha256(token))
                .query((rs, n) -> new Pending(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        SessionStore.instant(rs.getTimestamp("expires_at")),
                        SessionStore.instant(rs.getTimestamp("accepted_at")),
                        SessionStore.instant(rs.getTimestamp("revoked_at"))))
                .optional()
                .orElseThrow(UserService::invitationInvalid);
        if (invitation.acceptedAt() != null || invitation.revokedAt() != null) {
            throw invitationInvalid();
        }
        if (!now.isBefore(invitation.expiresAt())) {
            throw ApiException.rule("invitation_expired", "The invitation has expired; ask an admin for a new one.");
        }
        UserResponse user = lockUser(invitation.userId());
        if (!"invited".equals(user.status())) {
            throw invitationInvalid();
        }
        passwords.checkStrength(password, user.email(), user.phoneE164());
        accounts.ensureCredentials(user.id());
        jdbc.sql(
                        "UPDATE user_credentials SET password_hash = ?, password_changed_at = ?, updated_at = ? WHERE user_id = ?")
                .params(passwords.hash(password), Timestamp.from(now), Timestamp.from(now), user.id())
                .update();
        jdbc.sql("UPDATE users SET status = 'active', updated_at = ?, version = version + 1 WHERE id = ?")
                .params(Timestamp.from(now), user.id())
                .update();
        jdbc.sql("UPDATE user_invitations SET accepted_at = ? WHERE id = ?")
                .params(Timestamp.from(now), invitation.id())
                .update();
        audit.record(
                new AuditLog.Entry(
                        "core.invitation.accepted",
                        "core.user",
                        user.id(),
                        null,
                        Map.of("status", "invited"),
                        Map.of("status", "active")),
                user.id(),
                "staff");
        return user.id();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Invitation inviteFirstAdmin(String fullName, String email, String phone, UUID platformUserId) {
        UUID id = createInvitedUser(fullName, email, phone);
        insertAssignment(id, new RoleAssignmentRequest(TENANT_ADMIN, null), platformUserId);
        UserResponse user = find(id).orElseThrow();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("full_name", user.fullName());
        after.put("email", user.email());
        after.put("phone_e164", user.phoneE164());
        after.put("roles", rolesForAudit(user.roles()));
        audit.record(
                AuditLog.Entry.created("core.user.invited", "core.user", id, null, after), platformUserId, "platform");
        InvitationLink link = issueInvitation(id, platformUserId, user.email(), user.phoneE164());
        return new Invitation(id, link.url(), link.expiresAt());
    }

    // ---- Reads ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    UserPage list(List<String> statuses, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        String after = Cursor.decode(cursor).orElse(null);
        StringBuilder sql = new StringBuilder(
                "SELECT id, full_name, email, phone_e164, status, mfa_enabled, last_login_at, created_at, version"
                        + " FROM users WHERE kind = 'staff'");
        Map<String, Object> params = new LinkedHashMap<>();
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND status IN (:statuses)");
            params.put("statuses", statuses);
        }
        if (after != null) {
            sql.append(" AND (lower(full_name) || chr(1) || id::text) > :after");
            params.put("after", after);
        }
        sql.append(" ORDER BY lower(full_name) || chr(1) || id::text LIMIT :limit");
        params.put("limit", size + 1);
        List<UserResponse> rows =
                jdbc.sql(sql.toString()).params(params).query(this::map).list();
        boolean more = rows.size() > size;
        List<UserResponse> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(items.getLast().fullName().toLowerCase() + "\u0001"
                        + items.getLast().id())
                : null;
        return new UserPage(List.copyOf(items), next);
    }

    @Transactional(readOnly = true)
    UserResponse get(UUID userId) {
        return find(userId).orElseThrow(ApiException::notFound);
    }

    // ---- Changes --------------------------------------------------------------------------

    @Transactional
    UserResponse update(UUID userId, String ifMatch, UpdateUserRequest request) {
        int expected = Versions.fromIfMatch(ifMatch);
        UserResponse before = lockUser(userId);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String fullName = request.fullName() == null
                ? before.fullName()
                : request.fullName().trim();
        String email = request.email() == null ? before.email() : normaliseEmail(request.email());
        String phone = request.phone() == null ? before.phoneE164() : normalisePhone(request.phone());
        checkUnique(email, phone, userId);
        jdbc.sql("""
                        UPDATE users SET full_name = ?, email = ?, phone_e164 = ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(fullName, email, phone, userId).update();
        audit.record(new AuditLog.Entry(
                "core.user.updated",
                "core.user",
                userId,
                null,
                contact(before.fullName(), before.email(), before.phoneE164()),
                contact(fullName, email, phone)));
        return find(userId).orElseThrow();
    }

    /** Replaces the user's role assignments (FR-IAM-01, FR-IAM-02). */
    @Transactional
    UserResponse setRoles(UUID userId, List<RoleAssignmentRequest> requested) {
        Principal admin = CurrentPrincipal.require();
        UserResponse before = lockUser(userId);
        List<RoleAssignmentRequest> roles = checkRoles(admin, requested);
        boolean keepsAdmin = roles.stream().anyMatch(r -> r.roleKey().equals(TENANT_ADMIN));
        if (!keepsAdmin && isTenantAdmin(userId)) {
            requireAnotherActiveAdmin(userId);
        }
        Set<String> wanted = new LinkedHashSet<>();
        roles.forEach(r -> wanted.add(r.roleKey() + "|" + r.branchId()));
        for (RoleAssignment current : before.roles()) {
            if (!wanted.remove(current.roleKey() + "|" + current.branchId())) {
                jdbc.sql("""
                                UPDATE user_role_assignments SET revoked_at = now(), revoked_by = ?, updated_at = now(),
                                       version = version + 1
                                 WHERE user_id = ? AND role_key = ? AND branch_id IS NOT DISTINCT FROM ? AND revoked_at IS NULL
                                """)
                        .params(admin.userId(), userId, current.roleKey(), current.branchId())
                        .update();
            }
        }
        for (RoleAssignmentRequest role : roles) {
            if (wanted.contains(role.roleKey() + "|" + role.branchId())) {
                insertAssignment(userId, role, admin.userId());
            }
        }
        UserResponse after = find(userId).orElseThrow();
        audit.record(new AuditLog.Entry(
                "core.user.roles_changed",
                "core.user",
                userId,
                null,
                Map.of("roles", rolesForAudit(before.roles())),
                Map.of("roles", rolesForAudit(after.roles()))));
        return after;
    }

    /** FR-IAM-08: deactivation ends every session at once; the next request is refused. */
    @Transactional
    UserResponse deactivate(UUID userId) {
        Principal admin = CurrentPrincipal.require();
        if (admin.userId().equals(userId)) {
            throw ApiException.rule("cannot_deactivate_self", "Ask another tenant admin to deactivate your account.");
        }
        UserResponse before = lockUser(userId);
        if ("deactivated".equals(before.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The user is already deactivated.");
        }
        if (isTenantAdmin(userId)) {
            requireAnotherActiveAdmin(userId);
        }
        Instant now = clock.now();
        jdbc.sql("UPDATE users SET status = 'deactivated', updated_at = ?, version = version + 1 WHERE id = ?")
                .params(Timestamp.from(now), userId)
                .update();
        jdbc.sql(
                        "UPDATE user_invitations SET revoked_at = ? WHERE user_id = ? AND accepted_at IS NULL AND revoked_at IS NULL")
                .params(Timestamp.from(now), userId)
                .update();
        int revoked = accounts.sessions().revokeAllOf(userId, "user_deactivated", now);
        audit.record(new AuditLog.Entry(
                "core.user.deactivated",
                "core.user",
                userId,
                null,
                Map.of("status", before.status()),
                Map.of("status", "deactivated", "sessions_revoked", revoked)));
        return find(userId).orElseThrow();
    }

    /**
     * FR-IAM-12: a tenant admin clears another staff user's second factor after a lost device.
     * Not a maker-checker action (chapter 8 section 8.4, ADR-014): with a single admin locked out
     * there would be no second person able to sign in. Audited, and every session of the user ends.
     */
    @Transactional
    UserResponse resetMfa(UUID userId) {
        Principal admin = CurrentPrincipal.require();
        if (admin.userId().equals(userId)) {
            throw ApiException.rule(
                    "cannot_reset_own_mfa", "Use a recovery code, or ask another tenant admin to reset your MFA.");
        }
        UserResponse before = lockUser(userId);
        clearMfa(before);
        audit.record(new AuditLog.Entry(
                "core.user.mfa_reset",
                "core.user",
                userId,
                null,
                Map.of("mfa_enabled", before.mfaEnabled()),
                Map.of("mfa_enabled", false)));
        return find(userId).orElseThrow();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void resetAdminMfa(UUID userId, UUID platformUserId) {
        UserResponse before = lockUser(userId);
        if (!isTenantAdmin(userId)) {
            throw ApiException.rule("not_a_tenant_admin", "The platform resets the MFA of tenant admins only.");
        }
        clearMfa(before);
        audit.record(
                new AuditLog.Entry(
                        "core.user.mfa_reset",
                        "core.user",
                        userId,
                        null,
                        Map.of("mfa_enabled", before.mfaEnabled()),
                        Map.of("mfa_enabled", false, "by", "platform")),
                platformUserId,
                "platform");
    }

    // ---- Helpers --------------------------------------------------------------------------

    private void clearMfa(UserResponse user) {
        accounts.resetMfa(user.id());
        accounts.sessions().revokeAllOf(user.id(), "mfa_reset", clock.now());
    }

    private UUID createInvitedUser(String fullName, String rawEmail, String rawPhone) {
        String email = rawEmail == null || rawEmail.isBlank() ? null : normaliseEmail(rawEmail);
        String phone = rawPhone == null || rawPhone.isBlank() ? null : normalisePhone(rawPhone);
        if (email == null && phone == null) {
            throw ApiException.validation(List.of(new FieldProblem("email", "required", "Give an email or a phone.")));
        }
        long staff = jdbc.sql("SELECT count(*) FROM users WHERE kind = 'staff' AND status IN ('invited', 'active')")
                .query(Long.class)
                .single();
        planLimits.checkRoomFor(PlanLimits.MAX_STAFF_USERS, staff);
        checkUnique(email, phone, null);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO users (id, tenant_id, kind, full_name, email, phone_e164, status)
                        VALUES (?, current_setting('app.tenant_id')::uuid, 'staff', ?, ?, ?, 'invited')
                        """).params(id, fullName.trim(), email, phone).update();
        return id;
    }

    /** Revokes any open invitation of the user and issues a new one (72 hours). */
    private InvitationLink issueInvitation(UUID userId, UUID invitedBy, String email, String phone) {
        Instant now = clock.now();
        Instant expires = now.plus(INVITATION_TTL);
        jdbc.sql(
                        "UPDATE user_invitations SET revoked_at = ? WHERE user_id = ? AND accepted_at IS NULL AND revoked_at IS NULL")
                .params(Timestamp.from(now), userId)
                .update();
        String token = Secrets.token();
        jdbc.sql("""
                        INSERT INTO user_invitations (id, tenant_id, user_id, token_hash, expires_at, invited_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), userId, Secrets.sha256(token), Timestamp.from(expires), invitedBy)
                .update();
        CurrentTenant.Profile profile = tenant.profile();
        String url = hosts.tenantOrigin(profile.slug()) + "/accept-invitation#token=" + token;
        // The token never reaches the audit log: the row records that the link was shown.
        audit.record(
                new AuditLog.Entry(
                        "core.invitation.link_revealed",
                        "core.user",
                        userId,
                        null,
                        Map.of(),
                        Map.of("expires_at", expires.toString())),
                invitedBy,
                CurrentPrincipal.get().map(Principal::kind).orElse("platform"));
        Map<String, String> params = Map.of(
                "tenant_name",
                profile.name(),
                "link",
                url,
                "expires_at",
                DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(expires.atOffset(ZoneOffset.UTC)) + "Z");
        notifier.send(new Notifier.Message(
                email != null ? "email" : "sms", email != null ? email : phone, "core.staff_invitation", params));
        return new InvitationLink(url, expires);
    }

    private List<RoleAssignmentRequest> checkRoles(Principal admin, List<RoleAssignmentRequest> requested) {
        List<FieldProblem> problems = new ArrayList<>();
        Map<String, RoleAssignmentRequest> unique = new LinkedHashMap<>();
        for (RoleAssignmentRequest role : requested) {
            boolean staffRole = jdbc.sql("SELECT count(*) FROM roles WHERE key = ? AND kind = 'staff'")
                            .param(role.roleKey())
                            .query(Long.class)
                            .single()
                    > 0;
            if (!staffRole) {
                problems.add(new FieldProblem("roles", "unknown_role", "No staff role " + role.roleKey() + "."));
                continue;
            }
            if (role.roleKey().equals(TENANT_ADMIN) && role.branchId() != null) {
                problems.add(new FieldProblem("roles", "invalid", "The tenant admin role covers every branch."));
                continue;
            }
            boolean inScope = role.branchId() == null
                    ? admin.scopeOf(MANAGE).map(Principal.BranchScope::all).orElse(false)
                    : admin.may(MANAGE, role.branchId())
                            && branches.findActive(role.branchId()).isPresent();
            if (!inScope) {
                problems.add(new FieldProblem("roles", "unknown_branch", "No such active branch in your scope."));
                continue;
            }
            unique.putIfAbsent(role.roleKey() + "|" + role.branchId(), role);
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return List.copyOf(unique.values());
    }

    private void insertAssignment(UUID userId, RoleAssignmentRequest role, UUID grantedBy) {
        jdbc.sql("""
                        INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), userId, role.roleKey(), role.branchId(), grantedBy)
                .update();
    }

    private boolean isTenantAdmin(UUID userId) {
        return jdbc.sql("""
                                SELECT count(*) FROM user_role_assignments
                                 WHERE user_id = ? AND role_key = 'tenant_admin' AND revoked_at IS NULL
                                """).param(userId).query(Long.class).single() > 0;
    }

    /** A tenant always keeps at least one active tenant admin. */
    private void requireAnotherActiveAdmin(UUID leaving) {
        long others = jdbc.sql("""
                        SELECT count(DISTINCT a.user_id) FROM user_role_assignments a JOIN users u ON u.id = a.user_id
                         WHERE a.role_key = 'tenant_admin' AND a.revoked_at IS NULL AND u.status = 'active' AND u.id <> ?
                        """).param(leaving).query(Long.class).single();
        if (others == 0) {
            throw ApiException.rule("last_tenant_admin", "The tenant must keep at least one active tenant admin.");
        }
    }

    private void checkUnique(String email, String phone, UUID except) {
        if (email != null) {
            boolean taken = jdbc.sql("SELECT count(*) FROM users WHERE kind = 'staff' AND lower(email) = lower(?)"
                                    + " AND id IS DISTINCT FROM ?")
                            .params(email, except)
                            .query(Long.class)
                            .single()
                    > 0;
            if (taken) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "duplicate_email", "Duplicate email", "A staff user has this email.");
            }
        }
        if (phone != null) {
            boolean taken = jdbc.sql(
                                    "SELECT count(*) FROM users WHERE kind = 'staff' AND phone_e164 = ? AND id IS DISTINCT FROM ?")
                            .params(phone, except)
                            .query(Long.class)
                            .single()
                    > 0;
            if (taken) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "duplicate_phone", "Duplicate phone", "A staff user has this phone.");
            }
        }
    }

    private UserResponse lockUser(UUID userId) {
        jdbc.sql("SELECT id FROM users WHERE id = ? AND kind = 'staff' FOR UPDATE")
                .param(userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(ApiException::notFound);
        return find(userId).orElseThrow();
    }

    Optional<UserResponse> find(UUID userId) {
        return jdbc.sql("""
                        SELECT id, full_name, email, phone_e164, status, mfa_enabled, last_login_at, created_at, version
                          FROM users WHERE id = ? AND kind = 'staff'
                        """).param(userId).query(this::map).optional();
    }

    private UserResponse map(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new UserResponse(
                id,
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone_e164"),
                rs.getString("status"),
                rs.getBoolean("mfa_enabled"),
                SessionStore.instant(rs.getTimestamp("last_login_at")),
                grants.assignments(id).stream()
                        .map(a -> new RoleAssignment(a.roleKey(), a.roleName(), a.branchId()))
                        .toList(),
                SessionStore.instant(rs.getTimestamp("created_at")),
                rs.getInt("version"));
    }

    private static List<String> rolesForAudit(List<RoleAssignment> roles) {
        return roles.stream()
                .map(r -> r.roleKey() + "@" + (r.branchId() == null ? "all" : r.branchId()))
                .toList();
    }

    private static Map<String, Object> contact(String fullName, String email, String phone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("full_name", fullName);
        m.put("email", email);
        m.put("phone_e164", phone);
        return m;
    }

    private static String normaliseEmail(String email) {
        return email.trim().toLowerCase();
    }

    private static String normalisePhone(String raw) {
        return PhoneNumbers.normaliseUganda(raw)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "invalid_phone",
                        "Invalid phone number",
                        "Use a Uganda mobile number such as 07XXXXXXXX or +2567XXXXXXXX.",
                        List.of(new FieldProblem("phone", "invalid_phone", "Not a valid phone number."))));
    }

    private static ApiException invitationInvalid() {
        return ApiException.rule("invitation_invalid", "The invitation link is not valid; ask an admin for a new one.");
    }
}
