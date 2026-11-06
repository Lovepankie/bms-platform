package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
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
        return "items".equals(entitySet) && keys.contains(EXISTING_ITEM) ? Set.of(EXISTING_ITEM) : Set.of();
    }

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

            @Override
            public void row(String entitySet, Map<String, Object> values) {
                if (!"sales".equals(entitySet)) {
                    return;
                }
                long amount = (Long) values.get("amount");
                if ("adjustment".equals(values.get("kind"))) {
                    qty = qty.add((BigDecimal) values.get("qty"));
                    value = Math.addExact(value, amount);
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
                    journals.add(new Journal(HEAD_OFFICE, "inventory", value, value));
                }
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
        };
    }

    /** The days-past-due bucket of a loan made at that moment, aged to {@link #AS_AT} in Kampala. */
    static String bucket(Instant madeAt) {
        return bucket(madeAt.atZone(ZONE).toLocalDate());
    }

    /** The days-past-due bucket of a loan made on that day, aged to {@link #AS_AT}. */
    static String bucket(LocalDate madeOn) {
        long days = ChronoUnit.DAYS.between(madeOn, AS_AT);
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
}
