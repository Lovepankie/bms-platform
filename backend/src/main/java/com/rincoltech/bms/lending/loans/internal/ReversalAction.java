package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code repayment_reversal} (chapter 8 section 8.4; FR-REP-05): requested with
 * {@code lending.repayments.reverse_request}, decided with {@code lending.repayments.reverse_approve},
 * never below a threshold. The subject is the transaction; its version is its loan's, so any money
 * event on the loan between request and decision makes the request stale (FR-APR-08).
 */
@Component
class ReversalAction implements ApprovalAction {

    static final String TYPE = "repayment_reversal";
    static final String REASON = "reason";

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

    @Override
    public void execute(Execution e) {
        servicer.reverse(e.subjectId(), (String) e.payload().get(REASON), e.approvalId(), e.makerId(), e.checkerId());
    }
}
