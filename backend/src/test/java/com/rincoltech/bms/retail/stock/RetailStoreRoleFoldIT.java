package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.DatabaseMigrator;
import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

/**
 * Issue #371, ADR-104: the migration that folds the ADR-033 branch role {@code store} into the Store flag {@code sells_goods}.
 * The meaning chosen for #371 is ADR-048's Store: it keeps stock and takes no money at its till. The migration loses no row,
 * never strands a credit sale still owing, folds only a role that was in force (the retail module on and branch_roles on), and
 * says in the audit log what it did and why. It is replayed on a scratch database migrated to the migration before it, with
 * fabricated businesses and branches written as the previous release would have left them; its number and the one before it
 * are read from the migration folder, so a renumbering at merge keeps the test true. Names are fabricated.
 */
class RetailStoreRoleFoldIT extends IntegrationTest {

    static final String PLAN = "00000000-0000-4000-8000-000000000001";
    static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");
    static final String ROLES_ON = "{\"module_groups\": {\"retail\": {\"branch_roles\": true}}}";
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read";
    /** The role row and flags V115 leaves on a folded store. */
    static final String FOLDED_ROLE = "both";

    static final Map<String, Object> FOLDED_FLAGS = Map.of("sells", false, "stocks", true);

    @Autowired
    TestRestTemplate http;

    /** This migration's file and the version of the migration before it on main. */
    record Migration(Path file, int version, int previous) {}

    static Migration migration() throws IOException {
        List<Path> files;
        try (Stream<Path> listed = Files.list(MIGRATIONS)) {
            files = listed.toList();
        }
        Path own = files.stream()
                .filter(p -> p.getFileName().toString().endsWith("__retail_store_role_into_sells_goods.sql"))
                .findFirst()
                .orElseThrow();
        int version = versionOf(own);
        int previous = files.stream()
                .mapToInt(RetailStoreRoleFoldIT::versionOf)
                .filter(v -> v < version)
                .max()
                .orElseThrow();
        return new Migration(own, version, previous);
    }

