package com.rincoltech.bms.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;

/**
 * The one way a list endpoint matches a search text (chapter 7 section 7.6.1, ADR-039). A repository
 * parses the text once, describes the columns of its entity, and appends the returned condition
 * to its own query, next to the branch scope and the other filters. The condition never replaces a
 * scope predicate: it is ANDed with them, so a search cannot return a row the caller cannot list.
 *
 * <p>Matching, for every entity alike:
 *
 * <ul>
 *   <li>case and accent insensitive: both the column and the text go through {@code bms_fold}
 *       (migration V40), so "jose" finds a name written with an accent on the e;
 *   <li>the text is split on white space and a row must match every term (AND), each term anywhere in
 *       any name or code column, so "john doe" finds "Doe, John" and "led bulb" finds "LED screw bulb";
 *   <li>a term of four or more digits and a phone column also matches the digits of the phone, as typed
 *       and without its leading 256 or 0, so "0772", "+2567", "0700 000 011" and "700000011" all find
 *       "+256700000011"; a whole text that looks like a phone number is matched as one number as well,
 *       so spaces inside it do not split it;
 *   <li>{@code %}, {@code _} and the backslash are data, never wildcards;
 *   <li>rank 0: a name or code starts with the whole text; 1: the whole text starts a word in it;
 *       2: every term starts a word in it (several terms only); 3: any other match. The repository
 *       orders by rank first and then by its usual key, and the cursor carries the rank and a
 *       fingerprint of the text ({@link #wrap}, {@link #unwrap}).
 * </ul>
 */
public final class Search {

    /** Text beyond this many characters is ignored. */
    public static final int MAX_LENGTH = 100;

    /** Terms beyond this many are ignored. */
    public static final int MAX_TERMS = 6;

    /** A digit run shorter than this, as typed, is not treated as a phone number. */
    public static final int MIN_PHONE_DIGITS = 4;

    /** The national part of a phone number must be at least this long to be searched on its own. */
    static final int MIN_NATIONAL_DIGITS = 3;

    private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");
    private static final Pattern PHONE_LIKE = Pattern.compile("^\\+?[0-9\\s().-]+$");
    private static final Pattern NOT_DIGIT = Pattern.compile("\\D");

    private Search() {}

    /** The parsed text; null when there is nothing to search for, so callers skip the condition. */
    public static Query parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        if (text.isEmpty()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        for (String term : WHITE_SPACE.split(text)) {
            if (!term.isEmpty() && terms.size() < MAX_TERMS) {
                terms.add(term);
            }
        }
        List<String> whole = List.of();
        if (terms.size() > 1 && PHONE_LIKE.matcher(text).matches()) {
            whole = phoneKeys(text);
        }
        return new Query(List.copyOf(terms), whole);
    }

    /**
     * The digit strings to look for when {@code text} is a phone number: the digits as typed (four or
     * more), and the national part without a leading 256 or 0 (three or more). Empty when the text is
     * not phone-like or too short. The length is checked before anything is stripped, so "0772" and
     * "+2567" are searches, not nothing.
     */
    static List<String> phoneKeys(String text) {
        if (!PHONE_LIKE.matcher(text).matches()) {
            return List.of();
        }
        String typed = NOT_DIGIT.matcher(text).replaceAll("");
        if (typed.length() < MIN_PHONE_DIGITS) {
            return List.of();
        }
        String national = national(typed);
        if (national.length() >= MIN_NATIONAL_DIGITS && !national.equals(typed)) {
            return List.of(typed, national);
        }
        return List.of(typed);
    }

    /**
     * The national part of a digit string: a leading 256 (country code) or 0 (trunk prefix) removed,
     * so every written form of one number gives the same digits.
     */
    static String national(String digits) {
        if (digits.startsWith("256") && digits.length() > 3) {
            return digits.substring(3);
        }
        if (digits.startsWith("0")) {
            return digits.substring(1);
        }
        return digits;
    }

    /** Escapes the LIKE metacharacters so the text matches literally. */
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The search text, split into terms. {@code wholePhone} holds the digit keys of a multi-word phone-like text. */
    public record Query(List<String> terms, List<String> wholePhone) {

