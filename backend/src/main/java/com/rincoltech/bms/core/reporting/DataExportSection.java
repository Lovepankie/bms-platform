package com.rincoltech.bms.core.reporting;

/**
 * A vertical's sheets in the business's own copy of its data, "Download my data" (issue #334, ADR-074). The core never
 * names a vertical (chapter 5 section 5.4.3): a vertical exposes a bean of this type and the workbook carries its sheets
 * when the business has {@link #module()} switched on. The section runs in the run's one read-only snapshot transaction
 * with the tenant bound and no user, exactly as {@link ReportDefinition#query} does: it reads every branch, never the
 * clock, and writes plain tables, a sheet each, with plain column names.
 */
public interface DataExportSection {

    /** The module that must be switched on for the business, for example {@code retail}. */
    String module();

    /** Where the section's sheets go among the others: lower first. */
    int order();

    /** Reads the business's rows and writes them, table by table. */
    void write(DataExportSheets sheets);
}
