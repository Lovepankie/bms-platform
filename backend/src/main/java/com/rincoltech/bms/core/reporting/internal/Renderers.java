package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.ReportFailure;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.internal.Spool.Guard;
import com.rincoltech.bms.core.reporting.internal.Spool.TableMeta;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;

/**
 * The render phase for JSON and CSV (ADR-038 decision 5; the PDF and Excel renderers arrive with slices D2 and
 * later and read the same spool). Each renderer streams the spool into one file, a row at a time, and stops the
 * moment the file passes its size cap, so rendering never holds a table in memory and a runaway report ends with
 * {@code report_too_large} rather than a full disk.
 *
 * <p>Every output prints the parameters the run was asked with and the dates it stands at (FR-RPT-02). Money is
 * written as integer minor units with the currency named, as the insights exports do, so a spreadsheet sums it
 * exactly; basis points stay basis points; dates are ISO dates.
 */
final class Renderers {

    /** What every output says about the run. */
    record Header(
            String reportKey,
            String title,
            Map<String, Object> params,
            LocalDate asOf,
            Instant snapshotAt,
            Instant generatedAt,
            String currency,
            List<String> notes) {}

    private Renderers() {}

    // ---- JSON ---------------------------------------------------------------------------------

    static void json(
            ObjectMapper mapper,
            Spool spool,
            Header h,
            List<TableMeta> tables,
            Path target,
            long maxBytes,
            Guard guard) {
        try (OutputStream out = capped(Files.newOutputStream(target), maxBytes);
                JsonGenerator g = mapper.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringProperty("report_key", h.reportKey());
            g.writeStringProperty("title", h.title());
            g.writeStringProperty("as_of", h.asOf().toString());
            g.writeStringProperty("snapshot_at", h.snapshotAt().toString());
            g.writeStringProperty("generated_at", h.generatedAt().toString());
            g.writeStringProperty("currency", h.currency());
            g.writeName("parameters");
            g.writeStartObject();
            for (Map.Entry<String, Object> e : new TreeMap<>(h.params()).entrySet()) {
                g.writeName(e.getKey());
                mapper.writeValue(g, e.getValue());
            }
            g.writeEndObject();
            g.writeName("notes");
            g.writeStartArray();
            for (String note : h.notes()) {
                g.writeString(note);
            }
            g.writeEndArray();
            g.writeName("tables");
            g.writeStartArray();
            for (TableMeta t : tables) {
                g.writeStartObject();
                g.writeStringProperty("key", t.key());
                g.writeStringProperty("title", t.title());
                g.writeName("columns");
                g.writeStartArray();
                for (Column c : t.columns()) {
                    g.writeStartObject();
                    g.writeStringProperty("key", c.key());
                    g.writeStringProperty("label", c.label());
                    g.writeStringProperty("kind", c.kind().name().toLowerCase(java.util.Locale.ROOT));
                    g.writeEndObject();
                }
                g.writeEndArray();
                g.writeNumberProperty("row_count", t.rows());
                g.writeName("rows");
                g.writeStartArray();
                spool.forEachRawRow(t, guard, line -> g.writeRawValue(line));
                g.writeEndArray();
                g.writeEndObject();
            }
            g.writeEndArray();
            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- CSV ----------------------------------------------------------------------------------

    /**
     * RFC 4180 (CRLF line ends) in sections: the report and its parameters, then each table under its title with a
     * header row. A text cell that a spreadsheet would read as a formula is prefixed with an apostrophe (the same
     * rule as the insights exports, FR-RPT-07).
     */
    static void csv(Spool spool, Header h, List<TableMeta> tables, Path target, long maxBytes, Guard guard) {
        try (OutputStream raw = capped(Files.newOutputStream(target), maxBytes)) {
            Lines out = new Lines(raw);
            out.line("Report", h.title());
            out.line("Report key", h.reportKey());
            out.line("As at", h.asOf().toString());
            out.line("Data read at (UTC)", h.snapshotAt().toString());
            out.line("Generated at (UTC)", h.generatedAt().toString());
            out.line("Currency", h.currency());
            for (Map.Entry<String, Object> e : new TreeMap<>(h.params()).entrySet()) {
                out.line("Parameter " + e.getKey(), text(e.getValue()));
            }
            for (String note : h.notes()) {
                out.line("Note", note);
            }
            for (TableMeta t : tables) {
                out.line();
                out.line(t.title());
                List<String> heading = new ArrayList<>();
                for (Column c : t.columns()) {
                    heading.add(
                            switch (c.kind()) {
                                case MONEY -> c.label() + " (" + h.currency() + " minor units)";
                                case BASIS_POINTS -> c.label() + " (basis points)";
                                default -> c.label();
                            });
                }
                out.line(heading.toArray(new String[0]));
                spool.forEachRow(t, guard, row -> {
                    String[] cells = new String[row.size()];
                    for (int i = 0; i < cells.length; i++) {
                        Object v = row.get(i);
                        cells[i] = v == null ? "" : String.valueOf(v);
                    }
                    out.row(cells, t.columns());
                });
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(Object value) {
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(v -> parts.add(String.valueOf(v)));
            return String.join("; ", parts);
        }
        return String.valueOf(value);
    }

    /** RFC 4180 lines onto a stream. */
    private static final class Lines {

        private final OutputStream out;

        Lines(OutputStream out) {
            this.out = out;
        }

        void line(String... cells) {
            write(cells, null);
        }

        void row(String[] cells, List<Column> columns) {
            write(cells, columns);
        }

        private void write(String[] cells, List<Column> columns) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.length; i++) {
                boolean numeric = columns != null && columns.get(i).kind() != Kind.TEXT;
                sb.append(i == 0 ? "" : ",").append(cell(cells[i], numeric));
            }
            sb.append("\r\n");
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void flush() throws IOException {
            out.flush();
        }
    }

    /** One CSV cell; {@code numeric} cells (numbers, dates) are written as they are, never prefixed. */
    static String cell(String value, boolean numeric) {
        String v = value == null ? "" : value;
        if (!numeric && !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // ---- the size cap -------------------------------------------------------------------------

    /** Counts bytes and stops with {@code report_too_large} at the cap. */
    static OutputStream capped(OutputStream inner, long max) {
        return new FilterOutputStream(new BufferedOutputStream(inner, 64 * 1024)) {
            private long written;

            @Override
            public void write(int b) throws IOException {
                count(1);
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                count(len);
                out.write(b, off, len);
            }

            private void count(long n) {
                written += n;
                if (written > max) {
                    throw new ReportFailure("report_too_large", "The report is larger than the size limit.");
                }
            }
        };
    }
}
