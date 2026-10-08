package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.BriefResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.DigestPreview;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.DigestSettings;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.DigestSettingsRequest;
import com.rincoltech.bms.lending.insights.internal.Positions.Position;
import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's daily digest (ADR-030): the morning brief and two portfolio lines as plain text,
 * short enough for a phone and free of formatting so the WhatsApp relay of a later slice can send
 * it as it is. Off by default; when on, it goes once a day, at or after the tenant's chosen hour,
 * through the notification outbox to each email recipient and to the named Telegram chat. The
 * outbox key names the tenant, the date and the recipient, so a rerun never sends twice.
 */
@Service
class DigestService {

    static final String TEMPLATE = "insights.daily_digest";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final InsightsService insights;
    private final Positions positions;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    DigestService(
            JdbcClient jdbc,
            InsightsService insights,
            Positions positions,
            Outbox outbox,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.jdbc = jdbc;
        this.insights = insights;
        this.positions = positions;
        this.outbox = outbox;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    private Optional<DigestSettings> row() {
        return jdbc.sql("""
                        SELECT enabled, email_recipients, telegram_chat_id, send_hour, last_sent_on, version
                          FROM lending_insights_digest_settings
                        """)
                .query((rs, n) -> {
                    Array emails = rs.getArray("email_recipients");
                    Date last = rs.getDate("last_sent_on");
                    return new DigestSettings(
                            rs.getBoolean("enabled"),
                            emails == null ? List.of() : Arrays.asList((String[]) emails.getArray()),
                            rs.getString("telegram_chat_id"),
                            rs.getInt("send_hour"),
                            last == null ? null : last.toLocalDate(),
                            rs.getInt("version"));
                })
                .optional();
    }

    @Transactional(readOnly = true)
    DigestSettings settings() {
        return row().orElse(new DigestSettings(false, List.of(), null, 7, null, 0));
    }

    @Transactional
    DigestSettings update(String ifMatch, DigestSettingsRequest request) {
        DigestSettings before = settings();
        if (ifMatch != null && !ifMatch.replace("\"", "").equals(String.valueOf(before.version()))) {
            throw new ApiException(
                    HttpStatus.PRECONDITION_FAILED,
                    "version_mismatch",
                    "Version mismatch",
                    "The settings changed since they were read; reload and try again.");
        }
        if (request.enabled() && request.emailRecipients().isEmpty() && request.telegramChatId() == null) {
            throw ApiException.rule(
                    "digest_needs_recipient", "Name an email recipient or a Telegram chat to switch the digest on.");
        }
        UUID by = CurrentPrincipal.require().userId();
        String[] emails =
                request.emailRecipients().stream().map(String::trim).distinct().toArray(String[]::new);
        jdbc.sql("""
                        INSERT INTO lending_insights_digest_settings (id, tenant_id, enabled, email_recipients,
                            telegram_chat_id, send_hour, updated_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :enabled, :emails, :chat, :hour, :by)
                        ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled,
                            email_recipients = EXCLUDED.email_recipients, telegram_chat_id = EXCLUDED.telegram_chat_id,
                            send_hour = EXCLUDED.send_hour, updated_by = EXCLUDED.updated_by, updated_at = now(),
                            version = lending_insights_digest_settings.version + 1
                        """)
                .param("id", UUID.randomUUID())
                .param("enabled", request.enabled())
                .param("emails", emails)
                .param("chat", request.telegramChatId())
                .param("hour", request.sendHour())
                .param("by", by)
                .update();
        DigestSettings after = settings();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("enabled", before.enabled());
        b.put("email_recipients", before.emailRecipients().size());
        b.put("telegram_chat", before.telegramChatId() != null);
        b.put("send_hour", before.sendHour());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("enabled", after.enabled());
        a.put("email_recipients", after.emailRecipients().size());
        a.put("telegram_chat", after.telegramChatId() != null);
        a.put("send_hour", after.sendHour());
        audit.record(new AuditLog.Entry("lending.insights.digest_updated", "lending.insights", null, null, b, a));
        return after;
    }

    /** The whole tenant, today: the owner's view, whoever reads it. */
    private Scope tenantScope() {
        LocalDate today = clock.today(tenant.profile().timezone());
        return new Scope(today, today, today, null, null, null, tenant.profile().currency());
    }

    @Transactional(readOnly = true)
    DigestPreview preview() {
        Scope s = tenantScope();
        return new DigestPreview(s.today(), subject(s.today()), text(s));
    }

    private String subject(LocalDate date) {
        return tenant.profile().name() + ": daily brief for " + DATE.format(date);
    }

    /** The digest text: the brief's sentences, then the portfolio in two lines. */
    String text(Scope s) {
        BriefResponse brief = insights.brief(s);
        List<Position> live = positions.live(s);
        long po = live.stream().mapToLong(Position::principalOutstandingMinor).sum();
        long par30 = live.stream()
                .filter(p -> p.daysPastDue() > 30)
                .mapToLong(Position::principalOutstandingMinor)
                .sum();
        List<String> lines = new ArrayList<>();
        lines.add("Daily brief for " + tenant.profile().name() + ", " + DATE.format(s.today()));
        lines.add("");
        brief.sentences().forEach(sentence -> lines.add("- " + sentence));
        lines.add("");
        lines.add("Portfolio: " + Sentences.money(po, s.currency()) + " principal outstanding on "
                + (live.size() == 1 ? "1 active loan." : live.size() + " active loans."));
        Long par = Metrics.bp(par30, po);
        lines.add("PAR 30: " + (par == null ? "no loans outstanding." : Sentences.percent(par) + "."));
        lines.add("");
        lines.add("Open Insights in BMS for the details.");
        return String.join("\n", lines);
    }

    /**
     * Sends today's digest when it is on, the tenant's hour has come and it has not gone today.
     * Runs in the job's transaction with the tenant bound; returns the number of messages queued.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int sendIfDue() {
        DigestSettings settings = row().orElse(null);
        if (settings == null || !settings.enabled()) {
            return 0;
        }
        ZonedDateTime now = clock.now().atZone(tenant.profile().timezone());
        LocalDate today = now.toLocalDate();
        if (now.getHour() < settings.sendHour() || today.equals(settings.lastSentOn())) {
            return 0;
        }
        Scope s = tenantScope();
        String text = text(s);
        Map<String, String> params =
                Map.of("business_name", tenant.profile().name(), "date", DATE.format(today), "text", text);
        String base = "insights.digest:" + tenant.profile().id() + ":" + today + ":";
        int queued = 0;
        for (String email : settings.emailRecipients()) {
            if (outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL, email, TEMPLATE, params, base + "email:" + email.toLowerCase(Locale.ROOT)))) {
                queued++;
            }
        }
        if (settings.telegramChatId() != null) {
            String chat = Outbox.TELEGRAM_CHAT_PREFIX + settings.telegramChatId();
            if (outbox.enqueue(new Outbox.Message(
                    Outbox.TELEGRAM, chat, TEMPLATE, params, base + "telegram:" + settings.telegramChatId()))) {
                queued++;
            }
        }
        jdbc.sql("UPDATE lending_insights_digest_settings SET last_sent_on = ?")
                .param(Date.valueOf(today))
                .update();
        audit.record(AuditLog.Entry.created(
                "lending.insights.digest_queued",
                "lending.insights",
                null,
                null,
                Map.of("date", today.toString(), "messages", queued)));
        return queued;
    }
}
