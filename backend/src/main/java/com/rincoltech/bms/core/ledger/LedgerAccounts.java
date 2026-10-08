package com.rincoltech.bms.core.ledger;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Looks up the bound tenant's accounts by {@code system_key} (chapter 6 sections 6.6.2 and 6.11.1),
 * so a vertical's posting rules name accounts by role, never by code or id.
 */
public interface LedgerAccounts {

    /** The postable, active account with this key, if the tenant has one. */
    Optional<Account> bySystemKey(String systemKey);

    /**
     * The balance (debits less credits, in minor units) of the account with this key per branch, over
     * entries dated on or before {@code asOf}. Branches without entries are absent.
     */
    Map<UUID, Long> balanceByBranch(String systemKey, LocalDate asOf);

    /**
     * The net movement (debits less credits, in minor units) of the account with this key in one
     * branch for each entry date from {@code from} to {@code to}. Dates without entries are absent.
     */
    Map<LocalDate, Long> movementByDay(String systemKey, UUID branchId, LocalDate from, LocalDate to);

    record Account(UUID id, String code, String currency) {}
}
