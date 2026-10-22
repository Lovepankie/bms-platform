package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.internal.RunStore.Run;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.Principal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who may fetch a report file (FR-DOC-03), judged when the file is asked for and not when the run was queued: the
 * requester alone, while reports are still switched on for the tenant (the business data export of ADR-074 needs no switch), while they still hold the report's permission,
 * and while their branches and officers still cover the scope the file was made under (a user moved from all branches
 * to one cannot keep downloading the tenant-wide file for its 30 days). Anyone else, and every one of these refusals,
 * gets 404: the run is not theirs to know about. A holder of {@code core.audit.read} may read a run's status but not its
 * file. After all of that, an expired file answers 410 {@code report_expired}, so a stranger learns nothing from the
 * status code.
 */
@Component
class ReportDocumentAccess implements DocumentAccess {

    private final RunStore store;
    private final ReportRegistry registry;
    private final BusinessClock clock;
    private final TenantSettings settings;

    ReportDocumentAccess(
            RunStore store,
            ReportRegistry registry,
            BusinessClock clock,
            @org.springframework.context.annotation.Lazy TenantSettings settings) {
        // Lazy: the settings service uses the documents module, which collects every DocumentAccess, this one included.
        this.store = store;
        this.registry = registry;
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public String subjectType() {
        return RunExecutor.SUBJECT;
    }

    @Override
    public boolean hideDenial() {
        return true;
    }

    @Override
    public boolean canRead(Principal principal, UUID subjectId) {
        Run run = store.find(subjectId).orElse(null);
        if (run == null || !run.requestedBy().equals(principal.userId())) {
            return false;
        }
        Optional<ReportDefinition> def = registry.find(run.reportKey());
        if (def.isEmpty() || !principal.hasPermission(def.get().permission())) {
            return false;
        }
        // The business's own copy of its data is fetched whatever the reports switch says (ADR-074).
        if (!def.get().everyBusiness() && !settings.reportsEnabled()) {
            return false;
        }
        if (!def.get().stillAllowed(new ReportDefinition.Prepared(run.params(), run.scope(), run.asOf()))) {
            return false;
        }
        if (run.filesDeletedAt() != null
                || (run.expiresAt() != null && !clock.now().isBefore(run.expiresAt()))) {
            throw new ApiException(
                    HttpStatus.GONE,
                    "report_expired",
                    "The report has expired",
                    "Report files are kept for 30 days. Run the report again.");
        }
        return true;
    }
}
