package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.tenancy.BranchProvisioning;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
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

    static final Map<String, Set<String>> FIELDS = Map.of(
            "branches.jsonl", Set.of("code", "name"),
            "categories.jsonl", Set.of("name"),
            "units.jsonl", Set.of("name"),
            "products.jsonl", Set.of("code", "description", "category", "unit", "cost_minor", "sell_minor", "active"),
            "suppliers.jsonl", Set.of("name"),
            "customers.jsonl", Set.of("name", "contact"),
            "balances.jsonl", Set.of("branch", "product_code", "qty"),
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
                            "source_user"),
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
                            "source_user"),
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
                            "source_user"));

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
            RetailBooks books) {
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
    }

    /**
     * Imports the export directory into the tenant with this slug.
     *
     * @throws IllegalArgumentException for an unknown tenant, one without retail, or a missing
     *     directory; nothing is written
     */
    public ImportReport run(String tenantSlug, Path dir, boolean dryRun) {
        if (!dir.toFile().isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }
        Map<String, ExportFile> export = new LinkedHashMap<>();
        for (String name : FILES) {
            export.put(name, ExportFile.read(dir, name, FIELDS.get(name)));
        }
        ImportReport report = new ImportReport(tenantSlug, dir.toString(), dryRun);
        return tenants.callAsTenant(tenantSlug, "retail", () -> {
            if (dryRun) {
                transactions.executeWithoutResult(status -> {
                    importAll(export, report);
                    status.setRollbackOnly();
                });
            } else {
                importAll(export, report);
            }
            return report;
        });
    }

    private void importAll(Map<String, ExportFile> export, ImportReport report) {
        Run run = new Run(export, report, UUID.randomUUID());
        for (String name : FILES) {
            ExportFile file = export.get(name);
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

        Run(Map<String, ExportFile> export, ImportReport report, UUID id) {
            this.export = export;
            this.report = report;
            this.id = id;
        }

        void begin() {
            zone = currentTenant.profile().timezone();
            branchByCode = new HashMap<>();
            branches.all().forEach(b -> branchByCode.put(b.code().toUpperCase(Locale.ROOT), b.id()));
            productByCode = catalogue.productsByCode();
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
            Run reader = new Run(export, quiet, id);
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
