package com.rincoltech.bms.core.imports.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The review of one run as the validation applies it (issue #131, spec sections 3.5 and 10.1): every resolution of the run
 * from {@code import_issue_resolutions}, in the order they were made, folded per row. A resolution applies to a row only
 * while the row's cells are the ones it was made on ({@code raw_sha256}); it is found again by the row's identity
 * ({@code row_key}), so it carries across a new validation, a new parse and a new upload of the same file into the run. A
 * row whose identity had resolutions made on other cells, and none on its own, has changed since it was reviewed.
 */
final class ReviewState {

    /** The kinds that decide something about a row; {@code ask_customer} and {@code map_value} decide nothing about it. */
    static final Set<String> DECIDING =
            Set.of("edit_value", "exclude_row", "accept_suggestion", "link_row", "link_member", "override");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    /** One resolution as stored. {@code after} is its after value as plain maps, or empty. */
    record Made(
            UUID id,
            String rowKey,
            String sha,
            int occurrence,
            int copies,
            String code,
            String field,
            String kind,
            Map<String, Object> after) {}

    /**
     * What the review decided for one row with its current cells: the edits by field (the text in place of the cell, or
     * null to leave it empty), the exclusion, the links, the issues resolved by code and field, the questions for the
     * customer, and whether the row changed since it was reviewed.
     */
    record Row(
            Map<String, String> edits,
            UUID excludedBy,
            String linkedRowKey,
            UUID linkedMemberId,
            Map<String, UUID> resolvedBy,
            Map<String, Boolean> askCustomer,
            boolean changed) {

        static final Row NONE = new Row(Map.of(), null, null, null, Map.of(), Map.of(), false);

        /** The resolution that resolved an issue of this code and field on the row, or null. */
        UUID resolution(String code, String field) {
            return resolvedBy.get(key(code, field));
        }

        boolean asked(String code, String field) {
            return askCustomer.getOrDefault(key(code, field), false);
        }
    }

    private final Map<String, List<Made>> byRow;
    private final long last;

    /** The identities {@code link_row} resolutions link a row to. */
    private final Set<String> linked = new HashSet<>();

    private ReviewState(Map<String, List<Made>> byRow, long last) {
        this.byRow = byRow;
        this.last = last;
        byRow.values().stream()
                .flatMap(List::stream)
                .filter(m -> "link_row".equals(m.kind()) && m.after().get("row_key") != null)
                .forEach(m -> linked.add(String.valueOf(m.after().get("row_key"))));
    }

    /** Every resolution of the run, read under the business's row-level security. */
    static ReviewState load(JdbcClient jdbc, UUID runId) {
        Map<String, List<Made>> byRow = new HashMap<>();
        long[] last = {0};
        jdbc.sql("""
                        SELECT id, seq, row_key, raw_sha256, occurrence, copies, code, field, resolution,
                               after_value::text AS after_value
                          FROM import_issue_resolutions WHERE run_id = ? ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> {
                    String after = rs.getString("after_value");
                    Made m = new Made(
                            rs.getObject("id", UUID.class),
                            rs.getString("row_key"),
                            rs.getString("raw_sha256"),
                            rs.getInt("occurrence"),
                            rs.getInt("copies"),
                            rs.getString("code"),
                            rs.getString("field"),
                            rs.getString("resolution"),
                            after == null ? Map.of() : JSON.readValue(after, OBJECT));
                    last[0] = Math.max(last[0], rs.getLong("seq"));
                    return m;
                })
                .list()
                .forEach(m -> byRow.computeIfAbsent(m.rowKey(), k -> new ArrayList<>())
                        .add(m));
        return new ReviewState(byRow, last[0]);
    }

    /**
     * Whether the review can say anything of a row of this identity, or needs to find it: a resolution was made on it, or
     * links another row to it. The validation counts copies and cells for these identities only (issue #880), so a run
     * with no resolution holds nothing per row for the review.
     */
    boolean concerns(String rowKey) {
        return byRow.containsKey(rowKey) || linked.contains(rowKey);
    }

    /** Whether the run has no resolution at all. */
    boolean isEmpty() {
        return byRow.isEmpty();
    }

    /** The newest resolution's place in the run's order, or 0: a validation writes only if it is unchanged at the end. */
    long last() {
        return last;
    }

    /** The same, read now. */
    static long last(JdbcClient jdbc, UUID runId) {
        return jdbc.sql("SELECT coalesce(max(seq), 0) FROM import_issue_resolutions WHERE run_id = ?")
                .param(runId)
                .query(Long.class)
                .single();
    }

    /**
     * What the review decided for the row of this identity with these cells, the {@code occurrence}-th of the {@code copies}
     * such rows of the run, without asking whether it changed.
     */
    Row row(String rowKey, String sha, int occurrence, int copies) {
        return row(rowKey, sha, occurrence, copies, null);
    }

    /**
     * The same, and whether the row changed since it was reviewed: a deciding resolution of its identity was made on cells
     * that no row of the run with that identity has now ({@code present}: the SHA-256 of each such row's cells), and none on
     * its own. So two rows of one reference with different cells (a duplicate the queue resolves on one of them) never mark
     * each other changed; only cells that are gone from the run do. A resolution applies only to the copy it was made on:
     * a line repeated verbatim has the same identity and cells as its twin, and is told apart by its occurrence (review of
     * #868, must-fix 1). A resolution made while the run held another number of such copies is never matched by guess: it
     * applies to none of them, and each comes back as changed (Lead ruling on #868).
     */
    Row row(String rowKey, String sha, int occurrence, int copies, Set<String> present) {
        List<Made> all = byRow.get(rowKey);
        if (all == null) {
            return Row.NONE;
        }
        Map<String, String> edits = new LinkedHashMap<>();
        Map<String, UUID> resolvedBy = new HashMap<>();
        Map<String, Boolean> asked = new HashMap<>();
        UUID excludedBy = null;
        String linkedRow = null;
        UUID linkedMember = null;
        boolean reviewedHere = false;
        boolean reviewedElsewhere = false;
        for (Made m : all) {
            boolean sameCells = m.sha().equals(sha);
            boolean sameCopies = m.copies() == copies;
            boolean here = sameCells && sameCopies && m.occurrence() == occurrence;
            if (DECIDING.contains(m.kind())) {
                reviewedHere |= here;
                reviewedElsewhere |=
                        present != null && ((!sameCells && !present.contains(m.sha())) || (sameCells && !sameCopies));
            }
            if (!here) {
                continue;
            }
            switch (m.kind()) {
                case "edit_value", "accept_suggestion" -> {
                    if (m.field() != null && m.after().containsKey("value")) {
                        Object v = m.after().get("value");
                        edits.put(m.field(), v == null ? null : String.valueOf(v));
                    } else {
                        resolvedBy.put(key(m.code(), m.field()), m.id());
                    }
                }
                case "exclude_row" -> excludedBy = m.id();
                case "link_row" -> {
                    linkedRow = copy(
                            String.valueOf(m.after().get("row_key")),
                            String.valueOf(m.after().get("sha")),
                            ((Number) m.after().get("occurrence")).intValue());
                    resolvedBy.put(key(m.code(), m.field()), m.id());
                }
                case "link_member" -> {
                    linkedMember = UUID.fromString(String.valueOf(m.after().get("member_id")));
                    resolvedBy.put(key(m.code(), m.field()), m.id());
                }
                case "override" -> resolvedBy.put(key(m.code(), m.field()), m.id());
                case "ask_customer" ->
                    asked.put(
                            key(m.code(), m.field()),
                            Boolean.TRUE.equals(m.after().get("ask_customer")));
                default -> {
                    // map_value lives in the mapping version it made, not on the row.
                }
            }
        }
        return new Row(
                edits, excludedBy, linkedRow, linkedMember, resolvedBy, asked, reviewedElsewhere && !reviewedHere);
    }

    /** One copy of a row in the run: its identity, its cells and which of the rows with both it is. */
    static String copy(String rowKey, String sha, int occurrence) {
        return rowKey + "|" + sha + "|" + occurrence;
    }

    static String key(String code, String field) {
        return code + "|" + (field == null ? "" : field);
    }
}
