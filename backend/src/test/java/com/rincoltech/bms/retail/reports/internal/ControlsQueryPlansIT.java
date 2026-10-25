package com.rincoltech.bms.retail.reports.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;

/**
 * The query plans of the Controls page on a large tenant (issue #357, ADR-087). Off by default; run with
 * {@code mvn verify -Dit.test=ControlsQueryPlansIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dbms.controls.plans=true}.
 * A small set of real rows is made through the API (sales, voids, a changed price, a return, a credit write-off, a voided restock
 * and a corrected quantity), then copied, as the superuser with triggers off, into ten shops, thirty sellers and two years, about
 * a million sales. Every statement of {@link ControlsRepository} is captured as it would run and explained with ANALYZE as the
 * application role under row-level security, with JIT off as the repository sets it. The plans are written to {@code target/controls-query-plans.md}. Fabricated data.
 */
@EnabledIfSystemProperty(named = "bms.controls.plans", matches = "true")
class ControlsQueryPlansIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String SELLER = ADMIN + ",retail.price.override";
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read";
    static final String WRITE_OFF = ADMIN + ",retail.credit.manage,retail.credit.write_off,core.approvals.read";
    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");
    /** Copies of each base row: the base set has 30 sales, so about 30 x COPIES sales. */
    static final int COPIES = Integer.getInteger("bms.controls.copies", 34_000);

    static final int SHOPS = 10;
    static final int SELLERS = 30;
    static final int DAYS = 730;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    /** How long V101's two index builds took on the full table. */
    String builds = "";

    UUID product;
    UUID product2;

    static DataSource superuser() {
        return new DriverManagerDataSource(TestDatabase.url(), "postgres", "test-superuser-password");
    }

    void capability(String key) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    UUID sale(UUID branch, String method, Long overridden) {
        Map<String, Object> l1 = new LinkedHashMap<>();
        l1.put("product_id", product);
        l1.put("qty", "2");
        if (overridden != null) {
            l1.put("unit_price_minor", overridden);
            l1.put("price_override_reason", "Test agreed price");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", List.of(l1, Map.of("product_id", product2, "qty", "1")));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, SELLER, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    void ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
    }

    void seedBase() {
        t = TestDatabase.tenant("retail-ctl-plans", false, true);
        api = new RetailTestSupport(http, t);
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Test Admin"
                                + " One', ?, 'active')")
                .params(api.userId(), t.tenantId(), "admin-" + api.userId() + "@example.test")
                .update();
        product = api.product("PL-1", 1_000, 1_500);
        product2 = api.product("PL-2", 400, 700);
        for (UUID b : List.of(t.headOffice(), t.secondBranch())) {
            api.importedBalance(b, product, "10000");
            api.importedBalance(b, product2, "10000");
        }
        capability("price_lists");
        capability("returns");
        for (UUID b : List.of(t.headOffice(), t.secondBranch())) {
            List<UUID> sales = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                sales.add(sale(b, "cash", i == 3 ? 1_200L : null));
            }
            for (int i = 0; i < 3; i++) {
                sales.add(sale(b, "credit", null));
            }
            ok(api.post("/sales/" + sales.get(0) + "/void", Map.of("reason", "Test wrong item"), ADMIN));
            UUID line = UUID.fromString(api.get("/sales/" + sales.get(1), ADMIN)
                    .getBody()
                    .get("lines")
                    .get(0)
                    .get("id")
                    .asString());
            Map<String, Object> returned = new LinkedHashMap<>();
            returned.put("sale_line_id", line);
            returned.put("qty", "1");
            returned.put("reason", "defective");
            returned.put("disposition", "restock");
            ok(api.postKeyed(
                    "/sales/" + sales.get(1) + "/returns",
                    Map.of("lines", List.of(returned), "refund_method", "cash"),
                    RETURNER,
                    "*",
                    UUID.randomUUID().toString()));
            ok(api.postKeyed(
                    "/sales/" + sales.get(12) + "/write-off",
                    Map.of("reason", "Test buyer moved away"),
                    WRITE_OFF,
                    "*",
                    UUID.randomUUID().toString()));
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 1_000);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", "5")));
        Map<String, Object> restock = new LinkedHashMap<>();
        restock.put("purchased_on", LocalDate.now(KAMPALA).toString());
        restock.put("payment_method", "cash");
        restock.put("lines", List.of(line));
        UUID purchase = RetailTestSupport.id(api.postKeyed(
                "/purchases", restock, ADMIN, "*", UUID.randomUUID().toString()));
        ok(api.call(
                HttpMethod.POST,
                "/purchases/" + purchase + "/void",
                Map.of("reason", "Test wrong supplier"),
                ADMIN,
                "*",
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
    }

    /** INSERT INTO table SELECT every column, with the expressions of {@code over} in place of those columns. */
    static String copy(JdbcClient su, String table, Map<String, String> over, String from) {
        List<String> cols = su.sql(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND"
                                + " table_name = ? AND is_generated = 'NEVER' ORDER BY ordinal_position")
                .param(table)
                .query(String.class)
                .list();
        String select = cols.stream().map(c -> over.getOrDefault(c, "x." + c)).collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + String.join(", ", cols) + ") SELECT " + select + " " + from;
    }

    void multiply() throws SQLException {
        try (Connection c = superuser().getConnection()) {
            c.setAutoCommit(false);
            JdbcClient su = JdbcClient.create(new SingleConnectionDataSource(c, true));
            su.sql("SET session_replication_role = replica").update();
            UUID tenant = t.tenantId();
            // Ten shops and thirty sellers, a few of them deactivated.
            su.sql(
                            "CREATE TEMP TABLE shop AS SELECT g AS n, CASE g WHEN 0 THEN CAST(? AS uuid) WHEN 1 THEN CAST(? AS uuid)"
                                    + " ELSE gen_random_uuid() END AS id FROM generate_series(0, ?) g")
                    .params(t.headOffice(), t.secondBranch(), SHOPS - 1)
                    .update();
            su.sql(
                            "INSERT INTO branches (id, tenant_id, code, name) SELECT id, ?, 'PL' || n, 'Test Shop ' || n FROM shop"
                                    + " WHERE n >= 2")
                    .param(tenant)
                    .update();
            su.sql("CREATE TEMP TABLE seller AS SELECT g AS n, gen_random_uuid() AS id FROM generate_series(0, ?) g")
                    .param(SELLERS - 1)
                    .update();
            su.sql(
                            "INSERT INTO users (id, tenant_id, kind, full_name, email, status) SELECT id, ?, 'staff', 'Test Seller '"
                                    + " || n, 'seller' || n || '-' || id || '@example.test', CASE WHEN n % 10 = 9 THEN"
                                    + " 'deactivated' ELSE 'active' END FROM seller")
                    .param(tenant)
                    .update();
            // Each base sale, COPIES times: shop k % 10, seller k % 30, k % 730 days back.
            su.sql("CREATE TEMP TABLE m AS SELECT s.id AS old_id, gen_random_uuid() AS new_id, g AS k,"
                            + " make_interval(days => g % ?) AS back, (SELECT id FROM shop WHERE n = g % ?) AS branch_id,"
                            + " (SELECT id FROM seller WHERE n = (g * 7) % ?) AS user_id FROM retail_sales s,"
                            + " generate_series(1, ?) g WHERE s.tenant_id = ?")
                    .params(DAYS, SHOPS, SELLERS, COPIES, tenant)
                    .update();
            su.sql("CREATE INDEX ON m (old_id)").update();
            Map<String, String> sale = new LinkedHashMap<>();
            sale.put("id", "m.new_id");
            sale.put("sale_no", "x.sale_no || '-' || m.k");
            sale.put("branch_id", "m.branch_id");
            sale.put("created_by", "m.user_id");
            sale.put("sale_date", "x.sale_date - m.back");
            sale.put("created_at", "x.created_at - m.back");
            sale.put("updated_at", "x.updated_at - m.back");
            sale.put("voided_at", "x.voided_at - m.back");
            put(su, "retail_sales", sale);
            su.sql(copy(su, "retail_sales", sale, "FROM retail_sales x JOIN m ON m.old_id = x.id"))
                    .update();
            Map<String, String> line = new LinkedHashMap<>();
            line.put("id", "gen_random_uuid()");
            line.put("sale_id", "m.new_id");
            line.put("created_at", "x.created_at - m.back");
            put(su, "retail_sale_lines", line);
            su.sql(copy(su, "retail_sale_lines", line, "FROM retail_sale_lines x JOIN m ON m.old_id = x.sale_id"))
                    .update();
            Map<String, String> move = new LinkedHashMap<>();
            move.put("id", "gen_random_uuid()");
            move.put("source_id", "m.new_id");
            move.put("branch_id", "m.branch_id");
            move.put("reverses_movement_id", "NULL");
            move.put("created_at", "x.created_at - m.back");
            put(su, "retail_stock_movements", move);
            su.sql(copy(
                            su,
                            "retail_stock_movements",
                            move,
                            "FROM retail_stock_movements x JOIN m ON m.old_id = x.source_id"))
                    .update();
            Map<String, String> ret = new LinkedHashMap<>();
            ret.put("id", "gen_random_uuid()");
            ret.put("sale_id", "m.new_id");
            ret.put("branch_id", "m.branch_id");
            ret.put("refund_branch_id", "m.branch_id");
            ret.put("credit_note_no", "x.credit_note_no || '-' || m.k");
            su.sql("CREATE TEMP SEQUENCE return_seq START 1000").update();
            ret.put("branch_seq", "nextval('return_seq')");
            ret.put("created_by", "(SELECT id FROM seller WHERE n = (m.k * 11) % " + SELLERS + ")");
            ret.put("returned_on", "x.returned_on - m.back");
            ret.put("created_at", "x.created_at - m.back");
            put(su, "retail_sale_returns", ret);
            su.sql(copy(su, "retail_sale_returns", ret, "FROM retail_sale_returns x JOIN m ON m.old_id = x.sale_id"))
                    .update();
            Map<String, String> wo = new LinkedHashMap<>();
            wo.put("id", "gen_random_uuid()");
            wo.put("sale_id", "m.new_id");
            wo.put("branch_id", "m.branch_id");
            wo.put("written_off_on", "x.written_off_on - m.back");
            wo.put("created_at", "x.created_at - m.back");
            wo.put("journal_entry_id", "gen_random_uuid()");
            put(su, "retail_sale_writeoffs", wo);
            su.sql(copy(su, "retail_sale_writeoffs", wo, "FROM retail_sale_writeoffs x JOIN m ON m.old_id = x.sale_id"))
                    .update();
            // Each copied write-off gets its own journal entry, a copy of a real write-off's entry, so the reversal
            // check
            // (NOT EXISTS on journal_entries.reverses_entry_id) is measured as it runs on a real tenant.
            Map<String, String> woEntry = new LinkedHashMap<>();
            woEntry.put("id", "w.journal_entry_id");
            woEntry.put("entry_no", "left('W' || md5(w.id::text), 20)");
            woEntry.put("entry_date", "w.written_off_on");
            woEntry.put("branch_id", "w.branch_id");
            woEntry.put("source_id", "w.id");
            woEntry.put("reverses_entry_id", "NULL");
            woEntry.put("idempotency_key", "NULL");
            put(su, "journal_entries", woEntry);
            UUID woBase = su.sql(
                            "SELECT b.journal_entry_id FROM retail_sale_writeoffs b JOIN journal_entries e ON e.id ="
                                    + " b.journal_entry_id WHERE b.tenant_id = ? ORDER BY b.created_at LIMIT 1")
                    .param(tenant)
                    .query(UUID.class)
                    .single();
            su.sql(copy(
                            su,
                            "journal_entries",
                            woEntry,
                            "FROM retail_sale_writeoffs w JOIN journal_entries x ON x.id = '" + woBase + "' WHERE"
                                    + " w.tenant_id = '" + tenant + "' AND w.journal_entry_id NOT IN (SELECT id FROM"
                                    + " journal_entries WHERE tenant_id = '" + tenant + "')"))
                    .update();
            // And the tenant's books as a whole: the base entries copied to about a million, one in a hundred of them
            // reversed, so the reversal check runs against a journal of real size.
            long base = su.sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND entry_no NOT LIKE 'W%'")
                    .param(tenant)
                    .query(Long.class)
                    .single();
            Map<String, String> filler = new LinkedHashMap<>();
            filler.put("id", "gen_random_uuid()");
            filler.put("entry_no", "left('F' || md5(x.id::text || '-' || g), 20)");
            filler.put("reverses_entry_id", "NULL");
            filler.put("idempotency_key", "NULL");
            put(su, "journal_entries", filler);
            su.sql(copy(
                            su,
                            "journal_entries",
                            filler,
                            "FROM journal_entries x, generate_series(1, " + Math.max(1, 1_000_000 / Math.max(1, base))
                                    + ") g WHERE x.tenant_id = '" + tenant + "' AND x.entry_no NOT LIKE 'W%'"))
                    .update();
            su.sql("INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id, reference,"
                            + " source_module, source_type, reverses_entry_id) SELECT gen_random_uuid(), e.tenant_id,"
                            + " e.branch_id, left('R' || md5(e.id::text), 20), e.entry_date, e.period_id, e.reference,"
                            + " e.source_module, e.source_type, e.id FROM journal_entries e WHERE e.tenant_id = ? AND"
                            + " e.entry_no LIKE 'F%' AND e.reverses_entry_id IS NULL AND random() < 0.01")
                    .param(tenant)
                    .update();
            // Restocks voided and quantities corrected: a few thousand each, over the same shops and days.
            su.sql(
                            "CREATE TEMP TABLE pm AS SELECT p.id AS old_id, gen_random_uuid() AS new_id, g AS k, make_interval(days"
                                    + " => g % ?) AS back, (SELECT id FROM shop WHERE n = g % ?) AS branch_id FROM"
                                    + " retail_purchases p, generate_series(1, 3000) g WHERE p.tenant_id = ? AND EXISTS (SELECT 1"
                                    + " FROM retail_purchase_voids v WHERE v.purchase_id = p.id)")
                    .params(DAYS, SHOPS, tenant)
                    .update();
            Map<String, String> pur = new LinkedHashMap<>();
            pur.put("id", "pm.new_id");
            pur.put("purchase_no", "x.purchase_no || '-' || pm.k");
            put(su, "retail_purchases", pur);
            su.sql(copy(su, "retail_purchases", pur, "FROM retail_purchases x JOIN pm ON pm.old_id = x.id"))
                    .update();
            Map<String, String> pv = new LinkedHashMap<>();
            pv.put("id", "gen_random_uuid()");
            pv.put("purchase_id", "pm.new_id");
            pv.put("voided_on", "x.voided_on - pm.back");
            pv.put("created_at", "x.created_at - pm.back");
            put(su, "retail_purchase_voids", pv);
            su.sql(copy(
                            su,
                            "retail_purchase_voids",
                            pv,
                            "FROM retail_purchase_voids x JOIN pm ON pm.old_id =" + " x.purchase_id"))
                    .update();
            Map<String, String> pmv = new LinkedHashMap<>();
            pmv.put("id", "gen_random_uuid()");
            pmv.put("source_id", "pm.new_id");
            pmv.put("branch_id", "pm.branch_id");
            pmv.put("reverses_movement_id", "NULL");
            put(su, "retail_stock_movements", pmv);
            su.sql(copy(
                            su,
                            "retail_stock_movements",
                            pmv,
                            "FROM retail_stock_movements x JOIN pm ON pm.old_id = x.source_id"))
                    .update();
            c.commit();
            su.sql("SET session_replication_role = origin").update();
            c.setAutoCommit(true);
            su.sql("VACUUM (ANALYZE, PARALLEL 0)").update();
            // V101's two builds again on the full table, timed, as the migration runs them: each reads the whole table
            // under
            // a SHARE lock that holds sale inserts back until the commit.
            long rows = su.sql("SELECT count(*) FROM retail_sale_lines")
                    .query(Long.class)
                    .single();
            StringBuilder b = new StringBuilder();
            for (String index : List.of(
                    "retail_sale_lines_price_changed ON retail_sale_lines (tenant_id, sale_id) WHERE price_source ="
                            + " 'override'",
                    "retail_sale_lines_below_cost ON retail_sale_lines (tenant_id, sale_id) WHERE line_total_minor <"
                            + " line_cost_minor")) {
                String name = index.substring(0, index.indexOf(' '));
                su.sql("DROP INDEX " + name).update();
                long started = System.nanoTime();
                su.sql("CREATE INDEX " + index).update();
                b.append(b.isEmpty() ? "" : ", ")
                        .append('`')
                        .append(name)
                        .append("` ")
                        .append((System.nanoTime() - started) / 1_000_000)
                        .append(" ms");
            }
            builds = "V101's builds on " + rows + " rows of retail_sale_lines: " + b + ".";
        }
    }

    /** Drops the expressions of columns the table does not have, so one map shape serves every copy. */
    static void put(JdbcClient su, String table, Map<String, String> over) {
        over.keySet()
                .removeIf(col -> su.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema ="
                                        + " 'public' AND table_name = ? AND column_name = ?")
                                .params(table, col)
                                .query(Long.class)
                                .single()
                        == 0);
    }

    /** One captured statement and its parameters. */
    record Captured(String label, String sql, Map<String, Object> params) {}

    /** A JdbcClient that records the statement and answers nothing, so a repository method shows what it would run. */
    static JdbcClient recorder(List<Captured> out, String[] label) {
        return (JdbcClient) Proxy.newProxyInstance(
                JdbcClient.class.getClassLoader(), new Class<?>[] {JdbcClient.class}, (p, m, a) -> {
                    if (!m.getName().equals("sql")) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    String sql = (String) a[0];
                    if (sql.startsWith("SET LOCAL")) {
                        // A setting, not a statement to explain: the session that explains applies it (below).
                        return Proxy.newProxyInstance(
                                JdbcClient.class.getClassLoader(),
                                new Class<?>[] {JdbcClient.StatementSpec.class},
                                (p4, m4, a4) -> 0);
                    }
                    Map<String, Object> params = new LinkedHashMap<>();
                    Object[] spec = new Object[1];
                    spec[0] = Proxy.newProxyInstance(
                            JdbcClient.class.getClassLoader(),
                            new Class<?>[] {JdbcClient.StatementSpec.class},
                            (p2, m2, a2) -> {
                                if (m2.getName().equals("params") && a2[0] instanceof Map<?, ?> map) {
                                    map.forEach((k, v) -> params.put((String) k, v));
                                    return spec[0];
                                }
                                if (m2.getName().equals("query")) {
                                    out.add(new Captured(label[0], sql, params));
                                    return Proxy.newProxyInstance(
                                            JdbcClient.class.getClassLoader(),
                                            new Class<?>[] {JdbcClient.MappedQuerySpec.class},
                                            (p3, m3, a3) -> m3.getName().equals("list") ? List.of() : null);
                                }
                                throw new UnsupportedOperationException(m2.getName());
                            });
                    return spec[0];
                });
    }

    @Test
    void explainEveryStatementOnALargeTenant() throws Exception {
        seedBase();
        multiply();
        JdbcClient owner = TestDatabase.owner();
        long sales = owner.sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        long lines = owner.sql("SELECT count(*) FROM retail_sale_lines WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        long journal = owner.sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        long moves = owner.sql("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        String counts = owner.sql(
                        "SELECT (SELECT count(*) FROM retail_sales WHERE tenant_id = :t AND voided_at IS NOT NULL)"
                                + " || ' voided, ' || (SELECT count(*) FROM retail_sale_returns WHERE tenant_id = :t) || ' returns, '"
                                + " || (SELECT count(*) FROM retail_sale_writeoffs WHERE tenant_id = :t) || ' write-offs, ' ||"
                                + " (SELECT count(*) FROM retail_purchase_voids WHERE tenant_id = :t) || ' restocks voided'")
                .param("t", t.tenantId())
                .query(String.class)
                .single();

        List<Captured> captured = new ArrayList<>();
        String[] label = {""};
        ControlsRepository repo = new ControlsRepository(recorder(captured, label));
        LocalDate today = LocalDate.now(KAMPALA);
        UUID shop = t.headOffice();
        UUID seller = owner.sql("SELECT created_by FROM retail_sales WHERE tenant_id = ? AND branch_id = ? LIMIT 1")
                .params(t.tenantId(), shop)
                .query(UUID.class)
                .single();
        ControlsRepository.Window month =
                new ControlsRepository.Window(today.minusDays(29), today, KAMPALA, null, 10, null, null, null);
        ControlsRepository.Window monthShop =
                new ControlsRepository.Window(today.minusDays(29), today, KAMPALA, List.of(shop), 10, null, null, null);
        ControlsRepository.Window yearShopPerson = new ControlsRepository.Window(
                today.minusDays(365), today, KAMPALA, List.of(shop), 10, seller, null, null);
        ControlsRepository.Window year =
                new ControlsRepository.Window(today.minusDays(365), today, KAMPALA, null, 10, null, null, null);
        List<String> all = ControlsRepository.METRICS;

        label[0] = "Sales entered, a month, every shop (summary)";
        repo.sales(month);
        label[0] = "Every card, a month, every shop (summary)";
        repo.counts(all, month);
        label[0] = "Every card, a month, one shop (summary)";
        repo.counts(all, monthShop);
        label[0] = "Every card, a year, every shop (summary, the widest window)";
        repo.counts(all, year);
        for (String metric : List.of("voided_sales", "credit_writeoffs", "price_overrides", "restock_voids")) {
            label[0] = "Groups of " + metric + " by person, a month, every shop";
            repo.groups(metric, "user", month, 0, ControlsService.GROUP_PAGE + 1);
        }
        label[0] = "Groups of returns by day, a year, every shop";
        repo.groups("returns", "day", year, 0, ControlsService.GROUP_PAGE + 1);
        label[0] = "Groups of credit_writeoffs by reason, a year, one shop";
        repo.groups(
                "credit_writeoffs",
                "reason",
                new ControlsRepository.Window(
                        today.minusDays(365), today, KAMPALA, List.of(shop), 10, null, null, null),
                0,
                101);
        label[0] = "Records of voided_sales, a year, one shop and person";
        repo.events("voided_sales", yearShopPerson, 0, ControlsService.RECORD_PAGE + 1);
        label[0] = "Records of credit_writeoffs, a month, every shop";
        repo.events("credit_writeoffs", month, 0, ControlsService.RECORD_PAGE + 1);
        label[0] = "Records of quantity_corrections, a month, every shop";
        repo.events("quantity_corrections", month, 0, ControlsService.RECORD_PAGE + 1);
        label[0] = "Patterns: people, a month, every shop";
        repo.personCells(List.of("voided_sales", "returns", "price_overrides", "below_cost"), month);
        label[0] = "Patterns: sales per shop, a month, every shop";
        repo.salesByBranch(month);
        label[0] = "Patterns: items, a month, every shop";
        repo.itemCells(true, month);

        StringBuilder md = new StringBuilder("# Controls query plans on a large tenant (issue #357, ADR-087)\n\n");
        md.append(
                        "Generated by `ControlsQueryPlansIT` (`-Dbms.controls.plans=true`) on PostgreSQL 16 in Testcontainers, as the")
                .append(
                        " application role `bms_app` with forced row-level security, `app.tenant_id` set and JIT off for the"
                                + " transaction, as the repository sets it. One tenant: ")
                .append(SHOPS)
                .append(" shops, ")
                .append(SELLERS + 1)
                .append(" people, ")
                .append(DAYS)
                .append(" days, ")
                .append(sales)
                .append(" sales, ")
                .append(lines)
                .append(" sale lines, ")
                .append(moves)
                .append(" stock movements, ")
                .append(journal)
                .append(" journal entries; ")
                .append(counts)
                .append(". Today is ")
                .append(today)
                .append(" (Africa/Kampala). Fabricated data. ")
                .append(builds)
                .append("\n\n");
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            NamedParameterJdbcTemplate app = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(c, true));
            app.queryForList(
                    "SELECT set_config('app.tenant_id', :t, true)",
                    Map.of("t", t.tenantId().toString()));
            // As ControlsRepository.withoutJit does in the API's transaction.
            app.queryForList("SELECT set_config('jit', 'off', true)", Map.of());
            for (Captured q : captured) {
                // Warm once, then explain, so the plan shows the cached case a page sees after its first load.
                app.queryForList(q.sql(), q.params());
                List<String> plan = app.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + q.sql(), q.params(), String.class);
                md.append("## ").append(q.label()).append("\n\n```\n");
                plan.forEach(row -> md.append(row).append('\n'));
                md.append("```\n\n");
            }
            c.rollback();
        }
        Path file = Path.of("target", "controls-query-plans.md");
        try {
            Files.writeString(file, md.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(captured).isNotEmpty();
    }
}
