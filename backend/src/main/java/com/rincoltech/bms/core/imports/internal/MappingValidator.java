package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportMappings.Problem;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplates;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The validator of a mapping (issue #129, spec section 4.2): the document against its template version's JSON Schema, then
 * what a schema cannot say, then, given a file's headers, every column neither mapped nor ignored. Header names are compared
 * as the parser finds a header row, trimmed and ignoring case ({@link Parsing#key}). Every problem carries a code of
 * {@link ImportTemplates#SHARED_CODES} and the message built from it; none carries a cell's value. Plain Java, no Spring.
 */
final class MappingValidator {

    private static final Map<String, IssueCode> CODES = new LinkedHashMap<>();

    static {
        for (IssueCode c : ImportTemplates.SHARED_CODES) {
            CODES.put(c.code(), c);
        }
    }

    private MappingValidator() {}

    /**
     * The problems of a document on its own. {@code templates} finds a registered template by key and version; a document
     * whose template is not found has the one problem {@code TEMPLATE_UNKNOWN}. Otherwise every {@code MAPPING_INVALID}
     * problem of the schema, then the checks a schema cannot make, on every part the schema accepted.
     */
    static List<Problem> check(
            Map<String, Object> document, BiFunction<String, Integer, Optional<ImportTemplate>> templates) {
        List<Problem> out = new ArrayList<>();
        if (document == null) {
            out.add(schemaProblem("", "must be an object"));
            return out;
        }
        if (!(document.get("template") instanceof String key)) {
            out.add(schemaProblem("/template", "is required and must be a text"));
            return out;
        }
        if (!(document.get("template_version") instanceof Number v) || v.doubleValue() != Math.rint(v.doubleValue())) {
            out.add(schemaProblem("/template_version", "is required and must be a whole number"));
            return out;
        }
        Optional<ImportTemplate> found = templates.apply(key, v.intValue());
        if (found.isEmpty()) {
            out.add(problem(
                    "TEMPLATE_UNKNOWN", null, null, null, "/template", Map.of("template", key + "_v" + v.intValue())));
            return out;
        }
        ImportTemplate template = found.get();
        List<String> wrong = new ArrayList<>();
        for (SchemaCheck.Violation violation : SchemaCheck.check(TemplateSchema.of(template), document)) {
            out.add(schemaProblem(violation.pointer(), violation.reason()));
            wrong.add(violation.pointer());
        }
        // The later checks read a part's shape, so they run on every part the schema accepted (review of #866): one bad
        // currency does not hide a source's missing required field.
        if (!touched(wrong, "/time_zone") && document.get("time_zone") instanceof String zone) {
            try {
                ZoneId.of(zone);
            } catch (DateTimeException e) {
                out.add(problem("ZONE_UNKNOWN", null, null, null, "/time_zone", Map.of("value", zone)));
            }
        }
        for (Map.Entry<String, Map<String, Object>> source : sources(document).entrySet()) {
            if (!touched(wrong, "/sources/" + SchemaCheck.escape(source.getKey()))) {
                checkSource(template, source.getKey(), source.getValue(), out);
            }
        }
        return out;
    }

    /** Whether a violation is at {@code pointer} or inside it. */
    private static boolean touched(List<String> pointers, String pointer) {
        return pointers.stream().anyMatch(p -> p.equals(pointer) || p.startsWith(pointer + "/"));
    }

    /**
     * One source of a document already valid on its own, against the headers its file has: {@code COLUMN_UNMAPPED} once
     * per header neither mapped nor ignored, {@code REQUIRED_COLUMN_MISSING} per required field whose column is not among
     * them, {@code SOURCE_UNKNOWN} for a source the mapping does not have.
     */
    static List<Problem> headers(
            ImportTemplate template, Map<String, Object> document, String sourceKey, List<String> headers) {
        List<Problem> out = new ArrayList<>();
        Map<String, Object> source = sources(document).get(sourceKey);
        if (source == null) {
            out.add(problem("SOURCE_UNKNOWN", sourceKey, null, null, "/sources", Map.of()));
            return out;
        }
        Map<String, Map<String, Object>> columns = columns(source);
        Set<String> known = new HashSet<>();
        columns.keySet().forEach(c -> known.add(Parsing.key(c)));
        Set<String> present = new LinkedHashSet<>();
        for (String header : headers) {
            if (present.add(Parsing.key(header)) && !known.contains(Parsing.key(header))) {
                out.add(problem("COLUMN_UNMAPPED", sourceKey, header, null, null, Map.of()));
            }
        }
        EntitySet set = entitySet(template, (String) source.get("entity_set"));
        for (Map.Entry<String, Map<String, Object>> c : columns.entrySet()) {
            Object field = c.getValue().get("field");
            if (field != null && required(set, (String) field) && !present.contains(Parsing.key(c.getKey()))) {
                out.add(problem("REQUIRED_COLUMN_MISSING", sourceKey, c.getKey(), (String) field, null, Map.of()));
            }
        }
        return out;
    }

    private static void checkSource(
            ImportTemplate template, String key, Map<String, Object> source, List<Problem> out) {
        EntitySet set = entitySet(template, (String) source.get("entity_set"));
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> feeds = new LinkedHashMap<>();
        Set<String> kept = new HashSet<>();
        for (Map.Entry<String, Map<String, Object>> c : columns(source).entrySet()) {
            String column = c.getKey();
            if (seen.putIfAbsent(Parsing.key(column), column) != null) {
                out.add(problem("COLUMN_DUPLICATE", key, column, null, null, Map.of()));
                continue;
            }
            if (c.getValue().get("field") instanceof String field) {
                kept.add(Parsing.key(column));
                String first = feeds.putIfAbsent(field, column);
                if (first != null) {
                    out.add(problem("FIELD_MAPPED_TWICE", key, column, field, null, Map.of("other", first)));
                }
            }
        }
        for (Field f : set.fields()) {
            if (f.required() && !feeds.containsKey(f.name())) {
                out.add(problem("REQUIRED_FIELD_UNMAPPED", key, null, f.name(), null, Map.of("entity", set.key())));
            }
        }
        for (String maps : List.of("value_maps", "enum_maps")) {
            if (source.get(maps) instanceof Map<?, ?> byField) {
                for (Object field : byField.keySet()) {
                    if (!feeds.containsKey((String) field)) {
                        out.add(problem("MAP_FIELD_UNMAPPED", key, null, (String) field, null, Map.of()));
                    }
                }
            }
        }
        if (source.get("source_ref") instanceof Map<?, ?> ref
                && "column".equals(ref.get("rule"))
                && !kept.contains(Parsing.key((String) ref.get("column")))) {
            out.add(problem("SOURCE_REF_COLUMN_UNKNOWN", key, (String) ref.get("column"), null, null, Map.of()));
        }
    }

    /** The required columns of one source: those whose field is required, for an automatic header row. */
    static Set<String> requiredColumns(ImportTemplate template, Map<String, Object> source) {
        EntitySet set = entitySet(template, (String) source.get("entity_set"));
        Set<String> out = new LinkedHashSet<>();
        columns(source).forEach((column, target) -> {
            if (target.get("field") instanceof String field && required(set, field)) {
                out.add(column);
            }
        });
        return out;
    }

    /** The columns one source ignores, never stored (spec section 6.1). */
    static Set<String> ignoredColumns(Map<String, Object> source) {
        Set<String> out = new LinkedHashSet<>();
        columns(source).forEach((column, target) -> {
            if (Boolean.TRUE.equals(target.get("ignore"))) {
                out.add(column);
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> sources(Map<String, Object> document) {
        return document.get("sources") instanceof Map<?, ?> m ? (Map<String, Map<String, Object>>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> columns(Map<String, Object> source) {
        return source.get("columns") instanceof Map<?, ?> m ? (Map<String, Map<String, Object>>) m : Map.of();
    }

    private static EntitySet entitySet(ImportTemplate template, String key) {
        return template.entitySets().stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("entity set checked by the schema"));
    }

    private static boolean required(EntitySet set, String field) {
        return set.fields().stream().anyMatch(f -> f.name().equals(field) && f.required());
    }

    private static Problem schemaProblem(String pointer, String reason) {
        return problem("MAPPING_INVALID", null, null, null, pointer, Map.of("reason", reason));
    }

    /** A problem with its catalogue severity and its message, each placeholder filled from where it is and {@code extra}. */
    static Problem problem(
            String code, String source, String column, String field, String pointer, Map<String, String> extra) {
        IssueCode c = CODES.get(code);
        if (c == null) {
            throw new IllegalStateException("not in the catalogue: " + code);
        }
        Map<String, String> values = new LinkedHashMap<>(extra);
        values.putIfAbsent("source", source);
        values.putIfAbsent("column", column);
        values.putIfAbsent("field", field);
        values.putIfAbsent("pointer", pointer == null || pointer.isEmpty() ? "the top" : pointer);
        String message = c.message();
        for (Map.Entry<String, String> v : values.entrySet()) {
            if (v.getValue() != null) {
                message = message.replace("{" + v.getKey() + "}", v.getValue());
            }
        }
        return new Problem(code, c.severity().key(), source, column, field, pointer, message);
    }
}
