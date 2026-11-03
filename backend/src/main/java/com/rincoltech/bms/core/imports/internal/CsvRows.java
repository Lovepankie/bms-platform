package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.internal.Parsing.Cell;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads CSV (RFC 4180: comma separated, double quotes, a doubled quote inside quotes, CR LF or LF) one record at a time from
 * a reader, so only the current record is ever held. A leading byte order mark is dropped. Every cell is text, exactly as
 * written; the record number is the row number. A quote never closed, a cell over {@value Parsing#MAX_CELL_CHARS} characters
 * or a record of more than {@link #MAX_FIELDS} cells is {@code FILE_UNREADABLE} or {@code TOO_MANY_COLUMNS}.
 */
final class CsvRows {

    /** Cells of one record read at all (Excel's column count); past the column limit only empty ones may follow. */
    static final int MAX_FIELDS = 16_384;

    private CsvRows() {}

    static void read(Reader in, Parsing.Sheet sheet, Parsing.Limits limits) throws IOException {
        List<Cell> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        boolean any = false;
        int rowNo = 1;
        int quoteRow = 0;
        int c = in.read();
        if (c == '﻿') {
            c = in.read();
        }
        while (c != -1) {
            any = true;
            if (quoted) {
                if (c == '"') {
                    int next = in.read();
                    if (next == '"') {
                        append(field, '"', rowNo);
                    } else {
                        quoted = false;
                        c = next;
                        continue;
                    }
                } else {
                    append(field, (char) c, rowNo);
                }
            } else if (c == '"' && field.isEmpty() && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
                quoteRow = rowNo;
            } else if (c == ',') {
                end(record, field, wasQuoted, limits);
                wasQuoted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r') {
                    int next = in.read();
                    if (next != '\n') {
                        end(record, field, wasQuoted, limits);
                        sheet.row(rowNo++, record);
                        record = new ArrayList<>();
                        wasQuoted = false;
                        any = false;
                        c = next;
                        continue;
                    }
                }
                end(record, field, wasQuoted, limits);
                sheet.row(rowNo++, record);
                record = new ArrayList<>();
                wasQuoted = false;
                any = false;
            } else {
                append(field, (char) c, rowNo);
            }
            c = in.read();
        }
        if (quoted) {
            throw Parsing.unreadable("A quoted cell opened on row " + quoteRow + " is never closed.");
        }
        if (any) {
            end(record, field, wasQuoted, limits);
            sheet.row(rowNo, record);
        }
    }

    private static void append(StringBuilder field, char c, int rowNo) {
        if (c == 0) {
            // PostgreSQL cannot store a NUL in text or jsonb; a spreadsheet never writes one.
            throw Parsing.unreadable("A cell on row " + rowNo + " holds a NUL character.");
        }
        if (field.length() >= Parsing.MAX_CELL_CHARS) {
            throw Parsing.unreadable(
                    "A cell on row " + rowNo + " is longer than " + Parsing.MAX_CELL_CHARS + " characters.");
        }
        field.append(c);
    }

    private static void end(List<Cell> record, StringBuilder field, boolean quoted, Parsing.Limits limits) {
        if (record.size() >= MAX_FIELDS) {
            throw Parsing.tooManyColumns(limits);
        }
        // An empty cell, quoted or not, is empty: no value is kept for it.
        record.add(field.isEmpty() ? null : Cell.text(field.toString()));
        field.setLength(0);
    }
}
