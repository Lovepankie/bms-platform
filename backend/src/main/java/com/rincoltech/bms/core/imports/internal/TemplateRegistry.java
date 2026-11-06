package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.Kind;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.kernel.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * {@link ImportTemplates} over every {@link ImportTemplate} bean in the application (issue #129, chapter 5 section 5.4.3).
 * A vertical registers a template by declaring the bean in its own module; this class takes them through an
 * {@link ObjectProvider}, so it compiles and starts with none, and never names a vertical. Each template is checked once,
 * at start-up, and a template that fails a check stops the application with the reason, so a broken contract never reaches a
 * customer's run.
 */
@Service
class TemplateRegistry implements ImportTemplates {

    static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,59}$");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    /** The longest message template, so a message filled with two file names stays inside its 500 (review of #867). */
    static final int MAX_MESSAGE_TEMPLATE = 200;

    private final Map<String, ImportTemplate> templates = new LinkedHashMap<>();

    @Autowired
    TemplateRegistry(ObjectProvider<ImportTemplate> registered) {
        this(registered.orderedStream().toList());
    }

    /** The registry of exactly these templates, for a test. */
    TemplateRegistry(List<ImportTemplate> registered) {
        List<ImportTemplate> sorted = new ArrayList<>(registered);
        sorted.sort(Comparator.comparing(ImportTemplate::key).thenComparingInt(ImportTemplate::version));
        for (ImportTemplate t : sorted) {
            verify(t);
            if (templates.putIfAbsent(id(t.key(), t.version()), t) != null) {
                throw new IllegalStateException("import template " + t.id() + " is registered twice");
            }
        }
    }

    @Override
    public List<ImportTemplate> list() {
        return List.copyOf(templates.values());
    }

    @Override
    public Optional<ImportTemplate> find(String key, int version) {
        return Optional.ofNullable(templates.get(id(key, version)));
    }

    @Override
    public ImportTemplate require(String key, int version) {
        return find(key, version).orElseThrow(() -> unknown(key, version));
    }

    /** Built on each call, so no caller can change the document another reads. */
    @Override
    public Map<String, Object> schema(String key, int version) {
        return TemplateSchema.of(require(key, version));
    }

    @Override
    public List<IssueCode> catalogue(String key, int version) {
        List<IssueCode> all = new ArrayList<>(SHARED_CODES);
        all.addAll(require(key, version).issueCodes());
        return List.copyOf(all);
    }

    static ApiException unknown(String key, int version) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "TEMPLATE_UNKNOWN",
                "Template unknown",
                "No template " + key + "_v" + version + " is registered on this server.");
    }

    private static String id(String key, int version) {
        return key + "_v" + version;
    }

    /** The checks of a template's contract; each failure names the template and what is wrong. */
    static void verify(ImportTemplate t) {
        String where = "import template " + t.key() + " version " + t.version() + ": ";
        require(
                t.key() != null && KEY.matcher(t.key()).matches(),
                where + "the key must be lower-case letters, digits, _");
        require(t.version() > 0, where + "the version must be 1 or more");
        List<EntitySet> sets = t.entitySets();
        require(sets != null && !sets.isEmpty(), where + "it declares no entity set");
        Set<String> before = new HashSet<>();
        for (EntitySet set : sets) {
            require(
                    KEY.matcher(set.key()).matches(),
                    where + "entity set " + set.key() + " is not a plain lower-case name");
            require(!before.contains(set.key()), where + "entity set " + set.key() + " is declared twice");
            require(!set.fields().isEmpty(), where + "entity set " + set.key() + " has no field");
            Set<String> names = new HashSet<>();
            for (Field f : set.fields()) {
                String at = where + set.key() + "." + f.name() + ": ";
                require(KEY.matcher(f.name()).matches(), at + "the field name is not a plain lower-case name");
                require(names.add(f.name()), at + "the field is declared twice");
                Kind kind = f.type().kind();
                require(kind != Kind.TEXT || f.type().maxLength() > 0, at + "a text field needs a length above 0");
                require(
                        kind != Kind.ENUM
                                || (!f.type().values().isEmpty()
                                        && new HashSet<>(f.type().values()).size()
                                                == f.type().values().size()),
                        at + "an enum needs distinct values");
                require(
                        kind != Kind.REF
                                || before.contains(f.type().ref())
                                || set.key().equals(f.type().ref()),
                        at + "a ref must name this entity set or one committed before it");
                require(
                        kind != Kind.REF || keyField(sets, f.type().ref()).isPresent(),
                        at + "a ref must name an entity set with a key field");
                require(!f.key() || kind == Kind.TEXT, at + "a key field must be a text field");
                require(
                        (f.min() == null && f.max() == null) || kind == Kind.MONEY || kind == Kind.QTY,
                        at + "only a money or qty field has a range");
                require(
                        f.min() == null || f.max() == null || f.min().compareTo(f.max()) <= 0,
                        at + "the range's minimum is above its maximum");
            }
            require(
                    set.fields().stream().filter(Field::key).count() <= 1,
                    where + "entity set " + set.key() + " has more than one key field");
            before.add(set.key());
        }
        Set<String> personal = new HashSet<>();
        sets.forEach(s -> s.fields().stream().filter(Field::isPersonal).forEach(f -> personal.add(f.name())));
        Set<String> shared = new HashSet<>();
        SHARED_CODES.forEach(c -> shared.add(c.code()));
        Set<String> own = new HashSet<>();
        for (IssueCode c : t.issueCodes()) {
            require(
                    CODE.matcher(c.code()).matches(),
                    where + "code " + c.code() + " is not upper-case letters, digits, _");
            require(!shared.contains(c.code()), where + "code " + c.code() + " is a shared code");
            require(own.add(c.code()), where + "code " + c.code() + " is declared twice");
            require(!c.message().isBlank(), where + "code " + c.code() + " has no message");
            require(
                    c.message().length() <= MAX_MESSAGE_TEMPLATE,
                    where + "code " + c.code() + " has a message over " + MAX_MESSAGE_TEMPLATE + " characters");
            // JUSTIFICATION-A3: the personal-placeholder check of #130 sits with the registry's start-up checks.
            for (String p : placeholders(c.message())) {
                require(
                        !personal.contains(p),
                        where + "code " + c.code() + " interpolates the personal field {" + p + "}");
                require(
                        MESSAGE_PLACEHOLDERS.contains(p),
                        where + "code " + c.code() + " interpolates {" + p + "}, not a placeholder a message may hold");
            }
        }
    }

    /** The placeholders of a message template, in order: {@code {field}} gives {@code field}. */
    static List<String> placeholders(String message) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(message);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The key field of an entity set, if the template has the set and the set has one. */
    static Optional<Field> keyField(List<EntitySet> sets, String setKey) {
        return sets.stream()
                .filter(s -> s.key().equals(setKey))
                .flatMap(s -> s.fields().stream())
                .filter(Field::key)
                .findFirst();
    }

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new IllegalStateException(reason);
        }
    }
}
