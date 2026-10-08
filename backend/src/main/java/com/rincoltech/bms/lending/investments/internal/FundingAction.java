package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Inv;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code investment_funding} (chapter 8 section 8.4; FR-INV-03): requested with
 * {@code lending.investments.fund}, decided with {@code lending.investments.fund_approve}; below the
 * tenant's threshold it executes at once. Executes exactly the stored payload (FR-APR-08).
 */
@Component
class FundingAction implements ApprovalAction {

    static final String TYPE = "investment_funding";
    static final String DATE = "value_date";
    static final String METHOD = "payment_method_key";
    static final String REFERENCE = "external_reference";

    private final InvestmentRepository repo;
    private final InvestmentServicer servicer;

    FundingAction(InvestmentRepository repo, InvestmentServicer servicer) {
        this.repo = repo;
        this.servicer = servicer;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return InvestmentServicer.SUBJECT;
    }

    @Override
    public String makerPermission() {
        return "lending.investments.fund";
    }

    @Override
    public String checkerPermission() {
        return "lending.investments.fund_approve";
    }

    @Override
    public boolean thresholdApplies() {
        return true;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.find(subjectId).map(Inv::version);
    }

    @Override
    public void execute(Execution e) {
        Inv inv = repo.lock(e.subjectId()).orElseThrow(ApiException::notFound);
        LocalDate date = LocalDate.parse((String) e.payload().get(DATE));
        String method = (String) e.payload().get(METHOD);
        servicer.checkFundable(inv, date, method);
        servicer.fund(
                inv,
                date,
                method,
                (String) e.payload().get(REFERENCE),
                e.approvalId(),
                e.makerId(),
                e.checkerId(),
                "staff");
    }
}
