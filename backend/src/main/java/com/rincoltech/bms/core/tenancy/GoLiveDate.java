package com.rincoltech.bms.core.tenancy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The business's go-live date (ADR-071): the day the app became its register. An existing loan is brought in only with a
 * disbursement date before it, so the bring-in cannot backdate new lending. The Owner sets it in the tenant settings
 * ({@code PATCH /settings}); the platform operator sets it for a business through this port; until either does, the
 * business date of the first administrator sign-in is stored as the default. Runs in the caller's transaction, bound to
 * the tenant.
 */
public interface GoLiveDate {

    /** The stored date and how it came: {@code set} (by the Owner or the operator) or {@code first_admin_sign_in}. */
    record Live(LocalDate date, String source) {}

    /** The stored date, or empty while no administrator has signed in and nobody has set one. */
    Optional<Live> get();

    /** The operator's change, today or earlier, audited in the tenant's log as the operator's act with the reason. */
    void setByOperator(LocalDate date, UUID platformUserId, String reason);

    /**
     * An administrator signed in at {@code at}: when no date is stored yet, its business date is stored as the default,
     * so the date never moves with later sign-ins or role changes. Nothing otherwise.
     */
    void adminSignedIn(UUID userId, Instant at);
}
