package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Event;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Problem;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Versions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tenant's own SMS wording per event type (FR-NTF-03, ADR-036 decision 9, spec 4.10): the list with each event's
 * default, placeholders and saved override, the save and the removal under {@code If-Match} on the override's
 * version, and the preview with fabricated data. Every text goes through {@link SmsTemplateLanguage} before it is
 * stored, and again when a message is rendered. The preview shows the text exactly as it would be sent, how it is
 * encoded (GSM 7 or UCS-2), how many segments it takes in the sample and in the worst case, and, when the tenant has
 * history, what that many segments cost: the average cost per segment of its own sent messages over the last 30
 * days. No price list is kept in the code or the repository, so a tenant with no history gets no estimate, not a
 * made-up one. The business name in a preview is the tenant's own; every other value is fabricated.
 */
@Service
class SmsTemplateService {

    static final Duration COST_HISTORY = Duration.ofDays(30);

    @Schema(name = "SmsTemplatePlaceholder")
    record PlaceholderView(String name, String description, String sample, int maxLength) {}

    /** How a text is sent: its encoding, length, segments (sample and worst case) and the estimated cost of the sample. */
    @Schema(name = "SmsTemplateMeasure")
    record Measure(
            @Schema(description = "gsm7 or ucs2") String encoding,
            int units,
            int segments,

            @Schema(description = "Every placeholder at its longest and outside the SMS alphabet")
            int worstCaseSegments,

            @Schema(
                    description =
                            "Average cost of a segment of this tenant's sent messages in the last 30 days, times segments; null without history")
            Long estimatedCostMinor,

            String currency) {}

    @Schema(name = "SmsTemplateOverride")
    record OverrideView(String text, int version, Instant updatedAt) {}

    @Schema(name = "SmsTemplate")
    record Item(
            String eventKey,
            String channel,
            String title,
            String defaultText,
            List<PlaceholderView> placeholders,

            @Schema(description = "The tenant's own wording, or null while the default is used")
            OverrideView override,

            @Schema(description = "The wording in force with sample data: the override, else the default")
            String sampleText,

            Measure measure,

            @Schema(
                    description =
                            "Set when the sample is the default words because the business name is too long for the wording")
            String note,

            int maxChars,
            int maxWorstCaseSegments) {}

    @Schema(name = "SmsTemplatePreview")
    record Preview(
            String text,
            Measure measure,

            @Schema(
                    description =
                            "Set when the text shown is the default words, not the ones previewed (a business name too long for the wording)")
            String note) {}

    /** The text of a sample, and a note when it is not the wording asked for. */
    record Sampled(String text, String note) {}

    static final String NOTE_LONG_VALUE = "Your business name is longer than the place the wording has for it, so your"
            + " own wording cannot be used for it and the default words are sent. Shorten the business name in the set-up.";

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final CurrentTenant tenant;
    private final OutboxTemplates defaults;

    SmsTemplateService(
            JdbcClient jdbc, BusinessClock clock, AuditLog audit, CurrentTenant tenant, OutboxTemplates defaults) {
        this.defaults = defaults;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.tenant = tenant;
    }

    @Transactional(readOnly = true)
    List<Item> list() {
        Map<String, OverrideView> overrides = new LinkedHashMap<>();
        jdbc.sql("SELECT event_key, body, version, updated_at FROM sms_template_overrides")
                .query((rs, n) -> {
                    overrides.put(
                            rs.getString("event_key"),
                            new OverrideView(
                                    rs.getString("body"),
                                    rs.getInt("version"),
                                    rs.getTimestamp("updated_at").toInstant()));
                    return null;
                })
                .list();
        CostRate rate = costRate();
        return SmsTemplateEvents.ALL.stream()
                .map(e -> item(e, overrides.get(e.key()), rate))
                .toList();
    }

