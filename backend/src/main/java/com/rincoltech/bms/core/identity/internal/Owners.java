package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Principal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The Owner of the business (ADR-062, FR-IAM-18): one staff user per business, marked {@code users.is_owner}, who holds the
 * administrator role like every administrator and alone adds, removes, deactivates, demotes or promotes an administrator.
 * Nobody inside the business removes, deactivates or demotes the Owner; the Owner hands the role on by a transfer.
 *
 * <p>Every method runs in the caller's transaction, bound to the request's tenant: the row policy of {@code users} keeps it to
 * the tenant's own staff. A business with no Owner yet (one with no active administrator when V79 ran) keeps the rules of
 * before this change until a platform operator assigns one; that is logged as a warning each time a rule is skipped.
 *
 * <p>Locking: one advisory lock per business. Everything that makes or moves the Owner takes it exclusively; every owner rule
 * on a staff change takes it shared, first thing in its transaction (before any row lock, so the two orders never cross), so an
 * act an Owner started cannot commit after that person stopped being the Owner.
 */
@Component
class Owners {

    private static final Logger log = LoggerFactory.getLogger(Owners.class);
    private static final String LOCK_KEY = "hashtextextended('bms.owner:' || current_setting('app.tenant_id'), 0)";

    private final JdbcClient jdbc;
    private final AuditLog audit;

