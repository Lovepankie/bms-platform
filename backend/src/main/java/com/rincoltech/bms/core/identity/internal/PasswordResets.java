package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.identity.TenantAdmins.ResetLink;
import com.rincoltech.bms.core.identity.internal.Accounts.Account;
import com.rincoltech.bms.core.identity.internal.UserApi.SignInResetLink;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequestContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A staff user who forgot the password gets back in with a one-time link (FR-IAM-15, ADR-042): asked for on the sign-in page
 * (the link goes to the email on file), issued by a tenant admin for an ordinary user, or issued by the platform operator for
 * a tenant admin (the link is shown once to whoever issued it). The token is 256 random bits, stored as a SHA-256 hash,
 * single use, valid for one hour; a newer link revokes the older ones. Using it sets the password under the same rules and
 * hashing as an invitation, ends every session of the user and lifts the failed-attempt lock (ADR-059). It never touches the
 * second factor or the user's status: only an active user can reset, and a user still invited keeps the invitation path.
 *
 * <p>The request for a link answers the same whether or not an account matched; everything that differs (no account, not
 * active, no email, a role that needs a second factor the user has not set up, too many links this hour) happens inside and is
 * visible only in the audit log. A tenant admin never resets a tenant admin or themselves: that is the platform operator's
 * path, or the sign-in page for one's own account.
 */
@Service
class PasswordResets {

