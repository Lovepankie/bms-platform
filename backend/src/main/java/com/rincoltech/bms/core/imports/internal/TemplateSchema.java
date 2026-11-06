package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.Kind;
import com.rincoltech.bms.core.imports.ImportTemplate.RowRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema (draft 2020-12) of a mapping for one template version (issue #129, spec section 4.2), built from the
 * template, as plain maps and lists in a fixed key order. The shared shape is the same for every template; each entity set
 * narrows a source that names it to its own fields, its enum values and the row rules it offers, through {@code if} and
 * {@code then}. It uses only the keywords {@link SchemaCheck} interprets, so the document the service publishes is exactly
 * the one it checks with. Plain Java, no Spring.
 */
final class TemplateSchema {

    static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";

    /** The date conventions of spec section 4.2. */
    static final List<String> DATE_CONVENTIONS = List.of("day_first", "month_first", "iso");

    /** A source's key in the mapping: lower case, as a template key. */
    static final String SOURCE_KEY = "^[a-z][a-z0-9_]{0,39}$";

    /** The fixed header rows a mapping may name: the rows a header is looked for in (spec section 3.4.1). */
    static final int MAX_HEADER_ROW = Parsing.HEADER_SEARCH_ROWS;

    private TemplateSchema() {}

    static Map<String, Object> of(ImportTemplate template) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("template", constant(template.key()));
        properties.put("template_version", constant(template.version()));
        properties.put("date_convention", oneOf(DATE_CONVENTIONS));
        properties.put("time_zone", text(1, 64));
        properties.put("currency", object("type", "string", "pattern", "^[A-Z]{3}$"));
        properties.put("unit_maps", labels(text(1, 100), text(1, 40)));
        Map<String, Object> sources = labels(object("type", "string", "pattern", SOURCE_KEY), ref("source"));
        sources.put("minProperties", 1);
        properties.put("sources", sources);
        Map<String, Object> schema = object("$schema", DRAFT, "$id", "urn:bms:imports:mapping:" + template.id());
        schema.put("title", "Mapping for the template " + template.id());
        schema.putAll(closed(
                properties, "template", "template_version", "date_convention", "time_zone", "currency", "sources"));
        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("source", source(template));
        defs.put("ignored", closed(object("ignore", constant(true), "reason", text(3, 200)), "ignore", "reason"));
        defs.put("source_ref", sourceRef());
        for (EntitySet set : template.entitySets()) {
            defs.put("entity_" + set.key(), entitySet(set));
        }
        schema.put("$defs", defs);
        return schema;
    }

    /** The shape every source shares; the entity set it names narrows it. */
    private static Map<String, Object> source(ImportTemplate template) {
        Map<String, Object> p = new LinkedHashMap<>();
        List<String> sets = template.entitySets().stream().map(EntitySet::key).toList();
        p.put("entity_set", oneOf(sets));
        p.put("file", text(1, 255));
        p.put("sheet", text(1, 100));
        Map<String, Object> fixedRow = object("type", "integer", "minimum", 1, "maximum", MAX_HEADER_ROW);
        p.put("header_row", anyOf(constant("auto"), fixedRow));
        Map<String, Object> mapped = closed(object("field", text(1, 64)), "field");
        Map<String, Object> columns = labels(text(1, 200), anyOf(mapped, ref("ignored")));
        columns.put("minProperties", 1);
        p.put("columns", columns);
        p.put("value_maps", labels(text(1, 200), labels(text(1, 200), text(0, 200))));
        p.put("enum_maps", labels(text(1, 200), labels(text(1, 200), text(0, 200))));
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put(RowRule.SUBHEADER_MONTH.key(), object("type", "boolean"));
        rules.put(RowRule.TOTALS_ROW.key(), oneOf(List.of("first", "last")));
        rules.put(
                RowRule.PLACEHOLDER_ROWS.key(),
                object("type", "array", "minItems", 1, "maxItems", 50, "items", text(1, 100)));
        p.put("row_rules", closed(rules));
        p.put("source_ref", ref("source_ref"));
        Map<String, Object> source = closed(p, "entity_set", "header_row", "columns", "source_ref");
        List<Object> narrowing = new ArrayList<>();
        for (EntitySet set : template.entitySets()) {
            Map<String, Object> names = object("required", List.of("entity_set"));
            names.put("properties", object("entity_set", constant(set.key())));
            narrowing.add(object("if", names, "then", ref("entity_" + set.key())));
        }
        source.put("allOf", narrowing);
        return source;
    }

    /** What one entity set allows in a source that names it. */
    private static Map<String, Object> entitySet(EntitySet set) {
        List<String> fields = set.fields().stream().map(Field::name).toList();
        Map<String, Object> enumMaps = new LinkedHashMap<>();
        for (Field f : set.fields()) {
            if (f.type().kind() == Kind.ENUM) {
                enumMaps.put(f.name(), labels(text(1, 200), oneOf(f.type().values())));
            }
        }
        Map<String, Object> mapped = object("type", "object", "required", List.of("field"));
        mapped.put("properties", object("field", oneOf(fields)));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("columns", object("additionalProperties", anyOf(mapped, ref("ignored"))));
        p.put("value_maps", object("propertyNames", oneOf(fields)));
        p.put("enum_maps", object("propertyNames", oneOf(List.copyOf(enumMaps.keySet())), "properties", enumMaps));
        List<String> rules = set.rowRules().stream().map(RowRule::key).sorted().toList();
        p.put("row_rules", object("propertyNames", oneOf(rules)));
        return object("properties", p);
    }

    private static Map<String, Object> sourceRef() {
        Map<String, Object> rowHash = closed(object("rule", constant("row_hash")), "rule");
        Map<String, Object> column =
                closed(object("rule", constant("column"), "column", text(1, 200)), "rule", "column");
        return anyOf(rowHash, column);
    }

    /** An object with exactly these properties, of which {@code required} must be present. */
    private static Map<String, Object> closed(Map<String, Object> properties, String... required) {
        Map<String, Object> out = object("type", "object", "additionalProperties", false);
        if (required.length > 0) {
            out.put("required", List.of(required));
        }
        out.put("properties", properties);
        return out;
    }

    /** An object of any keys matching {@code names}, each value matching {@code values}. */
    private static Map<String, Object> labels(Map<String, Object> names, Map<String, Object> values) {
        return object("type", "object", "propertyNames", names, "additionalProperties", values);
    }

    private static Map<String, Object> anyOf(Object... schemas) {
        return object("anyOf", Arrays.asList(schemas));
    }

    private static Map<String, Object> oneOf(List<?> values) {
        return object("enum", values);
    }

    private static Map<String, Object> constant(Object value) {
        return object("const", value);
    }

    private static Map<String, Object> ref(String def) {
        return object("$ref", "#/$defs/" + def);
    }

    private static Map<String, Object> text(int min, int max) {
        return object("type", "string", "minLength", min, "maxLength", max);
    }

    /** A map of the given keys and values, in that order. */
    private static Map<String, Object> object(Object... keysAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }
}
