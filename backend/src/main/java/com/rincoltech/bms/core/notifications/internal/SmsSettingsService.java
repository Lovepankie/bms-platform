package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Versions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the tenant's SMS settings group through the core's settings store, so every change is versioned
 * under {@code If-Match}, audited with before and after, and held to the group's own rules whoever calls
 * ({@link SmsSettingsGroup}). Two things belong here and not in the group: the acknowledgement is stamped with the
 * signed-in user and the clock, and switching on drops what was queued before, in the same transaction, so a
 * tenant never releases a backlog by switching SMS on (ADR-036 decision 14).
 */
@Service
class SmsSettingsService {

    /** The version of the text the administrator is shown before switching on; a change of wording bumps it. */
    static final String ACK_TEXT_VERSION = "sms-1";

    static final String ACK_TEXT =
            "Switching on SMS sends text messages from your business to your members' phones: savings receipts now,"
                    + " and reminders when they are built. Every message costs money. The cost of each message is"
                    + " recorded and is passed on to your business through your plan, and the platform sets the most"
                    + " you can send in a day. A message goes only to a member's own phone, never to next of kin,"
                    + " and never to someone who has opted out. Messages that were queued while SMS was off are"
                    + " not sent when you switch it on.";

    @Schema(name = "SmsAcknowledgement", description = "The text to show before switching on, and its version")
    record Acknowledgement(String textVersion, String text) {}

    /** What the settings screen shows: the saved values, the limits they sit under, and today's and this month's use. */
    @Schema(name = "SmsSettings")
    record View(
            boolean enabled,
            boolean operatorEnabled,
            String mode,
            boolean testMessageAvailable,
            String senderId,
            String senderStatus,
            List<String> approvedSenderIds,
            String windowStart,
            String windowEnd,
            String windowZone,
            String windowEarliest,
            String windowLatest,
            int windowMinMinutes,
            Integer dailyCap,
            Integer monthlyCap,
            int dailyCeiling,
            long monthlyCeiling,
            int effectiveDailyCap,
            long effectiveMonthlyCap,
            long usedToday,
            long usedThisMonth,
            boolean includeBalance,
            Acknowledgement acknowledgement,
            String acknowledgedTextVersion,
            Instant acknowledgedAt,
            UUID acknowledgedBy,
            int version) {}

    /** A partial change; a null field is unchanged. An empty {@code senderId} clears it. */
    record Change(
            Boolean enabled,
            String acknowledgedTextVersion,
            String senderId,
            String windowStart,
            String windowEnd,
            Integer dailyCap,
            Integer monthlyCap,
            Boolean includeBalance) {}

    private static final String COUNTED = "'queued', 'sent', 'delivered', 'undelivered', 'unknown', 'sunk'";

    private final ModuleSettings settings;
    private final SmsSettingsGroup group;
    private final SmsSettings platform;
    private final JdbcClient jdbc;
    private final BusinessClock clock;

