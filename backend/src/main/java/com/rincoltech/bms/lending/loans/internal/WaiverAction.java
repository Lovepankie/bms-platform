package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.approvals.RequestSupersededException;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code charge_waiver} (chapter 8 section 8.4; FR-ARR-04): requested with
 * {@code lending.charges.waive_request}, decided with {@code lending.charges.waive_approve}; below
 * the tenant's threshold it takes effect at once. The subject is the loan, so a money event on it
 * between request and decision supersedes the request.
 *
 * <p>A waiver of an item's fees or interest (#265) carries {@link #ITEM_NO} and {@link #COMPONENT} instead of a charge:
 * its checker also needs {@link #FEE_INTEREST}, the accountant's permission, and a refusal on approval (the amount is
 * no longer due) supersedes the request, as ADR-091 decision 11 does for the actions the approval rules govern.
 */
@Component
class WaiverAction implements ApprovalAction {

    static final String TYPE = "charge_waiver";
    static final String CHARGE = "charge_id";
    static final String AMOUNT = "amount_minor";
    static final String REASON = "reason";
    static final String ITEM_NO = "item_no";
    static final String COMPONENT = "component";
    static final String FEE_INTEREST = "lending.charges.waive_fee_interest";

    /** Refusals worth trying again, which leave the request pending (ADR-091 decision 11). */
    private static final java.util.Set<String> TRANSIENT =
            java.util.Set.of("version_conflict", "idempotency_in_progress", "lock_timeout");

    private final LoanRepository loans;
    private final LoanServicer servicer;

    WaiverAction(LoanRepository loans, LoanServicer servicer) {
        this.loans = loans;
        this.servicer = servicer;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return LoanServicer.SUBJECT;
    }

    @Override
    public String makerPermission() {
        return "lending.charges.waive_request";
    }

    @Override
    public String checkerPermission() {
        return "lending.charges.waive_approve";
    }

    @Override
    public boolean thresholdApplies() {
        return true;
    }

    /** The loan's own officer may not decide a waiver on it (they collect from the borrower). */
    @Override
    public java.util.Set<UUID> conflictedDeciders(UUID subjectId, java.util.Map<String, Object> payload) {
        return loans.find(subjectId)
                .map(l -> java.util.Set.of(l.officerUserId()))
                .orElse(java.util.Set.of());
    }

    @Override
    public Optional<String> extraCheckerPermission(java.util.Map<String, Object> payload) {
        return payload.containsKey(COMPONENT) ? Optional.of(FEE_INTEREST) : Optional.empty();
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return loans.find(subjectId).map(Loan::version);
    }

    @Override
    public void execute(Execution e) {
        if (e.payload().containsKey(COMPONENT)) {
            try {
                servicer.waiveItem(
                        e.subjectId(),
                        ((Number) e.payload().get(ITEM_NO)).intValue(),
                        (String) e.payload().get(COMPONENT),
                        ((Number) e.payload().get(AMOUNT)).longValue(),
                        (String) e.payload().get(REASON),
                        e.approvalId(),
                        e.makerId(),
                        e.checkerId());
            } catch (ApiException x) {
                if (e.approvalId() == null || !x.status().is4xxClientError() || transientRefusal(x)) {
                    throw x;
                }
                // What was asked can no longer be waived as it stands: the request goes stale with the reason.
                throw new RequestSupersededException(x.code(), x.title(), x.getMessage(), x.extensions());
            }
            return;
        }
        servicer.waive(
                UUID.fromString((String) e.payload().get(CHARGE)),
                ((Number) e.payload().get(AMOUNT)).longValue(),
                (String) e.payload().get(REASON),
                e.approvalId(),
                e.makerId(),
                e.checkerId());
    }

    private static boolean transientRefusal(ApiException x) {
        return x.status() == org.springframework.http.HttpStatus.TOO_MANY_REQUESTS || TRANSIENT.contains(x.code());
    }
}
