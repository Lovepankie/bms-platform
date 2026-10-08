package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.kernel.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits a date range into the periods of a grain: day, week (Monday to Sunday), month or year.
 * The first and last periods are cut at the range ends, so a partial month is labelled and summed
 * as the part inside the range.
 */
final class Periods {

    static final Set<String> GRAINS = Set.of("day", "week", "month", "year");

    /** At most this many periods in one series: 92 days, about two years of weeks, ten years of months. */
    static final int MAX_PERIODS = 120;

    record Period(LocalDate start, LocalDate end, String label) {

        boolean contains(LocalDate d) {
            return !d.isBefore(start) && !d.isAfter(end);
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private Periods() {}

    /** The grain to use when none is asked for: days up to 31 days, weeks up to 120 days, then months. */
    static String defaultGrain(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days <= 31) {
            return "day";
        }
        if (days <= 120) {
            return "week";
        }
        return days <= 3 * 366 ? "month" : "year";
    }

    static List<Period> split(LocalDate from, LocalDate to, String grain) {
        if (!GRAINS.contains(grain)) {
            throw ApiException.rule("invalid_grain", "The grain is day, week, month or year.");
        }
        List<Period> out = new ArrayList<>();
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate natural = switch (grain) {
                case "day" -> start;
                case "week" -> start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                case "month" -> start.with(TemporalAdjusters.lastDayOfMonth());
                default -> start.with(TemporalAdjusters.lastDayOfYear());
            };
            LocalDate end = natural.isAfter(to) ? to : natural;
            out.add(new Period(start, end, label(start, end, grain)));
            if (out.size() > MAX_PERIODS) {
                throw ApiException.rule(
                        "too_many_periods",
                        "The range has more than " + MAX_PERIODS + " " + grain + "s; pick a larger grain.");
            }
            start = end.plusDays(1);
        }
        return out;
    }

    static String label(LocalDate start, LocalDate end, String grain) {
        return switch (grain) {
            case "day" -> DAY.format(start);
            case "week" -> "Week of " + DAY.format(start);
            case "month" -> MONTH.format(start);
            default -> String.valueOf(start.getYear());
        };
    }

    /** The index of the period holding {@code d}, or -1. Periods are in date order and contiguous. */
    static int indexOf(List<Period> periods, LocalDate d) {
        int lo = 0;
        int hi = periods.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Period p = periods.get(mid);
            if (d.isBefore(p.start())) {
                hi = mid - 1;
            } else if (d.isAfter(p.end())) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** The last day of each of the 11 months before {@code today}'s month, oldest first, then today. */
    static List<LocalDate> monthEnds(LocalDate today) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        for (int k = 11; k >= 1; k--) {
            out.add(firstOfMonth.minusMonths(k).with(TemporalAdjusters.lastDayOfMonth()));
        }
        out.add(today);
        return out;
    }
}
