package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportBooks;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A fabricated canonical template, registered only on the test classpath (as {@code TestApprovalAction} is), the way a
 * vertical registers its own: a bean that implements {@link ImportTemplate}, found by the registry without the core naming
 * it (issue #129). Its three entity sets use every field kind of spec section 3.2, and its mapping fixtures are in
 * {@code fixtures/imports/}. No real shop, header or value: every name here is made up.
 */
@Component
class FixtureShopTemplate implements ImportTemplate {

    static final String KEY = "fixture_shop";

    /** An item the business already holds, so a sale may name it without an items row in the run. */
    static final String EXISTING_ITEM = "FAB-900";

    static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    /** What a test does between the validation's two passes, where the engine asks for the business's keys; or null. */
    static volatile Runnable BETWEEN_PASSES;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public List<EntitySet> entitySets() {
        return List.of(
                new EntitySet(
                        "items",
                        List.of(
                                Field.required("code", FieldType.text(20))
                                        .normalisedBy(Normaliser.UPPER)
                                        .asKey(),
                                Field.required("name", FieldType.text(120)).normalisedBy(Normaliser.COLLAPSE_SPACES),
                                Field.optional("unit", FieldType.text(20)),
                                Field.optional("price", FieldType.money()).range(BigDecimal.ZERO, MAX_PRICE),
                                Field.optional("kind", FieldType.enumOf("stock", "service")),
                                Field.optional("active", FieldType.bool())),
                        Set.of(RowRule.PLACEHOLDER_ROWS)),
                new EntitySet(
                        "customers",
                        List.of(
                                Field.required("ref_no", FieldType.text(20)).asKey(),
                                Field.required("name", FieldType.text(120))
                                        .normalisedBy(Normaliser.COLLAPSE_SPACES)
                                        .personalData(),
                                Field.optional("phone", FieldType.phone()),
                                Field.optional("nin", FieldType.nin()),
                                Field.optional("joined_on", FieldType.date()))),
                new EntitySet(
                        "sales",
                        List.of(
                                Field.required("item", FieldType.ref("items")),
                                Field.optional("customer", FieldType.ref("customers")),
                                Field.required("sold_at", FieldType.datetime()),
                                Field.required("qty", FieldType.qty()),
                                Field.required("amount", FieldType.money()),
                                Field.required("kind", FieldType.enumOf("sale", "adjustment"))),
                        Set.of(RowRule.SUBHEADER_MONTH, RowRule.TOTALS_ROW)));
    }

    /**
     * Its own codes (issue #130): {@code FIXTURE_ITEM_UNNAMED} is retired, kept and marked, since the required flag of
     * {@code name} reports an empty name as {@code REQUIRED_MISSING}; {@code FIXTURE_SALE_QTY_NOT_POSITIVE} is the cross-field
     * rule of a sale. JUSTIFICATION-A3: the fixture template exercises the new contract of #130.
     */
    @Override
    public List<IssueCode> issueCodes() {
        return List.of(
                new IssueCode("FIXTURE_ITEM_UNNAMED", Severity.BLOCKING, "The item on line {line} has no name.", true),
                new IssueCode(
                        "FIXTURE_SALE_QTY_NOT_POSITIVE",
                        Severity.BLOCKING,
                        "{field} on {file} line {line} must be above zero for a sale."));
    }

    /** A sale sells something: its quantity is above zero; an adjustment may take stock off. */
    @Override
    public List<RowCheck> rowChecks(String entitySet) {
        if (!"sales".equals(entitySet)) {
            return List.of();
        }
        return List.of(values ->
                "sale".equals(values.get("kind")) && values.get("qty") instanceof BigDecimal qty && qty.signum() <= 0
                        ? List.of(new RowFinding("FIXTURE_SALE_QTY_NOT_POSITIVE", "qty", Map.of()))
                        : List.of());
    }

    /** The business already holds the item {@code FAB-900} (in compare form), as a vertical's own table would answer. */
    @Override
    public Set<String> existingKeys(String entitySet, Set<String> keys) {
        Runnable between = BETWEEN_PASSES;
        if (between != null) {
            between.run();
        }
        if (!"items".equals(entitySet)) {
            return Set.of();
        }
        return Set.of(EXISTING_ITEM, SHORT_ITEM).stream()
                .filter(keys::contains)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** A second item the business holds, which a test counts below zero (review of 391e1dc); no golden file names it. */
    static final String SHORT_ITEM = "FAB-901";

    /** The test business's head office: stock takes and loans. */
    static final String HEAD_OFFICE = "HQ";

    /** The test business's second branch: sales without a named buyer. */
    static final String SECOND_BRANCH = "BR2";

    /** The day the fixture's loans are aged at: the day after the golden run's cutover in Kampala. */
    static final LocalDate AS_AT = LocalDate.of(2026, 10, 1);

    static final ZoneId ZONE = ZoneId.of("Africa/Kampala");

    // JUSTIFICATION-A3: issue #132 and its review add the forecast to the template contract; the fixture's rules.
    /**
     * The fixture's predicted balances (issue #132), by rules made up for the test, one rule per kind of balance:
     *
     * <ul>
     *   <li>a stock take ({@code adjustment}) puts its quantity on hand at the head office at the value it is paid at:
     *       stock, and the {@code inventory} opening journal of the head office;
     *   <li>a sale to a named buyer is a loan of the head office of its amount, aged in days from its day to
     *       {@link #AS_AT} ({@link #bucket}): loan principal, an arrears bucket and the {@code loans_receivable} journal;
     *   <li>a sale without a named buyer is owed to the second branch: a receivable and its {@code trade_debtors} journal.
     * </ul>
     *
     * The integration test's writer posts by the same rules, and the test reads the books after the commit, so the preview
     * must equal what was posted, per branch.
     */
    @Override
    public ImportForecast forecast() {
        return new ImportForecast() {
            BigDecimal qty = BigDecimal.ZERO;
            long value;
            long owed;
            long lent;
            final Map<String, long[]> buckets = new TreeMap<>();
            final ProductStocks products = new ProductStocks();
            final List<Journal> inventory = new ArrayList<>();

            @Override
            public void batch(int seq) {
                products.closeBatch(inventory);
            }

            @Override
            public void row(String entitySet, Map<String, Object> values) {
                if (!"sales".equals(entitySet)) {
                    return;
                }
                long amount = (Long) values.get("amount");
                if ("adjustment".equals(values.get("kind"))) {
                    qty = qty.add((BigDecimal) values.get("qty"));
                    value = Math.addExact(value, amount);
                    products.add(values, amount);
                } else if (values.get("customer") != null) {
                    lent = Math.addExact(lent, amount);
                    long[] b = buckets.computeIfAbsent(bucket((Instant) values.get("sold_at")), k -> new long[2]);
                    b[0]++;
                    b[1] = Math.addExact(b[1], amount);
                } else {
                    owed = Math.addExact(owed, amount);
                }
            }

            @Override
            public Balances balances() {
                List<Journal> journals = new ArrayList<>();
                List<Stock> stock = new ArrayList<>();
                List<Amount> receivables = new ArrayList<>();
                List<Amount> loans = new ArrayList<>();
                List<ArrearsBucket> arrears = new ArrayList<>();
                if (value != 0 || qty.signum() != 0) {
                    stock.add(new Stock(HEAD_OFFICE, qty, value));
                }
                // The journals hold the positive balances only, netted over the whole run; a negative one is listed
                // apart (ImportBooks.stock).
                products.closeBatch(inventory);
                journals.addAll(inventory);
                if (lent != 0) {
                    loans.add(new Amount(HEAD_OFFICE, lent));
                    buckets.forEach((bucket, b) ->
                            arrears.add(new ArrearsBucket(HEAD_OFFICE, bucket, Math.toIntExact(b[0]), b[1])));
                    journals.add(new Journal(HEAD_OFFICE, "loans_receivable", lent, lent));
                }
                if (owed != 0) {
                    receivables.add(new Amount(SECOND_BRANCH, owed));
                    journals.add(new Journal(SECOND_BRANCH, "trade_debtors", owed, owed));
                }
                return new Balances(stock, receivables, loans, arrears, journals);
            }

            @Override
            public List<ProductStock> productStock() {
                return products.list();
            }
        };
    }

    // JUSTIFICATION-A3: issue #134, the fixture's stock per product and its books, for the reconciliation's checks.
    /** The fixture's stock takes per item at the head office (issue #134): the reconciliation's quantity check. */
    static final class ProductStocks {
        private final Map<String, BigDecimal> qty = new TreeMap<>();
        private final Map<String, Long> value = new TreeMap<>();

        void add(Map<String, Object> values, long amount) {
            String item = String.valueOf(values.get("item"));
            qty.merge(item, (BigDecimal) values.get("qty"), BigDecimal::add);
            value.merge(item, amount, Math::addExact);
        }

        private long journalledSoFar;

        /**
         * Closes a batch (Lead on the review of 4105c2a): its inventory journal is what the run's items counted above zero
         * are worth now less what the earlier batches journalled, so the journals of the run add up to the items netted
         * over every batch. A fall is a journal the other way (credit inventory), listed by its amount.
         */
        void closeBatch(List<ImportForecast.Journal> journals) {
            long now = journalled();
            long change = Math.subtractExact(now, journalledSoFar);
            if (change != 0) {
                long amount = Math.abs(change);
                journals.add(new ImportForecast.Journal(HEAD_OFFICE, "inventory", amount, amount));
            }
            journalledSoFar = now;
        }

        /** The value of the items counted above zero: what the opening inventory journals hold. */
        long journalled() {
            long sum = 0;
            for (Map.Entry<String, BigDecimal> e : qty.entrySet()) {
                if (e.getValue().signum() > 0) {
                    sum = Math.addExact(sum, value.get(e.getKey()));
                }
            }
            return sum;
        }

        List<ImportForecast.ProductStock> list() {
            List<ImportForecast.ProductStock> out = new ArrayList<>();
            qty.forEach((item, q) -> out.add(
                    new ImportForecast.ProductStock(HEAD_OFFICE, item, q, BigDecimal.valueOf(value.get(item)))));
            return out;
        }
    }

    /** Whether reading the fixture's books takes a second, as a big book would; off by default. */
    static volatile boolean SLOW_BOOKS;

    /** The test business's database, for the fixture's books; null when a unit test makes the template by hand. */
    @Autowired(required = false)
    JdbcClient jdbc;

    /**
     * The fixture's books (issue #134), read inside the reconciliation's transaction as the business: the stock the run's
     * registered movements put on hand per branch and item, from the retail stock ledger the test writers post to, valued
     * exactly at each movement's unit cost (the value matters only for a negative balance, listed separately), and the
     * run's loans from its {@code loans_receivable}
     * opening journals, one loan per line, aged from the entry's day to the day asked (the fixture has no loan book of its
     * own until the lending template of #136).
     */
    @Override
    public ImportBooks books() {
        JdbcClient db = jdbc;
        if (db == null) {
            return ImportBooks.NONE;
        }
        return new ImportBooks() {
            @Override
            public List<ImportForecast.ProductStock> stock(Scope scope) {
                if (SLOW_BOOKS) {
                    // A big book: one statement longer than a short limit (the sign-off's time limit test).
                    db.sql("SELECT pg_sleep(1)").query().singleRow();
                }
                List<UUID> movements = scope.of("retail.stock_movement");
                if (movements.isEmpty()) {
                    return List.of();
                }
                // Only the movements the run registered: stock held before or moved after the import is not the
                // import's.
                return db.sql("""
                                SELECT b.code, p.code, sum(m.qty), sum(m.qty * m.unit_cost_minor)
                                  FROM retail_stock_movements m
                                  JOIN branches b ON b.id = m.branch_id
                                  JOIN retail_products p ON p.id = m.product_id
                                 WHERE m.id = ANY(?)
                                 GROUP BY b.code, p.code
                                 ORDER BY b.code, p.code
                                """)
                        .param(movements.toArray(UUID[]::new))
                        .query((rs, n) -> new ImportForecast.ProductStock(
                                rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)))
                        .list();
            }

            @Override
            public List<ImportForecast.ArrearsBucket> arrears(Scope scope, LocalDate asAt) {
                List<UUID> journals = scope.of("ledger.journal_entry");
                if (journals.isEmpty()) {
                    return List.of();
                }
                Map<String, long[]> buckets = new TreeMap<>();
                db.sql("""
                                SELECT b.code, e.entry_date, l.debit - l.credit
                                  FROM journal_lines l
                                  JOIN journal_entries e ON e.id = l.entry_id
                                  JOIN gl_accounts a ON a.id = l.account_id
                                  JOIN branches b ON b.id = e.branch_id
                                 WHERE a.system_key = 'loans_receivable' AND l.entry_id = ANY(?)
                                """)
                        .param(journals.toArray(UUID[]::new))
                        .query((rs, n) -> {
                            String key =
                                    rs.getString(1) + "|" + bucket(rs.getDate(2).toLocalDate(), asAt);
                            long[] b = buckets.computeIfAbsent(key, k -> new long[2]);
                            b[0]++;
                            b[1] = Math.addExact(b[1], rs.getLong(3));
                            return null;
                        })
                        .list();
                List<ImportForecast.ArrearsBucket> out = new ArrayList<>();
                buckets.forEach((key, b) -> {
                    String[] parts = key.split("\\|");
                    out.add(new ImportForecast.ArrearsBucket(parts[0], parts[1], Math.toIntExact(b[0]), b[1]));
                });
                return out;
            }
        };
    }

    /** The days-past-due bucket of a loan made at that moment, aged to {@link #AS_AT} in Kampala. */
    static String bucket(Instant madeAt) {
        return bucket(madeAt.atZone(ZONE).toLocalDate());
    }

    /** The days-past-due bucket of a loan made on that day, aged to {@link #AS_AT}. */
    static String bucket(LocalDate madeOn) {
        return bucket(madeOn, AS_AT);
    }

    /** The days-past-due bucket of a loan made on that day, aged to the day given (issue #134, the fixture's books). */
    static String bucket(LocalDate madeOn, LocalDate asAt) {
        long days = ChronoUnit.DAYS.between(madeOn, asAt);
        if (days <= 0) {
            return "current";
        }
        return days <= 30 ? "1-30" : days <= 60 ? "31-60" : days <= 90 ? "61-90" : "over-90";
    }

    @Override
    public BatchWriter loader(String entitySet) {
        // The commit and the dry run resolve it (Lead ruling 4 on #869). It writes nothing unless a test gives it the
        // writer to run: the only way a test's writer reaches the commit, through a template, as the commit requires.
        return (runId, batchId, seq, checkpoint) -> {
            BatchWriter script = SCRIPT;
            return script == null
                    ? Map.of("entity_set", entitySet, "written", 0)
                    : script.write(runId, batchId, seq, checkpoint);
        };
    }

    /** What a test has the fixture's loader do for every batch, or null for nothing; reset after each test. */
    static volatile BatchWriter SCRIPT;

    // ---------------------------------------------------------------------------------------------
    // The receivable decision (issue #133), declared only while a test switches it on.
    // JUSTIFICATION-A3: the commit of #133 needs a template with a policy decision; the fixture's, switched per test.
    // ---------------------------------------------------------------------------------------------

    /**
     * The fixture's receivable decision (spec section 3.8): a sale to a named buyer is a credit sale whose payment the
     * source does not record, so a run that writes one needs the decision.
     */
    static final Policy RECEIVABLE =
            Policy.receivable("sales", v -> "sale".equals(v.get("kind")) && v.get("customer") != null);

    /** The policies the fixture declares; none unless a test sets them, and reset after it. */
    static volatile List<Policy> POLICIES = List.of();

    @Override
    public List<Policy> policies() {
        return POLICIES;
    }

    /**
     * With the receivable decision declared, the fixture's rules for a commit test (issue #133), made up: a stock take puts
     * its quantity on hand at the head office at the value it is paid at (stock, and the head office's {@code inventory}
     * opening journal); a credit sale is history, and only under {@code open_from_list} its amount is opened as a receivable
     * of the head office ({@code trade_debtors}); {@code treat_as_settled} and {@code defer} open none; a cash sale is
     * history only. Without the decision, the rules of {@link #forecast()}.
     */
    @Override
    public ImportForecast forecast(Map<String, String> policy) {
        if (POLICIES.isEmpty()) {
            return forecast();
        }
        boolean open = Policy.OPEN_FROM_LIST.equals(policy.get(Policy.RECEIVABLE));
        // One inventory and one trade debtors journal per batch, as the commit posts them (Lead ruling 2 on #872).
        return new ImportForecast() {
            final List<Journal> journals = new ArrayList<>();
            BigDecimal qty = BigDecimal.ZERO;
            long value;
            long owed;
            long batchOwed;
            // The run's items: each batch's inventory journal is the change in what its items counted above zero are
            // worth, netted over the whole run (ImportBooks.stock).
            final ProductStocks products = new ProductStocks();

            @Override
            public void batch(int seq) {
                close();
            }

            @Override
            public List<ProductStock> productStock() {
                return products.list();
            }

            private void close() {
                products.closeBatch(journals);
                if (batchOwed != 0) {
                    journals.add(new Journal(HEAD_OFFICE, "trade_debtors", batchOwed, batchOwed));
                }
                batchOwed = 0;
            }

            @Override
            public void row(String entitySet, Map<String, Object> values) {
                if (!"sales".equals(entitySet)) {
                    return;
                }
                long amount = (Long) values.get("amount");
                if ("adjustment".equals(values.get("kind"))) {
                    qty = qty.add((BigDecimal) values.get("qty"));
                    value = Math.addExact(value, amount);
                    products.add(values, amount);
                } else if (open && RECEIVABLE.neededBy().test(values)) {
                    owed = Math.addExact(owed, amount);
                    batchOwed = Math.addExact(batchOwed, amount);
                }
            }

            @Override
            public Balances balances() {
                close();
                List<Stock> stock = new ArrayList<>();
                List<Amount> receivables = new ArrayList<>();
                if (value != 0 || qty.signum() != 0) {
                    stock.add(new Stock(HEAD_OFFICE, qty, value));
                }
                if (owed != 0) {
                    receivables.add(new Amount(HEAD_OFFICE, owed));
                }
                return new Balances(stock, receivables, List.of(), List.of(), List.copyOf(journals));
            }
        };
    }
}