    @Transactional
    Item put(String eventKey, String ifMatch, String rawText) {
        Event event = event(eventKey);
        int expected = Versions.fromIfMatch(ifMatch);
        String text = check(event, rawText);
        Optional<OverrideView> current = override(eventKey);
        int currentVersion = current.map(OverrideView::version).orElse(0);
        if (currentVersion != expected) {
            throw Versions.conflict(currentVersion);
        }
        Instant now = clock.now();
        UUID actor = CurrentPrincipal.require().userId();
        if (current.isEmpty()) {
            Integer created = jdbc.sql("INSERT INTO sms_template_overrides (id, tenant_id, event_key, body, version,"
                            + " created_at, updated_at, updated_by)"
                            + " VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 1, ?, ?, ?)"
                            + " ON CONFLICT (tenant_id, event_key) DO NOTHING RETURNING version")
                    .params(UUID.randomUUID(), eventKey, text, Timestamp.from(now), Timestamp.from(now), actor)
                    .query(Integer.class)
                    .optional()
                    .orElse(null);
            if (created == null) {
                // Another administrator saved the first override between our read and our insert.
                throw Versions.conflict(
                        override(eventKey).map(OverrideView::version).orElse(1));
            }
            audit("core.sms.template.changed", eventKey, Map.of(), Map.of("text", text, "version", 1));
        } else if (!current.get().text().equals(text)) {
            Integer updated = jdbc.sql("UPDATE sms_template_overrides SET body = ?, version = version + 1,"
                            + " updated_at = ?, updated_by = ? WHERE event_key = ? AND version = ? RETURNING version")
                    .params(text, Timestamp.from(now), actor, eventKey, expected)
                    .query(Integer.class)
                    .optional()
                    .orElse(null);
            if (updated == null) {
                throw Versions.conflict(
                        override(eventKey).map(OverrideView::version).orElse(0));
            }
            audit(
                    "core.sms.template.changed",
                    eventKey,
                    Map.of("text", current.get().text(), "version", expected),
                    Map.of("text", text, "version", updated));
        }
        return item(event, override(eventKey).orElse(null), costRate());
    }

    @Transactional
    Item revert(String eventKey, String ifMatch) {
        Event event = event(eventKey);
        int expected = Versions.fromIfMatch(ifMatch);
        Optional<OverrideView> current = override(eventKey);
        if (current.isEmpty()) {
            if (expected != 0) {
                throw Versions.conflict(0);
            }
            return item(event, null, costRate());
        }
        if (current.get().version() != expected) {
            throw Versions.conflict(current.get().version());
        }
        int deleted = jdbc.sql("DELETE FROM sms_template_overrides WHERE event_key = ? AND version = ?")
                .params(eventKey, expected)
                .update();
        if (deleted == 0) {
            throw Versions.conflict(
                    override(eventKey).map(OverrideView::version).orElse(0));
        }
        audit(
                "core.sms.template.reverted",
                eventKey,
                Map.of("text", current.get().text(), "version", expected),
                Map.of());
        return item(event, null, costRate());
    }

    /** The saved override, else the default, or the text given, shown with sample data; nothing is stored or sent. */
    @Transactional(readOnly = true)
    Preview preview(String eventKey, String rawText) {
        Event event = event(eventKey);
        String text = rawText == null
                ? override(eventKey).map(OverrideView::text).orElse(event.defaultText())
                : check(event, rawText);
        Sampled sampled = sampled(event, text);
        return new Preview(sampled.text(), measure(event, text, costRate()), sampled.note());
    }

    /**
     * The rendered sample of a text with fabricated values and the tenant's own business name. A business name too long
     * for the wording is never cut, so the wording cannot be used for it and the default is what is sent: that is what
     * is shown, with the reason.
     */
    Sampled sampled(Event event, String text) {
        Map<String, String> values =
                SmsTemplateLanguage.sampleValues(event, tenant.profile().name());
        try {
            return new Sampled(SmsTemplateLanguage.render(event, text, values), null);
        } catch (Problem p) {
            if (!"value_too_long".equals(p.code())) {
                throw p;
            }
            return new Sampled(
                    defaults.render(SmsTemplateEvents.CHANNEL, event.key(), values)
                            .text(),
                    NOTE_LONG_VALUE);
        }
    }

