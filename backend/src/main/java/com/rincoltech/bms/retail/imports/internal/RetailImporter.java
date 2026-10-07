package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.tenancy.BranchProvisioning;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.cashbook.CashBookHistory;
import com.rincoltech.bms.retail.cashbook.CashBookHistory.ExpenseItem;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory.Ensured;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory.Product;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.imports.internal.ExportFile.Row;
import com.rincoltech.bms.retail.imports.internal.ExportFile.RowProblem;
import com.rincoltech.bms.retail.purchasing.PurchaseHistory;
import com.rincoltech.bms.retail.sales.SaleHistory;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailHistory;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.HistoricalMovement;
import com.rincoltech.bms.retail.stock.StockLedger.Position;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code import-retail} command's work (FR-RET-12; ADR-020 decision 9; data dictionary rules
 * 1 to 6). Binds the tenant by slug through {@link TenantJobs}, then imports the files in
 * dependency order, each in its own transaction, through the retail modules' history ports. A dry
 * run wraps every file in one outer transaction that is rolled back, so it reads and writes
 * exactly as a real run and leaves nothing behind. A file that fails stops the run; the files
 * before it stay committed, and a re-run skips what they wrote.
 */
@Service
public class RetailImporter {

    static final String SOURCE_TYPE = "retail.import";
    static final String OPENING = "retail.import_opening";

    static final List<String> FILES = List.of(
            "branches.jsonl",
            "categories.jsonl",
            "units.jsonl",
            "products.jsonl",
            "suppliers.jsonl",
            "customers.jsonl",
            "purchases.jsonl",
            "sales.jsonl",
            "usage.jsonl",
            "balances.jsonl");

    /**
     * The optional cash book files (ADR-022 decision 18; data dictionary section 5), in the order the
     * report lists them. An export without them imports exactly as before.
     */
    static final List<String> CASH_FILES = List.of(
            "cash_parties.jsonl",
            "expense_categories.jsonl",
            "savings.jsonl",
            "expenses.jsonl",
            "banking.jsonl",
            "withdrawals.jsonl",
            "advances.jsonl",
            "advance_payments.jsonl",
            "cash_balances.jsonl");

    /**
     * The order the cash book files are written in: banking after everything its expected amount is
     * computed from (savings, expenses, advances and their payments of the same day), and the
     * opening journal last, when every outstanding advance is known.
     */
    static final List<String> CASH_ORDER = List.of(
            "cash_parties.jsonl",
            "expense_categories.jsonl",
            "savings.jsonl",
            "expenses.jsonl",
            "withdrawals.jsonl",
            "advances.jsonl",
            "advance_payments.jsonl",
            "banking.jsonl",
            "cash_balances.jsonl");

