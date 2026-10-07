package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ImportResult;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ImportRow;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importing a new client's items from CSV (#146). A dry run reads the file and reports, row by row,
 * what would be added, what would be skipped (a code that exists already, or repeats in the file)
 * and what is wrong; an apply does the same and adds the rows that are fine, in one transaction.
 * Applying is idempotent on the product code: run twice, the second run skips every row. An apply
 * with any error row adds nothing, so a half-read file never lands. Codes are normalised by
 * {@link RetailCatalogue#normaliseCode} as the pilot importer does; categories and units are created
 * on demand, matched by name ignoring case. Allowed for an administrator only, and the cost column
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

    ProductImportService(CatalogueRepository repo, CurrentTenant tenant, AuditLog audit) {
        this.repo = repo;
        this.tenant = tenant;
        this.audit = audit;
    }

    @Transactional
    ImportResult run(String text, boolean dryRun) {
        Principal principal = CurrentPrincipal.require();
        if (!principal.hasPermission(ADMIN)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Only an administrator can import items from a file.");
        }
        List<List<String>> table = Csv.parse(text);
        if (table.isEmpty()) {
            throw problem("csv", "The file is empty.");
        }
        Map<String, Integer> columns = columns(table.getFirst());
        for (String required : List.of("code", "description", "category", "unit", "sell_price")) {
            if (!columns.containsKey(required)) {
                throw problem(
                        "csv",
                        "The file needs a column called "
                                + required.replace('_', ' ')
                                + ". The columns are: code, description, category, unit, sell price and, optionally, cost price.");
            }
        }
        boolean withCost = columns.containsKey("cost_price");
        if (withCost && !principal.hasPermission("retail.profit.read")) {
            throw ApiException.rule(
                    "cost_not_allowed",
                    "The cost price column needs permission to see costs. Take that column out of the file and try again.");
        }
        List<List<String>> dataRows = table.subList(1, table.size());
        if (dataRows.size() > MAX_ROWS) {
            throw problem("csv", "The file has more than " + MAX_ROWS + " rows. Split it into smaller files.");
        }

        Set<String> existing = repo.productCodes();
        Map<String, UUID> categories = repo.categoryIdsByName();
        Map<String, UUID> units = repo.unitIdsByName();
        Set<String> newCategories = new LinkedHashSet<>();
        Set<String> newUnits = new LinkedHashSet<>();
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
            String rawCode = cell(cells, columns, "code");
            String description = cell(cells, columns, "description");
            String shown = rawCode.strip();
            String problem = null;
            String code = RetailCatalogue.normaliseCode(rawCode).orElse(null);
            String category = cell(cells, columns, "category").strip();
            String unit = cell(cells, columns, "unit").strip();
            Long sell = amount(cell(cells, columns, "sell_price"));
            Long cost = withCost ? amount(cell(cells, columns, "cost_price")) : Long.valueOf(0);
            boolean costBlank = withCost && cell(cells, columns, "cost_price").isBlank();
            if (code == null) {
                problem = "The code is empty or holds a special character.";
            } else if (code.length() > 40) {
                problem = "The code is longer than 40 characters.";
            } else if (description.isBlank() || description.strip().length() > 300) {
                problem = "The description is empty or longer than 300 characters.";
            } else if (category.isEmpty() || category.length() > 100) {
                problem = "The category is empty or longer than 100 characters.";
            } else if (unit.isEmpty() || unit.length() > 30) {
                problem = "The unit is empty or longer than 30 characters.";
            } else if (sell == null) {
                problem = "The sell price must be a whole number, for example 12000.";
            } else if (cost == null && !costBlank) {
                problem = "The cost price must be a whole number, for example 9000.";
            } else if (sell > RetailCatalogue.MAX_AMOUNT_MINOR
                    || (cost != null && cost > RetailCatalogue.MAX_AMOUNT_MINOR)) {
                problem = "A price is too large.";
            } else if (cost != null && cost > 0 && sell <= cost) {
                problem = "The sell price must be above the cost price.";
            }
            if (problem != null) {
                errors++;
                report.add(new ImportRow(line, shown, description.strip(), "error", problem));
                continue;
            }
            String key = code.toLowerCase(Locale.ROOT);
            if (existing.contains(key)) {
                skipped++;
                report.add(new ImportRow(
                        line, code, description.strip(), "skipped", "An item with this code exists already."));
                continue;
            }
            if (!seen.add(key)) {
                skipped++;
                report.add(new ImportRow(
                        line,
                        code,
                        description.strip(),
                        "skipped",
                        "This code is repeated in the file; the first row is used."));
                continue;
            }
            if (!categories.containsKey(category.toLowerCase(Locale.ROOT))) {
                newCategories.add(category);
            }
            if (!units.containsKey(unit.toLowerCase(Locale.ROOT))) {
                newUnits.add(unit);
            }
            added++;
            report.add(new ImportRow(line, code, description.strip(), "added", dryRun ? "Would be added." : "Added."));
            pending.add(new Pending(code, description.strip(), category, unit, sell, cost == null ? 0 : cost));
        }
        if (!dryRun) {
            if (errors > 0) {
                throw ApiException.rule(
                        "import_has_errors",
                        errors + (errors == 1 ? " row has" : " rows have")
                                + " a problem. Nothing was added. Run the check to see which rows, fix them and try again.");
            }
            apply(pending, categories, units, newCategories, newUnits, added, skipped, principal.userId());
        }
        return new ImportResult(
                dryRun,
                added + skipped + errors,
                added,
                skipped,
                errors,
                List.copyOf(dryRun || errors == 0 ? newCategories : Set.of()),
                List.copyOf(dryRun || errors == 0 ? newUnits : Set.of()),
                report);
    }

    private void apply(
            List<Pending> pending,
            Map<String, UUID> categories,
            Map<String, UUID> units,
            Set<String> newCategories,
            Set<String> newUnits,
            int added,
            int skipped,
            UUID by) {
        Map<String, UUID> categoryIds = new HashMap<>(categories);
        Map<String, UUID> unitIds = new HashMap<>(units);
        for (String name : newCategories) {
            UUID id = UUID.randomUUID();
            repo.insertCategory(id, name, by);
            categoryIds.put(name.toLowerCase(Locale.ROOT), id);
            audit.record(AuditLog.Entry.created(
                    "retail.category.created", "retail.category", id, null, Map.of("name", name)));
        }
        for (String name : newUnits) {
            UUID id = UUID.randomUUID();
            repo.insertUnit(id, name, by);
            unitIds.put(name.toLowerCase(Locale.ROOT), id);
            audit.record(AuditLog.Entry.created("retail.unit.created", "retail.unit", id, null, Map.of("name", name)));
        }
        String currency = tenant.profile().currency();
        for (Pending p : pending) {
            UUID categoryId = categoryIds.get(p.category().toLowerCase(Locale.ROOT));
            UUID unitId = unitIds.get(p.unit().toLowerCase(Locale.ROOT));
            Product product = new Product(
                    UUID.randomUUID(),
                    p.code(),
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
            repo.insert(product, by);
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
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("code", p.code());
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
        summary.put("categories_created", newCategories.size());
        summary.put("units_created", newUnits.size());
        audit.record(AuditLog.Entry.created("retail.product.imported", PRODUCT, null, null, summary));
    }

    private record Pending(String code, String description, String category, String unit, long sell, long cost) {}

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

    /** A whole, non-negative amount, with or without thousands commas; null when it is anything else. */
    private static Long amount(String text) {
        String clean = text.strip().replace(",", "");
        if (!clean.matches("\\d{1,15}")) {
            return null;
        }
        return Long.parseLong(clean);
    }

    private static ApiException problem(String field, String message) {
        return ApiException.validation(List.of(new FieldProblem(field, "invalid", message)));
    }

    /** A small CSV reader: a byte order mark, quoted cells with doubled quotes, and comma, semicolon or tab separators. */
    static final class Csv {

        private Csv() {}

        static List<List<String>> parse(String raw) {
            String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
            int eol = text.indexOf('\n');
            String first = eol < 0 ? text : text.substring(0, eol);
            char separator = first.indexOf('\t') >= 0 ? '\t' : count(first, ';') > count(first, ',') ? ';' : ',';
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"' && cell.toString().isBlank()) {
                    quoted = true;
                    cell.setLength(0);
                } else if (c == separator) {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
        }

        private static long count(String s, char c) {
            return s.chars().filter(x -> x == c).count();
        }
    }
}
