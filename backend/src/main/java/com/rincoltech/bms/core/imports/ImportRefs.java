package com.rincoltech.bms.core.imports;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The references of imported records (issue #133, spec section 3.7, ADR-027 decision 4): {@code import_refs}, the
 * generalisation of {@code retail_import_refs}. {@link #sourceRef} is the one framework function that derives a row's
 * {@code source_ref} from its source file, tab, row and a hash of its mapped content; the validation reports a row whose
 * key is already imported with another hash as {@code CHANGED_SINCE_IMPORT}, and the commit never writes it twice. Writers
 * read their rows and register what they create through {@link BatchWriter.Checkpoint}.
 */
public final class ImportRefs {

    /** The longest key kept as written; a longer one is kept by its hash, so a reference fits {@code import_refs}. */
    static final int MAX_KEY = 300;

    private ImportRefs() {}

    /**
     * The {@code source_ref} of a row: its {@link #sourceKey} and the hash of its mapped content, joined by {@code #}.
     *
     * @param file the source file as the mapping names it (or the mapping source's key when it names none)
     * @param tab the sheet of the file, or null for a file with one table (CSV, JSON Lines)
     * @param row the row within the file and tab: {@code line:<n>} for a row known by its position, {@code ref:<code>} for
     *     a row known by its reference column
     * @param contentHash {@link #contentHash} of the row's mapped values
     */
    public static String sourceRef(String file, String tab, String row, String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("a source_ref needs the hash of the row's content");
        }
        return sourceKey(file, tab, row) + "#" + contentHash;
    }

    /** The reference without its content hash: what a later run of the same row matches on. */
    public static String sourceKey(String file, String tab, String row) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(row, "row");
        String key = file.strip() + "|" + (tab == null || tab.isBlank() ? "-" : tab.strip()) + "|" + row.strip();
        return key.length() <= MAX_KEY ? key : "sha256:" + sha256(key);
    }

    /** The key part of a {@code source_ref}: everything before its last {@code #}. */
    public static String keyOf(String sourceRef) {
        int hash = sourceRef.lastIndexOf('#');
        if (hash <= 0) {
            throw new IllegalArgumentException("not a source_ref");
        }
        return sourceRef.substring(0, hash);
    }

    /**
     * The hash of a row's mapped content: the first 32 hex digits of the SHA-256 of its canonical JSON (Lead ruling 11 on
     * #872), the one form every type is written in whatever it was typed as: an object with its fields sorted by name and
     * a null field left out; a number without trailing zeros, so {@code 1.10} and {@code 1.1} are one value; a boolean as
     * itself; and every other value (text, a {@code LocalDate}, an {@code Instant}, an enum) as the JSON string of its ISO
     * text. So the validation's typed values and the same values read back from the database hash alike.
     */
    public static String contentHash(Map<String, ?> values) {
        StringBuilder canonical = new StringBuilder();
        write(canonical, values == null ? Map.of() : values);
        return sha256(canonical.toString()).substring(0, 32);
    }

    /** The canonical JSON of a value: what {@link #contentHash} hashes. */
    static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                out.append('{');
                boolean[] first = {true};
                new TreeMap<>(map).forEach((k, v) -> {
                    if (v != null) {
                        out.append(first[0] ? "" : ",");
                        first[0] = false;
                        string(out, String.valueOf(k));
                        out.append(':');
                        write(out, v);
                    }
                });
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    out.append(i == 0 ? "" : ",");
                    write(out, list.get(i));
                }
                out.append(']');
            }
            case Number n -> out.append(plain(n));
            case Boolean b -> out.append(b);
            default -> string(out, value.toString());
        }
    }

    /** A JSON string: quoted, with a quote, a backslash and every control character escaped. */
    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A number in one form: {@code 1.10}, {@code 1.1} and {@code 1.100} are {@code 1.1}; {@code 5.0} is {@code 5}. */
    private static String plain(Number n) {
        return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the business's imports already hold for a row. */
    public enum Status {
        /** Never imported: the writer creates its records and registers them. */
        NEW,
        /** Imported before with the same content: the writer writes nothing and counts it {@code existing}. */
        EXISTING
    }

    /**
     * One staged row a writer writes, in line order: its line, its typed values (as {@link ImportTemplate.RowCheck} reads
     * them), its {@code source_ref}, whether it is new or already imported, and the records already registered for it by
     * target type. A row imported before with other content never reaches a writer: the validation holds it back as
     * {@code CHANGED_SINCE_IMPORT}, and the commit refuses the batch if one reaches it all the same.
     */
    public record StagedRow(
            int line, Map<String, Object> values, String sourceRef, Status status, Map<String, UUID> targets) {

        public StagedRow {
            values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
            targets = targets == null ? Map.of() : Map.copyOf(targets);
        }
    }

    /**
     * An opening balance to post (spec section 3.7): one journal per batch, branch and kind, through {@code post_entry}, against
     * {@code opening_balance_equity}. {@code accountKey} is the account's system key; {@code debit} true for an asset the
     * business holds (inventory, receivables, loans receivable: debit the account, credit equity), false for what it owes
     * (member credits: debit equity, credit the account). {@code branchCode} is what the report names the branch by.
     */
    public record Opening(
            UUID branchId,
            String branchCode,
            String kind,
            LocalDate entryDate,
            String accountKey,
            boolean debit,
            long amountMinor) {

        public Opening {
            Objects.requireNonNull(branchId, "branchId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(entryDate, "entryDate");
            Objects.requireNonNull(accountKey, "accountKey");
            if (amountMinor < 0) {
                throw new IllegalArgumentException("an opening balance is posted as a positive amount");
            }
        }
    }
}
