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
     * An account by reference: {@code gl:<account id>} names one account directly (a payment
     * method's mapping, FR-GL-08); anything else is a {@code system_key}. Postable and active only.
     */
    Optional<Account> byRef(String ref);

    /**
     * The account of an entry's one debit line, as a {@link #byRef} reference: where a payment's
     * money went in. A correction credits that account, not the method's mapping now, so a remap
     * between a payment and its reversal cannot split them across two accounts.
     */
    Optional<String> debitedAccountRef(UUID entryId);

    /**
     * The balance (debits less credits, in minor units) of the account with this reference (a {@code system_key} or
     * {@code gl:<account id>}, as {@link #byRef}) per branch, over entries dated on or before {@code asOf}. Branches
     * without entries are absent.
     */
    Map<UUID, Long> balanceByBranch(String ref, LocalDate asOf);

    /**
     * The net movement (debits less credits, in minor units) of the account with this reference (as {@link
     * #balanceByBranch}) in one branch for each entry date from {@code from} to {@code to}. Dates without entries
     * are absent.
     */
    Map<LocalDate, Long> movementByDay(String ref, UUID branchId, LocalDate from, LocalDate to);

    /**
     * The balance (debits less credits, in minor units) of the account with this key over the entries
     * (and their reversals) that one source posted, whatever the branch: the entries whose {@code
     * source_module} and {@code source_type} are given. For an account two verticals share (the
     * inter-branch clearing account), this is how a module reads only its own part of it.
     */
    long balanceBySource(String systemKey, String sourceModule, String sourceType);

    record Account(UUID id, String code, String currency) {}
}
