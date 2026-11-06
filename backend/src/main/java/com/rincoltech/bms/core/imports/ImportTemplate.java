package com.rincoltech.bms.core.imports;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    /**
     * The writer of a batch of one entity set, run by the commit inside the batch's own transaction (issue #133), and by
     * the dry run inside its rolled-back one (#132). A loader whose behaviour depends on state it captures (a directory,
     * a date, a setting) overrides {@link BatchWriter#identity()} to name that state, as {@code exportWriter} does: by
     * default two loaders of one lambda are the same writer, so a dry run would stand for a commit it did not rehearse.
     */
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

    // JUSTIFICATION-A3: issue #130 extends the template contract itself (personal and key fields, ranges, checks).
    /**
     * One target field of an entity set (issue #130 adds the last four). {@code personal}: the field holds personal data (a
     * name, a contact), so no message may interpolate it; a {@code phone} or {@code nin} field is personal whatever the
     * flag says ({@link #isPersonal()}). {@code key}: the entity set's code, at most one per set, a {@code text} field; a
     * {@code ref} to the set names it, and two rows of the run with the same key ignoring case and spaces are reported
     * ({@code CODE_DUPLICATE}). {@code min} and {@code max}: the inclusive range of a {@code money} or {@code qty} field, or
     * null for no bound ({@code VALUE_OUT_OF_RANGE}).
     */
    record Field(
            String name,
            FieldType type,
            boolean required,
            Normaliser normaliser,
            boolean personal,
            boolean key,
            BigDecimal min,
            BigDecimal max) {

        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            normaliser = normaliser == null ? Normaliser.TRIM : normaliser;
        }

        public Field(String name, FieldType type, boolean required, Normaliser normaliser) {
            this(name, type, required, normaliser, false, false, null, null);
        }

        public static Field required(String name, FieldType type) {
            return new Field(name, type, true, null);
        }

        public static Field optional(String name, FieldType type) {
            return new Field(name, type, false, null);
        }

        public Field normalisedBy(Normaliser normaliser) {
            return new Field(name, type, required, normaliser, personal, key, min, max);
        }

        /** Marks the field as personal data: never interpolated into a message. */
        public Field personalData() {
            return new Field(name, type, required, normaliser, true, key, min, max);
        }

        /** Marks the field as the entity set's code. */
        public Field asKey() {
            return new Field(name, type, required, normaliser, personal, true, min, max);
        }

        /** The inclusive range of a money or qty field; either bound may be null. */
        public Field range(BigDecimal min, BigDecimal max) {
            return new Field(name, type, required, normaliser, personal, key, min, max);
        }

        /** Whether the field holds personal data: flagged so, or a phone or a NIN. */
        public boolean isPersonal() {
            return personal || type.kind() == Kind.PHONE || type.kind() == Kind.NIN;
        }
    }

    /**
     * A cross-field rule of one entity set (spec section 3.4; chapter 13 section 13.6 for lending, quantity and price rules
     * for retail), run by the validation of #130 on each row after its fields are typed. It sees the typed values by field
     * of the fields that passed their own checks (text, {@code Long} for money, {@code BigDecimal} for qty,
     * {@code LocalDate}, {@code Instant}, {@code Boolean}) and answers its findings. A finding names a code of the
     * template's catalogue and the field it is on; its parameters may only be placeholders of
     * {@link ImportTemplates#MESSAGE_PLACEHOLDERS} and never carry a value of the row.
     */
    @FunctionalInterface
    interface RowCheck {

        List<RowFinding> check(Map<String, Object> values);
    }

    /** One finding of a {@link RowCheck}: a code, the field it is on (or null for the row), and non-personal parameters. */
    record RowFinding(String code, String field, Map<String, String> params) {

        public RowFinding {
            Objects.requireNonNull(code, "code");
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** The cross-field rules of one entity set, run in this order; none by default. */
    default List<RowCheck> rowChecks(String entitySet) {
        return List.of();
    }

    /**
     * The keys of one entity set the business already holds, among {@code keys} (spec section 3.4: a reference names a
     * record "in the import or the tenant"). The validation asks only for the keys a {@code ref} names that no row of the
     * run holds, each in compare form: the key field's normaliser applied, then upper case with every space removed. Called
     * inside a read-only transaction bound to the business, so it reads only the business's own rows (RLS). None by
     * default.
     */
    default Set<String> existingKeys(String entitySet, Set<String> keys) {
        return Set.of();
    }

    /**
     * Whether the business holds the record of one entity set with this id (issue #131): the target of the review's
     * {@code link_member}, which links a staged row to a record the business already has instead of creating one. Called
     * inside the resolution's transaction, bound to the business. None by default, so a template that does not answer
     * refuses every link to a record.
     */
    default boolean recordExists(String entitySet, UUID id) {
        return false;
    }

    /**
     * A new forecast of what the commit will post (issue #132): the preview's predicted balances, computed by the rules the
     * loader posts by. {@link ImportForecast#NONE} by default, for a template that posts no opening balance.
     */
    default ImportForecast forecast() {
        return ImportForecast.NONE;
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