    static int versionOf(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    @Test
    void theMigrationFoldsCleanStoresSkipsBlockedOnesAndCompletes() throws Exception {
        Migration v = migration();
        String database = "store_fold_replay_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String url = TestDatabase.scratchDatabase(database);
            DataSource ds = new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD);
            assertThat(DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                            .target(String.valueOf(v.previous()))
                            .load()
                            .migrate()
                            .targetSchemaVersion)
                    .isEqualTo(String.valueOf(v.previous()));
            JdbcClient owner = JdbcClient.create(ds);

            // Retail on and branch roles on: the role is in force. A store that keeps goods;
            // a store with a credit sale still owing; an open and a closed store that keep no goods;
            // a store already flagged; a shop; a branch with no row.
            UUID on = tenant(owner, "fold-on", ROLES_ON, true);
            UUID store = branch(owner, on, "STA", true, true, "store");
            UUID owing = branch(owner, on, "STO", true, true, "store");
            creditSaleOwing(owner, on, owing);
            UUID noGoods = branch(owner, on, "STB", false, true, "store");
            UUID closedNoGoods = branch(owner, on, "STG", false, true, "store");
            owner.sql("UPDATE branches SET status = 'inactive' WHERE id = ?")
                    .param(closedNoGoods)
                    .update();
            UUID flagged = branch(owner, on, "STC", true, false, "store");
            UUID shop = branch(owner, on, "SHA", true, true, "shop");
            UUID plain = branch(owner, on, "PLA", true, true, null);
            // Not in force, so never read and every branch sold: branch roles off; settings never saved; retail off
            // with the
            // switch left on.
            UUID off = tenant(owner, "fold-off", "{\"module_groups\": {\"retail\": {\"branch_roles\": false}}}", true);
            UUID dormant = branch(owner, off, "STD", true, true, "store");
            UUID never = tenant(owner, "fold-never", null, true);
            UUID neverStore = branch(owner, never, "STE", true, true, "store");
            UUID retailOff = tenant(owner, "fold-retail-off", ROLES_ON, false);
            UUID retailOffStore = branch(owner, retailOff, "STF", true, true, "store");
            long branchesBefore = count(owner, "SELECT count(*) FROM branches");
            long rolesBefore = count(owner, "SELECT count(*) FROM retail_branch_roles");

            // The migration completes: a branch with a blocker is skipped, never a reason to stop the deploy.
            assertThat(DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                            .load()
                            .migrate()
                            .success)
                    .isTrue();

            // Nothing is lost: every branch and every role row is still there.
            assertThat(count(owner, "SELECT count(*) FROM branches")).isEqualTo(branchesBefore);
            assertThat(count(owner, "SELECT count(*) FROM retail_branch_roles")).isEqualTo(rolesBefore);
            // The store in force that kept goods and owes nothing is now a Store: it keeps stock and takes no money.
            assertThat(flags(owner, store)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 2));
            // The store with a credit sale owing keeps selling, so the payments on it can still be taken at its till.
            assertThat(flags(owner, owing)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            // The rest keep their flags: no goods (cannot be a Store), already a Store, a shop,
            // no row, and every role that was not in force, retail off included.
            assertThat(flags(owner, noGoods)).isEqualTo(Map.of("sells", true, "stocks", false, "version", 1));
            assertThat(flags(owner, closedNoGoods)).isEqualTo(Map.of("sells", true, "stocks", false, "version", 1));
            assertThat(flags(owner, flagged)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 1));
            assertThat(flags(owner, shop)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, plain)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, dormant)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, neverStore)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, retailOffStore)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            // The role says only shop or both now; a store is read from the flag.
            for (UUID b : List.of(store, owing, noGoods, closedNoGoods, flagged, dormant, neverStore, retailOffStore)) {
                assertThat(role(owner, b)).as("role of %s", b).isEqualTo("both");
            }
            assertThat(role(owner, shop)).isEqualTo("shop");
            assertThat(count(owner, "SELECT count(*) FROM retail_branch_roles WHERE branch_id = '" + plain + "'"))
                    .isZero();
            // Every change is in the audit log, by the system, with what it was and why.
            assertThat(audited(owner, "core.branch.updated")).containsExactly(store + ":true>false");
            assertThat(audited(owner, "retail.branch_role.changed"))
                    .containsExactlyInAnyOrder(
                            store + ":store>both",
                            owing + ":store>both",
                            noGoods + ":store>both",
                            closedNoGoods + ":store>both",
                            flagged + ":store>both",
                            dormant + ":store>both",
                            neverStore + ":store>both",
                            retailOffStore + ":store>both");
            assertThat(reason(owner, store)).contains("is now a Store");
            // A skipped branch's audit row names its blockers, so an administrator can settle it and mark it a Store.
            assertThat(reason(owner, owing))
                    .contains("not made a Store because of credit_sales_owing")
                    .contains("keeps selling");
            assertThat(blockersOf(owner, owing)).isEqualTo("[\"credit_sales_owing\"]");
            assertThat(reason(owner, noGoods)).contains("not made a Store because of keeps_no_goods");
            assertThat(blockersOf(owner, noGoods)).isEqualTo("[\"keeps_no_goods\"]");
            assertThat(reason(owner, closedNoGoods)).contains("keeps no goods, so it cannot be a Store");
            assertThat(blockersOf(owner, store)).isEqualTo("[]");
            assertThat(reason(owner, flagged)).contains("already a Store");
            for (UUID b : List.of(dormant, neverStore, retailOffStore)) {
                assertThat(reason(owner, b)).as("reason of %s", b).contains("was not in force");
            }

            // The fold run again, on a store written as the previous release would (the old check
            // put back for it), folds that one only; a third run changes nothing. It runs as an
            // owner that bypasses row level security, so it sees every business's rows.
            assertThat(owner.sql("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
                            .query(Boolean.class)
                            .single())
                    .isTrue();
            String fold = between(Files.readString(v.file()), "store-fold");
            assertThat(fold).contains("UPDATE branches").contains("UPDATE retail_branch_roles");
            owner.sql("ALTER TABLE retail_branch_roles DROP CONSTRAINT retail_branch_roles_role_check, ADD CONSTRAINT"
                            + " retail_branch_roles_role_check CHECK (role IN ('store', 'shop', 'both'))")
                    .update();
            UUID late = branch(owner, on, "STL", true, true, "store");
            long audits = count(owner, "SELECT count(*) FROM audit_log");
            runAs(ds, fold);
            assertThat(flags(owner, late)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 2));
            assertThat(role(owner, late)).isEqualTo("both");
            assertThat(count(owner, "SELECT count(*) FROM audit_log")).isEqualTo(audits + 2);
            runAs(ds, fold);
            assertThat(count(owner, "SELECT count(*) FROM audit_log")).isEqualTo(audits + 2);
            assertThat(flags(owner, store)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 2));
            assertThat(flags(owner, late)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 2));
            owner.sql("ALTER TABLE retail_branch_roles DROP CONSTRAINT retail_branch_roles_role_check, ADD CONSTRAINT"
                            + " retail_branch_roles_role_check CHECK (role IN ('shop', 'both'))")
                    .update();

            // The role and the flag can never disagree again: the role table refuses store.
            assertThatThrownBy(() -> owner.sql("UPDATE retail_branch_roles SET role = 'store' WHERE branch_id = ?")
                            .param(shop)
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            TestDatabase.dropScratchDatabase(database);
        }
    }

    /**
     * The lock step of V115, run on its own on a migrated scratch database under live traffic. A sale of the previous release
     * holding a branch row FOR SHARE makes an attempt time out, a notice says so and the next attempt gets the locks. The sale
     * then reading the role table is never blocked behind the migration, because the migration takes branches first, as the
     * sale does. With the attempts used up it stops with its message and changes nothing.
     */
    @Test
    void theLockStepRetriesKeepsTheSaleOrderAndStopsClearly() throws Exception {
        String database = "store_fold_locks_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String url = TestDatabase.scratchDatabase(database);
            DataSource ds = new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD);
            DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                    .load()
                    .migrate();
            JdbcClient owner = JdbcClient.create(ds);
            UUID business = tenant(owner, "fold-locks", ROLES_ON, true);
            UUID shop = branch(owner, business, "SHL", true, true, "shop");
            String lock = between(Files.readString(migration().file()), "store-lock");

            // A sale holds its branch row past one attempt; the migration retries, then gets the locks.
            try (Connection sale = ds.getConnection();
                    Connection migration = ds.getConnection()) {
                sale.setAutoCommit(false);
                migration.setAutoCommit(false);
                holdBranchRow(sale, shop);
                CompletableFuture<List<String>> notices = CompletableFuture.supplyAsync(() -> run(migration, lock));
                awaitWaiting(owner, "branches");
                // The sale now reads the role table, as the previous release's sale does next: it is not blocked.
                long started = System.nanoTime();
                try (Statement s = sale.createStatement()) {
                    s.execute("SELECT count(*) FROM retail_branch_roles");
                }
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
                Thread.sleep(2_500);
                sale.commit();
                assertThat(notices.get(30, TimeUnit.SECONDS))
                        .anySatisfy(n -> assertThat(n).contains("lock attempt 1 of 40 did not get the locks"));
                migration.rollback();
            }

            // With the attempts used up (two here; 40 in a deploy) it stops with its message.
            try (Connection sale = ds.getConnection();
                    Connection migration = ds.getConnection()) {
                sale.setAutoCommit(false);
                migration.setAutoCommit(false);
                holdBranchRow(sale, shop);
                try (Statement s = migration.createStatement()) {
                    s.execute("SET bms.store_fold_lock_attempts = 2");
                }
                assertThatThrownBy(() -> run(migration, lock))
                        .hasMessageContaining("held retail_branch_roles or branches for about two minutes")
                        .hasMessageContaining("Nothing was changed");
                migration.rollback();
                sale.rollback();
            }
            assertThat(role(owner, shop)).isEqualTo("shop");
        } finally {
            TestDatabase.dropScratchDatabase(database);
        }
    }

    static void holdBranchRow(Connection sale, UUID branch) throws Exception {
        try (Statement s = sale.createStatement()) {
            s.execute("SELECT id FROM branches WHERE id = '" + branch + "' FOR SHARE");
        }
    }

    /** Runs SQL on the connection and returns the notices it raised. */
    static List<String> run(Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            List<String> notices = new java.util.ArrayList<>();
            for (java.sql.SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
                notices.add(w.getMessage());
            }
            return notices;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    static void runAs(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** The SQL between {@code -- name:begin} and {@code -- name:end} in the migration. */
    static String between(String sql, String name) {
        int from = sql.indexOf("-- " + name + ":begin") + ("-- " + name + ":begin").length();
        return sql.substring(from, sql.indexOf("-- " + name + ":end"));
    }

    /** Waits until a backend is waiting for a lock on the table. */
    static void awaitWaiting(JdbcClient owner, String table) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            long waiting = owner.sql("SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
                            + " WHERE NOT l.granted AND c.relname = ?")
                    .param(table)
                    .query(Long.class)
                    .single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the migration never waited for " + table);
    }

    // JUSTIFICATION-A3: the tests the reviews of #676 ask for (a mixed business in one run,
    // the blockers against real till activity, the lock step under traffic), with their helpers.
    /**
     * One business with a clean store and a store with credit owing migrates in a single run: one folded, one skipped. A
     * transaction of the previous release holds a lock on the role table for longer than one lock attempt (two seconds), so the
     * migration's lock times out at least once, retries and completes instead of failing the deploy.
     */
    @Test
    void aMixedBusinessMigratesInOneRun() throws Exception {
        Migration v = migration();
        String database = "store_fold_mixed_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String url = TestDatabase.scratchDatabase(database);
            DataSource ds = new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD);
            DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                    .target(String.valueOf(v.previous()))
                    .load()
                    .migrate();
            JdbcClient owner = JdbcClient.create(ds);
            UUID mixed = tenant(owner, "fold-mixed", ROLES_ON, true);
            UUID clean = branch(owner, mixed, "CLN", true, true, "store");
            UUID owing = branch(owner, mixed, "OWE", true, true, "store");
            creditSaleOwing(owner, mixed, owing);

            Connection sale = ds.getConnection();
            sale.setAutoCommit(false);
            try (Statement s = sale.createStatement()) {
                s.execute("SELECT count(*) FROM retail_branch_roles");
            }
            CompletableFuture<Void> released = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(3_500);
                    sale.commit();
                    sale.close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            long started = System.nanoTime();
            assertThat(DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                            .load()
                            .migrate()
                            .success)
                    .isTrue();
            released.get(30, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("the migration waited for the lock past one attempt")
                    .isGreaterThan(Duration.ofSeconds(2));

            assertThat(flags(owner, clean)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 2));
            assertThat(flags(owner, owing)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            // The state aFoldedStoreTakesNoMoneyAtItsTill writes by hand is the one V115 produces.
            assertThat(role(owner, clean)).isEqualTo(FOLDED_ROLE);
            assertThat(flags(owner, clean)).containsAllEntriesOf(FOLDED_FLAGS);
            assertThat(role(owner, owing)).isEqualTo("both");
            assertThat(audited(owner, "core.branch.updated")).containsExactly(clean + ":true>false");
            assertThat(blockersOf(owner, owing)).isEqualTo("[\"credit_sales_owing\"]");
            assertThat(reason(owner, clean)).contains("is now a Store");
        } finally {
            TestDatabase.dropScratchDatabase(database);
        }
    }

    /**
     * The blockers against real till activity: a credit sale owing, a later payment on a credit sale, a banking and a refund taken
     * at a branch since its role was set each make V115 skip it; a credit sale written off (#349) no longer counts as owing, a cash
     * sale's own payment is not a later payment, and what came before the role was set does not count.
     */
    @Test
    void theBlockersSeeEveryTillUseSinceTheRoleWasSetAndNotAWrittenOffSale() {
        TestDatabase.Fixture t = TestDatabase.tenant("fold-blockers", false, true);
        CashbookTestSupport cb = new CashbookTestSupport(http, t);
        RetailTestSupport api = cb.api;
        UUID branch = t.secondBranch();
        UUID product = api.product("FOLD-WIRE", 1_000, 1_500);
        api.stockUp(branch, product, "20");
        capability(api, "returns");
        LocalDate today = CashbookTestSupport.daysAgo(0);
        String since = now();
        assertThat(blockers(t, branch, since)).isEmpty();

        UUID owing = RetailTestSupport.id(cb.sale(branch, "credit", product, "1", today));
        assertThat(blockers(t, branch, since)).containsExactly("credit_sales_owing");
        ResponseEntity<JsonNode> written = api.postKeyed(
                "/sales/" + owing + "/write-off",
                Map.of("reason", "Test buyer left town"),
                ADMIN + ",retail.credit.write_off,retail.credit.manage",
                "*",
                UUID.randomUUID().toString());
        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(blockers(t, branch, since)).isEmpty();

        UUID paidInPart = RetailTestSupport.id(cb.sale(branch, "credit", product, "1", today));
        assertThat(cb.pay(paidInPart, 500, "cash", today).getStatusCode().is2xxSuccessful())
                .isTrue();
        assertThat(blockers(t, branch, since)).containsExactlyInAnyOrder("credit_sales_owing", "credit_payments");
        assertThat(cb.sale(branch, "cash", product, "1", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(blockers(t, branch, since)).containsExactlyInAnyOrder("credit_sales_owing", "credit_payments");
        Map<String, Object> banking = new LinkedHashMap<>();
        banking.put("branch_id", branch);
        banking.put("business_date", today.toString());
        banking.put("amount_minor", 1_500);
        ResponseEntity<JsonNode> banked = cb.post("/bankings", banking, CashbookTestSupport.ALL);
        assertThat(banked.getStatusCode()).as("%s", banked.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID cash = RetailTestSupport.id(api.sell(branch, "cash", product, "1"));
        ResponseEntity<JsonNode> refunded = api.postKeyed(
                "/sales/" + cash + "/returns",
                refund(api, cash, branch),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(refunded.getStatusCode()).as("%s", refunded.getBody()).isEqualTo(HttpStatus.CREATED);

        assertThat(blockers(t, branch, since))
                .containsExactlyInAnyOrder("credit_sales_owing", "credit_payments", "bankings", "refunds");
        // Only what happened since the role was set counts; a credit sale owing counts whenever it was made.
        assertThat(blockers(t, branch, now())).containsExactly("credit_sales_owing");
        assertThat(blockers(t, t.headOffice(), since)).isEmpty();
    }

    static Map<String, Object> refund(RetailTestSupport api, UUID sale, UUID branch) {
        String saleLine = api.get("/sales/" + sale, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", saleLine);
        line.put("qty", "1");
        line.put("reason", "defective");
        line.put("disposition", "restock");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line));
        body.put("refund_method", "cash");
        body.put("refund_branch_id", branch);
        return body;
    }

    static String now() {
        return TestDatabase.owner()
                .sql("SELECT clock_timestamp()::text")
                .query(String.class)
                .single();
    }

    static List<String> blockers(TestDatabase.Fixture t, UUID branch, String since) {
        String joined = TestDatabase.owner()
                .sql("SELECT array_to_string(retail_store_fold_blockers(?, ?, ?::timestamptz), ',')")
                .params(t.tenantId(), branch, since)
                .query(String.class)
                .single();
        return joined.isEmpty() ? List.of() : List.of(joined.split(","));
    }

    /**
     * The chosen meaning, pinned: a branch as V115 leaves a folded store (role row both, the Store flag set, branch roles on)
     * reads as a store and refuses a payment on a credit sale, a refund paid out of its till and a banking with ADR-048's 422
     * {@code branch_does_not_sell}, while it still takes stock in.
     */
    @Test
    void aFoldedStoreTakesNoMoneyAtItsTill() {
        TestDatabase.Fixture t = TestDatabase.tenant("fold-till", false, true);
        CashbookTestSupport cb = new CashbookTestSupport(http, t);
        RetailTestSupport api = cb.api;
        UUID branch = t.secondBranch();
        UUID product = api.product("FOLD-CABLE", 1_000, 1_500);
        api.stockUp(branch, product, "20");
        capability(api, "branch_roles");
        capability(api, "returns");
        LocalDate today = CashbookTestSupport.daysAgo(0);
        // Made while the branch still sold: a credit sale, and a cash sale to refund.
        UUID credit = RetailTestSupport.id(cb.sale(branch, "credit", product, "1", today));
        UUID cash = RetailTestSupport.id(api.sell(branch, "cash", product, "1"));
        String saleLine = api.get("/sales/" + cash, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString();

        // What V115 leaves for a folded store, as aMixedBusinessMigratesInOneRun checks against the migration itself.
        TestDatabase.owner()
                .sql("INSERT INTO retail_branch_roles (tenant_id, branch_id, role) VALUES (?, ?, ?)")
                .params(t.tenantId(), branch, FOLDED_ROLE)
                .update();
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = ?, stocks_goods = ? WHERE id = ?")
                .params(FOLDED_FLAGS.get("sells"), FOLDED_FLAGS.get("stocks"), branch)
                .update();

        for (JsonNode i : api.get("/branch-roles", ADMIN).getBody().get("items")) {
            if (i.get("branch_id").asString().equals(branch.toString())) {
                assertThat(i.get("role").asString()).isEqualTo("store");
                assertThat(i.get("can_sell").asBoolean()).isFalse();
            }
        }
        assertDoesNotSell(cb.pay(credit, 500, "cash", today));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", saleLine);
        line.put("qty", "1");
        line.put("reason", "defective");
        line.put("disposition", "restock");
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("lines", List.of(line));
        refund.put("refund_method", "cash");
        refund.put("refund_branch_id", branch);
        assertDoesNotSell(api.postKeyed(
                "/sales/" + cash + "/returns",
                refund,
                RETURNER,
                "*",
                UUID.randomUUID().toString()));
        Map<String, Object> banking = new LinkedHashMap<>();
        banking.put("branch_id", branch);
        banking.put("business_date", today.toString());
        banking.put("amount_minor", 1_500);
        assertDoesNotSell(cb.post("/bankings", banking, CashbookTestSupport.ALL));
        // It still keeps stock: a stock-take into it works.
        api.stockUp(branch, product, "25");
    }

    static void assertDoesNotSell(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("branch_does_not_sell");
    }

    static void capability(RetailTestSupport api, String key) {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of(key, true),
                                "core.settings.manage",
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    static UUID tenant(JdbcClient owner, String slug, String settings, boolean retail) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, ?, 'Test Tenant Fold', '" + PLAN + "')")
                .params(id, slug)
                .update();
        if (settings != null) {
            owner.sql("INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, ?::jsonb)")
                    .params(UUID.randomUUID(), id, settings)
                    .update();
        }
        if (retail) {
            owner.sql("INSERT INTO tenant_modules (tenant_id, module_key) VALUES (?, 'retail')")
                    .param(id)
                    .update();
        }
        return id;
    }

    static UUID branch(JdbcClient owner, UUID tenant, String code, boolean stocks, boolean sells, String role) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO branches (id, tenant_id, code, name, stocks_goods, sells_goods)"
                        + " VALUES (?, ?, ?, 'Test Branch ' || ?, ?, ?)")
                .params(id, tenant, code, code, stocks, sells)
                .update();
        if (role != null) {
            owner.sql("INSERT INTO retail_branch_roles (tenant_id, branch_id, role) VALUES (?, ?, ?)")
                    .params(tenant, id, role)
                    .update();
        }
        return id;
    }

    /** A completed credit sale made at the branch with nothing paid, as the previous release would have recorded it. */
    static void creditSaleOwing(JdbcClient owner, UUID tenant, UUID branch) {
        owner.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                                                  currency, total_minor, cost_total_minor, paid_minor, status, created_by)
                        VALUES (?, ?, ?, 'S-STO-000001', current_date, 'credit', 'Test Buyer 01', 'UGX', 1500, 1000, 0,
                                'completed', ?)
                        """)
                .params(UUID.randomUUID(), tenant, branch, UUID.randomUUID())
                .update();
    }

    static Map<String, Object> flags(JdbcClient owner, UUID branch) {
        return owner.sql("SELECT sells_goods, stocks_goods, version FROM branches WHERE id = ?")
                .param(branch)
                .query((rs, n) -> Map.<String, Object>of(
                        "sells", rs.getBoolean("sells_goods"),
                        "stocks", rs.getBoolean("stocks_goods"),
                        "version", rs.getInt("version")))
                .single();
    }

    static String role(JdbcClient owner, UUID branch) {
        return owner.sql("SELECT role FROM retail_branch_roles WHERE branch_id = ?")
                .param(branch)
                .query(String.class)
                .single();
    }

    static String reason(JdbcClient owner, UUID branch) {
        return owner.sql(
                        "SELECT data ->> 'reason' FROM audit_log WHERE action = 'retail.branch_role.changed' AND entity_id = ?")
                .param(branch)
                .query(String.class)
                .single();
    }

    static String blockersOf(JdbcClient owner, UUID branch) {
        return owner.sql(
                        "SELECT data -> 'blockers' FROM audit_log WHERE action = 'retail.branch_role.changed' AND entity_id = ?")
                .param(branch)
                .query(String.class)
                .single()
                .replace(" ", "");
    }

    static long count(JdbcClient owner, String sql) {
        return owner.sql(sql).query(Long.class).single();
    }

    /** Each audit row of the action as entity:before>after, checking it was the system's and cites ADR-104. */
    static List<String> audited(JdbcClient owner, String action) {
        return owner.sql("""
                        SELECT entity_id::text || ':' || coalesce(data #>> '{before,sells_goods}', data #>> '{before,role}')
                               || '>' || coalesce(data #>> '{after,sells_goods}', data #>> '{after,role}')
                          FROM audit_log
                         WHERE action = ? AND actor_kind = 'system' AND data ->> 'reason' LIKE '%(ADR-104)'
                        """).param(action).query(String.class).list();
    }
}
