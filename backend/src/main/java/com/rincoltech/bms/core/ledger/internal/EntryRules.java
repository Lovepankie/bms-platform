package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.kernel.ApiException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The pure balance rules of ADR-004, checked before anything touches the database. */
final class EntryRules {

    private EntryRules() {}

    static void check(List<Line> lines) {
        if (lines.size() < 2) {
            throw ApiException.rule("invalid_journal_line", "A journal entry needs at least two lines.");
        }
        Map<String, long[]> totals = new TreeMap<>();
        for (Line line : lines) {
            if (line.debit() < 0 || line.credit() < 0 || (line.debit() > 0) == (line.credit() > 0)) {
                throw ApiException.rule(
                        "invalid_journal_line", "Each line carries exactly one positive debit or one positive credit.");
            }
            long[] t = totals.computeIfAbsent(line.currency(), c -> new long[2]);
            t[0] = Math.addExact(t[0], line.debit());
            t[1] = Math.addExact(t[1], line.credit());
        }
        totals.forEach((currency, t) -> {
            if (t[0] != t[1]) {
                throw ApiException.rule(
                        "unbalanced_entry",
                        "Debits (" + t[0] + ") and credits (" + t[1] + ") differ in " + currency + ".");
            }
        });
    }
}
