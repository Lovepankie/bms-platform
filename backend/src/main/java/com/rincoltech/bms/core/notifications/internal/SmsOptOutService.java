package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.notifications.SmsOptOuts;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.PhoneNumbers;
import com.rincoltech.bms.kernel.Search;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SMS opt-out table (FR-NTF-14, ADR-036 decision 8, spec 4.9): the member screen's control through the
 * {@link SmsOptOuts} port, and the administrator's list of numbers (add, remove, import), searched with the shared
 * matcher. A row is either about a member (it follows the member to any number) or about a number alone (matched by
 * the keyed hash, so a pasted STOP list works whoever the number belongs to). The number itself is never stored,
 * only its hash and a masked form for the screen. Every change is audited with the masked number only; removal
 * keeps the row (removed_at) so the history stays. Two people acting at once, or a double click, cannot stack rows:
 * the insert is {@code ON CONFLICT} against a partial unique index and the loser updates the winner's row.
 */
@Service
class SmsOptOutService implements SmsOptOuts {

    static final int MAX_IMPORT_ROWS = 2000;
    static final int MAX_IMPORT_CHARS = 200_000;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    /** The one permission that may lift or narrow an opt-out (the tenant administrator's; no new row is seeded). */
    static final String LIFT_PERMISSION = "core.settings.manage";

    static final int MIN_REASON = 3;
    static final int MAX_REASON = 200;
    private static final Pattern SEPARATORS = Pattern.compile("[\\r\\n,;\\t]+");
    private static final Search.Fields SEARCH = Search.Fields.of().phones("o.recipient_masked");

    /** One active opt-out as the list shows it. */
    record Item(
            UUID id,
            String kind,
            UUID memberId,
            String scope,
            String source,
            String recipientMasked,
            Instant createdAt,
            UUID createdBy) {}

    record Page(List<Item> items, String nextCursor) {}

    /** One line of an import: what happened to it, never the number. */
    @Schema(name = "SmsOptOutImportRow")
    record ImportRow(
            int line,

            @Schema(description = "added, changed, unchanged, duplicate_in_list or invalid_phone")
            String outcome) {}

    @Schema(name = "SmsOptOutImport")
    record ImportResult(
            boolean dryRun, int rowsRead, int added, int changed, int unchanged, int invalid, List<ImportRow> rows) {}

    private final JdbcClient jdbc;
    private final SmsRecipientHash hasher;
    private final AuditLog audit;
    private final BusinessClock clock;