        /** A short, stable fingerprint of the text, so a cursor can only be used with the search it came from. */
        public String fingerprint() {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(String.join(" ", terms).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash, 0, 6);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The condition, the rank expression and their parameters for one entity's columns. */
        public Match match(Fields fields) {
            Map<String, Object> params = new LinkedHashMap<>();
            List<String> all = new ArrayList<>();
            for (int i = 0; i < terms.size(); i++) {
                String term = terms.get(i);
                String like = "s" + i;
                params.put(like, "%" + escape(term) + "%");
                List<String> arms = new ArrayList<>();
                for (Column c : fields.columns) {
                    if (!c.phone) {
                        arms.add(c.arm("bms_fold(" + c.sql + ") LIKE bms_fold(:" + like + ")"));
                    }
                }
                List<String> keys = fields.hasPhone() ? phoneKeys(term) : List.of();
                arms.addAll(phoneArms(fields, params, "d" + i, keys, "%", "%"));
                all.add("(" + String.join(" OR ", arms) + ")");
            }
            String where = String.join(" AND ", all);
            List<String> whole = fields.hasPhone() ? wholePhone : List.of();
            if (!whole.isEmpty()) {
                where = "(" + where + " OR (" + String.join(" OR ", phoneArms(fields, params, "dw", whole, "%", "%"))
                        + "))";
            }
            return new Match("(" + where + ")", rank(fields, params), params);
        }

        /** Phone conditions for each key: {@code bms_digits(col) LIKE before || key || after}. */
        private static List<String> phoneArms(
                Fields fields,
                Map<String, Object> params,
                String name,
                List<String> keys,
                String before,
                String after) {
            List<String> arms = new ArrayList<>();
            for (int k = 0; k < keys.size(); k++) {
                String key = name + k;
                params.put(key, before + keys.get(k) + after);
                for (Column c : fields.columns) {
                    if (c.phone) {
                        arms.add(c.arm("bms_digits(" + c.sql + ") LIKE :" + key));
                    }
                }
            }
            return arms;
        }

        private String rank(Fields fields, Map<String, Object> params) {
            String phrase = String.join(" ", terms);
            params.put("rp", escape(phrase) + "%");
            params.put("rw", "% " + escape(phrase) + "%");
            List<String> prefix = new ArrayList<>();
            List<String> word = new ArrayList<>();
            List<String> eachWord = new ArrayList<>();
            for (Column c : fields.columns) {
                if (c.phone) {
                    continue;
                }
                String f = "bms_fold(" + c.sql + ")";
                prefix.add(c.arm(f + " LIKE bms_fold(:rp)"));
                word.add(c.arm(f + " LIKE bms_fold(:rw)"));
                if (terms.size() > 1) {
                    List<String> each = new ArrayList<>();
                    for (int i = 0; i < terms.size(); i++) {
                        params.put("tp" + i, escape(terms.get(i)) + "%");
                        params.put("tw" + i, "% " + escape(terms.get(i)) + "%");
                        each.add("(" + f + " LIKE bms_fold(:tp" + i + ") OR " + f + " LIKE bms_fold(:tw" + i + "))");
                    }
                    eachWord.add(c.arm("(" + String.join(" AND ", each) + ")"));
                }
            }
            if (fields.hasPhone()) {
                List<String> keys = !wholePhone.isEmpty()
                        ? wholePhone
                        : terms.size() == 1 ? phoneKeys(terms.getFirst()) : List.of();
                for (int k = 0; k < keys.size(); k++) {
                    String key = keys.get(k);
                    // A number written with the country code or the trunk zero starts at the same place.
                    for (String start : new String[] {key, "0" + key, "256" + key}) {
                        String name = "rd" + k + start.length();
                        params.put(name, start + "%");
                        for (Column c : fields.columns) {
                            if (c.phone) {
                                prefix.add(c.arm("bms_digits(" + c.sql + ") LIKE :" + name));
                            }
                        }
                    }
                }
            }
            return "CASE WHEN " + orFalse(prefix) + " THEN 0 WHEN " + orFalse(word) + " THEN 1 WHEN "
                    + orFalse(eachWord) + " THEN 2 ELSE 3 END";
        }

        private static String orFalse(List<String> arms) {
            return arms.isEmpty() ? "false" : "(" + String.join(" OR ", arms) + ")";
        }
    }

    /**
     * What to append to a query. {@code where} is a parenthesised boolean expression, {@code rank} an
     * integer expression (0 to 3) to select as {@code search_rank} and to order by first.
     */
    public record Match(String where, String rank, Map<String, Object> params) {}

    /** A column reached through a foreign key: {@code fk IN (SELECT key FROM from WHERE <condition>)}. */
    private record Related(String fk, String from, String key) {}

    /** One searched column: a SQL expression, whether it holds a phone number, an optional guard and an optional relation. */
    private record Column(String sql, boolean phone, String guard, Related related) {

        /** The condition inside the relation (a subquery that runs once, not per row) and behind the guard. */
        String arm(String condition) {
            String c = condition;
            if (related != null) {
                c = related.fk + " IN (SELECT " + related.key + " FROM " + related.from + " WHERE " + c + ")";
            }
            return guard == null ? c : "(" + guard + " AND " + c + ")";
        }
    }

