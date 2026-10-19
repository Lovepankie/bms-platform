package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.savings.SavingsServicing;
import com.rincoltech.bms.lending.savings.internal.SavingsRepository.AccountRow;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The payment purpose {@code lending.savings_deposit} (ADR-035, spec 3.4, issue #219): what the payments module asks of a
 * savings account. It owns no rules of its own. {@link #describe} says whether the account can take a deposit (active or
 * dormant, in the tenant's currency, and for a first deposit at least the product's minimum opening balance) and which
 * branch the money belongs to; {@link #book} is {@link SavingsServicing#deposit} with the method {@code gateway}, so a paid
 * mobile money order is numbered, receipted, posted and texted exactly like a cashier's deposit, and the same call a second
 * time with the same order tracking id (Booking#providerReference) returns the first transaction.
 *
 * <p>The account must still be depositable when the money arrives: a closed, frozen or otherwise unfit account is refused in
 * {@link #book}, and the money is held as an unallocated receipt (spec 3.7), never put on an account staff have stopped. A
 * payment dated before the account opened or on or before the last end of day written cannot be dated so: it is refused
 * {@code before_last_repayment} (the handler's word for "retry dated today") on the first try and booked dated today.
 *
 * <p>A business refusal of the servicing code is handed to the payments module as a {@link PurposeRefusal}, which never
 * strands the money (ADR-035 decision 14). Any other exception is a technical failure and propagates.
 */
@Component
class SavingsDepositPurpose implements PurposeHandler {

    static final String PURPOSE = "lending.savings_deposit";
    static final String METHOD = "gateway";

    /** The business refusals of the cash route that become an unallocated receipt; any other failure is retried. */
    private static final Set<String> REFUSALS = Set.of(
            "account_closed",
            "account_frozen",
            "account_dormant",
            "value_date_in_future",
            "before_opening",
            "value_date_closed",
            "below_minimum_opening",
            "payment_method_unmapped",
            "invalid_status_transition",
            "duplicate_reference",
            "period_closed");

    /** The refusals a later date cures: the booker retries once dated today (a payment cannot be dated into a written day). */
    private static final Set<String> REDATE = Set.of("before_opening", "value_date_closed");

    private final SavingsRepository repo;
    private final SavingsServicing servicing;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    SavingsDepositPurpose(
            SavingsRepository repo, SavingsServicing servicing, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.servicing = servicing;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Override
    public String purpose() {
        return PURPOSE;
    }

    @Override
    public Optional<UUID> branchOf(UUID subjectId) {
        return repo.account(subjectId).map(AccountRow::branchId);
    }

    @Override
    public Subject describe(UUID subjectId, long amountMinor, String currency) throws PurposeRefusal {
        AccountRow a = repo.account(subjectId).orElseThrow(() -> new PurposeRefusal("subject_not_payable"));
        if (!depositable(a) || !a.currency().equals(currency)) {
            throw new PurposeRefusal("subject_not_payable");
        }
        if (a.txnCount() == 0 && amountMinor < a.minOpeningBalanceMinor()) {
            throw new PurposeRefusal("below_minimum_opening");
        }
        return new Subject("savings_account", a.branchId());
    }

    @Override
    public UUID book(Booking booking) throws PurposeRefusal {
        AccountRow a = repo.lock(booking.subjectId()).orElseThrow(() -> new PurposeRefusal("subject_not_payable"));
        if (!a.currency().equals(booking.currency())) {
            throw new PurposeRefusal("currency_mismatch");
        }
        if (!depositable(a)) {
            // Closed, frozen or anything else: the money goes to unallocated receipts (spec 3.7), never onto an account
            // staff have stopped.
            throw new PurposeRefusal("closed".equals(a.status()) ? "account_closed" : "account_frozen");
        }
        // The order tracking id is unique per intent; a provider's short confirmation code is not (two genuine payments
        // can carry one), so it is the reference the port is idempotent on. The code is on the intent's audit row.
        String reference = booking.providerReference();
        try {
            return servicing.deposit(
                    booking.subjectId(),
                    booking.amountMinor(),
                    booking.valueDate(),
                    METHOD,
                    booking.initiatedBy(),
                    reference,
                    "gateway",
                    true);
        } catch (ApiException e) {
            if (REDATE.contains(e.code())
                    && booking.valueDate().isBefore(clock.today(tenant.profile().timezone()))) {
                // Dated into a day whose balances are written (or before the account opened): the booker retries it
                // dated today.
                throw new PurposeRefusal(BEFORE_LAST_REPAYMENT);
            }
            if (REFUSALS.contains(e.code())) {
                throw new PurposeRefusal(e.code());
            }
            throw e;
        }
    }

    /** Active and dormant accounts take a deposit; a frozen or closed account does not (spec 3.7). */
    private static boolean depositable(AccountRow a) {
        return "active".equals(a.status()) || "dormant".equals(a.status());
    }
}