    SmsOptOutService(JdbcClient jdbc, SmsRecipientHash hasher, AuditLog audit, BusinessClock clock) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- the member screen's port ---------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<Entry> status(UUID memberId) {
        return jdbc.sql("SELECT id, member_id, scope, recipient_masked, created_at FROM sms_opt_outs"
                        + " WHERE member_id = ? AND removed_at IS NULL")
                .param(memberId)
                .query((rs, n) -> new Entry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("member_id", UUID.class),
                        Scope.of(rs.getString("scope")).orElseThrow(),
                        rs.getString("recipient_masked"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional
    public Entry optOut(UUID memberId, String phoneE164, Scope scope, String reason) {
        // Another person may lift the row between our insert finding it and our reading it; then the insert is tried
        // again.
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<Entry> done = tryOptOut(memberId, phoneE164, scope, reason);
            if (done.isPresent()) {
                return done.get();
            }
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "try_again",
                "Try again",
                "The opt-out changed while it was being saved. Try again.");
    }

    private Optional<Entry> tryOptOut(UUID memberId, String phoneE164, Scope scope, String reason) {
        String masked = phoneE164 == null
                ? null
                : PhoneNumbers.forSms(phoneE164).map(SmsOutboxChannel::maskE164).orElse(null);
        Instant now = clock.now();
        UUID created = jdbc.sql("INSERT INTO sms_opt_outs (id, tenant_id, created_at, member_id, recipient_hash,"
                        + " recipient_masked, scope, source, created_by)"
                        + " VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, NULL, ?, ?, 'staff', ?)"
                        + " ON CONFLICT (tenant_id, member_id) WHERE removed_at IS NULL AND member_id IS NOT NULL"
                        + " DO NOTHING RETURNING id")
                .params(UUID.randomUUID(), Timestamp.from(now), memberId, masked, scope.key(), actor())
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (created != null) {
            audit("core.sms.opt_out.added", created, Map.of(), state("member", memberId, scope.key(), "staff", masked));
        } else {
            // Someone (or an earlier click) holds the active row: change its scope if it differs.
            Optional<Map<String, Object>> found = jdbc
                    .sql("SELECT id, scope, recipient_masked FROM sms_opt_outs"
                            + " WHERE member_id = ? AND removed_at IS NULL FOR UPDATE")
                    .param(memberId)
                    .query()
                    .listOfRows()
                    .stream()
                    .findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> was = found.get();
            if (!scope.key().equals(was.get("scope"))) {
                String why = requireReasonIfNarrowing(
                        Scope.of((String) was.get("scope")).orElseThrow(), scope, reason);
                jdbc.sql("UPDATE sms_opt_outs SET scope = ? WHERE id = ?")
                        .params(scope.key(), was.get("id"))
                        .update();
                audit(
                        "core.sms.opt_out.changed",
                        (UUID) was.get("id"),
                        state("member", memberId, (String) was.get("scope"), "staff", (String)
                                was.get("recipient_masked")),
                        withReason(
                                state("member", memberId, scope.key(), "staff", (String) was.get("recipient_masked")),
                                why));
            }
        }
        return status(memberId);
    }

    /**
     * A block can be made wider by anyone who may set one, but narrowed (every message down to reminders only) or lifted
     * only by the tenant administrator: the opt-out is the member's protection against being pestered (spec 4.14), so a
     * single click by a field officer must not undo it.
     */
    private static boolean narrows(Scope was, Scope now) {
        return Scope.ALL == was && Scope.REMINDERS == now;
    }

    /**
     * Narrowing is a partial lift: the administrator's, and with a reason like any lift. Returns the trimmed reason, or
     * null when the change is not a narrowing (then any reason given is ignored).
     */
    private static String requireReasonIfNarrowing(Scope was, Scope now, String reason) {
        if (!narrows(was, now)) {
            return null;
        }
        requireAdministrator();
        return requireReason(reason);
    }

    private static Map<String, Object> withReason(Map<String, Object> state, String reason) {
        if (reason == null) {
            return state;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(state);
        out.put("reason", reason);
        return out;
    }

    private static void requireAdministrator() {
        if (!CurrentPrincipal.require().hasPermission(LIFT_PERMISSION)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Only a business administrator can lift or narrow an opt-out.");
        }
    }

    /** The reason is part of the audit row; it is words about why, kept short, and it is not for names or numbers. */
    private static String requireReason(String reason) {
        String r = reason == null ? "" : reason.strip();
        if (r.length() < MIN_REASON || r.length() > MAX_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "reason", "reason_required", "Say why, in " + MIN_REASON + " to " + MAX_REASON + " characters.")));
        }
        return r;
    }

    @Override
    @Transactional
    public boolean lift(UUID memberId, String reason) {
        requireAdministrator();
        String why = requireReason(reason);
        Optional<Map<String, Object>> was = jdbc
                .sql("UPDATE sms_opt_outs SET removed_at = ?, removed_by = ?"
                        + " WHERE member_id = ? AND removed_at IS NULL RETURNING id, scope, source, recipient_masked")
                .params(Timestamp.from(clock.now()), actor(), memberId)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        was.ifPresent(row -> audit(
                "core.sms.opt_out.removed",
                (UUID) row.get("id"),
                state("member", memberId, (String) row.get("scope"), (String) row.get("source"), (String)
                        row.get("recipient_masked")),
                Map.of("reason", why)));
        return was.isPresent();
    }

    // ---- the administrator's list ---------------------------------------------------------------

    @Transactional(readOnly = true)
    Page list(String q, String scope, String kind, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Search.Query search = Search.parse(q);
        Search.After after = Search.unwrap(search, cursor);
        Cursor.Key key = Cursor.decodeKey(after.cursor()).orElse(null);

        Search.Match match = search == null ? null : search.match(SEARCH);
        StringBuilder inner = new StringBuilder("SELECT ")
                .append(match == null ? "" : match.rank() + " AS search_rank, ")
                .append("o.id, o.member_id, o.scope, o.source, o.recipient_masked, o.created_at, o.created_by,"
                        + " -(extract(epoch from o.created_at) * 1000000)::bigint AS sort_key"
                        + " FROM sms_opt_outs o WHERE o.removed_at IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        if (scope != null) {
            inner.append(" AND o.scope = :scope");
            params.put("scope", scope);
        }
        if ("member".equals(kind)) {
            inner.append(" AND o.member_id IS NOT NULL");
        } else if ("number".equals(kind)) {
            inner.append(" AND o.member_id IS NULL");
        }
        if (match != null) {
            inner.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        String keyset = null;
        if (key != null) {
            params.put("afterKey", longOf(key.sortKey()));
            params.put("afterId", key.id());
            if (match == null) {
                keyset = "(sort_key, id) > (:afterKey, :afterId)";
            } else {
                keyset = "(search_rank, sort_key, id) > (:afterRank, :afterKey, :afterId)";
                params.put("afterRank", after.rank());
            }
        }
        params.put("limit", size + 1);
        List<Search.Ranked<Row>> rows = jdbc.sql(Search.page(
                        inner.toString(), keyset, match == null ? "sort_key, id" : "search_rank, sort_key, id"))
                .params(params)
                .query(Search.ranked(
                        (rs, n) -> new Row(
                                new Item(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("member_id") == null ? "number" : "member",
                                        rs.getObject("member_id", UUID.class),
                                        rs.getString("scope"),
                                        rs.getString("source"),
                                        rs.getString("recipient_masked"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getObject("created_by", UUID.class)),
                                rs.getLong("sort_key")),
                        match != null))
                .list();
        boolean more = rows.size() > size;
        List<Search.Ranked<Row>> shown = more ? rows.subList(0, size) : rows;
        String next = null;
        if (more) {
            Search.Ranked<Row> last = shown.getLast();
            next = Search.wrap(
                    search,
                    last.rank(),
                    Cursor.encode(last.row().sortKey() + "|" + last.row().item().id()));
        }
        return new Page(shown.stream().map(r -> r.row().item()).toList(), next);
    }

    private record Row(Item item, long sortKey) {}

    /** Adds a number to the list, or sets the scope of the one already there (the administrator's intent, either way). */
    @Transactional
    Added addNumber(String rawPhone, Scope scope, String reason) {
        String e164 = PhoneNumbers.forSms(rawPhone)
                .orElseThrow(() -> ApiException.validation(List.of(new FieldProblem(
                        "phone", "invalid_phone", "Use a Uganda mobile number such as 0700 000 001."))));
        Upserted result = upsertNumber(e164, scope, false, "number", reason);
        return new Added(item(result.id()), "added".equals(result.outcome()));
    }

    /** The row, and whether this call made it (else it was already on the list). */
    record Added(Item item, boolean created) {}

    /** The outcome of putting one number on the list. */
    private record Upserted(UUID id, String outcome) {}

    /**
     * Puts a number on the list. {@code strongerOnly} (an import) never weakens a row that is already there: a
     * pasted list must not lift a block an administrator set to {@code all}.
     */
    private Upserted upsertNumber(String e164, Scope scope, boolean strongerOnly, String source, String reason) {
        // Another person may lift the row between our insert finding it and our reading it; then the insert is tried
        // again.
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<Upserted> done = tryUpsertNumber(e164, scope, strongerOnly, source, reason);
            if (done.isPresent()) {
                return done.get();
            }
        }
        throw new ApiException(
                HttpStatus.CONFLICT, "try_again", "Try again", "The list changed while it was being saved. Try again.");
    }

    private Optional<Upserted> tryUpsertNumber(
            String e164, Scope scope, boolean strongerOnly, String source, String reason) {
        String hash = hasher.of(e164);
        String masked = SmsOutboxChannel.maskE164(e164);
        UUID created = jdbc.sql("INSERT INTO sms_opt_outs (id, tenant_id, created_at, member_id, recipient_hash,"
                        + " recipient_masked, scope, source, created_by)"
                        + " VALUES (?, current_setting('app.tenant_id')::uuid, ?, NULL, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (tenant_id, recipient_hash) WHERE removed_at IS NULL AND member_id IS NULL"
                        + " DO NOTHING RETURNING id")
                .params(UUID.randomUUID(), Timestamp.from(clock.now()), hash, masked, scope.key(), source, actor())
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (created != null) {
            audit("core.sms.opt_out.added", created, Map.of(), state("number", null, scope.key(), source, masked));
            return Optional.of(new Upserted(created, "added"));
        }
        Optional<Map<String, Object>> found = jdbc
                .sql("SELECT id, scope FROM sms_opt_outs"
                        + " WHERE member_id IS NULL AND recipient_hash = ? AND removed_at IS NULL FOR UPDATE")
                .param(hash)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> was = found.get();
        UUID id = (UUID) was.get("id");
        String had = (String) was.get("scope");
        boolean stronger = Scope.ALL == scope && "reminders".equals(had);
        boolean differs = !scope.key().equals(had);
        if (differs && (!strongerOnly || stronger)) {
            // Narrowing a number's block is a partial lift: it needs a reason (an import never narrows).
            String why = requireReasonIfNarrowing(Scope.of(had).orElseThrow(), scope, reason);
            jdbc.sql("UPDATE sms_opt_outs SET scope = ? WHERE id = ?")
                    .params(scope.key(), id)
                    .update();
            audit(
                    "core.sms.opt_out.changed",
                    id,
                    state("number", null, had, source, masked),
                    withReason(state("number", null, scope.key(), source, masked), why));
            return Optional.of(new Upserted(id, "changed"));
        }
        return Optional.of(new Upserted(id, "unchanged"));
    }

    /**
     * Lifts one row of the list, whoever it is about, with the reason, for the tenant administrator only. 404 when it is
     * not an active row of this tenant.
     */
    @Transactional
    void remove(UUID id, String reason) {
        requireAdministrator();
        String why = requireReason(reason);
        Map<String, Object> row = jdbc
                .sql("UPDATE sms_opt_outs SET removed_at = ?, removed_by = ?"
                        + " WHERE id = ? AND removed_at IS NULL RETURNING member_id, scope, source, recipient_masked")
                .params(Timestamp.from(clock.now()), actor(), id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(ApiException::notFound);
        UUID member = (UUID) row.get("member_id");
        audit(
                "core.sms.opt_out.removed",
                id,
                state(
                        member == null ? "number" : "member",
                        member,
                        (String) row.get("scope"),
                        (String) row.get("source"),
                        (String) row.get("recipient_masked")),
                Map.of("reason", why));
    }

    /**
     * Reads a pasted list, one number per entry (separated by new lines, commas or semicolons), normalises each with
     * the strict SMS rule and, unless {@code dryRun}, puts the valid ones on the list. An entry that is not a number
     * we send to is reported by its position and left out; the rest go on: a stop request must be honoured even when
     * another line of the same paste is wrong. Repeating the same paste changes nothing.
     */
    @Transactional
    ImportResult importNumbers(String text, Scope scope, boolean dryRun) {
        if (text == null || text.isBlank()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("numbers", "required", "Paste at least one number.")));
        }
        if (text.length() > MAX_IMPORT_CHARS) {
            throw ApiException.validation(List.of(new FieldProblem("numbers", "too_long", "The list is too long.")));
        }
        List<String> entries = new ArrayList<>();
        for (String part : SEPARATORS.split(text)) {
            if (!part.isBlank()) {
                entries.add(part.strip());
            }
        }
        if (entries.size() > MAX_IMPORT_ROWS) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "numbers", "too_many_rows", "At most " + MAX_IMPORT_ROWS + " numbers at a time.")));
        }
        List<ImportRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int added = 0;
        int changed = 0;
        int unchanged = 0;
        int invalid = 0;
        for (int i = 0; i < entries.size(); i++) {
            int line = i + 1;
            Optional<String> e164 = PhoneNumbers.forSms(entries.get(i));
            if (e164.isEmpty()) {
                invalid++;
                rows.add(new ImportRow(line, "invalid_phone"));
                continue;
            }
            if (!seen.add(e164.get())) {
                unchanged++;
                rows.add(new ImportRow(line, "duplicate_in_list"));
                continue;
            }
            String outcome = dryRun
                    ? previewOutcome(e164.get(), scope)
                    : upsertNumber(e164.get(), scope, true, "import", null).outcome();
            switch (outcome) {
                case "added" -> added++;
                case "changed" -> changed++;
                default -> unchanged++;
            }
            rows.add(new ImportRow(line, outcome));
        }
        if (!dryRun && (added > 0 || changed > 0)) {
            audit(
                    "core.sms.opt_out.imported",
                    UUID.randomUUID(),
                    Map.of(),
                    Map.of("rows_read", entries.size(), "added", added, "changed", changed, "scope", scope.key()));
        }
        return new ImportResult(dryRun, entries.size(), added, changed, unchanged, invalid, rows);
    }

    private String previewOutcome(String e164, Scope scope) {
        Optional<String> had = jdbc.sql("SELECT scope FROM sms_opt_outs"
                        + " WHERE member_id IS NULL AND recipient_hash = ? AND removed_at IS NULL")
                .param(hasher.of(e164))
                .query(String.class)
                .optional();
        if (had.isEmpty()) {
            return "added";
        }
        return Scope.ALL == scope && "reminders".equals(had.get()) ? "changed" : "unchanged";
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Item item(UUID id) {
        return jdbc.sql("SELECT id, member_id, scope, source, recipient_masked, created_at, created_by"
                        + " FROM sms_opt_outs WHERE id = ?")
                .param(id)
                .query((rs, n) -> new Item(
                        rs.getObject("id", UUID.class),
                        rs.getObject("member_id") == null ? "number" : "member",
                        rs.getObject("member_id", UUID.class),
                        rs.getString("scope"),
                        rs.getString("source"),
                        rs.getString("recipient_masked"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("created_by", UUID.class)))
                .single();
    }

    private static UUID actor() {
        return CurrentPrincipal.require().userId();
    }

    private static Map<String, Object> state(String kind, UUID memberId, String scope, String source, String masked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        if (memberId != null) {
            m.put("member_id", memberId.toString());
        }
        m.put("scope", scope);
        m.put("source", source);
        if (masked != null) {
            m.put("recipient", masked);
        }
        return m;
    }

    private void audit(String action, UUID id, Map<String, Object> before, Map<String, Object> after) {
        audit.record(new AuditLog.Entry(action, "core.sms_opt_out", id, null, before, after));
    }

    private static long longOf(String sortKey) {
        try {
            return Long.parseLong(sortKey);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Invalid cursor.");
        }
    }
}
