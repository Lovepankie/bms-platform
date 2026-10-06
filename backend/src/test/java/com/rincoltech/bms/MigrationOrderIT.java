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
 * fixes), V20 (the retail import references), V21 (the lending review follow-ups), V22 (the retail stock
 * transfers of #84) and V25 (lending disbursement and repayments, #108) apply in order on an empty database, and on a
 * database a server already migrated to V9 before the retail work reached it, with
 * {@code outOfOrder} off exactly as {@link DatabaseMigrator} runs it. Each case gets its own
 * PostgreSQL 16 container initialised by {@code deploy/postgres/initdb/01-roles.sh}.
 */
class MigrationOrderIT {

    static final List<String> VERSIONS =
            List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "20", "21", "22", "25");

    @Test
    void everyMigrationAppliesInOrderOnAnEmptyDatabase() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.success).isTrue();
            assertThat(result.targetSchemaVersion).isEqualTo("25");
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
                    .containsExactly("10", "11", "12", "13", "14", "20", "21", "22", "25");
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

    /**
     * #84: a tenant that switched retail on before V22 has no inter-branch clearing account in its
     * retail chart; V22 adds it, so its first transfer can post one entry per branch (ADR-004).
     * JUSTIFICATION-A3: a new test case; no existing case migrates a retail tenant across V22.
     */
    @Test
    void transfersMigrationGivesAnExistingRetailTenantTheClearingAccount() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "21").migrate().targetSchemaVersion).isEqualTo("21");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            UUID tenant = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v21', 'Test Tenant V21', '00000000-0000-4000-8000-000000000001')")
                    .param(tenant)
                    .update();
            owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                    .params(tenant, "{retail}")
                    .query((rs, n) -> 1)
                    .single();
            assertThat(clearing(owner, tenant)).isEmpty();

            DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(clearing(owner, tenant)).containsExactly("1190 true");
        }
    }

    /**
     * #108: staging already holds tenants with loans when V25 arrives. Its CHECKs on the loan balance
     * columns hold for every row written before it (all zero), and its new tables start empty.
     * JUSTIFICATION-A3: a new test case; no existing case migrates a database with loans across V25.
     */
    @Test
    void servicingMigrationAppliesOnADatabaseThatAlreadyHoldsLoans() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "22").migrate().targetSchemaVersion).isEqualTo("22");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            UUID tenant = UUID.randomUUID();
            UUID branch = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v22', 'Test Tenant V22', '00000000-0000-4000-8000-000000000001')")
                    .param(tenant)
                    .update();
            owner.sql(
                            "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                    .params(branch, tenant)
                    .update();
            owner.sql("""
                            INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                currency, kyc_status, status, source)
                            VALUES (?, ?, ?, 'M000001', 'Test Borrower 01', '+256700000001', 'none', 'UGX', 'verified',
                                'active', 'staff')
                            """).params(member, tenant, branch).update();
            owner.sql(
                            "INSERT INTO lending_loan_products (id, tenant_id, code, name, status) VALUES (?, ?, 'TEST', 'Test', 'active')")
                    .params(product, tenant)
                    .update();
            owner.sql("""
                            INSERT INTO lending_loan_product_versions (id, tenant_id, product_id, version_no, currency,
                                interest_method, interest_rate_bp, rate_unit, term_unit, min_term_count, max_term_count,
                                default_term_count, repayment_pattern, min_principal_minor, max_principal_minor, created_by)
                            VALUES (?, ?, ?, 1, 'UGX', 'flat', 1000, 'per_term', 'month', 1, 3, 1, 'bullet', 100000,
                                1000000, ?)
                            """).params(version, tenant, product, UUID.randomUUID()).update();
            for (String status : List.of("draft", "approved", "cancelled")) {
                owner.sql("""
                                INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                                    officer_user_id, status, channel, purpose_category, currency,
                                    requested_principal_minor, requested_term_count, term_unit, interest_method,
                                    interest_rate_bp, rate_unit, repayment_pattern)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'staff', 'business', 'UGX', 500000, 1, 'month', 'flat',
                                    1000, 'per_term', 'bullet')
                                """)
                        .params(
                                UUID.randomUUID(),
                                tenant,
                                branch,
                                "LN-" + status,
                                member,
                                version,
                                UUID.randomUUID(),
                                status)
                        .update();
            }

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.success).isTrue();
            assertThat(result.migrations.stream().map(m -> m.version).toList()).containsExactly("25");
            assertThat(owner.sql("SELECT count(*) FROM lending_loans WHERE tenant_id = ?")
                            .param(tenant)
                            .query(Long.class)
                            .single())
                    .isEqualTo(3);
            assertThat(owner.sql("SELECT count(*) FROM lending_schedule_items")
                            .query(Long.class)
                            .single())
                    .isZero();
        }
    }

    private static List<String> clearing(JdbcClient owner, UUID tenant) {
        return owner.sql("""
                        SELECT code || ' ' || is_system_controlled FROM gl_accounts
                         WHERE tenant_id = ? AND system_key = 'interbranch_clearing'
                        """).param(tenant).query(String.class).list();
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
