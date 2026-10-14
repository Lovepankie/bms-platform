package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.kernel.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * The reports the verticals registered (chapter 5 section 5.4.3): a map from key to definition, built once at
 * start. The catalogue a user sees is what is both in a module their tenant has on and under a permission they
 * hold (FR-RPT-01); a run is accepted on the same two tests.
 */
@Component
class ReportRegistry {

    private final Map<String, ReportDefinition> byKey = new TreeMap<>();
    private final TenantModules modules;

    ReportRegistry(List<ReportDefinition> definitions, TenantModules modules) {
        Set<String> seen = new HashSet<>();
        for (ReportDefinition d : definitions) {
            if (!seen.add(d.key())) {
                throw new IllegalStateException("Two reports are registered as " + d.key());
            }
            if (!d.formats().stream().allMatch(f -> f.equals("json") || f.equals("csv"))) {
                throw new IllegalStateException("Report " + d.key() + " offers a format this build cannot render.");
            }
            byKey.put(d.key(), d);
        }
        this.modules = modules;
    }

    Optional<ReportDefinition> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** The report when it exists and its module is on for the bound tenant. */
    Optional<ReportDefinition> findInTenant(String key) {
        return find(key).filter(d -> modules.isEnabled(d.module()));
    }

    /** What the principal may run in the bound tenant, by key. */
    List<ReportDefinition> available(Principal principal) {
        return byKey.values().stream()
                .filter(d -> modules.isEnabled(d.module()))
                .filter(d -> principal.hasPermission(d.permission()))
                .toList();
    }
}
