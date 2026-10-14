package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.internal.Renderers.Header;
import com.rincoltech.bms.core.reporting.internal.Spool.Guard;
import com.rincoltech.bms.core.reporting.internal.Spool.TableMeta;
import com.rincoltech.bms.kernel.ApiException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The PDF of a report (ADR-038 decision 5, ADR-034): the model for the shared {@code report} template, laid out by the
 * document renderer. Everything on the page is text formatted here from the spool, so the PDF, the workbook and the page
 * of the insights cannot disagree: money in whole currency units with the currency in the heading, rates as percentages to
 * one decimal from the integer basis points, dates as {@code 8 Oct 2026}, a missing figure as a dash.
 *
 * <p>Memory is bounded by the row cap and not by the book: a table longer than the cap is cut to its first rows and its
 * total row, with a printed pointer to the Excel file (the spool is scanned, and only the printed rows are parsed). The
 * document is laid out landscape when any table has more than seven columns, and the table header repeats on every page.
 */
final class PdfReport {

    static final String TEMPLATE = "report";

    /** Columns beyond this many turn the page to landscape. */
    static final int PORTRAIT_COLUMNS = 7;

    private static final String DASH = "-";

    private PdfReport() {}

    /**
     * Renders the PDF of a run to {@code target}. A render place is awaited until the run's own deadline or cancellation
     * (the guard), because a report is a job and a short wait that suits a request would fail it for no reason.
     */
    static void write(
            Documents documents,
            ReportsProperties limits,
            Header h,
            Presentation p,
            String number,
            List<TableMeta> tables,
            Spool spool,
            Path target,
            Guard guard) {
        while (true) {
            guard.checkpoint();
            try {
                // The model (the printed rows of every table) is built once a render place is held.
                documents.renderPdf(
                        TEMPLATE, () -> model(limits.pdfRowCap, h, p, number, tables, spool, guard), target, 5_000);
                return;
            } catch (ApiException e) {
                if (!e.code().equals("documents_busy")) {
                    throw e;
                }
            }
        }
    }

    static Map<String, Object> model(
            int rowCap, Header h, Presentation p, String number, List<TableMeta> tables, Spool spool, Guard guard) {
        int exponent = ReportFormats.exponent(h.currency());
        Map<String, Object> lh = new LinkedHashMap<>();
        lh.put("name", p.businessName());
        lh.put("colour", p.brand());
        lh.put("onColour", p.onBrand());
        lh.put("tint", p.tint());
        lh.put("footer", p.footer());
        lh.put("logo", p.logo());

        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("name", p.scope());
        branch.put("location", null);

        boolean landscape = tables.stream().anyMatch(t -> t.columns().size() > PORTRAIT_COLUMNS);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", h.title().toUpperCase(Locale.ENGLISH));
        doc.put("number", number);
        doc.put("date", ReportFormats.date(h.generatedAt().atZone(p.zone()).toLocalDate()));
        if (landscape) {
            doc.put("landscape", "yes");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("asAt", ReportFormats.date(h.asOf()));
        report.put("currency", h.currency());
        report.put(
                "parameters",
                p.parameters().stream()
                        .map(l -> Map.<String, Object>of("label", l.label(), "value", l.value()))
                        .toList());
        report.put("madeLine", ReportFormats.dateTime(h.generatedAt(), p.zone()) + " (" + p.zone() + ") by " + p.by());
        report.put("readLine", ReportFormats.dateTime(h.snapshotAt(), p.zone()) + " (" + p.zone() + ")");
        report.put("notes", h.notes());

        List<Map<String, Object>> out = new ArrayList<>();
        for (TableMeta t : tables) {
            out.add(table(t, rowCap, h.currency(), exponent, spool, guard));
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("lh", lh);
        model.put("branch", branch);
        model.put("doc", doc);
        model.put("report", report);
        model.put("tables", out);
        return model;
    }

    private static Map<String, Object> table(
            TableMeta t, int rowCap, String currency, int exponent, Spool spool, Guard guard) {
        List<Column> columns = t.columns();
        double weights = 0;
        for (Column c : columns) {
            weights += weight(c);
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            // A long unbroken word wraps inside its cell, rows are close set (a report is read by the page), and a text
            // column that follows a figure is set off from it.
            String gap = c.kind() == Kind.TEXT && i > 0 ? " padding-left: 6mm;" : "";
            m.put("style", "word-wrap: break-word; padding-top: 0.9mm; padding-bottom: 0.9mm;" + gap);
            // A heading wraps inside its column instead of running into the next (a figure's cell never wraps).
            m.put("hstyle", "white-space: normal; word-wrap: break-word;" + gap);
            m.put("label", XlsxWriter.header(c, currency));
            m.put("num", c.kind() != Kind.TEXT);
            m.put("width", Math.round(weight(c) / weights * 1000) / 10.0);
            cols.add(m);
        }
        long dataRows = t.rows() - (t.hasTotal() ? 1 : 0);
        boolean cut = dataRows > rowCap;
        List<List<String>> rows = new ArrayList<>();
        List<String>[] total = new List[1];
        long head = cut ? rowCap : dataRows;
        long[] seen = {0};
        spool.forEachRowCut(t, guard, head, t.hasTotal(), row -> {
            seen[0]++;
            List<String> cells = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                cells.add(text(columns.get(i), row.get(i), exponent));
            }
            if (t.hasTotal() && seen[0] > head) {
                total[0] = cells;
            } else {
                rows.add(cells);
            }
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", t.title());
        m.put("columns", cols);
        m.put("rows", rows);
        m.put("total", total[0]);
        m.put(
                "cut",
                cut
                        ? "Showing the first " + ReportFormats.count(rowCap) + " of " + ReportFormats.count(dataRows)
                                + " rows. The full detail is in the Excel file."
                        : null);
        m.put("empty", t.rows() == 0);
        return m;
    }

    /**
     * Text columns take more of the width than numbers, and a figure's column is never narrower than its heading's longest
     * word needs (a word is never split mid-word in a heading).
     */
    private static double weight(Column c) {
        if (c.kind() == Kind.TEXT) {
            return 3.0;
        }
        int longest = 0;
        for (String word : c.label().split("\\s+")) {
            longest = Math.max(longest, word.length());
        }
        return Math.max(c.kind() == Kind.MONEY ? 1.9 : 1.4, longest * 0.24);
    }

    private static String text(Column c, Object v, int exponent) {
        if (v == null) {
            return c.kind() == Kind.TEXT ? "" : DASH;
        }
        return switch (c.kind()) {
            case TEXT -> String.valueOf(v);
            case COUNT -> ReportFormats.count(((Number) v).longValue());
            case MONEY -> ReportFormats.money(((Number) v).longValue(), exponent);
            case BASIS_POINTS -> ReportFormats.percent(((Number) v).longValue());
            case DATE -> ReportFormats.date(java.time.LocalDate.parse(String.valueOf(v)));
        };
    }
}
