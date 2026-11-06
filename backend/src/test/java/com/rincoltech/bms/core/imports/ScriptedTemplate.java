package com.rincoltech.bms.core.imports;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A test-only template (Lead ruling 4 on #869): the commit and the dry run resolve every batch's writer from its template,
 * so a test's writer reaches them only as this template's loader, never passed by the caller. Its one entity set holds a
 * code; its loader runs {@link #SCRIPT}, the writer the test sets, or writes nothing. Registered only on the test
 * classpath, as {@code FixtureShopTemplate} is. No real data: every name is made up.
 */
@Component
public class ScriptedTemplate implements ImportTemplate {

    public static final String KEY = "scripted_test";

    /** What a test has the loader do for every batch, or null for nothing; reset after each test. */
    public static volatile BatchWriter SCRIPT;

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
                "rows", List.of(Field.required("code", FieldType.text(40)).asKey())));
    }

    @Override
    public List<IssueCode> issueCodes() {
        return List.of();
    }

    @Override
    public BatchWriter loader(String entitySet) {
        return (runId, batchId, seq, checkpoint) -> {
            BatchWriter script = SCRIPT;
            return script == null ? Map.of() : script.write(runId, batchId, seq, checkpoint);
        };
    }

    /** A mapping of a sheet with one column, {@code Code}, onto the entity set. */
    public static Map<String, Object> mapping() {
        Map<String, Object> source = Map.ofEntries(
                Map.entry("entity_set", "rows"),
                Map.entry("header_row", "auto"),
                Map.entry("columns", Map.of("Code", Map.of("field", "code"))),
                Map.entry("source_ref", Map.of("rule", "row_hash")));
        return Map.ofEntries(
                Map.entry("template", KEY),
                Map.entry("template_version", 1),
                Map.entry("date_convention", "iso"),
                Map.entry("time_zone", "Africa/Kampala"),
                Map.entry("currency", "UGX"),
                Map.entry("sources", Map.of("rows", source)));
    }
}
