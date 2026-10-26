package com.rincoltech.bms;

import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL 16 container for the whole test JVM, initialised exactly like a server:
 * {@code deploy/postgres/initdb/01-roles.sh} creates bms_owner and bms_app, then the same
 * {@link DatabaseMigrator} the one-shot migrate container uses applies every migration as
 * bms_owner. The application under test connects as bms_app, so row-level security is real.
 * (A test connected as the container's superuser would pass with every policy dropped.)
 */
public final class TestDatabase {

    public static final String OWNER_PASSWORD = "test-owner-password";
    public static final String APP_PASSWORD = "test-app-password";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("bms")
            .withUsername("postgres")
            .withPassword("test-superuser-password")
            .withEnv("BMS_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("BMS_APP_PASSWORD", APP_PASSWORD)
            // Many cached Spring contexts each hold a pool, so the default 100 connections ran out
            // ("remaining connection slots are reserved"). fsync=off is the container default.
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300", "-c", "shared_buffers=128MB")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("../deploy/postgres/initdb/01-roles.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh");

    static {
        POSTGRES.start();
        DatabaseMigrator.migrate(POSTGRES.getJdbcUrl(), "bms_owner", OWNER_PASSWORD);
    }

    private TestDatabase() {}

    public static String url() {
        return POSTGRES.getJdbcUrl();
    }

    public static DataSource ownerDataSource() {
        return new DriverManagerDataSource(url(), "bms_owner", OWNER_PASSWORD);
    }

    public static DataSource appDataSource() {
        return new DriverManagerDataSource(url(), "bms_app", APP_PASSWORD);
    }

    /**
     * A new, empty database in the same container, given every database-level step {@code 01-roles.sh} gives {@code bms}
     * (owner, public schema owner, the revokes and grants); the script's other steps create the two roles, which belong to
     * the whole server, and it creates no extension and sets no setting. For a test that replays a migration, so its locks
     * never reach the shared database; it calls {@link #dropScratchDatabase} when done. Returns the database's JDBC URL.
     */
    public static String scratchDatabase(String name) {
        requireScratchName(name);
        superuser(POSTGRES.getDatabaseName())
                .sql("CREATE DATABASE " + name + " OWNER bms_owner")
                .update();
        JdbcClient scratch = superuser(name);
        scratch.sql("ALTER SCHEMA public OWNER TO bms_owner").update();
        scratch.sql("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC").update();
        scratch.sql("GRANT CONNECT, TEMPORARY ON DATABASE " + name + " TO bms_app")
                .update();
        scratch.sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC").update();
        scratch.sql("GRANT USAGE ON SCHEMA public TO bms_app").update();
        return urlOf(name);
    }

    public static void dropScratchDatabase(String name) {
        requireScratchName(name);
        superuser(POSTGRES.getDatabaseName())
                .sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)")
                .update();
    }

    /** The name goes into DDL unquoted, so only lower case letters, digits and underscores are taken. */
    private static void requireScratchName(String name) {
        if (!name.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("A scratch database name is lower case letters, digits and underscores");
        }
    }

    private static String urlOf(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private static JdbcClient superuser(String database) {
        return JdbcClient.create(
                new DriverManagerDataSource(urlOf(database), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** bms_owner bypasses RLS: for arranging fixtures and asserting on raw rows only. */
    public static JdbcClient owner() {
        return JdbcClient.create(ownerDataSource());
    }

    /** A fabricated tenant with a head office branch and, optionally, lending enabled. */
    public static Fixture tenant(String slugPrefix, boolean lending) {
        return tenant(slugPrefix, lending, false);
    }

    /**
     * As {@link #tenant(String, boolean)}; {@code retail} switches the retail module on through the
     * same platform function the platform API calls, so its chart of accounts is seeded as on a
     * server (ADR-020 decision 7).
     */
    public static Fixture tenant(String slugPrefix, boolean lending, boolean retail) {
        String slug = slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        UUID headOffice = UUID.randomUUID();
        UUID secondBranch = UUID.randomUUID();
        JdbcClient owner = owner();
        owner.sql(
                        "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, ?, ?, '00000000-0000-4000-8000-000000000001')")
                .params(tenantId, slug, "Test Tenant " + slug)
                .update();
        owner.sql(
                        "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                .params(headOffice, tenantId)
                .update();
        owner.sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'BR2', 'Test Branch Two')")
                .params(secondBranch, tenantId)
                .update();
        if (lending) {
            owner.sql("INSERT INTO tenant_modules (tenant_id, module_key) VALUES (?, 'lending')")
                    .param(tenantId)
                    .update();
            owner.sql("SELECT bms_seed_lending_chart(?)")
                    .param(tenantId)
                    .query(Integer.class)
                    .single();
        }
        if (retail) {
            owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                    .params(tenantId, lending ? "{lending,retail}" : "{retail}")
                    .query((rs, n) -> 1)
                    .single();
        }
        return new Fixture(tenantId, slug, headOffice, secondBranch);
    }

    public record Fixture(UUID tenantId, String slug, UUID headOffice, UUID secondBranch) {

        public UUID account(String systemKey) {
            return owner().sql("SELECT id FROM gl_accounts WHERE tenant_id = ? AND system_key = ?")
                    .params(tenantId, systemKey)
                    .query(UUID.class)
                    .single();
        }
    }
}
