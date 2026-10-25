package com.rincoltech.bms.retail.manifest;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettingsGroup;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The tenant settings group {@code retail.capabilities} (ADR-033, docs/specs/trade-capabilities.md
 * section 1): eight booleans, absent meaning off, the profile (the kind of business, {@link BusinessTypes}; issue #424,
 * ADR-092), and the policy {@code credit_sale_recognition} ({@code on_sale} by default, or {@code on_payment}; ADR-043).
 * A profile is a preset that sets the switches it governs in one change; the module reads the
 * flags and never the profile. The core stores the group and calls this class to validate it.
 * It also holds the two slow mover rules of the stock health report (issue #414): {@code slow_mover_months}
 * (1 to 24, default 4) and {@code slow_mover_min_sold} (units sold in 90 days, 0 to 1,000,000, default 0 meaning off).
 */
@Component
class RetailCapabilityGroup implements ModuleSettingsGroup {

    private static final Logger log = LoggerFactory.getLogger(RetailCapabilityGroup.class);

    private final BusinessClock clock;
    private final CurrentTenant tenant;
    /** The tenants whose stored rule without a start day was already reported, so a read does not log it again and again. */
    private final Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    RetailCapabilityGroup(BusinessClock clock, CurrentTenant tenant) {
        this.clock = clock;
        this.tenant = tenant;
    }

    static final String PROFILE = "profile";
    /** How credit sales count in the reports and the daily savings (ADR-043); {@code on_sale} is the default. */
    static final String CREDIT_RECOGNITION = "credit_sale_recognition";

    static final List<String> CREDIT_RECOGNITIONS = List.of("on_sale", "on_payment");

    /**
     * The first business day the rule {@code on_payment} counts from: the business day after it was asked for, worked out here, while
     * the settings row is locked, from the business clock, and never given by the caller. Credit sales counted from before it keep counting at the sale (ADR-043). The rule
     * is one-way: it is switched on once, and the only way back is to cancel it before this day has come. Absent while the rule is
     * {@code on_sale}; a stored {@code on_payment} with no start day is no rule at all.
     */
    static final String EFFECTIVE_FROM = "credit_recognition_effective_from";

    /** The name {@code /me} lists while the rule is on_payment, so the screens can say which profit they show. */
    static final String ON_PAYMENT_FLAG = "credit_on_payment";

    /** Slow when the stock lasts more than this many months at the pace of the last 90 days (issue #414). */
    static final String SLOW_MONTHS = "slow_mover_months";

    /** Slow when fewer than this many units sold in the last 90 days; 0 switches the rule off (issue #414). */
    static final String SLOW_MIN_SOLD = "slow_mover_min_sold";

    static final int DEFAULT_SLOW_MONTHS = 4;
    static final int MAX_SLOW_MONTHS = 24;
    static final int MAX_SLOW_MIN_SOLD = 1_000_000;

    static final List<String> CAPABILITIES = List.of(
            "branch_roles",
            "units",
            "price_lists",
            "customer_accounts",
            "documents",
            "purchasing",
            "transfer_receipt",
            "returns");

    @Override
    public String moduleKey() {
        return RetailManifest.MODULE_KEY;
    }

    @Override
    public String auditAction() {
        return "retail.capabilities.changed";
    }

    @Override
    public Map<String, Object> normalise(Map<String, Object> current, Map<String, Object> proposed) {
        Map<String, Object> out = new LinkedHashMap<>();
        // A profile from before issue #424 (shop or trade) is read as the kind it became, so a row an older server
        // wrote during a
        // deploy reads the way migration V103 rewrote the others.
        String stored = current.get(PROFILE) instanceof String p ? p : null;
        out.put(PROFILE, BusinessTypes.isType(stored) ? stored : BusinessTypes.fromLegacy(stored, current));
        String mode =
                current.get(CREDIT_RECOGNITION) instanceof String c && CREDIT_RECOGNITIONS.contains(c) ? c : "on_sale";
        out.put(CREDIT_RECOGNITION, mode);
        String effective = current.get(EFFECTIVE_FROM) instanceof String d && validDate(d) ? d : null;
        if (mode.equals("on_payment") && effective == null) {
            // No start day means no rule: never guess 1970, which would count every sale ever made when the buyer pays.
            // The id comes from the thread's tenant context, no lookup: a read of a broken tenant costs nothing more.
            String who = com.rincoltech.bms.kernel.TenantContext.current()
                    .map(java.util.UUID::toString)
                    .orElse("unbound");
            if (warned.add(who)) {
                log.warn(
                        "retail.capabilities of tenant {} has credit_sale_recognition on_payment with no start day; reading it as on_sale",
                        who);
            }
            mode = "on_sale";
            out.put(CREDIT_RECOGNITION, mode);
        }
        for (String key : CAPABILITIES) {
            out.put(key, Boolean.TRUE.equals(current.get(key)));
        }
        out.put(SLOW_MONTHS, whole(current.get(SLOW_MONTHS), 1, MAX_SLOW_MONTHS, DEFAULT_SLOW_MONTHS));
        out.put(SLOW_MIN_SOLD, whole(current.get(SLOW_MIN_SOLD), 0, MAX_SLOW_MIN_SOLD, 0));
        List<FieldProblem> problems = new ArrayList<>();
        proposed.forEach((key, value) -> {
            if (key.equals(SLOW_MONTHS) || key.equals(SLOW_MIN_SOLD)) {
                int max = key.equals(SLOW_MONTHS) ? MAX_SLOW_MONTHS : MAX_SLOW_MIN_SOLD;
                int min = key.equals(SLOW_MONTHS) ? 1 : 0;
                if (whole(value, min, max, -1) < 0) {
                    problems.add(
                            new FieldProblem(key, "invalid", "Use a whole number from " + min + " to " + max + "."));
                } else {
                    out.put(key, whole(value, min, max, -1));
                }
            } else if (key.equals(CREDIT_RECOGNITION)) {
                if (!(value instanceof String c && CREDIT_RECOGNITIONS.contains(c))) {
                    problems.add(new FieldProblem(CREDIT_RECOGNITION, "invalid", "Use on_sale or on_payment."));
                }
            } else if (!key.equals(PROFILE) && !CAPABILITIES.contains(key)) {
                problems.add(new FieldProblem(key, "unknown", "Not a retail capability."));
            } else if (key.equals(PROFILE) && !(value instanceof String p && BusinessTypes.isType(p))) {
                problems.add(new FieldProblem(
                        PROFILE, "invalid", "Use shops, stores_and_shops, wholesale or wholesale_and_shops."));
            } else if (!key.equals(PROFILE) && !(value instanceof Boolean)) {
                problems.add(new FieldProblem(key, "invalid", "Use true or false."));
            }
        });
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        if (proposed.get(CREDIT_RECOGNITION) instanceof String next && !next.equals(mode)) {
            // Read here, inside the locked change, so a request that waited for the lock decides on the day it really
            // is.
            LocalDate today = clock.today(tenant.profile().timezone());
            if (next.equals("on_payment")) {
                // One way: on_payment is switched on once, and counts from the next business day.
                effective = today.plusDays(1).toString();
            } else {
                // Going back is only the cancelling of a change that has not taken effect yet; nothing else goes back.
                // This runs
                // under the settings row lock, on the stored state.
                if (effective == null || !LocalDate.parse(effective).isAfter(today)) {
                    throw ApiException.rule(
                            "credit_rule_cannot_be_switched_off",
                            "This rule cannot be switched off once it has started. Ask Rincol support.");
                }
                effective = null;
            }
            mode = next;
            out.put(CREDIT_RECOGNITION, mode);
        }
        if (effective != null) {
            out.put(EFFECTIVE_FROM, effective);
        }
        if (proposed.get(PROFILE) instanceof String profile) {
            out.put(PROFILE, profile);
            // The kind sets the switches it governs; branch roles and returns are left as they are.
            for (String key : BusinessTypes.GOVERNED) {
                out.put(key, BusinessTypes.PRESETS.get(profile).contains(key));
            }
        }
        for (String key : CAPABILITIES) {
            if (proposed.get(key) instanceof Boolean on) {
                out.put(key, on);
            }
        }
        return out;
    }

    @Override
    public List<String> enabledCapabilities(Map<String, Object> normalised) {
        List<String> out = new ArrayList<>(CAPABILITIES.stream()
                .filter(k -> Boolean.TRUE.equals(normalised.get(k)))
                .toList());
        if ("on_payment".equals(normalised.get(CREDIT_RECOGNITION))) {
            out.add(ON_PAYMENT_FLAG);
        }
        out.sort(null);
        return out;
    }

    /** {@code value} when it is a whole number from {@code min} to {@code max}, else {@code fallback}. */
    private static int whole(Object value, int min, int max, int fallback) {
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            double v = n.doubleValue();
            if (v >= min && v <= max) {
                return (int) v;
            }
        }
        return fallback;
    }

    private static boolean validDate(String d) {
        try {
            java.time.LocalDate.parse(d);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }
}
