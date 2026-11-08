package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.Kind;
import com.rincoltech.bms.core.imports.ImportTemplate.RowCheck;
import com.rincoltech.bms.core.imports.ImportTemplate.RowFinding;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.kernel.NationalIds;
import com.rincoltech.bms.kernel.PhoneNumbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The validation engine of customer data onboarding (issue #130, spec sections 3.3 and 3.4): each staged row against the
 * template and the mapping version its batch recorded. Plain Java, no Spring and no database, so the same rows always give
 * the same issues: the service feeds it the run's rows and stores what it answers.
 *
 * <p>It works in two passes over the run's batches, each in commit order (the template's entity sets, then the batch's
 * order) and each batch in line order. {@link #index} learns what the run holds: the first line of each code, each
 * {@code source_ref} and each NIN, and the codes every reference names. Between the passes the caller answers
 * {@link #missingReferences} with the keys the business already holds ({@link #addExisting}). {@link #validate} then checks
 * each row and answers its typed values, its {@code source_ref} and its issues, in a fixed order: the fields in the
 * template's order (required, normaliser, type, range, cutover), then the row's reference, then per field its reference to
 * another batch and its duplicates, then the template's cross-field rules.
 *
 * <p>Normalisers are the shared form functions: the field's own {@link ImportTemplate.Normaliser} for text, codes and
 * references ({@code RetailCatalogue.normaliseCode} for retail codes), {@link PhoneNumbers#normaliseUganda} for a phone and
 * {@link NationalIds#normaliseNin} for a NIN (chapter 13 section 13.6). Every change one makes is an {@code info}
 * {@code VALUE_NORMALISED} with the original. A value map or enum map of the mapping is the mapping's explicit word and is
 * applied before the normaliser without an issue.
 *
 * <p>A message is built from the code's template with only {@link ImportTemplates#MESSAGE_PLACEHOLDERS}, filled by the
 * engine with the field name, the batch's file name, line numbers and the rule; a cell's text never reaches a message. The
 * original text an issue concerns travels beside it ({@link Finding#original}) for the review screen alone.
 */
final class RowValidator {

    /** The severities of a row's issue; a code of another severity is never reported on a row. */
    static final Set<String> ROW_SEVERITIES = Set.of("info", "warning", "blocking");

    /** Rows of one batch the service reads at a time. */
    static final int CHUNK = 1_000;

    /** The longest {@code source_ref} kept as written; a longer column value is kept by its hash. */
    static final int MAX_REF_TEXT = 200;

    private static final Pattern SAFE_NUMBER = Pattern.compile("^[+-]?\\d{1,15}(\\.\\d{1,6})?$");
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z_]{1,39}$");

    /** What a message shows instead of a rule's parameter that is not a number, code or name, or holds a personal value. */
    static final String HIDDEN = "(hidden)";

    /** The longest message, the size of {@code import_issues.message}. */
    static final int MAX_MESSAGE = 500;

    /** The longest file name a message shows. */
    static final int MAX_FILE_NAME = 100;

    /** Commas, or spaces, only as separators of groups of three digits: {@code 12,500}, {@code 1 234 567.5}. */
    private static final Pattern GROUPED = Pattern.compile("^[+-]?[1-9]\\d{0,2}(,\\d{3})+(\\.\\d+)?$");

    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]");
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern SHORT_DATE = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})$");
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$");
    private static final Set<String> TRUE = Set.of("true", "yes", "y", "1");
    private static final Set<String> FALSE = Set.of("false", "no", "n", "0");

    /** The reader's types of a cell that holds no value: a formula's error, and a formula saved without its value. */
    static final Set<String> UNREAD_CELLS = Set.of("error", XlsxRows.FORMULA);

    /** One batch: its id, its file name, and the mapping version and source it was parsed with. */
    record BatchInput(UUID batchId, String file, Map<String, Object> mapping, String sourceKey) {}

    /** A cell as read: its value (text, number, boolean or JSON) and the reader's type of it. */
    record RawCell(Object value, String type) {}

    /**
     * One staged row: its id, its line in the source, its kept cells by header name, and the review's edits in force by
     * field (issue #131): the text an operator gave a field in place of its cell, or null to leave it empty. The cells are
     * never changed; an edit is read in place of its field's cell, and the row's {@code source_ref} is always made from the
     * cells as stored.
     */
    record SourceRow(UUID rowId, int line, Map<String, RawCell> cells, Map<String, String> edits) {

        SourceRow {
            edits = edits == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(edits));
        }

        SourceRow(UUID rowId, int line, Map<String, RawCell> cells) {
            this(rowId, line, cells, Map.of());
        }
    }

    /**
     * One issue: its code, the code's severity, the field (null for the whole row), the message, and the original cell text
     * the issue is about (null when there is none). The original may be personal; it is never in the message.
     */
    record Finding(String code, String severity, String field, String message, String original, String suggestion) {

        Finding(String code, String severity, String field, String message, String original) {
            this(code, severity, field, message, original, null);
        }
    }

    /** What a row gives: its typed values by field, ready for JSON, its {@code source_ref}, and its issues in order. */
    record RowResult(Map<String, Object> normalised, String sourceRef, List<Finding> findings) {}

    private record Location(UUID batchId, String file, int line) {}

    private record Pending(String code, String field, Map<String, String> params, String original, String suggestion) {

        Pending(String code, String field, Map<String, String> params, String original) {
            this(code, field, params, original, null);
        }
    }

    private final ImportTemplate template;

    /** The template's rules over the run's rows, one per entity set that has them, for this validation (issue #136). */
    private final Map<String, Optional<ImportTemplate.SetRules>> setRules = new HashMap<>();

    private final Map<String, IssueCode> catalogue = new HashMap<>();
    private final Instant cutoverAt;
    private final Map<UUID, Plan> plans = new HashMap<>();
    private final Map<String, Map<String, Location>> firstKey = new HashMap<>();
    private final Map<String, Map<String, Location>> firstRef = new HashMap<>();
    private final Map<String, Map<String, Location>> firstNin = new HashMap<>();
    private final Map<String, Set<String>> wanted = new TreeMap<>();
    private final Map<String, Set<String>> existing = new HashMap<>();

    /**
     * @param catalogue the template's whole catalogue ({@link ImportTemplates#catalogue})
     * @param cutoverAt the run's cutover moment, or null when none is agreed yet
     */
    RowValidator(ImportTemplate template, List<IssueCode> catalogue, Instant cutoverAt) {
        this.template = template;
        catalogue.forEach(c -> this.catalogue.put(c.code(), c));
        this.cutoverAt = cutoverAt;
    }

    /** Pass 1: learns the row's code, reference, NINs and the codes its references name. */
    void index(BatchInput batch, SourceRow row) {
        if (blank(row)) {
            return;
        }
        Plan p = plan(batch);
        Typed t = type(p, row);
        Location here = new Location(batch.batchId(), batch.file(), row.line());
        for (Field f : p.set.fields()) {
            Object v = t.values.get(f.name());
            if (!(v instanceof String s)) {
                continue;
            }
            if (f.key()) {
                firstKey.computeIfAbsent(p.set.key(), k -> new HashMap<>()).putIfAbsent(compare(s), here);
            } else if (f.type().kind() == Kind.NIN) {
                firstNin.computeIfAbsent(p.set.key() + "." + f.name(), k -> new HashMap<>())
                        .putIfAbsent(s, here);
            } else if (f.type().kind() == Kind.REF) {
                wanted.computeIfAbsent(f.type().ref(), k -> new TreeSet<>()).add(compare(s));
            }
        }
        if (t.sourceRef != null) {
            firstRef.computeIfAbsent(p.set.key(), k -> new HashMap<>()).putIfAbsent(t.sourceRef, here);
        }
        ImportTemplate.SetRules rules = rules(p);
        if (rules != null) {
            rules.index(setRow(batch, row, p, t));
        }
    }

    // JUSTIFICATION-A3: issue #136 runs the template's rules over the run's rows (SetRules) in both passes.
    /** The template's rules over the run's rows for the batch's entity set, made once per validation; null for none. */
    private ImportTemplate.SetRules rules(Plan p) {
        return setRules.computeIfAbsent(p.set.key(), k -> Optional.ofNullable(template.setRules(k)))
                .orElse(null);
    }

    /** The row as the set's rules read it: cell texts by field (value maps applied), typed values, row rules, the day. */
    private ImportTemplate.SetRow setRow(BatchInput batch, SourceRow row, Plan p, Typed t) {
        return new ImportTemplate.SetRow(
                batch.batchId(),
                row.line(),
                t.mapped,
                t.values,
                p.rowRules,
                cutoverAt == null ? null : LocalDate.ofInstant(cutoverAt, p.zone));
    }

    /**
     * A finding of the template's own rules as the engine reports it: its parameters only numbers, codes and names, never a
     * personal value of the row (shown as {@link #HIDDEN}), and its suggestion kept only on a field that is not personal and
     * when it holds no personal value of the row.
     */
    private Pending pending(Plan p, RowFinding finding, List<String> personal) {
        Map<String, String> params = new LinkedHashMap<>();
        finding.params().forEach((k, v) -> params.put(k, safeParam(v) && !holds(v, personal) ? v : HIDDEN));
        String suggestion = finding.suggestion();
        if (suggestion != null) {
            Field field = finding.field() == null
                    ? null
                    : p.set.fields().stream()
                            .filter(f -> f.name().equals(finding.field()))
                            .findFirst()
                            .orElse(null);
            if (field == null || field.isPersonal() || holds(suggestion, personal)) {
                suggestion = null;
            }
        }
        return new Pending(finding.code(), finding.field(), params, null, suggestion);
    }

    /** Between the passes: per entity set, in key order, the codes references name that no row of the run holds. */
    Map<String, Set<String>> missingReferences() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        wanted.forEach((set, keys) -> {
            Set<String> held = firstKey.getOrDefault(set, Map.of()).keySet();
            Set<String> missing = new TreeSet<>(keys);
            missing.removeAll(held);
            if (!missing.isEmpty()) {
                out.put(set, Collections.unmodifiableSet(missing));
            }
        });
        return out;
    }

    /** The codes of one entity set the business already holds, in compare form. */
    void addExisting(String entitySet, Set<String> keys) {
        Set<String> into = existing.computeIfAbsent(entitySet, k -> new HashSet<>());
        keys.forEach(k -> into.add(compare(k)));
    }

    /** Pass 2: the row's typed values, its reference and its issues. */
    RowResult validate(BatchInput batch, SourceRow row) {
        if (blank(row)) {
            Finding blank = render(batch.file(), row.line(), new Pending("BLANK_ROW", null, Map.of(), null));
            return new RowResult(Map.of(), null, List.of(blank));
        }
        Plan p = plan(batch);
        Typed t = type(p, row);
        ImportTemplate.SetRules rules = rules(p);
        if (rules != null) {
            // A row the template classes as no record (a sub-header, a placeholder): none of its fields is checked.
            RowFinding classed = rules.classify(setRow(batch, row, p, t));
            if (classed != null) {
                Finding finding = render(batch.file(), row.line(), pending(p, classed, personalValues(p, t)));
                return new RowResult(Map.of(), t.sourceRef, List.of(finding));
            }
        }
        Location here = new Location(batch.batchId(), batch.file(), row.line());
        if (t.sourceRef != null) {
            Location first = firstRef.getOrDefault(p.set.key(), Map.of()).get(t.sourceRef);
            if (first != null && !first.equals(here)) {
                t.add("DUPLICATE_SOURCE_REF", null, Map.of("other", other(first, here)), null);
            }
        }
        for (Field f : p.set.fields()) {
            if (!(t.values.get(f.name()) instanceof String s)) {
                continue;
            }
            String original = t.texts.get(f.name());
            if (f.type().kind() == Kind.REF) {
                String target = f.type().ref();
                String key = compare(s);
                if (!firstKey.getOrDefault(target, Map.of()).containsKey(key)
                        && !existing.getOrDefault(target, Set.of()).contains(key)) {
                    t.add("REF_UNKNOWN", f.name(), Map.of("entity", target), original);
                }
            }
            if (f.key()) {
                Location first = firstKey.getOrDefault(p.set.key(), Map.of()).get(compare(s));
                if (first != null && !first.equals(here)) {
                    t.add(
                            "CODE_DUPLICATE",
                            f.name(),
                            Map.of("other", other(first, here), "entity", p.set.key()),
                            original);
                }
            }
            if (f.type().kind() == Kind.NIN) {
                Location first = firstNin.getOrDefault(p.set.key() + "." + f.name(), Map.of())
                        .get(s);
                if (first != null && !first.equals(here)) {
                    t.add("NIN_DUPLICATE", f.name(), Map.of("other", other(first, here)), original);
                }
            }
        }
        Map<String, Object> values = Collections.unmodifiableMap(new LinkedHashMap<>(t.values));
        // A rule's parameter values: only numbers, codes and the template's names, never a personal value of the row.
        // One that is not is shown as HIDDEN rather than stopping the run on ordinary data (second review of #867).
        // JUSTIFICATION-A3: the Lead's ruling 6 and the second review's item 3.
        List<String> personal = personalValues(p, t);
        for (RowCheck check : template.rowChecks(p.set.key())) {
            for (RowFinding finding : check.check(values)) {
                t.pending.add(pending(p, finding, personal));
            }
        }
        if (rules != null) {
            ImportTemplate.SetResult result = rules.check(setRow(batch, row, p, t));
            result.findings().forEach(f -> t.pending.add(pending(p, f, personal)));
            result.values().forEach((field, v) -> {
                if (p.set.fields().stream().noneMatch(f -> f.name().equals(field))) {
                    throw new IllegalStateException("a set rule replaced a value that is not a field: " + field);
                }
                if (v == null) {
                    t.values.remove(field);
                } else {
                    t.values.put(field, v);
                }
            });
        }
        List<Finding> findings = new ArrayList<>(t.pending.size());
        for (Pending pending : t.pending) {
            findings.add(render(batch.file(), row.line(), pending));
        }
        Map<String, Object> normalised = new LinkedHashMap<>();
        for (Field f : p.set.fields()) {
            Object v = t.values.get(f.name());
            if (v != null) {
                normalised.put(f.name(), v instanceof LocalDate || v instanceof Instant ? v.toString() : v);
            }
        }
        return new RowResult(normalised, t.sourceRef, List.copyOf(findings));
    }

    /**
     * The only values a cross-field rule may give a placeholder (Lead ruling on #867): a number, a code in capitals
     * ({@code FAB-001} is not one, a rule's code such as {@code UGX} or {@code SALE_QTY} is), or the name of one of the
     * template's entity sets or fields. A person's name, phone or NIN is none of these.
     */
    boolean safeParam(String v) {
        if (SAFE_NUMBER.matcher(v).matches() || SAFE_CODE.matcher(v).matches()) {
            return true;
        }
        return template.entitySets().stream()
                .anyMatch(s -> s.key().equals(v)
                        || s.fields().stream().anyMatch(f -> f.name().equals(v)));
    }

    /** The message of a code, filled from the engine's own placeholders: never a cell's text. */
    private Finding render(String file, int line, Pending f) {
        IssueCode c = catalogue.get(f.code());
        if (c == null) {
            throw new IllegalStateException("not in the template's catalogue: " + f.code());
        }
        if (c.retired()) {
            throw new IllegalStateException("a retired code is never reported: " + f.code());
        }
        String severity = c.severity().key();
        if (!ROW_SEVERITIES.contains(severity)) {
            throw new IllegalStateException("not a code of a row: " + f.code());
        }
        Map<String, String> values = new HashMap<>();
        f.params().forEach((k, v) -> {
            if (!ImportTemplates.MESSAGE_PLACEHOLDERS.contains(k)) {
                throw new IllegalStateException("not a message placeholder: " + k);
            }
            values.put(k, v);
        });
        if (f.field() != null) {
            values.put("field", f.field());
        }
        values.put("file", shortName(file));
        values.put("line", String.valueOf(line));
        // One pass over the template only, so a placeholder inside a file name is never filled (second review of #867),
        // and never more than the 500 characters import_issues.message holds. JUSTIFICATION-A3: its items 2 to 4.
        StringBuilder message = new StringBuilder();
        Matcher m = TemplateRegistry.PLACEHOLDER.matcher(c.message());
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(message, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(message);
        return new Finding(f.code(), severity, f.field(), cap(message.toString()), f.original(), f.suggestion());
    }

    /** The digits of a value without a leading 256 or 0, so one phone written three ways compares equal. */
    private static String national(String v) {
        String digits = v.replaceAll("\\D", "");
        if (digits.startsWith("256") && digits.length() == 12) {
            return digits.substring(3);
        }
        return digits.startsWith("0") && digits.length() == 10 ? digits.substring(1) : digits;
    }

    /** A message cut to {@value #MAX_MESSAGE} characters, ending in three dots when cut. */
    static String cap(String message) {
        if (message.codePointCount(0, message.length()) <= MAX_MESSAGE) {
            return message;
        }
        return message.substring(0, message.offsetByCodePoints(0, MAX_MESSAGE - 3)) + "...";
    }

    /** The personal values of a row worth looking for: as written and normalised, of four characters or more. */
    private static List<String> personalValues(Plan p, Typed t) {
        List<String> out = new ArrayList<>();
        for (Field f : p.set.fields()) {
            if (f.isPersonal()) {
                for (Object v : new Object[] {t.texts.get(f.name()), t.values.get(f.name())}) {
                    if (v instanceof String s && s.strip().length() >= 4) {
                        out.add(s.strip());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether a parameter value holds one of the row's personal values, as text or, for a phone or NIN written another way,
     * by its digits ({@code 700000101} in {@code +256700000101}).
     */
    static boolean holds(String v, List<String> personal) {
        String digits = national(v);
        for (String s : personal) {
            String sDigits = national(s);
            if (v.contains(s)
                    || (digits.length() >= 7
                            && sDigits.length() >= 7
                            && (sDigits.contains(digits) || digits.contains(sDigits)))) {
                return true;
            }
        }
        return false;
    }

    private static String other(Location first, Location here) {
        return first.file().equals(here.file())
                ? String.valueOf(first.line())
                : first.line() + " of " + shortName(first.file());
    }

    /**
     * A file name as a message shows it: at most {@value #MAX_FILE_NAME} characters, the middle elided, so a message with
     * two file names stays well inside {@code import_issues.message} (500) whatever names the customer gave (review of
     * #867).
     */
    static String shortName(String file) {
        int length = file.codePointCount(0, file.length());
        if (length <= MAX_FILE_NAME) {
            return file;
        }
        int head = file.offsetByCodePoints(0, 50);
        int tail = file.offsetByCodePoints(0, length - (MAX_FILE_NAME - 53));
        return file.substring(0, head) + "..." + file.substring(tail);
    }

    // ---------------------------------------------------------------------------------------------
    // Typing one row.
    // ---------------------------------------------------------------------------------------------

    /** A row's fields as typed so far: values by field, the text each came from, its reference and its issues. */
    private static final class Typed {

        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, String> texts = new HashMap<>();
        /** Each field's text with the mapping's value map applied, before any normaliser: what the set's rules read. */
        final Map<String, String> mapped = new HashMap<>();

        final List<Pending> pending = new ArrayList<>();
        String sourceRef;

        void add(String code, String field, Map<String, String> params, String original) {
            pending.add(new Pending(code, field, params, original));
        }
    }

    private Typed type(Plan p, SourceRow row) {
        Map<String, RawCell> cells = new HashMap<>();
        new TreeMap<>(row.cells()).forEach((header, cell) -> cells.putIfAbsent(Parsing.key(header), cell));
        Typed t = new Typed();
        for (Field f : p.set.fields()) {
            String column = p.columnOf.get(f.name());
            // A field no column feeds takes the source's constant, if it has one (issue #136, `defaults`).
            String text = row.edits().containsKey(f.name())
                    ? edited(row.edits().get(f.name()))
                    : column == null ? p.defaults.get(f.name()) : text(cells.get(column));
            RawCell cell = column == null ? null : cells.get(column);
            if (!row.edits().containsKey(f.name()) && cell != null && UNREAD_CELLS.contains(cell.type())) {
                // A formula's error (#N/A, #REF!) or a formula saved without its value is not what the sheet shows:
                // never a value and never an empty field (issue #137, spec section 5.3).
                t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), text);
                continue;
            }
            if (text == null) {
                if (f.required()) {
                    t.add("REQUIRED_MISSING", f.name(), Map.of(), null);
                }
                continue;
            }
            t.texts.put(f.name(), text);
            String mapped = mapValue(p.valueMaps.get(f.name()), text);
            if (f.type().kind() == Kind.ENUM) {
                mapped = mapValue(p.enumMaps.get(f.name()), mapped);
            }
            t.mapped.put(f.name(), mapped);
            Object value = typed(p, f, mapped, text, t);
            if (value != null) {
                t.values.put(f.name(), value);
            }
        }
        t.sourceRef = sourceRef(p, cells, row.line(), t);
        return t;
    }

    private Object typed(Plan p, Field f, String mapped, String original, Typed t) {
        return switch (f.type().kind()) {
            case TEXT -> code(f, f, mapped, original, t);
            case REF -> code(f, keyField(f.type().ref()), mapped, original, t);
            case PHONE -> form(f, PhoneNumbers::normaliseUganda, "uganda_phone", mapped, original, t);
            case NIN -> form(f, NationalIds::normaliseNin, "national_id", mapped, original, t);
            case MONEY -> money(f, mapped, original, t);
            case QTY -> qty(f, mapped, original, t);
            case DATE -> date(p, f, mapped, original, t);
            case DATETIME -> moment(p, f, mapped, original, t);
            case BOOL -> bool(f, mapped, original, t);
            case ENUM -> enumValue(f, mapped, original, t);
        };
    }

    /**
     * A text, a code or a reference to one: the normaliser of {@code rule} (the field itself, or the key field a reference
     * names), then a hidden character in a code, then the longest length.
     */
    private Object code(Field f, Field rule, String mapped, String original, Typed t) {
        String n = rule.normaliser().apply(mapped);
        if (n == null || n.isBlank()) {
            if (f.required()) {
                t.add("REQUIRED_MISSING", f.name(), Map.of(), original);
            }
            return null;
        }
        if ((f.key() || f.type().kind() == Kind.REF) && hidden(n)) {
            t.add("CODE_CONTROL_CHAR", f.name(), Map.of(), original);
            return null;
        }
        int limit = rule.type().maxLength();
        if (n.codePointCount(0, n.length()) > limit) {
            t.add("TEXT_TOO_LONG", f.name(), Map.of("limit", String.valueOf(limit)), original);
            return null;
        }
        if (!n.equals(mapped)) {
            t.add(
                    "VALUE_NORMALISED",
                    f.name(),
                    Map.of("normaliser", rule.normaliser().name()),
                    original);
        }
        return n;
    }

    /** A phone or a NIN through the form's own function: refused is {@code TYPE_INVALID}, changed is noted. */
    private static Object form(
            Field f,
            Function<String, Optional<String>> function,
            String rule,
            String mapped,
            String original,
            Typed t) {
        Optional<String> n = function.apply(mapped);
        if (n.isEmpty()) {
            t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), original);
            return null;
        }
        if (!n.get().equals(mapped)) {
            t.add("VALUE_NORMALISED", f.name(), Map.of("normaliser", rule), original);
        }
        return n.get();
    }

    /** Whole shillings (ADR-004). */
    private static Object money(Field f, String mapped, String original, Typed t) {
        BigDecimal d = decimal(mapped);
        if (d == null) {
            t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), original);
            return null;
        }
        separators(f, mapped, original, t);
        if (d.stripTrailingZeros().scale() > 0) {
            t.add("MONEY_FRACTION", f.name(), Map.of(), original);
            return null;
        }
        long minor;
        try {
            minor = d.longValueExact();
        } catch (ArithmeticException e) {
            t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), original);
            return null;
        }
        return inRange(f, d, original, t) ? minor : null;
    }

    /** Up to three decimal places. */
    private static Object qty(Field f, String mapped, String original, Typed t) {
        BigDecimal d = decimal(mapped);
        if (d == null) {
            t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), original);
            return null;
        }
        separators(f, mapped, original, t);
        BigDecimal q = d.stripTrailingZeros();
        if (q.scale() > 3) {
            t.add("QTY_PRECISION", f.name(), Map.of(), original);
            return null;
        }
        if (q.scale() < 0) {
            q = q.setScale(0);
        }
        return inRange(f, q, original, t) ? q : null;
    }

    /** Group separators removed from a number are a change like any normaliser's: an {@code info} with the original. */
    private static void separators(Field f, String mapped, String original, Typed t) {
        if (grouped(mapped)) {
            t.add("VALUE_NORMALISED", f.name(), Map.of("normaliser", "thousands_separator"), original);
        }
    }

    private static boolean inRange(Field f, BigDecimal d, String original, Typed t) {
        boolean below = f.min() != null && d.compareTo(f.min()) < 0;
        boolean above = f.max() != null && d.compareTo(f.max()) > 0;
        if (!below && !above) {
            return true;
        }
        String range;
        if (f.min() != null && f.max() != null) {
            range = "from " + f.min().toPlainString() + " to " + f.max().toPlainString();
        } else if (f.min() != null) {
            range = "at least " + f.min().toPlainString();
        } else {
            range = "at most " + f.max().toPlainString();
        }
        t.add("VALUE_OUT_OF_RANGE", f.name(), Map.of("range", range), original);
        return false;
    }

    private Object date(Plan p, Field f, String mapped, String original, Typed t) {
        LocalDateTime at = dateTime(p.convention, mapped, false);
        if (at == null) {
            t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), original);
            return null;
        }
        LocalDate day = at.toLocalDate();
        if (cutoverAt != null && day.isAfter(LocalDate.ofInstant(cutoverAt, p.zone))) {
            t.add("DATE_AFTER_CUTOVER", f.name(), Map.of(), original);
        }
        return day;
    }

    private Object moment(Plan p, Field f, String mapped, String original, Typed t) {
        LocalDateTime at = dateTime(p.convention, mapped, true);
        if (at == null) {
            t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), original);
            return null;
        }
        Instant instant = at.atZone(p.zone).toInstant();
        if (cutoverAt != null && instant.isAfter(cutoverAt)) {
            t.add("DATE_AFTER_CUTOVER", f.name(), Map.of(), original);
        }
        return instant;
    }

    private static Object bool(Field f, String mapped, String original, Typed t) {
        String s = mapped.strip().toLowerCase(Locale.ROOT);
        if (TRUE.contains(s)) {
            return Boolean.TRUE;
        }
        if (FALSE.contains(s)) {
            return Boolean.FALSE;
        }
        t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), original);
        return null;
    }

    private static Object enumValue(Field f, String mapped, String original, Typed t) {
        String s = mapped.strip();
        for (String v : f.type().values()) {
            if (v.equalsIgnoreCase(s)) {
                return v;
            }
        }
        t.add("TYPE_INVALID", f.name(), Map.of("type", f.type().spec()), original);
        return null;
    }

    /**
     * The row's reference (spec section 4.2): with the rule {@code column}, the source key and that column's text, or its
     * hash when it is long; with {@code row_hash}, the source key, the line and a hash of the row's kept cells. An empty
     * reference column is {@code REQUIRED_MISSING} on {@code source_ref}.
     */
    private static String sourceRef(Plan p, Map<String, RawCell> cells, int line, Typed t) {
        if (p.refColumn != null) {
            String v = text(cells.get(p.refColumn));
            if (v == null) {
                t.add("REQUIRED_MISSING", "source_ref", Map.of(), null);
                return null;
            }
            String s = v.strip();
            return "col:" + p.sourceKey + ":" + (s.length() > MAX_REF_TEXT ? "sha256:" + sha256(s) : s);
        }
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(cells)
                .forEach((header, cell) -> canonical
                        .append(header)
                        .append('\u001f')
                        .append(cell.type())
                        .append('\u001f')
                        .append(text(cell))
                        .append('\u001e'));
        return "row:" + p.sourceKey + ":" + line + ":"
                + sha256(canonical.toString()).substring(0, 16);
    }

    // ---------------------------------------------------------------------------------------------
    // What the review queue needs (issue #131). JUSTIFICATION-A3: three engine answers the queue needs, beside the
    // engine.
    // ---------------------------------------------------------------------------------------------

    /**
     * The row's identity across validations and uploads: its reference without the content hash. With the rule
     * {@code column}, the reference itself ({@code col:<source>:<value>}); with {@code row_hash}, or a reference column left
     * empty, the source and the line ({@code row:<source>:<line>}). Made from the cells as stored, never from an edit.
     */
    String rowKey(BatchInput batch, SourceRow row) {
        Plan p = plan(batch);
        Map<String, RawCell> cells = new HashMap<>();
        new TreeMap<>(row.cells()).forEach((header, cell) -> cells.putIfAbsent(Parsing.key(header), cell));
        return rowKeyOf(sourceRef(p, cells, row.line(), new Typed()), p.sourceKey, row.line());
    }

    /** The same from a row's stored {@code source_ref}, its batch's source and its line. */
    static String rowKeyOf(String sourceRef, String source, int line) {
        return sourceRef != null && sourceRef.startsWith("col:") ? sourceRef : "row:" + source + ":" + line;
    }

    /** {@code ROW_CHANGED_SINCE_REVIEW} on a row whose cells changed since it was resolved (spec section 10.1). */
    Finding rowChanged(BatchInput batch, int line) {
        return render(batch.file(), line, new Pending("ROW_CHANGED_SINCE_REVIEW", null, Map.of(), null));
    }

    /** The finding on a row the business already imported with other content (spec section 10.1, issue #133). */
    Finding changedSinceImport(BatchInput batch, int line) {
        return render(batch.file(), line, new Pending("CHANGED_SINCE_IMPORT", null, Map.of(), null));
    }

    /**
     * The value the engine proposes for a finding, or null: a fraction of a shilling rounded to whole shillings, a quantity
     * rounded to three places, both half up (chapter 13 section 13.7, {@code AMOUNT_NOT_INTEGER}). Only for a field that is
     * not personal, so a suggestion never holds a personal value.
     */
    String suggestion(BatchInput batch, Finding f) {
        if (f.field() == null || f.original() == null) {
            return null;
        }
        Field field = plan(batch).set.fields().stream()
                .filter(x -> x.name().equals(f.field()))
                .findFirst()
                .orElse(null);
        BigDecimal d = field == null || field.isPersonal() ? null : decimal(f.original());
        if (d == null) {
            return null;
        }
        return switch (f.code()) {
            case "MONEY_FRACTION" -> d.setScale(0, RoundingMode.HALF_UP).toPlainString();
            case "QTY_PRECISION" ->
                d.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
            default -> null;
        };
    }

    /** An edit's text as a cell's: blank is empty. */
    private static String edited(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---------------------------------------------------------------------------------------------
    // Reading values.
    // ---------------------------------------------------------------------------------------------

    /** A cell as text, or null when it is absent or blank. */
    static String text(RawCell cell) {
        if (cell == null || cell.value() == null) {
            return null;
        }
        String s = switch (cell.value()) {
            case String text -> text;
            case BigDecimal d -> d.toPlainString();
            case Number n -> new BigDecimal(n.toString()).toPlainString();
            default -> String.valueOf(cell.value());
        };
        return s.isBlank() ? null : s;
    }

    /** A row whose kept cells are all empty: the parse keeps it, so it is reported, never validated (chapter 13.5). */
    static boolean blank(SourceRow row) {
        return row.cells().values().stream().allMatch(c -> text(c) == null);
    }

    /** A code compared ignoring case and spaces. */
    static String compare(String code) {
        return SPACES.matcher(code).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /** A control or format character, or a space other than U+0020, inside a code. */
    static boolean hidden(String code) {
        return code.codePoints().anyMatch(cp -> {
            int type = Character.getType(cp);
            return type == Character.CONTROL
                    || type == Character.FORMAT
                    || (cp != ' ' && (Character.isWhitespace(cp) || Character.isSpaceChar(cp)));
        });
    }

    /**
     * A number, with commas or spaces only between groups of three digits ({@code 12,500}, {@code 12 500}); null when it is
     * not one. {@code 12,50} and {@code 1,5} are refused, never read as 1250 or 15 (review of #867).
     */
    static BigDecimal decimal(String s) {
        String grouped = SPACES.matcher(s.strip()).replaceAll(",");
        if (grouped.indexOf(',') >= 0) {
            if (!GROUPED.matcher(grouped).matches()) {
                return null;
            }
            grouped = grouped.replace(",", "");
        }
        return NUMBER.matcher(grouped).matches() ? new BigDecimal(grouped) : null;
    }

    /** Whether a number was written with group separators, which reading it removes (an {@code info} with the original). */
    static boolean grouped(String s) {
        return SPACES.matcher(s.strip()).find() || s.indexOf(',') >= 0;
    }

    /**
     * A date, or a date and time, as written: ISO ({@code 2026-06-30}, {@code 2026-06-30T14:05}, {@code 2026-06-30 14:05:00})
     * under every convention, and day first or month first ({@code 30/06/2026}, {@code 6.30.26}) under the mapping's. A date
     * alone is midnight; a time is refused where only a date is wanted. Null when it is neither.
     */
    static LocalDateTime dateTime(String convention, String s, boolean timeAllowed) {
        String text = s.strip();
        String datePart = text;
        String timePart = null;
        int cut = Math.max(text.indexOf('T'), text.indexOf(' '));
        if (cut > 0) {
            datePart = text.substring(0, cut);
            timePart = text.substring(cut + 1).strip();
        }
        LocalDate day = day(convention, datePart);
        if (day == null) {
            return null;
        }
        if (timePart == null) {
            return day.atStartOfDay();
        }
        Matcher m = TIME.matcher(timePart);
        if (!m.matches()) {
            return null;
        }
        try {
            LocalTime time = LocalTime.of(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
            if (!timeAllowed && !time.equals(LocalTime.MIDNIGHT)) {
                return null;
            }
            return day.atTime(time);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static LocalDate day(String convention, String s) {
        try {
            Matcher iso = ISO_DATE.matcher(s);
            if (iso.matches()) {
                return LocalDate.of(
                        Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            }
            Matcher m = SHORT_DATE.matcher(s);
            if (!m.matches() || "iso".equals(convention)) {
                return null;
            }
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            int year = Integer.parseInt(m.group(3));
            if (m.group(3).length() == 2) {
                year += 2000;
            }
            return "month_first".equals(convention) ? LocalDate.of(year, a, b) : LocalDate.of(year, b, a);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String mapValue(Map<String, String> map, String s) {
        if (map == null) {
            return s;
        }
        String v = map.get(Parsing.key(s));
        return v == null ? s : v;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Field keyField(String entitySet) {
        return TemplateRegistry.keyField(template.entitySets(), entitySet)
                .orElseThrow(() -> new IllegalStateException("the registry checks a ref names a set with a key"));
    }

    // ---------------------------------------------------------------------------------------------
    // What a batch's mapping source says.
    // ---------------------------------------------------------------------------------------------

    private Plan plan(BatchInput batch) {
        return plans.computeIfAbsent(batch.batchId(), id -> new Plan(batch));
    }

    /** One batch's source of its mapping: the entity set, each field's column, the value maps, the convention and zone. */
    private final class Plan {

        final String sourceKey;
        final EntitySet set;
        final ZoneId zone;
        final String convention;
        final Map<String, String> columnOf = new HashMap<>();
        final Map<String, Map<String, String>> valueMaps;
        final Map<String, Map<String, String>> enumMaps;
        final String refColumn;
        /** The source's constants by field (issue #136) and the row rules it switches on, as written. */
        final Map<String, String> defaults;

        final Map<String, Object> rowRules;

        Plan(BatchInput batch) {
            this.sourceKey = batch.sourceKey();
            Map<String, Object> source =
                    MappingValidator.sources(batch.mapping()).get(sourceKey);
            if (source == null) {
                throw new IllegalStateException("the batch's source is not in its mapping");
            }
            Object setKey = source.get("entity_set");
            this.set = template.entitySets().stream()
                    .filter(s -> s.key().equals(setKey))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("the mapping's entity set is not in the template"));
            this.zone = ZoneId.of(String.valueOf(batch.mapping().get("time_zone")));
            this.convention = String.valueOf(batch.mapping().get("date_convention"));
            // The mapping validator refuses a field fed by two columns, so each field has one column.
            if (source.get("columns") instanceof Map<?, ?> columns) {
                columns.forEach((column, target) -> {
                    if (target instanceof Map<?, ?> m && m.get("field") instanceof String field) {
                        columnOf.putIfAbsent(field, Parsing.key(String.valueOf(column)));
                    }
                });
            }
            this.valueMaps = labels(source.get("value_maps"));
            this.enumMaps = labels(source.get("enum_maps"));
            this.refColumn = source.get("source_ref") instanceof Map<?, ?> ref && "column".equals(ref.get("rule"))
                    ? Parsing.key(String.valueOf(ref.get("column")))
                    : null;
            this.defaults = MappingValidator.defaults(source);
            Map<String, Object> rules = new LinkedHashMap<>();
            if (source.get("row_rules") instanceof Map<?, ?> r) {
                r.forEach((k, v) -> rules.put(String.valueOf(k), v));
            }
            this.rowRules = rules;
        }

        private static Map<String, Map<String, String>> labels(Object maps) {
            Map<String, Map<String, String>> out = new HashMap<>();
            if (maps instanceof Map<?, ?> byField) {
                byField.forEach((field, labels) -> {
                    Map<String, String> m = new HashMap<>();
                    if (labels instanceof Map<?, ?> l) {
                        l.forEach((label, value) -> m.put(Parsing.key(String.valueOf(label)), String.valueOf(value)));
                    }
                    out.put(String.valueOf(field), m);
                });
            }
            return out;
        }
    }
}
