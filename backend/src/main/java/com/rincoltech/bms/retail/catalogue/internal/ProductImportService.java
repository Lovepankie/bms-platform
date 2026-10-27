package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CsvTable;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
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
 * and the file's codes are not read. Allowed for an administrator only, and the cost column
 * only for a caller holding {@code retail.profit.read}. Cost is never written to the audit log.
 */
@Service
class ProductImportService {

    static final int MAX_CHARS = 1_000_000;
    static final int MAX_ROWS = 2_000;
    static final String ADMIN = "core.settings.manage";

    private static final String PRODUCT = "retail.product";

    private final CatalogueRepository repo;
    private final CurrentTenant tenant;
    private final AuditLog audit;
    private final ModuleSettings settings;
    private final BarcodeRepository barcodes;

    ProductImportService(
            CatalogueRepository repo,
            CurrentTenant tenant,
            AuditLog audit,
            ModuleSettings settings,
            BarcodeRepository barcodes) {
        this.repo = repo;
        this.tenant = tenant;
        this.audit = audit;
        this.settings = settings;
        this.barcodes = barcodes;
    }

    @Transactional
    ImportResult run(String text, boolean dryRun, boolean makeCodes) {
        Principal principal = CurrentPrincipal.require();
        if (!principal.hasPermission(ADMIN)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Only an administrator can import items from a file.");
        }
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
            Long sell = CsvTable.amountMinor(cell(cells, columns, "sell_price"), exponent);
            Long cost = withCost ? CsvTable.amountMinor(cell(cells, columns, "cost_price"), exponent) : Long.valueOf(0);
            boolean costBlank = withCost && cell(cells, columns, "cost_price").isBlank();
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
            } else if (sell == null) {
                problem = "The selling price must be " + example(exponent, 12000) + ".";
            } else if (cost == null && !costBlank) {
                problem = "The buying price must be " + example(exponent, 9000) + ".";
            } else if (sell > RetailCatalogue.MAX_AMOUNT_MINOR
                    || (cost != null && cost > RetailCatalogue.MAX_AMOUNT_MINOR)) {
                problem = "A price is too large.";
            } else if (cost != null && cost > 0 && sell <= cost) {
                problem = "The selling price must be above the buying price.";
            }
            if (problem != null) {
                errors++;
                report.add(new ImportRow(line, shown, description.strip(), "error", problem));
                continue;
            }
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
            String categoryKey = category.toLowerCase(Locale.ROOT);
            String unitKey = unit.toLowerCase(Locale.ROOT);
            String packKey = packUnit.toLowerCase(Locale.ROOT);
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
                                + "). Open it in Items, or give this one another name or unit."));
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
                            + movedNote(rowCodes, rowPackCodes, rowFormat, heldCodes)));
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
                    rowFormat));
        }
        if (!dryRun) {
            if (errors > 0) {
                throw ApiException.rule(
                        "import_has_errors",
                        errors + (errors == 1 ? " row has" : " rows have")
                                + " a problem. Nothing was added. Run the check to see which rows, fix them and try again.");
            }
            try {
                Map<Integer, String> made = apply(
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
                    report.set(row, new ImportRow(r.line(), code, r.description(), r.outcome(), r.message()));
                });
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
                added + skipped + errors,
                added,
                skipped,
                errors,
                List.copyOf(dryRun || errors == 0 ? newCategories.values() : List.<String>of()),
                List.copyOf(dryRun || errors == 0 ? newUnits.values() : List.<String>of()),
                report);
    }

    /** Adds the rows; returns the code made for each row that had none, by its place in the report. */
    private Map<Integer, String> apply(
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
            String name = e.getValue();
            UUID id = UUID.randomUUID();
            repo.insertCategory(id, name, null, by);
            categoryIds.put(e.getKey(), id);
            audit.record(AuditLog.Entry.created(
                    "retail.category.created", "retail.category", id, null, Map.of("name", name)));
        }
        for (Map.Entry<String, String> e : newUnits.entrySet()) {
            String name = e.getValue();
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
                .map(p -> categoryIds.get(p.category().toLowerCase(Locale.ROOT)))
                .distinct()
                .sorted()
                .forEach(repo::lockCategory);
        Map<Integer, String> made = new HashMap<>();
        for (Pending p : ordered) {
            UUID categoryId = categoryIds.get(p.category().toLowerCase(Locale.ROOT));
            UUID unitId = unitIds.get(p.unit().toLowerCase(Locale.ROOT));
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
                // Issue #637: the rules of CatalogueService.addPack with no purpose and no price (a pack for both),
                // whatever the unit's usual purpose; RetailSellingPacksIT holds the two paths together.
                packUnitId = unitIds.get(p.packUnit().toLowerCase(Locale.ROOT));
                UUID packId = UUID.randomUUID();
                repo.insertPack(packId, product.id(), packUnitId, p.packFactor(), RetailCatalogue.PACK_BOTH, null, by);
                audit.record(AuditLog.Entry.created(
                        "retail.product_unit.created",
                        "retail.product_unit",
                        packId,
                        null,
                        Map.of(
                                "product_id",
                                product.id(),
                                "unit_id",
                                packUnitId,
                                "factor",
                                p.packFactor().setScale(3).toPlainString(),
                                "source",
                                "import")));
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
            String barcodeFormat) {}

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
