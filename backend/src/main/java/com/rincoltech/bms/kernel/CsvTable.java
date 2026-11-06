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
    private static final Pattern GROUPED = Pattern.compile("[1-9]\\d{0,2}(,\\d{3})+(\\.\\d+)?");

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

    /** The words an import adds when it refuses an amount for its comma (issue #870). */
    public static final String COMMA_WORDS =
            " Use a point for decimals; a comma only goes between thousands, for example 12,500.";

    /** True when a comma in the amount is not between groups of three digits, as in 1,5 or 12,50: refused, never read. */
    public static boolean commaDecimal(String text) {
        String strip = text.strip();
        return strip.indexOf(',') >= 0 && !GROUPED.matcher(strip).matches();
    }

    /**
     * A non-negative amount in major units, with or without thousands commas, as integer minor units for a currency with
     * {@code exponent} decimals (the same rule as the screens' parseMinor); a fraction is accepted only up to the
     * exponent. Null when it is anything else. A comma is taken only between groups of three digits: 12,500 is read, and 1,5
     * or 12,50, a comma used as a decimal point, is refused, never read as 15 or 1250 (issue #870, the rule of #867).
     */
    public static Long amountMinor(String text, int exponent) {
        String strip = text.strip();
        if (commaDecimal(strip)) {
            return null;
        }
        String clean = strip.replace(",", "");
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
