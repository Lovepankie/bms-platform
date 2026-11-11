package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportCommandRuns;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportTemplate.Policy;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.tenancy.BranchProvisioning;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CompactKeys;
import com.rincoltech.bms.retail.cashbook.CashBookHistory;
import com.rincoltech.bms.retail.cashbook.CashBookHistory.ExpenseItem;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory.Product;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.imports.internal.ExportFile.Row;
import com.rincoltech.bms.retail.imports.internal.ExportFile.RowProblem;
import com.rincoltech.bms.retail.purchasing.PurchaseHistory;
import com.rincoltech.bms.retail.sales.SaleHistory;
import com.rincoltech.bms.retail.stock.Quantities;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code import-retail} command's work (FR-RET-12; ADR-020 decision 9; data dictionary rules
 * 1 to 6), and since issue #135 the loader of the canonical template {@code retail_v1}
 * ({@link RetailV1Template}): one code for the command's export and for a business's sheets read
 * by the import framework with a mapping.
 *
 * <p>The command binds the tenant by slug through {@link TenantJobs}, reads the export directory,
 * and writes each file in dependency order as one batch of a run of the framework
 * ({@link ImportCommandRuns}), each in its own transaction, through the retail modules' history
 * ports: every record registered in {@code import_refs} (and, until the contract step, in
 * {@code retail_import_refs}), the opening stock journal posted through the framework. A dry run
 * wraps every file in one outer transaction that is rolled back, so it reads and writes exactly as
 * a real run and leaves nothing behind, the run included. A file that fails stops the run; the
 * files before it stay committed, and a re-run skips what they wrote.
 */
@Service
public class RetailImporter {

    static final String SOURCE_TYPE = "retail.import";

    /** The business's name of the export's mapping (issue #135), stored in {@code import_mappings}. */
    static final String MAPPING = "import-retail-export";

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

    private static final Logger log = LoggerFactory.getLogger(RetailImporter.class);

    /**
     * The rows read and written at a time (issue #880): a page of the checkpoint's rows, and of the items a file makes. Small
     * enough that a page's objects die young on the staging heap, so the old generation holds no page.
     */
    static final int PAGE = 250;

    /** The note of an imported restock before its {@code source_ref}, which issue #881 reads back as its tiebreak. */
    static final String IMPORTED = "Imported ";

