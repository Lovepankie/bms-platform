package com.rincoltech.bms.retail.manifest;

import com.rincoltech.bms.core.tenancy.ModuleSettingsGroup;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The tenant settings group {@code retail.capabilities} (ADR-033, docs/specs/trade-capabilities.md
 * section 1): eight booleans, absent meaning off, and the profile {@code shop} or {@code trade}.
 * A profile is a preset that writes a bundle of the flags in one change; the module reads the
 * flags and never the profile. The core stores the group and calls this class to validate it.
 */
@Component
class RetailCapabilityGroup implements ModuleSettingsGroup {

    static final String PROFILE = "profile";
    static final List<String> CAPABILITIES = List.of(
            "branch_roles",
            "units",
            "price_lists",
            "customer_accounts",
            "documents",
            "purchasing",
            "transfer_receipt",
            "returns");
    /** What choosing the trade profile switches on; documents and purchasing stay as they are. */
    static final Set<String> TRADE_PRESET =
            Set.of("branch_roles", "units", "price_lists", "customer_accounts", "returns", "transfer_receipt");

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
        out.put(PROFILE, current.get(PROFILE) instanceof String p && isProfile(p) ? p : "shop");
        for (String key : CAPABILITIES) {
            out.put(key, Boolean.TRUE.equals(current.get(key)));
        }
        List<FieldProblem> problems = new ArrayList<>();
        proposed.forEach((key, value) -> {
            if (!key.equals(PROFILE) && !CAPABILITIES.contains(key)) {
                problems.add(new FieldProblem(key, "unknown", "Not a retail capability."));
            } else if (key.equals(PROFILE) && !(value instanceof String p && isProfile(p))) {
                problems.add(new FieldProblem(PROFILE, "invalid", "Use shop or trade."));
            } else if (!key.equals(PROFILE) && !(value instanceof Boolean)) {
                problems.add(new FieldProblem(key, "invalid", "Use true or false."));
            }
        });
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        if (proposed.get(PROFILE) instanceof String profile) {
            out.put(PROFILE, profile);
            for (String key : CAPABILITIES) {
                if (profile.equals("shop")) {
                    out.put(key, false);
                } else if (TRADE_PRESET.contains(key)) {
                    out.put(key, true);
                }
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
        return CAPABILITIES.stream()
                .filter(k -> Boolean.TRUE.equals(normalised.get(k)))
                .sorted()
                .toList();
    }

    private static boolean isProfile(String p) {
        return p.equals("shop") || p.equals("trade");
    }
}
