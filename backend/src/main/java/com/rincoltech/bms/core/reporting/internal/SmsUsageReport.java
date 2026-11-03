package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.notifications.SmsUsage;
import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * {@code core.sms_usage} (chapter 14 section 14.6, FR-NTF-07, FR-NTF-15, issue #215): the business's SMS use per calendar
 * month and per event, from its delivery log through {@link SmsUsage}. Permission {@code core.reports.financial}; offered
 * only to a business with its SMS switch on or any message in the log (off by default). Parameters {@code from} and
 * {@code to}, whole months in the business's time zone; the last twelve months up to today when absent, at most 24
 * months (the log keeps 13). Two tables, read in the run's one snapshot:
 *
 * <ul>
 *   <li>{@code usage}: per month and event, the messages, how many were reported delivered and not delivered, the
 *       segments, the recorded cost and the messages with no recorded cost; the last row is the total.
 *   <li>{@code months}: per month, the messages, segments and recorded cost; the last row is the total.
 * </ul>
 *
 * The cost is what the provider reported for each message, in integer minor units of the business's currency; nothing
 * here prices a message or bills anyone.
 */
@Component
class SmsUsageReport implements ReportDefinition {

    static final String KEY = "core.sms_usage";
    static final int MAX_MONTHS = 24;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final SmsUsage usage;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    SmsUsageReport(SmsUsage usage, CurrentTenant tenant, BusinessClock clock) {
        this.usage = usage;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "SMS use";
    }

    @Override
    public String description() {
        return "Text messages sent each month by event, with their segments and the cost the provider recorded.";
    }

    @Override
    public String module() {
        return "core";
    }

    @Override
    public String permission() {
        return "core.reports.financial";
    }

    @Override
    public boolean offeredInTenant() {
        return usage.offered();
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param(
                        "from", "date", false, "A day in the first month; eleven months before the last when absent."),
                new Param("to", "date", false, "A day in the last month; today when absent."));
    }

    @Override
    public Set<String> formats() {
        return Set.of("json", "csv", "pdf", "xlsx");
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        CurrentTenant.Profile profile = tenant.profile();
        LocalDate today = clock.today(profile.timezone());
        LocalDate to = ReportParams.date(params, "to", today);
        LocalDate from = ReportParams.date(params, "from", to.withDayOfMonth(1).minusMonths(11));
        YearMonth first = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        if (first.isAfter(last)) {
            throw ReportParams.invalid("from", "The first month must not be after the last.");
        }
        if (ChronoUnit.MONTHS.between(first, last) >= MAX_MONTHS) {
            throw ReportParams.invalid("from", "Ask for at most " + MAX_MONTHS + " months.");
        }
        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put("from", first.atDay(1).toString());
        normalised.put("to", last.atEndOfMonth().toString());
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("zone", profile.timezone().getId());
        scope.put("currency", profile.currency());
        return new Prepared(normalised, scope, to);
    }

    @Override
    public String describeScope(Prepared run) {
        return "The whole business";
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        LocalDate from = LocalDate.parse((String) prepared.params().get("from"));
        LocalDate to = LocalDate.parse((String) prepared.params().get("to"));
        ZoneId zone = ZoneId.of((String) prepared.scope().get("zone"));
        String currency = (String) prepared.scope().get("currency");
        out.note("A message counts once it was handed to the provider, including one whose outcome is unknown; a"
                + " message refused, blocked by an opt out, still queued or written to the test sink does not.");
        out.note("Cost is the cost the provider recorded for each message, in " + currency
                + "; a message without one is counted under Without a cost.");
        List<SmsUsage.Line> lines = usage.months(from, to, zone, currency);

        ReportOutput.Table byEvent = out.table(
                "usage",
                "By month and event",
                List.of(
                        new Column("month", "Month", Kind.TEXT),
                        new Column("event", "Event", Kind.TEXT),
                        new Column("messages", "Messages", Kind.COUNT),
                        new Column("delivered", "Delivered", Kind.COUNT),
                        new Column("undelivered", "Not delivered", Kind.COUNT),
                        new Column("segments", "Segments", Kind.COUNT),
                        new Column("cost", "Recorded cost", Kind.MONEY),
                        new Column("without_cost", "Without a cost", Kind.COUNT)));
        long[] total = new long[6];
        Map<YearMonth, long[]> perMonth = new TreeMap<>();
        for (SmsUsage.Line l : lines) {
            byEvent.row(
                    MONTH.format(l.month()),
                    l.event(),
                    l.messages(),
                    l.delivered(),
                    l.undelivered(),
                    l.segments(),
                    l.costMinor(),
                    l.withoutCost());
            long[] figures = {l.messages(), l.delivered(), l.undelivered(), l.segments(), l.costMinor(), l.withoutCost()
            };
            long[] month = perMonth.computeIfAbsent(l.month(), m -> new long[6]);
            for (int i = 0; i < figures.length; i++) {
                total[i] += figures[i];
                month[i] += figures[i];
            }
        }
        byEvent.total("Total", null, total[0], total[1], total[2], total[3], total[4], total[5]);

        ReportOutput.Table months = out.table(
                "months",
                "By month",
                List.of(
                        new Column("month", "Month", Kind.TEXT),
                        new Column("messages", "Messages", Kind.COUNT),
                        new Column("segments", "Segments", Kind.COUNT),
                        new Column("cost", "Recorded cost", Kind.MONEY)));
        for (Map.Entry<YearMonth, long[]> m : perMonth.entrySet()) {
            months.row(MONTH.format(m.getKey()), m.getValue()[0], m.getValue()[3], m.getValue()[4]);
        }
        months.total("Total", total[0], total[3], total[4]);
    }
}