    SmsSettingsService(
            ModuleSettings settings,
            SmsSettingsGroup group,
            SmsSettings platform,
            JdbcClient jdbc,
            BusinessClock clock) {
        this.settings = settings;
        this.group = group;
        this.platform = platform;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    View view() {
        Map<String, Object> g = settings.group(SmsSettingsGroup.KEY);
        Optional<Integer> operatorCap = jdbc.sql("SELECT daily_cap FROM sms_tenant_control_current() WHERE enabled")
                .query(Integer.class)
                .optional();
        int dailyCeiling = group.dailyCeiling();
        long monthlyCeiling = SmsSettingsGroup.monthlyCeiling(dailyCeiling);
        Integer dailyCap =
                SmsSettingsGroup.whole(g.get(SmsSettingsGroup.DAILY_CAP)).orElse(null);
        Integer monthlyCap =
                SmsSettingsGroup.whole(g.get(SmsSettingsGroup.MONTHLY_CAP)).orElse(null);
        List<String> approved = jdbc.sql("SELECT sender_id FROM sms_sender_ids_current()")
                .query(String.class)
                .list();
        String chosen = g.get(SmsSettingsGroup.SENDER_ID) instanceof String s ? s : null;
        String senderStatus = chosen == null
                ? "none"
                : approved.stream().anyMatch(chosen::equalsIgnoreCase) ? "approved" : "withdrawn";

        Instant now = clock.now();
        LocalDate today = now.atZone(BusinessClock.DEFAULT_ZONE).toLocalDate();
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        long usedToday = count(today, today.plusDays(1));
        long usedMonth = count(firstOfMonth, firstOfMonth.plusMonths(1));

        return new View(
                Boolean.TRUE.equals(g.get(SmsSettingsGroup.ENABLED)),
                operatorCap.isPresent(),
                platform.mode().key(),
                platform.mode() == SmsSettings.Mode.SINK || platform.mode() == SmsSettings.Mode.ALLOWLIST,
                chosen,
                senderStatus,
                approved,
                (String) g.get(SmsSettingsGroup.WINDOW_START),
                (String) g.get(SmsSettingsGroup.WINDOW_END),
                BusinessClock.DEFAULT_ZONE.getId(),
                SmsSettingsGroup.EARLIEST,
                SmsSettingsGroup.LATEST,
                SmsSettingsGroup.MIN_WINDOW_MINUTES,
                dailyCap,
                monthlyCap,
                dailyCeiling,
                monthlyCeiling,
                dailyCap == null ? dailyCeiling : Math.min(dailyCap, dailyCeiling),
                monthlyCap == null ? monthlyCeiling : Math.min(monthlyCap, monthlyCeiling),
                usedToday,
                usedMonth,
                Boolean.TRUE.equals(g.get(SmsSettingsGroup.INCLUDE_BALANCE)),
                new Acknowledgement(ACK_TEXT_VERSION, ACK_TEXT),
                (String) g.get(SmsSettingsGroup.ACK_VERSION),
                g.get(SmsSettingsGroup.ACK_AT) instanceof String s ? Instant.parse(s) : null,
                g.get(SmsSettingsGroup.ACK_BY) instanceof String s ? UUID.fromString(s) : null,
                settings.version());
    }

    @Transactional
    View update(String ifMatch, Change c) {
        int expected = Versions.fromIfMatch(ifMatch);
        // The version first: a request that lost a race is a conflict whatever else is wrong with it. (The check that
        // counts is the one under the row lock inside the change below.)
        int current = settings.version();
        if (current != expected) {
            throw Versions.conflict(current);
        }
        Map<String, Object> before = settings.group(SmsSettingsGroup.KEY);
        boolean wasOn = Boolean.TRUE.equals(before.get(SmsSettingsGroup.ENABLED));
        boolean switchingOn = Boolean.TRUE.equals(c.enabled()) && !wasOn;

        Map<String, Object> proposed = new LinkedHashMap<>();
        if (c.enabled() != null) {
            proposed.put(SmsSettingsGroup.ENABLED, c.enabled());
        }
        if (switchingOn) {
            if (!ACK_TEXT_VERSION.equals(c.acknowledgedTextVersion())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "acknowledgement_required",
                        "Acknowledgement required",
                        "Switching SMS on needs the acknowledgement of the text that was shown.");
            }
            proposed.put(
                    SmsSettingsGroup.ACK_BY, CurrentPrincipal.require().userId().toString());
            proposed.put(SmsSettingsGroup.ACK_AT, clock.now().toString());
            proposed.put(SmsSettingsGroup.ACK_VERSION, ACK_TEXT_VERSION);
        } else if (c.acknowledgedTextVersion() != null) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "acknowledged_text_version",
                    "invalid",
                    "The acknowledgement is only taken when SMS is switched on.")));
        }
        if (c.senderId() != null) {
            proposed.put(SmsSettingsGroup.SENDER_ID, c.senderId());
        }
        if (c.windowStart() != null) {
            proposed.put(SmsSettingsGroup.WINDOW_START, c.windowStart());
        }
        if (c.windowEnd() != null) {
            proposed.put(SmsSettingsGroup.WINDOW_END, c.windowEnd());
        }
        if (c.dailyCap() != null) {
            proposed.put(SmsSettingsGroup.DAILY_CAP, c.dailyCap());
        }
        if (c.monthlyCap() != null) {
            proposed.put(SmsSettingsGroup.MONTHLY_CAP, c.monthlyCap());
        }
        if (c.includeBalance() != null) {
            proposed.put(SmsSettingsGroup.INCLUDE_BALANCE, c.includeBalance());
        }

        settings.change(SmsSettingsGroup.KEY, proposed, expected);
        if (switchingOn) {
            // Same transaction as the change: either both happen or neither.
            jdbc.sql("SELECT sms_tenant_switched_on(?)")
                    .param(Timestamp.from(clock.now()))
                    .query(Integer.class)
                    .single();
        }
        return view();
    }

    private long count(LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT count(*) FROM sms_deliveries WHERE created_at >= ? AND created_at < ?"
                        + " AND status IN (" + COUNTED + ")")
                .params(
                        Timestamp.from(
                                from.atStartOfDay(BusinessClock.DEFAULT_ZONE).toInstant()),
                        Timestamp.from(
                                to.atStartOfDay(BusinessClock.DEFAULT_ZONE).toInstant()))
                .query(Long.class)
                .single();
    }
}
