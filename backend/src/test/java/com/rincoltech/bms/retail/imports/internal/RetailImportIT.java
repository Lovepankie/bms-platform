package com.rincoltech.bms.retail.imports.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The golden test of the retail import (#55; FR-RET-12; ADR-020 decision 9; data dictionary rules 1
 * to 6) on real PostgreSQL, the importer connected as bms_app under row-level security. The fixture
 * {@code fixtures/retail/import-sample/} is fabricated; the expected figures below were worked out
 * from it independently of the importer.
 */
class RetailImportIT extends IntegrationTest {

    static final Path SAMPLE = Path.of("../fixtures/retail/import-sample");

    /** Opening journal per branch: positive source balances at the product master's cost. */
    static final Map<String, Long> OPENING = Map.of("KLA", 2_464_600L, "ENT", 4_568_150L, "JJA", 1_569_950L);

    /** The one negative source balance: JJA TP-007 at -3, cost 9,000. */
    static final long NEGATIVE_AT_COST = -27_000L;

    static final String TABLES_SQL = """
            SELECT (SELECT count(*) FROM retail_products WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_price_history WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_stock_movements WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_sales WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_sale_lines WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_purchases WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_purchase_lines WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_usage_reports WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_usage_lines WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_suppliers WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_customers WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_categories WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_units WHERE tenant_id = :t)
                 + (SELECT count(*) FROM retail_import_refs WHERE tenant_id = :t)
                 + (SELECT count(*) FROM branches WHERE tenant_id = :t)
                 + (SELECT count(*) FROM journal_entries WHERE tenant_id = :t)
                 + (SELECT count(*) FROM journal_lines WHERE tenant_id = :t)
            """;

    @Autowired
    RetailImporter importer;

    @Autowired
    TestRestTemplate http;

