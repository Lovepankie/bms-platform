package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code charge_waiver} (chapter 8 section 8.4; FR-ARR-04): requested with
 * {@code lending.charges.waive_request}, decided with {@code lending.charges.waive_approve}; below
 * the tenant's threshold it takes effect at once. The subject is the loan, so a money event on it
 * between request and decision supersedes the request.
 */
@Component
class WaiverAction implements ApprovalAction {

    static final String TYPE = "charge_waiver";
    static final String CHARGE = "charge_id";
    static final String AMOUNT = "amount_minor";
    static final String REASON = "reason";

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

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return loans.find(subjectId).map(Loan::version);
    }

    @Override
    public void execute(Execution e) {
        servicer.waive(
                UUID.fromString((String) e.payload().get(CHARGE)),
                ((Number) e.payload().get(AMOUNT)).longValue(),
                (String) e.payload().get(REASON),
                e.approvalId(),
                e.makerId(),
                e.checkerId());
    }
}