    static final Duration TTL = Duration.ofHours(1);
    /** Links one account can ask for on the sign-in page within an hour; further requests are answered and ignored. */
    static final int MAX_SELF_REQUESTS_PER_HOUR = 3;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH);

    static final String RESET_TEMPLATE = "identity.password_reset";
    static final String CHANGED_TEMPLATE = "identity.password_changed";
    static final String ISSUED_TEMPLATE = "identity.password_reset_issued";

    private final JdbcClient jdbc;
    private final StaffAccounts accounts;
    private final Passwords passwords;
    private final PlatformHost hosts;
    private final CurrentTenant tenant;
    private final Outbox outbox;
    private final AuditLog audit;
    private final BusinessClock clock;

    PasswordResets(
            JdbcClient jdbc,
            StaffAccounts accounts,
            Passwords passwords,
            PlatformHost hosts,
            CurrentTenant tenant,
            Outbox outbox,
            AuditLog audit,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.passwords = passwords;
        this.hosts = hosts;
        this.tenant = tenant;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    private record Target(UUID id, String email, String phone, String status) {}

    /**
     * The user asks for a link on the sign-in page. Returns nothing, on purpose: the caller must not be able to tell what
     * happened. The actor of every row is nobody (the caller is not signed in); the client address is in the row.
     */
    @Transactional
    void request(String login) {
        String typed = login == null ? "" : login.trim();
        Optional<Account> found = accounts.byLogin(typed);
        if (found.isEmpty()) {
            audit.record(
                    new AuditLog.Entry(
                            "core.password_reset.unknown_login",
                            "core.user",
                            null,
                            null,
                            Map.of(),
                            data("login", typed)),
                    null,
                    "staff");
            return;
        }
        Target user = lock(found.get().id());
        if (user == null) {
            return;
        }
        if (!"active".equals(user.status())) {
            refused(user, "not_active");
            return;
        }
        if (user.email() == null) {
            refused(user, "no_email");
            return;
        }
        if (needsSecondFactorFirst(found.get())) {
            // Whoever reads the mailbox would otherwise set the password and enrol their own authenticator (ADR-042
            // decision 7).
            refused(user, "mfa_not_enrolled");
            return;
        }
        Instant now = clock.now();
        long recent = jdbc.sql("""
                        SELECT count(*) FROM user_password_resets WHERE user_id = ? AND origin = 'self' AND created_at > ?
                        """)
                .params(user.id(), Timestamp.from(now.minus(Duration.ofHours(1))))
                .query(Long.class)
                .single();
        if (recent >= MAX_SELF_REQUESTS_PER_HOUR) {
            refused(user, "rate_limited");
            return;
        }
        Issued issued = issue(user.id(), "self", null, now);
        audit.record(
                new AuditLog.Entry(
                        "core.password_reset.requested",
                        "core.user",
                        user.id(),
                        null,
                        Map.of(),
                        data("origin", "self", "expires_at", issued.expiresAt().toString())),
                null,
                "staff");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenant_name", tenant.profile().name());
        params.put("link", issued.url());
        params.put("expires_at", stamp(issued.expiresAt()));
        // The row carries the link until it is sent (then the parameters are cleared) or until it expires (a frequent
        // job
        // clears it then), so the token is in the database in plain text for the hour at most plus the job's interval.
        outbox.enqueue(new Outbox.Message(
                Outbox.EMAIL,
                user.email(),
                RESET_TEMPLATE,
                params,
                RESET_TEMPLATE + ":" + issued.id(),
                "password-reset:" + user.id(),
                issued.expiresAt()));
    }

    /** Whether the signed-in user (the acting admin) has a second factor enrolled, for the step-up the route asks for. */
    @Transactional(readOnly = true)
    boolean actorHasSecondFactor() {
        return jdbc.sql("SELECT mfa_enabled FROM users WHERE id = ? AND kind = 'staff'")
                .param(CurrentPrincipal.require().userId())
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Everything the tenant route refuses, read without a lock and before the caller's authenticator code is spent: an unknown
     * user or one outside the admin's branches (404), the admin's own account, a tenant admin, a user not active (409).
     */
    @Transactional(readOnly = true)
    void precheckForAdmin(UUID userId) {
        adminTarget(CurrentPrincipal.require(), userId, false);
    }

    /**
     * A tenant admin issues a link for an active user who is not a tenant admin and not themselves; it is returned once and
     * never emailed, but the user is told by email that one was issued (without the link) (FR-IAM-15).
     */
    @Transactional
    SignInResetLink issueForAdmin(UUID userId) {
        Principal admin = CurrentPrincipal.require();
        Target user = adminTarget(admin, userId, true);
        Instant now = clock.now();
        Issued issued = issue(userId, "admin", admin.userId(), now);
        audit.record(new AuditLog.Entry(
                "core.password_reset.requested",
                "core.user",
                userId,
                null,
                Map.of(),
                data("origin", "admin", "expires_at", issued.expiresAt().toString())));
        // The token never reaches the audit log: the row records that the link was shown.
        audit.record(new AuditLog.Entry(
                "core.password_reset.link_revealed",
                "core.user",
                userId,
                null,
                Map.of(),
                data("expires_at", issued.expiresAt().toString())));
        notifyIssued(user, issued, now, "An administrator");
        return new SignInResetLink(issued.url(), issued.expiresAt());
    }

    private Target adminTarget(Principal admin, UUID userId, boolean lock) {
        Target user = find(userId, lock);
        if (user == null) {
            throw ApiException.notFound();
        }
        requireInScope(admin, userId);
        if (admin.userId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "cannot_reset_own_sign_in",
                    "Not allowed",
                    "Use Forgot password on the sign-in page for your own account.");
        }
        requireActive(user);
        if (isTenantAdmin(userId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "admin_reset_by_operator",
                    "Not allowed",
                    "An administrator's sign-in is reset by the platform operator. Ask them.");
        }
        return user;
    }

    /** What the operator route refuses, read without a lock and before the operator's authenticator code is spent. */
    @Transactional(readOnly = true)
    void precheckForOperator(UUID userId) {
        operatorTarget(userId, false);
    }

    private Target operatorTarget(UUID userId, boolean lock) {
        Target user = find(userId, lock);
        if (user == null) {
            throw ApiException.notFound();
        }
        if (!isTenantAdmin(userId)) {
            throw ApiException.rule("not_a_tenant_admin", "The platform resets the sign-in of tenant admins only.");
        }
        requireActive(user);
        return user;
    }

    /** The platform operator issues a link for a tenant admin whom nobody in the tenant may reset (ADR-042 decision 9). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    ResetLink issueForOperator(UUID userId, UUID operatorId) {
        Target user = operatorTarget(userId, true);
        Instant now = clock.now();
        Issued issued = issue(userId, "operator", operatorId, now);
        audit.record(
                new AuditLog.Entry(
                        "core.password_reset.requested",
                        "core.user",
                        userId,
                        null,
                        Map.of(),
                        data(
                                "origin",
                                "operator",
                                "expires_at",
                                issued.expiresAt().toString())),
                operatorId,
                "platform");
        audit.record(
                new AuditLog.Entry(
                        "core.password_reset.link_revealed",
                        "core.user",
                        userId,
                        null,
                        Map.of(),
                        data("expires_at", issued.expiresAt().toString())),
                operatorId,
                "platform");
        notifyIssued(user, issued, now, "The platform operator");
        return new ResetLink(issued.url(), issued.expiresAt());
    }

    /** Who a link would set the password of, for the strength check before the hash: the sign-in names it may not equal. */
    record LinkHolder(String email, String phone) {}

    /**
     * Everything {@link #complete} refuses about the link, read without a lock, so the new password is hashed only for a link
     * that works and outside any transaction (ADR-059): {@link #complete} checks it all again under its locks.
     */
    @Transactional(readOnly = true)
    LinkHolder precheck(String token) {
        Instant now = clock.now();
        String hash = Secrets.sha256(token == null ? "" : token);
        Pending link = pending(hash, false).orElseThrow(PasswordResets::linkInvalid);
        Target user = find(link.userId(), false);
        checkLink(link, user, now);
        boolean enrolled = jdbc.sql("SELECT mfa_enabled FROM users WHERE id = ?")
                .param(user.id())
                .query(Boolean.class)
                .single();
        if ("self".equals(link.origin()) && !enrolled && accounts.mfaRequired(user.id())) {
            throw linkInvalid();
        }
        return new LinkHolder(user.email(), user.phone());
    }

    /**
     * The link sets a new password, hashed by the caller outside any transaction ({@code passwordHash}, of {@code password}).
     * Returns the user, whom the caller signs in through {@link AuthFlow#afterReset}. A failure before the password is
     * written (bad link, weak password) changes nothing, so the link still works for a second try.
     *
     * <p>A completed reset lifts the failed-attempt lock and starts the count again (ADR-059, amending ADR-042 decision 7):
     * the link came from the owner's own mailbox, so a stranger's wrong passwords cannot keep the owner out. The second
     * factor is untouched and still asked for at once. What it costs: whoever reads the mailbox can reset to lift a lock
     * between guesses at the code, at most {@link #MAX_SELF_REQUESTS_PER_HOUR} times an hour, so five guesses each time on
     * top of the five every 15 minutes the lock allows anyway (35 an hour instead of 20), and every reset changes the
     * owner's password, ends their sessions and emails them. The lift is audited.
     */
    @Transactional(noRollbackFor = ApiException.class)
    UUID complete(String token, String password, String passwordHash) {
        Instant now = clock.now();
        String hash = Secrets.sha256(token == null ? "" : token);
        // The user's row is locked before the link's, the order request() and issueForAdmin() use, so two of them
        // cannot deadlock.
        Pending seen = pending(hash, false).orElseThrow(PasswordResets::linkInvalid);
        Account account = accounts.byIdForUpdate(seen.userId()).orElseThrow(PasswordResets::linkInvalid);
        Pending link = pending(hash, true).orElseThrow(PasswordResets::linkInvalid);
        Target user = lock(link.userId());
        checkLink(link, user, now);
        if ("self".equals(link.origin()) && needsSecondFactorFirst(account)) {
            // Asked for before the role gained its second factor requirement: the same refusal as at the request.
            throw linkInvalid();
        }
        passwords.checkStrength(password, user.email(), user.phone());
        jdbc.sql(
                        "UPDATE user_credentials SET password_hash = ?, password_changed_at = ?, updated_at = ? WHERE user_id = ?")
                .params(passwordHash, Timestamp.from(now), Timestamp.from(now), account.id())
                .update();
        if (account.lockedAt(now) || account.failedLoginCount() > 0) {
            accounts.clearLock(account.id());
            if (account.lockedAt(now)) {
                audit.record(
                        new AuditLog.Entry(
                                "core.auth.unlocked",
                                "core.user",
                                user.id(),
                                null,
                                Map.of("locked_until", account.lockedUntil().toString()),
                                data("by", "password_reset")),
                        null,
                        "staff");
            }
        }
        jdbc.sql("UPDATE user_password_resets SET used_at = ? WHERE id = ?")
                .params(Timestamp.from(now), link.id())
                .update();
        revokeOpen(user.id(), now);
        int revoked = accounts.sessions().revokeAllOf(user.id(), "password_reset", now);
        audit.record(
                new AuditLog.Entry(
                        "core.password_reset.used",
                        "core.user",
                        user.id(),
                        null,
                        Map.of(),
                        data("origin", link.origin(), "sessions_revoked", revoked)),
                null,
                "staff");
        if (user.email() != null) {
            // Tells the owner of the mailbox the password changed; carries no link, so a stale copy of it is harmless.
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    user.email(),
                    CHANGED_TEMPLATE,
                    Map.of("tenant_name", tenant.profile().name(), "when", stamp(now)),
                    CHANGED_TEMPLATE + ":" + link.id(),
                    "password-reset:" + user.id(),
                    now.plus(Duration.ofDays(1))));
        }
        return user.id();
    }

    private record Pending(UUID id, UUID userId, String origin, Instant expiresAt, Instant usedAt, Instant revokedAt) {}

    private Optional<Pending> pending(String tokenHash, boolean forUpdate) {
        return jdbc.sql("""
                        SELECT id, user_id, origin, expires_at, used_at, revoked_at FROM user_password_resets
                         WHERE token_hash = ?""" + (forUpdate ? " FOR UPDATE" : ""))
                .param(tokenHash)
                .query((rs, n) -> new Pending(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("origin"),
                        SessionStore.instant(rs.getTimestamp("expires_at")),
                        SessionStore.instant(rs.getTimestamp("used_at")),
                        SessionStore.instant(rs.getTimestamp("revoked_at"))))
                .optional();
    }

    /** Revokes the open links of a user: before a new one is written, and when the user is deactivated. */
    void revokeOpen(UUID userId, Instant at) {
        jdbc.sql("""
                        UPDATE user_password_resets SET revoked_at = ?
                         WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL
                        """).params(Timestamp.from(at), userId).update();
    }

    private record Issued(UUID id, String url, Instant expiresAt) {}

    /** Revokes every open link of the user and writes a new one (one hour). The user's row is locked by the caller. */
    private Issued issue(UUID userId, String origin, UUID requestedBy, Instant now) {
        Instant expires = now.plus(TTL);
        revokeOpen(userId, now);
        String token = Secrets.token();
        UUID id = UUID.randomUUID();
        // created_at from the kernel clock, never the database's: the per-account count of the last hour compares it
        // with it.
        jdbc.sql("""
                        INSERT INTO user_password_resets (id, tenant_id, created_at, user_id, token_hash, expires_at, origin, requested_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        Timestamp.from(now),
                        userId,
                        Secrets.sha256(token),
                        Timestamp.from(expires),
                        origin,
                        requestedBy)
                .update();
        String url = hosts.tenantOrigin(tenant.profile().slug()) + "/reset-password#token=" + token;
        return new Issued(id, url, expires);
    }

    /** The user is told that a reset was issued, without the link: the admin or operator hands it over. */
    private void notifyIssued(Target user, Issued issued, Instant now, String who) {
        if (user.email() == null) {
            return;
        }
        outbox.enqueue(new Outbox.Message(
                Outbox.EMAIL,
                user.email(),
                ISSUED_TEMPLATE,
                Map.of("tenant_name", tenant.profile().name(), "when", stamp(now), "who", who),
                ISSUED_TEMPLATE + ":" + issued.id(),
                "password-reset:" + user.id(),
                now.plus(Duration.ofDays(1))));
    }

    /** The refusals of a link itself: unknown, used, replaced, expired, or for a user who is no longer active. */
    private static void checkLink(Pending link, Target user, Instant now) {
        if (link.usedAt() != null || link.revokedAt() != null) {
            throw linkInvalid();
        }
        if (!now.isBefore(link.expiresAt())) {
            throw ApiException.rule("reset_link_expired", "The reset link has expired; ask for a new one.");
        }
        if (user == null || !"active".equals(user.status())) {
            throw linkInvalid();
        }
    }

    /**
     * Whether the account must have a second factor (its role or the tenant's setting requires one) and has none yet. A link
     * by email would let the reader of the mailbox choose the password and then enrol their own authenticator.
     */
    private boolean needsSecondFactorFirst(Account account) {
        return !account.mfaEnabled() && accounts.mfaRequired(account.id());
    }

    private void requireActive(Target user) {
        if (!"active".equals(user.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "invited".equals(user.status())
                            ? "This user has not accepted the invitation yet; send the invitation link again."
                            : "A deactivated user cannot reset the password.");
        }
    }

    private boolean isTenantAdmin(UUID userId) {
        return jdbc.sql("""
                                SELECT count(*) FROM user_role_assignments
                                 WHERE user_id = ? AND role_key = 'tenant_admin' AND revoked_at IS NULL
                                """).param(userId).query(Long.class).single() > 0;
    }

    /**
     * A tenant admin whose permission covers some branches only may reset a user whose every role is in those branches. A user
     * with a role over every branch, or with no role at all, needs an admin who covers every branch.
     */
    void requireInScope(Principal admin, UUID userId) {
        boolean all = admin.scopeOf("core.users.manage")
                .map(Principal.BranchScope::all)
                .orElse(false);
        if (all) {
            return;
        }
        var branches = jdbc.sql("""
                        SELECT branch_id FROM user_role_assignments WHERE user_id = ? AND revoked_at IS NULL
                        """)
                .param(userId)
                .query((rs, n) -> rs.getObject("branch_id", UUID.class))
                .list();
        boolean covered =
                !branches.isEmpty() && branches.stream().allMatch(b -> b != null && admin.may("core.users.manage", b));
        if (!covered) {
            throw ApiException.notFound();
        }
    }

    private Target lock(UUID userId) {
        return find(userId, true);
    }

    private Target find(UUID userId, boolean lock) {
        return jdbc.sql("SELECT id, email, phone_e164, status FROM users WHERE id = ? AND kind = 'staff'"
                        + (lock ? " FOR UPDATE" : ""))
                .param(userId)
                .query((rs, n) -> new Target(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("phone_e164"),
                        rs.getString("status")))
                .optional()
                .orElse(null);
    }

    private void refused(Target user, String reason) {
        audit.record(
                new AuditLog.Entry(
                        "core.password_reset.refused", "core.user", user.id(), null, Map.of(), data("reason", reason)),
                null,
                "staff");
    }

    /** Audit data with the client address, which the audit search shows (the column beside it is not in the search). */
    private static Map<String, Object> data(Object... pairs) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            data.put((String) pairs[i], pairs[i + 1]);
        }
        String ip = RequestContext.clientIp();
        data.put("ip", ip == null ? "unknown" : ip);
        return data;
    }

    /** The time as a message shows it. */
    private static String stamp(Instant at) {
        return STAMP.format(at.atOffset(ZoneOffset.UTC));
    }

    private static ApiException linkInvalid() {
        return ApiException.rule("reset_link_invalid", "The reset link is not valid; ask for a new one.");
    }
}
