package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * The range chosen on the dashboard (issue #428): its days, the period before it of the same length, and the steps of its
 * chart. A day a step up to 62 days, seven days a step from the first day up to a year, a calendar month a step beyond (All
 * only). The steps of the period before are counted from its own first day, so step i of both lines up on the chart.
 */
record DashboardRange(
        String period, LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo, String group) {

    static final Set<String> PERIODS = Set.of("today", "7d", "30d", "ytd", "1y", "all", "custom");
    static final int DAY_STEPS_UP_TO = 62;
    /** All reaches back at most this many years (ADR-060): the figures of a longer history are read in whole, never in one request. */
    static final int ALL_YEARS = 3;
    /** Beyond this many days (two years) the best product and best seller are not worked out (ADR-060): they scan every line of the span. */
    static final int LEADERS_UP_TO_DAYS = 731;

    /**
     * The range of a request, or null when it asks for none (the dashboard of issue #149 as it was). Dates without a period
     * are a custom range; a custom range follows the window of the other reports (at most 366 days).
     */
    static DashboardRange of(String period, LocalDate from, LocalDate to, LocalDate today, LocalDate firstSale) {
        if (period == null && from == null && to == null) {
            return null;
        }
        String p = period == null ? "custom" : period;
        if (!PERIODS.contains(p)) {
            throw ApiException.validation(List.of(
                    new FieldProblem("period", "invalid", "period is today, 7d, 30d, ytd, 1y, all or custom.")));
        }
        if (!p.equals("custom") && (from != null || to != null)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("from", "invalid", "from and to go with period custom only.")));
        }
        if (p.equals("custom") && (from == null || to == null)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("from", "required", "A custom range needs both from and to.")));
        }
        return switch (p) {
            case "today" -> before(p, today, today);
            case "7d" -> before(p, today.minusDays(6), today);
            case "30d" -> before(p, today.minusDays(29), today);
            // This year is set against the same days of last year.
            case "ytd" -> {
                LocalDate start = today.withDayOfYear(1);
                yield new DashboardRange(
                        p, start, today, start.minusYears(1), today.minusYears(1), group(start, today));
            }
            case "1y" -> {
                LocalDate start = today.minusYears(1).plusDays(1);
                yield new DashboardRange(p, start, today, start.minusYears(1), start.minusDays(1), group(start, today));
            }
            // Everything since the first sale in scope, at most three years: nothing comes before it to compare with.
            case "all" -> {
                LocalDate floor = today.minusYears(ALL_YEARS).plusDays(1);
                LocalDate start = firstSale == null || firstSale.isAfter(today) ? today : firstSale;
                if (start.isBefore(floor)) {
                    start = floor;
                }
                yield new DashboardRange(p, start, today, null, null, group(start, today));
            }
            default -> {
                AnalyticsSupport.Window w = AnalyticsSupport.window(from, to, today);
                if (w.to().isAfter(today)) {
                    throw ApiException.validation(
                            List.of(new FieldProblem("to", "invalid", "to must not be after today.")));
                }
                yield before(p, w.from(), w.to());
            }
        };
    }

    /** A range set against the days just before it, as many as it has. */
    private static DashboardRange before(String period, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return new DashboardRange(period, from, to, from.minusDays(days), from.minusDays(1), group(from, to));
    }

    private static String group(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return days <= DAY_STEPS_UP_TO ? "day" : days <= AnalyticsSupport.MAX_DAYS ? "week" : "month";
    }

    boolean hasPrevious() {
        return previousFrom != null;
    }

    /** Whether the best product and best seller are worked out: not over a span longer than two years. */
    boolean leaders() {
        return ChronoUnit.DAYS.between(from, to) + 1 <= LEADERS_UP_TO_DAYS;
    }

    /** The first day the figures need: the period before when there is one. */
    LocalDate first() {
        return hasPrevious() ? previousFrom : from;
    }

    boolean contains(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }

    boolean inPrevious(LocalDate day) {
        return hasPrevious() && !day.isBefore(previousFrom) && !day.isAfter(previousTo);
    }

    /** How many steps the chart has. */
    int steps() {
        return step(from, to) + 1;
    }

    /** The step of a day of the range, or of the period before when counted from its first day. */
    int step(LocalDate start, LocalDate day) {
        return switch (group) {
            case "month" -> (int) ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(day));
            case "week" -> (int) (ChronoUnit.DAYS.between(start, day) / 7);
            default -> (int) ChronoUnit.DAYS.between(start, day);
        };
    }

    /** The first day of a step of the range. */
    LocalDate stepStart(int i) {
        return switch (group) {
            case "month" -> i == 0 ? from : YearMonth.from(from).plusMonths(i).atDay(1);
            case "week" -> from.plusDays(7L * i);
            default -> from.plusDays(i);
        };
    }
}