    Owners(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The shared owner lock, until the transaction ends: taken by every staff change before it reads or locks a row. */
    void lockShared() {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(" + LOCK_KEY + ")")
                .query((rs, n) -> 1)
                .single();
    }

    /**
     * The exclusive owner lock, until the transaction ends: taken first by everything that may make or move the Owner (a
     * transfer, the first Owner of a business, an invitation acceptance).
     */
    void lockExclusive() {
        jdbc.sql("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")")
                .query((rs, n) -> 1)
                .single();
    }

    /** The Owner of the request's business, or empty while it has none. */
    Optional<UUID> current() {
        return jdbc.sql("SELECT id FROM users WHERE kind = 'staff' AND is_owner")
                .query(UUID.class)
                .optional();
    }

    boolean isOwner(UUID userId) {
        return current().map(userId::equals).orElse(false);
    }

    /**
     * An act only the Owner may do (inviting or promoting an administrator). Refused with 403 {@code owner_only} for anyone
     * else; allowed to every holder of the route's permission while the business has no Owner.
     */
    void requireOwner(Principal actor, String what) {
        lockShared();
        Optional<UUID> owner = current();
        if (owner.isEmpty()) {
            warnOwnerless(what);
            return;
        }
        if (!owner.get().equals(actor.userId())) {
            throw ownerOnly();
        }
    }

    /**
     * A change to another administrator (deactivate, change roles or contact, reset the second factor, a new invitation link).
     * The Owner may; anyone else is refused, with 403 {@code owner_protected} when the target is the Owner and 403
     * {@code owner_only} otherwise.
     */
    void requireMayChangeAdmin(Principal actor, UUID target, String what) {
        lockShared();
        Optional<UUID> owner = current();
        if (owner.isEmpty()) {
            warnOwnerless(what);
            return;
        }
        if (owner.get().equals(actor.userId())) {
            return;
        }
        throw owner.get().equals(target) ? ownerProtected() : ownerOnly();
    }

    /**
     * Makes {@code to} the Owner in the caller's transaction and returns the previous Owner, or null when the business had
     * none. {@code to} must be an active administrator of the business who is not already the Owner (422
     * {@code owner_must_be_active_admin}, {@code already_owner}); the previous Owner stays an administrator. Serialised per
     * business by the exclusive owner lock, so two transfers at once end with exactly one Owner; the unique index
     * {@code users_one_owner} holds it in any case (409 {@code owner_changed}). With {@code actorMustBeOwner} (the Owner's own
     * transfer) the actor must still be the Owner once the lock is held (403 {@code owner_only}; 409 {@code no_owner} for a
     * business with none) and must not have changed their contact or password in the last 24 hours (422
     * {@code owner_recently_changed}). Audited as {@code core.user.ownership_transferred} by the actor, with the reason.
     */
    UUID transfer(UUID to, UUID actorId, String actorKind, String reason, boolean actorMustBeOwner) {
        lockExclusive();
        UUID previous = current().orElse(null);
        if (actorMustBeOwner) {
            requireActorIsOwner(previous, actorId);
            requireNoRecentChange(actorId);
        }
        requireCandidate(to, true);
        if (to.equals(previous)) {
            throw ApiException.rule("already_owner", "This person is already the owner of the business.");
        }
        try {
            if (previous != null) {
                jdbc.sql("UPDATE users SET is_owner = false, updated_at = now(), version = version + 1 WHERE id = ?")
                        .param(previous)
                        .update();
            }
            jdbc.sql("UPDATE users SET is_owner = true, updated_at = now(), version = version + 1 WHERE id = ?")
                    .param(to)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw ownerChanged();
        }
        Map<String, Object> before = new HashMap<>();
        before.put("owner", previous);
        Map<String, Object> after = new HashMap<>();
        after.put("owner", to);
        after.put("by", actorKind.equals("platform") ? "platform" : "owner");
        after.put("reason", reason == null || reason.isBlank() ? null : reason.strip());
        audit.record(
                new AuditLog.Entry("core.user.ownership_transferred", "core.user", to, null, before, after),
                actorId,
                actorKind);
        return previous;
    }

    /**
     * The cooling period of the Owner's own transfer: refused with 422 {@code owner_recently_changed} while the Owner's email or
     * phone ({@code users.contact_changed_at}), two-step sign-in ({@code users.second_factor_changed_at}, set up or reset) or
     * password ({@code user_credentials.password_changed_at}) changed less than 24 hours ago, so someone who took the Owner's
     * session, or knows the password and had the second factor reset, cannot hand the business over at once.
     */
    void requireNoRecentChange(UUID ownerId) {
        // Compared with the database clock. contact_changed_at and second_factor_changed_at are written with now();
        // password_changed_at is written with the application clock (BusinessClock), which on a server is the same host
        // time,
        // so a day of difference is never in doubt.
        Timestamp allowedFrom =
                jdbc.sql("""
                        SELECT greatest(u.contact_changed_at, u.second_factor_changed_at, c.password_changed_at) + interval '24 hours'
                          FROM users u LEFT JOIN user_credentials c ON c.user_id = u.id
                         WHERE u.id = ?
                           AND greatest(u.contact_changed_at, u.second_factor_changed_at, c.password_changed_at)
                               > now() - interval '24 hours'
                        """).param(ownerId).query(Timestamp.class).optional().orElse(null);
        if (allowedFrom != null) {
            throw ApiException.rule(
                    "owner_recently_changed",
                    "Your email, phone, password or two-step sign-in changed in the last 24 hours, so ownership can be handed"
                            + " over from " + allowedFrom.toInstant() + " on.");
        }
    }

    /**
     * Everything {@link #transfer} refuses about the new Owner: not a staff user of this business (404), not active or not an
     * administrator (422 {@code owner_must_be_active_admin}). {@code forUpdate} locks the row, as the transfer does.
     */
    void requireCandidate(UUID userId, boolean forUpdate) {
        String status = jdbc.sql(
                        "SELECT status FROM users WHERE id = ? AND kind = 'staff'" + (forUpdate ? " FOR UPDATE" : ""))
                .param(userId)
                .query(String.class)
                .optional()
                .orElseThrow(ApiException::notFound);
        if (!"active".equals(status) || !isAdmin(userId)) {
            throw ApiException.rule(
                    "owner_must_be_active_admin",
                    "Ownership can only go to an active administrator of the business. Make the person an administrator first.");
        }
    }

    /**
     * After an invitation is accepted: an administrator who joins a business that has no Owner and no other active
     * administrator becomes its Owner (the first administrator of a business, invited before V79). Under the exclusive owner
     * lock, so two administrators accepting at once cannot both become the Owner. Audited as the person who joined.
     */
    void claimIfFirstAdmin(UUID userId) {
        lockExclusive();
        if (current().isPresent() || !isAdmin(userId)) {
            return;
        }
        long admins = jdbc.sql("""
                        SELECT count(DISTINCT a.user_id) FROM user_role_assignments a JOIN users u ON u.id = a.user_id
                         WHERE a.role_key = 'tenant_admin' AND a.revoked_at IS NULL AND u.status = 'active'
                        """).query(Long.class).single();
        if (admins != 1) {
            return;
        }
        markFirstOwner(userId, userId, "staff", "the first administrator joined a business with no owner");
    }

    /**
     * The first administrator of a new business is its Owner from the start (still invited until they accept), under the
     * exclusive owner lock. Nothing happens when the business already has an Owner.
     */
    void markFirstOwner(UUID userId, UUID actorId, String actorKind, String reason) {
        lockExclusive();
        if (current().isPresent()) {
            return;
        }
        try {
            // No version bump: this runs in the transaction that creates the user or accepts the invitation, which owns
            // it.
            jdbc.sql("UPDATE users SET is_owner = true WHERE id = ?")
                    .param(userId)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw ownerChanged();
        }
        Map<String, Object> before = new HashMap<>();
        before.put("owner", null);
        audit.record(
                new AuditLog.Entry(
                        "core.user.owner_assigned",
                        "core.user",
                        userId,
                        null,
                        before,
                        Map.of("owner", userId, "reason", reason)),
                actorId,
                actorKind);
    }

    private boolean isAdmin(UUID userId) {
        return jdbc.sql("""
                                SELECT count(*) FROM user_role_assignments
                                 WHERE user_id = ? AND role_key = 'tenant_admin' AND revoked_at IS NULL
                                """).param(userId).query(Long.class).single() > 0;
    }

    /** The Owner's own transfer: the business has an Owner and it is the actor. */
    static void requireActorIsOwner(UUID owner, UUID actorId) {
        if (owner == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "no_owner",
                    "No owner yet",
                    "This business has no owner yet. The platform operator assigns one.");
        }
        if (!owner.equals(actorId)) {
            throw ownerOnly();
        }
    }

    /**
     * A setting only the Owner of the business changes, with no fallback (the go-live date, ADR-071): 403 {@code owner_only}
     * for anyone else, and for everyone while the business has no Owner, when only the platform operator changes it.
     */
    void requireOwnerStrictly(Principal actor, String what) {
        lockShared();
        if (!current().map(actor.userId()::equals).orElse(false)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "owner_only",
                    "Only the owner",
                    "Only the owner of the business can " + what
                            + "; while the business has no owner, ask the platform operator.");
        }
    }

    private static void warnOwnerless(String what) {
        log.warn(
                "The business has no owner, so the owner-only rule is not applied to: {}. A platform operator assigns one.",
                what);
    }

    static ApiException ownerOnly() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "owner_only",
                "Only the owner",
                "Only the owner of the business can add, remove, deactivate or change an administrator.");
    }

    static ApiException ownerProtected() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "owner_protected",
                "The owner is protected",
                "Nobody can remove, deactivate or change the owner of the business. The owner can hand ownership to another administrator.");
    }

    private static ApiException ownerChanged() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "owner_changed",
                "The owner changed",
                "The owner of the business changed at the same moment. Reload the page and try again.");
    }
}
