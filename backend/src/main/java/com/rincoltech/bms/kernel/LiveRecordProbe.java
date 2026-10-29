package com.rincoltech.bms.kernel;

/**
 * One module's answer to "does this business already trade?" for the trading tenant guard of customer data onboarding
 * (issue #127, ADR-027 decision 6, spec section 5.4): whether the bound business has a live business record in the tables
 * the module owns, meaning one not created by an import (a sale, a loan transaction, a journal). The import commit asks
 * every bean of this type in the caller's transaction and refuses to write into a business that trades unless the run
 * carries the approved override. Lives in the kernel so a vertical can answer without depending on {@code core.imports},
 * as {@link PersonalDataEraser} (chapter 5 section 5.4.3).
 */
public interface LiveRecordProbe {

    /** A short name for the refusal and its audit row, for example {@code lending}. */
    String area();

    /** Whether the bound business has a live record in this module, read in the caller's transaction. */
    boolean hasLiveRecords();
}
