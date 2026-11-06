package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportTemplate;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A test-only template (Lead ruling 4 on #869) whose loader is {@code import-retail}'s own real path,
 * {@link RetailImporter#exportWriter}, over the export directory {@link #DIR}: the commit and the dry run resolve every
 * batch's writer from its template, so until the retail template of #135 this is how a test runs the retail export through
 * the framework. Its one entity set holds a line naming the export; the loader reads the directory, not the rows.
 * Registered only on the test classpath. No real data: the export is the fabricated {@code fixtures/retail/import-sample/}.
 */
@Component
class RetailExportTestTemplate implements ImportTemplate {

    static final String KEY = "retail_export_test";

    /** The export directory the loader imports; a test may point it at a copy, and resets it after. */
    static volatile Path DIR = RetailImportIT.SAMPLE;

    private final RetailImporter importer;

    RetailExportTestTemplate(RetailImporter importer) {
        this.importer = importer;
    }

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
        return List.of(new EntitySet(
                "export", List.of(Field.required("name", FieldType.text(100)).asKey())));
    }

    @Override
    public List<IssueCode> issueCodes() {
        return List.of();
    }

    @Override
    public BatchWriter loader(String entitySet) {
        return importer.exportWriter(DIR, null);
    }

    /** A mapping of a sheet with one column, {@code Export}, onto the entity set. */
    static Map<String, Object> mapping() {
        Map<String, Object> source = Map.ofEntries(
                Map.entry("entity_set", "export"),
                Map.entry("header_row", "auto"),
                Map.entry("columns", Map.of("Export", Map.of("field", "name"))),
                Map.entry("source_ref", Map.of("rule", "row_hash")));
        return Map.ofEntries(
                Map.entry("template", KEY),
                Map.entry("template_version", 1),
                Map.entry("date_convention", "iso"),
                Map.entry("time_zone", "Africa/Kampala"),
                Map.entry("currency", "UGX"),
                Map.entry("sources", Map.of("export", source)));
    }
}
