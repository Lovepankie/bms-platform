package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the merchant statement a person uploaded (spec 3.9, FR-PAY-11). The provider offers no settlement interface, so the
 * file is the operator's export from the provider's dashboard; its exact columns are not documented to us (spec 3.12.2), so the
 * reader asks for few and accepts the usual names for them. Nothing is guessed: a value it cannot read is a problem with its
 * physical line number in the file, and any problem refuses the whole file, so a statement is never half read.
 *
 * <p>Needs a header row (comma separated, quotes as in RFC 4180, UTF-8) with a column for the order tracking id or the
 * confirmation code, the amount and the date; the fee, the settled amount, the currency and the payment method are read when
 * present, and every other column (a payer's account or number included) is ignored and never stored. Two columns that mean the
 * same thing, a line with another number of cells than the header, and a quote left open at the end of the file all refuse the
 * file: a stray quote must not swallow the lines after it. Amounts are the decimal the provider shows ({@code 5200} or
 * {@code 5,200.00}); a fraction a currency cannot hold is refused, never rounded. A date is ISO ({@code 2026-10-19}, a time after
 * it is ignored) or day first with slashes ({@code 19/10/2026}) when the first number can only be a day; {@code 10/11/2026} is
 * refused as ambiguous. A date after the day of the upload is refused. A code or method that starts with {@code = + - @} is
 * refused, so the file can never carry a spreadsheet formula into a screen or an export.
 */
final class StatementCsv {

    static final int MAX_BYTES = 1_000_000;
    static final int MAX_ROWS = 5000;
    private static final int MAX_PROBLEMS = 20;
    private static final Pattern TRACKING = Pattern.compile("^[0-9a-fA-F-]{36}$");
    private static final Pattern CODE = Pattern.compile("^[\\x21-\\x7E]{1,100}$");
    private static final Pattern METHOD = Pattern.compile("^[\\x20-\\x7E]{1,40}$");
    private static final Pattern THOUSANDS = Pattern.compile("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$");
    private static final Pattern FORMULA = Pattern.compile("^[=+\\-@\\t\\r].*");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("order_tracking_id", "tracking"),
            Map.entry("tracking_id", "tracking"),
            Map.entry("ordertrackingid", "tracking"),
            Map.entry("confirmation_code", "code"),
            Map.entry("payment_code", "code"),
            Map.entry("confirmation", "code"),
            Map.entry("amount", "amount"),
            Map.entry("gross", "amount"),
            Map.entry("gross_amount", "amount"),
            Map.entry("fee", "fee"),
            Map.entry("fees", "fee"),
            Map.entry("charge", "fee"),
            Map.entry("charges", "fee"),
            Map.entry("commission", "fee"),
            Map.entry("settled", "settled"),
            Map.entry("settled_amount", "settled"),
            Map.entry("net", "settled"),
            Map.entry("net_amount", "settled"),
            Map.entry("currency", "currency"),
            Map.entry("date", "date"),
            Map.entry("paid_on", "date"),
            Map.entry("payment_date", "date"),
            Map.entry("paid_at", "date"),
            Map.entry("payment_method", "method"),
            Map.entry("method", "method"));

    /**
     * One statement line, read and validated. {@code rowNo} is the line's position among the data rows, from 1; {@code line} the
     * physical line of the file it starts on, which is what a problem names.
     */
    record Row(
            int rowNo,
            int line,
            String orderTrackingId,
            String confirmationCode,
            long amountMinor,
            long feeMinor,
            Long settledMinor,
            String currency,
            LocalDate paidOn,
            String paymentMethod) {}

    /** A record of the file and the physical line it starts on (a quoted cell may hold line breaks). */
    record Rec(int line, List<String> cells) {}

    private StatementCsv() {}

    /**
     * @param defaultCurrency the currency of a line that names none (the tenant's own)
     * @param today the day of the upload in the business's time zone: a later date is refused
     */
    static List<Row> parse(byte[] bytes, String defaultCurrency, LocalDate today) {
        if (bytes == null || bytes.length == 0) {
            throw invalid(0, "file", "The file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw invalid(0, "file", "The file is larger than 1 MB.");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalid(0, "file", "The file is not UTF-8 text.");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<Rec> lines = split(text);
        if (lines.isEmpty()) {
            throw invalid(0, "file", "The file has no header row.");
        }
        Rec header = lines.getFirst();
        Map<String, Integer> columns = new HashMap<>();
        List<FieldProblem> problems = new ArrayList<>();
        for (int i = 0; i < header.cells().size(); i++) {
            String key = header.cells().get(i).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
            String field = ALIASES.get(key);
            if (field != null && columns.putIfAbsent(field, i) != null) {
                problems.add(new FieldProblem(
                        "line " + header.line() + " header",
                        "duplicate_column",
                        "Two columns both mean " + field + "; keep one."));
            }
        }
        if (!columns.containsKey("amount")) {
            problems.add(new FieldProblem("header", "missing_column", "The header needs an amount column."));
        }
        if (!columns.containsKey("date")) {
            problems.add(new FieldProblem("header", "missing_column", "The header needs a date column."));
        }
        if (!columns.containsKey("tracking") && !columns.containsKey("code")) {
            problems.add(new FieldProblem(
                    "header",
                    "missing_column",
                    "The header needs an order tracking id or a confirmation code column."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        int dataRows = lines.size() - 1;
        if (dataRows < 1) {
            throw invalid(0, "file", "The file has a header and no lines.");
        }
        if (dataRows > MAX_ROWS) {
            throw invalid(0, "file", "The file has more than " + MAX_ROWS + " lines; upload it in parts.");
        }
        List<Row> rows = new ArrayList<>();
        Set<String> seenTracking = new HashSet<>();
        Set<String> seenCodeOnly = new HashSet<>();
        for (int n = 1; n < lines.size(); n++) {
            Rec rec = lines.get(n);
            List<String> cells = rec.cells();
            int line = rec.line();
            int before = problems.size();
            if (cells.size() != header.cells().size()) {
                problems.add(problem(
                        line,
                        "cells",
                        "The line has " + cells.size() + " cells and the header has "
                                + header.cells().size() + "."));
                if (problems.size() >= MAX_PROBLEMS) {
                    break;
                }
                continue;
            }
            String tracking = blankToNull(cell(cells, columns.get("tracking")));
            String code = blankToNull(cell(cells, columns.get("code")));
            if (tracking != null && !TRACKING.matcher(tracking).matches()) {
                problems.add(problem(line, "order_tracking_id", "Not an order tracking id."));
            }
            if (code != null
                    && (!CODE.matcher(code).matches() || FORMULA.matcher(code).matches())) {
                problems.add(problem(
                        line,
                        "confirmation_code",
                        "Not a confirmation code (up to 100 characters, no spaces, not starting with = + - or @)."));
            }
            if (tracking == null && code == null) {
                problems.add(problem(
                        line,
                        "order_tracking_id",
                        "The line has neither an order tracking id nor a confirmation code."));
            }
            if (tracking != null && !seenTracking.add(tracking.toLowerCase(Locale.ROOT))) {
                problems.add(problem(line, "order_tracking_id", "The same order appears twice in the file."));
            }
            if (tracking == null && code != null && !seenCodeOnly.add(code.toUpperCase(Locale.ROOT))) {
                problems.add(
                        problem(line, "confirmation_code", "The same confirmation code appears twice in the file."));
            }
            String currency = blankToNull(cell(cells, columns.get("currency")));
            currency = currency == null ? defaultCurrency : currency.toUpperCase(Locale.ROOT);
            if (!MinorUnits.supported(currency)) {
                problems.add(problem(line, "currency", "Only Uganda shillings (UGX) are read."));
                currency = defaultCurrency;
            }
            Long amount = money(cells, columns.get("amount"), currency, line, "amount", false, problems);
            Long fee = money(cells, columns.get("fee"), currency, line, "fee", true, problems);
            Long settled = money(cells, columns.get("settled"), currency, line, "settled", true, problems);
            LocalDate paidOn = date(cell(cells, columns.get("date")), line, today, problems);
            String method = blankToNull(cell(cells, columns.get("method")));
            if (method != null
                    && (!METHOD.matcher(method).matches()
                            || FORMULA.matcher(method).matches())) {
                problems.add(problem(line, "payment_method", "Not a payment method name."));
            }
            if (amount != null && fee != null && fee > amount) {
                problems.add(problem(line, "fee", "The fee is more than the amount."));
            }
            if (problems.size() == before) {
                rows.add(new Row(
                        n, line, tracking, code, amount, fee == null ? 0 : fee, settled, currency, paidOn, method));
            }
            if (problems.size() >= MAX_PROBLEMS) {
                break;
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(
                    problems.size() > MAX_PROBLEMS ? problems.subList(0, MAX_PROBLEMS) : problems);
        }
        return rows;
    }

    private static Long money(
            List<String> cells,
            Integer column,
            String currency,
            int line,
            String field,
            boolean optional,
            List<FieldProblem> problems) {
        String raw = blankToNull(cell(cells, column));
        if (raw == null) {
            if (!optional) {
                problems.add(problem(line, field, "Missing."));
            }
            return null;
        }
        BigDecimal value;
        String compact = raw.replaceAll("\\s", "");
        // A comma is accepted only as a thousands separator, in groups of three ("1,500,000" or "1,500.50"); "1,50" is
        // a
        // decimal comma or a typo and is refused, never read as 150.
        if (compact.indexOf(',') >= 0 && !THOUSANDS.matcher(compact).matches()) {
            problems.add(problem(line, field, "Not an amount: use commas only between groups of three digits."));
            return null;
        }
        try {
            value = new BigDecimal(compact.replace(",", ""));
        } catch (NumberFormatException e) {
            problems.add(problem(line, field, "Not an amount."));
            return null;
        }
        if (optional && value.signum() == 0) {
            return 0L;
        }
        return MinorUnits.fromDecimal(value, currency).orElseGet(() -> {
            problems.add(problem(line, field, "Not a whole, positive amount in " + currency + "."));
            return null;
        });
    }

    private static final DateTimeFormatter DAY_FIRST = DateTimeFormatter.ofPattern("d/M/uuuu");

    private static LocalDate date(String raw, int line, LocalDate today, List<FieldProblem> problems) {
        String s = blankToNull(raw);
        if (s == null) {
            problems.add(problem(line, "date", "Missing."));
            return null;
        }
        LocalDate day;
        try {
            if (s.length() >= 10 && s.charAt(4) == '-') {
                day = LocalDate.parse(s.substring(0, 10));
            } else {
                int slash = s.indexOf('/');
                int first = slash > 0 ? Integer.parseInt(s.substring(0, slash).trim()) : 0;
                if (first <= 12) {
                    problems.add(problem(
                            line,
                            "date",
                            "Ambiguous date: write it as 2026-10-19 (or day first, such as 19/10/2026)."));
                    return null;
                }
                day = LocalDate.parse(s, DAY_FIRST);
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            problems.add(problem(line, "date", "Use 2026-10-19 or 19/10/2026."));
            return null;
        }
        if (day.isAfter(today)) {
            problems.add(problem(line, "date", "The date is in the future."));
            return null;
        }
        return day;
    }

    private static String cell(List<String> cells, Integer column) {
        return column == null || column >= cells.size() ? null : cells.get(column);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static FieldProblem problem(int line, String field, String message) {
        return new FieldProblem("line " + line + " " + field, "invalid", message);
    }

    private static ApiException invalid(int line, String field, String message) {
        return ApiException.validation(
                List.of(new FieldProblem(line == 0 ? field : "line " + line, "invalid", message)));
    }

    /**
     * RFC 4180: comma separated, a quoted cell may hold commas, line breaks and a doubled quote; blank lines are skipped. Each record
     * carries the physical line it starts on. A quote still open at the end of the file refuses the file, naming the line it opened
     * on, so a stray quote can never silently swallow the lines after it.
     */
    static List<Rec> split(String text) {
        List<Rec> lines = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int physical = 1;
        int recordLine = 1;
        int quoteLine = 0;
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
                    if (c == '\n') {
                        physical++;
                    }
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                quoteLine = physical;
                if (!any && cell.length() == 0) {
                    recordLine = physical;
                }
                any = true;
            } else if (c == ',') {
                if (!any) {
                    recordLine = physical;
                }
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || cell.length() > 0) {
                    row.add(cell.toString());
                    lines.add(new Rec(recordLine, row));
                }
                row = new ArrayList<>();
                cell.setLength(0);
                any = false;
                physical++;
            } else {
                if (!any && cell.length() == 0) {
                    recordLine = physical;
                }
                cell.append(c);
                any = true;
            }
        }
        if (quoted) {
            throw invalid(
                    quoteLine, "quote", "A quote opened here is never closed, so the lines after it cannot be read.");
        }
        if (any || cell.length() > 0) {
            row.add(cell.toString());
            lines.add(new Rec(recordLine, row));
        }
        return lines;
    }
}