    @Autowired
    BusinessClock clock;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    RetailTestSupport api;
    JdbcClient owner;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-imp", false, true);
        other = TestDatabase.tenant("retail-oth", false, true);
        api = new RetailTestSupport(http, t);
        owner = TestDatabase.owner();
    }

    @Test
    void dryRunReportsTheSameAndWritesNothing() {
        long before = rows(t.tenantId());

        ImportReport dry = importer.run(t.slug(), SAMPLE, true);

        assertThat(dry.failed()).as(dry.render()).isFalse();
        assertThat(dry.checksumLine()).contains("match=yes");
        assertThat(rows(t.tenantId())).isEqualTo(before);
        assertThat(owner.sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'retail.import.%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();

        ImportReport real = importer.run(t.slug(), SAMPLE, false);
        assertThat(real.render().replace("committed, one transaction per file", "x"))
                .isEqualTo(dry.render().replace("dry run, every write rolled back", "x"));
    }

    @Test
    void goldenImportThenReRunAddsNothing() throws IOException {
        RetailTestSupport otherApi = new RetailTestSupport(http, other);
        UUID otherProduct = otherApi.product("TP-001", 4_000, 5_000);
        otherApi.stockUp(other.headOffice(), otherProduct, "7");
        long otherRows = rows(other.tenantId());

        ImportReport report = importer.run(t.slug(), SAMPLE, false);
        String text = report.render();

        assertThat(report.failed()).as(text).isFalse();
        // read, written, existing, skipped
        assertThat(report.counts("branches.jsonl")).containsExactly(3, 3, 0, 0);
        assertThat(report.counts("products.jsonl")).containsExactly(21, 20, 0, 1);
        assertThat(report.counts("purchases.jsonl")).containsExactly(34, 34, 0, 0);
        assertThat(report.counts("sales.jsonl")).containsExactly(221, 220, 0, 1);
        assertThat(report.counts("usage.jsonl")).containsExactly(15, 15, 0, 0);
        assertThat(report.counts("balances.jsonl")).containsExactly(60, 60, 0, 0);
        assertThat(report.anomalies())
                .anyMatch(a -> a.startsWith("products.jsonl:6: duplicate product code tp-001 (same as line 1"))
                .anyMatch(a -> a.startsWith("sales.jsonl:221: unknown product code TP-404"));
        assertThat(text)
                .contains("JJA TP-007 -3.000")
                .contains("credit sales imported unpaid")
                .contains("match=yes");

        // Balances equal the source's current quantities exactly.
        for (String line : Files.readAllLines(SAMPLE.resolve("balances.jsonl"))) {
            JsonNode b = JsonMapper.builder().build().readTree(line);
            BigDecimal balance = owner.sql("""
                            SELECT s.qty FROM retail_stock_balances s
                              JOIN branches br ON br.id = s.branch_id JOIN retail_products p ON p.id = s.product_id
                             WHERE s.tenant_id = ? AND br.code = ? AND p.code = ?
                            """)
                    .params(
                            t.tenantId(),
                            b.get("branch").asString(),
                            b.get("product_code").asString())
                    .query(BigDecimal.class)
                    .single();
            assertThat(balance).isEqualByComparingTo(b.get("qty").asString());
        }

        // History is flagged and absent from the ledger; only the three opening journals exist.
        assertThat(count("SELECT count(*) FROM retail_sales WHERE tenant_id = ? AND historical"))
                .isEqualTo(220);
        assertThat(count("SELECT count(*) FROM retail_purchases WHERE tenant_id = ? AND historical"))
                .isEqualTo(32);
        assertThat(count("SELECT count(*) FROM retail_usage_reports WHERE tenant_id = ? AND historical"))
                .isEqualTo(15);
        assertThat(count("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND NOT historical"))
                .isZero();
        assertThat(count("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'legacy_balance'"))
                .isEqualTo(60);
        assertThat(count("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'adjustment'"))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'return'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM retail_sales WHERE tenant_id = ? AND payment_method = 'credit'"))
                .isEqualTo(20);
        assertThat(count("""
                        SELECT count(*) FROM retail_sales WHERE tenant_id = ?
                           AND (sale_entry_id IS NOT NULL OR cost_entry_id IS NOT NULL)""")).isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_type <> 'retail.import_opening'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM retail_price_history WHERE tenant_id = ? AND source = 'import'"))
                .isEqualTo(17);
        assertThat(count("SELECT count(*) FROM retail_products WHERE tenant_id = ? AND NOT active"))
                .isEqualTo(1);

        // One balanced opening journal per branch: debit inventory, credit opening balance equity.
        Map<String, long[]> journals = new LinkedHashMap<>();
        owner.sql("""
                        SELECT b.code, a.system_key, sum(l.debit) AS debit, sum(l.credit) AS credit
                          FROM journal_entries j JOIN journal_lines l ON l.entry_id = j.id
                          JOIN gl_accounts a ON a.id = l.account_id JOIN branches b ON b.id = j.branch_id
                         WHERE j.tenant_id = ? GROUP BY b.code, a.system_key
                        """)
                .param(t.tenantId())
                .query((rs, n) -> {
                    long[] v = journals.computeIfAbsent(rs.getString(1), k -> new long[4]);
                    int at = rs.getString(2).equals("inventory") ? 0 : 2;
                    v[at] += rs.getLong(3);
                    v[at + 1] += rs.getLong(4);
                    return 1;
                })
                .list();
        assertThat(journals).containsOnlyKeys(OPENING.keySet());
        OPENING.forEach(
                (code, amount) -> assertThat(journals.get(code)).as(code).containsExactly(amount, 0L, 0L, amount));
        assertThat(count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(3);

        // The valuation counts imported stock exactly like live stock (FR-RET-09).
        JsonNode valuation = api.get("/reports/valuation", ADMIN).getBody();
        long openingTotal = OPENING.values().stream().mapToLong(Long::longValue).sum();
        assertThat(valuation.get("value_at_cost_minor").asLong()).isEqualTo(openingTotal + NEGATIVE_AT_COST);
        assertThat(valuation.get("expected_sales_minor").asLong()).isEqualTo(10_692_400L);
        UUID jja = branch("JJA");
        for (JsonNode b : valuation.get("branches")) {
            long difference = b.get("revaluation_difference_minor").asLong();
            assertThat(difference)
                    .isEqualTo(b.get("branch_id").asString().equals(jja.toString()) ? NEGATIVE_AT_COST : 0);
        }

        // Every movement carries a business date (V14, review F4): a history movement the date of
        // its source row, the legacy balance the import date in the tenant's zone, the date the
        // opening journals carry.
        LocalDate importDate = clock.today(BusinessClock.DEFAULT_ZONE);
        assertThat(count("""
                        SELECT count(*) FROM retail_stock_movements m JOIN retail_sales s ON s.id = m.source_id
                         WHERE m.tenant_id = ? AND m.business_date <> s.sale_date""")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM retail_stock_movements m JOIN retail_purchases p ON p.id = m.source_id
                         WHERE m.tenant_id = ? AND m.business_date <> p.purchased_on""")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM retail_stock_movements m JOIN retail_usage_reports u ON u.id = m.source_id
                         WHERE m.tenant_id = ? AND m.business_date <> u.occurred_on""")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM retail_stock_movements
                         WHERE tenant_id = ? AND kind IN ('adjustment', 'return')
                           AND business_date <> (occurred_at AT TIME ZONE 'Africa/Kampala')::date""")).isZero();
        assertThat(owner.sql("""
                        SELECT DISTINCT business_date FROM retail_stock_movements
                         WHERE tenant_id = ? AND kind = 'legacy_balance'""").param(t.tenantId()).query(LocalDate.class).list())
                .containsExactly(importDate);
        assertThat(owner.sql("SELECT DISTINCT entry_date FROM journal_entries WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(LocalDate.class)
                        .list())
                .containsExactly(importDate);

        // A valuation as_of a day before the import reads the imported history by business date,
        // without the legacy balance, and the inventory account holds nothing yet on that day.
        LocalDate before = importDate.minusDays(1);
        long historyAtCost =
                owner.sql("""
                        SELECT coalesce(sum(round(q.qty * p.cost_minor)), 0)
                          FROM (SELECT branch_id, product_id, sum(qty) AS qty FROM retail_stock_movements
                                 WHERE tenant_id = ? AND kind <> 'legacy_balance' AND business_date <= ?
                                 GROUP BY branch_id, product_id) q
                          JOIN retail_products p ON p.id = q.product_id
                        """).params(t.tenantId(), before).query(Long.class).single();
        assertThat(historyAtCost).isNotZero();
        JsonNode past = api.get("/reports/valuation?as_of=" + before, ADMIN).getBody();
        assertThat(past.get("value_at_cost_minor").asLong()).isEqualTo(historyAtCost);
        for (JsonNode b : past.get("branches")) {
            assertThat(b.get("inventory_account_minor").asLong()).isZero();
        }
        JsonNode onImportDay =
                api.get("/reports/valuation?as_of=" + importDate, ADMIN).getBody();
        assertThat(onImportDay.get("value_at_cost_minor").asLong()).isEqualTo(openingTotal + NEGATIVE_AT_COST);

        // One day's profit counts imported sales and usage exactly like live ones (FR-RET-10).
        JsonNode day = api.get("/reports/profit/daily?from=2026-09-10&to=2026-09-10", ADMIN)
                .getBody();
        assertThat(day.get("sales_minor").asLong()).isEqualTo(23_100L + 209_500L + 900_600L);
        assertThat(day.get("cost_of_sales_minor").asLong()).isEqualTo(21_500L + 49_750L + 848_500L);
        assertThat(day.get("usage_cost_minor").asLong()).isEqualTo(36_000L);
        assertThat(day.get("profit_minor").asLong())
                .isEqualTo(23_100L + 209_500L + 900_600L - 21_500L - 49_750L - 848_500L - 36_000L);

        // A re-run of the same export adds nothing.
        long after = rows(t.tenantId());
        ImportReport again = importer.run(t.slug(), SAMPLE, false);
        assertThat(again.failed()).as(again.render()).isFalse();
        assertThat(rows(t.tenantId())).isEqualTo(after);
        assertThat(again.counts("sales.jsonl")).containsExactly(221, 0, 220, 1);
        assertThat(again.counts("balances.jsonl")).containsExactly(60, 0, 60, 0);
        assertThat(again.render()).contains("already posted, not posted again").contains("match=yes");

        // Row-level security: the other tenant's catalogue and stock are untouched.
        assertThat(rows(other.tenantId())).isEqualTo(otherRows);
        assertThat(owner.sql("SELECT cost_minor FROM retail_products WHERE id = ?")
                        .param(otherProduct)
                        .query(Long.class)
                        .single())
                .isEqualTo(4_000L);
    }

    @Test
    void refusesAnUnknownTenantAndOneWithoutRetail() {
        TestDatabase.Fixture lendingOnly = TestDatabase.tenant("retail-none", true, false);
        assertThatThrownBy(() -> importer.run("no-such-tenant", SAMPLE, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> importer.run(lendingOnly.slug(), SAMPLE, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retail");
        assertThatThrownBy(() -> importer.run(t.slug(), SAMPLE.resolve("missing"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsBadRowsAndSkipsThem(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("categories.jsonl"), "{\"name\": \"Test Category\"}\n");
        Files.writeString(dir.resolve("units.jsonl"), "{\"name\": \"piece\"}\n");
        Files.writeString(dir.resolve("products.jsonl"), """
                {"code": "TB-1", "description": "Test bulb", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                {"code": "TB-2", "description": "Test bulb two", "category": "Test Category", "unit": "piece", "cost_minor": 1.5, "sell_minor": 150}
                not json
                """);
        Files.writeString(dir.resolve("sales.jsonl"), """
                {"source_ref": "S1", "branch": "HQ", "product_code": "tb-1", "qty": "2", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01T10:00:00"}
                {"source_ref": "S2", "branch": "XX", "product_code": "TB-1", "qty": "2", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01T10:00:00"}
                {"source_ref": "S3", "branch": "HQ", "product_code": "TB-1", "qty": "1.0005", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01"}
                {"source_ref": "S4", "branch": "HQ", "product_code": "TB-1", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "credit", "sold_at": "2026-09-01"}
                {"source_ref": "S1", "branch": "HQ", "product_code": "TB-1", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01"}
                """);

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(report.counts("products.jsonl")).containsExactly(3, 1, 0, 2);
        assertThat(report.counts("sales.jsonl")).containsExactly(5, 1, 0, 4);
        assertThat(report.anomalies())
                .anyMatch(a -> a.startsWith("products.jsonl:2: cost_minor must be a whole number"))
                .anyMatch(a -> a.startsWith("products.jsonl:3: not valid JSON"))
                .anyMatch(a -> a.startsWith("sales.jsonl:2: unknown branch code XX"))
                .anyMatch(a -> a.startsWith("sales.jsonl:3: qty has more than three decimal places"))
                .anyMatch(a -> a.startsWith("sales.jsonl:4: a credit sale needs a buyer"))
                .anyMatch(a -> a.startsWith("sales.jsonl:5: duplicate source_ref S1"))
                .anyMatch(a -> a.startsWith("branches.jsonl: file not found"));
        // The sale went below zero without a guard: history happened; the balance shows it.
        assertThat(owner.sql("SELECT qty FROM retail_stock_balances WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("-2");
    }

    /**
     * Review F9 carried into the import: a product code is normalised exactly as the catalogue
     * does it ({@link RetailCatalogue#normaliseCode}), so an imported code never differs from a
     * catalogue code by a Unicode space or a full-width form, and a code with a special space inside
     * is refused rather than stored.
     */
    @Test
    void normalisesProductCodesLikeTheCatalogue(@TempDir Path dir) throws IOException {
        api.product("NB-1", 100, 150);
        Files.writeString(dir.resolve("categories.jsonl"), "{\"name\": \"Test Category\"}\n");
        Files.writeString(dir.resolve("units.jsonl"), "{\"name\": \"piece\"}\n");
        Files.writeString(dir.resolve("products.jsonl"), """
                {"code": "NB-1\\u00a0", "description": "Test bulb one", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                {"code": "\\uff2e\\uff22-2", "description": "Test bulb two", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                {"code": "NB\\u20033", "description": "Test bulb three", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                """);
        Files.writeString(dir.resolve("sales.jsonl"), """
                {"source_ref": "S1", "branch": "HQ", "product_code": "\\u202fnb-2", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01T10:00:00"}
                {"source_ref": "S2", "branch": "HQ", "product_code": "nb-1\\u00a0", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01T10:00:00"}
                """);

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(report.counts("products.jsonl")).containsExactly(3, 1, 1, 1);
        assertThat(report.counts("sales.jsonl")).containsExactly(2, 2, 0, 0);
        assertThat(report.anomalies()).anyMatch(a -> a.startsWith("products.jsonl:3: product code NB"));
        assertThat(owner.sql("SELECT code FROM retail_products WHERE tenant_id = ? ORDER BY code")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("NB-1", "NB-2");
        assertThat(count("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'sale'"))
                .isEqualTo(2);
    }

    /**
     * Issue #73: a re-run never changes an existing product's prices. A price edited in the app
     * between two runs is kept, no history row is written, the product counts as existing and the
     * difference is reported. The re-run still writes one audit row per file.
     */
    @Test
    void reRunKeepsPricesEditedInTheApp(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("categories.jsonl"), "{\"name\": \"Test Category\"}\n");
        Files.writeString(dir.resolve("units.jsonl"), "{\"name\": \"piece\"}\n");
        Files.writeString(dir.resolve("products.jsonl"), """
                {"code": "TE-1", "description": "Test kettle", "category": "Test Category", "unit": "piece", "cost_minor": 1000, "sell_minor": 1500}
                """);
        ImportReport first = importer.run(t.slug(), dir, false);
        assertThat(first.failed()).as(first.render()).isFalse();
        assertThat(first.counts("products.jsonl")).containsExactly(1, 1, 0, 0);
        UUID product = owner.sql("SELECT id FROM retail_products WHERE tenant_id = ? AND code = 'TE-1'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();

        assertThat(api.editPrices(
                                product, Map.of("cost_minor", 1_200, "sell_minor", 1_800, "reason", "Test edit"), ADMIN)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        long history = count("SELECT count(*) FROM retail_price_history WHERE tenant_id = ?");
        long audits = audits();

        ImportReport again = importer.run(t.slug(), dir, false);

        assertThat(again.failed()).as(again.render()).isFalse();
        assertThat(again.counts("products.jsonl")).containsExactly(1, 0, 1, 0);
        assertThat(again.anomalies())
                .anyMatch(a -> a.startsWith(
                        "products.jsonl:1: product TE-1 existed with other prices; its prices were left unchanged"));
        assertThat(owner.sql("SELECT cost_minor, sell_minor FROM retail_products WHERE id = ?")
                        .param(product)
                        .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                        .single())
                .containsExactly(1_200L, 1_800L);
        assertThat(count("SELECT count(*) FROM retail_price_history WHERE tenant_id = ?"))
                .isEqualTo(history);
        assertThat(audits()).isEqualTo(audits + RetailImporter.FILES.size());
    }

    /** Issue #73: a balance the history already gives (zero legacy delta) is existing on every run. */
    @Test
    void zeroDeltaBalancesCountAsExisting(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("categories.jsonl"), "{\"name\": \"Test Category\"}\n");
        Files.writeString(dir.resolve("units.jsonl"), "{\"name\": \"piece\"}\n");
        Files.writeString(dir.resolve("products.jsonl"), """
                {"code": "TZ-1", "description": "Test cup one", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                {"code": "TZ-2", "description": "Test cup two", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                {"code": "TZ-3", "description": "Test cup three", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                """);
        Files.writeString(dir.resolve("sales.jsonl"), """
                {"source_ref": "S1", "branch": "HQ", "product_code": "TZ-1", "qty": "2", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01T10:00:00"}
                """);
        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TZ-1", "qty": "-2"}
                {"branch": "HQ", "product_code": "TZ-2", "qty": "0"}
                {"branch": "HQ", "product_code": "TZ-3", "qty": "4"}
                """);

        ImportReport first = importer.run(t.slug(), dir, false);
        assertThat(first.failed()).as(first.render()).isFalse();
        assertThat(first.counts("balances.jsonl")).containsExactly(3, 1, 2, 0);
        assertThat(first.checksumLine()).contains("match=yes");

        ImportReport again = importer.run(t.slug(), dir, false);
        assertThat(again.failed()).as(again.render()).isFalse();
        assertThat(again.counts("balances.jsonl")).containsExactly(3, 0, 3, 0);
        assertThat(count("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'legacy_balance'"))
                .isEqualTo(1);
    }

    /** Issue #73: the 40 character limit applies to the normal form, and an over-long one skips the row. */
    @Test
    void checksTheCodeLengthAfterNormalisation(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("categories.jsonl"), "{\"name\": \"Test Category\"}\n");
        Files.writeString(dir.resolve("units.jsonl"), "{\"name\": \"piece\"}\n");
        // U+FDFA is one character that NFKC expands to eighteen: 40 raw, 57 normalised.
        Files.writeString(dir.resolve("products.jsonl"), """
                {"code": "%s\\ufdfa", "description": "Test long code", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                {"code": "TL-1", "description": "Test short code", "category": "Test Category", "unit": "piece", "cost_minor": 100, "sell_minor": 150}
                """.formatted("L".repeat(39)));

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(report.counts("products.jsonl")).containsExactly(2, 1, 0, 1);
        assertThat(report.anomalies())
                .anyMatch(a -> a.startsWith("products.jsonl:1: code is longer than 40 characters after normalisation"));
    }

    private long audits() {
        return owner.sql(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.import.file_imported'")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    private long rows(UUID tenantId) {
        return owner.sql(TABLES_SQL).param("t", tenantId).query(Long.class).single();
    }

    private long count(String sql) {
        return owner.sql(sql).param(t.tenantId()).query(Long.class).single();
    }

    private UUID branch(String code) {
        return owner.sql("SELECT id FROM branches WHERE tenant_id = ? AND code = ?")
                .params(t.tenantId(), code)
                .query(UUID.class)
                .single();
    }
}