    /**
     * The columns of one entity: {@code names} and {@code codes} match as folded text (the split is
     * documentation, both are substring and prefix matched), {@code phones} match as digits. A group
     * added with {@link #guarded} matches only where its guard is true, for columns the list does not
     * show and that need a permission of their own (a member's phone and national id on a loan list). A
     * group added with {@link #related} lives in another table, reached through a foreign key; its condition
     * becomes an uncorrelated subquery, evaluated once per statement and not once per row. Every column must be a
     * plain column reference: {@code bms_fold} uses its argument more than once when it is inlined, so an
     * expression (above all a subquery) would be worked out again each time.
     */
    public static final class Fields {

        private final List<Column> columns = new ArrayList<>();

        public static Fields of() {
            return new Fields();
        }

        public Fields names(String... sql) {
            return add(false, sql);
        }

        public Fields codes(String... sql) {
            return add(false, sql);
        }

        public Fields phones(String... sql) {
            return add(true, sql);
        }

        /** Adds the columns of {@code inner}, each matching only where {@code guard} holds. */
        public Fields guarded(String guard, Fields inner) {
            for (Column c : inner.columns) {
                columns.add(new Column(c.sql, c.phone, guard, c.related));
            }
            return this;
        }

        /**
         * Adds the columns of {@code inner}, which belong to table {@code from} (for example
         * {@code "retail_customers c"}) with key column {@code key}, matched through {@code fk} of this entity.
         */
        public Fields related(String fk, String from, String key, Fields inner) {
            for (Column c : inner.columns) {
                columns.add(new Column(c.sql, c.phone, c.guard, new Related(fk, from, key)));
            }
            return this;
        }

        private Fields add(boolean phone, String... sql) {
            for (String s : sql) {
                columns.add(new Column(s, phone, null, null));
            }
            return this;
        }

        private boolean hasPhone() {
            return columns.stream().anyMatch(c -> c.phone);
        }
    }

    // ------------------------------------------------------------------------------------- paging

    /** A row with the rank it matched at (0 when there is no search). */
    public record Ranked<T>(T row, int rank) {}

    /** Maps a row and reads {@code search_rank} when the query selected it. */
    public static <T> RowMapper<Ranked<T>> ranked(RowMapper<T> mapper, boolean searching) {
        return (ResultSet rs, int n) -> new Ranked<>(mapper.mapRow(rs, n), searching ? rank(rs) : 0);
    }

    private static int rank(ResultSet rs) throws SQLException {
        return rs.getInt("search_rank");
    }

    /**
     * A searched page: {@code inner} selects {@code search_rank} and applies every filter; the keyset
     * condition and the order read that column, so the rank is worked out once per row and not again
     * in the paging condition. {@code keyset} is null on the first page.
     */
    public static String page(String inner, String keyset, String order) {
        return "SELECT * FROM (" + inner + ") ranked" + (keyset == null ? "" : " WHERE " + keyset) + " ORDER BY "
                + order + " LIMIT :limit";
    }

    /** The position after which the next page starts: the rank of the last row and the entity's own cursor. */
    public record After(int rank, String cursor) {}

    /** Marks a searched list's cursor; no sort key, number or code contains it. */
    private static final char RANKED = '\u0001';

    /**
     * Splits a cursor into its rank and the entity's own cursor. A searched list carries a marker, the
     * rank and the fingerprint of the text it was issued for; an unsearched list carries none of them.
     * A cursor of the other kind, or one issued for another text, is 400: restart the list.
     */
    public static After unwrap(Query query, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new After(0, null);
        }
        String raw = Cursor.decode(cursor).orElse("");
        boolean ranked = !raw.isEmpty() && raw.charAt(0) == RANKED;
        if (ranked != (query != null)) {
            throw malformed("This cursor does not belong to this search. Restart the list.");
        }
        if (query == null) {
            return new After(0, cursor);
        }
        String[] parts = raw.substring(1).split("~", 3);
        if (parts.length != 3) {
            throw malformed("Invalid cursor.");
        }
        if (!parts[1].equals(query.fingerprint())) {
            throw malformed("This cursor was issued for a different search text. Restart the list.");
        }
        try {
            return new After(Integer.parseInt(parts[0]), parts[2]);
        } catch (NumberFormatException e) {
            throw malformed("Invalid cursor.");
        }
    }

    /** The cursor to hand out: the entity's own cursor, with the rank and the text's fingerprint in front when searching. */
    public static String wrap(Query query, int rank, String cursor) {
        return query == null
                ? cursor
                : Cursor.encode(RANKED + Integer.toString(rank) + "~" + query.fingerprint() + "~" + cursor);
    }

    private static ApiException malformed(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", detail);
    }
}
