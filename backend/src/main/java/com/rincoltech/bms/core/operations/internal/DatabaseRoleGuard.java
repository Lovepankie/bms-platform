package com.rincoltech.bms.core.operations.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the application's database role could bypass row-level security: a
 * superuser, a role with {@code BYPASSRLS}, or the owner of any application table (NFR-SEC-03,
 * ADR-003). Construction fails, so the application context fails, so the container never becomes
 * ready.
 */
@Component
class DatabaseRoleGuard {

    static final String CHECK_SQL = """
            SELECT r.rolname, r.rolsuper, r.rolbypassrls,
                   (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relowner = r.oid) AS owned_tables
              FROM pg_roles r
             WHERE r.rolname = current_user
            """;

    DatabaseRoleGuard(JdbcClient jdbc, @Value("${bms.database.role-guard:true}") boolean enabled) {
        if (enabled) {
            check(jdbc);
        }
    }

    static void check(JdbcClient jdbc) {
        jdbc.sql(CHECK_SQL).query(rs -> {
            String role = rs.getString("rolname");
            if (rs.getBoolean("rolsuper") || rs.getBoolean("rolbypassrls") || rs.getLong("owned_tables") > 0) {
                throw new IllegalStateException("Refusing to start: database role '" + role
                        + "' is a superuser, has BYPASSRLS or owns application tables."
                        + " Connect as the application role (bms_app).");
            }
        });
    }
}
