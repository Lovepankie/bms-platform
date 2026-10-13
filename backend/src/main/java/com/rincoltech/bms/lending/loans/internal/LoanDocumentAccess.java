package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.Principal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** FR-DOC-03: a loan's receipts and vouchers are readable by whoever may read the loan in its branch. */
@Component
class LoanDocumentAccess implements DocumentAccess {

    private final LoanRepository loans;

    LoanDocumentAccess(LoanRepository loans) {
        this.loans = loans;
    }

    @Override
    public String subjectType() {
        return LoanDocuments.SUBJECT;
    }

    @Override
    public boolean canRead(Principal principal, UUID loanId) {
        return loans.find(loanId)
                .map(l -> principal.may("lending.loans.read", l.branchId()))
                .orElse(false);
    }
}
