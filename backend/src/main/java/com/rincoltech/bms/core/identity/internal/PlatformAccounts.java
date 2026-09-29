package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
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

/**
 * Platform operators ({@code platform_users}): email, password and a mandatory TOTP second factor
 * (chapter 8 section 8.2). Their security events go to {@code platform_audit_log}.
 */
@Component
class PlatformAccounts implements Accounts {

    private static final String SELECT = """
            SELECT id, email AS login, CASE WHEN is_active THEN 'active' ELSE 'deactivated' END AS status,
                   password_hash, mfa_enabled, totp_secret_enc, totp_pending_secret_enc, totp_last_step,
                   failed_login_count, locked_until
              FROM platform_users
            """;

    private final JdbcClient jdbc;
    private final PlatformAuditLog audit;
    private final SessionStore sessions;

    PlatformAccounts(JdbcClient jdbc, PlatformAuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.sessions = SessionStore.platform(jdbc);
    }

    @Override
    public String kind() {
        return "platform";
    }

    @Override
    public UUID tenantId() {
        return null;
    }

    @Override
    public String totpIssuer() {
        return "BMS Platform";
    }

    @Override
    public Duration idleTtl() {
        return Duration.ofHours(2);
    }

    @Override
    public Duration absoluteTtl() {
        return Duration.ofHours(12);
    }

    @Override
    public Optional<Account> byLogin(String login) {
        if (login == null || !login.contains("@")) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " WHERE lower(email) = lower(?) AND password_hash IS NOT NULL")
                .param(login.trim())
                .query(PlatformAccounts::map)
                .optional();
    }

    @Override
    public Optional<Account> byIdForUpdate(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ? FOR UPDATE")
                .param(id)
                .query(PlatformAccounts::map)
                .optional();
    }

    @Override
    public Failures recordFailure(UUID id, Instant now, int maxFailures, Instant lockUntil) {
        return jdbc.sql(Accounts.failureSql("platform_users"))
                .params(Timestamp.from(now), Timestamp.from(now), maxFailures, Timestamp.from(lockUntil), id)
                .query(Accounts::failures)
                .single();
    }

    @Override
    public void recordSuccess(UUID id, Instant at) {
        jdbc.sql(
                        "UPDATE platform_users SET failed_login_count = 0, locked_until = NULL, last_login_at = ? WHERE id = ?")
                .params(Timestamp.from(at), id)
                .update();
    }

    @Override
    public void setPendingTotp(UUID id, byte[] sealedSecret) {
        jdbc.sql("UPDATE platform_users SET totp_pending_secret_enc = ?, updated_at = now() WHERE id = ?")
                .params(sealedSecret, id)
                .update();
    }

    @Override
    public void enableTotp(UUID id, byte[] sealedSecret, long usedStep) {
        jdbc.sql("""
                        UPDATE platform_users SET totp_secret_enc = ?, totp_pending_secret_enc = NULL, totp_last_step = ?,
                               mfa_enabled = true, updated_at = now() WHERE id = ?
                        """).params(sealedSecret, usedStep, id).update();
    }

    @Override
    public boolean advanceTotpStep(UUID id, long usedStep) {
        return jdbc.sql("""
                                UPDATE platform_users SET totp_last_step = ?
                                 WHERE id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)
                                """).params(usedStep, id, usedStep).update() == 1;
    }

    @Override
    public void replaceRecoveryCodes(UUID id, List<String> codeHashes) {
        jdbc.sql("DELETE FROM platform_user_recovery_codes WHERE platform_user_id = ?")
                .param(id)
                .update();
        for (String hash : codeHashes) {
            jdbc.sql("INSERT INTO platform_user_recovery_codes (id, platform_user_id, code_hash) VALUES (?, ?, ?)")
                    .params(UUID.randomUUID(), id, hash)
                    .update();
        }
    }

    @Override
    public boolean useRecoveryCode(UUID id, String codeHash, Instant at) {
        return jdbc.sql("""
                                UPDATE platform_user_recovery_codes SET used_at = ?
                                 WHERE id = (SELECT id FROM platform_user_recovery_codes
                                              WHERE platform_user_id = ? AND code_hash = ? AND used_at IS NULL LIMIT 1)
                                   AND used_at IS NULL
                                """).params(Timestamp.from(at), id, codeHash).update() == 1;
    }

    @Override
    public int unusedRecoveryCodes(UUID id) {
        return jdbc.sql(
                        "SELECT count(*) FROM platform_user_recovery_codes WHERE platform_user_id = ? AND used_at IS NULL")
                .param(id)
                .query(Integer.class)
                .single();
    }

    @Override
    public boolean mfaRequired(UUID id) {
        return true;
    }

    @Override
    public void audit(String action, UUID userId, Map<String, Object> data) {
        audit.record(action, userId, null, data);
    }

    @Override
    public SessionStore sessions() {
        return sessions;
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
