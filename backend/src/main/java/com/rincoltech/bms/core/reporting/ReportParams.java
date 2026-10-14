package com.rincoltech.bms.core.reporting;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Reads the parameters of a report request into typed values, and refuses an invalid one with the field named
 * (422 {@code validation_failed}), so every definition reports a bad parameter the same way.
 */
public final class ReportParams {

    private ReportParams() {}

    /** A date written {@code yyyy-mm-dd}, or {@code fallback} when absent. */
    public static LocalDate date(Map<String, Object> params, String name, LocalDate fallback) {
        Object value = params.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw invalid(name, "Use a date written yyyy-mm-dd.");
        }
    }

    /** A list of ids, sorted and without duplicates, so equal requests are equal; empty when absent. */
    public static List<UUID> uuids(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null) {
            return List.of();
        }
        Collection<?> items = value instanceof Collection<?> c ? c : List.of(value);
        TreeSet<UUID> out = new TreeSet<>();
        for (Object item : items) {
            try {
                out.add(UUID.fromString(String.valueOf(item)));
            } catch (IllegalArgumentException e) {
                throw invalid(name, "Give ids as a list of uuids.");
            }
        }
        return new ArrayList<>(out);
    }

    /** One of the allowed values, or {@code fallback} when absent. */
    public static String choice(Map<String, Object> params, String name, List<String> allowed, String fallback) {
        Object value = params.get(name);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        if (!allowed.contains(text)) {
            throw invalid(name, "Use one of: " + String.join(", ", allowed) + ".");
        }
        return text;
    }

    /** A whole number in a range, or {@code fallback} when absent. */
    public static int integer(Map<String, Object> params, String name, int min, int max, int fallback) {
        Object value = params.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number n)
                || n.doubleValue() != n.longValue()
                || n.longValue() < min
                || n.longValue() > max) {
            throw invalid(name, "Give a whole number from " + min + " to " + max + ".");
        }
        return n.intValue();
    }

    public static ApiException invalid(String field, String message) {
        return ApiException.validation(List.of(new FieldProblem(field, "invalid", message)));
    }
}
