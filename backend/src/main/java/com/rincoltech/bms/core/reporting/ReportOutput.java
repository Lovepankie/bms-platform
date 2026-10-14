package com.rincoltech.bms.core.reporting;

import java.util.List;

/**
 * Where a report writes its rows in the query phase (ADR-038 decision 2). Rows go straight to a spool file on
 * disk, one at a time, so a report never holds its result in memory; the renderers (JSON and CSV today, PDF and
 * Excel later) read the spool after the snapshot transaction has committed.
 *
 * <p>The output enforces the run's caps as rows arrive: more rows in one table than the row cap ends the run
 * with {@code report_too_large}, and the deadline and a cancellation are checked as the rows go, so a long
 * query ends on time.
 */
public interface ReportOutput {

    /** Starts a table; its columns are fixed for the table. Tables are written one after another. */
    Table table(String key, String title, List<Column> columns);

    /** A line printed with the parameters on every output, for example where the figures were read from. */
    void note(String text);

    /** Rows written so far, over every table. */
    long rows();

    /**
     * Throws when the run has been cancelled or is over time. A report that reads for a long time before it writes its
     * first row (a total over a whole book) calls it between blocks of its reading, so a stop is not delayed until the
     * first row.
     */
    void checkpoint();

    interface Table {

        /**
         * One row, a cell per column. A {@code TEXT} cell is a {@code String}, a {@code DATE} cell a
         * {@code LocalDate}, the other kinds a {@code Long} or {@code Integer}; any cell may be null.
         */
        void row(Object... cells);

        /**
         * The table's last row, set off as a total (bold and ruled in the PDF and the workbook; an ordinary row in JSON
         * and CSV). Like {@link #row} it counts toward the row cap, but the PDF keeps it when it cuts a long table, so
         * the total is always printed. No row may follow it.
         */
        void total(Object... cells);
    }

    /**
     * @param key the machine name, also the JSON key and the CSV header's stem
     * @param label the heading shown to people
     */
    record Column(String key, String label, Kind kind) {}

    /** What a column holds; the output formats treat each kind the same way everywhere. */
    enum Kind {
        TEXT,
        COUNT,
        /** Integer minor units of the tenant's currency. */
        MONEY,
        /** Basis points, shown as a percentage to one decimal. */
        BASIS_POINTS,
        DATE
    }
}
