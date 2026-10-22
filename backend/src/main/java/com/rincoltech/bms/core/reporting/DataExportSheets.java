package com.rincoltech.bms.core.reporting;

import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Where a {@link DataExportSection} writes its sheets (ADR-074). Money is integer minor units (whole shillings for UGX)
 * in a {@code MONEY} column, a business date a {@code DATE} cell, and a moment is written as the business's date and time
 * in its own time zone ({@link #date}, {@link #time}), never in UTC. A table longer than the split size is cut into more
 * sheets: by year for a table of dated rows, then into numbered parts, so no sheet passes the row cap of a run.
 *
 * <p>Tables are written one after another, as on a {@link ReportOutput}: open a table, write all of its rows, then open the
 * next. Opening a table ends the one before it.
 */
public interface DataExportSheets {

    /** The business's time zone: every date and time of the workbook is in it. */
    ZoneId zone();

    /** The business date of a moment, or null. */
    LocalDate date(Timestamp at);

    /** The time of day of a moment, {@code HH:mm} in the business's time zone, or null. */
    String time(Timestamp at);

    /** Throws when the run was cancelled or is over time; call it between long reads. */
    void checkpoint();

    /**
     * A table whose rows have no date to split on (products, customers): one sheet, cut into numbered parts only past the
     * split size.
     */
    Rows table(String key, String title, List<Column> columns);

    /**
     * A table of dated rows written in date order (sales, repayments). When it holds more than the split size, each year
     * gets its own sheet ({@code Sales 2025}, {@code Sales 2026}); otherwise it is one sheet.
     *
     * @param rowCount how many rows will be written, counted in the same snapshot
     */
    DatedRows dated(String key, String title, List<Column> columns, long rowCount);

    interface Rows {
        /** One row, a cell per column, typed as {@link ReportOutput.Table#row} says. */
        void row(Object... cells);
    }

    interface DatedRows {
        /** One row on its date, which decides the year sheet; rows must come in date order. */
        void row(LocalDate date, Object... cells);
    }
}
