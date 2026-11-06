package com.rincoltech.bms.core.imports;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A canonical template: a vertical's import contract (issue #129, spec section 4.1, ADR-027 decision 3). It is code in the
 * vertical, registered with the core as a Spring bean that implements this interface (the registry of chapter 5 section
 * 5.4.3), so {@code core.imports} reads it and never names a vertical. It declares its entity sets in commit (dependency)
 * order, each with typed fields, required flags, normalisers and the row rules a mapping may switch on; its own issue codes,
 * which the registry adds to the shared catalogue of {@link ImportTemplates#SHARED_CODES}; and its loader, the
 * {@link BatchWriter} the commit runs for a batch of one entity set.
 *
 * <p>A template changes only by a new version ({@code retail} version 2 beside version 1); a run keeps the version it started
 * with, and every batch records the key and version of the mapping it was parsed with. The registry refuses at start-up a
 * template it cannot trust: a key or version registered twice, a key, field or code that is not a plain lower-case or
 * upper-case name, a field declared twice, a {@code ref} to an entity set the template does not have, an {@code enum} with
 * no values, a {@code text} of no length, or a code that clashes with a shared one.
 */
public interface ImportTemplate {

    /** Lower-case letters, digits and underscores, starting with a letter: {@code retail}, {@code lending_loans}. */
    String key();

    /** 1, 2, ...: a new version for any change to the entity sets, fields, rules or codes. */
    int version();

    /** The entity sets in commit order: a {@code ref} points only to a set before it or to itself. */
    List<EntitySet> entitySets();

    /** The template's own codes; the shared codes of the core are added by the registry. */
    List<IssueCode> issueCodes();

    /** The writer of a batch of one entity set, run by the commit inside the batch's own transaction (issue #133). */
    BatchWriter loader(String entitySet);

    /** The name a person reads: {@code retail_v1}. */
    default String id() {
        return key() + "_v" + version();
    }

    /** The kinds of field of spec section 3.2. */
    enum Kind {
        TEXT,
        MONEY,
        QTY,
        DATE,
        DATETIME,
        PHONE,
        NIN,
        ENUM,
        BOOL,
        REF;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A field's type: {@code text(n)} with its longest length, {@code money} (whole shillings), {@code qty} (up to three
     * decimal places), {@code date}, {@code datetime}, {@code phone}, {@code nin}, {@code enum(...)} with its values,
     * {@code bool}, or {@code ref(entity set)}.
     */
    record FieldType(Kind kind, int maxLength, List<String> values, String ref) {

        public FieldType {
            Objects.requireNonNull(kind, "kind");
            values = values == null ? List.of() : List.copyOf(values);
        }

        public static FieldType text(int maxLength) {
            return new FieldType(Kind.TEXT, maxLength, List.of(), null);
        }

        public static FieldType money() {
            return new FieldType(Kind.MONEY, 0, List.of(), null);
        }

        public static FieldType qty() {
            return new FieldType(Kind.QTY, 0, List.of(), null);
        }

        public static FieldType date() {
            return new FieldType(Kind.DATE, 0, List.of(), null);
        }

        public static FieldType datetime() {
            return new FieldType(Kind.DATETIME, 0, List.of(), null);
        }

        public static FieldType phone() {
            return new FieldType(Kind.PHONE, 0, List.of(), null);
        }

        public static FieldType nin() {
            return new FieldType(Kind.NIN, 0, List.of(), null);
        }

        public static FieldType enumOf(String... values) {
            return new FieldType(Kind.ENUM, 0, Arrays.asList(values), null);
        }

        public static FieldType bool() {
            return new FieldType(Kind.BOOL, 0, List.of(), null);
        }

        public static FieldType ref(String entitySet) {
            return new FieldType(Kind.REF, 0, List.of(), entitySet);
        }

        /** As spec section 3.2 writes it: {@code text(120)}, {@code enum(sale|adjustment)}, {@code ref(products)}. */
        public String spec() {
            return switch (kind) {
                case TEXT -> "text(" + maxLength + ")";
                case ENUM -> "enum(" + String.join("|", values) + ")";
                case REF -> "ref(" + ref + ")";
                default -> kind.key();
            };
        }
    }

    /**
     * A shared normaliser (spec section 3.3): a named function from the cell's text to the value validated. The validation
     * of #130 reports each change it makes as an {@code info} issue with the original value, never a silent change. A
     * vertical passes the same function its forms use (for retail codes {@code RetailCatalogue.normaliseCode}), under a
     * name of its own.
     */
    record Normaliser(String name, UnaryOperator<String> function) {

        /** Leading and trailing spaces removed. */
        public static final Normaliser TRIM = new Normaliser("trim", s -> s == null ? null : s.strip());

        /** Trimmed, and every run of spaces inside one space. */
        public static final Normaliser COLLAPSE_SPACES = new Normaliser(
                "collapse_spaces", s -> s == null ? null : s.strip().replaceAll("\\s+", " "));

        /** Spaces collapsed and upper case, for codes compared ignoring case. */
        public static final Normaliser UPPER = new Normaliser(
                "upper",
                s -> s == null ? null : s.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT));

        public Normaliser {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(function, "function");
        }

        public String apply(String raw) {
            return function.apply(raw);
        }
    }

    /** One target field of an entity set. */
    record Field(String name, FieldType type, boolean required, Normaliser normaliser) {

        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            normaliser = normaliser == null ? Normaliser.TRIM : normaliser;
        }

        public static Field required(String name, FieldType type) {
            return new Field(name, type, true, null);
        }

        public static Field optional(String name, FieldType type) {
            return new Field(name, type, false, null);
        }

        public Field normalisedBy(Normaliser normaliser) {
            return new Field(name, type, required, normaliser);
        }
    }

    /**
     * The row rules a template may offer an entity set, each switched on per source by the mapping (spec section 4.2):
     * sub-header rows that carry the month for the rows below them, a totals row at the first or last position to ignore,
     * and the cell texts that mark a row as a placeholder.
     */
    enum RowRule {
        SUBHEADER_MONTH,
        TOTALS_ROW,
        PLACEHOLDER_ROWS;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One entity set: a kind of record a batch creates, with its fields and the row rules a mapping may use. */
    record EntitySet(String key, List<Field> fields, Set<RowRule> rowRules) {

        public EntitySet {
            Objects.requireNonNull(key, "key");
            fields = List.copyOf(fields);
            rowRules = rowRules == null ? Set.of() : Set.copyOf(rowRules);
        }

        public EntitySet(String key, List<Field> fields) {
            this(key, fields, Set.of());
        }
    }

    /**
     * How an issue weighs (spec section 3.4 and its table): {@code info}, {@code warning} and {@code blocking} on a row;
     * {@code batch_fails} for a whole-file problem; {@code refused} for a request, an upload, a run or a mapping the service
     * refuses.
     */
    enum Severity {
        INFO,
        WARNING,
        BLOCKING,
        BATCH_FAILS,
        REFUSED;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One stable code of the catalogue (spec section 3.4): never renamed or reused; a retired code stays, marked retired.
     * The message is written for the customer, with placeholders such as {@code {field}}, {@code {file}},
     * {@code {line}} and {@code {column}}, and never carries the value of a personal field.
     */
    record IssueCode(String code, Severity severity, String message, boolean retired) {

        public IssueCode {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
        }

        public IssueCode(String code, Severity severity, String message) {
            this(code, severity, message, false);
        }
    }
}