    /**
     * Restocks of an item in time order (issue #881): by date, then by {@code source_ref} as UTF-8 bytes, which the books
     * hold as well as the batch, so every split of the history over runs orders them as one run does.
     */
    static final Comparator<Run.Restock> IN_TIME = Comparator.comparing(Run.Restock::at)
            .thenComparing(
                    Run.Restock::ref,
                    (a, b) -> Arrays.compareUnsigned(
                            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));

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
    private final CashBookHistory cashBook;
    private final ImportCommandRuns commandRuns;
    private final ImportMappings mappings;

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
            CashBookHistory cashBook,
            ImportCommandRuns commandRuns,
            ImportMappings mappings) {
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
        this.cashBook = cashBook;
        this.commandRuns = commandRuns;
        this.mappings = mappings;
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
        Map<String, ExportFile> export = readExport(dir);
        ImportReport report = new ImportReport(tenantSlug, dir.toString(), dryRun);
        return tenants.callAsTenant(tenantSlug, "retail", () -> {
            if (dryRun) {
                transactions.executeWithoutResult(status -> {
                    importAll(export, report, firstLiveDate, true);
                    status.setRollbackOnly();
                });
            } else {
                importAll(export, report, firstLiveDate, false);
            }
            return report;
        });
    }

    // JUSTIFICATION-A3: issue #135 makes this command's code the loader of the retail_v1 template, no second path.
    /**
     * The loader of {@code retail_v1} for one entity set (issue #135): the batch's staged rows, read through the checkpoint
     * in line order, written by this command's own code for the export file of the same name, as if the command read that
     * file ({@link ExportFile#staged}). It keeps nothing from one batch to the next: what depends on the run's earlier
     * batches (the stock the run put on hand, what it already journalled) it reads from the database, from the run's own
     * records in {@code import_refs}. Every record it creates is registered there with the row's {@code source_ref}, and
     * a row the business's imports already hold is counted existing and written again for nothing. It tells the batch's
     * report the file's counts, its anomalies, the negative balances, the notes and, for the balances, the stock per
     * branch and the checksum; the opening journals the framework tells as it posts them.
     */
    BatchWriter loader(String entitySet) {
        if (!RetailV1Template.SETS.contains(entitySet)) {
            throw new IllegalArgumentException("retail_v1 has no entity set " + entitySet);
        }
        String name = entitySet + ".jsonl";
        // A reference or balance row the business's imports already hold with this content was written by an earlier
        // run: counted existing, written again for nothing. A history row goes through its file's code, which counts it
        // existing in its place.
        boolean history = RetailV1Template.BY_REFERENCE.contains(entitySet);
        // Issue #880: a reference set is written a page at a time, holding one page. History and balances need the
        // whole batch at once (a restock's price history reads every row in time order; one opening journal per branch
        // from every balance row), so their pages are gathered into one file first, each row's values held once.
        boolean whole = history || entitySet.equals("balances");
        return new BatchWriter() {
            @Override
            public Map<String, Object> write(UUID runId, UUID batchId, int seq, Checkpoint checkpoint) {
                ImportReport report = new ImportReport("", "", false);
                StagedRefs refs = new StagedRefs(checkpoint);
                Run run = new Run(Map.of(), report, runId, null, false);
                run.attach(checkpoint, batchId, refs);
                run.begin();
                ImportReport.Counts counts = report.file(name);
                ExportFile file = ExportFile.staged(name, List.of());
                int after = 0;
                while (true) {
                    List<ImportRefs.StagedRow> page = checkpoint.rows(after, PAGE);
                    if (page.isEmpty()) {
                        break;
                    }
                    after = page.getLast().line();
                    List<Map.Entry<Integer, Map<String, Object>>> toWrite = new ArrayList<>(page.size());
                    for (ImportRefs.StagedRow r : page) {
                        counts.read++;
                        if (!history && r.status() == ImportRefs.Status.EXISTING) {
                            counts.existing++;
                            continue;
                        }
                        refs.hold(r);
                        toWrite.add(Map.entry(r.line(), r.values()));
                    }
                    if (whole) {
                        file.addStaged(toWrite);
                    } else if (!toWrite.isEmpty()) {
                        run.write(name, ExportFile.staged(name, toWrite));
                        refs.flush();
                    }
                }
                if (whole) {
                    run.write(name, file);
                    refs.flush();
                }
                if (name.equals("balances.jsonl")) {
                    run.checksum(file);
                }
                report.intoFile(checkpoint, name);
                report.intoTail(checkpoint);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("read", counts.read);
                out.put("written", counts.written);
                out.put("existing", counts.existing);
                out.put("skipped", counts.skipped);
                return out;
            }

            /** The template, its version, the entity set and this class: what the dry run rehearsed (issue #132). */
            @Override
            public String identity() {
                return RetailV1Template.KEY + "_v" + RetailV1Template.VERSION + " " + entitySet + " "
                        + RetailImporter.class.getName();
            }
        };
    }

    /** Every file of the export directory, read once; an absent file is an empty one. */
    private static Map<String, ExportFile> readExport(Path dir) {
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
        return export;
    }

    // JUSTIFICATION-A3: issue #135 runs the command on a run of the framework; opening it is the one new step.
    /** The command's run of the framework and the export's mapping its batches record. */
    private record CommandRun(UUID id, UUID mapping) {}

    /** No person stored the export's mapping: the command runs on the host, as the platform operator's tool. */
    static final UUID NO_PERSON = new UUID(0, 0);

    /**
     * Opens the command's run (issue #135) with the export's mapping, stored once per business under {@link #MAPPING} and
     * reused while it is the same document. A refusal of the framework (another import committing, the kill switch) is
     * the command's refusal: nothing is written.
     */
    private CommandRun openCommand(boolean dryRun) {
        try {
            return transactions.execute(s -> {
                Map<String, Object> document =
                        RetailV1Template.exportMapping(currentTenant.profile().timezone());
                String hash = ImportRefs.contentHash(document);
                UUID mapping = mappings.latest(MAPPING)
                        .filter(m -> ImportRefs.contentHash(m.document()).equals(hash))
                        .map(ImportMappings.Mapping::id)
                        .orElseGet(() -> mappings.save(
                                        MAPPING,
                                        document,
                                        NO_PERSON,
                                        "the normalised export of the import-retail command")
                                .id());
                return new CommandRun(commandRuns.open(dryRun), mapping);
            });
        } catch (ApiException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private void importAll(
            Map<String, ExportFile> export, ImportReport report, LocalDate firstLiveDate, boolean dryRun) {
        CommandRun command = openCommand(dryRun);
        Run run = new Run(export, report, command.id(), firstLiveDate, true);
        // Refuse a first live day that moves the one already fixed, before anything is written.
        try {
            transactions.executeWithoutResult(status -> run.begin());
        } catch (RuntimeException e) {
            stopped(command, dryRun);
            throw e;
        }
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
        int batches = 0;
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
            int seq = ++batches;
            try {
                // One batch of the command's run per file (issue #135): the retail_v1 files record the export's
                // mapping and its source of their entity set; the cash book files, outside retail_v1, none.
                transactions.executeWithoutResult(status -> {
                    run.begin();
                    String set = name.substring(0, name.length() - ".jsonl".length());
                    boolean mapped = RetailV1Template.SETS.contains(set);
                    UUID batch = commandRuns.batch(
                            command.id(), seq, name, mapped ? command.mapping() : null, mapped ? set : null);
                    commandRuns.write(command.id(), batch, checkpoint -> {
                        run.attach(checkpoint, batch, new CommandRefs(checkpoint));
                        run.write(name, file);
                        Map<String, Object> after = new LinkedHashMap<>();
                        after.put("file", name);
                        after.put("written", counts.written);
                        after.put("existing", counts.existing);
                        after.put("skipped", counts.skipped);
                        audit.record(
                                AuditLog.Entry.created(
                                        "retail.import.file_imported", "retail.import", run.id, null, after),
                                null,
                                "system");
                        report.intoFile(checkpoint, name);
                        return after;
                    });
                });
            } catch (RuntimeException e) {
                report.fail(name + ": " + e.getMessage());
                stopped(command, dryRun);
                return;
            }
        }
        if (export.get("advances.jsonl").present || export.get("cash_balances.jsonl").present) {
            int seq = ++batches;
            try {
                transactions.executeWithoutResult(status -> {
                    run.begin();
                    UUID batch = commandRuns.batch(command.id(), seq, "advance openings", null, null);
                    commandRuns.write(command.id(), batch, checkpoint -> {
                        run.attach(checkpoint, batch, new CommandRefs(checkpoint));
                        run.advanceOpenings();
                        return Map.of();
                    });
                });
            } catch (RuntimeException e) {
                report.fail("advance openings: " + e.getMessage());
                stopped(command, dryRun);
                return;
            }
        }
        try {
            transactions.executeWithoutResult(status -> {
                run.begin();
                run.checksum(export.get("balances.jsonl"));
                commandRuns.finish(command.id(), false);
            });
        } catch (RuntimeException e) {
            // The run's end is reported as a file's failure is (re-review of 0bb283a, nice-to-have 5).
            report.fail("finish: " + e.getMessage());
            stopped(command, dryRun);
            return;
        }
        run.cashBookNotes();
    }

    /**
     * Ends the command's run a file stopped. A dry run is rolled back whole, run and all, and its transaction may be
     * aborted by the failure, so nothing more is sent to it.
     */
    private void stopped(CommandRun command, boolean dryRun) {
        if (dryRun) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> commandRuns.finish(command.id(), true));
        } catch (RuntimeException e) {
            // The failure that stopped the file is the one reported; a run that cannot be ended now is cancelled by
            // the next command once its lease lapses (review of 97cb1be, finding 2).
            log.warn("import-retail could not end run {}: {}", command.id(), e.getMessage());
        }
    }

    /** One file's work in the batch the run is attached to: the command's and the loader's alike. */
    private void writeFile(Run run, String name, ExportFile file) {
        switch (name) {
            case "branches.jsonl" -> run.branches(file);
            case "categories.jsonl" ->
                run.named(file, n -> made(catalogue.ensureCategory(n)), 100, run.namedCategories, "retail.category");
            case "units.jsonl" -> {
                // Issue #698: every unit key of the file locked first, in key order, as in the CSV import.
                catalogue.lockUnits(texts(file, "name"));
                run.named(file, n -> made(catalogue.ensureUnit(n)), 30, run.namedUnits, "retail.unit");
            }
            case "products.jsonl" -> {
                catalogue.lockUnits(texts(file, "unit"));
                run.products(file);
            }
            case "suppliers.jsonl" ->
                run.named(file, n -> made(purchases.ensureSupplier(n)), 200, run.namedSuppliers, "retail.supplier");
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
    }

    /** A record a file's row found or made: its id and whether this row made it. */
    record Made(UUID id, boolean created) {}

    static Made made(CatalogueHistory.Ensured e) {
        return new Made(e.id(), e.created());
    }

    static Made made(PurchaseHistory.Ensured e) {
        return new Made(e.id(), e.created());
    }

    private static final java.util.regex.Pattern TRAILING_NUMBER = java.util.regex.Pattern.compile("^(.*?)(\\d+)$");

    /** Source ids in natural order: the same prefix, then the trailing number as a number (ADV-9 before ADV-10). */
    /** The text of one field on every row that has it as text; a row where it is not text is reported by its own read. */
    static List<String> texts(ExportFile file, String field) {
        List<String> out = new ArrayList<>();
        for (ExportFile.Row row : file.rows) {
            try {
                String s = row.text(field);
                if (s != null) {
                    out.add(s);
                }
            } catch (RuntimeException e) {
                // Left to the row's own read, which reports it in its place.
            }
        }
        return out;
    }

    static int naturalOrder(String a, String b) {
        java.util.regex.Matcher x = TRAILING_NUMBER.matcher(a);
        java.util.regex.Matcher y = TRAILING_NUMBER.matcher(b);
        if (x.matches() && y.matches() && x.group(1).equals(y.group(1))) {
            int byValue = new BigDecimal(x.group(2)).compareTo(new BigDecimal(y.group(2)));
            if (byValue != 0) {
                return byValue;
            }
        }
        return a.compareTo(b);
    }

    /** The state of one run: lookups refreshed at the start of each file's transaction. */
    private final class Run {

        final Map<String, ExportFile> export;
        final ImportReport report;
        final UUID id;
        final Set<String> namedCategories = new HashSet<>();
        final Set<String> namedUnits = new HashSet<>();
        final Set<String> namedSuppliers = new HashSet<>();

        /**
         * One file's own state across the pages a batch writes it in (issue #880), cleared with each file's lookups: the
         * branch codes and credit buyers seen, the line of each item code, and the categories and units ensured by the
         * name a row gave, so each is asked of the catalogue once.
         */
        final Set<String> branchCodes = new HashSet<>();

        final Set<String> buyerNames = new HashSet<>();
        CompactKeys itemLines = new CompactKeys();
        final Map<String, UUID> categoryIds = new HashMap<>();
        final Map<String, UUID> unitIds = new HashMap<>();

        ZoneId zone;
        Map<String, UUID> branchByCode = Map.of();
        /** Branches that keep no goods (issue #329): a balance for one is refused, never written. */
        Set<UUID> noGoods = Set.of();

        Map<String, Product> productByCode = Map.of();
        long creditSales;
        long creditSalesMinor;

        final LocalDate requestedFirstLive;
        LocalDate firstLiveDate;
        UUID headOffice;
        LocalDate today;
        int createdCategories;
        int createdItems;
        int createdParties;
        int feeAdvances;
        long feeMinor;
        final Set<String> companyNames = new HashSet<>();

        // JUSTIFICATION-A3: issue #135 runs one Run for the command and for the framework's batch of retail_v1.
        /**
         * The command, which reads the whole export and knows which names its reference files listed, or the framework's
         * loader of one batch, which knows only its own rows and asks the catalogue (issue #135).
         */
        final boolean command;

        /** The batch being written and the framework's side of it (issue #135); set by {@link #attach}. */
        BatchWriter.Checkpoint checkpoint;

        UUID batchId;
        Refs refs;

        Run(Map<String, ExportFile> export, ImportReport report, UUID id, LocalDate firstLiveDate, boolean command) {
            this.export = export;
            this.report = report;
            this.id = id;
            this.requestedFirstLive = firstLiveDate;
            this.firstLiveDate = firstLiveDate;
            this.command = command;
        }

        /** The batch the next file is written in, and how its rows are known to the business's imports. */
        void attach(BatchWriter.Checkpoint checkpoint, UUID batchId, Refs refs) {
            this.checkpoint = checkpoint;
            this.batchId = batchId;
            this.refs = refs;
        }

        void write(String name, ExportFile file) {
            writeFile(this, name, file);
        }

        /**
         * Whether a name a row uses was listed: by the reference file the command read before (the command), or held by
         * the catalogue before this row (a batch of the framework, which keeps nothing from an earlier batch). A name the
         * framework's row makes is registered on that row (review of 97cb1be, finding 5).
         */
        // JUSTIFICATION-A3: the review asks every record a framework batch writes registered; this is that one case.
        boolean listed(
                Set<String> names,
                String name,
                java.util.function.Supplier<Made> ensure,
                String file,
                Row row,
                String targetType) {
            if (command) {
                return names.contains(name.toLowerCase(Locale.ROOT));
            }
            Made made = ensure.get();
            if (made.created()) {
                refs.created(file, row, targetType, made.id());
            }
            return !made.created();
        }

        void begin() {
            branchCodes.clear();
            buyerNames.clear();
            itemLines = new CompactKeys();
            categoryIds.clear();
            unitIds.clear();
            zone = currentTenant.profile().timezone();
            branchByCode = new HashMap<>();
            branches.all().forEach(b -> branchByCode.put(b.code().toUpperCase(Locale.ROOT), b.id()));
            noGoods = branches.all().stream()
                    .filter(b -> !b.stocksGoods())
                    .map(Branches.Branch::id)
                    .collect(java.util.stream.Collectors.toSet());
            productByCode = catalogue.productsByCode();
            today = clock.today(zone);
            // The first live day is fixed by the first run: a later run reads it back from the opening
            // entries and refuses a different one, so a rerun cannot move it.
            LocalDate opened = jdbc.sql("""
                            SELECT max(entry_date) FROM journal_entries
                             WHERE source_type IN ('retail.cash_opening', 'retail.advance_opening')
                            """)
                    .query(java.sql.Date.class)
                    .optional()
                    .map(java.sql.Date::toLocalDate)
                    .orElse(null);
            if (opened != null) {
                LocalDate stored = opened.plusDays(1);
                if (requestedFirstLive != null && !requestedFirstLive.equals(stored)) {
                    throw new IllegalArgumentException("--first-live-date " + requestedFirstLive
                            + " differs from the first live day " + stored
                            + " already fixed by this tenant's opening entries; omit the flag or use that date");
                }
                firstLiveDate = stored;
            }
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
                if (checkpoint != null) {
                    checkpoint.rowWritten();
                }
            }
        }

        // ---- Reference files -------------------------------------------------------------

        void branches(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            each(file, row -> {
                String code = row.requiredText("code", 10).toUpperCase(Locale.ROOT);
                String name = row.requiredText("name", 200);
                if (!code.matches("^[A-Z0-9]{2,10}$")) {
                    throw row.problem("branch code " + code + " is not 2 to 10 letters or digits");
                }
                if (!branchCodes.add(code)) {
                    throw row.problem("duplicate branch code " + code);
                }
                if (branchByCode.containsKey(code)) {
                    refs.matched(file.name, row, "core.branch", branchByCode.get(code));
                    c.existing++;
                    return;
                }
                UUID created = branchProvisioning.create(code, name);
                branchByCode.put(code, created);
                refs.created(file.name, row, "core.branch", created);
                c.written++;
            });
        }

        /** A file of names (categories, units, suppliers): created when no name matches ignoring case. */
        void named(
                ExportFile file, Function<String, Made> create, int maxLength, Set<String> names, String targetType) {
            ImportReport.Counts c = report.file(file.name);
            each(file, row -> {
                String name = row.requiredText("name", maxLength);
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw row.problem("duplicate name " + name);
                }
                Made made = create.apply(name);
                if (made.created()) {
                    refs.created(file.name, row, targetType, made.id());
                    c.written++;
                } else {
                    refs.matched(file.name, row, targetType, made.id());
                    c.existing++;
                }
            });
        }

        // JUSTIFICATION-A3: issue #880 writes the items a page at a time: a read per row, then one write per page.
        /**
         * Data dictionary rule 1: a code is matched trimmed and ignoring case; the first row wins. Since issue #880 a page of
         * rows is read first, each item's code looked up in the products this file's lookups hold, then the page's new items
         * are made in one statement batch and their twins of the same name found in one query, and each row is reported in
         * line order as before. The lookup by {@code lower(code)} that row-level security keeps from its index made each
         * row scan every item of the business, so the batch took four times as long for twice the rows.
         */
        void products(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            for (int from = 0; from < file.rows.size(); from += PAGE) {
                productsPage(file.name, c, file.rows.subList(from, Math.min(file.rows.size(), from + PAGE)));
            }
        }

        /** An item row once read: why it is skipped, or what its category and unit said and what it makes or matches. */
        private record ItemRow(
                Row row,
                RowProblem problem,
                String code,
                List<String> anomalies,
                CatalogueHistory.NewProduct made,
                Product held,
                boolean otherPrices) {}

        private void productsPage(String name, ImportReport.Counts c, List<Row> rows) {
            List<ItemRow> items = new ArrayList<>(rows.size());
            for (Row row : rows) {
                try {
                    items.add(item(name, row));
                } catch (RowProblem p) {
                    items.add(new ItemRow(row, p, null, List.of(), null, null, false));
                }
                if (checkpoint != null) {
                    checkpoint.rowWritten();
                }
            }
            List<CatalogueHistory.NewProduct> made = items.stream()
                    .map(ItemRow::made)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            catalogue.createProducts(made);
            Map<UUID, Integer> madeAt = new HashMap<>();
            items.stream()
                    .filter(i -> i.made() != null)
                    .forEach(i -> madeAt.put(i.made().id(), i.row().line()));
            Map<UUID, List<Product>> twins = catalogue.sameName(madeAt.keySet());
            for (ItemRow i : items) {
                int line = i.row().line();
                if (i.problem() != null) {
                    report.skip(name, i.problem().line, i.problem().getMessage());
                    continue;
                }
                i.anomalies().forEach(a -> report.anomaly(name, line, a));
                String code = i.code();
                if (i.made() != null) {
                    refs.created(name, i.row(), "retail.product", i.made().id());
                    c.written++;
                    // Issue #352: a name is kept once per unit. History needs every code, so both rows are kept; the
                    // report tells the owner which items to look at. An item a later line of the page made did not
                    // exist yet when this row was written.
                    twins.getOrDefault(i.made().id(), List.of()).stream()
                            .filter(o -> madeAt.getOrDefault(o.id(), 0) < line)
                            .findFirst()
                            .ifPresent(other -> report.anomaly(
                                    name,
                                    line,
                                    "product " + code + " has the same name as product " + other.code()
                                            + " in the same unit; both were kept, check whether they are one item"));
                } else {
                    refs.matched(name, i.row(), "retail.product", i.held().id());
                    c.existing++;
                    if (i.otherPrices()) {
                        report.anomaly(
                                name,
                                line,
                                "product " + code + " existed with other prices; its prices were left unchanged");
                    }
                }
            }
        }

        /** Reads one item row; a product of its code that this file's lookups hold is matched, never changed (#73). */
        private ItemRow item(String name, Row row) {
            // The length is checked on the normal form, which NFKC can make longer (issue #73).
            String code = normalisedCode(row, row.requiredText("code", Integer.MAX_VALUE));
            if (code.codePointCount(0, code.length()) > 40) {
                throw row.problem("code is longer than 40 characters after normalisation");
            }
            String key = code.toLowerCase(Locale.ROOT);
            Long first = itemLines.putIfAbsent(key, row.line());
            if (first != null) {
                throw row.problem(
                        "duplicate product code " + code + " (same as line " + first + " ignoring case and spaces)");
            }
            String description = row.requiredText("description", 300);
            String category = row.requiredText("category", 100);
            String unit = row.requiredText("unit", 30);
            long cost = row.money("cost_minor");
            long sell = row.money("sell_minor");
            boolean active = row.flag("active", true);
            List<String> anomalies = new ArrayList<>(2);
            if (!listed(namedCategories, category, () -> category(category), name, row, "retail.category")) {
                anomalies.add("category " + category + " is not in categories.jsonl");
            }
            if (!listed(namedUnits, unit, () -> unit(unit), name, row, "retail.unit")) {
                anomalies.add("unit " + unit + " is not in units.jsonl");
            }
            UUID categoryId = category(category).id();
            UUID unitId = unit(unit).id();
            Product held = productByCode.get(key);
            if (held != null) {
                boolean other = held.costMinor() != cost || held.sellMinor() != sell;
                return new ItemRow(row, null, code, anomalies, null, held, other);
            }
            return new ItemRow(
                    row,
                    null,
                    code,
                    anomalies,
                    new CatalogueHistory.NewProduct(
                            UUID.randomUUID(), code, description, categoryId, unitId, cost, sell, active),
                    null,
                    false);
        }

        /** The category of this name, asked of the catalogue once per file: a later row finds it held. */
        Made category(String name) {
            UUID id = categoryIds.get(name);
            if (id != null) {
                return new Made(id, false);
            }
            Made made = made(catalogue.ensureCategory(name));
            categoryIds.put(name, made.id());
            return made;
        }

        /** The unit of this name, as {@link #category}; its key's lock is held from the first ask to the transaction's end. */
        Made unit(String name) {
            UUID id = unitIds.get(name);
            if (id != null) {
                return new Made(id, false);
            }
            Made made = made(catalogue.ensureUnit(name));
            unitIds.put(name, made.id());
            return made;
        }

        void customers(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            each(file, row -> {
                String name = row.requiredText("name", 200);
                String contact = row.optionalText("contact", 100);
                if (!buyerNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw row.problem("duplicate credit buyer name");
                }
                SaleHistory.Ensured customer = sales.ensureCustomer(name, contact);
                if (customer.created()) {
                    refs.created(file.name, row, "retail.customer", customer.id());
                    c.written++;
                } else {
                    refs.matched(file.name, row, "retail.customer", customer.id());
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
            return refs.seen(file, row, ref);
        }

        void remember(String file, Row row, String ref, String targetType, UUID targetId, String sourceUser) {
            refs.remember(file, row, ref, targetType, targetId, sourceUser);
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
         * product changed its cost or sell price: consecutive by date, then by {@code source_ref}, among this batch's
         * rows and the restocks earlier runs imported (issue #881, {@link #neighbours}).
         */
        void purchases(ExportFile file) {
            ImportReport.Counts c = report.file(file.name);
            List<PurchaseRow> rows = new ArrayList<>();
            each(file, row -> rows.add(parsePurchase(row)));
            // Whether each row is held already, before anything is written (issue #881): a restock this batch writes is
            // placed among the item's imported restocks as the books hold them before the batch. Told in row order.
            Set<String> refs = new HashSet<>();
            Map<PurchaseRow, Object> held = new HashMap<>();
            for (PurchaseRow r : rows) {
                try {
                    held.put(r, seen("purchases", r.row(), r.ref(), refs));
                } catch (RowProblem p) {
                    held.put(r, p);
                }
            }
            Neighbours neighbours = neighbours(rows.stream()
                    .filter(r -> r.kind().equals("purchase") && Boolean.FALSE.equals(held.get(r)))
                    .toList());
            for (PurchaseRow r : rows) {
                if (held.get(r) instanceof RowProblem p) {
                    report.skip(file.name, p.line, p.getMessage());
                    continue;
                }
                if (Boolean.TRUE.equals(held.get(r))) {
                    c.existing++;
                    continue;
                }
                UUID target;
                String targetType;
                if (r.kind().equals("purchase")) {
                    UUID supplier = null;
                    if (r.supplier() != null) {
                        PurchaseHistory.Ensured s = purchases.ensureSupplier(r.supplier());
                        if (s.created() && !command) {
                            // A framework batch registers every record it writes (re-review of 0bb283a, must-fix 2).
                            this.refs.created(file.name, r.row(), "retail.supplier", s.id());
                        }
                        if (s.created()
                                || (command
                                        && !namedSuppliers.contains(r.supplier().toLowerCase(Locale.ROOT)))) {
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
                            IMPORTED + r.ref());
                    targetType = "retail.purchase";
                    Restock before = neighbours.before().get(r);
                    PriceStep step = before == null
                            ? null
                            : PriceStep.between(
                                    before, Restock.of(r), r.product().sellMinor());
                    if (step != null) {
                        catalogue.importPriceChange(
                                r.product().id(),
                                step.oldCost(),
                                step.newCost(),
                                step.oldSell(),
                                step.newSell(),
                                target,
                                r.at(),
                                "Imported restock " + r.ref());
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
                    List<UUID> made = stock.recordHistorical(r.on(), moves);
                    target = made.getFirst();
                    targetType = "retail.stock_movement";
                    remember("purchases", r.row(), r.ref(), targetType, target, r.user());
                    // Every branch's movement is the run's own stock (issue #135): the first is the row's record, as
                    // before, and the others are registered with it, so a move between shops nets to zero in the books.
                    for (UUID other : made.subList(1, made.size())) {
                        remember("purchases", r.row(), r.ref(), targetType, other, r.user());
                    }
                    c.written++;
                    continue;
                }
                remember("purchases", r.row(), r.ref(), targetType, target, r.user());
                Restock next = neighbours.after().get(r);
                if (next != null) {
                    laterRestock(file.name, r, next, neighbours.recorded().get(next.purchase()));
                }
                c.written++;
            }
        }

        // JUSTIFICATION-A3: issue #881 places a restock among the item's restocks in the books; these are its parts.
        /**
         * A restock in time order (issue #881): a row of this batch ({@code purchase} null until written) or a restock an
         * earlier run imported, its {@code source_ref} read back from the note {@link #IMPORTED} gave it.
         */
        record Restock(Instant at, String ref, long cost, Long sell, UUID purchase) {

            static Restock of(PurchaseRow r) {
                return new Restock(r.at(), r.ref(), r.cost(), r.sell(), null);
            }

            static Restock of(PurchaseHistory.ImportedRestock b) {
                String note = b.note() == null ? "" : b.note();
                return new Restock(
                        b.purchasedAt(),
                        note.startsWith(IMPORTED) ? note.substring(IMPORTED.length()) : note,
                        b.costMinor(),
                        b.sellMinor(),
                        b.purchaseId());
            }
        }

        /**
         * The price change from one restock to the next (data dictionary section 3): a restock without a sell price keeps
         * the one before it, and the first one the item's; null when neither price changed.
         */
        record PriceStep(long oldCost, long newCost, long oldSell, long newSell) {

            static PriceStep between(Restock before, Restock after, long itemSell) {
                long oldSell = before.sell() == null ? itemSell : before.sell();
                long newSell = after.sell() == null ? oldSell : after.sell();
                return before.cost() == after.cost() && oldSell == newSell
                        ? null
                        : new PriceStep(before.cost(), after.cost(), oldSell, newSell);
            }

            boolean same(CatalogueHistory.ImportedChange h) {
                return h.oldCostMinor() == oldCost
                        && h.newCostMinor() == newCost
                        && h.oldSellMinor() == oldSell
                        && h.newSellMinor() == newSell;
            }
        }

        /**
         * For each new restock row: the restock just before it ({@code before}), of this batch or the books; the restock
         * of the books just after it when no new row comes between ({@code after}); and the {@code import} history row
         * each such later restock already carries ({@code recorded}, by purchase).
         */
        record Neighbours(
                Map<PurchaseRow, Restock> before,
                Map<PurchaseRow, Restock> after,
                Map<UUID, CatalogueHistory.ImportedChange> recorded) {}

        /**
         * Issue #881: each new restock row placed among its item's restocks, the books' (as they stand before this batch,
         * whatever run imported them) and this batch's, by date and then by {@code source_ref}, so any split of the
         * history over runs, in any order, finds the restock before it that one run would. The books are read for a page
         * of items at a time, never a row at a time.
         */
        Neighbours neighbours(List<PurchaseRow> fresh) {
            Map<UUID, List<PurchaseRow>> byItem = new LinkedHashMap<>();
            for (PurchaseRow r : fresh) {
                byItem.computeIfAbsent(r.product().id(), k -> new ArrayList<>()).add(r);
            }
            Map<PurchaseRow, Restock> before = new HashMap<>();
            Map<PurchaseRow, Restock> after = new HashMap<>();
            Map<UUID, CatalogueHistory.ImportedChange> recorded = new HashMap<>();
            List<UUID> items = new ArrayList<>(byItem.keySet());
            for (int from = 0; from < items.size(); from += PAGE) {
                List<UUID> page = items.subList(from, Math.min(items.size(), from + PAGE));
                Map<UUID, List<Restock>> line = new HashMap<>();
                Map<Restock, PurchaseRow> mine = new IdentityHashMap<>();
                for (PurchaseHistory.ImportedRestock b : purchases.importedRestocks(page)) {
                    line.computeIfAbsent(b.productId(), k -> new ArrayList<>()).add(Restock.of(b));
                }
                Set<UUID> followed = new LinkedHashSet<>();
                Set<UUID> later = new HashSet<>();
                for (UUID item : page) {
                    List<Restock> restocks = line.computeIfAbsent(item, k -> new ArrayList<>());
                    for (PurchaseRow r : byItem.get(item)) {
                        Restock s = Restock.of(r);
                        mine.put(s, r);
                        restocks.add(s);
                    }
                    restocks.sort(IN_TIME);
                    for (int i = 1; i < restocks.size(); i++) {
                        PurchaseRow previous = mine.get(restocks.get(i - 1));
                        PurchaseRow current = mine.get(restocks.get(i));
                        if (current != null) {
                            before.put(current, restocks.get(i - 1));
                        } else if (previous != null) {
                            after.put(previous, restocks.get(i));
                            followed.add(item);
                            later.add(restocks.get(i).purchase());
                        }
                    }
                }
                if (!followed.isEmpty()) {
                    catalogue.importedChanges(followed).forEach((purchase, change) -> {
                        if (later.contains(purchase)) {
                            recorded.put(purchase, change);
                        }
                    });
                }
            }
            return new Neighbours(before, after, recorded);
        }

        /**
         * Issue #881: restock {@code r} is dated before {@code next}, which an earlier run imported, with no restock between,
         * so the change at {@code next} is now from {@code r}, as one run of the whole history records it. History is
         * append only: a change the books lack is added (and registered with {@code r}), one they hold alike is left, and
         * one they hold otherwise, or where none is due now, is kept and reported, since only a rewrite could correct it.
         */
        void laterRestock(String file, PurchaseRow r, Restock next, CatalogueHistory.ImportedChange recorded) {
            PriceStep step = PriceStep.between(Restock.of(r), next, r.product().sellMinor());
            if (step == null ? recorded == null : recorded != null && step.same(recorded)) {
                return;
            }
            if (step != null && recorded == null) {
                UUID id = catalogue.importPriceChange(
                        r.product().id(),
                        step.oldCost(),
                        step.newCost(),
                        step.oldSell(),
                        step.newSell(),
                        next.purchase(),
                        next.at(),
                        "Imported restock " + next.ref());
                remember("purchases", r.row(), r.ref(), "retail.price_history", id, r.user());
                return;
            }
            report.anomaly(
                    file,
                    r.row().line(),
                    "dated before restock " + next.ref() + ", which an earlier run imported; the price change recorded"
                            + " at " + next.ref() + " is kept as it is, since price history is never rewritten,"
                            + " and differs from what one run of the whole history records");
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
                remember("sales", row, ref, "retail.sale", sale, row.optionalText("source_user", 200));
                c.written++;
            });
            if (creditSales > 0) {
                // The run's receivable decision (spec section 3.8): retail_v1 offers treat_as_settled and defer,
                // neither of which opens a receivable. The command records none and keeps its own rule, the same.
                String decision = checkpoint
                        .policy(Policy.RECEIVABLE)
                        .map(o -> " (receivable decision: " + o + ")")
                        .orElse("");
                report.note("credit sales imported unpaid (the source keeps no payments): " + creditSales + ", total "
                        + creditSalesMinor + " minor units; no receivable is journalled for them" + decision);
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
                remember("usage", row, ref, "retail.usage", report, row.optionalText("source_user", 200));
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
            refuseLive(row, field, d);
            return d;
        }

        /** The first live day: the named one, else the import day; history is strictly before it. */
        LocalDate firstLive() {
            return firstLiveDate == null ? today : firstLiveDate;
        }

        /** A cash book row on or after the first live day belongs to the live book, not to history. */
        void refuseLive(Row row, String field, LocalDate d) {
            if (!d.isBefore(firstLive())) {
                throw row.problem(field + " " + d + " is on or after the first live day " + firstLive()
                        + "; history is dated before it, enter live records in the cash book");
            }
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
                remember("savings", row, ref, "retail.savings", id, row.optionalText("source_user", 200));
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
                if (explanation != null && explanation.length() < CashBookHistory.MIN_EXPLANATION) {
                    throw row.problem("explanation is shorter than " + CashBookHistory.MIN_EXPLANATION + " characters");
                }
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
                remember("expenses", row, ref, "retail.expense", id, row.optionalText("source_user", 200));
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
                remember("withdrawals", row, ref, "retail.withdrawal", id, row.optionalText("source_user", 200));
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
            rows.sort(Comparator.comparing(AdvanceRow::date)
                    .thenComparing(AdvanceRow::ref, RetailImporter::naturalOrder));
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
                            a.row(),
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
                    if (cashBook.advanceOpened(advance)) {
                        String entryNo = cashBook.balanceOpenedRepayment(
                                        advance, id, p.amount(), firstLive().minusDays(1))
                                .orElse("none");
                        report.anomaly(
                                file.name,
                                p.row().line(),
                                "payment " + p.ref() + " is for advance " + p.advanceRef()
                                        + ", whose opening line was posted for the earlier balance; a balancing"
                                        + " entry " + entryNo + " lowers the ledger by the payment");
                    }
                    remember(
                            "advance_payments",
                            p.row(),
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
                refuseLive(row, "banked_at", LocalDate.ofInstant(bankedAt, zone));
                CashBookHistory.Banked banked = cashBook.importBanking(branch, date, amount, bankedAt);
                remember("banking", row, ref, "retail.banking", banked.id(), row.optionalText("source_user", 200));
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
            LocalDate day = firstLive().minusDays(1);
            Set<UUID> branchesDone = new HashSet<>();
            each(file, row -> {
                UUID branch = branchOrHead(row, "branch");
                Long cash = row.optionalMoney("cash_on_hand_minor");
                Long bank = row.optionalSignedMoney("bank_minor");
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
                // A journal: the framework registers it in import_refs at the batch's end; the cash book reads this.
                refs.journal("cash_opening", branch.toString(), "journal", e.entryId());
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

        /**
         * Every outstanding historical advance with no opening line yet gets its own supplementary
         * opening entry (a shop with no cash balance row, or an advance imported after the shop's
         * opening was posted), so no advance is left off the ledger. Keyed per advance: a rerun adds
         * nothing. A shop with advances but no cash balance row is reported.
         */
        void advanceOpenings() {
            LocalDate day = firstLive().minusDays(1);
            Set<UUID> pending = cashBook.branchesWithUnopenedAdvances();
            if (pending.isEmpty()) {
                return;
            }
            for (UUID branch : pending) {
                boolean hasRow = jdbc.sql("""
                                SELECT EXISTS (SELECT 1 FROM retail_import_refs
                                                WHERE source_file = 'cash_opening' AND source_ref = ?)
                                """)
                        .param(branch.toString())
                        .query(Boolean.class)
                        .single();
                if (!hasRow) {
                    report.anomaly(
                            "cash_balances.jsonl",
                            0,
                            "branch " + codeOf(branch)
                                    + " has outstanding advances but no cash balance row; each advance is opened"
                                    + " by its own entry, and its cash, bank and savings reserve are not carried");
                }
            }
            for (CashBookHistory.AdvanceOpened a : cashBook.postAdvanceOpenings(day)) {
                report.cashOpening(new ImportReport.CashOpening(
                        codeOf(a.branchId()),
                        day,
                        0,
                        0,
                        0,
                        1,
                        a.remainingMinor(),
                        a.entryNo(),
                        "advance " + a.advanceNo() + " opened by its own entry"));
            }
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
            List<Balance> rows = new ArrayList<>();
            for (Balance b : parseBalances(file)) {
                if (noGoods.contains(b.branch())) {
                    // The branch keeps no goods (ADR-041): refuse the row and say so, like any other row that cannot be
                    // written.
                    report.anomaly(
                            file.name,
                            b.row().line(),
                            "branch " + b.branchCode() + " keeps no goods; the balance of "
                                    + b.product().code() + " is not imported");
                    c.skipped++;
                } else {
                    rows.add(b);
                }
            }
            Map<String, Position> positions = new HashMap<>();
            stock.positions().forEach(p -> positions.put(p.branchId() + "|" + p.productId(), p));
            Instant opening = openingInstant();
            // The legacy balance and the opening journal carry the import date in the tenant's
            // zone, so a valuation as_of an earlier date reads the imported history alone, on the
            // same basis as the inventory account (review F4).
            LocalDate importDate = clock.today(zone);
            // One opening journal per branch, ever (Lead's rule on the review of 4af7a39): the branches an import
            // already journalled, where a row that differs from the books is listed for a stock-take, never journalled.
            Set<String> opened = new HashSet<>(RetailV1Template.openedBranches(jdbc, null));
            // The books' balance of each item an earlier import already wrote a legacy balance for: this run writes
            // nothing for it, so its opening values the books, not the file (review of bf87e45, must-fix 1).
            Map<String, Map<String, BigDecimal>> legacyHeld = new HashMap<>();
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
                    // The item whose balance an earlier import gave: what a framework run's row stands for.
                    refs.matched(
                            file.name, b.row(), "retail.product", b.product().id());
                    c.existing++;
                    legacyHeld
                            .computeIfAbsent(b.branchCode(), k -> new HashMap<>())
                            .put(b.product().code(), balance);
                    if (balance.compareTo(b.qty()) != 0) {
                        report.anomaly(
                                file.name,
                                b.row().line(),
                                "legacy balance already written; the balance is "
                                        + Quantities.format(balance) + " and the source says "
                                        + Quantities.format(b.qty())
                                        + (opened.contains(RetailV1Template.compare(b.branchCode()))
                                                ? "; differs from the opening; correct it with a stock-take"
                                                : "; the opening values the balance; correct it with a stock-take"));
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
                // JUSTIFICATION-A3: the Lead's rule on the review of 4af7a39 lists each difference at an opened branch.
                boolean openedBranch = opened.contains(RetailV1Template.compare(b.branchCode()));
                if (legacy.signum() != 0 && openedBranch && p != null) {
                    // The branch's opening is posted and the books hold this item there: the difference is the owner's
                    // to correct with a stock-take, not a second journal.
                    refs.matched(
                            file.name, b.row(), "retail.product", b.product().id());
                    c.existing++;
                    report.anomaly(
                            file.name,
                            b.row().line(),
                            "branch " + b.branchCode() + " product "
                                    + b.product().code() + ": the source says "
                                    + Quantities.format(b.qty()) + " and the books hold " + Quantities.format(balance)
                                    + "; differs from the opening; correct it with a stock-take");
                    continue;
                }
                if (legacy.signum() != 0 && openedBranch) {
                    report.anomaly(
                            file.name,
                            b.row().line(),
                            "branch " + b.branchCode() + " product "
                                    + b.product().code() + " is new at the branch: its"
                                    + " balance " + Quantities.format(b.qty()) + " is imported and is not in the"
                                    + " opening already posted; it differs from the opening; correct it with a"
                                    + " stock-take if it is wrong");
                }
                if (legacy.signum() == 0) {
                    // Nothing to write: the history already gives the source balance, on every run.
                    refs.matched(
                            file.name, b.row(), "retail.product", b.product().id());
                    c.existing++;
                } else {
                    UUID movement = stock.recordHistorical(
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
                                            "Legacy balance from the import")))
                            .getFirst();
                    // The run's own stock (issue #134): the books and the opening journal read it from import_refs.
                    refs.created(file.name, b.row(), "retail.stock_movement", movement);
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
            openingJournals(rows, importDate, opened, legacyHeld);
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

        // JUSTIFICATION-A3: issue #135 moves the opening stock journal onto the framework's openingJournal.
        /**
         * Data dictionary rule 5 through the framework (issues #135, #872 and #873), one opening journal per branch, ever
         * (Lead's rule on the review of 4af7a39): the first run that commits balances for a branch posts it, through
         * {@link BatchWriter.Checkpoint#openingJournal} against opening balance equity, for what the branch's balance rows
         * above zero are worth at each item's current cost ({@link RetailV1Template.BranchOpening#journal}, the function the
         * forecast predicts by). The legacy balance makes each row's derived balance the source's, whatever run wrote the
         * history, so a first journal after a stopped or killed run still values everything imported. A negative balance is
         * never journalled (the negative-stock rule: it is listed for the first stock-take). A branch that already has an
         * import opening journal ({@code opened}: the framework's, the command's or the old command's) takes none, reported
         * as already posted, as the command always reported it; its differences were listed by {@code balances} for a
         * stock-take.
         */
        private void openingJournals(
                List<Balance> rows,
                LocalDate today,
                Set<String> opened,
                Map<String, Map<String, BigDecimal>> legacyHeld) {
            Map<String, Map<String, BigDecimal>> byBranch = new TreeMap<>();
            Map<String, Long> costs = new HashMap<>();
            Map<String, UUID> ids = new HashMap<>();
            Set<UUID> inactive = branches.all().stream()
                    .filter(b -> !b.active())
                    .map(Branches.Branch::id)
                    .collect(java.util.stream.Collectors.toSet());
            for (Balance b : rows) {
                byBranch.computeIfAbsent(b.branchCode(), k -> new LinkedHashMap<>())
                        .putIfAbsent(b.product().code(), b.qty());
                costs.put(b.product().code(), b.product().costMinor());
                ids.put(b.branchCode(), b.branch());
            }
            byBranch.forEach((code, items) -> {
                UUID branch = ids.get(code);
                RetailV1Template.BranchOpening opening = RetailV1Template.branchOpening(
                        RetailV1Template.openingQuantities(items, legacyHeld.getOrDefault(code, Map.of())), costs::get);
                checkpoint.stock(code, opening.qty(), opening.valueMinor());
                boolean earlier = opened.contains(RetailV1Template.compare(code));
                long amount = opening.journal(earlier);
                if (earlier) {
                    report.opening(new ImportReport.Opening(
                            code,
                            importJournalled(branch),
                            earlierOpening(branch, code),
                            "already posted, not posted again:"));
                    return;
                }
                if (amount != 0 && inactive.contains(branch)) {
                    // The ledger takes no entry at a closed branch: the amount is told, for the owner to post by hand.
                    report.anomaly(
                            "balances.jsonl",
                            0,
                            "branch " + code + " is inactive; its opening stock of " + amount
                                    + " minor units is not journalled");
                    return;
                }
                if (amount == 0) {
                    report.opening(new ImportReport.Opening(code, 0, null, "nothing to post"));
                    return;
                }
                checkpoint.openingJournal(new ImportRefs.Opening(
                        branch, code, "inventory", today, "inventory", true, amount, RetailV1Template.OPENING_TYPE));
                Map<String, Object> entry = jdbc.sql("""
                                SELECT e.id, e.entry_no FROM import_refs r JOIN journal_entries e ON e.id = r.target_id
                                 WHERE r.batch_id = ? AND r.target_type = 'ledger.journal_entry'
                                   AND r.source_key LIKE 'opening|inventory|%' AND e.branch_id = ?
                                """).params(batchId, branch).query().listOfRows().stream()
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException("the opening journal of " + code + " is not registered"));
                refs.journal("opening", code, "core.journal_entry", (UUID) entry.get("id"));
                opened.add(RetailV1Template.compare(code));
                report.opening(new ImportReport.Opening(code, amount, (String) entry.get("entry_no"), "posted"));
            });
        }

        /**
         * What every import's opening stock journal already put on the branch's inventory account, from the ledger: the
         * framework's, this command's since #135, and the earlier command's (V145's copies and the old table).
         */
        private long importJournalled(UUID branch) {
            return jdbc.sql("""
                            SELECT coalesce(sum(l.debit - l.credit), 0)
                              FROM journal_entries e
                              JOIN journal_lines l ON l.entry_id = e.id
                              JOIN gl_accounts a ON a.id = l.account_id
                             WHERE e.tenant_id = current_setting('app.tenant_id')::uuid AND e.branch_id = ?
                               AND a.system_key = 'inventory'
                               AND e.id IN (SELECT r.target_id FROM import_refs r
                                             WHERE r.tenant_id = e.tenant_id AND r.target_type = 'ledger.journal_entry'
                                               AND r.source_key LIKE 'opening|inventory|%'
                                            UNION
                                            SELECT o.target_id FROM retail_import_refs o
                                             WHERE o.tenant_id = e.tenant_id AND o.source_file = 'opening')
                            """).params(branch).query(Long.class).single();
        }

        /** The first opening stock journal of the branch any import posted, by its number; null when none did. */
        private String earlierOpening(UUID branch, String code) {
            return jdbc.sql("""
                            SELECT min(entry_no) FROM (
                                SELECT e.entry_no FROM import_refs r JOIN journal_entries e ON e.id = r.target_id
                                 WHERE r.tenant_id = current_setting('app.tenant_id')::uuid
                                   AND r.target_type = 'ledger.journal_entry' AND r.source_key LIKE 'opening|inventory|%'
                                   AND e.branch_id = ?
                                UNION ALL
                                SELECT e.entry_no FROM retail_import_refs r JOIN journal_entries e ON e.id = r.target_id
                                 WHERE r.tenant_id = current_setting('app.tenant_id')::uuid
                                   AND r.source_file = 'opening' AND r.source_ref = ?) o
                            """)
                    .params(branch, code)
                    .query(String.class)
                    .optional()
                    .orElse(null);
        }

        /**
         * One second before the earliest imported history of the business, so the legacy balance precedes it: read from
         * the stock ledger, since a batch of the framework holds only its own file (issue #135).
         */
        private Instant openingInstant() {
            return jdbc.sql("""
                            SELECT min(occurred_at) FROM retail_stock_movements
                             WHERE tenant_id = current_setting('app.tenant_id')::uuid AND historical
                               AND kind <> 'legacy_balance'
                            """)
                    .query(java.sql.Timestamp.class)
                    .optional()
                    .map(t -> t.toInstant().minusSeconds(1))
                    .orElseGet(clock::now);
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
            int[] excluded = {0};
            ImportReport quiet = new ImportReport("", "", true);
            Run reader = new Run(export, quiet, id, null, command);
            reader.begin();
            reader.each(file, row -> {
                try {
                    Balance b = new Balance(
                            row,
                            row.text("branch").strip().toUpperCase(Locale.ROOT),
                            reader.branch(row, "branch", row.text("branch")),
                            reader.product(row, row.text("product_code")),
                            row.qty("qty"));
                    // A balance for a branch that keeps no goods is refused (reported by balances()), so it is not
                    // compared.
                    if (reader.noGoods.contains(b.branch())) {
                        excluded[0]++;
                    } else {
                        rows.add(b);
                    }
                } catch (RuntimeException e) {
                    // skipped rows are already in the report
                }
            });
            Map<String, BigDecimal> derived = new HashMap<>();
            stock.positions().forEach(p -> derived.put(p.branchId() + "|" + p.productId(), p.balance()));
            // A branch that keeps no goods should hold none: say so when the books show a balance there (issue #329).
            stock.positions().stream()
                    .filter(p -> noGoods.contains(p.branchId()) && p.balance().signum() != 0)
                    .forEach(p -> report.anomaly(
                            file.name,
                            0,
                            "branch " + codeOf(p.branchId()) + " keeps no goods but holds "
                                    + Quantities.format(p.balance()) + " of " + productCode(p.productId())));
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
                    + " source_sha256=" + s + " derived_sha256=" + d + " match=" + (s.equals(d) ? "yes" : "NO")
                    + " excluded_no_goods=" + excluded[0]);
        }
    }

    // JUSTIFICATION-A3: issue #135 keys the command's rows and the framework's staged rows in import_refs.
    /** How a file's rows are known to the business's imports (issue #135): the command's export, or a framework batch. */
    interface Refs {

        /** Whether the business's imports already hold this history row: it is counted existing and not written. */
        boolean seen(String file, Row row, String ref);

        /** Registers the record a history row made. */
        void remember(String file, Row row, String ref, String targetType, UUID targetId, String sourceUser);

        /** Registers a record a reference row or a balance row made (a branch, an item, a legacy balance). */
        void created(String file, Row row, String targetType, UUID targetId);

        /**
         * Registers the record the business already held that a row matched (an item of the same code, a balance the
         * history already gives): a framework run registers every row it writes, so the reconciliation finds each in the
         * books (issue #134). The command, which matches by name on every run, registers only what it made.
         */
        void matched(String file, Row row, String targetType, UUID targetId);

        /** Keeps a journal the command posted in {@code retail_import_refs}, for the cash book and an earlier release. */
        void journal(String file, String ref, String targetType, UUID entryId);
    }

    /** The tab of the keys of the command's reference and balance rows, a tab no upload has. */
    static final String COMMAND_TAB = "import-retail";

    /** The key in {@code import_refs} of a history row of the export: what V145 and the export's mapping derive. */
    static String historyKey(String file, String ref) {
        return ImportRefs.sourceKey(file + ".jsonl", null, "ref:" + ref);
    }

    /**
     * The command's export (issue #135): a history row is known by its key alone ({@link ImportRefs#BY_KEY}), as the
     * command always knew it by its {@code source_ref} whatever its content, and found in {@code import_refs} (which V145
     * filled from {@code retail_import_refs}) or, for a row a previous release wrote after the backfill, in the old table;
     * a reference or balance row by its file and line. Every history row and journal is also written to
     * {@code retail_import_refs}, the expand step's second write, so the previous release still recognises it after a
     * rollback; that table goes in the contract step.
     */
    private final class CommandRefs implements Refs {

        private final BatchWriter.Checkpoint checkpoint;

        CommandRefs(BatchWriter.Checkpoint checkpoint) {
            this.checkpoint = checkpoint;
        }

        @Override
        public boolean seen(String file, Row row, String ref) {
            return jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM import_refs
                                            WHERE tenant_id = current_setting('app.tenant_id')::uuid AND source_key = ?)
                                OR EXISTS (SELECT 1 FROM retail_import_refs
                                            WHERE tenant_id = current_setting('app.tenant_id')::uuid
                                              AND source_file = ? AND source_ref = ?)
                            """)
                    .params(historyKey(file, ref), file, ref)
                    .query(Boolean.class)
                    .single();
        }

        @Override
        public void remember(String file, Row row, String ref, String targetType, UUID targetId, String sourceUser) {
            checkpoint.register(ImportRefs.byKey(historyKey(file, ref)), targetType, targetId);
            legacy(file, ref, targetType, targetId, sourceUser);
        }

        /**
         * A reference or balance row of the command is known by its position in the command's own export, under the tab
         * {@link #COMMAND_TAB}: never a key a screens upload derives ({@code balances.jsonl|-|line:12}), so a corrected
         * row uploaded later is not taken for this one whatever its content (re-review of 0bb283a, must-fix 1). Only
         * history rows share a key with the screens, by their {@code source_ref}.
         */
        @Override
        public void created(String file, Row row, String targetType, UUID targetId) {
            checkpoint.register(
                    ImportRefs.byKey(ImportRefs.sourceKey(file, COMMAND_TAB, "line:" + row.line())),
                    targetType,
                    targetId);
        }

        @Override
        public void matched(String file, Row row, String targetType, UUID targetId) {
            // Matched again on every run, by name or code, as the command always did.
        }

        @Override
        public void journal(String file, String ref, String targetType, UUID entryId) {
            legacy(file, ref, targetType, entryId, null);
        }
    }

    /** The old table's row, once: a key it already holds is the same row (the framework's table decides now). */
    private void legacy(String file, String ref, String targetType, UUID targetId, String sourceUser) {
        jdbc.sql("""
                        INSERT INTO retail_import_refs (tenant_id, source_file, source_ref, target_type, target_id,
                            source_user)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """).params(file, ref, targetType, targetId, sourceUser).update();
    }

    /**
     * A batch of the framework (issue #135): each row is the staged row of its line, known by its {@code source_ref} and
     * whether the business's imports hold it, and every record it makes is registered under that reference. Its history
     * rows and opening journals are written to {@code retail_import_refs} too, as the command's are, so the previous
     * release recognises them after a rollback (review of 97cb1be, finding 4).
     */
    private final class StagedRefs implements Refs {

        private final BatchWriter.Checkpoint checkpoint;

        /** The staged rows being written, by line, without their values (issue #880): one page, or a whole batch. */
        private final Map<Integer, ImportRefs.StagedRow> rows = new HashMap<>();

        /** What those rows made or matched, registered together when they are written ({@link #flush}). */
        private final List<BatchWriter.Registration> made = new ArrayList<>();

        StagedRefs(BatchWriter.Checkpoint checkpoint) {
            this.checkpoint = checkpoint;
        }

        /** Holds a row about to be written, so the records it makes are registered under its reference. */
        void hold(ImportRefs.StagedRow r) {
            rows.put(
                    r.line(),
                    new ImportRefs.StagedRow(
                            r.line(), Map.of(), r.sourceRef(), r.status(), r.targets(), r.linkedMemberId()));
        }

        /** Registers what the rows held made, in one call, and lets them go: they are written. */
        void flush() {
            checkpoint.registerAll(List.copyOf(made));
            made.clear();
            rows.clear();
        }

        private void register(Row row, String targetType, UUID targetId) {
            made.add(new BatchWriter.Registration(staged(row).sourceRef(), targetType, targetId));
        }

        // JUSTIFICATION-A3: the review of 51cfb70 (nice-to-have 5) asks a framework batch to know the command's keys.
        /**
         * Existing as the framework staged it, or held under the command's key: an upload with another mapping (its own
         * file and tab names) derives another key for the same {@code source_ref}, so the command's history is looked up
         * as {@link CommandRefs#seen} does, and each record found is registered under this row's reference, for the
         * reconciliation to find it.
         */
        @Override
        public boolean seen(String file, Row row, String ref) {
            ImportRefs.StagedRow s = staged(row);
            if (s.status() == ImportRefs.Status.EXISTING) {
                return true;
            }
            List<Map<String, Object>> held = jdbc.sql("""
                            SELECT r.target_type, r.target_id FROM import_refs r
                             WHERE r.tenant_id = current_setting('app.tenant_id')::uuid AND r.source_key = ?
                            UNION
                            SELECT o.target_type, o.target_id FROM retail_import_refs o
                             WHERE o.tenant_id = current_setting('app.tenant_id')::uuid AND o.source_file = ?
                               AND o.source_ref = ? AND o.target_id IS NOT NULL
                            """)
                    .params(historyKey(file, ref), file, ref)
                    .query()
                    .listOfRows();
            held.forEach(h -> register(row, (String) h.get("target_type"), (UUID) h.get("target_id")));
            return !held.isEmpty();
        }

        @Override
        public void remember(String file, Row row, String ref, String targetType, UUID targetId, String sourceUser) {
            register(row, targetType, targetId);
            legacy(file, ref, targetType, targetId, sourceUser);
        }

        @Override
        public void created(String file, Row row, String targetType, UUID targetId) {
            register(row, targetType, targetId);
        }

        @Override
        public void matched(String file, Row row, String targetType, UUID targetId) {
            register(row, targetType, targetId);
        }

        @Override
        public void journal(String file, String ref, String targetType, UUID entryId) {
            // The framework registered the journal as it posted it; the old table keeps it for the previous release.
            legacy(file, ref, targetType, entryId, null);
        }

        private ImportRefs.StagedRow staged(Row row) {
            ImportRefs.StagedRow s = rows.get(row.line());
            if (s == null) {
                throw new IllegalStateException("no staged row at line " + row.line());
            }
            return s;
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
