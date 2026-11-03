package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.SmsUsage;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SmsUsage} over the tenant's delivery log ({@code sms_deliveries}, row-level security), for the report
 * {@code core.sms_usage} (issue #215). Plain SQL in the caller's transaction: the report's one read-only snapshot.
 */
@Component
class JdbcSmsUsage implements SmsUsage {

    private final JdbcClient jdbc;
    private final SmsTenantConfig config;

    /**
     * The tenant config is lazy: the settings service lists every report definition while it is being made, and this port
     * sits under one ({@code core.sms_usage}), so a direct reference would be a cycle.
     */
    JdbcSmsUsage(JdbcClient jdbc, @Lazy SmsTenantConfig config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean offered() {
        return config.current().enabled()
                || jdbc.sql("SELECT EXISTS (SELECT 1 FROM sms_deliveries)")
                        .query(Boolean.class)
                        .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Line> months(LocalDate from, LocalDate to, ZoneId zone, String currency) {
        Timestamp start =
                Timestamp.from(from.withDayOfMonth(1).atStartOfDay(zone).toInstant());
        Timestamp end = Timestamp.from(
                YearMonth.from(to).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        return jdbc.sql("""
                        SELECT to_char(d.created_at AT TIME ZONE ?, 'YYYY-MM') AS month, d.template_key,
                               count(*) AS messages,
                               count(*) FILTER (WHERE d.status = 'delivered') AS delivered,
                               count(*) FILTER (WHERE d.status = 'undelivered') AS undelivered,
                               coalesce(sum(coalesce(d.provider_segments, d.segments, 0)), 0) AS segments,
                               coalesce(sum(d.cost_minor) FILTER (WHERE d.currency = ?), 0) AS cost,
                               count(*) FILTER (WHERE d.cost_minor IS NULL OR d.currency IS DISTINCT FROM ?) AS without_cost
                          FROM sms_deliveries d
                         WHERE d.status IN ('sent', 'delivered', 'undelivered', 'unknown')
                           AND d.created_at >= ? AND d.created_at < ?
                         GROUP BY 1, 2
                         ORDER BY 1, 2
                        """)
                .params(zone.getId(), currency, currency, start, end)
                .query((rs, n) -> {
                    String key = rs.getString("template_key");
                    return new Line(
                            YearMonth.parse(rs.getString("month")),
                            key,
                            eventName(key),
                            rs.getLong("messages"),
                            rs.getLong("delivered"),
                            rs.getLong("undelivered"),
                            rs.getLong("segments"),
                            rs.getLong("cost"),
                            rs.getLong("without_cost"));
                })
                .list();
    }

    /** The event in words: the template's title, or the key itself for one this build does not name. */
    static String eventName(String key) {
        if (key.equals("payment.link")) {
            return "Payment link";
        }
        if (key.startsWith("test.")) {
            return "Test message, " + eventName(key.substring("test.".length()));
        }
        return SmsTemplateEvents.find(key).map(SmsTemplateLanguage.Event::title).orElse(key);
    }
}
