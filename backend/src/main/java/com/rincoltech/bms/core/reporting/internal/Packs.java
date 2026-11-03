package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportDefinition.Prepared;
import com.rincoltech.bms.core.reporting.ReportPack;
import com.rincoltech.bms.kernel.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The request and download sides of a pack run (ADR-038 decision 4, issue #229). A pack's frozen scope holds its own
 * scope and, in print order, each part's prepared request: {@code {"pack": {...}, "parts": [{"key", "params", "scope",
 * "as_of"}, ...]}}. Each part is prepared by its own definition in the request, with the requester signed in, so a part
 * in a pack freezes exactly what it freezes when run alone (branches, the requester's own loans, the branches where names
 * and phones may be printed), and the job reads only what was frozen.
 */
@Component
class Packs {

    /** One part of a pack run: its definition and what it was prepared with. */
    record Part(int number, ReportDefinition def, Prepared prepared) {}

    private final ReportRegistry registry;

    Packs(ReportRegistry registry) {
        this.registry = registry;
    }

    /**
     * Request time: prepares every part from the pack's normalised request and returns the run to store. A part whose
     * module is off for the tenant refuses the pack, as a missing permission does.
     */
    Prepared prepare(ReportPack pack, Prepared own) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (ReportDefinition part : registry.parts(pack)) {
            if (registry.findInTenant(part.key()).isEmpty()) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND, "report_not_found", "Report not found", "No such report is available.");
            }
            Prepared p = part.prepare(pack.partParams(part.key(), own));
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("key", part.key());
            stored.put("params", p.params());
            stored.put("scope", p.scope());
            stored.put("as_of", p.asOf().toString());
            parts.add(stored);
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("pack", own.scope());
        scope.put("parts", parts);
        return new Prepared(own.params(), scope, own.asOf());
    }

    /** The pack's own request, as its definition prepared it (its scope without the parts). */
    @SuppressWarnings("unchecked")
    static Prepared own(Prepared run) {
        Object scope = run.scope().get("pack");
        return new Prepared(
                run.params(), scope instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), run.asOf());
    }

    /** The parts of a stored pack run, in print order; a part whose report is no longer offered fails the run. */
    @SuppressWarnings("unchecked")
    List<Part> parts(Prepared run) {
        List<Part> out = new ArrayList<>();
        Object stored = run.scope().get("parts");
        if (!(stored instanceof List<?> list) || list.isEmpty()) {
            throw new com.rincoltech.bms.core.reporting.ReportFailure("report_unavailable", "The pack has no parts.");
        }
        for (Object item : list) {
            Map<String, Object> m = (Map<String, Object>) item;
            ReportDefinition def = registry.find(String.valueOf(m.get("key")))
                    .orElseThrow(() -> new com.rincoltech.bms.core.reporting.ReportFailure(
                            "report_unavailable", "A report of the pack is no longer offered."));
            out.add(new Part(
                    out.size() + 1,
                    def,
                    new Prepared(
                            (Map<String, Object>) m.get("params"),
                            (Map<String, Object>) m.get("scope"),
                            LocalDate.parse(String.valueOf(m.get("as_of"))))));
        }
        return out;
    }

    /** Download time: the pack's own check and every part's, each on what it froze. */
    boolean stillAllowed(ReportPack pack, Prepared run) {
        if (!pack.stillAllowed(own(run))) {
            return false;
        }
        List<Part> parts;
        try {
            parts = parts(run);
        } catch (com.rincoltech.bms.core.reporting.ReportFailure e) {
            return false;
        }
        for (Part part : parts) {
            if (!part.def().stillAllowed(part.prepared())) {
                return false;
            }
        }
        return true;
    }
}
