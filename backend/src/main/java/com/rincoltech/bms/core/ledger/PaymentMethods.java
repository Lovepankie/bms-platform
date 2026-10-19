package com.rincoltech.bms.core.ledger;

import java.util.UUID;

/**
 * FR-GL-08: the ledger account each payment method posts to. A branch's own mapping wins over the
 * tenant's; a method the tenant never mapped posts to its seeded account (ADR-026), so a tenant
 * that never opens the mapping keeps working. A method switched off, or one whose account is not
 * postable and active, is refused with {@code payment_method_unmapped} before anything is written.
 */
public interface PaymentMethods {

    /**
     * The account to post a payment by this method to, as an account reference that
     * {@link LedgerAccounts#byRef} resolves: a {@code system_key}, or {@code gl:<account id>}.
     *
     * @param branchId the branch the money moves at, or {@code null} for the tenant's mapping
     */
    String accountRef(String methodKey, UUID branchId);

    /**
     * As {@link #accountRef}, but empty instead of refusing when the method is unmapped, switched off or its account is
     * not postable. For a caller that has a fallback and must not mark its transaction rollback-only by a refusal.
     */
    java.util.Optional<String> accountRefIfMapped(String methodKey, UUID branchId);
}
