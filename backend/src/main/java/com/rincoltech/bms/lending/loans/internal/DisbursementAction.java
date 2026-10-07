package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code loan_disbursement} (chapter 8 section 8.4; FR-DIS-01, FR-DIS-02): requested with
 * {@code lending.disbursements.request}, decided with {@code lending.disbursements.authorise};
 * below the tenant's threshold it executes at once. Executes exactly the stored payload
 * (FR-APR-08): date, payment method and reference.
 */
@Component
class DisbursementAction implements ApprovalAction {

    static final String TYPE = "loan_disbursement";
    static final String DATE = "disbursement_date";
    static final String METHOD = "payment_method_key";
    static final String REFERENCE = "external_reference";

    private final LoanRepository loans;
    private final LoanServicer servicer;

    DisbursementAction(LoanRepository loans, LoanServicer servicer) {
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
        return "lending.disbursements.request";
    }

    @Override
    public String checkerPermission() {
        return "lending.disbursements.authorise";
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
        Loan loan = loans.lock(e.subjectId()).orElseThrow(ApiException::notFound);
        LocalDate date = LocalDate.parse((String) e.payload().get(DATE));
        String method = (String) e.payload().get(METHOD);
        servicer.checkDisbursable(loan, date, method);
        servicer.disburse(
                loan,
                date,
                method,
                (String) e.payload().get(REFERENCE),
                e.approvalId(),
                e.makerId(),
                e.checkerId(),
                "staff");
    }
}
