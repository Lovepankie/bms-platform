package com.rincoltech.bms.core.reporting.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;

/**
 * How a figure is written for people in the PDF and the workbook (chapter 14 section 14.1): money in whole currency units
 * with thousands separators, rates as percentages to one decimal rounded half up from the integer basis points, dates as
 * {@code 8 Oct 2026}. Every conversion is done on integers or {@link BigDecimal}, never on a floating point number
 * (ADR-004), so the PDF, the workbook and the page cannot disagree by a rounding.
 */
final class ReportFormats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private ReportFormats() {}

    /** Decimal places of the currency's minor unit: UGX has none, most others have two. */
    static int exponent(String currency) {
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            return digits < 0 ? 2 : digits;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    /** {@code 1,234,567} for UGX and {@code 1,234.50} for a currency with a minor unit; a minus sign for a loss. */
    static String money(long minor, int exponent) {
        if (exponent == 0) {
            return String.format(Locale.ENGLISH, "%,d", minor);
        }
        return String.format(Locale.ENGLISH, "%,." + exponent + "f", BigDecimal.valueOf(minor, exponent));
    }

    /** The amount as the plain decimal a spreadsheet stores: {@code 1234.5}, never in exponent form. */
    static String plainMoney(long minor, int exponent) {
        return BigDecimal.valueOf(minor, exponent).stripTrailingZeros().toPlainString();
    }

    /** Basis points as a fraction a spreadsheet formats as a percentage: 1389 is {@code 0.1389}. */
    static String plainRate(long basisPoints) {
        return BigDecimal.valueOf(basisPoints, 4).stripTrailingZeros().toPlainString();
    }

    /** Basis points as a percentage to one decimal, half up: 1389 is {@code 13.9%}, 1385 is {@code 13.9%}. */
    static String percent(long basisPoints) {
        long abs = Math.abs(basisPoints);
        long tenths = (abs + 5) / 10;
        return (basisPoints < 0 && tenths != 0 ? "-" : "") + tenths / 10 + "." + tenths % 10 + "%";
    }

    /**
     * A quantity as an exact number without trailing zeros or an exponent (2.500 is 2.5, 10 stays 10): what the spool keeps
     * and the workbook writes. Read back from the spool it may be a {@code Double}, whose text is parsed again exactly.
     */
    static BigDecimal quantity(Object value) {
        BigDecimal d = value instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(value));
        d = d.stripTrailingZeros();
        return d.scale() < 0 ? d.setScale(0) : d;
    }

    /** A quantity for people: thousands grouped, up to three decimals, half up. */
    static String quantityText(Object value) {
        java.text.DecimalFormat f =
                new java.text.DecimalFormat("#,##0.###", java.text.DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        f.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return f.format(quantity(value));
    }

    static String count(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    static String date(LocalDate date) {
        return date == null ? "" : date.format(DATE);
    }

    /** A moment in the tenant's own time, to the minute: {@code 8 Oct 2026 13:00}. */
    static String dateTime(Instant instant, ZoneId zone) {
        return instant == null ? "" : instant.atZone(zone).format(DATE_TIME);
    }

    /** A readable number for a report file, unique by its run: {@code RPT-20261008-3F9A21}. */
    static String reportNumber(java.util.UUID runId, Instant made, ZoneId zone) {
        return "RPT-" + NUMBER_DATE.format(made.atZone(zone)) + "-"
                + runId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    /** Days since 1899-12-30, the serial number a spreadsheet stores for a date. */
    static long excelSerial(LocalDate date) {
        return date.toEpochDay() + 25_569L;
    }

    /** {@code branch_id} reads "Branches" (a list) and {@code as_at} reads "As at". */
    static String label(String name, String type) {
        String base = name.endsWith("_id") ? name.substring(0, name.length() - 3) : name;
        String text = base.replace('_', ' ');
        text = text.substring(0, 1).toUpperCase(Locale.ENGLISH) + text.substring(1);
        if ("uuid_list".equals(type)) {
            if (text.endsWith("y")) {
                return text.substring(0, text.length() - 1) + "ies";
            }
            return text + (text.endsWith("ch") || text.endsWith("s") ? "es" : "s");
        }
        return text;
    }
}