    static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            Map.entry("branches.jsonl", Set.of("code", "name")),
            Map.entry("categories.jsonl", Set.of("name")),
            Map.entry("units.jsonl", Set.of("name")),
            Map.entry(
                    "products.jsonl",
                    Set.of("code", "description", "category", "unit", "cost_minor", "sell_minor", "active")),
            Map.entry("suppliers.jsonl", Set.of("name")),
            Map.entry("customers.jsonl", Set.of("name", "contact")),
            Map.entry("balances.jsonl", Set.of("branch", "product_code", "qty")),
            Map.entry("cash_parties.jsonl", Set.of("name", "contact", "kind")),
            Map.entry("expense_categories.jsonl", Set.of("category", "item", "requires_explanation")),
            Map.entry(
                    "savings.jsonl",
                    Set.of("source_ref", "branch", "business_date", "amount_minor", "total_sold_minor", "source_user")),
            Map.entry(
                    "expenses.jsonl",
                    Set.of(
                            "source_ref",
                            "branch",
                            "business_date",
                            "category",
                            "item",
                            "beneficiary",
                            "amount_minor",
                            "explanation",
                            "source_user")),
            Map.entry(
                    "banking.jsonl",
                    Set.of("source_ref", "branch", "business_date", "amount_minor", "banked_at", "source_user")),
            Map.entry(
                    "withdrawals.jsonl",
                    Set.of("source_ref", "branch", "business_date", "amount_minor", "source_user")),
            Map.entry(
                    "advances.jsonl",
                    Set.of(
                            "source_ref",
                            "branch",
                            "party",
                            "taken_by",
                            "principal_minor",
                            "purpose",
                            "business_date",
                            "processing_fee_minor",
                            "source_user")),
            Map.entry(
                    "advance_payments.jsonl",
                    Set.of("source_ref", "advance_ref", "amount_minor", "method", "paid_on", "source_user")),
            Map.entry(
                    "cash_balances.jsonl",
                    Set.of("branch", "cash_on_hand_minor", "bank_minor", "savings_reserve_minor")),
            Map.entry(
                    "sales.jsonl",
                    Set.of(
                            "source_ref",
                            "branch",
                            "product_code",
                            "qty",
                            "unit_price_minor",
                            "unit_cost_minor",
                            "payment_method",
                            "buyer",
                            "buyer_contact",
                            "due_date",
                            "sold_at",
                            "source_user")),
            Map.entry(
                    "purchases.jsonl",
                    Set.of(
                            "source_ref",
                            "product_code",
                            "supplier",
                            "kind",
                            "unit_cost_minor",
                            "unit_sell_minor",
                            "qty_by_branch",
                            "purchased_on",
                            "source_user")),
            Map.entry(
                    "usage.jsonl",
                    Set.of(
                            "source_ref",
                            "branch",
                            "product_code",
                            "kind",
                            "reason",
                            "qty",
                            "unit_cost_minor",
                            "reported_at",
                            "source_user")));

    private final TenantJobs tenants;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final Branches branches;
    private final BranchProvisioning branchProvisioning;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final CatalogueHistory catalogue;
    private final PurchaseHistory purchases;
    private final SaleHistory sales;
    private final RetailHistory history;
    private final StockLedger stock;
    private final RetailBooks books;
    private final CashBookHistory cashBook;

    RetailImporter(
            TenantJobs tenants,
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            Branches branches,
            BranchProvisioning branchProvisioning,
            CurrentTenant currentTenant,
            BusinessClock clock,
            AuditLog audit,
            CatalogueHistory catalogue,
            PurchaseHistory purchases,
            SaleHistory sales,
            RetailHistory history,
            StockLedger stock,
            RetailBooks books,
            CashBookHistory cashBook) {
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.branches = branches;
        this.branchProvisioning = branchProvisioning;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.audit = audit;
        this.catalogue = catalogue;
        this.purchases = purchases;
        this.sales = sales;
        this.history = history;
        this.stock = stock;
        this.books = books;
        this.cashBook = cashBook;
    }

    /**
     * Imports the export directory into the tenant with this slug.
     *
     * @throws IllegalArgumentException for an unknown tenant, one without retail, or a missing
     *     directory; nothing is written
     */
    public ImportReport run(String tenantSlug, Path dir, boolean dryRun) {
        return run(tenantSlug, dir, dryRun, null);
    }

    /**
     * As {@link #run(String, Path, boolean)}, with the first live day of the cash book: its opening
     * journals are dated the day before it. {@code null} means the import day in the tenant's zone.
     */
    public ImportReport run(String tenantSlug, Path dir, boolean dryRun, LocalDate firstLiveDate) {
        if (!dir.toFile().isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }
        Map<String, ExportFile> export = new LinkedHashMap<>();
        for (String name : FILES) {
            export.put(name, ExportFile.read(dir, name, FIELDS.get(name)));
        }
        for (String name : CASH_FILES) {
            export.put(name, ExportFile.read(dir, name, FIELDS.get(name)));
        }
        ImportReport report = new ImportReport(tenantSlug, dir.toString(), dryRun);
        return tenants.callAsTenant(tenantSlug, "retail", () -> {
            if (dryRun) {
                transactions.executeWithoutResult(status -> {
                    importAll(export, report, firstLiveDate);
                    status.setRollbackOnly();
                });
            } else {
                importAll(export, report, firstLiveDate);
            }
            return report;
        });
    }

    private void importAll(Map<String, ExportFile> export, ImportReport report, LocalDate firstLiveDate) {
        Run run = new Run(export, report, UUID.randomUUID(), firstLiveDate);
        List<String> order = new ArrayList<>(FILES);
        order.addAll(CASH_ORDER);
        // The report lists the files in the export's order; an absent cash book file is not listed.
        for (String name : FILES) {
            report.file(name);
        }
        for (String name : CASH_FILES) {
            if (export.get(name).present) {
                report.file(name);
            }
        }
        for (String name : order) {
            ExportFile file = export.get(name);
            if (!file.present && CASH_FILES.contains(name)) {
                continue;
            }
            ImportReport.Counts counts = report.file(name);
            counts.read = file.rows.size() + file.unreadable.size();
            if (!file.present) {
                report.anomaly(name, 0, "file not found; treated as empty");
            }
            file.unreadable.forEach(p -> report.skip(name, p.line, p.getMessage()));
            if (!file.unknownFields.isEmpty()) {
                report.anomaly(name, 0, "fields not in the export format, ignored: " + file.unknownFields);
            }
            try {
                transactions.executeWithoutResult(status -> {
                    run.begin();
                    switch (name) {
                        case "branches.jsonl" -> run.branches(file);
                        case "categories.jsonl" ->
                            run.named(file, n -> catalogue.ensureCategory(n).created(), 100, run.namedCategories);
                        case "units.jsonl" ->
                            run.named(file, n -> catalogue.ensureUnit(n).created(), 30, run.namedUnits);
                        case "products.jsonl" -> run.products(file);
                        case "suppliers.jsonl" ->
                            run.named(file, n -> purchases.ensureSupplier(n).created(), 200, run.namedSuppliers);
                        case "customers.jsonl" -> run.customers(file);
                        case "purchases.jsonl" -> run.purchases(file);
                        case "sales.jsonl" -> run.sales(file);
                        case "usage.jsonl" -> run.usage(file);
                        case "balances.jsonl" -> run.balances(file);
                        case "cash_parties.jsonl" -> run.cashParties(file);
                        case "expense_categories.jsonl" -> run.expenseCategories(file);
                        case "savings.jsonl" -> run.savings(file);
                        case "expenses.jsonl" -> run.expenses(file);
                        case "banking.jsonl" -> run.banking(file);
                        case "withdrawals.jsonl" -> run.withdrawals(file);
                        case "advances.jsonl" -> run.advances(file);
                        case "advance_payments.jsonl" -> run.advancePayments(file);
                        case "cash_balances.jsonl" -> run.cashBalances(file);
                        default -> throw new IllegalStateException(name);
                    }
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("file", name);
                    after.put("written", counts.written);
                    after.put("existing", counts.existing);
                    after.put("skipped", counts.skipped);
                    audit.record(
                            AuditLog.Entry.created("retail.import.file_imported", "retail.import", run.id, null, after),
                            null,
                            "system");
                });
            } catch (RuntimeException e) {
                report.fail(name + ": " + e.getMessage());
                return;
            }
        }
        transactions.executeWithoutResult(status -> {
            run.begin();
            run.checksum(export.get("balances.jsonl"));
        });
        run.cashBookNotes();
    }

    /** The state of one run: lookups refreshed at the start of each file's transaction. */
    private final class Run {

        final Map<String, ExportFile> export;
        final ImportReport report;
        final UUID id;
        final Set<String> namedCategories = new HashSet<>();
        final Set<String> namedUnits = new HashSet<>();
        final Set<String> namedSuppliers = new HashSet<>();
        ZoneId zone;
        Map<String, UUID> branchByCode = Map.of();
        Map<String, Product> productByCode = Map.of();
        long creditSales;
        long creditSalesMinor;

        final LocalDate firstLiveDate;
        UUID headOffice;
        LocalDate today;
        int createdCategories;
        int createdItems;
        int createdParties;
        int feeAdvances;
        long feeMinor;
        final Set<String> companyNames = new HashSet<>();

        Run(Map<String, ExportFile> export, ImportReport report, UUID id, LocalDate firstLiveDate) {
            this.export = export;
            this.report = report;
            this.id = id;
            this.firstLiveDate = firstLiveDate;
        }

        void begin() {
            zone = currentTenant.profile().timezone();
            branchByCode = new HashMap<>();
            branches.all().forEach(b -> branchByCode.put(b.code().toUpperCase(Locale.ROOT), b.id()));
            productByCode = catalogue.productsByCode();
            today = clock.today(zone);
            List<Branches.Branch> heads =
                    branches.all().stream().filter(Branches.Branch::headOffice).toList();
            headOffice = heads.size() == 1 ? heads.getFirst().id() : null;
        }

        UUID branch(Row row, String field, String code) {
            if (code == null) {
                throw row.problem(field + " is missing");
            }
            UUID id = branchByCode.get(code.strip().toUpperCase(Locale.ROOT));
            if (id == null) {
                throw row.problem("unknown branch code " + code);
            }
            return id;
        }

        Product product(Row row, String code) {
            if (code == null) {
                throw row.problem("product_code is missing");
            }
            Product p = productByCode.get(codeKey(row, code));
            if (p == null) {
                throw row.problem("unknown product code " + code);
            }
            return p;
        }

        /**
         * The catalogue's normal form of a product code (review F9), lower-cased for matching, so
         * an imported code can never differ from a catalogue code by Unicode whitespace or a
         * compatibility form.
         */
        String codeKey(Row row, String code) {
            return normalisedCode(row, code).toLowerCase(Locale.ROOT);
        }

        String normalisedCode(Row row, String code) {
            return RetailCatalogue.normaliseCode(code)
                    .orElseThrow(() -> row.problem(
                            "product code " + code.strip() + " holds a control character or a special space"));
        }

        /** Runs each row; a {@link RowProblem} is reported and the row skipped. */
        void each(ExportFile file, Consumer<Row> work) {
            for (Row row : file.rows) {
                try {
                    work.accept(row);
                } catch (RowProblem p) {
                    report.skip(file.name, p.line, p.getMessage());
                }
            }
        }

        // ---- Reference files -------------------------------------------------------------

        void branches(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> seen = new HashSet<>();
            each(file, row -> {
                String code = row.requiredText("code", 10).toUpperCase(Locale.ROOT);
                String name = row.requiredText("name", 200);
                if (!code.matches("^[A-Z0-9]{2,10}$")) {
                    throw row.problem("branch code " + code + " is not 2 to 10 letters or digits");
                }
                if (!seen.add(code)) {
                    throw row.problem("duplicate branch code " + code);
                }
                if (branchByCode.containsKey(code)) {
                    c.existing++;
                    return;
                }
                branchByCode.put(code, branchProvisioning.create(code, name));
                c.written++;
            });
        }

        /** A file of names (categories, units, suppliers): created when no name matches ignoring case. */
        void named(ExportFile file, Predicate<String> create, int maxLength, Set<String> names) {
            ImportReport.Counts c = report.file(file.name);
            each(file, row -> {
                String name = row.requiredText("name", maxLength);
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw row.problem("duplicate name " + name);
                }
                if (create.test(name)) {
                    c.written++;
                } else {
                    c.existing++;
                }
            });
        }

        /** Data dictionary rule 1: a code is matched trimmed and ignoring case; the first row wins. */
        void products(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Map<String, Integer> firstLine = new HashMap<>();
            each(file, row -> {
                // The length is checked on the normal form, which NFKC can make longer (issue #73).
                String code = normalisedCode(row, row.requiredText("code", Integer.MAX_VALUE));
                if (code.codePointCount(0, code.length()) > 40) {
                    throw row.problem("code is longer than 40 characters after normalisation");
                }
                String key = code.toLowerCase(Locale.ROOT);
                Integer first = firstLine.putIfAbsent(key, row.line());
                if (first != null) {
                    throw row.problem("duplicate product code " + code + " (same as line " + first
                            + " ignoring case and spaces)");
                }
                String description = row.requiredText("description", 300);
                String category = row.requiredText("category", 100);
                String unit = row.requiredText("unit", 30);
                long cost = row.money("cost_minor");
                long sell = row.money("sell_minor");
                boolean active = row.flag("active", true);
                if (!namedCategories.contains(category.toLowerCase(Locale.ROOT))) {
                    report.anomaly(file.name, row.line(), "category " + category + " is not in categories.jsonl");
                }
                if (!namedUnits.contains(unit.toLowerCase(Locale.ROOT))) {
                    report.anomaly(file.name, row.line(), "unit " + unit + " is not in units.jsonl");
                }
                Ensured product = catalogue.ensureProduct(
                        code,
                        description,
                        catalogue.ensureCategory(category).id(),
                        catalogue.ensureUnit(unit).id(),
                        cost,
                        sell,
                        active);
                if (product.created()) {
                    c.written++;
                } else {
                    c.existing++;
                    if (product.changed()) {
                        report.anomaly(
                                file.name,
                                row.line(),
                                "product " + code + " existed with other prices; its prices were left unchanged");
                    }
                }
            });
        }

        void customers(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> seen = new HashSet<>();
            each(file, row -> {
                String name = row.requiredText("name", 200);
                String contact = row.optionalText("contact", 100);
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw row.problem("duplicate credit buyer name");
                }
                if (sales.ensureCustomer(name, contact).created()) {
                    c.written++;
                } else {
                    c.existing++;
                }
            });
        }

        // ---- History files ---------------------------------------------------------------

        /** True when the reference was imported before (or earlier in this file): the row is skipped. */
        boolean seen(String file, Row row, String ref, Set<String> inFile) {
            if (!inFile.add(ref)) {
                throw row.problem("duplicate source_ref " + ref);
            }
            return jdbc.sql("SELECT count(*) FROM retail_import_refs WHERE source_file = ? AND source_ref = ?")
                            .params(file, ref)
                            .query(Long.class)
                            .single()
                    > 0;
        }

        void remember(String file, String ref, String targetType, UUID targetId, String sourceUser) {
            jdbc.sql("""
                            INSERT INTO retail_import_refs (tenant_id, source_file, source_ref, target_type, target_id,
                                source_user)
                            VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                            """).params(file, ref, targetType, targetId, sourceUser).update();
        }

        record PurchaseRow(
                Row row,
                String ref,
                String kind,
                Product product,
                String supplier,
                long cost,
                Long sell,
                Map<UUID, BigDecimal> qtyByBranch,
                LocalDate on,
                Instant at,
                String user) {}

        /**
         * Restocks, and the stock-takes and returns the source recorded as restocks (data
         * dictionary section 3). Price history rows are written where consecutive purchases of a
         * product changed its cost or sell price.
         */
        void purchases(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            List<PurchaseRow> rows = new ArrayList<>();
            each(file, row -> rows.add(parsePurchase(row)));
            Map<PurchaseRow, PurchaseRow> previous = new HashMap<>();
            Map<UUID, PurchaseRow> last = new HashMap<>();
            rows.stream()
                    .filter(r -> r.kind().equals("purchase"))
                    .sorted(Comparator.comparing(PurchaseRow::at)
                            .thenComparing(r -> r.row().line()))
                    .forEach(r -> {
                        PurchaseRow before = last.put(r.product().id(), r);
                        if (before != null) {
                            previous.put(r, before);
                        }
                    });
            Set<String> refs = new HashSet<>();
            for (PurchaseRow r : rows) {
                try {
                    if (seen("purchases", r.row(), r.ref(), refs)) {
                        c.existing++;
                        continue;
                    }
                } catch (RowProblem p) {
                    report.skip(file.name, p.line, p.getMessage());
                    continue;
                }
                UUID target;
                String targetType;
                if (r.kind().equals("purchase")) {
                    UUID supplier = null;
                    if (r.supplier() != null) {
                        PurchaseHistory.Ensured s = purchases.ensureSupplier(r.supplier());
                        if (s.created() || !namedSuppliers.contains(r.supplier().toLowerCase(Locale.ROOT))) {
                            report.anomaly(file.name, r.row().line(), "supplier is not in suppliers.jsonl; created");
                            namedSuppliers.add(r.supplier().toLowerCase(Locale.ROOT));
                        }
                        supplier = s.id();
                    }
                    target = purchases.importPurchase(
                            supplier,
                            r.product().id(),
                            r.cost(),
                            r.sell(),
                            r.qtyByBranch(),
                            r.on(),
                            r.at(),
                            "Imported " + r.ref());
                    targetType = "retail.purchase";
                    PurchaseRow before = previous.get(r);
                    if (before != null) {
                        long oldSell = before.sell() == null ? r.product().sellMinor() : before.sell();
                        long newSell = r.sell() == null ? oldSell : r.sell();
                        if (before.cost() != r.cost() || oldSell != newSell) {
                            catalogue.importPriceChange(
                                    r.product().id(),
                                    before.cost(),
                                    r.cost(),
                                    oldSell,
                                    newSell,
                                    target,
                                    r.at(),
                                    "Imported restock " + r.ref());
                        }
                    }
                } else {
                    List<HistoricalMovement> moves = new ArrayList<>();
                    r.qtyByBranch()
                            .forEach((branch, qty) -> moves.add(new HistoricalMovement(
                                    branch,
                                    r.product().id(),
                                    r.kind(),
                                    qty,
                                    r.cost(),
                                    r.at(),
                                    SOURCE_TYPE,
                                    null,
                                    null,
                                    "Imported " + r.ref())));
                    target = stock.recordHistorical(r.on(), moves).getFirst();
                    targetType = "retail.stock_movement";
                }
                remember("purchases", r.ref(), targetType, target, r.user());
                c.written++;
            }
        }

        private PurchaseRow parsePurchase(Row row) {
            String ref = row.requiredText("source_ref", 100);
            String kind = row.oneOf("kind", List.of("purchase", "adjustment", "return"));
            Product product = product(row, row.text("product_code"));
            long cost = row.money("unit_cost_minor");
            Long sell = row.optionalMoney("unit_sell_minor");
            Map<UUID, BigDecimal> qty = new TreeMap<>();
            for (Map.Entry<String, BigDecimal> e :
                    row.qtyByBranch("qty_by_branch").entrySet()) {
                UUID branch = branch(row, "qty_by_branch", e.getKey());
                BigDecimal q = e.getValue();
                if (q.signum() == 0) {
                    continue;
                }
                if (q.signum() < 0 && !kind.equals("adjustment")) {
                    throw row.problem("a " + kind + " quantity must be positive, branch " + e.getKey());
                }
                if (qty.putIfAbsent(branch, q) != null) {
                    throw row.problem("branch " + e.getKey() + " appears twice in qty_by_branch");
                }
            }
            if (qty.isEmpty()) {
                throw row.problem("no quantity in qty_by_branch");
            }
            Instant at = row.instant("purchased_on", zone);
            return new PurchaseRow(
                    row,
                    ref,
                    kind,
                    product,
                    kind.equals("purchase") ? row.optionalText("supplier", 200) : null,
                    cost,
                    sell,
                    qty,
                    LocalDate.ofInstant(at, zone),
                    at,
                    row.optionalText("source_user", 200));
        }

        void sales(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> refs = new HashSet<>();
            each(file, row -> {
                String ref = row.requiredText("source_ref", 100);
                if (seen("sales", row, ref, refs)) {
                    c.existing++;
                    return;
                }
                UUID branch = branch(row, "branch", row.text("branch"));
                Product product = product(row, row.text("product_code"));
                BigDecimal qty = row.qty("qty");
                if (qty.signum() <= 0) {
                    throw row.problem("qty must be positive");
                }
                long price = row.money("unit_price_minor");
                long cost = row.money("unit_cost_minor");
                String method = row.oneOf("payment_method", List.of("cash", "credit"));
                String buyer = row.optionalText("buyer", 200);
                String contact = row.optionalText("buyer_contact", 100);
                boolean credit = method.equals("credit");
                if (credit && buyer == null) {
                    throw row.problem("a credit sale needs a buyer");
                }
                LocalDate due = credit ? row.optionalDate("due_date", zone) : null;
                Instant at = row.instant("sold_at", zone);
                UUID customer = buyer == null ? null : sales.findCustomer(buyer).orElse(null);
                if (credit && customer == null) {
                    report.anomaly(file.name, row.line(), "credit buyer is not in customers.jsonl; kept as entered");
                }
                UUID sale = sales.importSale(new SaleHistory.Sale(
                        branch,
                        product.id(),
                        qty,
                        price,
                        cost,
                        method,
                        customer,
                        buyer,
                        contact,
                        due,
                        at,
                        LocalDate.ofInstant(at, zone)));
                if (credit) {
                    creditSales++;
                    creditSalesMinor = Math.addExact(creditSalesMinor, Quantities.value(qty, price));
                }
                remember("sales", ref, "retail.sale", sale, row.optionalText("source_user", 200));
                c.written++;
            });
            if (creditSales > 0) {
                report.note("credit sales imported unpaid (the source keeps no payments): " + creditSales + ", total "
                        + creditSalesMinor + " minor units; no receivable is journalled for them");
            }
        }

        void usage(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> refs = new HashSet<>();
            each(file, row -> {
                String ref = row.requiredText("source_ref", 100);
                if (seen("usage", row, ref, refs)) {
                    c.existing++;
                    return;
                }
                UUID branch = branch(row, "branch", row.text("branch"));
                Product product = product(row, row.text("product_code"));
                String kind = row.oneOf("kind", List.of("used", "damaged"));
                String reason = row.requiredText("reason", 300);
                BigDecimal qty = row.qty("qty");
                if (qty.signum() <= 0) {
                    throw row.problem("qty must be positive");
                }
                long cost = row.money("unit_cost_minor");
                Instant at = row.instant("reported_at", zone);
                UUID report = history.importUsage(
                        branch, product.id(), kind, reason, qty, cost, at, LocalDate.ofInstant(at, zone));
                remember("usage", ref, "retail.usage", report, row.optionalText("source_user", 200));
                c.written++;
            });
        }

        // ---- Cash book files (ADR-022 decision 18) ---------------------------------------

        final Set<String> listedItems = new HashSet<>();

        /** A required business date: ISO 8601, never in the future. */
        LocalDate date(Row row, String field) {
            LocalDate d = LocalDate.ofInstant(row.instant(field, zone), zone);
            if (d.isAfter(today)) {
                throw row.problem(field + " " + d + " is in the future");
            }
            return d;
        }

        Instant dayStart(LocalDate date) {
            return date.atStartOfDay(zone).toInstant();
        }

        /** Money above zero, as the cash book tables require. */
        long positive(Row row, String field) {
            long v = row.money(field);
            if (v <= 0) {
                throw row.problem(field + " must be above zero");
            }
            return v;
        }

        /** The row's branch, or the tenant's single head office when it names none, whatever any scope is. */
        UUID branchOrHead(Row row, String field) {
            String code = row.text(field);
            if (code != null) {
                return branch(row, field, code);
            }
            if (headOffice == null) {
                throw row.problem(field + " is missing and the tenant has no single head office branch");
            }
            return headOffice;
        }

        void cashParties(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> seen = new HashSet<>();
            each(file, row -> {
                String name = row.requiredText("name", 200);
                String contact = row.optionalText("contact", 100);
                String kind =
                        row.oneOf("kind", List.of("owner", "staff", "related_entity", "supplier", "other", "company"));
                if (!seen.add(kind + "|" + name.toLowerCase(Locale.ROOT))) {
                    throw row.problem("duplicate party " + name + " of kind " + kind);
                }
                if (kind.equals("company")) {
                    companyNames.add(name.toLowerCase(Locale.ROOT));
                    throw row.problem("party " + name
                            + " is marked as the company, which is not a party (ADR-022 open question 9); not mapped");
                }
                if (cashBook.ensureParty(kind, name, contact).created()) {
                    c.written++;
                } else {
                    c.existing++;
                }
            });
        }

        void expenseCategories(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> seen = new HashSet<>();
            each(file, row -> {
                String category = row.requiredText("category", 100);
                String item = row.requiredText("item", 100);
                boolean requires = row.flag("requires_explanation", false);
                String key = category.toLowerCase(Locale.ROOT) + "|" + item.toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    throw row.problem("duplicate item " + item + " of category " + category);
                }
                ExpenseItem e = cashBook.ensureExpenseItem(category, item, requires);
                listedItems.add(key);
                if (e.itemCreated() || e.categoryCreated()) {
                    c.written++;
                } else {
                    c.existing++;
                }
            });
        }

        void savings(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> refs = new HashSet<>();
            each(file, row -> {
                String ref = row.requiredText("source_ref", 100);
                if (seen("savings", row, ref, refs)) {
                    c.existing++;
                    return;
                }
                UUID branch = branch(row, "branch", row.text("branch"));
                LocalDate date = date(row, "business_date");
                long amount = row.money("amount_minor");
                Long sold = row.optionalMoney("total_sold_minor");
                UUID id = cashBook.importSavings(branch, date, amount, sold == null ? 0 : sold, dayStart(date))
                        .orElseThrow(() -> row.problem("branch " + row.text("branch")
                                + " already has a savings record for " + date + "; a second one is never merged"));
                remember("savings", ref, "retail.savings", id, row.optionalText("source_user", 200));
                c.written++;
            });
        }

        /** The party a name stands for in an expense or an advance; created as kind other when unknown. */
        UUID partyByName(ExportFile file, Row row, String field, String name) {
            if (companyNames.contains(name.toLowerCase(Locale.ROOT))) {
                report.anomaly(
                        file.name,
                        row.line(),
                        field + " " + name + " is the company, which is not a party; imported without it");
                return null;
            }
            Optional<CashBookHistory.Party> found = cashBook.findParty(name, List.of());
            if (found.isPresent()) {
                return found.get().id();
            }
            createdParties++;
            report.anomaly(
                    file.name, row.line(), field + " " + name + " is not in cash_parties.jsonl; created as kind other");
            return cashBook.ensureParty("other", name, null).id();
        }

        void expenses(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> refs = new HashSet<>();
            each(file, row -> {
                String ref = row.requiredText("source_ref", 100);
                if (seen("expenses", row, ref, refs)) {
                    c.existing++;
                    return;
                }
                UUID branch = branch(row, "branch", row.text("branch"));
                LocalDate date = date(row, "business_date");
                String category = row.requiredText("category", 100);
                String item = row.requiredText("item", 100);
                String beneficiary = row.optionalText("beneficiary", 200);
                long amount = positive(row, "amount_minor");
                String explanation = row.optionalText("explanation", 500);
                ExpenseItem e = cashBook.ensureExpenseItem(category, item, false);
                if (e.categoryCreated()) {
                    createdCategories++;
                    report.anomaly(
                            file.name,
                            row.line(),
                            "category " + category + " is not in expense_categories.jsonl; created as written");
                }
                if (e.itemCreated()) {
                    createdItems++;
                    report.anomaly(
                            file.name,
                            row.line(),
                            "item " + item + " of category " + category
                                    + " is not in expense_categories.jsonl; created as written");
                }
                if (e.requiresExplanation() && explanation == null) {
                    throw row.problem("item " + e.itemName() + " requires an explanation");
                }
                UUID party = beneficiary == null ? null : partyByName(file, row, "beneficiary", beneficiary);
                UUID id = cashBook.importExpense(
                        new CashBookHistory.Expense(branch, date, e, party, amount, explanation, dayStart(date)));
                remember("expenses", ref, "retail.expense", id, row.optionalText("source_user", 200));
                c.written++;
            });
        }

        void withdrawals(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> refs = new HashSet<>();
            each(file, row -> {
                String ref = row.requiredText("source_ref", 100);
                if (seen("withdrawals", row, ref, refs)) {
                    c.existing++;
                    return;
                }
                UUID branch = branchOrHead(row, "branch");
                LocalDate date = date(row, "business_date");
                long amount = positive(row, "amount_minor");
                UUID id = cashBook.importWithdrawal(branch, date, amount, dayStart(date));
                remember("withdrawals", ref, "retail.withdrawal", id, row.optionalText("source_user", 200));
                c.written++;
            });
        }

        record AdvanceRow(
                Row row,
                String ref,
                UUID branch,
                LocalDate date,
                UUID party,
                String takenBy,
                long principal,
                String purpose,
                long fee) {}

        /**
         * Advances in business date then source id order, each taking the next live advance number;
         * the source id stays in the import reference and the note, never in {@code advance_no}.
         */
        void advances(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            List<AdvanceRow> rows = new ArrayList<>();
            each(file, row -> rows.add(parseAdvance(row)));
            rows.sort(Comparator.comparing(AdvanceRow::date).thenComparing(AdvanceRow::ref));
            Set<String> refs = new HashSet<>();
            for (AdvanceRow a : rows) {
                try {
                    if (seen("advances", a.row(), a.ref(), refs)) {
                        c.existing++;
                        continue;
                    }
                    UUID takenBy = a.takenBy() == null ? null : partyByName(file, a.row(), "taken_by", a.takenBy());
                    String note = "Imported " + a.ref()
                            + (a.fee() > 0 ? "; processing fee " + a.fee() + " minor units, not modelled" : "");
                    CashBookHistory.Advanced advance = cashBook.importAdvance(new CashBookHistory.Advance(
                            a.branch(),
                            a.date(),
                            a.party(),
                            takenBy,
                            a.principal(),
                            a.purpose(),
                            note,
                            dayStart(a.date())));
                    if (a.fee() > 0) {
                        feeAdvances++;
                        feeMinor = Math.addExact(feeMinor, a.fee());
                        report.anomaly(
                                file.name,
                                a.row().line(),
                                "processing fee " + a.fee() + " is not modelled; kept in the note of "
                                        + advance.advanceNo());
                    }
                    remember(
                            "advances",
                            a.ref(),
                            "retail.advance",
                            advance.id(),
                            a.row().optionalText("source_user", 200));
                    c.written++;
                } catch (RowProblem p) {
                    report.skip(file.name, p.line, p.getMessage());
                }
            }
        }

        private AdvanceRow parseAdvance(Row row) {
            String ref = row.requiredText("source_ref", 100);
            UUID branch = branch(row, "branch", row.text("branch"));
            String partyName = row.requiredText("party", 200);
            Optional<CashBookHistory.Party> party = cashBook.findParty(partyName, List.of());
            if (party.isEmpty()) {
                throw row.problem(
                        companyNames.contains(partyName.toLowerCase(Locale.ROOT))
                                ? "party " + partyName + " is marked as the company, which is not a party; not mapped"
                                : "unknown party " + partyName + " (not in cash_parties.jsonl)");
            }
            if (!List.of("owner", "staff", "related_entity")
                    .contains(party.get().kind())) {
                throw row.problem("party " + partyName + " is of kind "
                        + party.get().kind() + "; an advance goes to the owner, a staff member or a related entity");
            }
            long principal = positive(row, "principal_minor");
            LocalDate date = date(row, "business_date");
            Long fee = row.optionalMoney("processing_fee_minor");
            return new AdvanceRow(
                    row,
                    ref,
                    branch,
                    date,
                    party.get().id(),
                    row.optionalText("taken_by", 200),
                    principal,
                    row.optionalText("purpose", 300),
                    fee == null ? 0 : fee);
        }

        record PaymentRow(Row row, String ref, String advanceRef, long amount, String method, LocalDate paidOn) {}

        void advancePayments(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            List<PaymentRow> rows = new ArrayList<>();
            each(
                    file,
                    row -> rows.add(new PaymentRow(
                            row,
                            row.requiredText("source_ref", 100),
                            row.requiredText("advance_ref", 100),
                            positive(row, "amount_minor"),
                            row.oneOf("method", List.of("cash", "mobile_money", "bank")),
                            date(row, "paid_on"))));
            rows.sort(Comparator.comparing(PaymentRow::paidOn)
                    .thenComparingInt(r -> r.row().line()));
            Set<String> refs = new HashSet<>();
            for (PaymentRow p : rows) {
                try {
                    if (seen("advance_payments", p.row(), p.ref(), refs)) {
                        c.existing++;
                        continue;
                    }
                    UUID advance = jdbc.sql("""
                                    SELECT target_id FROM retail_import_refs
                                     WHERE source_file = 'advances' AND source_ref = ?
                                    """)
                            .param(p.advanceRef())
                            .query(UUID.class)
                            .optional()
                            .orElseThrow(() -> p.row().problem("unknown advance_ref " + p.advanceRef()));
                    UUID id = cashBook.importRepayment(
                                    advance, p.amount(), p.method(), p.paidOn(), dayStart(p.paidOn()))
                            .orElseThrow(() -> p.row()
                                    .problem("amount " + p.amount() + " is above the remaining principal of advance "
                                            + p.advanceRef()));
                    remember(
                            "advance_payments",
                            p.ref(),
                            "retail.advance_repayment",
                            id,
                            p.row().optionalText("source_user", 200));
                    c.written++;
                } catch (RowProblem problem) {
                    report.skip(file.name, problem.line, problem.getMessage());
                }
            }
        }

        /** Banking after savings, expenses, advances and payments: its expected amount is computed from them. */
        void banking(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            Set<String> refs = new HashSet<>();
            each(file, row -> {
                String ref = row.requiredText("source_ref", 100);
                if (seen("banking", row, ref, refs)) {
                    c.existing++;
                    return;
                }
                UUID branch = branch(row, "branch", row.text("branch"));
                LocalDate date = date(row, "business_date");
                long amount = positive(row, "amount_minor");
                Instant bankedAt = row.text("banked_at") == null ? dayStart(date) : row.instant("banked_at", zone);
                if (LocalDate.ofInstant(bankedAt, zone).isAfter(today)) {
                    throw row.problem("banked_at is in the future");
                }
                CashBookHistory.Banked banked = cashBook.importBanking(branch, date, amount, bankedAt);
                remember("banking", ref, "retail.banking", banked.id(), row.optionalText("source_user", 200));
                c.written++;
            });
        }

        /**
         * One balanced opening entry per branch with a row, dated the day before the first live day,
         * recorded in {@code retail_import_refs} under {@code cash_opening} with the branch id: the
         * cash book's read model finds the first live day from exactly that.
         */
        void cashBalances(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            LocalDate day = (firstLiveDate == null ? today : firstLiveDate).minusDays(1);
            Set<UUID> branchesDone = new HashSet<>();
            each(file, row -> {
                UUID branch = branchOrHead(row, "branch");
                Long cash = row.optionalMoney("cash_on_hand_minor");
                Long bank = row.optionalMoney("bank_minor");
                Long savings = row.optionalMoney("savings_reserve_minor");
                String code = codeOf(branch);
                if (!branchesDone.add(branch)) {
                    throw row.problem("a second cash balance row for branch " + code);
                }
                String posted = jdbc.sql("""
                                SELECT j.entry_no FROM retail_import_refs r JOIN journal_entries j ON j.id = r.target_id
                                 WHERE r.source_file = 'cash_opening' AND r.source_ref = ?
                                """)
                        .param(branch.toString())
                        .query(String.class)
                        .optional()
                        .orElse(null);
                if (posted != null) {
                    report.cashOpening(new ImportReport.CashOpening(
                            code, day, 0, 0, 0, 0, 0, posted, "already posted, not posted again:"));
                    c.existing++;
                    return;
                }
                Optional<CashBookHistory.OpeningPosted> entry = cashBook.postOpening(
                        branch, day, cash == null ? 0 : cash, bank == null ? 0 : bank, savings == null ? 0 : savings);
                if (entry.isEmpty()) {
                    report.cashOpening(new ImportReport.CashOpening(code, day, 0, 0, 0, 0, 0, null, "nothing to post"));
                    c.existing++;
                    return;
                }
                CashBookHistory.OpeningPosted e = entry.get();
                remember("cash_opening", branch.toString(), "journal", e.entryId(), null);
                report.cashOpening(new ImportReport.CashOpening(
                        code,
                        day,
                        e.cashMinor(),
                        e.bankMinor(),
                        e.savingsMinor(),
                        e.advances(),
                        e.advancesMinor(),
                        e.entryNo(),
                        "posted"));
                c.written++;
            });
        }

        /** What the report says about names the cash book reference files did not list and fees not modelled. */
        void cashBookNotes() {
            if (createdCategories + createdItems + createdParties > 0) {
                report.note("cash book lists extended by rows the reference files did not list: " + createdCategories
                        + " categories, " + createdItems + " items, " + createdParties
                        + " parties; created exactly as written, for the owner to review");
            }
            if (feeAdvances > 0) {
                report.note("advance processing fees are not modelled; kept in the advance's note: " + feeAdvances
                        + " advances, total " + feeMinor + " minor units");
            }
        }

        // ---- Balances, legacy movements and the opening journals -------------------------

        record Balance(Row row, String branchCode, UUID branch, Product product, BigDecimal qty) {}

        /**
         * Data dictionary rules 4 and 5. Each balance row gets one {@code legacy_balance} movement
         * that makes the derived balance equal the source's current quantity, dated before the
         * imported history. Each branch gets one opening journal for its positive balances at the
         * product's current cost; negative balances are listed for the first stock-take.
         */
        void balances(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            List<Balance> rows = parseBalances(file);
            Map<String, Position> positions = new HashMap<>();
            stock.positions().forEach(p -> positions.put(p.branchId() + "|" + p.productId(), p));
            Instant opening = openingInstant();
            // The legacy balance and the opening journal carry the import date in the tenant's
            // zone, so a valuation as_of an earlier date reads the imported history alone, on the
            // same basis as the inventory account (review F4).
            LocalDate importDate = clock.today(zone);
            Set<String> covered = new HashSet<>();
            for (Balance b : rows) {
                String key = b.branch() + "|" + b.product().id();
                covered.add(key);
                Position p = positions.get(key);
                BigDecimal balance = p == null ? BigDecimal.ZERO : p.balance();
                if (b.qty().signum() < 0) {
                    report.negative(b.branchCode() + " " + b.product().code() + " " + Quantities.format(b.qty()));
                }
                if (p != null && p.legacy()) {
                    c.existing++;
                    if (balance.compareTo(b.qty()) != 0) {
                        report.anomaly(
                                file.name,
                                b.row().line(),
                                "legacy balance already written; the balance is "
                                        + Quantities.format(balance) + " and the source says "
                                        + Quantities.format(b.qty())
                                        + "; correct it with a stock-take");
                    }
                    continue;
                }
                if (p != null && p.historicalQty().compareTo(balance) != 0) {
                    report.anomaly(
                            file.name,
                            b.row().line(),
                            "branch " + b.branchCode() + " product "
                                    + b.product().code() + " has movements recorded outside the import");
                }
                BigDecimal legacy = b.qty().subtract(balance);
                if (legacy.signum() == 0) {
                    // Nothing to write: the history already gives the source balance, on every run.
                    c.existing++;
                } else {
                    stock.recordHistorical(
                            importDate,
                            List.of(new HistoricalMovement(
                                    b.branch(),
                                    b.product().id(),
                                    "legacy_balance",
                                    legacy,
                                    b.product().costMinor(),
                                    opening,
                                    SOURCE_TYPE,
                                    null,
                                    null,
                                    "Legacy balance from the import")));
                    c.written++;
                }
            }
            for (Position p : positions.values()) {
                if (!covered.contains(p.branchId() + "|" + p.productId())
                        && p.balance().signum() != 0) {
                    report.anomaly(
                            file.name,
                            0,
                            "no balance row for branch " + codeOf(p.branchId()) + " product "
                                    + productCode(p.productId()) + "; its derived balance "
                                    + Quantities.format(p.balance())
                                    + " is kept");
                }
            }
            openingJournals(rows, importDate);
        }

        private List<Balance> parseBalances(ExportFile file) {
            List<Balance> rows = new ArrayList<>();
            Map<String, Integer> firstLine = new HashMap<>();
            each(file, row -> {
                String code = row.text("branch");
                UUID branch = branch(row, "branch", code);
                Product product = product(row, row.text("product_code"));
                BigDecimal qty = row.qty("qty");
                Integer first = firstLine.putIfAbsent(branch + "|" + product.id(), row.line());
                if (first != null) {
                    throw row.problem("duplicate balance for branch " + code + " product " + product.code()
                            + " (same as line " + first + ")");
                }
                rows.add(new Balance(row, code.strip().toUpperCase(Locale.ROOT), branch, product, qty));
            });
            return rows;
        }

        private void openingJournals(List<Balance> rows, LocalDate today) {
            Map<String, long[]> byBranch = new TreeMap<>();
            Map<String, UUID> ids = new HashMap<>();
            for (Balance b : rows) {
                long[] v = byBranch.computeIfAbsent(b.branchCode(), k -> new long[1]);
                ids.put(b.branchCode(), b.branch());
                if (b.qty().signum() > 0) {
                    v[0] = Math.addExact(
                            v[0], Quantities.value(b.qty(), b.product().costMinor()));
                }
            }
            byBranch.forEach((code, v) -> {
                String posted =
                        jdbc.sql("""
                                SELECT j.entry_no FROM retail_import_refs r JOIN journal_entries j ON j.id = r.target_id
                                 WHERE r.source_file = 'opening' AND r.source_ref = ?
                                """).param(code).query(String.class).optional().orElse(null);
                if (posted != null) {
                    report.opening(new ImportReport.Opening(code, v[0], posted, "already posted, not posted again:"));
                    return;
                }
                UUID branch = ids.get(code);
                var entry = books.post(new Posting(
                        branch,
                        today,
                        "Opening stock",
                        "Opening stock at current cost, from the import",
                        OPENING,
                        null,
                        OPENING + ":" + branch,
                        List.of(Leg.debit("inventory", v[0]), Leg.credit("opening_balance_equity", v[0]))));
                if (entry.isEmpty()) {
                    report.opening(new ImportReport.Opening(code, 0, null, "nothing to post"));
                    return;
                }
                remember("opening", code, "core.journal_entry", entry.get().entryId(), null);
                report.opening(new ImportReport.Opening(code, v[0], entry.get().entryNo(), "posted"));
            });
        }

        /** One second before the earliest imported history, so the legacy balance precedes it. */
        private Instant openingInstant() {
            Instant earliest = null;
            for (String name : List.of("sales.jsonl", "purchases.jsonl", "usage.jsonl")) {
                String field = switch (name) {
                    case "sales.jsonl" -> "sold_at";
                    case "purchases.jsonl" -> "purchased_on";
                    default -> "reported_at";
                };
                for (Row row : export.get(name).rows) {
                    try {
                        Instant at = row.instant(field, zone);
                        earliest = earliest == null || at.isBefore(earliest) ? at : earliest;
                    } catch (RowProblem ignored) {
                        // reported with its file
                    }
                }
            }
            return earliest == null ? clock.now() : earliest.minusSeconds(1);
        }

        private String codeOf(UUID branchId) {
            return branchByCode.entrySet().stream()
                    .filter(e -> e.getValue().equals(branchId))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(branchId.toString());
        }

        private String productCode(UUID productId) {
            return productByCode.values().stream()
                    .filter(p -> p.id().equals(productId))
                    .map(Product::code)
                    .findFirst()
                    .orElse(productId.toString());
        }

        /**
         * SHA-256 over the sorted lines {@code BRANCH|product code|qty} of the balances file, and over
         * the same lines with the derived balance after the import; equal digests mean every
         * balance matches the source exactly.
         */
        void checksum(ExportFile file) {
            List<Balance> rows = new ArrayList<>();
            ImportReport quiet = new ImportReport("", "", true);
            Run reader = new Run(export, quiet, id, null);
            reader.begin();
            reader.each(file, row -> {
                try {
                    rows.add(new Balance(
                            row,
                            row.text("branch").strip().toUpperCase(Locale.ROOT),
                            reader.branch(row, "branch", row.text("branch")),
                            reader.product(row, row.text("product_code")),
                            row.qty("qty")));
                } catch (RuntimeException e) {
                    // skipped rows are already in the report
                }
            });
            Map<String, BigDecimal> derived = new HashMap<>();
            stock.positions().forEach(p -> derived.put(p.branchId() + "|" + p.productId(), p.balance()));
            List<String> source = new ArrayList<>();
            List<String> after = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            BigDecimal total = BigDecimal.ZERO;
            for (Balance b : rows) {
                String key = b.branch() + "|" + b.product().id();
                if (!keys.add(key)) {
                    continue;
                }
                String prefix = b.branchCode() + "|" + b.product().code().toLowerCase(Locale.ROOT) + "|";
                source.add(prefix + Quantities.format(b.qty()));
                after.add(prefix + Quantities.format(derived.getOrDefault(key, BigDecimal.ZERO.setScale(3))));
                total = total.add(b.qty());
            }
            String s = sha256(source);
            String d = sha256(after);
            report.checksum("balances checksum: rows=" + source.size() + " total_qty=" + Quantities.format(total)
                    + " source_sha256=" + s + " derived_sha256=" + d + " match=" + (s.equals(d) ? "yes" : "NO"));
        }
    }

    static String sha256(List<String> lines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            lines.stream().sorted().forEach(l -> digest.update((l + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
