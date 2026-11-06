package com.rincoltech.bms.core.imports.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Checks a JSON value (plain maps, lists, strings, numbers, booleans and null) against a JSON Schema, for the keywords
 * {@link TemplateSchema} writes and no others (issue #129): {@code $ref} to {@code #/$defs/...}, {@code type},
 * {@code const}, {@code enum}, {@code properties}, {@code required}, {@code additionalProperties}, {@code propertyNames},
 * {@code minProperties}, {@code items}, {@code minItems}, {@code maxItems}, {@code minLength}, {@code maxLength},
 * {@code pattern}, {@code minimum}, {@code maximum}, {@code anyOf}, {@code allOf}, {@code if} and {@code then}. A keyword it
 * does not know is a programming error and throws, so the published schema can never say more than is checked. Every
 * violation is reported with its JSON Pointer; values are never quoted, since a mapping's labels are the customer's words.
 * Plain Java, no Spring.
 */
final class SchemaCheck {

    /** One violation: where (a JSON Pointer, {@code ""} for the whole document) and what, in plain words. */
    record Violation(String pointer, String reason) {}

    private static final List<String> KNOWN = List.of(
            "$schema",
            "$id",
            "title",
            "$defs",
            "$ref",
            "type",
            "const",
            "enum",
            "properties",
            "required",
            "additionalProperties",
            "propertyNames",
            "minProperties",
            "items",
            "minItems",
            "maxItems",
            "minLength",
            "maxLength",
            "pattern",
            "minimum",
            "maximum",
            "anyOf",
            "allOf",
            "if",
            "then");

    private final Map<String, Object> root;

    private SchemaCheck(Map<String, Object> root) {
        this.root = root;
    }

    /** The violations, each once: the shared shape of a source and its entity set's narrowing can both refuse one value. */
    static List<Violation> check(Map<String, Object> schema, Object value) {
        List<Violation> out = new ArrayList<>();
        new SchemaCheck(schema).validate(schema, value, "", out);
        return List.copyOf(new LinkedHashSet<>(out));
    }

    @SuppressWarnings("unchecked")
    private void validate(Map<String, Object> schema, Object value, String at, List<Violation> out) {
        for (String keyword : schema.keySet()) {
            if (!KNOWN.contains(keyword)) {
                throw new IllegalStateException("schema keyword not supported: " + keyword);
            }
        }
        if (schema.get("$ref") instanceof String ref) {
            validate(resolve(ref), value, at, out);
        }
        if (schema.containsKey("type") && !hasType(value, schema.get("type"))) {
            out.add(new Violation(at, "must be " + typeWords(schema.get("type"))));
            return;
        }
        if (schema.containsKey("const") && !same(schema.get("const"), value)) {
            out.add(new Violation(at, "must be " + words(schema.get("const"))));
        }
        if (schema.get("enum") instanceof List<?> allowed && allowed.stream().noneMatch(a -> same(a, value))) {
            out.add(new Violation(at, allowed.isEmpty() ? "is not allowed here" : "must be one of " + listed(allowed)));
        }
        if (value instanceof String s) {
            int length = s.codePointCount(0, s.length());
            if (schema.get("minLength") instanceof Number min && length < min.intValue()) {
                out.add(new Violation(
                        at, min.intValue() == 1 ? "must not be empty" : "must be at least " + min + " characters"));
            }
            if (schema.get("maxLength") instanceof Number max && length > max.intValue()) {
                out.add(new Violation(at, "must be at most " + max + " characters"));
            }
            if (schema.get("pattern") instanceof String p
                    && !Pattern.compile(strictEnd(p)).matcher(s).find()) {
                out.add(new Violation(at, "is not in the expected form"));
            }
        }
        if (isNumber(value)) {
            BigDecimal n = decimal(value);
            if (schema.get("minimum") instanceof Number min && n.compareTo(decimal(min)) < 0) {
                out.add(new Violation(at, "must be at least " + min));
            }
            if (schema.get("maximum") instanceof Number max && n.compareTo(decimal(max)) > 0) {
                out.add(new Violation(at, "must be at most " + max));
            }
        }
        if (value instanceof Map<?, ?> object) {
            object((Map<String, Object>) object, schema, at, out);
        }
        if (value instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.intValue()) {
                out.add(new Violation(at, "must hold at least " + min + " item" + (min.intValue() == 1 ? "" : "s")));
            }
            if (schema.get("maxItems") instanceof Number max && list.size() > max.intValue()) {
                out.add(new Violation(at, "must hold at most " + max + " items"));
            }
            if (schema.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < list.size(); i++) {
                    validate((Map<String, Object>) items, list.get(i), at + "/" + i, out);
                }
            }
        }
        if (schema.get("allOf") instanceof List<?> all) {
            for (Object s : all) {
                validate((Map<String, Object>) s, value, at, out);
            }
        }
        if (schema.get("anyOf") instanceof List<?> any) {
            boolean matched = false;
            for (Object s : any) {
                List<Violation> trial = new ArrayList<>();
                validate((Map<String, Object>) s, value, at, trial);
                if (trial.isEmpty()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.add(new Violation(at, "matches none of the forms allowed here"));
            }
        }
        if (schema.get("if") instanceof Map<?, ?> condition && schema.get("then") instanceof Map<?, ?> then) {
            List<Violation> trial = new ArrayList<>();
            validate((Map<String, Object>) condition, value, at, trial);
            if (trial.isEmpty()) {
                validate((Map<String, Object>) then, value, at, out);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void object(Map<String, Object> object, Map<String, Object> schema, String at, List<Violation> out) {
        if (schema.get("minProperties") instanceof Number min && object.size() < min.intValue()) {
            out.add(new Violation(at, "must hold at least " + min + " entr" + (min.intValue() == 1 ? "y" : "ies")));
        }
        if (schema.get("required") instanceof List<?> required) {
            for (Object name : required) {
                if (!object.containsKey((String) name)) {
                    out.add(new Violation(at + "/" + escape((String) name), "is required"));
                }
            }
        }
        Map<String, Object> properties =
                schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        Object additional = schema.get("additionalProperties");
        for (Map.Entry<String, Object> e : object.entrySet()) {
            String name = e.getKey();
            String here = at + "/" + escape(name);
            if (schema.get("propertyNames") instanceof Map<?, ?> names) {
                List<Violation> trial = new ArrayList<>();
                validate((Map<String, Object>) names, name, here, trial);
                if (!trial.isEmpty()) {
                    out.add(new Violation(here, "is not a name allowed here"));
                    continue;
                }
            }
            if (properties.get(name) instanceof Map<?, ?> property) {
                validate((Map<String, Object>) property, e.getValue(), here, out);
            } else if (Boolean.FALSE.equals(additional)) {
                out.add(new Violation(here, "is not allowed here"));
            } else if (additional instanceof Map<?, ?> rest) {
                validate((Map<String, Object>) rest, e.getValue(), here, out);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(String ref) {
        if (!ref.startsWith("#/$defs/")) {
            throw new IllegalStateException("only local $defs references are supported: " + ref);
        }
        Object def = ((Map<String, Object>) root.get("$defs")).get(ref.substring("#/$defs/".length()));
        if (!(def instanceof Map<?, ?> m)) {
            throw new IllegalStateException("unknown reference " + ref);
        }
        return (Map<String, Object>) m;
    }

    private static boolean hasType(Object value, Object type) {
        if (type instanceof Collection<?> types) {
            return types.stream().anyMatch(t -> hasType(value, t));
        }
        return switch ((String) type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            case "number" -> isNumber(value);
            case "integer" -> isNumber(value) && isWhole(decimal(value));
            default -> throw new IllegalStateException("unknown type " + type);
        };
    }

    private static String typeWords(Object type) {
        return switch (String.valueOf(type)) {
            case "object" -> "an object";
            case "array" -> "a list";
            case "string" -> "a text";
            case "boolean" -> "true or false";
            case "integer" -> "a whole number";
            case "number" -> "a number";
            default -> String.valueOf(type);
        };
    }

    /** JSON equality: numbers by value, so {@code 1} and {@code 1.0} are the same. */
    private static boolean same(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        return Objects.equals(a, b);
    }

    private static boolean isNumber(Object v) {
        return v instanceof Number && !(v instanceof Double d && (d.isNaN() || d.isInfinite()));
    }

    private static boolean isWhole(BigDecimal n) {
        return n.signum() == 0 || n.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal decimal(Object n) {
        return switch (n) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> BigDecimal.valueOf(f.doubleValue());
            case Number x -> BigDecimal.valueOf(x.longValue());
            default -> throw new IllegalArgumentException("not a number");
        };
    }

    /**
     * A JSON Schema pattern follows ECMA-262, where a final {@code $} is the end of the text; Java's {@code $} also matches
     * before a final line break, so {@code "UGX\n"} would pass {@code ^[A-Z]{3}$} (review of #866). A final {@code $} is read
     * as {@code \z}.
     */
    static String strictEnd(String pattern) {
        return pattern.endsWith("$") && !pattern.endsWith("\\$")
                ? pattern.substring(0, pattern.length() - 1) + "\\z"
                : pattern;
    }

    private static String listed(List<?> constants) {
        return String.join(", ", constants.stream().map(SchemaCheck::words).toList());
    }

    /** A schema's own constants, never a value from the document, are quoted. */
    private static String words(Object constant) {
        return constant instanceof String s ? "\"" + s + "\"" : String.valueOf(constant);
    }

    /** RFC 6901: {@code ~} as {@code ~0} and {@code /} as {@code ~1}. */
    static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
