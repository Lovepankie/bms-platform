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
 *   <li>a term of four or more digits and a phone column also matches the digits of the phone: the digits
 *       as typed anywhere in the number (the last digits of a number, or a whole one), and, when the text
 *       starts with 256 or 0, the rest of it at the start of the number written with 256, with 0 or with
 *       neither, so "0772", "+2567", "0700 000 011" and "700000011" all find "+256700000011" and "0772123456",
 *       while a four digit term such as 0123 does not match every contact that has 123 somewhere in it; a
 *       whole text that looks like a phone number is matched as one number as well, so spaces inside it
 *       do not split it;
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
        PhoneKey whole = terms.size() > 1 ? phoneKey(text) : null;
        return new Query(List.copyOf(terms), whole);
    }

    /**
     * What to look for when a text is a phone number: the digits {@code typed} (found anywhere in a number,
     * so the last digits of a number work), and {@code rest}, the digits after a leading 256 or 0 (found only
     * at the start of a number, written with 256, with 0 or with neither). {@code rest} is empty when the text
     * starts with neither. The length is checked before anything is removed, so "0772" and "+2567" are
     * searches, not nothing.
     */
    record PhoneKey(String typed, String rest) {

        List<String> starts() {
            return rest.isEmpty() ? List.of() : List.of(rest, "0" + rest, "256" + rest);
        }
    }

    /** The key of a phone-like text of four or more typed digits; null when it is not one. */
    static PhoneKey phoneKey(String text) {
        if (!PHONE_LIKE.matcher(text).matches()) {
            return null;
        }
        String typed = NOT_DIGIT.matcher(text).replaceAll("");
        if (typed.length() < MIN_PHONE_DIGITS) {
            return null;
        }
        String rest = "";
        if (typed.startsWith("256")) {
            rest = typed.substring(3);
        } else if (typed.startsWith("0")) {
            rest = typed.substring(1);
        }
        return new PhoneKey(typed, rest);
    }

    /** Escapes the LIKE metacharacters so the text matches literally. */
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The search text, split into terms. {@code wholePhone} is the key of a multi-word phone-like text, or null. */
    public record Query(List<String> terms, PhoneKey wholePhone) {

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
                PhoneKey key = fields.hasPhone() ? phoneKey(term) : null;
                arms.addAll(phoneArms(fields, params, "d" + i, key, false));
                all.add("(" + String.join(" OR ", arms) + ")");
            }
            String where = String.join(" AND ", all);
            if (fields.hasPhone() && wholePhone != null) {
                where = "(" + where + " OR (" + String.join(" OR ", phoneArms(fields, params, "dw", wholePhone, false))
                        + "))";
            }
            return new Match("(" + where + ")", rank(fields, params), params);
        }

        /**
         * Phone conditions for one key: the typed digits anywhere in the number (or, for the rank, at its
         * start), and the rest at the start of the number written with 256, with 0 or with neither.
         */
        private static List<String> phoneArms(
                Fields fields, Map<String, Object> params, String name, PhoneKey key, boolean startOnly) {
            List<String> arms = new ArrayList<>();
            if (key == null) {
                return arms;
            }
            params.put(name + "t", (startOnly ? "" : "%") + key.typed() + "%");
            List<String> starts = key.starts();
            for (int k = 0; k < starts.size(); k++) {
                params.put(name + "s" + k, starts.get(k) + "%");
            }
            for (Column c : fields.columns) {
                if (!c.phone) {
                    continue;
                }
                String digits = "bms_digits(" + c.sql + ")";
                arms.add(c.arm(digits + " LIKE :" + name + "t"));
                for (int k = 0; k < starts.size(); k++) {
                    arms.add(c.arm(digits + " LIKE :" + name + "s" + k));
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
                PhoneKey key = wholePhone != null ? wholePhone : terms.size() == 1 ? phoneKey(terms.getFirst()) : null;
                prefix.addAll(phoneArms(fields, params, "rd", key, true));
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
     * in the paging condition. {@code keyset} is null on the first page. {@code OFFSET 0} fences the inner
     * query: without it the planner flattens the subquery and puts the rank expression back into the
     * keyset condition and the sort, which is the work this avoids.
     */
    public static String page(String inner, String keyset, String order) {
        return "SELECT * FROM (" + inner + " OFFSET 0) ranked" + (keyset == null ? "" : " WHERE " + keyset)
                + " ORDER BY " + order + " LIMIT :limit";
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
