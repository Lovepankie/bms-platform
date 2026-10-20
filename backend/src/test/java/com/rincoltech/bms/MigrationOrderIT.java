package com.rincoltech.bms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The one Flyway sequence shared by lending and retail (issue #71, review F6): V1 to V9 (lending,
 * V9 the loan appraisals of #42), V10 to V14 (retail R1 to R4, the price floor and the review
 * fixes), V20 (the retail import references), V21 (the lending review follow-ups), V22 (the retail
 * stock transfers of #84), V23 (the onboarding applications and the outbox of #89), V26 (the
 * indexes of #107), V28 (the retail catalogue management of #146), V29 (lending disbursement and
 * repayments, #108), V30 (lending savings, #151) and V31 (the per-user preferences of #19) apply in order on an
 * empty database, and on a
 * database a server already migrated to V9 before the retail work reached it, with
 * {@code outOfOrder} off exactly as {@link DatabaseMigrator} runs it. Each case gets its own
 * PostgreSQL 16 container initialised by {@code deploy/postgres/initdb/01-roles.sh}.
 */
class MigrationOrderIT {

    /**
     * The migrations already merged and deployed: their numbers never change and none is removed.
     * Merge order matters because {@code outOfOrder} is off: V41 (the retail price lists, #247, merged)
     * came before V42 (the loan pledge guard, #176, merged), then V43 (payment methods, #160, merged),
     * then V44 (the retail customer accounts, #196), then V45 (customer returns, #201, merged). Nothing here enforces the order, merge order does.
     */
    static final List<String> MERGED = List.of(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "20", "21", "22", "23", "26",
            "28", "29", "30", "31", "32", "33", "34", "37", "38", "39", "40", "41", "42", "43", "44", "45", "46");

    /** Every migration file on the classpath in numeric order: a new migration only adds its file. */
    static final List<String> VERSIONS = discoveredVersions();

    static List<String> discoveredVersions() {
        try {
            Path dir =
                    Path.of(MigrationOrderIT.class.getResource("/db/migration").toURI());
            try (Stream<Path> files = Files.list(dir)) {
                return files.map(f -> f.getFileName().toString())
                        .filter(n -> n.matches("V\\d+__.*\\.sql"))
                        .map(n -> n.substring(1, n.indexOf("__")))
                        .sorted(Comparator.comparingInt(Integer::parseInt))
                        .toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot list the migrations", e);
        }
    }

    @Test
    void mergedMigrationsAreNeverRemovedOrRenumbered() {
        assertThat(VERSIONS).startsWith(MERGED.toArray(String[]::new));
        assertThat(VERSIONS).doesNotHaveDuplicates();
    }

    @Test
    void everyMigrationAppliesInOrderOnAnEmptyDatabase() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.success).isTrue();
            assertThat(result.targetSchemaVersion).isEqualTo(VERSIONS.get(VERSIONS.size() - 1));
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
                    .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("10"), VERSIONS.size()));
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
     * #108: staging already holds tenants with loans when V29 arrives. Its CHECKs on the loan balance
     * columns hold for every row written before it (all zero), and its new tables start empty.
     * JUSTIFICATION-A3: a new test case; no existing case migrates a database with loans across V29.
     */
    @Test
    void servicingMigrationAppliesOnADatabaseThatAlreadyHoldsLoans() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "28").migrate().targetSchemaVersion).isEqualTo("28");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            UUID tenant = UUID.randomUUID();
            UUID branch = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v23', 'Test Tenant V23', '00000000-0000-4000-8000-000000000001')")
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
            assertThat(result.migrations.stream().map(m -> m.version).toList())
                    .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("29"), VERSIONS.size()));
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

    /**
     * #151: V30 widens the outbox channel CHECK to accept SMS and adds the savings tables, so a
     * database at V29 with a pending email keeps it, and an SMS row can then be queued.
     * JUSTIFICATION-A3: a new test case; no existing case migrates outbox rows across V30.
     */
    @Test
    void savingsMigrationKeepsTheOutboxRowsOfADatabaseAtV29() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "29").migrate().targetSchemaVersion).isEqualTo("29");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            String enqueue =
                    "SELECT notification_outbox_enqueue(?, ?, ?, 'test.template', '{}'::jsonb, ?, NULL, NULL, now())";
            owner.sql(enqueue)
                    .params(UUID.randomUUID(), "email", "test@example.test", "test:v29")
                    .query(Boolean.class)
                    .single();

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.migrations.stream().map(m -> m.version).toList())
                    .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("30"), VERSIONS.size()));
            assertThat(owner.sql(enqueue)
                            .params(UUID.randomUUID(), "sms", "+256700000001", "test:v30")
                            .query(Boolean.class)
                            .single())
                    .isTrue();
            assertThat(owner.sql("SELECT channel FROM notification_outbox ORDER BY idempotency_key")
                            .query(String.class)
                            .list())
                    .containsExactly("email", "sms");
        }
    }

    /**
     * #212: the SMS channel migration adds the tenant, category and subject to the outbox and replaces the
     * claim. A database at V47 that holds pending SMS rows queued by the old code (no tenant, the balance in
     * the parameters) keeps them, they read as transactional rows of no tenant, and the new claim still
     * takes them.
     * JUSTIFICATION-A3: a new test case; no existing case migrates SMS rows across the SMS channel migration.
     */
    @Test
    void smsChannelMigrationKeepsThePendingSmsRowsOfADatabaseAtV47() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "47").migrate().targetSchemaVersion).isEqualTo("47");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            owner.sql(
                            "SELECT notification_outbox_enqueue(?, 'sms', '+256700000001', 'savings.deposit',"
                                    + " '{\"balance\": \"75,000\"}'::jsonb, 'test:v47-sms', NULL, now() + interval '2 days', now())")
                    .param(UUID.randomUUID())
                    .query(Boolean.class)
                    .single();

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.migrations.stream().map(m -> m.version).toList())
                    .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("47") + 1, VERSIONS.size()));
            var row = owner.sql(
                            "SELECT status, tenant_id IS NULL AS no_tenant, category, subject_type IS NULL AS no_subject,"
                                    + " params->>'balance' AS balance FROM notification_outbox WHERE idempotency_key = 'test:v47-sms'")
                    .query()
                    .singleRow();
            assertThat(row.get("status")).isEqualTo("pending");
            assertThat(row.get("no_tenant")).isEqualTo(true);
            assertThat(row.get("category")).isEqualTo("transactional");
            assertThat(row.get("no_subject")).isEqualTo(true);
            assertThat(row.get("balance")).isEqualTo("75,000");
            // The replaced claim still takes it.
            assertThat(owner.sql("SELECT idempotency_key FROM notification_outbox_claim(ARRAY['sms'], 3,"
                                    + " now() + interval '1 minute', now() + interval '3 minutes')")
                            .query(String.class)
                            .list())
                    .containsExactly("test:v47-sms");
        }
    }

    /**
     * #221: the member event SMS migration (V53) adds {@code lending_investments.notify_member} and backfills it. A database
     * at V52 that already holds investments (a register loaded by the seed or an import, or by staff earlier) must not have
     * its investors sent a maturity reminder the day SMS is switched on: the flag becomes false for an investment whose
     * funding did not come from a staff request, and for one funded more than 3 days ago on the tenant's calendar, and stays
     * true for one funded by staff in the last 3 days (the cutoff day included) and for one still waiting for its funding.
     * JUSTIFICATION-A3: a new test case; no existing case migrates investments across the event SMS migration.
     */
    @Test
    void eventSmsMigrationBackfillsTheNotifyFlagOfExistingInvestments() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            replayEventSmsBackfill(postgres.getJdbcUrl(), "postgres", "test-superuser-password");
        }
    }

    /**
     * The body of {@link #eventSmsMigrationBackfillsTheNotifyFlagOfExistingInvestments} against any empty database whose roles
     * were made by {@code 01-roles.sh}, so that it can also be replayed where Docker is not available. The rows are
     * fabricated as the superuser with foreign keys switched off for the session (the question is about one column, not
     * about the ledger).
     */
    static void replayEventSmsBackfill(String url, String superUser, String superPassword) {
        assertThat(flyway(url, "52").migrate().targetSchemaVersion).isEqualTo("52");
        SingleConnectionDataSource single = new SingleConnectionDataSource(url, superUser, superPassword, true);
        JdbcClient su = JdbcClient.create(single);
        su.sql("SET session_replication_role = replica").update();
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        su.sql(
                        "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v53', 'Test Tenant V53', '00000000-0000-4000-8000-000000000001')")
                .param(tenant)
                .update();
        record Case(String accountNo, String status, Integer daysAgo, String source, boolean expected) {}
        List<Case> cases = List.of(
                new Case("IV900001", "active", 0, "staff", true),
                new Case("IV900002", "active", 3, "staff", true),
                new Case("IV900003", "active", 4, "staff", false),
                new Case("IV900004", "active", 400, "staff", false),
                new Case("IV900005", "active", 0, "system", false),
                new Case("IV900006", "active", 0, "import", false),
                new Case("IV900007", "active", 1, "portal", false),
                new Case("IV900008", "pending_funding", null, null, true));
        for (Case c : cases) {
            UUID id = UUID.randomUUID();
            su.sql("""
                            INSERT INTO lending_investments (id, tenant_id, branch_id, account_no, member_id, product_id,
                                currency, status, principal_minor, return_rate_bp, return_method, term_months,
                                payout_frequency, product_type, early_withdrawal_allowed, start_date, maturity_date,
                                agreed_return_minor, principal_held_minor, channel)
                            SELECT ?, ?, ?, ?, ?, ?, 'UGX', ?, 1000000, 1200, 'flat', 6, 'at_maturity', 'fixed_term',
                                   false, d.s, d.s + 180, CASE WHEN d.s IS NULL THEN NULL ELSE 60000 END,
                                   CASE WHEN d.s IS NULL THEN 0 ELSE 1000000 END, 'staff'
                              FROM (SELECT (now() AT TIME ZONE 'Africa/Kampala')::date - ?::int AS s) d
                            """)
                    .params(
                            id,
                            tenant,
                            branch,
                            c.accountNo(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            c.status(),
                            c.daysAgo())
                    .update();
            if (c.source() != null) {
                su.sql("""
                                INSERT INTO lending_investment_transactions (id, tenant_id, branch_id, investment_id,
                                    txn_type, amount_minor, principal_minor, currency, value_date, journal_entry_id,
                                    source)
                                VALUES (?, ?, ?, ?, 'funding', 1000000, 1000000, 'UGX',
                                    (now() AT TIME ZONE 'Africa/Kampala')::date - ?::int, ?, ?)
                                """)
                        .params(UUID.randomUUID(), tenant, branch, id, c.daysAgo(), UUID.randomUUID(), c.source())
                        .update();
            }
        }

        MigrateResult result = DatabaseMigrator.migrate(url, "bms_owner", TestDatabase.OWNER_PASSWORD);

        assertThat(result.success).isTrue();
        assertThat(result.migrations.stream().map(m -> m.version).toList()).contains("53");
        JdbcClient owner =
                JdbcClient.create(new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD));
        for (Case c : cases) {
            assertThat(owner.sql("SELECT notify_member FROM lending_investments WHERE account_no = ?")
                            .param(c.accountNo())
                            .query(Boolean.class)
                            .single())
                    .as("%s (%s, %s days ago, %s)", c.accountNo(), c.status(), c.daysAgo(), c.source())
                    .isEqualTo(c.expected());
        }
        // A new investment is told by default, and the outbox learned its lease and the channel's last look.
        assertThat(owner.sql(
                                "SELECT column_default FROM information_schema.columns WHERE table_name = 'lending_investments'"
                                        + " AND column_name = 'notify_member'")
                        .query(String.class)
                        .single())
                .isEqualTo("true");
        assertThat(owner.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'notification_outbox'"
                                + " AND column_name = 'leased_until'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(owner.sql("SELECT notification_outbox_is_pending(?)")
                        .param(UUID.randomUUID())
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    private static List<String> clearing(JdbcClient owner, UUID tenant) {
        return owner.sql("""
                        SELECT code || ' ' || is_system_controlled FROM gl_accounts
                         WHERE tenant_id = ? AND system_key = 'interbranch_clearing'
                        """).param(tenant).query(String.class).list();
    }

    /**
     * #107: V26 changes only indexes and a storage setting, so a database at V22 that holds
     * retail rows (a negative balance among them) keeps every row, and the old indexes give way to
     * the new ones. ADR-028 has the lock times measured on 25 times the staging data.
     * JUSTIFICATION-A3: a new test case; no existing case migrates retail data across V26.
     */
    @Test
    void optimisationMigrationKeepsTheRowsOfADatabaseAtV22() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "22").migrate().targetSchemaVersion).isEqualTo("22");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            UUID tenant = UUID.randomUUID();
            UUID branch = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v22', 'Test Tenant V22', '00000000-0000-4000-8000-000000000001')")
                    .param(tenant)
                    .update();
            owner.sql(
                            "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                    .params(branch, tenant)
                    .update();
            owner.sql("""
                            WITH c AS (INSERT INTO retail_categories (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'Test')
                                       RETURNING id),
                                 u AS (INSERT INTO retail_units (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'pcs')
                                       RETURNING id)
                            INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                         sell_minor, currency)
                            SELECT ?, ?, 'T1', 'Test product', c.id, u.id, 100, 150, 'UGX' FROM c, u
                            """).params(tenant, tenant, product, tenant).update();
            owner.sql("""
                            INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id,
                                                                kind, qty, unit_cost_minor, source_type)
                            VALUES (gen_random_uuid(), ?, now(), current_date, ?, ?, 'adjustment', -2, 100, 'test')
                            """).params(tenant, branch, product).update();
            owner.sql("INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id, qty) VALUES (?, ?, ?, -2)")
                    .params(tenant, branch, product)
                    .update();

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.migrations.stream().map(m -> m.version).toList())
                    .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("23"), VERSIONS.size()));
            assertThat(owner.sql("SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ?")
                            .param(tenant)
                            .query(String.class)
                            .list())
                    .containsExactly("-2.000");
            assertThat(owner.sql("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ?")
                            .param(tenant)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L);
            assertThat(owner.sql("""
                                    SELECT indexname FROM pg_indexes
                                     WHERE indexname IN ('retail_stock_movements_position', 'retail_sale_lines_by_sale',
                                                         'retail_sales_by_created', 'retail_stock_balances_negative',
                                                         'retail_stock_movements_balance', 'retail_sale_lines_sale')
                                     ORDER BY indexname
                                    """).query(String.class).list())
                    .containsExactly(
                            "retail_sale_lines_by_sale", "retail_sales_by_created", "retail_stock_movements_position");
            assertThat(owner.sql("SELECT reloptions::text FROM pg_class WHERE relname = 'retail_stock_balances'")
                            .query(String.class)
                            .single())
                    .isEqualTo("{fillfactor=80}");
        }
    }

    /**
     * #193: staging already holds transfers when the two-step transfer arrives. The migration replaces
     * the status check, the entry-pair check, the void-column checks and the update guard of V22; every
     * existing row (completed, voided) satisfies the new rules, all the new constraints end validated,
     * and an existing completed transfer is still voided once by the same update and no more.
     * JUSTIFICATION-A3: a new test case; no existing case migrates transfers across V38.
     */
    @Test
    void twoStepTransfersMigrationKeepsTheTransfersOfADatabaseAtV37() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "37").migrate().targetSchemaVersion).isEqualTo("37");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            UUID tenant = UUID.randomUUID();
            UUID hq = UUID.randomUUID();
            UUID two = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            UUID completed = UUID.randomUUID();
            UUID voided = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v37', 'Test Tenant V37', '00000000-0000-4000-8000-000000000001')")
                    .param(tenant)
                    .update();
            owner.sql(
                            "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                    .params(hq, tenant)
                    .update();
            owner.sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'BR2', 'Test Branch Two')")
                    .params(two, tenant)
                    .update();
            owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                    .params(tenant, "{retail}")
                    .query((rs, n) -> 1)
                    .single();
            owner.sql("""
                            WITH c AS (INSERT INTO retail_categories (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'Test')
                                       RETURNING id),
                                 u AS (INSERT INTO retail_units (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'pcs')
                                       RETURNING id)
                            INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                         sell_minor, currency)
                            SELECT ?, ?, 'T1', 'Test product', c.id, u.id, 100, 150, 'UGX' FROM c, u
                            """).params(tenant, tenant, product, tenant).update();
            for (UUID id : List.of(completed, voided)) {
                boolean isVoided = id.equals(voided);
                owner.sql("""
                                INSERT INTO retail_transfers (id, tenant_id, from_branch_id, to_branch_id, transfer_date, currency,
                                    cost_total_minor, status, created_by, voided_at, voided_by, void_reason)
                                VALUES (?, ?, ?, ?, DATE '2026-01-15', 'UGX', 100, ?, gen_random_uuid(),
                                    CASE WHEN ? THEN now() END, CASE WHEN ? THEN gen_random_uuid() END,
                                    CASE WHEN ? THEN 'Test reason' END)
                                """)
                        .params(id, tenant, hq, two, isVoided ? "voided" : "completed", isVoided, isVoided, isVoided)
                        .update();
                owner.sql("""
                                INSERT INTO retail_transfer_lines (id, tenant_id, transfer_id, line_no, product_id, qty,
                                    unit_cost_minor, line_cost_minor)
                                VALUES (gen_random_uuid(), ?, ?, 1, ?, 1, 100, 100)
                                """).params(tenant, id, product).update();
            }

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.success).isTrue();
            assertThat(result.migrations.stream().map(m -> m.version).toList())
                    .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("38"), VERSIONS.size()));
            assertThat(owner.sql("SELECT status FROM retail_transfers WHERE tenant_id = ? ORDER BY status")
                            .param(tenant)
                            .query(String.class)
                            .list())
                    .containsExactly("completed", "voided");
            assertThat(owner.sql(
                                    "SELECT count(*) FROM retail_transfer_lines WHERE tenant_id = ? AND received_qty IS NULL")
                            .param(tenant)
                            .query(Long.class)
                            .single())
                    .isEqualTo(2);
            assertThat(owner.sql("""
                                    SELECT count(*) FROM pg_constraint
                                     WHERE conrelid IN ('retail_transfers'::regclass, 'retail_transfer_lines'::regclass)
                                       AND contype = 'c' AND NOT convalidated
                                    """).query(Long.class).single())
                    .as("every check ends validated")
                    .isZero();
            assertThat(owner.sql("""
                                    SELECT conname FROM pg_constraint
                                     WHERE conrelid = 'retail_transfers'::regclass AND contype = 'c'
                                       AND conname IN ('retail_transfers_status_check', 'retail_transfers_check1',
                                                       'retail_transfers_check2', 'retail_transfers_check3')
                                    """).query(String.class).list())
                    .as("the replaced V22 checks are gone")
                    .isEmpty();
            assertThat(owner.sql("""
                                    UPDATE retail_transfers SET status = 'voided', voided_at = now(), voided_by = gen_random_uuid(),
                                        void_reason = 'Test reason' WHERE id = ?
                                    """).param(completed).update()).isEqualTo(1);
            assertThatThrownBy(() -> owner.sql("UPDATE retail_transfers SET void_reason = 'Other' WHERE id = ?")
                            .param(completed)
                            .update())
                    .rootCause()
                    .hasMessageContaining("voided to voided is not a transition");
        }
    }

    /**
     * #194: staging already holds sales and restocks when units of measure arrive. The migration adds
     * nullable columns, a table and an account; every existing sale line and purchase line keeps null
     * unit columns (entered in the base unit), the new checks and foreign keys end validated, and the
     * retail tenant gets the purchase_rounding account.
     * JUSTIFICATION-A3: a new test case; no existing case migrates sale or purchase lines across V39 (V38 sits between).
     */
    @Test
    void unitsMigrationKeepsTheSalesAndRestocksOfADatabaseAtV37() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "37").migrate().targetSchemaVersion).isEqualTo("37");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            UUID tenant = UUID.randomUUID();
            UUID branch = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            UUID sale = UUID.randomUUID();
            UUID purchase = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v37u', 'Test Tenant V37U', '00000000-0000-4000-8000-000000000001')")
                    .param(tenant)
                    .update();
            owner.sql(
                            "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                    .params(branch, tenant)
                    .update();
            owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                    .params(tenant, "{retail}")
                    .query((rs, n) -> 1)
                    .single();
            owner.sql("""
                            WITH c AS (INSERT INTO retail_categories (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'Test')
                                       RETURNING id),
                                 u AS (INSERT INTO retail_units (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'pcs')
                                       RETURNING id)
                            INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                         sell_minor, currency)
                            SELECT ?, ?, 'T1', 'Test product', c.id, u.id, 83, 100, 'UGX' FROM c, u
                            """).params(tenant, tenant, product, tenant).update();
            owner.sql("""
                            INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, currency,
                                total_minor, cost_total_minor, paid_minor, status, created_by)
                            VALUES (?, ?, ?, 'RS00000001', DATE '2026-01-15', 'cash', 'UGX', 300, 249, 300, 'completed',
                                gen_random_uuid())
                            """).params(sale, tenant, branch).update();
            owner.sql("""
                            INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                                unit_cost_minor, line_total_minor, line_cost_minor)
                            VALUES (gen_random_uuid(), ?, ?, 1, ?, 3, 100, 83, 300, 249)
                            """).params(tenant, sale, product).update();
            owner.sql("""
                            INSERT INTO retail_purchases (id, tenant_id, purchase_no, purchased_on, payment_method, currency,
                                total_minor, created_by)
                            VALUES (?, ?, 'RP00000001', DATE '2026-01-15', 'cash', 'UGX', 830, gen_random_uuid())
                            """).params(purchase, tenant).update();
            owner.sql("""
                            INSERT INTO retail_purchase_lines (id, tenant_id, purchase_id, line_no, product_id, cost_minor,
                                qty_total, line_total_minor)
                            VALUES (gen_random_uuid(), ?, ?, 1, ?, 83, 10, 830)
                            """).params(tenant, purchase, product).update();
            assertThat(owner.sql(
                                    "SELECT count(*) FROM gl_accounts WHERE tenant_id = ? AND system_key = 'purchase_rounding'")
                            .param(tenant)
                            .query(Long.class)
                            .single())
                    .isZero();

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.success).isTrue();
            assertThat(result.migrations.stream().map(m -> m.version).toList())
                    .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("37") + 1, VERSIONS.size()));
            assertThat(owner.sql("""
                                    SELECT count(*) FROM retail_sale_lines
                                     WHERE tenant_id = ? AND sale_unit_id IS NULL AND sale_qty IS NULL
                                       AND sale_unit_price_minor IS NULL AND unit_factor IS NULL AND qty = 3
                                    """).param(tenant).query(Long.class).single()).isEqualTo(1);
            assertThat(owner.sql("""
                                    SELECT count(*) FROM retail_purchase_lines
                                     WHERE tenant_id = ? AND unit_id IS NULL AND unit_qty_total IS NULL
                                       AND unit_cost_minor IS NULL AND unit_factor IS NULL AND line_total_minor = 830
                                    """).param(tenant).query(Long.class).single()).isEqualTo(1);
            assertThat(owner.sql("""
                                    SELECT count(*) FROM pg_constraint
                                     WHERE conrelid IN ('retail_sale_lines'::regclass, 'retail_purchase_lines'::regclass,
                                                        'retail_product_units'::regclass)
                                       AND NOT convalidated
                                    """).query(Long.class).single())
                    .as("every constraint ends validated")
                    .isZero();
            assertThat(owner.sql(
                                    "SELECT count(*) FROM gl_accounts WHERE tenant_id = ? AND system_key = 'purchase_rounding'")
                            .param(tenant)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            assertThat(owner.sql("SELECT count(*) FROM retail_product_units WHERE tenant_id = ?")
                            .param(tenant)
                            .query(Long.class)
                            .single())
                    .isZero();
            // The all-or-none check: half a unit record is refused.
            assertThatThrownBy(
                            () -> owner.sql("""
                                    INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty,
                                        unit_price_minor, unit_cost_minor, line_total_minor, line_cost_minor, sale_qty)
                                    VALUES (gen_random_uuid(), ?, ?, 2, ?, 1, 100, 83, 100, 83, 1)
                                    """).params(tenant, sale, product).update())
                    .hasMessageContaining("retail_sale_lines_unit_chk");
        }
    }

    /**
     * #195: staging already holds sales and credit buyers when price lists arrive. The migration adds three tables, nullable
     * columns and three permissions; every existing sale line keeps null price columns (priced as before), every buyer has no
     * group and no list, every new check and foreign key ends validated, the sales role gets the price override it has always
     * had in effect and only the administrator the list permissions, and a half-recorded price is refused.
     * JUSTIFICATION-A3: a new test case; no existing case migrates sales across V41 (V40 sits between).
     */
    @Test
    void priceListsMigrationKeepsTheSalesAndBuyersOfADatabaseAtV39() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            assertThat(flyway(postgres, "39").migrate().targetSchemaVersion).isEqualTo("39");
            JdbcClient owner = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD));
            UUID tenant = UUID.randomUUID();
            UUID branch = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            UUID customer = UUID.randomUUID();
            UUID sale = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v39p', 'Test Tenant V39P', '00000000-0000-4000-8000-000000000001')")
                    .param(tenant)
                    .update();
            owner.sql(
                            "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                    .params(branch, tenant)
                    .update();
            owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                    .params(tenant, "{retail}")
                    .query((rs, n) -> 1)
                    .single();
            owner.sql("""
                            WITH c AS (INSERT INTO retail_categories (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'Test')
                                       RETURNING id),
                                 u AS (INSERT INTO retail_units (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'pcs')
                                       RETURNING id)
                            INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                         sell_minor, currency)
                            SELECT ?, ?, 'T1', 'Test product', c.id, u.id, 83, 100, 'UGX' FROM c, u
                            """).params(tenant, tenant, product, tenant).update();
            owner.sql("INSERT INTO retail_customers (id, tenant_id, name) VALUES (?, ?, 'Test Buyer 01')")
                    .params(customer, tenant)
                    .update();
            owner.sql("""
                            INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                                currency, total_minor, cost_total_minor, paid_minor, status, created_by)
                            VALUES (?, ?, ?, 'RS00000001', DATE '2026-01-15', 'cash', ?, 'UGX', 300, 249, 300, 'completed',
                                gen_random_uuid())
                            """).params(sale, tenant, branch, customer).update();
            owner.sql("""
                            INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                                unit_cost_minor, line_total_minor, line_cost_minor)
                            VALUES (gen_random_uuid(), ?, ?, 1, ?, 3, 100, 83, 300, 249)
                            """).params(tenant, sale, product).update();

            MigrateResult result =
                    DatabaseMigrator.migrate(postgres.getJdbcUrl(), "bms_owner", TestDatabase.OWNER_PASSWORD);

            assertThat(result.success).isTrue();
            assertThat(result.migrations.stream().map(m -> m.version).toList())
                    .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("39") + 1, VERSIONS.size()));
            assertThat(owner.sql("""
                                    SELECT count(*) FROM retail_sale_lines
                                     WHERE tenant_id = ? AND price_source IS NULL AND price_list_id IS NULL
                                       AND price_break_min_qty IS NULL AND resolved_unit_price_minor IS NULL
                                       AND price_override_reason IS NULL AND line_total_minor = 300
                                    """).param(tenant).query(Long.class).single()).isEqualTo(1);
            assertThat(owner.sql("""
                                    SELECT count(*) FROM retail_customers
                                     WHERE tenant_id = ? AND customer_group_id IS NULL AND price_list_id IS NULL
                                    """).param(tenant).query(Long.class).single()).isEqualTo(1);
            assertThat(owner.sql("SELECT price_list_id IS NULL FROM retail_sales WHERE id = ?")
                            .param(sale)
                            .query(Boolean.class)
                            .single())
                    .isTrue();
            assertThat(owner.sql("""
                                    SELECT count(*) FROM pg_constraint
                                     WHERE conrelid IN ('retail_sale_lines'::regclass, 'retail_sales'::regclass,
                                                        'retail_customers'::regclass, 'retail_price_lists'::regclass,
                                                        'retail_price_list_items'::regclass,
                                                        'retail_customer_groups'::regclass)
                                       AND NOT convalidated
                                    """).query(Long.class).single())
                    .as("every constraint ends validated")
                    .isZero();
            assertThat(owner.sql("""
                                    SELECT string_agg(role_key, ',' ORDER BY role_key) FROM role_permissions
                                     WHERE permission_key = 'retail.price.override'
                                    """).query(String.class).single()).isEqualTo("retail_sales,tenant_admin");
            assertThat(owner.sql("""
                                    SELECT string_agg(role_key, ',' ORDER BY role_key) FROM role_permissions
                                     WHERE permission_key IN ('retail.pricelist.manage', 'retail.pricelist.use')
                                    """).query(String.class).single()).isEqualTo("tenant_admin,tenant_admin");
            // A half-recorded price is refused by the database: an override with no reason.
            assertThatThrownBy(
                            () -> owner.sql("""
                                    INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty,
                                        unit_price_minor, unit_cost_minor, line_total_minor, line_cost_minor, price_source,
                                        resolved_unit_price_minor)
                                    VALUES (gen_random_uuid(), ?, ?, 2, ?, 1, 100, 83, 100, 83, 'override', 100)
                                    """).params(tenant, sale, product).update())
                    .hasMessageContaining("retail_sale_lines_price_chk");
        }
    }

    /**
     * #196: staging already holds credit buyers and credit sales when customer accounts arrive. The migration adds two nullable
     * columns, two tables and two permissions and touches none of the sales tables: every buyer keeps a null limit and null
     * terms, every sale and payment is as it was, the column lists of the sales tables are unchanged, every new check and
     * foreign key ends validated, only the administrator gets the credit permissions, and a limit below zero or terms of more
     * than a year are refused by the database.
     * JUSTIFICATION-A3: a new test case; no existing case migrates credit buyers across V44 (V42 and V43 are merged by then, so the case also applies them).
     */
    @Test
    void customerAccountsMigrationKeepsTheBuyersAndSalesOfADatabaseAtV41() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            verifyCustomerAccountsMigration(postgres.getJdbcUrl());
        }
    }

    /** The body of the case above, on any empty database that has the roles of {@code 01-roles.sh}. */
    static void verifyCustomerAccountsMigration(String url) {
        assertThat(flyway(url, "41").migrate().targetSchemaVersion).isEqualTo("41");
        JdbcClient owner =
                JdbcClient.create(new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD));
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v41a', 'Test Tenant V41A', '00000000-0000-4000-8000-000000000001')")
                .param(tenant)
                .update();
        owner.sql(
                        "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                .params(branch, tenant)
                .update();
        owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                .params(tenant, "{retail}")
                .query((rs, n) -> 1)
                .single();
        owner.sql("INSERT INTO retail_customers (id, tenant_id, name) VALUES (?, ?, 'Test Buyer 01')")
                .params(customer, tenant)
                .update();
        owner.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                            due_date, currency, total_minor, cost_total_minor, paid_minor, status, created_by)
                        VALUES (?, ?, ?, 'RS00000001', DATE '2026-01-15', 'credit', ?, DATE '2026-02-15', 'UGX', 300, 249, 100,
                            'completed', gen_random_uuid())
                        """).params(sale, tenant, branch, customer).update();
        String sales = "('retail_sales', 'retail_sale_lines', 'retail_sale_payments')";
        String columns =
                "SELECT string_agg(table_name || '.' || column_name || ':' || data_type, ',' ORDER BY table_name,"
                        + " ordinal_position) FROM information_schema.columns WHERE table_schema = 'public' AND table_name IN "
                        + sales;
        String before = owner.sql(columns).query(String.class).single();

        MigrateResult result = DatabaseMigrator.migrate(url, "bms_owner", TestDatabase.OWNER_PASSWORD);

        assertThat(result.success).isTrue();
        assertThat(result.migrations.stream().map(m -> m.version).toList())
                .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("41") + 1, VERSIONS.size()));
        assertThat(owner.sql(columns).query(String.class).single())
                .as("the sales tables keep their shape")
                .isEqualTo(before);
        assertThat(owner.sql("""
                                SELECT count(*) FROM retail_customers
                                 WHERE tenant_id = ? AND credit_limit_minor IS NULL AND terms_days IS NULL
                                """).param(tenant).query(Long.class).single()).isEqualTo(1);
        assertThat(owner.sql("SELECT paid_minor FROM retail_sales WHERE id = ?")
                        .param(sale)
                        .query(Long.class)
                        .single())
                .isEqualTo(100);
        assertThat(owner.sql("""
                                SELECT count(*) FROM pg_constraint
                                 WHERE conrelid IN ('retail_customers'::regclass, 'retail_account_payments'::regclass,
                                                    'retail_account_payment_allocations'::regclass)
                                   AND NOT convalidated
                                """).query(Long.class).single())
                .as("every constraint ends validated")
                .isZero();
        assertThat(owner.sql("""
                                SELECT string_agg(role_key || ':' || permission_key, ',' ORDER BY permission_key) FROM role_permissions
                                 WHERE permission_key IN ('retail.credit.manage', 'retail.credit.override')
                                """).query(String.class).single())
                .isEqualTo("tenant_admin:retail.credit.manage,tenant_admin:retail.credit.override");
        // The database refuses a limit below zero, an absurd limit and terms of more than a year.
        assertThatThrownBy(() -> owner.sql("UPDATE retail_customers SET credit_limit_minor = -1 WHERE id = ?")
                        .param(customer)
                        .update())
                .hasMessageContaining("retail_customers_credit_limit_chk");
        assertThatThrownBy(() -> owner.sql("UPDATE retail_customers SET terms_days = 366 WHERE id = ?")
                        .param(customer)
                        .update())
                .hasMessageContaining("retail_customers_terms_chk");
        // The sales guard still refuses to lower a paid amount outside the reversal of a payment on account, even when
        // no
        // payment row backs it (this sale was written with 100 paid and none): V44 does not weaken it.
        assertThatThrownBy(() -> owner.sql("UPDATE retail_sales SET paid_minor = 40 WHERE id = ?")
                        .param(sale)
                        .update())
                .hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> owner.sql("UPDATE retail_sales SET paid_minor = 0 WHERE id = ?")
                        .param(sale)
                        .update())
                .hasStackTraceContaining("only the paid amount");
        // A receipt of a payment on account is reached only through the application.
        assertThat(owner.sql("SELECT count(*) FROM retail_account_payments")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * #201: staging already holds credit sales, some of them part paid, when customer returns arrive. The migration adds two
     * tables, a helper function, a new body for the sales guard, the account 4110 and one permission, and touches none of
     * the large tables: the column lists of the sales tables and of the stock movements are unchanged, the sale keeps its paid
     * amount, both new tables are empty, every new check and foreign key ends validated, the retail tenant gets the contra
     * account with a debit normal balance, only the administrator gets the permission, and the guard still refuses to lower
     * a paid amount outside a named reversed receipt or voided return while it lets one rise.
     * JUSTIFICATION-A3: a new test case; no existing case migrates a part paid credit sale across V45.
     */
    @Test
    void customerReturnsMigrationKeepsTheSalesOfADatabaseAtV44() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            verifyCustomerReturnsMigration(postgres.getJdbcUrl());
        }
    }

    /** The body of the case above, on any empty database that has the roles of {@code 01-roles.sh}. */
    static void verifyCustomerReturnsMigration(String url) {
        assertThat(flyway(url, "44").migrate().targetSchemaVersion).isEqualTo("44");
        JdbcClient owner =
                JdbcClient.create(new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD));
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v44r', 'Test Tenant V44R', '00000000-0000-4000-8000-000000000001')")
                .param(tenant)
                .update();
        owner.sql(
                        "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                .params(branch, tenant)
                .update();
        owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                .params(tenant, "{retail}")
                .query((rs, n) -> 1)
                .single();
        owner.sql("INSERT INTO retail_customers (id, tenant_id, name) VALUES (?, ?, 'Test Buyer 01')")
                .params(customer, tenant)
                .update();
        owner.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                            due_date, currency, total_minor, cost_total_minor, paid_minor, status, created_by)
                        VALUES (?, ?, ?, 'RS00000001', DATE '2026-01-15', 'credit', ?, DATE '2026-02-15', 'UGX', 300, 249, 100,
                            'completed', gen_random_uuid())
                        """).params(sale, tenant, branch, customer).update();
        String large = "('retail_sales', 'retail_sale_lines', 'retail_sale_payments', 'retail_stock_movements')";
        String columns =
                "SELECT string_agg(table_name || '.' || column_name || ':' || data_type, ',' ORDER BY table_name,"
                        + " ordinal_position) FROM information_schema.columns WHERE table_schema = 'public' AND table_name IN "
                        + large;
        String before = owner.sql(columns).query(String.class).single();

        MigrateResult result = DatabaseMigrator.migrate(url, "bms_owner", TestDatabase.OWNER_PASSWORD);

        assertThat(result.success).isTrue();
        assertThat(result.migrations.stream().map(m -> m.version).toList())
                .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("44") + 1, VERSIONS.size()));
        assertThat(owner.sql(columns).query(String.class).single())
                .as("the large tables keep their shape")
                .isEqualTo(before);
        assertThat(owner.sql("SELECT paid_minor FROM retail_sales WHERE id = ?")
                        .param(sale)
                        .query(Long.class)
                        .single())
                .isEqualTo(100);
        assertThat(owner.sql("""
                                SELECT (SELECT count(*) FROM retail_sale_returns) + (SELECT count(*) FROM retail_sale_return_lines)
                                """).query(Long.class).single())
                .as("a tenant that never records a return writes nothing")
                .isZero();
        assertThat(owner.sql("""
                                SELECT count(*) FROM pg_constraint
                                 WHERE conrelid IN ('retail_sale_returns'::regclass, 'retail_sale_return_lines'::regclass)
                                   AND NOT convalidated
                                """).query(Long.class).single())
                .as("every constraint ends validated")
                .isZero();
        assertThat(owner.sql("""
                                SELECT account_type || ':' || normal_balance FROM gl_accounts
                                 WHERE tenant_id = ? AND system_key = 'sales_returns' AND code = '4110'
                                """).param(tenant).query(String.class).single()).isEqualTo("income:debit");
        assertThat(owner.sql("""
                                SELECT string_agg(role_key, ',' ORDER BY role_key) FROM role_permissions
                                 WHERE permission_key = 'retail.sale.return'
                                """).query(String.class).single()).isEqualTo("tenant_admin");
        assertThat(owner.sql("SELECT retail_sale_settled_minor(?, ?)")
                        .params(tenant, sale)
                        .query(Long.class)
                        .single())
                .as("no payment row and no return backs the paid amount of this sale")
                .isZero();
        // The guard still refuses a lowering, with no setting and with a settings value naming nothing, and lets a rise
        // through.
        assertThatThrownBy(() -> owner.sql("UPDATE retail_sales SET paid_minor = 40 WHERE id = ?")
                        .param(sale)
                        .update())
                .hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> owner.sql("UPDATE retail_sales SET paid_minor = 0 WHERE id = ?")
                        .param(sale)
                        .update())
                .hasStackTraceContaining("only the paid amount");
        assertThat(owner.sql("UPDATE retail_sales SET paid_minor = 150 WHERE id = ?")
                        .param(sale)
                        .update())
                .isEqualTo(1);
    }

    /**
     * #197: staging already holds sales, transfers, customer returns and stored receipts when the optional documents arrive. The migration
     * adds six tables and one permission and replaces one CHECK on documents: every sale, transfer and stored file is as
     * it was, the sales tables keep their shape, every new check ends validated, the old stored kinds are still accepted
     * and the new ones are too (an unknown one is not), only the administrator gets the permission, and the database
     * itself refuses a counter that skips, a deleted counter, an edited document and a second change of a quotation.
     * JUSTIFICATION-A3: a new test case; no existing case migrates a database across V46 (V45, the returns slice, is merged by then).
     */
    @Test
    void documentsMigrationKeepsTheSalesReturnsAndFilesOfADatabaseAtV45() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            verifyDocumentsMigration(postgres.getJdbcUrl());
        }
    }

    /** The body of the case above, on any empty database that has the roles of {@code 01-roles.sh}. */
    static void verifyDocumentsMigration(String url) {
        assertThat(flyway(url, "45").migrate().targetSchemaVersion).isEqualTo("45");
        JdbcClient owner =
                JdbcClient.create(new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD));
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        UUID saleReturn = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v44d', 'Test Tenant V44D', '00000000-0000-4000-8000-000000000001')")
                .param(tenant)
                .update();
        owner.sql(
                        "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                .params(branch, tenant)
                .update();
        owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                .params(tenant, "{retail}")
                .query((rs, n) -> 1)
                .single();
        owner.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            currency, total_minor, cost_total_minor, paid_minor, status, created_by)
                        VALUES (?, ?, ?, 'RS00000001', DATE '2026-01-15', 'cash', 'Test Buyer 01', 'UGX', 300, 249, 300,
                            'completed', gen_random_uuid())
                        """).params(sale, tenant, branch).update();
        // A customer return of V45 (the credit note), whose number V46 must leave alone.
        owner.sql("""
                        INSERT INTO retail_sale_returns (id, tenant_id, credit_note_no, branch_seq, sale_id, branch_id,
                            refund_branch_id, returned_on, currency, total_minor, cost_minor, refund_minor, refund_method,
                            refund_choice, created_by)
                        VALUES (?, ?, 'CNHQ-000001', 1, ?, ?, ?, DATE '2026-01-16', 'UGX', 100, 60, 100, 'cash', 'cash',
                            gen_random_uuid())
                        """).params(saleReturn, tenant, sale, branch, branch).update();
        owner.sql("""
                        INSERT INTO documents (id, tenant_id, doc_type, object_key, content_type, size_bytes, sha256)
                        VALUES (gen_random_uuid(), ?, 'receipt', 'tenants/' || ?::text || '/receipt/x.pdf', 'application/pdf', 1,
                                repeat('a', 64))
                        """).params(tenant, tenant).update();
        String sales = "('retail_sales', 'retail_sale_lines', 'retail_sale_payments', 'retail_transfers')";
        String columns =
                "SELECT string_agg(table_name || '.' || column_name || ':' || data_type, ',' ORDER BY table_name,"
                        + " ordinal_position) FROM information_schema.columns WHERE table_schema = 'public' AND table_name IN "
                        + sales;
        String before = owner.sql(columns).query(String.class).single();

        MigrateResult result = DatabaseMigrator.migrate(url, "bms_owner", TestDatabase.OWNER_PASSWORD);

        assertThat(result.success).isTrue();
        assertThat(result.migrations.stream().map(m -> m.version).toList())
                .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("45") + 1, VERSIONS.size()));
        assertThat(owner.sql(columns).query(String.class).single())
                .as("the sales and transfer tables keep their shape")
                .isEqualTo(before);
        assertThat(owner.sql("SELECT credit_note_no || ':' || total_minor FROM retail_sale_returns WHERE id = ?")
                        .param(saleReturn)
                        .query(String.class)
                        .single())
                .as("the return and its credit note number are as V45 left them")
                .isEqualTo("CNHQ-000001:100");
        assertThat(owner.sql("SELECT count(*) FROM retail_credit_note_issues")
                        .query(Long.class)
                        .single())
                .as("a credit note PDF is made only when asked")
                .isZero();
        assertThat(owner.sql("SELECT paid_minor FROM retail_sales WHERE id = ?")
                        .param(sale)
                        .query(Long.class)
                        .single())
                .isEqualTo(300);
        assertThat(owner.sql("SELECT count(*) FROM documents WHERE tenant_id = ?")
                        .param(tenant)
                        .query(Long.class)
                        .single())
                .as("the stored receipt satisfies the replaced check")
                .isEqualTo(1);
        assertThat(owner.sql("""
                                SELECT count(*) FROM pg_constraint
                                 WHERE conrelid IN ('documents'::regclass, 'retail_document_prefixes'::regclass,
                                                    'retail_document_series'::regclass, 'retail_quotations'::regclass,
                                                    'retail_quotation_lines'::regclass, 'retail_documents'::regclass,
                                                    'retail_credit_note_issues'::regclass)
                                   AND NOT convalidated
                                """).query(Long.class).single())
                .as("every constraint ends validated")
                .isZero();
        assertThat(owner.sql("SELECT count(*) FROM pg_constraint WHERE conname = 'documents_doc_type_check'")
                        .query(Long.class)
                        .single())
                .as("the old check is gone and the new one stands")
                .isZero();
        assertThat(owner.sql("""
                                SELECT string_agg(role_key || ':' || permission_key, ',' ORDER BY permission_key) FROM role_permissions
                                 WHERE permission_key = 'retail.document.issue'
                                """).query(String.class).single()).isEqualTo("tenant_admin:retail.document.issue");
        String insert = "INSERT INTO documents (id, tenant_id, doc_type, object_key, content_type, size_bytes, sha256)"
                + " VALUES (gen_random_uuid(), ?, ?, 'tenants/' || ?::text || '/' || gen_random_uuid()::text, 'application/pdf', 1,"
                + " repeat('b', 64))";
        for (String kind : List.of("invoice", "delivery_note", "quotation", "credit_note", "voucher")) {
            assertThat(owner.sql(insert).params(tenant, kind, tenant).update())
                    .as(kind)
                    .isEqualTo(1);
        }
        assertThatThrownBy(() -> owner.sql(insert)
                        .params(tenant, "statement_of_account", tenant)
                        .update())
                .hasMessageContaining("documents_doc_type_trade_chk");

        // The counter goes up by one and never down, skips or disappears; a document is never edited or deleted.
        owner.sql(
                        "INSERT INTO retail_document_series (tenant_id, type, branch_id, last_no) VALUES (?, 'invoice', ?, 0)")
                .params(tenant, branch)
                .update();
        assertThat(owner.sql("UPDATE retail_document_series SET last_no = last_no + 1 WHERE tenant_id = ?")
                        .param(tenant)
                        .update())
                .isEqualTo(1);
        assertThatThrownBy(() -> owner.sql("UPDATE retail_document_series SET last_no = 5 WHERE tenant_id = ?")
                        .param(tenant)
                        .update())
                .hasStackTraceContaining("only goes up by one");
        assertThatThrownBy(() -> owner.sql("UPDATE retail_document_series SET last_no = 0 WHERE tenant_id = ?")
                        .param(tenant)
                        .update())
                .hasStackTraceContaining("only goes up by one");
        assertThatThrownBy(() -> owner.sql("DELETE FROM retail_document_series WHERE tenant_id = ?")
                        .param(tenant)
                        .update())
                .hasStackTraceContaining("append-only");
        UUID document = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_documents (id, tenant_id, created_by, type, number, seq_no, branch_id, issue_date,
                            sale_id, buyer_name, currency, total_minor, snapshot)
                        VALUES (?, ?, gen_random_uuid(), 'invoice', 'INV-HQ-000001', 1, ?, DATE '2026-01-16', ?,
                            'Test Buyer 01', 'UGX', 300, '{"v": 1}')
                        """).params(document, tenant, branch, sale).update();
        assertThatThrownBy(() -> owner.sql("UPDATE retail_documents SET buyer_name = 'x' WHERE id = ?")
                        .param(document)
                        .update())
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> owner.sql("DELETE FROM retail_documents WHERE id = ?")
                        .param(document)
                        .update())
                .hasStackTraceContaining("append-only");
        // One invoice per sale, one number per series, a document with no source is refused.
        assertThatThrownBy(() -> owner.sql("""
                                INSERT INTO retail_documents (id, tenant_id, created_by, type, number, seq_no, branch_id, issue_date,
                                    sale_id, currency, snapshot)
                                VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'invoice', 'INV-HQ-000002', 2, ?, DATE '2026-01-16', ?,
                                    'UGX', '{}')
                                """).params(tenant, branch, sale).update())
                .hasMessageContaining("retail_documents_sale");
        assertThatThrownBy(() -> owner.sql("""
                                INSERT INTO retail_documents (id, tenant_id, created_by, type, number, seq_no, branch_id, issue_date,
                                    currency, snapshot)
                                VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'invoice', 'INV-HQ-000003', 3, ?, DATE '2026-01-16',
                                    'UGX', '{}')
                                """).params(tenant, branch).update())
                .hasMessageContaining("retail_documents_check");
        // The facts of a credit note PDF: one per return, never edited, and only for a return that exists.
        owner.sql("""
                        INSERT INTO retail_credit_note_issues (tenant_id, return_id, issued_by, issued_on, buyer_name)
                        VALUES (?, ?, gen_random_uuid(), DATE '2026-01-17', 'Test Buyer 01')
                        """).params(tenant, saleReturn).update();
        assertThatThrownBy(() -> owner.sql("UPDATE retail_credit_note_issues SET buyer_name = 'x' WHERE return_id = ?")
                        .param(saleReturn)
                        .update())
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> owner.sql("""
                                INSERT INTO retail_credit_note_issues (tenant_id, return_id, issued_by, issued_on, buyer_name)
                                VALUES (?, gen_random_uuid(), gen_random_uuid(), DATE '2026-01-17', 'Test Buyer 01')
                                """).param(tenant).update())
                .hasMessageContaining("retail_credit_note_issues");
        // A quotation changes once, from open to converted or cancelled, and its lines never.
        UUID quotation = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_quotations (id, tenant_id, created_by, branch_id, quote_date, valid_until, buyer_name,
                            currency, total_minor)
                        VALUES (?, ?, gen_random_uuid(), ?, DATE '2026-01-15', DATE '2026-02-15', 'Test Buyer 01', 'UGX', 0)
                        """).params(quotation, tenant, branch).update();
        assertThatThrownBy(() -> owner.sql("UPDATE retail_quotations SET total_minor = 5 WHERE id = ?")
                        .param(quotation)
                        .update())
                .hasStackTraceContaining("can only be converted or cancelled");
        assertThat(owner.sql("""
                                UPDATE retail_quotations SET status = 'cancelled', cancelled_at = now(), cancelled_by = gen_random_uuid(),
                                       cancel_reason = 'Test reason', version = version + 1 WHERE id = ?
                                """).param(quotation).update()).isEqualTo(1);
        assertThatThrownBy(() -> owner.sql("""
                                UPDATE retail_quotations SET status = 'converted', converted_sale_id = ?, converted_at = now(),
                                       converted_by = gen_random_uuid(), cancelled_at = NULL, cancelled_by = NULL, cancel_reason = NULL,
                                       version = version + 1 WHERE id = ?
                                """).params(sale, quotation).update())
                .hasStackTraceContaining("can only be converted or cancelled");
    }

    /**
     * Optional purchasing (#198, V47): a database a server already migrated to V46, holding
     * a retail tenant with a supplier and a credit restock, takes the migration without touching any existing table: the
     * column lists of the plain restock, the stock movements and the journal are unchanged, the restock row is as it was, every
     * new table is empty with its constraints validated, the retail tenant gets the two chart accounts with their normal
     * balances, only the administrator gets the two permissions, and the plain restock still inserts.
     * JUSTIFICATION-A3: a new test case; no existing case migrates a retail restock across the purchasing migration (V46, the documents slice, is merged by then).
     */
    @Test
    void optionalPurchasingMigrationLeavesTheRestockOfADatabaseAtV46AsItWas() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            verifyPurchasingMigration(postgres.getJdbcUrl());
        }
    }

    /** The body of the case above, on any empty database that has the roles of {@code 01-roles.sh}. */
    static void verifyPurchasingMigration(String url) {
        assertThat(flyway(url, "46").migrate().targetSchemaVersion).isEqualTo("46");
        JdbcClient owner =
                JdbcClient.create(new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD));
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v46p', 'Test Tenant V46P', '00000000-0000-4000-8000-000000000001')")
                .param(tenant)
                .update();
        owner.sql(
                        "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                .params(branch, tenant)
                .update();
        owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                .params(tenant, "{retail}")
                .query((rs, n) -> 1)
                .single();
        owner.sql("INSERT INTO retail_suppliers (id, tenant_id, name) VALUES (?, ?, 'Test Supplier 01')")
                .params(supplier, tenant)
                .update();
        owner.sql("""
                        INSERT INTO retail_purchases (id, tenant_id, purchase_no, supplier_id, purchased_on, payment_method,
                            currency, total_minor, created_by)
                        VALUES (?, ?, 'RP00000001', ?, DATE '2026-01-15', 'credit', 'UGX', 500, gen_random_uuid())
                        """).params(purchase, tenant, supplier).update();
        String existing = "('retail_purchases', 'retail_purchase_lines', 'retail_suppliers', 'retail_stock_movements',"
                + " 'journal_entries', 'journal_lines', 'gl_accounts')";
        String columns =
                "SELECT string_agg(table_name || '.' || column_name || ':' || data_type, ',' ORDER BY table_name,"
                        + " ordinal_position) FROM information_schema.columns WHERE table_schema = 'public' AND table_name IN "
                        + existing;
        String before = owner.sql(columns).query(String.class).single();

        MigrateResult result = DatabaseMigrator.migrate(url, "bms_owner", TestDatabase.OWNER_PASSWORD);

        assertThat(result.success).isTrue();
        assertThat(result.migrations.stream().map(m -> m.version).toList())
                .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("46") + 1, VERSIONS.size()));
        assertThat(owner.sql(columns).query(String.class).single())
                .as("the existing tables keep their shape")
                .isEqualTo(before);
        assertThat(owner.sql("SELECT payment_method || ':' || total_minor FROM retail_purchases WHERE id = ?")
                        .param(purchase)
                        .query(String.class)
                        .single())
                .isEqualTo("credit:500");
        String created = "('retail_purchasing_settings', 'retail_purchase_orders', 'retail_purchase_order_lines',"
                + " 'retail_goods_receipts', 'retail_goods_receipt_lines', 'retail_supplier_bills',"
                + " 'retail_supplier_bill_lines', 'retail_supplier_bill_entries', 'retail_supplier_payments',"
                + " 'retail_supplier_payment_allocations', 'retail_supplier_payment_entries')";
        assertThat(owner.sql("SELECT count(*) FROM pg_class WHERE relname IN " + created + " AND relkind = 'r'")
                        .query(Long.class)
                        .single())
                .as("eleven new tables")
                .isEqualTo(11);
        assertThat(owner.sql("""
                                SELECT (SELECT count(*) FROM retail_purchasing_settings) + (SELECT count(*) FROM retail_purchase_orders)
                                     + (SELECT count(*) FROM retail_purchase_order_lines) + (SELECT count(*) FROM retail_goods_receipts)
                                     + (SELECT count(*) FROM retail_goods_receipt_lines) + (SELECT count(*) FROM retail_supplier_bills)
                                     + (SELECT count(*) FROM retail_supplier_bill_lines) + (SELECT count(*) FROM retail_supplier_bill_entries)
                                     + (SELECT count(*) FROM retail_supplier_payments)
                                     + (SELECT count(*) FROM retail_supplier_payment_allocations)
                                     + (SELECT count(*) FROM retail_supplier_payment_entries)
                                """).query(Long.class).single())
                .as("a tenant that never turns the capability on writes nothing")
                .isZero();
        assertThat(owner.sql("""
                                SELECT count(*) FROM pg_constraint
                                 WHERE conrelid IN (SELECT oid FROM pg_class WHERE relname IN %s) AND NOT convalidated
                                """.formatted(created)).query(Long.class).single())
                .as("every constraint ends validated")
                .isZero();
        assertThat(owner.sql("""
                                SELECT string_agg(system_key || '=' || account_type || ':' || normal_balance, ',' ORDER BY system_key)
                                  FROM gl_accounts WHERE tenant_id = ?
                                   AND system_key IN ('goods_received_not_invoiced', 'purchase_price_variance')
                                """).param(tenant).query(String.class).single())
                .isEqualTo("goods_received_not_invoiced=liability:credit,purchase_price_variance=expense:debit");
        assertThat(owner.sql("""
                                SELECT string_agg(permission_key || '>' || role_key, ',' ORDER BY permission_key, role_key)
                                  FROM role_permissions WHERE permission_key IN ('retail.payable.manage', 'retail.purchase.approve')
                                """).query(String.class).single())
                .isEqualTo("retail.payable.manage>tenant_admin,retail.purchase.approve>tenant_admin");
        // The plain restock path is unchanged: another restock row inserts as before.
        assertThat(owner.sql("""
                                INSERT INTO retail_purchases (id, tenant_id, purchase_no, supplier_id, purchased_on, payment_method,
                                    currency, total_minor, created_by)
                                VALUES (gen_random_uuid(), ?, 'RP00000002', ?, DATE '2026-01-16', 'credit', 'UGX', 100, gen_random_uuid())
                                """).params(tenant, supplier).update()).isEqualTo(1);
    }

    /**
     * Landed cost and supplier credit notes (#199, V48): a database a server already migrated to V47, holding a retail tenant
     * with a stock movement and a purchasing settings row, takes the migration without touching any existing table's columns
     * but the one new setting; the eight new tables are empty with their constraints validated; the two stock movement checks
     * are replaced by looser ones that are not yet validated (the lock note of the file) and validate without failing; the
     * new kind return_to_supplier inserts only as a negative quantity; and no permission or chart account is added.
     * JUSTIFICATION-A3: a new test case; no existing case migrates a populated stock movement table across the landed cost migration.
     */
    @Test
    void landedCostMigrationReplacesTheMovementChecksWithoutScanningAndAddsEightEmptyTables() {
        try (PostgreSQLContainer postgres = database()) {
            postgres.start();
            verifyLandedCostMigration(postgres.getJdbcUrl());
        }
    }

    /** The body of the case above, on any empty database that has the roles of {@code 01-roles.sh}. */
    static void verifyLandedCostMigration(String url) {
        assertThat(flyway(url, "47").migrate().targetSchemaVersion).isEqualTo("47");
        JdbcClient owner =
                JdbcClient.create(new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD));
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'test-v47l', 'Test Tenant V47L', '00000000-0000-4000-8000-000000000001')")
                .param(tenant)
                .update();
        owner.sql(
                        "INSERT INTO branches (id, tenant_id, code, name, is_head_office) VALUES (?, ?, 'HQ', 'Head Office', true)")
                .params(branch, tenant)
                .update();
        owner.sql("SELECT platform_set_tenant_modules(?, ?::text[], NULL)")
                .params(tenant, "{retail}")
                .query((rs, n) -> 1)
                .single();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        owner.sql("INSERT INTO retail_categories (id, tenant_id, name) VALUES (?, ?, 'Test Category')")
                .params(category, tenant)
                .update();
        owner.sql("INSERT INTO retail_units (id, tenant_id, name) VALUES (?, ?, 'u-test')")
                .params(unit, tenant)
                .update();
        owner.sql("""
                        INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, currency, cost_minor, sell_minor)
                        VALUES (?, ?, 'TEST-1', 'Test Product', ?, ?, 'UGX', 100, 150)
                        """).params(product, tenant, category, unit).update();
        owner.sql("""
                        INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
                            unit_cost_minor, source_type)
                        VALUES (gen_random_uuid(), ?, now(), DATE '2026-01-15', ?, ?, 'purchase', 5, 100, 'test')
                        """).params(tenant, branch, product).update();
        owner.sql("INSERT INTO retail_purchasing_settings (tenant_id, bill_tolerance_bp) VALUES (?, 25)")
                .param(tenant)
                .update();
        String existing =
                "('retail_purchases', 'retail_suppliers', 'retail_goods_receipts', 'retail_goods_receipt_lines',"
                        + " 'retail_supplier_bills', 'retail_supplier_bill_lines', 'retail_stock_movements', 'journal_entries',"
                        + " 'journal_lines', 'gl_accounts')";
        String columns =
                "SELECT string_agg(table_name || '.' || column_name || ':' || data_type, ',' ORDER BY table_name,"
                        + " ordinal_position) FROM information_schema.columns WHERE table_schema = 'public' AND table_name IN "
                        + existing;
        String before = owner.sql(columns).query(String.class).single();
        long permissions =
                owner.sql("SELECT count(*) FROM permissions").query(Long.class).single();
        long accounts =
                owner.sql("SELECT count(*) FROM gl_accounts").query(Long.class).single();

        MigrateResult result = DatabaseMigrator.migrate(url, "bms_owner", TestDatabase.OWNER_PASSWORD);

        assertThat(result.success).isTrue();
        assertThat(result.migrations.stream().map(m -> m.version).toList())
                .containsExactlyElementsOf(VERSIONS.subList(VERSIONS.indexOf("47") + 1, VERSIONS.size()));
        assertThat(owner.sql(columns).query(String.class).single())
                .as("the existing tables keep their shape")
                .isEqualTo(before);
        // V48 adds no permission; V55 (the AI assistant, #226) adds the two advisor ones and V57 (#233) the payment
        // account
        // one, which are not V48's to answer for.
        assertThat(owner.sql(
                                "SELECT count(*) FROM permissions WHERE key NOT IN ('lending.advisor.use', 'lending.advisor.configure',"
                                        + " 'core.payment_account.manage', 'retail.controls.read', 'retail.credit.write_off')")
                        .query(Long.class)
                        .single())
                .as("no permission is added")
                .isEqualTo(permissions);
        // V64 (the write-off of a credit sale, #340) adds the chart account 5140, which is not V48's to answer for.
        assertThat(owner.sql("SELECT count(*) FROM gl_accounts WHERE system_key IS DISTINCT FROM 'bad_debt_loss'")
                        .query(Long.class)
                        .single())
                .as("no chart account is added")
                .isEqualTo(accounts);
        String created = "('retail_landed_costs', 'retail_landed_cost_charges', 'retail_landed_cost_lines',"
                + " 'retail_landed_cost_entries', 'retail_receipt_holds', 'retail_supplier_credits',"
                + " 'retail_supplier_credit_lines', 'retail_supplier_credit_entries')";
        assertThat(owner.sql("SELECT count(*) FROM pg_class WHERE relname IN " + created + " AND relkind = 'r'")
                        .query(Long.class)
                        .single())
                .as("eight new tables")
                .isEqualTo(8);
        assertThat(owner.sql("""
                                SELECT (SELECT count(*) FROM retail_landed_costs) + (SELECT count(*) FROM retail_landed_cost_charges)
                                     + (SELECT count(*) FROM retail_landed_cost_lines) + (SELECT count(*) FROM retail_landed_cost_entries)
                                     + (SELECT count(*) FROM retail_receipt_holds) + (SELECT count(*) FROM retail_supplier_credits)
                                     + (SELECT count(*) FROM retail_supplier_credit_lines)
                                     + (SELECT count(*) FROM retail_supplier_credit_entries)
                                """).query(Long.class).single())
                .as("a tenant that never turns the capability on writes nothing")
                .isZero();
        assertThat(owner.sql("""
                                SELECT count(*) FROM pg_constraint
                                 WHERE conrelid IN (SELECT oid FROM pg_class WHERE relname IN %s) AND NOT convalidated
                                """.formatted(created)).query(Long.class).single())
                .as("every constraint of the new tables ends validated")
                .isZero();
        assertThat(owner.sql("""
                                SELECT count(*) FROM pg_class c
                                 WHERE c.relname IN %s AND c.relkind = 'r' AND c.relrowsecurity AND c.relforcerowsecurity
                                """.formatted(created)).query(Long.class).single())
                .as("row-level security is forced on every new table")
                .isEqualTo(8);
        // The setting: the existing row reads the new column as false and keeps its value.
        assertThat(owner.sql(
                                "SELECT require_costing_before_sale || ':' || bill_tolerance_bp FROM retail_purchasing_settings")
                        .query(String.class)
                        .single())
                .isEqualTo("false:25");
        // The two movement checks were replaced without a scan, so they are not yet validated; validating them cannot
        // fail.
        assertThat(owner.sql("""
                                SELECT string_agg(conname || '=' || convalidated, ',' ORDER BY conname) FROM pg_constraint
                                 WHERE conrelid = 'retail_stock_movements'::regclass
                                   AND conname IN ('retail_stock_movements_kind_check', 'retail_stock_movements_check')
                                """).query(String.class).single())
                .isEqualTo("retail_stock_movements_check=false,retail_stock_movements_kind_check=false");
        owner.sql("ALTER TABLE retail_stock_movements VALIDATE CONSTRAINT retail_stock_movements_kind_check")
                .update();
        owner.sql("ALTER TABLE retail_stock_movements VALIDATE CONSTRAINT retail_stock_movements_check")
                .update();
        // The new kind goes out of stock, never in; an unknown kind is still refused; the old rows are as they were.
        String insert = """
                INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
                    unit_cost_minor, source_type)
                VALUES (gen_random_uuid(), ?, now(), DATE '2026-01-16', ?, ?, ?, CAST(? AS numeric), 100, 'test')
                """;
        assertThat(owner.sql(insert)
                        .params(tenant, branch, product, "return_to_supplier", "-2")
                        .update())
                .isEqualTo(1);
        assertThatThrownBy(() -> owner.sql(insert)
                        .params(tenant, branch, product, "return_to_supplier", "2")
                        .update())
                .hasStackTraceContaining("retail_stock_movements_check");
        assertThatThrownBy(() -> owner.sql(insert)
                        .params(tenant, branch, product, "sold_to_nobody", "-2")
                        .update())
                .hasStackTraceContaining("violates check constraint");
        assertThatThrownBy(() -> owner.sql(insert)
                        .params(tenant, branch, product, "sale", "2")
                        .update())
                .hasStackTraceContaining("retail_stock_movements_check");
        assertThat(owner.sql("SELECT count(*) FROM retail_stock_movements WHERE kind = 'purchase'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
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
        // Shared rootless Docker on the runners can hand out a host port another job just took.
        return new PostgreSQLContainer("postgres:16-alpine")
                .withStartupAttempts(3)
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
        return flyway(postgres.getJdbcUrl(), target);
    }

    private static Flyway flyway(String url, String target) {
        var config = Flyway.configure()
                .dataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
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
