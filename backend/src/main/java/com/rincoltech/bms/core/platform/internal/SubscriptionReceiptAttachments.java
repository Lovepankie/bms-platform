package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.EmailAttachments;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The receipt a subscription payment's email carries (issue #480, ADR-075), rendered when the email is sent from the
 * receipt row of the outbox row's own business, so the PDF is the one the Owner's page gives.
 */
@Component
class SubscriptionReceiptAttachments implements EmailAttachments {

    private final TenantJobs tenantJobs;
    private final BillingService billing;

    SubscriptionReceiptAttachments(TenantJobs tenantJobs, BillingService billing) {
        this.tenantJobs = tenantJobs;
        this.billing = billing;
    }

    @Override
    public String kind() {
        return BillingService.RECEIPT_KIND;
    }

    @Override
    public Optional<Attachment> read(UUID tenantId, String id) {
        UUID paymentId;
        try {
            paymentId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return tenantJobs.callAs(
                tenantId,
                () -> billing.receiptPdf(paymentId)
                        .map(r -> new Attachment(r.fileName(), "application/pdf", r.bytes())));
    }
}
