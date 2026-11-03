package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.internal.Parsing.Cell;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads JSON Lines (the retail export format) one line at a time: each line that is not blank is one JSON object, one row.
 * The columns are the objects' keys in the order first seen; the mapping's required headers must be keys of the first
 * object. A string is text, a number a number, true or false a bool, null empty, and an object or array is kept as JSON. A
 * line that is not an object, or is over {@link #MAX_LINE_CHARS} characters, is {@code FILE_UNREADABLE}. The line number is
 * the row number; there is no header row.
 */
final class JsonLinesRows {

    static final int MAX_LINE_CHARS = 1024 * 1024;

    private JsonLinesRows() {}

    /** @return the columns seen */
    static int read(Reader in, ObjectMapper mapper, Parsing.Limits limits, Parsing.Sink sink) throws IOException {
        List<String> columns = new ArrayList<>();
        Map<String, String> byKey = new HashMap<>();
        StringBuilder line = new StringBuilder();
        int lineNo = 0;
        int lastRowNo = 0;
        int rows = 0;
        boolean first = true;
        boolean more = true;
        while (more) {
            line.setLength(0);
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                if (line.length() >= MAX_LINE_CHARS) {
                    throw Parsing.unreadable(
                            "Line " + (lineNo + 1) + " is longer than " + MAX_LINE_CHARS + " characters.");
                }
                line.append((char) c);
            }
            more = c != -1;
            if (!more && line.isEmpty()) {
                break;
            }
            lineNo++;
            if (lineNo == 1 && !line.isEmpty() && line.charAt(0) == '﻿') {
                line.deleteCharAt(0);
            }
            if (line.toString().isBlank()) {
                continue;
            }
            JsonNode node;
            try {
                node = mapper.readTree(line.toString());
            } catch (JacksonException e) {
                throw Parsing.unreadable("Line " + lineNo + " is not valid JSON.");
            }
            if (node == null || !node.isObject()) {
                throw Parsing.unreadable("Line " + lineNo + " is not a JSON object.");
            }
            if (first) {
                Set<String> keys =
                        node.propertyNames().stream().map(Parsing::key).collect(Collectors.toSet());
                if (!keys.containsAll(limits.requiredHeaders())) {
                    throw Parsing.headerNotFound();
                }
                sink.header(0, List.copyOf(node.propertyNames()));
                first = false;
                // Blank lines before the first object are not rows, as rows above a header are not.
                lastRowNo = lineNo - 1;
            }
            int gap = lineNo - lastRowNo - 1;
            if ((long) rows + gap + 1 > limits.maxRows()) {
                throw Parsing.tooManyRows(limits);
            }
            for (int r = lastRowNo + 1; r < lineNo; r++) {
                sink.row(r, Map.of());
            }
            rows += gap + 1;
            lastRowNo = lineNo;
            Map<String, Cell> raw = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String name = e.getKey();
                if (!byKey.containsKey(name)) {
                    if (columns.size() >= limits.maxColumns()) {
                        throw Parsing.tooManyColumns(limits);
                    }
                    columns.add(name);
                    byKey.put(name, name);
                }
                Cell cell = cell(e.getValue(), lineNo);
                if (cell != null && !limits.ignoredColumns().contains(Parsing.key(name))) {
                    raw.put(name, cell);
                }
            }
            sink.row(lineNo, raw);
        }
        if (first) {
            throw Parsing.headerNotFound();
        }
        return columns.size();
    }

    private static Cell cell(JsonNode v, int lineNo) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isString()) {
            String s = v.asString();
            if (s.length() > Parsing.MAX_CELL_CHARS) {
                throw Parsing.unreadable(
                        "A value on line " + lineNo + " is longer than " + Parsing.MAX_CELL_CHARS + " characters.");
            }
            return s.isEmpty() ? null : Cell.text(s);
        }
        if (v.isNumber()) {
            return new Cell(v.decimalValue(), "number");
        }
        if (v.isBoolean()) {
            return new Cell(v.asBoolean(), "bool");
        }
        return new Cell(v, "json");
    }
}
