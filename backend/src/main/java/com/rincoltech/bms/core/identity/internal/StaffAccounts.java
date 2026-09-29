package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.PhoneNumbers;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Tenant staff accounts: {@code users} and {@code user_credentials}, under the tenant policy. */
@Component
class StaffAccounts implements Accounts {

    private static final String SELECT = """
            SELECT u.id, coalesce(u.email, u.phone_e164) AS login, u.status, c.password_hash, u.mfa_enabled,
                   c.totp_secret_enc, c.totp_pending_secret_enc, c.totp_last_step, u.failed_login_count, u.locked_until
              FROM users u LEFT JOIN user_credentials c ON c.user_id = u.id
             WHERE u.kind = 'staff'
            """;

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final TenantSettings settings;
    private final CurrentTenant tenant;
    private final SessionStore sessions;

    StaffAccounts(JdbcClient jdbc, AuditLog audit, TenantSettings settings, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.settings = settings;
        this.tenant = tenant;
        this.sessions = SessionStore.staff(jdbc);
    }

    @Override
    public String kind() {
        return "staff";
    }

    @Override
    public UUID tenantId() {
        return TenantContext.require();
    }

    @Override
    public String totpIssuer() {
        return "BMS " + tenant.profile().slug();
    }

    @Override
    public Duration idleTtl() {
        return Duration.ofHours(12);
    }

    @Override
    public Duration absoluteTtl() {
        return Duration.ofDays(7);
    }

    @Override
    public Optional<Account> byLogin(String login) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        List<Account> found;
        if (login.contains("@")) {
            found = jdbc.sql(SELECT + " AND lower(u.email) = lower(?)")
                    .param(login.trim())
                    .query(StaffAccounts::map)
                    .list();
        } else {
            Optional<String> phone = PhoneNumbers.normaliseUganda(login);
            if (phone.isEmpty()) {
                return Optional.empty();
            }
            found = jdbc.sql(SELECT + " AND u.phone_e164 = ?")
                    .param(phone.get())
                    .query(StaffAccounts::map)
                    .list();
        }
        // A phone number shared by two staff users signs neither in by phone.
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /**
     * Locks both rows: a waiting caller re-reads the locked rows only, so locking {@code users}
     * alone would leave it a stale {@code totp_last_step} (a replayed code signing in twice).
     */
    @Override
    public Optional<Account> byIdForUpdate(UUID id) {
        jdbc.sql("""
                        INSERT INTO user_credentials (user_id, tenant_id)
                        SELECT id, current_setting('app.tenant_id')::uuid FROM users WHERE id = ? AND kind = 'staff'
                        ON CONFLICT (user_id) DO NOTHING
                        """).param(id).update();
        return jdbc.sql(SELECT.replace("LEFT JOIN", "JOIN") + " AND u.id = ? FOR UPDATE OF u, c")
                .param(id)
                .query(StaffAccounts::map)
                .optional();
    }

    @Override
    public Failures recordFailure(UUID id, Instant now, int maxFailures, Instant lockUntil) {
        return jdbc.sql(Accounts.failureSql("users"))
                .params(Timestamp.from(now), Timestamp.from(now), maxFailures, Timestamp.from(lockUntil), id)
                .query(Accounts::failures)
                .single();
    }

    @Override
    public void recordSuccess(UUID id, Instant at) {
        jdbc.sql("UPDATE users SET failed_login_count = 0, locked_until = NULL, last_login_at = ? WHERE id = ?")
                .params(Timestamp.from(at), id)
                .update();
    }

    @Override
    public void setPendingTotp(UUID id, byte[] sealedSecret) {
        ensureCredentials(id);
        jdbc.sql("UPDATE user_credentials SET totp_pending_secret_enc = ?, updated_at = now() WHERE user_id = ?")
                .params(sealedSecret, id)
                .update();
    }

