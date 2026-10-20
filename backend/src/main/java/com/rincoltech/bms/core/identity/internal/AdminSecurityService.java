package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.AdminSecurity;
import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.core.identity.PasswordStepUp;
import com.rincoltech.bms.core.identity.SealedSecrets;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@link AdminSecurity} and {@link SealedSecrets} over the staff accounts, the role tables and the data key. */
@Component
class AdminSecurityService implements AdminSecurity, PasswordStepUp, SealedSecrets {

    private final AuthFlow flow;
    private final StaffAccounts accounts;
    private final JdbcClient jdbc;
    private final DataKeys keys;

    AdminSecurityService(AuthFlow flow, StaffAccounts accounts, JdbcClient jdbc, DataKeys keys) {
        this.flow = flow;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.keys = keys;
    }

    @Override
    public void requireTotp(String code) {
        flow.confirmTotp(accounts, CurrentPrincipal.require().userId(), code);
    }

    @Override
    public void requirePassword(String password) {
        var caller = CurrentPrincipal.require();
        flow.confirmPassword(accounts, caller.userId(), caller.sessionId(), password);
    }

    @Override
    public List<Holder> holders(String permission) {
        return jdbc.sql("""
                        SELECT DISTINCT u.id, u.email
                          FROM users u
                          JOIN user_role_assignments a ON a.user_id = u.id AND a.tenant_id = u.tenant_id AND a.revoked_at IS NULL
                          JOIN role_permissions rp ON rp.role_key = a.role_key
                         WHERE rp.permission_key = ? AND u.kind = 'staff' AND u.status = 'active'
                           AND u.email IS NOT NULL
                        """)
                .param(permission)
                .query((rs, n) -> new Holder(rs.getObject("id", UUID.class), rs.getString("email")))
                .list();
    }

    @Override
    public String actorName() {
        UUID self = CurrentPrincipal.require().userId();
        return jdbc.sql("SELECT full_name FROM users WHERE id = ?")
                .param(self)
                .query(String.class)
                .optional()
                .orElse("");
    }

    // ---- re-sealing --------------------------------------------------------------------------------------------

    @Override
    public int resealBoundTenant() {
        return resealBoundTenantOf(null);
    }

    /** As {@link #resealBoundTenant}, restricted to one tenant when given (a connection that bypasses row-level security). */
    int resealBoundTenantOf(UUID onlyTenant) {
        int changed = 0;
        var rows = jdbc.sql("SELECT user_id, totp_secret_enc, totp_pending_secret_enc FROM user_credentials"
                        + " WHERE (totp_secret_enc IS NOT NULL OR totp_pending_secret_enc IS NOT NULL)"
                        + " AND (CAST(? AS uuid) IS NULL OR tenant_id = CAST(? AS uuid)) FOR UPDATE")
                .params(onlyTenant, onlyTenant)
                .query((rs, n) -> new Object[] {
                    rs.getObject("user_id", UUID.class),
                    rs.getBytes("totp_secret_enc"),
                    rs.getBytes("totp_pending_secret_enc")
                })
                .list();
        for (Object[] r : rows) {
            byte[] secret = (byte[]) r[1];
            byte[] pending = (byte[]) r[2];
            boolean a = secret != null && keys.needsRewrap(secret);
            boolean b = pending != null && keys.needsRewrap(pending);
            if (a || b) {
                jdbc.sql(
                                "UPDATE user_credentials SET totp_secret_enc = ?, totp_pending_secret_enc = ? WHERE user_id = ?")
                        .params(
                                a ? keys.seal(keys.open(secret)) : secret,
                                b ? keys.seal(keys.open(pending)) : pending,
                                r[0])
                        .update();
                changed += (a ? 1 : 0) + (b ? 1 : 0);
            }
        }
        return changed;
    }

    @Override
    public int resealPlatformUsers() {
        int changed = 0;
        var rows = jdbc.sql("SELECT id, totp_secret_enc, totp_pending_secret_enc FROM platform_users"
                        + " WHERE totp_secret_enc IS NOT NULL OR totp_pending_secret_enc IS NOT NULL FOR UPDATE")
                .query((rs, n) -> new Object[] {
                    rs.getObject("id", UUID.class),
                    rs.getBytes("totp_secret_enc"),
                    rs.getBytes("totp_pending_secret_enc")
                })
                .list();
        for (Object[] r : rows) {
            byte[] secret = (byte[]) r[1];
            byte[] pending = (byte[]) r[2];
            boolean a = secret != null && keys.needsRewrap(secret);
            boolean b = pending != null && keys.needsRewrap(pending);
            if (a || b) {
                jdbc.sql("UPDATE platform_users SET totp_secret_enc = ?, totp_pending_secret_enc = ? WHERE id = ?")
                        .params(
                                a ? keys.seal(keys.open(secret)) : secret,
                                b ? keys.seal(keys.open(pending)) : pending,
                                r[0])
                        .update();
                changed += (a ? 1 : 0) + (b ? 1 : 0);
            }
        }
        return changed;
    }

    @Override
    public long remainingBoundTenant() {
        return remaining("SELECT totp_secret_enc, totp_pending_secret_enc FROM user_credentials");
    }

    @Override
    public long remainingPlatformUsers() {
        return remaining("SELECT totp_secret_enc, totp_pending_secret_enc FROM platform_users");
    }

    private long remaining(String sql) {
        long n = 0;
        for (byte[][] r : jdbc.sql(sql)
                .query((rs, i) -> new byte[][] {rs.getBytes(1), rs.getBytes(2)})
                .list()) {
            for (byte[] box : r) {
                if (box != null && keys.needsRewrap(box)) {
                    n++;
                }
            }
        }
        return n;
    }
}
