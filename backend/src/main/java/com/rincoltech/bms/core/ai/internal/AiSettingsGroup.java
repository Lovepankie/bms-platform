package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettingsGroup;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The tenant settings group {@code ai} (chapter 6 section 6.5, ADR-037): the switch, the two daily
 * caps and the acknowledgement record, stored in the tenant settings row and versioned and audited
 * with it. Every write path goes through {@link #normalise}, so the platform allow-list holds
 * whoever calls: switching on for a tenant that is not on the list is refused with 403
 * {@code advisor_not_available}, and so is any attempt to write the acknowledgement without
 * switching on. A cap above the platform ceiling is refused; the gateway also takes the lower of the
 * two on every call, so lowering the ceiling later bites at once.
 */
@Component
class AiSettingsGroup implements ModuleSettingsGroup {

    static final String KEY = "ai";
    static final String ENABLED = "enabled";
    static final String TOKEN_CAP = "daily_token_cap";
    static final String COST_CAP = "daily_cost_cap_micro";
    static final String ACK_BY = "acknowledged_by";
    static final String ACK_AT = "acknowledged_at";
    static final String ACK_VERSION = "acknowledged_text_version";
    private static final Set<String> KEYS = Set.of(ENABLED, TOKEN_CAP, COST_CAP, ACK_BY, ACK_AT, ACK_VERSION);

    private final AiProperties properties;
    private final AiAllowList allowList;
    private final CurrentTenant tenant;

    AiSettingsGroup(AiProperties properties, AiAllowList allowList, CurrentTenant tenant) {
        this.properties = properties;
        this.allowList = allowList;
        this.tenant = tenant;
    }

    @Override
    public String moduleKey() {
        return KEY;
    }

    @Override
    public String auditAction() {
        return "core.ai.settings.changed";
    }

    @Override
    public Map<String, Object> normalise(Map<String, Object> current, Map<String, Object> proposed) {
        List<FieldProblem> problems = new ArrayList<>();
        proposed.keySet().stream()
                .filter(k -> !KEYS.contains(k))
                .forEach(k -> problems.add(new FieldProblem(k, "unknown", "Not an AI setting.")));
        boolean wasOn = Boolean.TRUE.equals(current.get(ENABLED));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ENABLED, wasOn);
        out.put(TOKEN_CAP, number(current.get(TOKEN_CAP), properties.tenantDailyTokens()));
        out.put(COST_CAP, number(current.get(COST_CAP), properties.tenantDailyCostMicro()));
        for (String key : List.of(ACK_BY, ACK_AT, ACK_VERSION)) {
            if (current.get(key) instanceof String s) {
                out.put(key, s);
            }
        }

        if (proposed.containsKey(ENABLED) && !(proposed.get(ENABLED) instanceof Boolean)) {
            problems.add(new FieldProblem(ENABLED, "invalid", "Use true or false."));
        }
        cap(proposed, TOKEN_CAP, properties.platformDailyTokens(), problems).ifPresent(v -> out.put(TOKEN_CAP, v));
        cap(proposed, COST_CAP, properties.platformDailyCostMicro(), problems).ifPresent(v -> out.put(COST_CAP, v));
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }

        boolean switchingOn = Boolean.TRUE.equals(proposed.get(ENABLED)) && !wasOn;
        boolean ackProposed = proposed.keySet().stream().anyMatch(k -> k.startsWith("acknowledged_"));
        if (switchingOn) {
            if (!allowList.allows(tenant.profile())) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "advisor_not_available",
                        "Not available",
                        "The AI assistant is not available for this business.");
            }
            acknowledgement(proposed).forEach(out::put);
        } else if (ackProposed) {
            throw ApiException.validation(List.of(new FieldProblem(
                    ACK_VERSION,
                    "invalid",
                    "The acknowledgement is recorded only when the assistant is switched on.")));
        }
        if (proposed.containsKey(ENABLED)) {
            out.put(ENABLED, proposed.get(ENABLED));
        }
        return out;
    }

    @Override
    public List<String> enabledCapabilities(Map<String, Object> normalised) {
        return List.of();
    }

    private static Map<String, Object> acknowledgement(Map<String, Object> proposed) {
        Map<String, Object> ack = new LinkedHashMap<>();
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
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "acknowledgement_required",
                    "Acknowledgement required",
                    "Switching the assistant on needs the acknowledgement of what is sent and where.");
        }
        ack.put(ACK_BY, proposed.get(ACK_BY));
        ack.put(ACK_AT, proposed.get(ACK_AT));
        ack.put(ACK_VERSION, ((String) proposed.get(ACK_VERSION)).strip());
        return ack;
    }

    private static java.util.Optional<Long> cap(
            Map<String, Object> proposed, String key, long platformCeiling, List<FieldProblem> problems) {
        if (!proposed.containsKey(key)) {
            return java.util.Optional.empty();
        }
        Object v = proposed.get(key);
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < 0) {
            problems.add(new FieldProblem(key, "invalid", "Use a whole number of 0 (stop) or more."));
            return java.util.Optional.empty();
        }
        if (n.longValue() > platformCeiling) {
            problems.add(new FieldProblem(key, "above_platform_ceiling", "Above the platform's daily ceiling."));
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(n.longValue());
    }

    private static long number(Object stored, long fallback) {
        return stored instanceof Number n ? n.longValue() : fallback;
    }
}
