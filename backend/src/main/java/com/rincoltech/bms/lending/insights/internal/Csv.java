package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.lending.insights.internal.InsightsApi.Column;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.TableResponse;
import java.util.List;

/**
 * RFC 4180 CSV of an insights table. A cell that a spreadsheet would read as a formula is
 * prefixed with an apostrophe; a name column is reduced to initials when the caller may not read
 * members (chapter 8 section 8.3.2). Money stays in integer minor units, with the currency in the
 * header, so a spreadsheet sums it exactly.
 */
final class Csv {

    private Csv() {}

    static String render(TableResponse t, boolean names) {
        StringBuilder out = new StringBuilder();
        List<Column> columns = t.columns();
        // Issue #863: the record ids are for the screen's links only; the export leaves them out.
        List<Integer> shown = java.util.stream.IntStream.range(0, columns.size())
                .filter(i -> !columns.get(i).kind().equals(InsightsTables.ID))
                .boxed()
                .toList();
        for (int i : shown) {
            Column c = columns.get(i);
            String header = c.kind().equals("money") && t.currency() != null
                    ? c.label() + " (" + t.currency() + " minor units)"
                    : c.label();
            out.append(i == shown.getFirst() ? "" : ",").append(cell(header));
        }
        out.append("\r\n");
        for (List<String> row : t.rows()) {
            for (int i : shown) {
                String v = row.get(i);
                if (!names
                        && InsightsTables.NAME_COLUMNS.contains(columns.get(i).key())) {
                    v = initials(v);
                }
                out.append(i == shown.getFirst() ? "" : ",").append(cell(v));
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    /** {@code Test Borrower 01} becomes {@code T. B. 0.} */
    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            out.append(out.isEmpty() ? "" : " ").append(part.charAt(0)).append('.');
        }
        return out.toString();
    }

    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("^-?\\d+$")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
