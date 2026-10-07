package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code loan_write_off} (chapter 8 section 8.4; FR-LCL-02): requested with
 * {@code lending.loans.write_off_request}, decided with {@code lending.loans.write_off_approve},
 * never below a threshold.
 */
@Component
class WriteOffAction implements ApprovalAction {

    static final String TYPE = "loan_write_off";
    static final String REASON = "reason";

    private final LoanRepository loans;
    private final LoanServicer servicer;

    WriteOffAction(LoanRepository loans, LoanServicer servicer) {
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
        return "lending.loans.write_off_request";
    }

    @Override
    public String checkerPermission() {
        return "lending.loans.write_off_approve";
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
        servicer.writeOff(e.subjectId(), (String) e.payload().get(REASON), e.approvalId(), e.makerId(), e.checkerId());
    }
}
