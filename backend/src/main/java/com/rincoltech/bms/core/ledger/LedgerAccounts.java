package com.rincoltech.bms.core.ledger;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up the bound tenant's accounts by {@code system_key} (chapter 6 sections 6.6.2 and 6.11.1),
 * so a vertical's posting rules name accounts by role, never by code or id.
 */
public interface LedgerAccounts {

    /** The postable, active account with this key, if the tenant has one. */
    Optional<Account> bySystemKey(String systemKey);

    record Account(UUID id, String code, String currency) {}
}
