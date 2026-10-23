package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code loan_bring_in_reversal} (chapter 8 section 8.4; FR-EXL-08, ADR-071 decision 9): an approved bring-in taken back,
 * requested with {@code lending.loans.bring_in} and decided with {@code lending.disbursements.authorise}, never by the
 * maker and never below a threshold. The subject is the loan; any money event on it between request and decision makes
 * the request stale, and the run refuses a loan that has had a transaction in the app or a penalty charge.
 */
@Component
class BringInReversalAction implements ApprovalAction {

    static final String TYPE = "loan_bring_in_reversal";
    static final String REASON = "reason";

    private final LoanRepository loans;
    private final ExistingLoanService existing;

    /** The service is lazy: it requests approvals, and the approvals hold every action, this one included. */
    BringInReversalAction(LoanRepository loans, @Lazy ExistingLoanService existing) {
        this.loans = loans;
        this.existing = existing;
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
        return ExistingLoanService.PERMISSION;
    }

    @Override
    public String checkerPermission() {
        return "lending.disbursements.authorise";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return loans.find(subjectId).map(Loan::version);
    }

    @Override
    public void execute(Execution e) {
        existing.reverse(e.subjectId(), (String) e.payload().get(REASON), e.approvalId(), e.makerId(), e.checkerId());
    }
}
