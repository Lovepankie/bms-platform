package com.rincoltech.bms.core.tenancy;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The business's go-live date (ADR-071): the day the app became its register. An existing loan is brought in only with a
 * disbursement date before it, so the bring-in cannot backdate new lending. The Owner sets it in the tenant settings
 * ({@code PATCH /settings}); the platform operator sets it for a business through this port. Runs in the caller's
 * transaction, bound to the tenant.
 */
public interface GoLiveDate {

    /** The date the Owner or the operator set, or empty while nobody has (the caller then applies its default). */
    Optional<LocalDate> set();

    /** The operator's change, today or earlier, audited in the tenant's log as the operator's act with the reason. */
    void setByOperator(LocalDate date, UUID platformUserId, String reason);
}
