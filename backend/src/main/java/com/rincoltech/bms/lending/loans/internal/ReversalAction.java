package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code repayment_reversal} (chapter 8 section 8.4; FR-REP-05): requested with
 * {@code lending.repayments.reverse_request}, decided with {@code lending.repayments.reverse_approve},
 * never below a threshold. The subject is the transaction; its version is its loan's, so any money
 * event on the loan between request and decision makes the request stale (FR-APR-08).
 *
 * <p>A request that would reopen a closed loan without some of its pledges (#176) carries
 * {@link #UNSECURED_REOPEN} and the items in {@link #NOT_RESTORED}. Its approver also needs
 * {@link #UNSECURED_REOPEN_PERMISSION} ({@link #extraCheckerPermission}), the seeded authority that
 * releases collateral (tenant administrator and branch manager, not the accountant), and the run
 * ends the request as stale when it would leave out an item the request did not list.
 */
@Component
class ReversalAction implements ApprovalAction {

    static final String TYPE = "repayment_reversal";
    static final String REASON = "reason";
    static final String UNSECURED_REOPEN = "reopen_would_be_unsecured";
    static final String NOT_RESTORED = "collateral_not_restored_on_reopen";

    /** Existing seeded permission (chapter 8): who may let a loan go without its collateral. */
    static final String UNSECURED_REOPEN_PERMISSION = "lending.collateral.release_approve";

    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final LoanServicer servicer;

    ReversalAction(LoanRepository loans, ServicingRepository repo, LoanServicer servicer) {
        this.loans = loans;
        this.repo = repo;
        this.servicer = servicer;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "lending.loan_transaction";
    }

    @Override
    public String makerPermission() {
        return "lending.repayments.reverse_request";
    }

    @Override
    public String checkerPermission() {
        return "lending.repayments.reverse_approve";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.txn(subjectId).flatMap(t -> loans.find(t.loanId())).map(Loan::version);
    }

    /** The approver of an unsecured reopen also holds the right to let collateral go; the accountant does not. */
    @Override
    public Optional<String> extraCheckerPermission(Map<String, Object> payload) {
        return Boolean.TRUE.equals(payload.get(UNSECURED_REOPEN))
                ? Optional.of(UNSECURED_REOPEN_PERMISSION)
                : Optional.empty();
    }

    @Override
    public void execute(Execution e) {
        Set<String> listed = new HashSet<>();
        if (e.payload().get(NOT_RESTORED) instanceof List<?> items) {
            items.forEach(i -> listed.add(String.valueOf(i)));
        }
        servicer.reverse(
                e.subjectId(), (String) e.payload().get(REASON), e.approvalId(), e.makerId(), e.checkerId(), listed);
    }
}
