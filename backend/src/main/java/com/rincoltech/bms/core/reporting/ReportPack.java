package com.rincoltech.bms.core.reporting;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A bundle of reports run as one (ADR-038 decision 4, issue #229): every part is queried in the run's one snapshot
 * transaction, and the run makes one combined PDF (a cover with the parameters, the contents and a sign off block, then each
 * part) and one workbook (a Parameters sheet, then the sheets of each part). A pack is offered in the single format
 * {@code pack}.
 *
 * <p>The framework does the rest from the parts' own definitions: a requester must hold {@link #permission()} and the
 * permission of every part, or the pack is refused (the parts can still be run singly); each part's
 * {@link ReportDefinition#prepare} runs in the request with the parameters {@link #partParams} gives it, so each freezes its
 * own scope (branches, officers, which names and phones it may print) exactly as when it is run alone; and a download is
 * refused when any part's {@link ReportDefinition#stillAllowed} says no.
 */
public interface ReportPack extends ReportDefinition {

    /** The format of every pack run: a PDF and a workbook. */
    String FORMAT = "pack";

    /** The keys of the reports in the pack, in the order they are printed; none is itself a pack. */
    List<String> parts();

    /**
     * The request a part is prepared with, from the pack's normalised parameters (for example the month's first day and its
     * last as {@code from} and {@code to}). Only parameters the part declares.
     */
    Map<String, Object> partParams(String partKey, Prepared pack);

    @Override
    default Set<String> formats() {
        return Set.of(FORMAT);
    }

    /** A pack has no rows of its own: its parts are queried by the framework. */
    @Override
    default void query(Prepared prepared, ReportOutput out) {
        throw new IllegalStateException("A pack is run through its parts.");
    }
}
