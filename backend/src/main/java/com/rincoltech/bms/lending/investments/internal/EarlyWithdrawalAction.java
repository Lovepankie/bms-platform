package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Inv;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code investment_early_withdrawal} (chapter 8 section 8.4; FR-INV-06): requested with
 * {@code lending.investments.payout}, decided with {@code lending.investments.early_withdraw_approve},
 * never below a threshold. Settles on the business date of the approval (R-INV-6), so the amount
 * paid is the quote of that day, not of the request's.
 */
@Component
class EarlyWithdrawalAction implements ApprovalAction {

    static final String TYPE = "investment_early_withdrawal";
    static final String METHOD = "payment_method_key";
    static final String REFERENCE = "external_reference";
    static final String REASON = "reason";

    private final InvestmentRepository repo;
    private final InvestmentServicer servicer;

    EarlyWithdrawalAction(InvestmentRepository repo, InvestmentServicer servicer) {
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
        return "lending.investments.payout";
    }

    @Override
    public String checkerPermission() {
        return "lending.investments.early_withdraw_approve";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.find(subjectId).map(Inv::version);
    }

    @Override
    public void execute(Execution e) {
        servicer.earlyWithdraw(
                e.subjectId(),
                (String) e.payload().get(METHOD),
                (String) e.payload().get(REFERENCE),
                (String) e.payload().get(REASON),
                e.approvalId(),
                e.makerId(),
                e.checkerId());
    }
}
