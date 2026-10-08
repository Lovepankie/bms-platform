package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Inv;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code investment_reversal} (chapter 8 section 8.4; FR-INV-11): requested with
 * {@code lending.investments.payout}, decided with {@code lending.investments.reverse_approve}, never
 * below a threshold. The subject is the transaction; its version is its investment's, so any money
 * event on the investment between request and decision makes the request stale (FR-APR-08).
 */
@Component
class InvestmentReversalAction implements ApprovalAction {

    static final String TYPE = "investment_reversal";
    static final String REASON = "reason";

    private final InvestmentRepository repo;
    private final InvestmentServicer servicer;

    InvestmentReversalAction(InvestmentRepository repo, InvestmentServicer servicer) {
        this.repo = repo;
        this.servicer = servicer;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "lending.investment_transaction";
    }

    @Override
    public String makerPermission() {
        return "lending.investments.payout";
    }

    @Override
    public String checkerPermission() {
        return "lending.investments.reverse_approve";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.txn(subjectId).flatMap(t -> repo.find(t.investmentId())).map(Inv::version);
    }

    @Override
    public void execute(Execution e) {
        servicer.reverse(e.subjectId(), (String) e.payload().get(REASON), e.approvalId(), e.makerId(), e.checkerId());
    }
}