    String sample(Event event, String text) {
        return sampled(event, text).text();
    }

    Event event(String eventKey) {
        return SmsTemplateEvents.find(eventKey).orElseThrow(ApiException::notFound);
    }

    private String check(Event event, String rawText) {
        if (rawText == null) {
            throw ApiException.validation(List.of(new FieldProblem("text", "blank", "The message cannot be empty.")));
        }
        String text = rawText.replace("\r\n", "\n").replace('\r', '\n').strip();
        try {
            SmsTemplateLanguage.validate(event, text);
        } catch (Problem p) {
            throw ApiException.validation(List.of(new FieldProblem("text", p.code(), p.getMessage())));
        }
        return text;
    }

    private Optional<OverrideView> override(String eventKey) {
        return jdbc.sql("SELECT body, version, updated_at FROM sms_template_overrides WHERE event_key = ?")
                .param(eventKey)
                .query((rs, n) -> new OverrideView(
                        rs.getString("body"),
                        rs.getInt("version"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    private Item item(Event event, OverrideView override, CostRate rate) {
        String effective = override == null ? event.defaultText() : override.text();
        try {
            SmsTemplateLanguage.validate(event, effective);
        } catch (Problem p) {
            // A saved wording the rules have since moved past is shown as it is saved, but the sample is the default's:
            // the channel falls back to the default for it too (SmsOutboxChannel.renderText).
            effective = event.defaultText();
        }
        return new Item(
                event.key(),
                SmsTemplateEvents.CHANNEL,
                event.title(),
                event.defaultText(),
                event.placeholders().stream()
                        .map(p -> new PlaceholderView(p.name(), p.description(), p.sample(), p.maxLength()))
                        .toList(),
                override,
                sampled(event, effective).text(),
                measure(event, effective, rate),
                sampled(event, effective).note(),
                SmsTemplateLanguage.MAX_CHARS,
                SmsTemplateLanguage.MAX_WORST_SEGMENTS);
    }

    Measure measure(Event event, String text, CostRate rate) {
        SmsSegments.Count sample = SmsSegments.count(sample(event, text));
        int worst =
                SmsSegments.count(SmsTemplateLanguage.worstCase(event, text)).segments();
        Long estimate = rate == null
                ? null
                : rate.perSegment()
                        .multiply(BigDecimal.valueOf(sample.segments()))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
        return new Measure(
                sample.encoding() == SmsSegments.Encoding.GSM7 ? "gsm7" : "ucs2",
                sample.units(),
                sample.segments(),
                worst,
                estimate,
                rate == null ? null : rate.currency());
    }

    /** What one segment cost this tenant on average lately, from its own delivery log; null without history. */
    record CostRate(BigDecimal perSegment, String currency) {}

    CostRate costRate() {
        Instant now = clock.now();
        return jdbc
                .sql("SELECT sum(cost_minor) AS cost, sum(segments) AS segs, max(currency) AS currency,"
                        + " count(DISTINCT currency) AS currencies FROM sms_deliveries"
                        + " WHERE created_at >= ? AND created_at <= ? AND status IN ('sent', 'delivered')"
                        + " AND cost_minor IS NOT NULL AND segments IS NOT NULL AND currency IS NOT NULL")
                .params(Timestamp.from(now.minus(COST_HISTORY)), Timestamp.from(now))
                .query((rs, n) -> {
                    long segments = rs.getLong("segs");
                    // Mixed currencies cannot be averaged: no estimate rather than a wrong one.
                    if (segments <= 0 || rs.getInt("currencies") != 1) {
                        return null;
                    }
                    return new CostRate(
                            BigDecimal.valueOf(rs.getLong("cost"))
                                    .divide(BigDecimal.valueOf(segments), 6, RoundingMode.HALF_UP),
                            rs.getString("currency"));
                })
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private void audit(String action, String eventKey, Map<String, Object> before, Map<String, Object> after) {
        audit.record(new AuditLog.Entry(
                action,
                "core.sms_template",
                UUID.nameUUIDFromBytes(eventKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                null,
                before,
                after));
    }
}
