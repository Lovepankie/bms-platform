package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportBooks;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.CompactKeys;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.stock.Quantities;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code retail_v1}, the canonical template of retail (issue #135, spec section 4.1 and Appendix A, ADR-027 decision 1): the
 * field list of the normalised export, {@code docs/specs/retail-pilot-data-dictionary.md} section 4, as ten entity sets in
 * the order the command imports them, the retail issue codes of spec section 3.4.1, the receivable decision, the forecast
 * and the books. Its loader is {@link RetailImporter#loader}: the code of {@code import-retail}, which the command runs on
 * its own export ({@link #exportMapping}) and the framework runs on a business's sheets read with a mapping, so both write
 * one way. The cash book files of section 5 are not part of it: they stay the command's own, a later version's to add.
 */
@Component
class RetailV1Template implements ImportTemplate {

    static final String KEY = "retail";

    static final int VERSION = 1;

    /** The entity sets in commit order, each fed by the export file of the same name. */
    static final List<String> SETS = List.of(
            "branches",
            "categories",
            "units",
            "products",
            "suppliers",
            "customers",
            "purchases",
            "sales",
            "usage",
            "balances");

    /** The sets whose rows the export knows by their own {@code source_ref}; the others by position. */
    static final Set<String> BY_REFERENCE = Set.of("purchases", "sales", "usage");

    /** The ledger type of the opening stock journal: the one the command's journals have always had. */
    static final String OPENING_TYPE = "retail.import_opening";

    static final String NAME_MISSING = "NAME_MISSING";
    static final String NEGATIVE_BALANCE = "NEGATIVE_BALANCE";
    static final String PRICE_DIFFERS_EXISTING = "PRICE_DIFFERS_EXISTING";
    static final String CREDIT_SALE_UNPAID = "CREDIT_SALE_UNPAID";

    /**
     * The receivable decision (spec section 3.8) of a source with credit sales and no payments. {@code open_from_list} is
     * not offered: the export holds no outstanding amount per buyer at the cutover moment, so a run that chose it could only
     * guess; a later version that adds that list offers it.
     */
    static final Policy RECEIVABLE = new Policy(
            Policy.RECEIVABLE,
            List.of(Policy.TREAT_AS_SETTLED, Policy.DEFER),
            Set.of(Policy.DEFER),
            "CREDIT_DECISION_MISSING",
            "sales",
            v -> "credit".equals(v.get("payment_method")));

    /** The catalogue's normal form of a product code (data dictionary rule 1), as the import and the forms apply it. */
    static final Normaliser RETAIL_CODE = new Normaliser(
            "retail_code",
            s -> s == null ? null : RetailCatalogue.normaliseCode(s).orElse(s.strip()));

    private static final BigDecimal MAX_AMOUNT = BigDecimal.valueOf(RetailCatalogue.MAX_AMOUNT_MINOR);

    /**
     * Found when a loader is asked for, not when the template is made: the importer runs the command on the framework,
     * whose registry holds this template, so a constructor dependency would be a cycle.
     */
    private final ObjectProvider<RetailImporter> importer;

    private final CatalogueHistory catalogue;
    private final Branches branches;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    RetailV1Template(
            ObjectProvider<RetailImporter> importer,
            CatalogueHistory catalogue,
            Branches branches,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.importer = importer;
        this.catalogue = catalogue;
        this.branches = branches;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public List<EntitySet> entitySets() {
        return ENTITY_SETS;
    }

    private static Field money(String name, boolean required) {
        Field f = required ? Field.required(name, FieldType.money()) : Field.optional(name, FieldType.money());
        return f.range(BigDecimal.ZERO, MAX_AMOUNT);
    }

    private static Field text(String name, int length, boolean required) {
        return required ? Field.required(name, FieldType.text(length)) : Field.optional(name, FieldType.text(length));
    }

    /** The source system's user name, kept with the reference: a person's, so never in a message. */
    private static Field sourceUser() {
        return text("source_user", 200, false).personalData();
    }

    /** Data dictionary section 4, field by field: required in bold there is required here. */
    static final List<EntitySet> ENTITY_SETS = List.of(
            new EntitySet(
                    "branches",
                    List.of(
                            Field.required("code", FieldType.text(10))
                                    .normalisedBy(Normaliser.UPPER)
                                    .asKey(),
                            text("name", 200, true))),
            new EntitySet("categories", List.of(text("name", 100, true).asKey())),
            new EntitySet("units", List.of(text("name", 30, true).asKey())),
            new EntitySet(
                    "products",
                    List.of(
                            Field.required("code", FieldType.text(40))
                                    .normalisedBy(RETAIL_CODE)
                                    .asKey(),
                            // The item's name: a row without one is NAME_MISSING, the retail code (spec 3.4.1).
                            text("description", 300, false),
                            text("category", 100, true),
                            text("unit", 30, true),
                            money("cost_minor", true),
                            money("sell_minor", true),
                            Field.optional("active", FieldType.bool()))),
            new EntitySet("suppliers", List.of(text("name", 200, true).asKey())),
            new EntitySet(
                    "customers",
                    List.of(
                            // Personal data, so not a key: no row refers to a buyer by it (a sale keeps its own text).
                            text("name", 200, true).personalData(),
                            // Kept as entered: a contact may hold several numbers or part of one (section 3).
                            text("contact", 100, false).personalData())),
            new EntitySet(
                    "purchases",
                    List.of(
                            text("source_ref", 100, true),
                            Field.required("product_code", FieldType.ref("products")),
                            Field.required("kind", FieldType.enumOf("purchase", "adjustment", "return")),
                            money("unit_cost_minor", true),
                            money("unit_sell_minor", false),
                            // Branch code to quantity: a JSON object, or BRANCH=qty pairs separated by ; in a sheet.
                            text("qty_by_branch", 2000, true),
                            Field.required("purchased_on", FieldType.datetime()),
                            text("supplier", 200, false),
                            sourceUser())),
            new EntitySet(
                    "sales",
                    List.of(
                            text("source_ref", 100, true),
                            Field.required("branch", FieldType.ref("branches")),
                            Field.required("product_code", FieldType.ref("products")),
                            Field.required("qty", FieldType.qty()),
                            money("unit_price_minor", true),
                            money("unit_cost_minor", true),
                            Field.required("payment_method", FieldType.enumOf("cash", "credit")),
                            text("buyer", 200, false).personalData(),
                            text("buyer_contact", 100, false).personalData(),
                            Field.optional("due_date", FieldType.date()),
                            Field.required("sold_at", FieldType.datetime()),
                            sourceUser())),
            new EntitySet(
                    "usage",
                    List.of(
                            text("source_ref", 100, true),
                            Field.required("branch", FieldType.ref("branches")),
                            Field.required("product_code", FieldType.ref("products")),
                            Field.required("kind", FieldType.enumOf("used", "damaged")),
                            text("reason", 300, true),
                            Field.required("qty", FieldType.qty()),
                            money("unit_cost_minor", true),
                            Field.required("reported_at", FieldType.datetime()),
                            sourceUser())),
            new EntitySet(
                    "balances",
                    List.of(
                            Field.required("branch", FieldType.ref("branches")),
                            Field.required("product_code", FieldType.ref("products")),
                            Field.required("qty", FieldType.qty()))));

    @Override
    public List<IssueCode> issueCodes() {
        return List.of(
                new IssueCode(NAME_MISSING, Severity.BLOCKING, "The item on line {line} has no name or description."),
                new IssueCode(
                        NEGATIVE_BALANCE,
                        Severity.WARNING,
                        "The source shows a negative quantity for this item at {branch}; it is imported as it is and"
                                + " listed for the first stock-take."),
                new IssueCode(
                        PRICE_DIFFERS_EXISTING,
                        Severity.INFO,
                        "The item already exists with other prices; its prices are left unchanged."),
                new IssueCode(
                        CREDIT_SALE_UNPAID,
                        Severity.INFO,
                        "Credit sale imported without payment history; the receivable decision applies."));
    }

    // JUSTIFICATION-A3: the Lead's rule on the review of 4af7a39 (one opening journal per branch) needs this one read.
    /**
     * The branches that already have an import opening stock journal, by code in compare form: the framework's and the
     * command's ({@code opening|inventory|...} in {@code import_refs}, V145's copies of the old command's included) and the
     * old command's in {@code retail_import_refs}. A journal of {@code runId} itself is left out (null for none), so the
     * forecast of a committed run still predicts the journal that run posted. The one read the forecast and the loader
     * apply the rule by: a branch in this set takes no second journal (Lead's rule on the review of 4af7a39).
     */
    static Set<String> openedBranches(JdbcClient jdbc, UUID runId) {
        Set<String> out = new HashSet<>();
        jdbc.sql("""
                        SELECT b.code FROM journal_entries e JOIN branches b ON b.id = e.branch_id
                         WHERE e.tenant_id = current_setting('app.tenant_id')::uuid AND e.source_type = ?
                           AND (e.id IN (SELECT r.target_id FROM import_refs r
                                          WHERE r.tenant_id = e.tenant_id AND r.target_type = 'ledger.journal_entry'
                                            AND r.source_key LIKE 'opening|inventory|%'
                                            AND r.run_id IS DISTINCT FROM CAST(? AS uuid))
                                OR e.id IN (SELECT o.target_id FROM retail_import_refs o
                                             WHERE o.tenant_id = e.tenant_id AND o.source_file = 'opening'
                                               AND NOT EXISTS (SELECT 1 FROM import_refs r
                                                                WHERE r.tenant_id = o.tenant_id
                                                                  AND r.target_id = o.target_id
                                                                  AND r.run_id = CAST(? AS uuid))))
                        """)
                .params(OPENING_TYPE, runId, runId)
                .query((rs, n) -> out.add(compare(rs.getString(1))))
                .list();
        return out;
    }

    /**
     * The retail rules a row shows by itself, and the one that needs the business's items: a product without a name, an
     * existing product whose prices differ (issue #73: they are kept), a negative balance, and a credit sale.
     */
    @Override
    public List<RowCheck> rowChecks(String entitySet) {
        return switch (entitySet) {
            case "products" -> List.of(v -> productFindings(v, this::existingPrices));
            case "balances" -> List.of(RetailV1Template::balanceFindings);
            case "sales" -> List.of(RetailV1Template::saleFindings);
            default -> List.of();
        };
    }

    static List<RowFinding> productFindings(Map<String, Object> v, Function<String, Optional<long[]>> prices) {
        List<RowFinding> out = new ArrayList<>();
        if (!(v.get("description") instanceof String d) || d.isBlank()) {
            out.add(new RowFinding(NAME_MISSING, "description", Map.of()));
        }
        if (v.get("code") instanceof String code
                && v.get("cost_minor") instanceof Long cost
                && v.get("sell_minor") instanceof Long sell) {
            prices.apply(code)
                    .filter(p -> p[0] != cost || p[1] != sell)
                    .ifPresent(p -> out.add(new RowFinding(PRICE_DIFFERS_EXISTING, "cost_minor", Map.of())));
        }
        return out;
    }

    static List<RowFinding> balanceFindings(Map<String, Object> v) {
        if (v.get("qty") instanceof BigDecimal qty && qty.signum() < 0) {
            String branch = String.valueOf(v.get("branch"));
            return List.of(new RowFinding(NEGATIVE_BALANCE, "qty", Map.of("branch", branch)));
        }
        return List.of();
    }

    static List<RowFinding> saleFindings(Map<String, Object> v) {
        return "credit".equals(v.get("payment_method"))
                ? List.of(new RowFinding(CREDIT_SALE_UNPAID, "payment_method", Map.of()))
                : List.of();
    }

    /** The cost and sell price of the business's product with this code, compared ignoring case. */
    private Optional<long[]> existingPrices(String code) {
        return jdbc.sql("SELECT cost_minor, sell_minor FROM retail_products"
                        + " WHERE tenant_id = current_setting('app.tenant_id')::uuid AND lower(code) = lower(?)")
                .param(code)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .optional();
    }

    /** The compare form of a key: upper case with every space removed (ImportTemplate#existingKeys). */
    static String compare(String key) {
        return key == null ? null : key.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    @Override
    public Set<String> existingKeys(String entitySet, Set<String> keys) {
        Set<String> held = switch (entitySet) {
            case "branches" ->
                branches.all().stream().map(b -> compare(b.code())).collect(Collectors.toSet());
            case "products" ->
                catalogue.productsByCode().values().stream()
                        .map(p -> compare(p.code()))
                        .collect(Collectors.toSet());
            default -> Set.of();
        };
        return keys.stream().filter(held::contains).collect(Collectors.toSet());
    }

    @Override
    public List<Policy> policies() {
        return List.of(RECEIVABLE);
    }

    @Override
    public BatchWriter loader(String entitySet) {
        return importer.getObject().loader(entitySet);
    }

    // ---------------------------------------------------------------------------------------------
    // The forecast and the books (issues #132 to #134), by the rules the loader posts by.
    // ---------------------------------------------------------------------------------------------

    @Override
    public ImportForecast forecast() {
        return forecast(Map.of());
    }

    /**
     * The predicted stock and opening journals, by the loader's rules (data dictionary rules 4 and 5): each balances row is
     * the source's current quantity of an item at a branch, which the legacy balance makes the derived balance, whatever the
     * history before it; the first row of an item and branch counts (a second is refused). Each is valued at the item's
     * cost: an item the business already holds keeps its own prices (issue #73), a new one takes its first products row's.
     * The opening inventory journal is one per branch, ever, for what the branch's rows above zero are worth,
     * so a negative balance is never in it (the negative-stock rule of #134). No receivable is opened under either option
     * of the decision. A history row moves its item's stock as the loader writes it, so an item whose legacy balance an
     * earlier import wrote is valued at the books' balance after this run's history, as the loader reads it (issue #876).
     */
    @Override
    public ImportForecast forecast(Map<String, String> policy) {
        return forecast(policy, null);
    }

    /**
     * {@link #forecast(Map)} of one run: a branch another run already gave an opening stock journal is predicted none,
     * as the loader posts none there (one opening journal per branch, ever: Lead's rule on the review of 4af7a39).
     */
    @Override
    public ImportForecast forecast(Map<String, String> policy, UUID runId) {
        return forecasts(policy, runId).get();
    }

    /** What {@link #forecast(Map, UUID)} reads, read once for every forecast the supplier makes (issue #880). */
    @Override
    public java.util.function.Supplier<ImportForecast> forecasts(Map<String, String> policy, UUID runId) {
        Map<String, Long> held = readOnly.execute(s -> {
            Map<String, Long> costs = new HashMap<>();
            catalogue.productsByCode().values().forEach(p -> costs.put(compare(p.code()), p.costMinor()));
            return costs;
        });
        Set<String> opened = readOnly.execute(s -> openedBranches(jdbc, runId));
        Map<String, Map<String, Held>> before = readOnly.execute(s -> heldBefore(jdbc, runId));
        CompactKeys earlier = readOnly.execute(s -> earlierHistory(jdbc, runId));
        // The ledger takes no entry at a closed branch, so the loader journals none there and none is predicted.
        Set<String> inactive = readOnly.execute(s -> branches.all().stream()
                .filter(b -> !b.active())
                .map(b -> compare(b.code()))
                .collect(Collectors.toSet()));
        Map<String, Long> costs = held == null ? Map.of() : Map.copyOf(held);
        // Copied once here; each forecast shares what was read and copies none of it (issue #876, nice-to-have 4).
        Set<String> openedBefore = opened == null ? Set.of() : Set.copyOf(opened);
        Set<String> closed = inactive == null ? Set.of() : Set.copyOf(inactive);
        Map<String, Map<String, Held>> books = before == null ? Map.of() : Map.copyOf(before);
        CompactKeys earlierRefs = earlier == null ? new CompactKeys() : earlier;
        return () -> new StockForecast(costs, openedBefore, closed, books, earlierRefs);
    }

    // JUSTIFICATION-A3: the Lead's steer for round 4 asks one function shared by the forecast and the loader.
    /**
     * One branch's opening stock by data dictionary rule 5, the one rule the loader posts and the forecast predicts (Lead's
     * steer on the review of 51cfb70, and his rule on the review of 4af7a39): the branch's balance rows, the source's
     * quantity of each item (which the legacy balance makes the derived one, whatever run wrote the history), valued at each
     * item's current cost. Only items with a balance row count, as the old command journalled them.
     *
     * @param qty the quantity of the branch's rows, the negatives included
     * @param valueMinor what those rows are worth
     * @param aboveZeroMinor what the rows above zero are worth: the opening journal's measure (the negative-stock rule)
     */
    record BranchOpening(BigDecimal qty, long valueMinor, long aboveZeroMinor) {

        /**
         * The opening journal to post: what the rows above zero are worth, once per branch, ever. A branch that already
         * has an import opening journal ({@code opened}) takes none, as the old command answered "already posted"; its
         * differences are listed for a stock-take instead.
         */
        long journal(boolean opened) {
            return opened ? 0 : aboveZeroMinor;
        }
    }

    // JUSTIFICATION-A3: the review of bf87e45 (must-fix 1) asks the loader and the forecast to value one quantity.
    /**
     * The quantity each balance row of a branch stands at once the run is written (review of bf87e45, must-fix 1, and
     * issue #876): the source's, which the legacy balance this run writes makes the books'; or, for an item the run leaves
     * as the books hold it ({@code kept}, the books' balance after the run's history: one whose legacy balance an earlier
     * import already wrote there, or one the books hold at a branch already opened), what the books hold, since this run
     * writes nothing for it. The opening values these quantities, so the first journal never values stock the books did
     * not receive, and the forecast's stock per item is these quantities less the books' before the run.
     */
    static Map<String, BigDecimal> openingQuantities(Map<String, BigDecimal> rows, Map<String, BigDecimal> kept) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        rows.forEach((item, q) -> out.put(item, kept.getOrDefault(item, q)));
        return out;
    }

    /**
     * What the books hold of an item at a branch before a run (issue #876): its balance without the run's own movements,
     * and whether an earlier import already wrote its legacy balance there.
     */
    record Held(BigDecimal qty, boolean legacy) {}

    // JUSTIFICATION-A3: issue #876 (should-fix 1 and 2) asks the forecast to read the books before its run.
    /**
     * The books before {@code runId} (null for none), as the loader reads its positions, by branch and item code in
     * compare form: each position unless all its movements are the run's (a balance row with no movement counts, as the
     * loader's {@code StockLedger.positions} left join counts it), at its balance less the run's own
     * movements (those it registered, and those of the documents it registered), and whether a legacy balance the run did
     * not write is among them. So the forecast of a committed run reads the books as its preview did, and adds the run's
     * own history from its rows, as before the commit.
     */
    static Map<String, Map<String, Held>> heldBefore(JdbcClient jdbc, UUID runId) {
        Map<String, Map<String, Held>> out = new HashMap<>();
        jdbc.sql("""
                        WITH mine AS (
                            SELECT m.id FROM import_refs r JOIN retail_stock_movements m ON m.id = r.target_id
                             WHERE r.tenant_id = current_setting('app.tenant_id')::uuid AND r.run_id = CAST(? AS uuid)
                            UNION
                            SELECT m.id FROM import_refs r JOIN retail_stock_movements m ON m.source_id = r.target_id
                             WHERE r.tenant_id = current_setting('app.tenant_id')::uuid AND r.run_id = CAST(? AS uuid))
                        SELECT b.code, p.code, s.qty - coalesce(sum(m.qty) FILTER (WHERE x.id IS NOT NULL), 0),
                               coalesce(bool_or(m.kind = 'legacy_balance' AND x.id IS NULL), false)
                          FROM retail_stock_balances s
                          JOIN branches b ON b.id = s.branch_id
                          JOIN retail_products p ON p.id = s.product_id
                          LEFT JOIN retail_stock_movements m
                            ON m.tenant_id = s.tenant_id AND m.branch_id = s.branch_id AND m.product_id = s.product_id
                          LEFT JOIN mine x ON x.id = m.id
                         WHERE s.tenant_id = current_setting('app.tenant_id')::uuid
                         GROUP BY b.code, p.code, s.qty
                        HAVING bool_or(x.id IS NULL)
                        """)
                .params(runId, runId)
                .query((rs, n) -> out.computeIfAbsent(compare(rs.getString(1)), k -> new HashMap<>())
                        .put(compare(rs.getString(2)), new Held(rs.getBigDecimal(3), rs.getBoolean(4))))
                .list();
        return out;
    }

    /**
     * The history rows the business's imports already hold and {@code runId} did not register, as {@code set|source_ref}:
     * the loader counts such a row existing and writes nothing for it, whatever key an upload derives for it
     * ({@code RetailImporter.StagedRefs#seen}), so the forecast moves no stock for it either (issue #876). Every history row
     * an import wrote is in {@code retail_import_refs}, the command's and the screens' alike.
     */
    static CompactKeys earlierHistory(JdbcClient jdbc, UUID runId) {
        CompactKeys out = new CompactKeys();
        jdbc.sql("""
                        SELECT o.source_file, o.source_ref FROM retail_import_refs o
                         WHERE o.tenant_id = current_setting('app.tenant_id')::uuid
                           AND o.source_file IN ('purchases', 'sales', 'usage')
                           AND NOT EXISTS (SELECT 1 FROM import_refs r
                                            WHERE r.tenant_id = o.tenant_id AND r.target_id = o.target_id
                                              AND r.run_id = CAST(? AS uuid))
                        """)
                .param(runId)
                .query((rs, n) -> out.putIfAbsent(rs.getString(1) + "|" + rs.getString(2), 0L))
                .list();
        return out;
    }

    /**
     * One stock movement a history row makes: its branch code as the loader looks it up (trimmed, upper case), its item
     * code as written, and the signed quantity.
     */
    record Move(String branch, String product, BigDecimal qty) {}

    /** The {@code source_ref} of a history row, by which the loader knows it: the one reader of it (issue #876). */
    static String historyRef(ExportFile.Row row) {
        return row.requiredText("source_ref", 100);
    }

    /**
     * A history row's {@code source_ref} taken once among {@code refs}, the ones written before it: a second is refused
     * (issue #876, the Lead's ruling on #908). The loader takes each file's refs, and the forecast each set's over its
     * run, since the loader finds a row an earlier batch of the run wrote in the business's imports.
     */
    static void refOnce(ExportFile.Row row, String ref, Set<String> refs) {
        if (!refs.add(ref)) {
            throw row.problem("duplicate source_ref " + ref);
        }
    }

    // JUSTIFICATION-A3: the review of 43a5867 on #908 asks one movements-for-row function for the loader and forecast.
    /**
     * The stock movements one history row makes (data dictionary section 4): the one rule the loader writes by
     * ({@code RetailImporter.purchases}, {@code sales}, {@code usage}) and the forecast predicts by (issue #876, review of
     * 43a5867). A purchase, adjustment or return adds its quantity at each branch, signed as given: zero is left out, only
     * an adjustment may be negative, a branch named twice and a row with no quantity are refused. A sale or a usage report
     * takes its positive quantity off its branch. Throws {@link ExportFile.RowProblem}, with the loader's words, for a row
     * the loader refuses.
     */
    static List<Move> historyMoves(String entitySet, ExportFile.Row row) {
        String product = row.text("product_code");
        switch (entitySet) {
            case "purchases" -> {
                String kind = row.oneOf("kind", List.of("purchase", "adjustment", "return"));
                Map<String, BigDecimal> out = new LinkedHashMap<>();
                for (Map.Entry<String, BigDecimal> e :
                        row.qtyByBranch("qty_by_branch").entrySet()) {
                    BigDecimal q = e.getValue();
                    if (q.signum() == 0) {
                        continue;
                    }
                    if (q.signum() < 0 && !kind.equals("adjustment")) {
                        throw row.problem("a " + kind + " quantity must be positive, branch " + e.getKey());
                    }
                    if (out.putIfAbsent(e.getKey().strip().toUpperCase(Locale.ROOT), q) != null) {
                        throw row.problem("branch " + e.getKey() + " appears twice in qty_by_branch");
                    }
                }
                if (out.isEmpty()) {
                    throw row.problem("no quantity in qty_by_branch");
                }
                return out.entrySet().stream()
                        .map(e -> new Move(e.getKey(), product, e.getValue()))
                        .toList();
            }
            case "sales", "usage" -> {
                BigDecimal q = row.qty("qty");
                if (q.signum() <= 0) {
                    throw row.problem("qty must be positive");
                }
                String branch = row.text("branch");
                return List.of(
                        new Move(branch == null ? null : branch.strip().toUpperCase(Locale.ROOT), product, q.negate()));
            }
            default -> throw new IllegalArgumentException("not a history set: " + entitySet);
        }
    }

    /** {@link BranchOpening} of a branch's balance rows, item to quantity, each item valued by {@code costMinor}. */
    static BranchOpening branchOpening(
            Map<String, BigDecimal> rows, java.util.function.ToLongFunction<String> costMinor) {
        BigDecimal qty = BigDecimal.ZERO;
        long value = 0;
        long aboveZero = 0;
        for (Map.Entry<String, BigDecimal> e : rows.entrySet()) {
            long v = Quantities.value(e.getValue(), costMinor.applyAsLong(e.getKey()));
            qty = qty.add(e.getValue());
            value = Math.addExact(value, v);
            if (e.getValue().signum() > 0) {
                aboveZero = Math.addExact(aboveZero, v);
            }
        }
        return new BranchOpening(qty, value, aboveZero);
    }

    /** The forecast of one run or one row: see {@link #forecast(Map)}. */
    static final class StockForecast implements ImportForecast {

        /** The business's costs as read, shared by every forecast of one read and never changed (issue #880). */
        private final Map<String, Long> held;

        /** The costs of new items this forecast's own products rows gave: one per row of a product master (#880). */
        private final CompactKeys added = new CompactKeys();

        private final Map<String, Map<String, BigDecimal>> qty = new TreeMap<>();

        /** The quantities {@link #opening} last valued each branch's balance rows at, by branch. */
        private final Map<String, Map<String, BigDecimal>> valued = new HashMap<>();

        /** The stock this forecast's history rows move, by branch and item: what the loader writes before balances. */
        private final Map<String, Map<String, BigDecimal>> history = new TreeMap<>();

        /** The {@code source_ref} of each history set this forecast was given, for {@link #refOnce}. */
        private final Map<String, Set<String>> refs = new HashMap<>(4);

        /** The balance rows ({@code branch|item}) whose branch was opened when their batch began. */
        private final Set<String> atOpened = new HashSet<>();

        /** The branches opened before the run, shared by every forecast of one read; and those this one opened. */
        private final Set<String> openedBefore;

        private final Set<String> opened = new HashSet<>();
        private final List<Journal> journals = new ArrayList<>();
        private final Set<String> inactive;
        private final Map<String, Map<String, Held>> books;
        private final CompactKeys earlier;

        StockForecast(Map<String, Long> held) {
            this(held, Set.of(), Set.of(), Map.of());
        }

        StockForecast(
                Map<String, Long> held,
                Set<String> opened,
                Set<String> inactive,
                Map<String, Map<String, Held>> books) {
            this(held, opened, inactive, books, new CompactKeys());
        }

        /**
         * {@code opened}: the branches another run already gave an opening stock journal ({@link #openedBranches});
         * {@code inactive}: the closed branches, where no journal is posted; {@code books}: the books before the run
         * ({@link #heldBefore}); {@code earlier}: the history rows other imports hold ({@link #earlierHistory}); all in
         * compare form, never changed, so every forecast of one read shares them (issue #876).
         */
        StockForecast(
                Map<String, Long> held,
                Set<String> opened,
                Set<String> inactive,
                Map<String, Map<String, Held>> books,
                CompactKeys earlier) {
            this.held = held;
            this.openedBefore = opened;
            this.inactive = inactive;
            this.books = books;
            this.earlier = earlier;
        }

        private boolean opened(String branch) {
            return openedBefore.contains(branch) || opened.contains(branch);
        }

        /**
         * The branch's opening: its balance rows at the quantities they stand at once the run is written, by
         * {@link #openingQuantities}, the one function the loader values the opening by, given what the loader gives it
         * (the books' balance of each item it leaves as the books hold it, read once this run's history is written, which
         * is when the loader reads it). The quantities are recorded for {@link #productStock} (issue #876).
         */
        private BranchOpening opening(String branch, Map<String, BigDecimal> items) {
            Map<String, BigDecimal> kept = new HashMap<>();
            items.keySet().forEach(item -> {
                if (kept(branch, item)) {
                    kept.put(item, atLoad(branch, item));
                }
            });
            Map<String, BigDecimal> quantities = openingQuantities(items, kept);
            valued.put(branch, quantities);
            return branchOpening(quantities, product -> cost(product));
        }

        /**
         * Whether the loader leaves the item's balance row as the books hold it ({@code RetailImporter.balances}): an
         * earlier import wrote its legacy balance there, or the books hold it at a branch opened when its batch began.
         */
        private boolean kept(String branch, String item) {
            Held h = books.getOrDefault(branch, Map.of()).get(item);
            boolean onBooks =
                    h != null || history.getOrDefault(branch, Map.of()).containsKey(item);
            return (h != null && h.legacy()) || (onBooks && atOpened.contains(branch + "|" + item));
        }

        /** What the books hold of the item at the branch when the loader reads them: before the run, plus its history. */
        private BigDecimal atLoad(String branch, String item) {
            return before(branch, item)
                    .add(history.getOrDefault(branch, Map.of()).getOrDefault(item, BigDecimal.ZERO));
        }

        private BigDecimal before(String branch, String item) {
            Held h = books.getOrDefault(branch, Map.of()).get(item);
            return h == null ? BigDecimal.ZERO : h.qty();
        }

        private long cost(String product) {
            Long c = held.get(product);
            return c != null ? c : added.get(product, 0L);
        }

        @Override
        public void batch(int seq) {
            close();
        }

        @Override
        public void row(String entitySet, Map<String, Object> values) {
            switch (entitySet) {
                case "products" -> {
                    if (values.get("code") instanceof String code && values.get("cost_minor") instanceof Long c) {
                        // An item the business holds keeps its own cost (issue #73); a new one its first row's.
                        if (!held.containsKey(compare(code))) {
                            added.putIfAbsent(compare(code), c);
                        }
                    }
                }
                case "balances" -> {
                    if (values.get("branch") instanceof String branch
                            && values.get("product_code") instanceof String product
                            && values.get("qty") instanceof BigDecimal q
                            && qty.computeIfAbsent(compare(branch), k -> new TreeMap<>())
                                            .putIfAbsent(compare(product), q)
                                    == null
                            && opened(compare(branch))) {
                        atOpened.add(compare(branch) + "|" + compare(product));
                    }
                }
                case "purchases", "sales", "usage" -> history(entitySet, values);
                default -> {}
            }
        }

        /**
         * A history row, before the balances (the template's order): the stock it moves, as the loader writes it, unless
         * the loader counts it existing: a row other imports hold ({@link #earlierHistory}), or a second of its
         * {@code source_ref} in this run (issue #876, should-fix 1).
         */
        private void history(String entitySet, Map<String, Object> values) {
            ExportFile.Row row = ExportFile.staged(entitySet + ".jsonl", List.of(Map.entry(0, values)))
                    .rows
                    .getFirst();
            List<Move> moves;
            try {
                String ref = historyRef(row);
                if (earlier.contains(entitySet + "|" + ref)) {
                    return;
                }
                refOnce(row, ref, refs.computeIfAbsent(entitySet, k -> new HashSet<>()));
                moves = historyMoves(entitySet, row);
            } catch (ExportFile.RowProblem e) {
                // The loader refuses the row and writes nothing for it.
                return;
            }
            // A row without its item or branch is refused by the loader's look-up of them (and by the validation).
            if (moves.stream().anyMatch(m -> m.branch() == null || m.product() == null)) {
                return;
            }
            moves.forEach(m -> history.computeIfAbsent(compare(m.branch()), k -> new TreeMap<>())
                    .merge(compare(m.product()), m.qty(), BigDecimal::add));
        }

        private long value(String product, BigDecimal q) {
            return Quantities.value(q, cost(product));
        }

        /**
         * Closes a batch: each branch with rows takes the loader's journal, {@link BranchOpening#journal}, once; a branch
         * already opened (by another run, or by an earlier batch of this one) takes none.
         */
        private void close() {
            qty.forEach((branch, items) -> {
                // Every branch is valued and its quantities recorded; a closed one only takes no journal, as the loader
                // still writes its legacy balances (review of db86c31 on #908).
                BranchOpening opening = opening(branch, items);
                if (inactive.contains(branch)) {
                    return;
                }
                long amount = opening.journal(opened(branch));
                if (amount != 0) {
                    journals.add(new Journal(branch, "inventory", amount, amount));
                    opened.add(branch);
                }
            });
        }

        @Override
        public Balances balances() {
            close();
            List<Stock> stock = new ArrayList<>();
            qty.forEach((branch, items) -> {
                BranchOpening opening = opening(branch, items);
                stock.add(new Stock(branch, opening.qty(), opening.valueMinor()));
            });
            return new Balances(stock, List.of(), List.of(), List.of(), List.copyOf(journals));
        }

        /**
         * The stock the run puts on hand, per branch and item, as the books will show it from the run's own records
         * ({@link #books()}): what the books hold once the run is written less what they held before it (issue #876,
         * should-fix 2). An item with a balance row stands at the quantity {@link #opening} recorded for it as
         * {@link #balances()} valued it, so an item the run leaves as the books hold it counts only this run's history; an
         * item with history alone counts its history.
         */
        @Override
        public List<ProductStock> productStock() {
            balances();
            Map<String, Map<String, BigDecimal>> onHand = new TreeMap<>();
            history.forEach((branch, items) -> items.keySet()
                    .forEach(item ->
                            onHand.computeIfAbsent(branch, k -> new TreeMap<>()).put(item, atLoad(branch, item))));
            valued.forEach((branch, items) ->
                    onHand.computeIfAbsent(branch, k -> new TreeMap<>()).putAll(items));
            List<ProductStock> out = new ArrayList<>();
            onHand.forEach((branch, items) -> items.forEach((product, q) -> {
                BigDecimal share = q.subtract(before(branch, product));
                out.add(new ProductStock(branch, product, share, BigDecimal.valueOf(value(product, share))));
            }));
            return out;
        }
    }

    /**
     * The books (issue #134): the stock the run's own records put on hand, per branch and item, from the stock ledger: the
     * movements of the purchases, sales and usage reports the run registered, and the stock movements it registered
     * (adjustments, returns and legacy balances), each item valued at its current cost as the opening journal values it.
     * Stock held before the import or moved after it is not the run's.
     */
    @Override
    public ImportBooks books() {
        return new ImportBooks() {
            @Override
            public List<ImportForecast.ProductStock> stock(Scope scope) {
                List<UUID> movements = scope.of("retail.stock_movement");
                List<UUID> documents = new ArrayList<>(scope.of("retail.purchase"));
                documents.addAll(scope.of("retail.sale"));
                documents.addAll(scope.of("retail.usage"));
                if (movements.isEmpty() && documents.isEmpty()) {
                    return List.of();
                }
                return jdbc.sql("""
                                SELECT b.code, p.code, sum(m.qty), p.cost_minor
                                  FROM retail_stock_movements m
                                  JOIN branches b ON b.id = m.branch_id
                                  JOIN retail_products p ON p.id = m.product_id
                                 WHERE m.id = ANY(?) OR m.source_id = ANY(?)
                                 GROUP BY b.code, p.code, p.cost_minor
                                 ORDER BY b.code, p.code
                                """)
                        .params(movements.toArray(UUID[]::new), documents.toArray(UUID[]::new))
                        .query((rs, n) -> {
                            BigDecimal q = rs.getBigDecimal(3);
                            return new ImportForecast.ProductStock(
                                    compare(rs.getString(1)),
                                    compare(rs.getString(2)),
                                    q,
                                    BigDecimal.valueOf(Quantities.value(q, rs.getLong(4))));
                        })
                        .list();
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // The mapping of the normalised export (the import-retail command's).
    // ---------------------------------------------------------------------------------------------

    /**
     * The mapping of the normalised export onto {@code retail_v1}: each JSON Lines file of the export directory is the
     * source of the entity set of its name, every field to itself, dates in ISO 8601 in the business's zone. The history
     * files are known by their {@code source_ref}, the others by position. The command records it on its batches; a JSON
     * Lines upload of an export with it keys its history rows as the command does, so the two recognise each other's rows.
     */
    static Map<String, Object> exportMapping(ZoneId zone) {
        Map<String, Object> sources = new LinkedHashMap<>();
        for (EntitySet set : ENTITY_SETS) {
            Map<String, Object> columns = new LinkedHashMap<>();
            set.fields().forEach(f -> columns.put(f.name(), Map.of("field", f.name())));
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("entity_set", set.key());
            source.put("file", set.key() + ".jsonl");
            source.put("header_row", "auto");
            source.put("columns", columns);
            source.put(
                    "source_ref",
                    BY_REFERENCE.contains(set.key())
                            ? Map.of("rule", "column", "column", "source_ref")
                            : Map.of("rule", "row_hash"));
            sources.put(set.key(), source);
        }
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("template", KEY);
        mapping.put("template_version", VERSION);
        mapping.put("date_convention", "iso");
        mapping.put("time_zone", zone.getId());
        mapping.put("currency", "UGX");
        mapping.put("sources", sources);
        return mapping;
    }
}