    @Override
    public void enableTotp(UUID id, byte[] sealedSecret, long usedStep) {
        jdbc.sql("""
                        UPDATE user_credentials SET totp_secret_enc = ?, totp_pending_secret_enc = NULL, totp_last_step = ?,
                               updated_at = now() WHERE user_id = ?
                        """).params(sealedSecret, usedStep, id).update();
        jdbc.sql("UPDATE users SET mfa_enabled = true, updated_at = now(), version = version + 1 WHERE id = ?")
                .param(id)
                .update();
    }

    @Override
    public boolean advanceTotpStep(UUID id, long usedStep) {
        return jdbc.sql("""
                                UPDATE user_credentials SET totp_last_step = ?
                                 WHERE user_id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)
                                """).params(usedStep, id, usedStep).update() == 1;
    }

    @Override
    public void replaceRecoveryCodes(UUID id, List<String> codeHashes) {
        jdbc.sql("DELETE FROM user_recovery_codes WHERE user_id = ?").param(id).update();
        for (String hash : codeHashes) {
            jdbc.sql("""
                            INSERT INTO user_recovery_codes (id, tenant_id, user_id, code_hash)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                            """).params(UUID.randomUUID(), id, hash).update();
        }
    }

    @Override
    public boolean useRecoveryCode(UUID id, String codeHash, Instant at) {
        return jdbc.sql("""
                                UPDATE user_recovery_codes SET used_at = ?
                                 WHERE id = (SELECT id FROM user_recovery_codes
                                              WHERE user_id = ? AND code_hash = ? AND used_at IS NULL LIMIT 1)
                                   AND used_at IS NULL
                                """).params(Timestamp.from(at), id, codeHash).update() == 1;
    }

    @Override
    public int unusedRecoveryCodes(UUID id) {
        return jdbc.sql("SELECT count(*) FROM user_recovery_codes WHERE user_id = ? AND used_at IS NULL")
                .param(id)
                .query(Integer.class)
                .single();
    }

    @Override
    public boolean mfaRequired(UUID id) {
        boolean byRole = jdbc.sql("""
                                SELECT count(*) FROM user_role_assignments a JOIN roles r ON r.key = a.role_key
                                 WHERE a.user_id = ? AND a.revoked_at IS NULL AND r.mfa_required
                                """).param(id).query(Long.class).single() > 0;
        return byRole || settings.requireMfaAllStaff();
    }

    @Override
    public void audit(String action, UUID userId, Map<String, Object> data) {
        audit.record(new AuditLog.Entry(action, "core.user", userId, null, Map.of(), data), userId, "staff");
    }

    @Override
    public SessionStore sessions() {
        return sessions;
    }

    /** Clears the second factor (FR-IAM-12): secret, pending secret, used step and codes. */
    void resetMfa(UUID id) {
        ensureCredentials(id);
        jdbc.sql("""
                        UPDATE user_credentials SET totp_secret_enc = NULL, totp_pending_secret_enc = NULL,
                               totp_last_step = NULL, updated_at = now() WHERE user_id = ?
                        """).param(id).update();
        jdbc.sql("DELETE FROM user_recovery_codes WHERE user_id = ?").param(id).update();
        jdbc.sql("UPDATE users SET mfa_enabled = false, updated_at = now(), version = version + 1 WHERE id = ?")
                .param(id)
                .update();
    }

    void ensureCredentials(UUID id) {
        jdbc.sql("""
                        INSERT INTO user_credentials (user_id, tenant_id) VALUES (?, current_setting('app.tenant_id')::uuid)
                        ON CONFLICT (user_id) DO NOTHING
                        """).param(id).update();
    }

    private static Account map(ResultSet rs, int n) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("login"),
                rs.getString("status"),
                rs.getString("password_hash"),
                rs.getBoolean("mfa_enabled"),
                rs.getBytes("totp_secret_enc"),
                rs.getBytes("totp_pending_secret_enc"),
                rs.getObject("totp_last_step", Long.class),
                rs.getInt("failed_login_count"),
                SessionStore.instant(rs.getTimestamp("locked_until")));
    }
}
