package com.rincoltech.bms.core.reporting;

import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Where a {@link DataExportSection} writes its sheets (ADR-074). Money is integer minor units (whole shillings for UGX)
 * in a {@code MONEY} column, a business date a {@code DATE} cell, and a moment is written as the business's date and time
 * in its own time zone ({@link #date}, {@link #time}), never in UTC. A table longer than the split size is cut into more
 * sheets: by year for a table of dated rows, then into numbered parts, so no sheet passes the row cap of a run.
 *
 * <p>Personal data. The weekly copy that goes out by email leaves out the columns that identify or reach a person (phone
 * numbers, ID numbers, date of birth, village, contact details), ADR-074 decision 6: a section names them in {@code
 * personal} and the sheets drop them, header and cells, from that copy. The copy downloaded after signing in keeps them.
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
    default Rows table(String key, String title, List<Column> columns) {
        return table(key, title, columns, Set.of());
    }

    /** As {@link #table(String, String, List)}, with the keys of the columns that hold personal data. */
    Rows table(String key, String title, List<Column> columns, Set<String> personal);

    /**
     * A table of dated rows written in date order (sales, repayments). When it holds more than the split size, each year
     * gets its own sheet ({@code Sales 2025}, {@code Sales 2026}); otherwise it is one sheet.
     *
     * @param rowCount how many rows will be written, counted in the same snapshot
     */
    default DatedRows dated(String key, String title, List<Column> columns, long rowCount) {
        return dated(key, title, columns, rowCount, Set.of());
    }

    /** As {@link #dated(String, String, List, long)}, with the keys of the columns that hold personal data. */
    DatedRows dated(String key, String title, List<Column> columns, long rowCount, Set<String> personal);

    interface Rows {
        /** One row, a cell per column, typed as {@link ReportOutput.Table#row} says. */
        void row(Object... cells);
    }

    interface DatedRows {
        /** One row on its date, which decides the year sheet; rows must come in date order. */
        void row(LocalDate date, Object... cells);
    }
}
