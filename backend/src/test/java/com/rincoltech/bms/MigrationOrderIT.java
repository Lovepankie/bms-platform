package com.rincoltech.bms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The one Flyway sequence shared by lending and retail (issue #71, review F6): V1 to V9 (lending,
 * V9 the loan appraisals of #42), V10 to V14 (retail R1 to R4, the price floor and the review
 * fixes) and V20 (the retail import references) apply in order on an empty database, and on a
 * database a server already migrated to V9 before the retail work reached it, with
 * {@code outOfOrder} off exactly as {@link DatabaseMigrator} runs it. Each case gets its own
 * PostgreSQL 16 container initialised by {@code deploy/postgres/initdb/01-roles.sh}.
 */
class MigrationOrderIT {

    static final List<String> VERSIONS =
            List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "20");

    @Test
    void everyMigrationAppliesInOrderOnAnEmptyDatabase() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.success).isTrue();
            assertThat(result.targetSchemaVersion).isEqualTo("20");
            assertThat(applied(postgres)).containsExactlyElementsOf(VERSIONS);
            assertThat(flyway(postgres, null).info().pending()).isEmpty();
        }
    }

    @Test
    void retailAndImportMigrationsApplyOnADatabaseAlreadyAtV9() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            // A server that took the loan pull requests first: schema at V9, with a lending tenant.
            MigrateResult first = flyway(postgres, "9").migrate();
            assertThat(first.targetSchemaVersion).isEqualTo("9");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            UUID tenant = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v9', 'Test Tenant V9', '00000000-0000-4000-8000-000000000001')")
                    .param(tenant)
                    .update();
            owner.sql(
                            "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                    .params(UUID.randomUUID(), tenant)
                    .update();
            owner.sql("INSERT INTO tenant_modules (tenant_id, module_key) VALUES (?, 'lending')")
                    .param(tenant)
                    .update();
            owner.sql("SELECT bms_seed_lending_chart(?)")
                    .param(tenant)
                    .query(Integer.class)
                    .single();
            long lendingAccounts = accounts(owner, tenant);

            MigrateResult second =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(second.success).isTrue();
            assertThat(second.migrations.stream().map(m -> m.version).toList())
                    .containsExactly("10", "11", "12", "13", "14", "20");
            assertThat(applied(postgres)).containsExactlyElementsOf(VERSIONS);
            assertThat(flyway(postgres, null).info().pending()).isEmpty();
            // The tenant from V9 can switch retail on: its chart is seeded next to the lending one.
            owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                    .params(tenant, "{lending,retail}")
                    .query((rs, n) -> 1)
                    .single();
            assertThat(accounts(owner, tenant)).isGreaterThan(lendingAccounts);
        }
    }

    @Test
    void noTwoMigrationsShareAVersion() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            MigrationInfo[] all = flyway(postgres, null).info().all();
            assertThat(Arrays.stream(all).map(i -> i.getVersion().getVersion()).toList())
                    .containsExactlyElementsOf(VERSIONS);
            assertThat(Arrays.stream(all).map(MigrationInfo::getState)).containsOnly(MigrationState.PENDING);
        }
    }

    private static PostgreSQLContainer database() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bms")
                .withUsername("postgres")
                .withPassword("test-superuser-password")
                .withEnv("BMS_OWNER_PASSWORD", TestDatabase.OWNER_PASSWORD)
                .withEnv("BMS_APP_PASSWORD", TestDatabase.APP_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("../deploy/postgres/initdb/01-roles.sh"), 0755),
                        "/docker-entrypoint-initdb.d/01-roles.sh");
    }

    /** The migrator's settings, optionally stopped at a target version. */
    private static Flyway flyway(PostgreSQLContainer postgres, String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(true);
        return (target == null ? config : config.target(target)).load();
    }

    private static List<String> applied(PostgreSQLContainer postgres) {
        return Arrays.stream(flyway(postgres, null).info().applied())
                .peek(i -> assertThat(i.getState()).isEqualTo(MigrationState.SUCCESS))
                .map(i -> i.getVersion().getVersion())
                .toList();
    }

    private static long accounts(JdbcClient owner, UUID tenant) {
        return owner.sql("SELECT count(*) FROM gl_accounts WHERE tenant_id = ?")
                .param(tenant)
                .query(Long.class)
                .single();
    }
}
