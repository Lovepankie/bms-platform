package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.tenancy.ModuleSettingsGroup;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The tenant settings group {@code sms} (chapter 6 section 6.5, ADR-036, spec 4.8): the tenant's own switch
 * and its acknowledgement, the sender id, the sending window, the two caps and the balance switch, stored in the
 * tenant settings row and versioned and audited with it. Every write path goes through {@link #normalise}, so the
 * rules hold whoever calls:
 *
 * <ul>
 *   <li>switching on needs the acknowledgement record (who, when, which text version); the record is refused
 *       without a switch-on;
 *   <li>the sender id must be one the platform operator approved for this tenant, never a free choice
 *       ({@code not_approved}), and must be 3 to 11 letters and digits with a letter in them;
 *   <li>the window is on the Africa/Kampala clock, starts before it ends, is at least {@value #MIN_WINDOW_MINUTES}
 *       minutes wide and lies between {@value #EARLIEST} and {@value #LATEST}, a hard limit that protects members
 *       from a message in the small hours or at night whatever a tenant sets;
 *   <li>the daily and monthly caps are whole numbers, 0 meaning stop (never the default), and can only be set at
 *       or below the ceiling: the operator's daily cap for the tenant (and the platform's own ceiling), and thirty-one
 *       times that for a month ({@code above_ceiling}). A tenant that never set one has none of its own and the
 *       ceiling applies. The channel takes the lower of the stored cap and the ceiling on every message, so a
 *       ceiling lowered later bites at once.
 * </ul>
 */
@Component
class SmsSettingsGroup implements ModuleSettingsGroup {

    static final String KEY = "sms";
    static final String ENABLED = "enabled";
    static final String ACK_BY = "acknowledged_by";
    static final String ACK_AT = "acknowledged_at";
    static final String ACK_VERSION = "acknowledged_text_version";
    static final String SENDER_ID = "sender_id";
    static final String WINDOW_START = "window_start";
    static final String WINDOW_END = "window_end";
    static final String DAILY_CAP = "daily_cap";
    static final String MONTHLY_CAP = "monthly_cap";
    static final String INCLUDE_BALANCE = "include_balance";

    static final LocalTime EARLIEST_START = LocalTime.of(6, 0);
    static final LocalTime LATEST_END = LocalTime.of(21, 0);
    static final int MIN_WINDOW_MINUTES = 60;
    static final String EARLIEST = "06:00";
    static final String LATEST = "21:00";
    /** A month is at most this many days: the monthly ceiling is the daily ceiling times this. */
    static final int DAYS_IN_MONTH_CEILING = 31;
    /** The operator cap shown for a tenant with no controls row yet (V50). */
    static final int DEFAULT_OPERATOR_DAILY_CAP = 200;
    /** No cap is stored above this, whatever the ceilings: it keeps the numbers inside an int. */
    static final int ABSOLUTE_CAP = 1_000_000;

    /** 3 to 11 ASCII letters and digits, at least one letter (an all digit string is a number, not a name). */
    static final Pattern SENDER = Pattern.compile("^(?=.*[A-Za-z])[A-Za-z0-9]{3,11}$");

    private static final Pattern CLOCK = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final Set<String> KEYS = Set.of(
            ENABLED,
            ACK_BY,
            ACK_AT,
            ACK_VERSION,
            SENDER_ID,
            WINDOW_START,
            WINDOW_END,
            DAILY_CAP,
            MONTHLY_CAP,
            INCLUDE_BALANCE);

    private final JdbcClient jdbc;
    private final SmsSettings platform;

    SmsSettingsGroup(JdbcClient jdbc, SmsSettings platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String moduleKey() {
        return KEY;
    }

    @Override
    public String auditAction() {
        return "core.sms.settings.changed";
    }

    @Override
    public Map<String, Object> normalise(Map<String, Object> current, Map<String, Object> proposed) {
        List<FieldProblem> problems = new ArrayList<>();
        proposed.keySet().stream()
                .filter(k -> !KEYS.contains(k))
                .forEach(k -> problems.add(new FieldProblem(k, "unknown", "Not an SMS setting.")));

        boolean wasOn = Boolean.TRUE.equals(current.get(ENABLED));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ENABLED, wasOn);
        for (String key : List.of(ACK_BY, ACK_AT, ACK_VERSION)) {
            if (current.get(key) instanceof String s) {
                out.put(key, s);
            }
        }
        if (current.get(SENDER_ID) instanceof String s && !s.isBlank()) {
            out.put(SENDER_ID, s);
        }
        // Whatever is stored, the window read back is inside the hard limits: a value written past the screen's
        // validation (an older flat key copied by migration, a row edited by hand) is clamped, never used as it is.
        LocalTime[] safe = safeWindow(current.get(WINDOW_START), current.get(WINDOW_END));
        out.put(WINDOW_START, safe[0].toString());
        out.put(WINDOW_END, safe[1].toString());
        whole(current.get(DAILY_CAP)).ifPresent(v -> out.put(DAILY_CAP, v));
        whole(current.get(MONTHLY_CAP)).ifPresent(v -> out.put(MONTHLY_CAP, v));
        out.put(INCLUDE_BALANCE, Boolean.TRUE.equals(current.get(INCLUDE_BALANCE)));

        flag(proposed, ENABLED, problems);
        flag(proposed, INCLUDE_BALANCE, problems);
        window(proposed, out, problems);
        caps(proposed, out, problems);
        sender(proposed, out, problems);
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }

        boolean switchingOn = Boolean.TRUE.equals(proposed.get(ENABLED)) && !wasOn;
        boolean ackProposed = proposed.keySet().stream().anyMatch(k -> k.startsWith("acknowledged_"));
        if (switchingOn) {
            acknowledgement(proposed).forEach(out::put);
        } else if (ackProposed) {
            throw ApiException.validation(List.of(new FieldProblem(
                    ACK_VERSION, "invalid", "The acknowledgement is recorded only when SMS is switched on.")));
        }
        if (proposed.containsKey(ENABLED)) {
            out.put(ENABLED, proposed.get(ENABLED));
        }
        if (proposed.containsKey(INCLUDE_BALANCE)) {
            out.put(INCLUDE_BALANCE, proposed.get(INCLUDE_BALANCE));
        }
        return out;
    }

    @Override
    public List<String> enabledCapabilities(Map<String, Object> normalised) {
        return List.of();
    }

    // ---- the ceilings -------------------------------------------------------------------------

    /** The most a tenant may set as its daily cap: the operator's cap for it, and never above the platform's own. */
    int dailyCeiling() {
        int operator = jdbc.sql("SELECT daily_cap FROM sms_tenant_control_current()")
                .query(Integer.class)
                .optional()
                .orElse(DEFAULT_OPERATOR_DAILY_CAP);
        return Math.min(operator, platform.platformDailyCap());
    }

    static long monthlyCeiling(int dailyCeiling) {
        return (long) dailyCeiling * DAYS_IN_MONTH_CEILING;
    }

    // ---- parts of a change ---------------------------------------------------------------------

    private static void flag(Map<String, Object> proposed, String key, List<FieldProblem> problems) {
        if (proposed.containsKey(key) && !(proposed.get(key) instanceof Boolean)) {
            problems.add(new FieldProblem(key, "invalid", "Use true or false."));
        }
    }

    private static void window(Map<String, Object> proposed, Map<String, Object> out, List<FieldProblem> problems) {
        boolean touched = proposed.containsKey(WINDOW_START) || proposed.containsKey(WINDOW_END);
        for (String key : List.of(WINDOW_START, WINDOW_END)) {
            if (!proposed.containsKey(key)) {
                continue;
            }
            Object v = proposed.get(key);
            if (!(v instanceof String s) || !CLOCK.matcher(s).matches()) {
                problems.add(new FieldProblem(key, "invalid", "Use a time such as 08:00."));
            } else {
                out.put(key, s);
            }
        }
        if (!touched || !problems.isEmpty()) {
            return;
        }
        LocalTime start = LocalTime.parse((String) out.get(WINDOW_START));
        LocalTime end = LocalTime.parse((String) out.get(WINDOW_END));
        if (!start.isBefore(end)) {
            problems.add(new FieldProblem(WINDOW_END, "invalid", "The window must end later than it starts."));
        } else if (Duration.between(start, end).toMinutes() < MIN_WINDOW_MINUTES) {
            problems.add(new FieldProblem(
                    WINDOW_END, "window_too_short", "The window must be at least " + MIN_WINDOW_MINUTES + " minutes."));
        }
        if (start.isBefore(EARLIEST_START)) {
            problems.add(
                    new FieldProblem(WINDOW_START, "outside_limits", "Messages cannot start before " + EARLIEST + "."));
        }
        if (end.isAfter(LATEST_END)) {
            problems.add(new FieldProblem(WINDOW_END, "outside_limits", "Messages cannot go after " + LATEST + "."));
        }
    }

    private void caps(Map<String, Object> proposed, Map<String, Object> out, List<FieldProblem> problems) {
        if (!proposed.containsKey(DAILY_CAP) && !proposed.containsKey(MONTHLY_CAP)) {
            return;
        }
        int dailyCeiling = dailyCeiling();
        long monthlyCeiling = monthlyCeiling(dailyCeiling);
        cap(proposed, DAILY_CAP, dailyCeiling, out, problems);
        cap(proposed, MONTHLY_CAP, monthlyCeiling, out, problems);
    }

    private static void cap(
            Map<String, Object> proposed,
            String key,
            long ceiling,
            Map<String, Object> out,
            List<FieldProblem> problems) {
        if (!proposed.containsKey(key)) {
            return;
        }
        var value = whole(proposed.get(key));
        if (value.isEmpty() || value.get() > ABSOLUTE_CAP) {
            problems.add(new FieldProblem(key, "invalid", "Use a whole number of 0 (stop) or more."));
        } else if (value.get() > ceiling) {
            problems.add(new FieldProblem(
                    key,
                    "above_ceiling",
                    "Above the limit set for your business (" + ceiling + "). A cap can be lowered, not raised."));
        } else {
            out.put(key, value.get());
        }
    }

    private void sender(Map<String, Object> proposed, Map<String, Object> out, List<FieldProblem> problems) {
        if (!proposed.containsKey(SENDER_ID)) {
            return;
        }
        Object v = proposed.get(SENDER_ID);
        if (v == null || (v instanceof String blank && blank.isBlank())) {
            out.remove(SENDER_ID);
            return;
        }
        if (!(v instanceof String s) || !SENDER.matcher(s).matches()) {
            problems.add(new FieldProblem(
                    SENDER_ID, "invalid", "Use 3 to 11 letters and digits, with at least one letter, and no spaces."));
            return;
        }
        String approved = jdbc.sql("SELECT sms_sender_id_approved(?)")
                .param(s)
                .query(String.class)
                .optional()
                .orElse(null);
        if (approved == null) {
            problems.add(new FieldProblem(
                    SENDER_ID, "not_approved", "This sender id is not approved for your business. Ask the platform."));
        } else {
            out.put(SENDER_ID, approved);
        }
    }

    private static Map<String, Object> acknowledgement(Map<String, Object> proposed) {
        boolean valid = true;
        try {
            valid &= proposed.get(ACK_BY) instanceof String by && UUID.fromString(by) != null;
            valid &= proposed.get(ACK_AT) instanceof String at && Instant.parse(at) != null;
        } catch (IllegalArgumentException | DateTimeParseException e) {
            valid = false;
        }
        valid &= proposed.get(ACK_VERSION) instanceof String v && !v.isBlank() && v.length() <= 40;
        if (!valid) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "acknowledgement_required",
                    "Acknowledgement required",
                    "Switching SMS on needs the acknowledgement of what it costs and who receives it.");
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put(ACK_BY, proposed.get(ACK_BY));
        ack.put(ACK_AT, proposed.get(ACK_AT));
        ack.put(ACK_VERSION, ((String) proposed.get(ACK_VERSION)).strip());
        return ack;
    }

    // ---- reading stored values -----------------------------------------------------------------

    /** A whole number of 0 or more; empty for anything else (a fraction, a string, a negative). */
    static java.util.Optional<Integer> whole(Object v) {
        if (v instanceof Number n
                && n.doubleValue() == Math.rint(n.doubleValue())
                && n.doubleValue() >= 0
                && n.doubleValue() <= Integer.MAX_VALUE) {
            return java.util.Optional.of(n.intValue());
        }
        return java.util.Optional.empty();
    }

    // JUSTIFICATION-A3: review finding 3 needs one read-side clamp; no existing code bounds a stored window.
    /**
     * The stored window inside the hard limits: the start raised to {@value #EARLIEST} and the end lowered to
     * {@value #LATEST} when they lie outside, and the default window when what is left is not a window (it does not
     * start before it ends, or is under {@value #MIN_WINDOW_MINUTES} minutes). Anything that is not a time is the
     * default for that side. {@code [start, end]}.
     */
    static LocalTime[] safeWindow(Object storedStart, Object storedEnd) {
        LocalTime start = LocalTime.parse(clock(storedStart, SmsWindow.DEFAULT_START));
        LocalTime end = LocalTime.parse(clock(storedEnd, SmsWindow.DEFAULT_END));
        if (start.isBefore(EARLIEST_START)) {
            start = EARLIEST_START;
        }
        if (end.isAfter(LATEST_END)) {
            end = LATEST_END;
        }
        if (!start.isBefore(end) || Duration.between(start, end).toMinutes() < MIN_WINDOW_MINUTES) {
            return new LocalTime[] {SmsWindow.DEFAULT_START, SmsWindow.DEFAULT_END};
        }
        return new LocalTime[] {start, end};
    }

    private static String clock(Object stored, LocalTime fallback) {
        if (stored instanceof String s && CLOCK.matcher(s).matches()) {
            return s;
        }
        return fallback.toString();
    }
}
