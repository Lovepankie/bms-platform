package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.ModuleSetUp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #618 (ADR-098): a business that gets the retail module starts with the units most shops count in, so its first item
 * can be saved without a trip to Units. A name the business already has, in any case, is skipped, so switching retail off and
 * on again adds only what is missing. Migration V109 gives the same names to every retail business that had no unit.
 */
@Component
class DefaultUnits implements ModuleSetUp {

    static final List<String> NAMES = List.of("Piece", "Pair", "Set", "Box", "Pack", "Kg", "Litre", "Metre");

    private final CatalogueRepository repo;
    private final AuditLog audit;

    DefaultUnits(CatalogueRepository repo, AuditLog audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Override
    public String moduleKey() {
        return "retail";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void switchedOn() {
        Set<String> existing = repo.unitIdsByName().keySet();
        for (String name : NAMES) {
            if (existing.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            UUID id = UUID.randomUUID();
            repo.insertUnit(id, name, null);
            audit.record(
                    AuditLog.Entry.created("retail.unit.created", "retail.unit", id, null, Map.of("name", name)),
                    null,
                    "system");
        }
    }
}
