package com.rincoltech.bms.core.imports;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a template's own books hold after a commit (issue #134, spec section 3.9): the system side of the reconciliation that
 * the ledger cannot answer, because the vertical keeps it. The core reads the journals itself, through the ledger's port;
 * a template answers for its stock ledger and its loan book. Every answer is read from those tables inside the caller's
 * read-only transaction, bound to the business, and never from the commit's report or from the staged rows, so the
 * reconciliation does not depend on what the writer said it did.
 *
 * <p>A product and a branch are named by their codes, as the template's forecast names them, never by a personal value.
 */
public interface ImportBooks {

    /**
     * The stock the run's own records put on hand, per branch and product, as the vertical's stock ledger holds those
     * records: the quantity and its value at cost of the stock movements the run registered in {@code import_refs}
     * ({@link Scope#targets}), never the business's whole balance, so stock held before the import or sold after it is not
     * a difference of the import (review of #873). A negative result is answered as it is: the reconciliation lists it
     * separately (spec section 3.9) as a {@code policy} item of its value, which explains the stock value difference only
     * because the writer never puts a negative balance in the opening {@code inventory} journals: they hold the positive
     * balances, the source holds all of them, and the difference is the negative ones. Every side nets each product over
     * the whole run (the Lead on #873): this answer over all the run's movements, the source over all its written rows,
     * and the writer's journal of each batch as the change in what the run's products above zero are worth (a fall is a
     * journal the other way). The writer derives both sides of that change from the database, never from memory: what the
     * run's products are worth from its earlier batches' written rows, and what is already journalled from the run's own
     * opening journals in {@code import_refs}, so a run resumed after a killed process journals what an unbroken one would
     * (the Lead on the review of 5b32682). A writer that journals a negative value leaves that value unexplained, and the
     * sign-off is refused (review of 391e1dc). None by default, for a template that keeps no stock.
     */
    default List<ImportForecast.ProductStock> stock(Scope scope) {
        return List.of();
    }

    /**
     * The run's loans by days-past-due bucket per branch, aged as at the day (the cutover day), as the vertical's loan book
     * holds them: the buckets the template's forecast names. None by default, for a template that makes no loans.
     */
    default List<ImportForecast.ArrearsBucket> arrears(Scope scope, LocalDate asAt) {
        return List.of();
    }

    /**
     * The run asked about: its id, and every record it registered in {@code import_refs} by target type (its opening
     * journals as {@code ledger.journal_entry}).
     */
    record Scope(UUID runId, Map<String, List<UUID>> targets) {

        public Scope {
            targets = Map.copyOf(targets);
        }

        /** The records of one target type the run registered, or none. */
        public List<UUID> of(String targetType) {
            return targets.getOrDefault(targetType, List.of());
        }
    }

    /** Books that hold nothing: the default of a template that keeps neither stock nor loans. */
    ImportBooks NONE = new ImportBooks() {};
}
