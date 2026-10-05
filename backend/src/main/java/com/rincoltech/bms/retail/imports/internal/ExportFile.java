package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One file of the normalised export: UTF-8 JSON Lines, one object per line, blank lines ignored. A
 * line that is not a JSON object is a problem of that line, reported and skipped; a missing file is
 * an empty one. Field readers throw {@link RowProblem} naming the field, so the caller reports the
 * row and skips it instead of guessing (chapter 13 section 13.2).
 */
final class ExportFile {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    final String name;
    final boolean present;
    final List<Row> rows = new ArrayList<>();
    final List<RowProblem> unreadable = new ArrayList<>();
    final Set<String> unknownFields = new TreeSet<>();

    private ExportFile(String name, boolean present) {
        this.name = name;
        this.present = present;
    }

    static ExportFile read(Path dir, String name, Set<String> fields) {
        Path path = dir.resolve(name);
        if (!Files.isRegularFile(path)) {
            return new ExportFile(name, false);
        }
        ExportFile file = new ExportFile(name, true);
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String text;
            int line = 0;
            while ((text = in.readLine()) != null) {
                line++;
                if (line == 1 && text.startsWith("﻿")) {
                    text = text.substring(1);
                }
                if (text.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = JSON.readTree(text);
                } catch (JacksonException e) {
                    file.unreadable.add(new RowProblem(line, "not valid JSON"));
                    continue;
                }
                if (node == null || !node.isObject()) {
                    file.unreadable.add(new RowProblem(line, "not a JSON object"));
                    continue;
                }
                for (String field : node.propertyNames()) {
                    if (!fields.contains(field)) {
                        file.unknownFields.add(field);
                    }
                }
                file.rows.add(new Row(line, node));
            }
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(name + " is not UTF-8 text", e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + name, e);
        }
        return file;
    }

    /** A problem with one line: the row is reported and skipped. */
    static final class RowProblem extends RuntimeException {

        final int line;

        RowProblem(int line, String message) {
            super(message, null, false, false);
            this.line = line;
        }
    }

    /** One line of the file, with typed readers that refuse to guess. */
    record Row(int line, JsonNode node) {

        RowProblem problem(String message) {
            return new RowProblem(line, message);
        }

        /** Trimmed text; null when absent, null or blank. */
        String text(String field) {
            JsonNode v = node.get(field);
            if (v == null || v.isNull()) {
                return null;
            }
            if (!v.isString() && !v.isNumber()) {
                throw problem(field + " must be text");
            }
            String s = v.asString().strip();
            return s.isEmpty() ? null : s;
        }

        String requiredText(String field, int maxLength) {
            String s = text(field);
            if (s == null) {
                throw problem(field + " is missing");
            }
            if (s.length() > maxLength) {
                throw problem(field + " is longer than " + maxLength + " characters");
            }
            return s;
        }

        String optionalText(String field, int maxLength) {
            String s = text(field);
            if (s != null && s.length() > maxLength) {
                throw problem(field + " is longer than " + maxLength + " characters");
            }
            return s;
        }

        /** Integer minor units, zero or more (ADR-004). */
        long money(String field) {
            JsonNode v = node.get(field);
            if (v == null || v.isNull()) {
                throw problem(field + " is missing");
            }
            try {
                BigDecimal d = v.isNumber()
                        ? v.decimalValue()
                        : new BigDecimal(v.asString().strip());
                long minor = d.longValueExact();
                if (minor < 0) {
                    throw problem(field + " is negative");
                }
                if (minor > RetailCatalogue.MAX_AMOUNT_MINOR) {
                    throw problem(field + " is above " + RetailCatalogue.MAX_AMOUNT_MINOR + " minor units");
                }
                return minor;
            } catch (ArithmeticException | NumberFormatException e) {
                throw problem(field + " must be a whole number of minor units");
            }
        }

        Long optionalMoney(String field) {
            JsonNode v = node.get(field);
            return v == null || v.isNull() || (v.isString() && v.asString().isBlank()) ? null : money(field);
        }

        /** A decimal string (or number) with at most three places; blank is zero (data dictionary rule 2). */
        BigDecimal qty(String field) {
            return qty(field, node.get(field));
        }

        BigDecimal qty(String field, JsonNode v) {
            if (v == null || v.isNull() || (v.isString() && v.asString().isBlank())) {
                return BigDecimal.ZERO.setScale(3);
            }
            try {
                BigDecimal d = v.isNumber()
                        ? v.decimalValue()
                        : new BigDecimal(v.asString().strip());
                if (d.stripTrailingZeros().scale() > 3) {
                    throw problem(field + " has more than three decimal places");
                }
                return d.setScale(3);
            } catch (NumberFormatException | ArithmeticException e) {
                throw problem(field + " must be a decimal number");
            }
        }

        boolean flag(String field, boolean absent) {
            JsonNode v = node.get(field);
            if (v == null || v.isNull()) {
                return absent;
            }
            if (v.isBoolean()) {
                return v.asBoolean();
            }
            throw problem(field + " must be true or false");
        }

        /** One of the given words, compared ignoring case. */
        String oneOf(String field, List<String> allowed) {
            String s = text(field);
            if (s == null) {
                throw problem(field + " is missing");
            }
            String lower = s.toLowerCase(java.util.Locale.ROOT);
            if (!allowed.contains(lower)) {
                throw problem(field + " must be one of " + String.join(", ", allowed));
            }
            return lower;
        }

        /**
         * ISO 8601: a date (start of that day), a local date and time, or one with an offset. Local
         * values are in the tenant's zone (Africa/Kampala for the pilot).
         */
        Instant instant(String field, ZoneId zone) {
            String s = text(field);
            if (s == null) {
                throw problem(field + " is missing");
            }
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException ignored) {
                // not an offset date-time; try the local forms
            }
            try {
                return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant();
            } catch (DateTimeParseException ignored) {
                // not a local date-time; try a date
            }
            try {
                return LocalDate.parse(s).atStartOfDay(zone).toInstant();
            } catch (DateTimeParseException e) {
                throw problem(field + " is not an ISO 8601 date or date-time");
            }
        }

        LocalDate optionalDate(String field, ZoneId zone) {
            return text(field) == null ? null : LocalDate.ofInstant(instant(field, zone), zone);
        }

        /** An object of branch code to quantity, in the order given. */
        Map<String, BigDecimal> qtyByBranch(String field) {
            JsonNode v = node.get(field);
            if (v == null || !v.isObject()) {
                throw problem(field + " must be an object of branch code to quantity");
            }
            Map<String, BigDecimal> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : v.properties()) {
                out.put(e.getKey().strip(), qty(field + "." + e.getKey(), e.getValue()));
            }
            return out;
        }
    }
}
