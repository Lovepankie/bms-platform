package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.LoanServicing;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The payment purpose {@code lending.loan_repayment} (ADR-035, spec 3.4, issue #216): what the payments module asks of
 * a loan. It owns no rules of its own. {@link #describe} says whether the loan can take this amount (active, in the
 * tenant's currency, no more than the payoff quote of today, spec 3.7) and which branch the money belongs to;
 * {@link #book} is {@link LoanServicing#recordRepayment} with the method {@code gateway}, so a paid mobile money order
 * is allocated, numbered, receipted and posted exactly like a cashier's repayment, and the same call a second time with
 * the same order tracking id (Booking#providerReference) returns the first transaction.
 *
 * <p>A business refusal of the servicing code (a closed loan, a value date before the loan's last repayment, a method
 * with no account) is handed to the payments module as a {@link PurposeRefusal}, which never strands the money: it
 * retries {@code before_last_repayment} dated today and posts anything else to unallocated receipts (ADR-035 decision
 * 14). Any other exception is a technical failure and propagates.
 */
@Component
class LoanRepaymentPurpose implements PurposeHandler {

    static final String PURPOSE = "lending.loan_repayment";
    static final String METHOD = "gateway";

    /** The business refusals of the cash route that become an unallocated receipt; any other failure is retried. */
    private static final java.util.Set<String> REFUSALS = java.util.Set.of(
            "before_last_repayment",
            "value_date_in_future",
            "before_disbursement",
            "payment_method_unmapped",
            "invalid_status_transition",
            "duplicate_reference",
            "period_closed");

    private final LoanRepository loans;
    private final LoanServicer servicer;
    private final ServicingRepository repo;
    private final LoanServicing servicing;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    LoanRepaymentPurpose(
            LoanRepository loans,
            LoanServicer servicer,
            ServicingRepository repo,
            LoanServicing servicing,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.loans = loans;
        this.servicer = servicer;
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
    public java.util.Optional<UUID> branchOf(UUID subjectId) {
        return loans.find(subjectId).map(Loan::branchId);
    }

    @Override
    public Subject describe(UUID subjectId, long amountMinor, String currency) throws PurposeRefusal {
        Loan loan = loans.find(subjectId).orElseThrow(() -> new PurposeRefusal("subject_not_payable"));
        if (!"active".equals(loan.status()) || !loan.currency().equals(currency)) {
            throw new PurposeRefusal("subject_not_payable");
        }
        long payoff =
                servicer.quote(loan, clock.today(tenant.profile().timezone())).totalMinor();
        if (amountMinor > payoff) {
            throw new PurposeRefusal("exceeds_payoff");
        }
        return new Subject("loan", loan.branchId());
    }

    @Override
    public java.util.OptionalLong payoffMinor(UUID subjectId) {
        return loans.find(subjectId)
                .filter(loan -> "active".equals(loan.status()))
                .map(loan -> java.util.OptionalLong.of(
                        servicer.quote(loan, clock.today(tenant.profile().timezone()))
                                .totalMinor()))
                .orElse(java.util.OptionalLong.empty());
    }

    @Override
    public java.util.Optional<String> label(UUID subjectId) {
        return loans.find(subjectId).map(loan -> "loan " + loan.loanNo());
    }

    @Override
    public java.util.Optional<Integer> subjectVersionOfTransaction(UUID transactionId) {
        return repo.txn(transactionId).flatMap(t -> loans.find(t.loanId())).map(Loan::version);
    }

    @Override
    public java.util.List<ManualReceipt> manualReceiptsWithReference(
            java.util.Collection<String> references, java.util.Collection<UUID> excluding) {
        return repo.manualWithReference(references, excluding);
    }

    @Override
    public java.util.Optional<String> reversalCheckerPermission() {
        return java.util.Optional.of("lending.repayments.reverse_approve");
    }

    @Override
    public void reverseBooked(
            UUID transactionId,
            String reason,
            UUID approvalId,
            UUID makerId,
            UUID checkerId,
            java.util.Set<String> listedCautions) {
        servicer.reverse(transactionId, reason, approvalId, makerId, checkerId, listedCautions, true);
    }

    @Override
    public java.util.List<String> reversalCautions(UUID transactionId) {
        return repo.txn(transactionId)
                .flatMap(t -> loans.find(t.loanId()).map(l -> servicer.unrestorable(l, t)))
                .orElse(java.util.List.of());
    }

    @Override
    public java.util.Optional<String> cautionCheckerPermission() {
        // The authority that lets collateral go, as for a staff reversal that reopens a loan without its pledges
        // (#176).
        return java.util.Optional.of(ReversalAction.UNSECURED_REOPEN_PERMISSION);
    }

    @Override
    public UUID book(Booking booking) throws PurposeRefusal {
        Loan loan = loans.find(booking.subjectId()).orElseThrow(() -> new PurposeRefusal("subject_not_payable"));
        if (!loan.currency().equals(booking.currency())) {
            throw new PurposeRefusal("currency_mismatch");
        }
        if (!"active".equals(loan.status())) {
            // Closed, written off or anything else: the money goes to unallocated receipts (spec 3.14), never a
            // recovery.
            throw new PurposeRefusal("invalid_status_transition");
        }
        // The order tracking id is unique per intent; a provider's short confirmation code is not (two genuine payments
        // can carry one), so it is the reference the port is idempotent on. The code is on the intent's audit row.
        String reference = booking.providerReference();
        try {
            return servicing.recordRepayment(
                    booking.subjectId(),
                    booking.amountMinor(),
                    booking.valueDate(),
                    METHOD,
                    booking.initiatedBy(),
                    reference,
                    "gateway",
                    true);
        } catch (ApiException e) {
            if (REFUSALS.contains(e.code())) {
                throw new PurposeRefusal(e.code());
            }
            throw e;
        }
    }
}
