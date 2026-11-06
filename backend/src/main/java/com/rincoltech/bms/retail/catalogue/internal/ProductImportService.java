package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CsvTable;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.CatalogueNames;
import com.rincoltech.bms.retail.catalogue.ImportedStock;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.StandardUnits;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ImportRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ImportResult;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ImportRow;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importing a new client's items from CSV (#146). A dry run reads the file and reports, row by row,
 * what would be added, what would be skipped (a code that exists already, or repeats in the file)
 * and what is wrong; an apply does the same and adds the rows that are fine, in one transaction.
 * Applying is idempotent on the product code, and a row without a code on its name and unit (issue #352's name rule, which
 * skips a name already held in that unit): run twice, the second run skips every row. An apply
 * with any error row adds nothing, so a half-read file never lands. Codes are normalised by
 * {@link RetailCatalogue#normaliseCode} as the pilot importer does; categories and units are created
 * on demand, matched by name ignoring case. A row with a blank code, or a file without the code column,
 * gets the next code of its category on apply, such as BLB-0001 (issue #493, ADR-081); rows with a code keep it, unless
 * "Let the app make the codes" is ticked ({@code make_codes}, owner decision D45, issue #562): then every row's code is made
 * and the file's codes are not read. Allowed to whoever may manage the catalogue, as Quick add (issue #870; an administrator
 * only before), and the cost column only for a caller holding {@code retail.profit.read}. Cost is never written to the audit log.
 *
 * <p>Issue #870 (ADR-101 amendment of 6 Nov 2026), Add many items: an optional {@code qty} column is how many of the item
 * there are now at {@code branch_id}, added as opening stock through stock's {@link ImportedStock} port (Quick add's own
 * opening stock), checked for {@code retail.stocktake.commit} there before anything is made; {@code stock_only} (yes) adds the
 * row's qty to the item already in the list under that name and unit instead of a new item. A list with quantities is
 * applied under an {@code Idempotency-Key}, so its stock is never added twice, and may come from the phone's outbox with its
 * offline block. A quantity or price with a comma used as a decimal point is refused (the rule of #867); selling below
 * buying is a warning on the row, no longer a refusal.
 */
@Service
class ProductImportService {

    static final int MAX_CHARS = 1_000_000;
    static final int MAX_ROWS = 2_000;
    static final String ADMIN = "core.settings.manage";
    static final String PATH = "/api/v1/retail/products/import";

    private static final String PRODUCT = "retail.product";
    /** Commas only between groups of three digits: 12,500 (the rule of #867). */
    private static final java.util.regex.Pattern GROUPED =
            java.util.regex.Pattern.compile("^[1-9]\\d{0,2}(,\\d{3})+(\\.\\d+)?$");

    private static final java.util.regex.Pattern QTY = java.util.regex.Pattern.compile("^\\d{1,11}(\\.\\d{1,3})?$");

    private final CatalogueRepository repo;
    private final CurrentTenant tenant;
    private final AuditLog audit;
    private final ModuleSettings settings;
    private final BarcodeRepository barcodes;
    private final CatalogueService catalogue;
    private final ImportedStock stock;
    private final Idempotency idempotency;

    ProductImportService(
            CatalogueRepository repo,
            CurrentTenant tenant,
            AuditLog audit,
            ModuleSettings settings,
            BarcodeRepository barcodes,
            CatalogueService catalogue,
            ImportedStock stock,
            Idempotency idempotency) {
        this.catalogue = catalogue;
        this.repo = repo;
        this.tenant = tenant;
        this.audit = audit;
        this.settings = settings;
        this.barcodes = barcodes;
        this.stock = stock;
        this.idempotency = idempotency;
    }

    /** What the key of an apply covers: the list without its offline block, and the codes choice. */
    record KeyedImport(ImportRequest request, boolean makeCodes) {}

    /**
     * An apply with an {@code Idempotency-Key} runs once per key (issue #870): the same key again answers the stored result
     * and adds nothing, to the person who stored it only (ADR-026 decision 5). A check, or an apply without a key, runs as
     * before.
     */
    @Transactional
    Outcome<ImportResult> run(String idempotencyKey, ImportRequest r, boolean dryRun, boolean makeCodes) {
        if (!dryRun && idempotencyKey != null) {
            return idempotency.once(
                    idempotencyKey,
                    "POST",
                    PATH,
                    new KeyedImport(r.forKey(), makeCodes),
                    ImportResult.class,
                    () -> run(r, false, makeCodes, true));
        }
        return new Outcome<>(run(r, dryRun, makeCodes, false), false);
    }

    private ImportResult run(ImportRequest request, boolean dryRun, boolean makeCodes, boolean keyed) {
        String text = request.csv();
        Principal principal = CurrentPrincipal.require();
        List<List<String>> table = CsvTable.parse(text);
        if (table.isEmpty()) {
            throw problem("csv", "The file is empty.");
        }
        Map<String, Integer> columns = columns(table.getFirst());
        for (String required : List.of("description", "category", "unit", "sell_price")) {
            if (!columns.containsKey(required)) {
                throw problem(
                        "csv",
                        "The file needs a column called "
                                + shown(required)
                                + ". The columns are: code (leave it empty and the app makes one from the category),"
                                + " description, category, unit, "
                                + (principal.hasPermission("retail.profit.read")
                                        ? "buying price (if you wish) and "
                                        : "")
                                + "selling price.");
            }
        }
        boolean withCost = columns.containsKey("cost_price");
        // ADR-033, issue #194: the optional pack columns, one pack per row. A file without them is read as before.
        boolean withPack = columns.containsKey("pack_unit") || columns.containsKey("pack_factor");
        boolean unitsOn =
                withPack && Boolean.TRUE.equals(settings.group("retail").get(RetailCatalogue.UNITS_CAPABILITY));
        if (withCost && !principal.hasPermission("retail.profit.read")) {
            throw ApiException.rule(
                    "cost_not_allowed",
                    "The buying price column needs permission to see buying prices. Take that column out of the file and try again.");
        }
        List<List<String>> dataRows = table.subList(1, table.size());
        if (dataRows.size() > MAX_ROWS) {
            throw problem("csv", "The file has more than " + MAX_ROWS + " rows. Split it into smaller files.");
        }
        // JUSTIFICATION-A3: issue #870 asks each row to carry how many there are now; the shop is checked here, once.
        // Issue #870: how many there are now. The shop is checked once, before anything is made, when any row has a
        // quantity; an apply that adds stock needs its key, so a resend never adds the stock twice.
        boolean withQty = columns.containsKey("qty");
        boolean withStockOnly = columns.containsKey("stock_only");
        boolean anyQty = withQty
                && dataRows.stream().anyMatch(c -> !cell(c, columns, "qty").isBlank());
        if (anyQty && !dryRun && !keyed) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "idempotency_key_missing",
                    "Idempotency-Key required",
                    "A list with quantities is added under an Idempotency-Key, so its stock is never added twice.");
        }
        java.time.Instant occurredAt =
                request.offline() == null ? null : request.offline().occurredAt();
        java.time.Instant onlineAsOf =
                request.offline() == null ? null : request.offline().onlineAsOf();
        ImportedStock.Opening at = anyQty ? stock.check(request.branchId(), occurredAt, onlineAsOf) : null;
        List<StockPending> stockPending = new ArrayList<>();
        int stockRows = 0;

        Set<String> existing = repo.productCodes();
        // Issue #345: the optional barcode columns, several codes per cell separated by spaces or |.
        boolean withBarcodes = columns.containsKey("barcode") || columns.containsKey("pack_barcode");
        Map<String, BarcodeRepository.Held> heldCodes = withBarcodes ? barcodes.allKeys() : Map.of();
        Map<String, Integer> codesInFile = new HashMap<>();
        // Issue #352: a name is kept once per unit, like a code. The keys are the database's own (bms_name_key).
        Map<String, String> existingNames = repo.productNames();
        Map<String, Integer> namesInFile = new HashMap<>();
        Map<String, UUID> categories = repo.categoryIdsByName();
        Map<String, UUID> units = repo.unitIdsByName();
        Set<String> offCategories = repo.inactiveCategoryNames();
        Set<String> offUnits = repo.inactiveUnitNames();
        int exponent = repo.currencyExponent(tenant.profile().currency());
        // Keyed on lower(trim(name)) like the unique index; the first spelling in the file names the new row.
        Map<String, String> newCategories = new LinkedHashMap<>();
        Map<String, String> newUnits = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        List<ImportRow> report = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        int added = 0;
        int skipped = 0;
        int errors = 0;
        int line = 1;
        for (List<String> cells : dataRows) {
            line++;
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            String fileCode = columns.containsKey("code") ? cell(cells, columns, "code") : "";
            // Issue #493: a blank code is made from the row's category on apply. Issue #562, owner decision D45: with
            // "Let the app make the codes" ticked every row's code is made and the file's codes are not read at all.
            String rawCode = makeCodes ? "" : fileCode;
            boolean auto = rawCode.isBlank();
            String description = cell(cells, columns, "description");
            String shown = rawCode.strip();
            String problem = null;
            String code = RetailCatalogue.normaliseCode(rawCode).orElse(null);
            String category = cell(cells, columns, "category").strip();
            String unit = cell(cells, columns, "unit").strip();
            String packUnit = withPack ? cell(cells, columns, "pack_unit").strip() : "";
            String packFactorText =
                    withPack ? cell(cells, columns, "pack_factor").strip() : "";
            boolean hasPack = !packUnit.isEmpty() || !packFactorText.isEmpty();
            java.math.BigDecimal packFactor = hasPack ? factor(packFactorText) : null;
            String sellText = cell(cells, columns, "sell_price");
            String costText = withCost ? cell(cells, columns, "cost_price") : "";
            Long sell = CsvTable.amountMinor(sellText, exponent);
            Long cost = withCost ? CsvTable.amountMinor(costText, exponent) : Long.valueOf(0);
            boolean costBlank = withCost && costText.isBlank();
            String qtyText = withQty ? cell(cells, columns, "qty").strip() : "";
            java.math.BigDecimal qty = qtyText.isEmpty() ? null : quantity(qtyText);
            boolean stockOnly = withStockOnly
                    && YES.contains(cell(cells, columns, "stock_only").strip().toLowerCase(Locale.ROOT));
            if (code == null && !auto) {
                problem = "The code holds a special character.";
            } else if (code != null && code.length() > 40) {
                problem = "The code is longer than 40 characters.";
            } else if (description.isBlank() || description.strip().length() > 300) {
                problem = "The description is empty or longer than 300 characters.";
            } else if (category.isEmpty() || category.length() > 100) {
                problem = "The category is empty or longer than 100 characters.";
            } else if (unit.isEmpty() || unit.length() > 30) {
                problem = "The unit is empty or longer than 30 characters.";
            } else if (hasPack && !unitsOn) {
                problem =
                        "Pack sizes need the units capability. Switch it on, or take the pack columns out of the file.";
            } else if (hasPack && (packUnit.isEmpty() || packUnit.length() > 30 || packFactor == null)) {
                problem =
                        "A pack needs both a pack unit (up to 30 characters) and a pack factor of at least 1 with at most"
                                + " three decimals and no commas, for example 12 or 12.5.";
            } else if (hasPack && packUnit.equalsIgnoreCase(unit)) {
                problem = "The pack unit must differ from the unit.";
            } else if (hasPack
                    && StandardUnits.fixedCount(packUnit) != null
                    && packFactor.compareTo(java.math.BigDecimal.valueOf(StandardUnits.fixedCount(packUnit))) != 0) {
                // Review of #722 (ADR-110): a dozen holds 12 on the import too, an error row like any other.
                problem = StandardUnits.fixedCountRefusal(StandardUnits.fixedCount(packUnit));
            } else if (qtyText.length() > 0 && qty == null) {
                problem = "How many must be a number, for example 12 or 2.5, with at most three decimals." + COMMA;
            } else if (stockOnly && (qty == null || qty.signum() <= 0)) {
                problem = "Type how many to add to its stock, more than 0.";
            } else if (sell == null && !stockOnly) {
                problem = "The selling price must be " + example(exponent, 12000) + "." + commaNote(sellText);
            } else if (cost == null && !costBlank && !stockOnly) {
                problem = "The buying price must be " + example(exponent, 9000) + "." + commaNote(costText);
            } else if ((sell != null && sell > RetailCatalogue.MAX_AMOUNT_MINOR)
                    || (cost != null && cost > RetailCatalogue.MAX_AMOUNT_MINOR)) {
                problem = "A price is too large.";
            }
            if (problem != null) {
                errors++;
                report.add(new ImportRow(line, shown, description.strip(), "error", problem));
                continue;
            }
            // Issue #870: Add to its stock, the item of this name and unit already in the list takes the quantity.
            if (stockOnly) {
                String unitKeyOf = CatalogueNames.unitKey(unit);
                CatalogueRepository.NameMatch same = repo.sameName(description.strip(), null).stream()
                        .filter(m -> m.familyId() == null
                                && CatalogueNames.unitKey(m.unit()).equals(unitKeyOf))
                        .findFirst()
                        .orElse(null);
                String refusal = same == null
                        ? "There is no item called " + description.strip() + " in " + unit + " to add stock to."
                        : !same.active()
                                ? same.description() + " is switched off. Switch it on first, on Items."
                                : null;
                if (refusal != null) {
                    errors++;
                    report.add(new ImportRow(line, shown, description.strip(), "error", refusal));
                    continue;
                }
                stockRows++;
                stockPending.add(new StockPending(same.id(), qty));
                report.add(new ImportRow(
                        line,
                        same.code(),
                        same.description(),
                        "stock",
                        (dryRun ? "Would add " : "Added ") + plain(qty) + " to the stock of " + same.description()
                                + " (" + same.code() + ").",
                        plain(qty),
                        same.code()));
                continue;
            }
            // Issue #870: selling below buying is the owner's to check, a warning on the row and never a refusal.
            String below =
                    cost != null && cost > 0 && sell < cost ? " The selling price is below the buying price." : "";
            String key = auto ? null : code.toLowerCase(Locale.ROOT);
            if (auto) {
                code = "";
            } else if (existing.contains(key)) {
                skipped++;
                report.add(new ImportRow(
                        line, code, description.strip(), "skipped", "An item with this code exists already."));
                continue;
            }
            if (!auto && !seen.add(key)) {
                skipped++;
                report.add(new ImportRow(
                        line,
                        code,
                        description.strip(),
                        "skipped",
                        "This code is repeated in the file; the first row is used."));
                continue;
            }
            // Issue #698: as categories are stored and compared, spaces inside counted once.
            String categoryKey = CatalogueNames.nameKey(category);
            // Issue #698 (ADR-109): "pcs" in a file is the shop's Piece; two spellings in one file make one unit.
            String unitKey = CatalogueNames.unitKey(unit);
            String packKey = CatalogueNames.unitKey(packUnit);
            // JUSTIFICATION-A3: the two duplicate-name skips of issue #352 sit beside the existing duplicate-code
            // skips, in this loop.
            String nameKey = repo.nameKey(description.strip()) + CatalogueRepository.NAME_SEP + unitKey;
            String sameName = existingNames.get(nameKey);
            if (sameName != null) {
                seen.remove(key);
                skipped++;
                report.add(new ImportRow(
                        line,
                        code,
                        description.strip(),
                        "skipped",
                        "An item with this name exists already (code " + sameName
                                + "). Open it in Items, or give this one another name or unit."
                                + (qty != null && qty.signum() > 0
                                        ? " To add the " + plain(qty) + " to its stock, choose Add to its stock."
                                        : ""),
                        qty == null ? null : plain(qty),
                        sameName));
                continue;
            }
            Integer sameLine = namesInFile.putIfAbsent(nameKey, line);
            if (sameLine != null) {
                seen.remove(key);
                skipped++;
                report.add(new ImportRow(
                        line,
                        code,
                        description.strip(),
                        "skipped",
                        "This name is repeated in the file (same as line " + sameLine + "); the first row is used."));
                continue;
            }
            String off = offCategories.contains(categoryKey)
                    ? "category \"" + category + "\" is switched off. Switch it on or use another category."
                    : offUnits.contains(unitKey)
                            ? "unit \"" + unit + "\" is switched off. Switch it on or use another unit."
                            : hasPack && offUnits.contains(packKey)
                                    ? "pack unit \"" + packUnit
                                            + "\" is switched off. Switch it on or use another unit."
                                    : null;
            if (off != null) {
                seen.remove(key);
                errors++;
                report.add(new ImportRow(line, code, description.strip(), "error", "Line " + line + ": the " + off));
                continue;
            }
            List<String> rowCodes = withBarcodes ? codes(cell(cells, columns, "barcode")) : List.of();
            List<String> rowPackCodes = withBarcodes ? codes(cell(cells, columns, "pack_barcode")) : List.of();
            // Blank reads the format from each code; code128 takes an all-digit code a spreadsheet may have damaged as
            // is.
            String barcodeFormat =
                    cell(cells, columns, "barcode_format").strip().toLowerCase(Locale.ROOT);
            String rowFormat = barcodeFormat.isEmpty() ? null : barcodeFormat;
            String barcodeProblem = !rowPackCodes.isEmpty() && !hasPack
                    ? "A pack barcode needs a pack unit and a pack factor on the same row."
                    : barcodeProblem(rowCodes, rowPackCodes, rowFormat, heldCodes, codesInFile, line);
            if (barcodeProblem != null) {
                seen.remove(key);
                errors++;
                report.add(new ImportRow(line, code, description.strip(), "error", barcodeProblem));
                continue;
            }
            // Only a row that passes holds its codes, so a later row is never refused over a code of an error row.
            for (String c : rowCodes) {
                codesInFile.putIfAbsent(Barcodes.key(c, rowFormat), line);
            }
            for (String c : rowPackCodes) {
                codesInFile.putIfAbsent(Barcodes.key(c, rowFormat), line);
            }
            if (!categories.containsKey(categoryKey)) {
                newCategories.putIfAbsent(categoryKey, category);
            }
            if (!units.containsKey(unitKey)) {
                newUnits.putIfAbsent(unitKey, unit);
            }
            if (hasPack && !units.containsKey(packKey)) {
                newUnits.putIfAbsent(packKey, packUnit);
            }
            added++;
            String packNote = hasPack
                    ? " With a pack of " + packFactor.setScale(3).toPlainString() + " in one " + packUnit + "."
                    : "";
            report.add(new ImportRow(
                    line,
                    code,
                    description.strip(),
                    "added",
                    (dryRun ? "Would be added." : "Added.")
                            + (auto ? " The app gives it the next code of " + category + "." : "")
                            + (makeCodes && !fileCode.isBlank()
                                    ? " The code " + fileCode.strip() + " in the file is not used."
                                    : "")
                            + packNote
                            + movedNote(rowCodes, rowPackCodes, rowFormat, heldCodes)
                            + (qty != null && qty.signum() > 0 ? " With " + plain(qty) + " in stock." : "")
                            + below,
                    qty == null ? null : plain(qty),
                    null));
            pending.add(new Pending(
                    report.size() - 1,
                    auto ? null : code,
                    description.strip(),
                    category,
                    unit,
                    sell,
                    cost == null ? 0 : cost,
                    hasPack ? packUnit : null,
                    packFactor,
                    rowCodes,
                    rowPackCodes,
                    rowFormat,
                    qty));
        }
        if (!dryRun) {
            if (errors > 0) {
                throw ApiException.rule(
                        "import_has_errors",
                        errors + (errors == 1 ? " row has" : " rows have")
                                + " a problem. Nothing was added. Run the check to see which rows, fix them and try again.");
            }
            Map<Integer, UUID> madeIds = new HashMap<>();
            try {
                Map<Integer, String> made = apply(
                        stockRows,
                        madeIds,
                        pending,
                        categories,
                        units,
                        newCategories,
                        newUnits,
                        added,
                        skipped,
                        makeCodes,
                        principal.userId());
                made.forEach((row, code) -> {
                    ImportRow r = report.get(row);
                    report.set(
                            row,
                            new ImportRow(
                                    r.line(),
                                    code,
                                    r.description(),
                                    r.outcome(),
                                    r.message(),
                                    r.qty(),
                                    r.existingCode()));
                });
                // Issue #870: the quantities, once every item is made, each through Quick add's opening stock.
                if (at != null) {
                    UUID importId = UUID.randomUUID();
                    for (Pending p : pending) {
                        if (p.qty() != null && p.qty().signum() > 0) {
                            stock.add(at, madeIds.get(p.row()), p.qty(), importId);
                        }
                    }
                    for (StockPending s : stockPending) {
                        stock.add(at, s.productId(), s.qty(), importId);
                    }
                    stock.recordedOffline(at, occurredAt, onlineAsOf, importId);
                }
            } catch (DuplicateKeyException e) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "import_conflict",
                        "Import conflict",
                        "Another change added an item code, category or unit with the same name while this file was being applied. Nothing was added. Run the check again and apply the file again.");
            }
        }
        return new ImportResult(
                dryRun,
                added + skipped + errors + stockRows,
                added,
                skipped,
                errors,
                List.copyOf(dryRun || errors == 0 ? newCategories.values() : List.<String>of()),
                List.copyOf(dryRun || errors == 0 ? newUnits.values() : List.<String>of()),
                report,
                stockRows,
                at == null ? null : at.branchId());
    }

    /** Adds the rows; returns the code made for each row that had none, by its place in the report. */
    private Map<Integer, String> apply(
            int stockOnlyRows,
            Map<Integer, UUID> madeIds,
            List<Pending> pending,
            Map<String, UUID> categories,
            Map<String, UUID> units,
            Map<String, String> newCategories,
            Map<String, String> newUnits,
            int added,
            int skipped,
            boolean makeCodes,
            UUID by) {
        // Issue #352: the names are locked in a fixed order before any row is written, so a person adding the same name
        // through the screen at this moment queues behind the import (or the import behind them) and sees it.
        pending.stream().map(Pending::description).sorted().distinct().forEach(repo::lockName);
        for (Pending row : pending) {
            if (repo.sameName(row.description(), null).stream()
                    .anyMatch(m -> m.unit().equalsIgnoreCase(row.unit()))) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "import_conflict",
                        "Import conflict",
                        "Another change added an item named " + row.description()
                                + " while this file was being applied. Nothing was added. Run the check again and apply the file again.");
            }
        }
        Map<String, UUID> categoryIds = new HashMap<>(categories);
        Map<String, UUID> unitIds = new HashMap<>(units);
        for (Map.Entry<String, String> e : newCategories.entrySet()) {
            // The name as it is stored (issue #698), so the audit says what the screens show.
            String name = CatalogueNames.display(e.getValue());
            UUID id = UUID.randomUUID();
            repo.insertCategory(id, name, null, by);
            categoryIds.put(e.getKey(), id);
            audit.record(AuditLog.Entry.created(
                    "retail.category.created", "retail.category", id, null, Map.of("name", name)));
        }
        // Issue #698: each new unit under its key's lock, taken in key order so two imports queue and never
        // deadlock; one added meanwhile in another spelling is used, never made twice.
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(newUnits).entrySet()) {
            String name = e.getValue();
            repo.lockUnitKey(name);
            CatalogueApi.Unit meanwhile = repo.sameUnit(name, null).orElse(null);
            if (meanwhile != null && !meanwhile.active()) {
                // Issue #662: a unit added and switched off meanwhile is a change made meanwhile, as for a pack unit.
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "import_conflict",
                        "Import conflict",
                        "Another change added the unit " + meanwhile.name()
                                + ", switched off, while this file was being applied. Nothing was added. Run the check again and apply the file again.");
            }
            if (meanwhile != null) {
                unitIds.put(e.getKey(), meanwhile.id());
                continue;
            }
            UUID id = UUID.randomUUID();
            repo.insertUnit(id, name, by);
            unitIds.put(e.getKey(), id);
            audit.record(AuditLog.Entry.created("retail.unit.created", "retail.unit", id, null, Map.of("name", name)));
        }
        String currency = tenant.profile().currency();
        // Issue #493: rows with a typed code go in first, so a made code steps over them; the categories that make
        // codes are
        // locked in a fixed order first, so two imports at once queue rather than deadlock.
        List<Pending> ordered = new ArrayList<>(pending);
        ordered.sort(Comparator.comparing((Pending p) -> p.code() == null));
        ordered.stream()
                .filter(p -> p.code() == null)
                .map(p -> categoryIds.get(CatalogueNames.nameKey(p.category())))
                .distinct()
                .sorted()
                .forEach(repo::lockCategory);
        Map<Integer, String> made = new HashMap<>();
        for (Pending p : ordered) {
            UUID categoryId = categoryIds.get(CatalogueNames.nameKey(p.category()));
            UUID unitId = unitIds.get(CatalogueNames.unitKey(p.unit()));
            String code = p.code() == null ? repo.nextItemCode(categoryId) : p.code();
            if (p.code() == null) {
                made.put(p.row(), code);
            }
            Product product = new Product(
                    UUID.randomUUID(),
                    code,
                    p.description(),
                    categoryId,
                    null,
                    unitId,
                    null,
                    p.sell(),
                    p.cost(),
                    currency,
                    true,
                    null,
                    null,
                    1,
                    null,
                    null);
            repo.insert(product, by, null);
            madeIds.put(p.row(), product.id());
            repo.insertHistory(
                    new PriceChange(
                            UUID.randomUUID(),
                            null,
                            by,
                            "initial",
                            null,
                            null,
                            p.cost(),
                            null,
                            p.sell(),
                            currency,
                            null),
                    product.id());
            UUID packUnitId = null;
            if (p.packUnit() != null) {
                // Issue #637: made by the one pack path of POST /products/{id}/units, its checks and its audit: for
                // both, with no price of its own, whatever the unit's usual purpose.
                packUnitId = unitIds.get(CatalogueNames.unitKey(p.packUnit()));
                try {
                    catalogue.createPack(
                            product,
                            packUnitId,
                            p.packFactor(),
                            RetailCatalogue.PACK_BOTH,
                            null,
                            CatalogueService.Holds.NONE,
                            by,
                            "import");
                } catch (ApiException e) {
                    // Issue #662: a pack unit switched off after the check read the units is a change made meanwhile.
                    if (e.errors().stream().anyMatch(f -> f.code().equals("inactive_unit"))) {
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "import_conflict",
                                "Import conflict",
                                "Another change switched off the unit " + p.packUnit()
                                        + " while this file was being applied. Nothing was added. Run the check again and apply the file again.");
                    }
                    throw e;
                }
            }
            for (String c : p.barcodes()) {
                addBarcode(product.id(), null, c, p.barcodeFormat(), by);
            }
            for (String c : p.packBarcodes()) {
                addBarcode(product.id(), packUnitId, c, p.barcodeFormat(), by);
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("code", code);
            after.put("description", p.description());
            after.put("category_id", categoryId);
            after.put("unit_id", unitId);
            after.put("sell_minor", p.sell());
            after.put("source", "import");
            audit.record(AuditLog.Entry.created("retail.product.created", PRODUCT, product.id(), null, after));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("added", added);
        summary.put("skipped", skipped);
        summary.put("make_codes", makeCodes);
        summary.put("categories_created", newCategories.size());
        summary.put("units_created", newUnits.size());
        // Issue #870: the new items that came with stock, and the rows that added stock to an item already in the list.
        summary.put(
                "with_stock",
                pending.stream()
                        .filter(p -> p.qty() != null && p.qty().signum() > 0)
                        .count());
        summary.put("stock_added", stockOnlyRows);
        audit.record(AuditLog.Entry.created("retail.product.imported", PRODUCT, null, null, summary));
        return made;
    }

    /** One imported barcode; a code an item that is switched off held is released to it first, as the screen does. */
    private void addBarcode(UUID productId, UUID unitId, String raw, String format, UUID by) {
        Barcodes.Checked c = Barcodes.check(raw, format).checked();
        barcodes.byKey(Barcodes.key(c)).ifPresent(h -> {
            // The holder is locked and read again, so an item switched on at this moment keeps its code.
            if (h.productActive()
                    || repo.lock(h.productId()).map(Product::active).orElse(false)) {
                throw barcodeConflict(c);
            }
            barcodes.delete(h.barcode().id());
            audit.record(new AuditLog.Entry(
                    "retail.barcode.released",
                    BarcodeService.BARCODE,
                    h.barcode().id(),
                    null,
                    Map.of("product_id", h.productId(), "code", h.barcode().code()),
                    Map.of("product_id", productId)));
        });
        UUID id = UUID.randomUUID();
        try {
            barcodes.insert(id, productId, unitId, c.code(), c.format().key, false, by);
        } catch (DuplicateKeyException e) {
            // Another writer took the code between the read above and this insert; the unique index refuses it.
            throw barcodeConflict(c);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("product_id", productId);
        after.put("code", c.code());
        after.put("format", c.format().key);
        after.put("source", "import");
        audit.record(AuditLog.Entry.created("retail.barcode.added", BarcodeService.BARCODE, id, null, after));
    }

    private static ApiException barcodeConflict(Barcodes.Checked c) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "import_conflict",
                "Import conflict",
                "Another change put the barcode " + c.code()
                        + " on an item while this file was being applied. Nothing was added. Run the check again and apply the file again.");
    }

    /** {@code code} is null for a row whose code the category makes on apply; {@code row} is its place in the report. */
    private record Pending(
            int row,
            String code,
            String description,
            String category,
            String unit,
            long sell,
            long cost,
            String packUnit,
            java.math.BigDecimal packFactor,
            List<String> barcodes,
            List<String> packBarcodes,
            String barcodeFormat,
            java.math.BigDecimal qty) {}

    // JUSTIFICATION-A3: issue #870 adds the qty and stock_only columns, read beside the other cells.
    /** Issue #870: a row that adds its quantity to the item already in the list. */
    private record StockPending(UUID productId, java.math.BigDecimal qty) {}

    /** Issue #870: the words of stock_only that mean yes. */
    private static final Set<String> YES = Set.of("yes", "y", "true", "1");

    private static final String COMMA =
            " Use a point for decimals; a comma only goes between thousands, for example 12,500.";

    /** How many, with commas only between thousands and at most three decimals; null when it is not one (never rounded). */
    private static java.math.BigDecimal quantity(String text) {
        String t = text.strip();
        if (t.indexOf(',') >= 0) {
            if (!GROUPED.matcher(t).matches()) {
                return null;
            }
            t = t.replace(",", "");
        }
        return QTY.matcher(t).matches() ? new java.math.BigDecimal(t) : null;
    }

    /** A quantity as people write it: 12, 2.5. */
    private static String plain(java.math.BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }

    /** The reason a price with a comma was refused, when the comma was used as a decimal point. */
    private static String commaNote(String text) {
        String t = text.strip();
        return t.indexOf(',') >= 0 && !GROUPED.matcher(t).matches() ? COMMA : "";
    }

    /** The codes of a barcode cell: separated by spaces or a vertical bar, since the file's separator may be a comma. */
    private static List<String> codes(String text) {
        return java.util.Arrays.stream(text.strip().split("[|\\s]+"))
                .filter(c -> !c.isEmpty())
                .toList();
    }

    /**
     * Issue #345: the first problem of a row's barcodes, or null. A code another active item holds, or one repeated in
     * the file, is an error, never a guess; a code held by an item that is switched off moves to the new item on apply.
     * {@code inFile} holds the codes of the rows that passed so far; the caller adds this row's once the row passes.
     */
    private static String barcodeProblem(
            List<String> rowCodes,
            List<String> packCodes,
            String format,
            Map<String, BarcodeRepository.Held> held,
            Map<String, Integer> inFile,
            int line) {
        List<String> all = new ArrayList<>(rowCodes);
        all.addAll(packCodes);
        java.util.Set<String> inRow = new java.util.HashSet<>();
        for (String c : all) {
            Barcodes.Result r = Barcodes.check(c, format);
            if (!r.ok()) {
                return "Barcode " + c + ": " + r.problem();
            }
            String k = Barcodes.key(r.checked());
            BarcodeRepository.Held h = held.get(k);
            if (h != null && h.productActive()) {
                return "Barcode " + c + " is on the item " + h.productDescription() + " (code " + h.productCode()
                        + ") already.";
            }
            Integer first = inFile.get(k);
            if (first != null) {
                return "Barcode " + c + " is repeated in the file (same as line " + first + ").";
            }
            if (!inRow.add(k)) {
                return "Barcode " + c + " is repeated on this line.";
            }
        }
        return null;
    }

    /** The codes a row takes from items that are switched off, which lose them for good. */
    private static String movedNote(
            List<String> rowCodes, List<String> packCodes, String format, Map<String, BarcodeRepository.Held> held) {
        StringBuilder note = new StringBuilder();
        for (List<String> list : List.of(rowCodes, packCodes)) {
            for (String c : list) {
                BarcodeRepository.Held h = held.get(Barcodes.key(c, format));
                if (h != null && !h.productActive()) {
                    note.append(" Barcode ")
                            .append(c)
                            .append(" moves from the switched-off item ")
                            .append(h.productDescription())
                            .append(" (code ")
                            .append(h.productCode())
                            .append(") and is removed there for good.");
                }
            }
        }
        return note.toString();
    }

    private static final java.util.regex.Pattern FACTOR =
            java.util.regex.Pattern.compile("(\\d{1,11})(?:\\.(\\d{1,3}))?");

    /** A pack factor above zero with at most three decimals, or null. */
    private static java.math.BigDecimal factor(String text) {
        // A comma is refused, not dropped: "12,5" must not become 125.
        java.util.regex.Matcher m = FACTOR.matcher(text);
        if (!m.matches()) {
            return null;
        }
        java.math.BigDecimal value = new java.math.BigDecimal(m.group());
        return value.compareTo(java.math.BigDecimal.ONE) >= 0 ? value : null;
    }

    /** A column as the help and the messages name it (#494): the price columns are the buying and selling price. */
    private static String shown(String column) {
        return switch (column) {
            case "sell_price" -> "selling price";
            case "cost_price" -> "buying price";
            default -> column.replace('_', ' ');
        };
    }

    /** Header names are matched ignoring case, spaces, hyphens and underscores; a few plain synonyms are accepted. */
    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).strip().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]+", "_");
            String canonical = switch (name) {
                case "code", "item_code", "product_code" -> "code";
                case "description", "name", "item", "item_name" -> "description";
                case "category" -> "category";
                case "unit", "unit_of_measure" -> "unit";
                case "sell_price", "selling_price", "sell", "price" -> "sell_price";
                case "cost_price", "cost", "buying_price" -> "cost_price";
                case "pack_unit" -> "pack_unit";
                case "pack_factor", "pack_size" -> "pack_factor";
                case "barcode", "barcodes", "ean", "upc" -> "barcode";
                case "pack_barcode", "pack_barcodes" -> "pack_barcode";
                case "barcode_format", "barcode_type" -> "barcode_format";
                // Issue #870: how many there are now, and adding them to the item already in the list.
                case "qty", "quantity", "how_many", "how_many_now", "stock" -> "qty";
                case "stock_only", "add_to_stock" -> "stock_only";
                default -> null;
            };
            if (canonical != null) {
                columns.putIfAbsent(canonical, i);
            }
        }
        return columns;
    }

    private static String cell(List<String> cells, Map<String, Integer> columns, String name) {
        Integer i = columns.get(name);
        return i == null || i >= cells.size() ? "" : cells.get(i);
    }

    private static String example(int exponent, long whole) {
        return exponent == 0
                ? "a whole number, for example " + whole
                : "an amount with at most " + exponent + " decimals, for example " + whole + " or " + whole + ".50";
    }

    private static ApiException problem(String field, String message) {
        return ApiException.validation(List.of(new FieldProblem(field, "invalid", message)));
    }
}
