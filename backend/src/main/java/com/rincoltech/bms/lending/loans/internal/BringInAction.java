package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code loan_bring_in} (chapter 8 section 8.4; FR-EXL-04, ADR-071): an existing loan recorded from a paper register is
 * requested with {@code lending.loans.bring_in} and decided with {@code lending.disbursements.authorise}, the checker of
 * a disbursement. No threshold: every one waits for a second person, because what it brings into the books was never
 * seen by the app. Executes the loan as stored, which cannot change while it waits (its version is checked).
 */
@Component
class BringInAction implements ApprovalAction {

    static final String TYPE = "loan_bring_in";
    static final String GO_LIVE = "go_live_date";

    private final LoanRepository loans;
    private final ExistingLoanService existing;

    /** The service is lazy: it requests approvals, and the approvals hold every action, this one included. */
    BringInAction(LoanRepository loans, @Lazy ExistingLoanService existing) {
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
        existing.approve(e.subjectId(), e.approvalId(), e.makerId(), e.checkerId());
    }
}
