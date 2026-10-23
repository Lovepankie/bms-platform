package com.rincoltech.bms.kernel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The CSV reading every check-then-commit import shares (the retail items of #146, the existing loans of #466): a small
 * reader for a byte order mark, quoted cells with doubled quotes, and comma, semicolon or tab separators; and the one
 * rule for an amount typed in major units. No floating point (ADR-004).
 */
public final class CsvTable {

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,15})(?:\\.(\\d+))?");

    private CsvTable() {}

    /** The rows of the text, each a list of its cells as typed (not trimmed). */
    public static List<List<String>> parse(String raw) {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        int eol = text.indexOf('\n');
        String first = eol < 0 ? text : text.substring(0, eol);
        char separator = first.indexOf('\t') >= 0 ? '\t' : count(first, ';') > count(first, ',') ? ';' : ',';
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().isBlank()) {
                quoted = true;
                cell.setLength(0);
            } else if (c == separator) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /**
     * A non-negative amount in major units, with or without thousands commas, as integer minor units for a currency with
     * {@code exponent} decimals (the same rule as the screens' parseMinor); a fraction is accepted only up to the
     * exponent. Null when it is anything else.
     */
    public static Long amountMinor(String text, int exponent) {
        String clean = text.strip().replace(",", "");
        Matcher m = AMOUNT.matcher(clean);
        if (!m.matches()) {
            return null;
        }
        String fraction = m.group(2) == null ? "" : m.group(2);
        if (fraction.length() > exponent) {
            return null;
        }
        return new BigDecimal(m.group(1) + fraction + "0".repeat(exponent - fraction.length())).longValueExact();
    }

    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }
}
