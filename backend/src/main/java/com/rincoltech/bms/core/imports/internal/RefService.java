package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportRefs.Opening;
import com.rincoltech.bms.core.imports.ImportRefs.StagedRow;
import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.core.ledger.LedgerPosting.TransactionEntry;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@code import_refs} (migration V143, issue #133): what the business's imports already hold, the rows a writer writes with
 * their {@code source_ref}, the registration of what a writer creates, and the opening journals. Every method runs inside
 * the caller's transaction (the batch's, or the validation's), under the business's row-level security.
 */
@Service
class RefService {

    /** What an opening journal is registered as. */
    static final String JOURNAL = "ledger.journal_entry";

    /** The source module of the opening journals, which the ledger's trading probe does not count as trading. */
    static final String MODULE = "core.imports";

    private final JdbcClient jdbc;
    private final PreviewService preview;
    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;

    RefService(JdbcClient jdbc, PreviewService preview, LedgerPosting ledger, LedgerAccounts accounts) {
        this.jdbc = jdbc;
        this.preview = preview;
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /**
     * The row part of a {@code source_ref}: {@code ref:<code>} for a row its mapping knows by a reference column (the
     * validation's reference {@code col:<source>:<code>}), {@code line:<n>} for a row known by its position.
     */
    static String row(String validationRef, String source, int line) {
        String key = RowValidator.rowKeyOf(validationRef, source, line);
        String column = "col:" + source + ":";
        return key.startsWith(column) ? "ref:" + key.substring(column.length()) : "line:" + line;
    }

    /** The {@code source_ref} of a staged row of a source, from its stored or typed-for-JSON values. */
    static String sourceRef(PreviewService.BatchSource src, String validationRef, int line, Map<String, ?> values) {
        return ImportRefs.sourceRef(
                src.file(), src.tab(), row(validationRef, src.source(), line), ImportRefs.contentHash(values));
    }

    /**
     * Every key of one source file and tab the business's imports hold, with the references held under it: what the
     * validation compares each row with ({@code CHANGED_SINCE_IMPORT}).
     */
    Map<String, Set<String>> imported(String file, String tab) {
        return imported(jdbc, file, tab);
    }

    /** As {@link #imported(String, String)}, for the preview, which this service depends on. */
    static Map<String, Set<String>> imported(JdbcClient jdbc, String file, String tab) {
        String prefix = ImportRefs.sourceKey(file, tab, "x");
        prefix = prefix.substring(0, prefix.length() - 1);
        String like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Map<String, Set<String>> out = new HashMap<>();
        jdbc.sql("SELECT source_key, source_ref FROM import_refs WHERE source_key LIKE ?")
                .param(like)
                .query((rs, n) -> out.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                        .add(rs.getString(2)))
                .list();
        return out;
    }

    /**
     * The batch's rows to write after a line, each with its {@code source_ref} and what the business's imports hold for it.
     * A row whose key is held with another content hash is refused with {@code CHANGED_SINCE_IMPORT}: never written twice.
     */
    List<StagedRow> rows(UUID batchId, PreviewService.BatchSource src, int afterLine, int limit) {
        List<PreviewService.StoredRow> stored = preview.rowsToWrite(batchId, src.set(), afterLine, limit);
        Map<String, String> refs = new LinkedHashMap<>();
        for (PreviewService.StoredRow r : stored) {
            refs.put(sourceRef(src, r.validationRef(), r.line(), r.stored()), null);
        }
        Map<String, Map<String, UUID>> held = new HashMap<>();
        Map<String, Set<String>> heldRefs = new HashMap<>();
        if (!refs.isEmpty()) {
            String[] keys =
                    refs.keySet().stream().map(ImportRefs::keyOf).distinct().toArray(String[]::new);
            jdbc.sql("SELECT source_key, source_ref, target_type, target_id FROM import_refs WHERE source_key = ANY(?)")
                    .param(keys)
                    .query((rs, n) -> {
                        heldRefs.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                                .add(rs.getString(2));
                        held.computeIfAbsent(rs.getString(2), k -> new HashMap<>())
                                .put(rs.getString(3), rs.getObject(4, UUID.class));
                        return null;
                    })
                    .list();
        }
        List<StagedRow> out = new ArrayList<>(stored.size());
        for (PreviewService.StoredRow r : stored) {
            String ref = sourceRef(src, r.validationRef(), r.line(), r.stored());
            Set<String> underKey = heldRefs.getOrDefault(ImportRefs.keyOf(ref), Set.of());
            if (!underKey.isEmpty() && !ImportRefs.holds(underKey, ref)) {
                throw changedSinceImport(r.line());
            }
            // A key held by its key alone (V145's rows, the import-retail command's) is this row whatever its content.
            Map<String, UUID> targets = held.containsKey(ref)
                    ? held.get(ref)
                    : held.getOrDefault(ImportRefs.byKey(ImportRefs.keyOf(ref)), Map.of());
            out.add(new StagedRow(
                    r.line(),
                    r.typed(),
                    ref,
                    targets.isEmpty() ? ImportRefs.Status.NEW : ImportRefs.Status.EXISTING,
                    targets));
        }
        return out;
    }

    /**
     * Registers a record a writer created, in the caller's transaction. A key already held with another content hash is
     * refused ({@code CHANGED_SINCE_IMPORT}); the same record registered twice for one reference is refused by the
     * table's unique key. A row may create several records of one type (a loan's collateral items), each registered. A
     * journal is not taken here: a writer registers one only by posting it through {@link #openingJournal}.
     */
    void register(UUID runId, UUID batchId, String sourceRef, String targetType, UUID targetId) {
        if (JOURNAL.equals(targetType)) {
            throw new IllegalArgumentException("a journal is registered only as an opening journal");
        }
        insert(runId, batchId, sourceRef, targetType, targetId);
    }

    private void insert(UUID runId, UUID batchId, String sourceRef, String targetType, UUID targetId) {
        String key = ImportRefs.keyOf(sourceRef);
        boolean other = jdbc.sql("SELECT EXISTS (SELECT 1 FROM import_refs WHERE source_key = ? AND source_ref <> ?)")
                .params(key, sourceRef)
                .query(Boolean.class)
                .single();
        if (other) {
            throw changedSinceImport(0);
        }
        jdbc.sql("""
                        INSERT INTO import_refs (id, tenant_id, run_id, batch_id, source_key, source_ref, target_type,
                                                 target_id)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), runId, batchId, key, sourceRef, targetType, targetId)
                .update();
    }

    /** What an opening journal came to: its status, its totals as the ledger's lines hold them and its number. */
    record Posted(String status, long debitMinor, long creditMinor, String entryNo) {}

    /**
     * Posts one opening journal of the run (spec section 3.7) through {@code post_entry} against
     * {@code opening_balance_equity} and registers it in {@code import_refs} in the caller's transaction: once per batch,
     * branch and kind. Its reference is the framework function's, of the file {@code opening}, the kind as the tab, the
     * run, batch and branch as the row, and the hash of the account, side, amount and date.
     */
    Posted openingJournal(UUID runId, UUID batchId, Opening o) {
        if (o.amountMinor() == 0) {
            return new Posted("nothing to post", 0, 0, null);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("account", o.accountKey());
        content.put("debit", o.debit());
        content.put("amount", o.amountMinor());
        content.put("date", o.entryDate().toString());
        // One per batch (review of f3be0a6 on #872): two batches of a run that both hold a branch's stock post a
        // journal each, never one that collides with the other's.
        String ref = ImportRefs.sourceRef(
                "opening",
                o.kind(),
                "run:" + runId + ":batch:" + batchId + ":branch:" + o.branchId(),
                ImportRefs.contentHash(content));
        UUID posted = jdbc.sql("SELECT target_id FROM import_refs WHERE source_ref = ? AND target_type = ?")
                .params(ref, JOURNAL)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (posted != null) {
            // The ledger's totals, read through its port; the entry's number is the ledger's and is not read here.
            LedgerPosting.Totals totals = ledger.totals(posted);
            return new Posted("already posted", totals.debitMinor(), totals.creditMinor(), null);
        }
        LedgerAccounts.Account account = accounts.bySystemKey(o.accountKey())
                .orElseThrow(() -> ApiException.rule(
                        "account_not_postable", "The business has no postable account " + o.accountKey() + "."));
        LedgerAccounts.Account equity = accounts.bySystemKey("opening_balance_equity")
                .orElseThrow(() -> ApiException.rule(
                        "account_not_postable", "The business has no opening balance equity account."));
        Money amount = Money.of(o.amountMinor(), account.currency());
        List<Line> lines = o.debit()
                ? List.of(Line.debit(account.id(), amount), Line.credit(equity.id(), amount))
                : List.of(Line.debit(equity.id(), amount), Line.credit(account.id(), amount));
        LedgerPosting.PostedEntry entry = ledger.post(new EntryRequest(
                o.branchId(),
                o.entryDate(),
                "IMPORT-OPENING",
                "Opening balance: " + o.kind(),
                MODULE,
                o.ledgerType(),
                runId,
                // At most 100 characters (journal_entries.idempotency_key): a hash of run, batch, branch and kind.
                "import-opening:"
                        + ImportRefs.contentHash(Map.of(
                                "run",
                                runId.toString(),
                                "batch",
                                batchId.toString(),
                                "branch",
                                o.branchId().toString(),
                                "kind",
                                o.kind())),
                lines));
        insert(runId, batchId, ref, JOURNAL, entry.entryId());
        LedgerPosting.Totals totals = ledger.totals(entry.entryId());
        return new Posted("posted", totals.debitMinor(), totals.creditMinor(), entry.entryNo());
    }

    // JUSTIFICATION-A3: the Lead's ruling 6 on #872, the end-of-batch check of the journals a batch posted.
    /**
     * Refuses a batch that posted a journal other than an opening journal (Lead ruling 6, and the Lead's note on the
     * review of 8e570b0, #872). Writers never vouch for their journals: the check reads each entry the transaction posted
     * from the ledger. One passes only as the framework's opening journal, {@code core.imports}, {@code opening_balance},
     * with a line on {@code opening_balance_equity}, registered by {@link #openingJournal} (the only way a journal is
     * registered from a writer: {@link #register} refuses one), whatever ledger type its template named
     * ({@link ImportRefs.Opening#ledgerType}). Since #135 the retail stock opening is posted that way. The one exception
     * left: an opening of the cash book that the {@code import-retail} command's cash files post through the cash book
     * (outside {@code retail_v1}), recognised by the ledger alone ({@link #isRetailCommandOpening}) and registered here by
     * the framework, never by the writer. Anything else fails the batch, which rolls back whole.
     */
    void requireOnlyOpeningJournals(UUID runId, UUID batchId) {
        List<TransactionEntry> posted = ledger.postedInThisTransaction();
        if (posted.isEmpty()) {
            return;
        }
        UUID[] ids = posted.stream().map(TransactionEntry::entryId).toArray(UUID[]::new);
        List<UUID> held = jdbc.sql("SELECT target_id FROM import_refs WHERE target_type = ? AND target_id = ANY(?)")
                .params(JOURNAL, ids)
                .query(UUID.class)
                .list();
        Set<UUID> registered = new HashSet<>(held);
        long refused = 0;
        for (TransactionEntry e : posted) {
            // Registered already, by this batch or by an earlier batch of one outer transaction (a dry run's).
            if ((isFrameworkOpening(e) || isRetailCommandOpening(e)) && registered.contains(e.entryId())) {
                continue;
            }
            if (isRetailCommandOpening(e)) {
                insert(
                        runId,
                        batchId,
                        ImportRefs.sourceRef("import-retail", e.sourceType(), "entry:" + e.entryId(), "0"),
                        JOURNAL,
                        e.entryId());
                continue;
            }
            refused++;
        }
        if (refused > 0) {
            throw new IllegalStateException(
                    "an import batch posted " + refused + " journal(s) that are not its opening journals");
        }
    }

    /**
     * The cash book openings the {@code import-retail} command's cash files post (module {@code retail}). The stock opening
     * {@code retail.import_opening} is not one since #135: {@code retail_v1} posts it through {@link #openingJournal}, so
     * one posted by the retail module inside a batch fails it.
     */
    static final Set<String> RETAIL_OPENINGS =
            Set.of("retail.cash_opening", "retail.advance_opening", "retail.advance_repayment_opening");

    /**
     * The framework's opening journal, by what the ledger holds: source module {@code core.imports}, which only
     * {@link #openingJournal} posts, and a line on {@code opening_balance_equity}, whatever ledger type the template named.
     */
    static boolean isFrameworkOpening(TransactionEntry e) {
        return MODULE.equals(e.sourceModule()) && e.againstOpeningEquity();
    }

    /** An opening journal of the {@code import-retail} command, by what the ledger holds. */
    static boolean isRetailCommandOpening(TransactionEntry e) {
        return "retail".equals(e.sourceModule())
                && RETAIL_OPENINGS.contains(e.sourceType())
                && e.againstOpeningEquity();
    }

    static ApiException changedSinceImport(int line) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "CHANGED_SINCE_IMPORT",
                "Changed since import",
                (line > 0 ? "Line " + line : "A row")
                        + " was already imported with other content; correct it in the app, not by import.");
    }
}
