package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.EmailAttachments;
import com.rincoltech.bms.core.reporting.internal.RunStore.Run;
import com.rincoltech.bms.kernel.BusinessClock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The workbook a weekly data export email carries (ADR-074), read when the email is sent: only a ready weekly run of
 * {@code core.data_export} of the row's own business whose file has not expired, and only up to the attachment limit, so the
 * sender never holds a large file in memory.
 */
@Component
class DataExportAttachments implements EmailAttachments {

    static final String KIND = DataExportReport.KEY;

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final TenantJobs tenantJobs;
    private final RunStore store;
    private final Documents documents;
    private final BusinessClock clock;
    private final TransactionTemplate tx;
    private final long maxBytes;

    DataExportAttachments(
            TenantJobs tenantJobs,
            RunStore store,
            Documents documents,
            BusinessClock clock,
            PlatformTransactionManager transactions,
            @Value("${bms.data-export.attach-max-bytes:5242880}") long maxBytes) {
        this.tenantJobs = tenantJobs;
        this.store = store;
        this.documents = documents;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setReadOnly(true);
        this.maxBytes = maxBytes;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Optional<Attachment> read(UUID tenantId, String id) {
        UUID runId;
        try {
            runId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return tenantJobs.callAs(tenantId, () -> {
            Optional<Run> run = tx.execute(s -> store.find(runId))
                    .filter(r -> r.reportKey().equals(KIND) && r.status().equals("ready"))
                    // Only the weekly copy, which leaves out personal data (ADR-074 decision 6), is ever attached.
                    .filter(r -> DataExportReport.isWeekly(r.params()))
                    .filter(r -> r.filesDeletedAt() == null
                            && r.expiresAt() != null
                            && clock.now().isBefore(r.expiresAt()));
            if (run.isEmpty()) {
                return Optional.empty();
            }
            try {
                return documents
                        .readReportObject(run.get().documentId(), maxBytes)
                        .map(bytes ->
                                new Attachment("business-data-" + run.get().asOf() + ".xlsx", XLSX, bytes));
            } catch (IllegalArgumentException e) {
                // Larger than the limit now (it was lowered after the week was queued): empty, so the sender fails the
                // row
                // cleanly with attachment_unavailable instead of retrying it.
                return Optional.<Attachment>empty();
            }
        });
    }
}
