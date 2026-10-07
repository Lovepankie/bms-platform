package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.savings.internal.SavingsRepository.AccountRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code savings_reversal} (chapter 8 section 8.4): requested with {@code lending.savings.withdraw},
 * decided with {@code lending.savings.withdraw_approve}, never below a threshold. The subject is the
 * transaction; its version is its account's, so any movement on the account between request and
 * decision makes the request stale (FR-APR-08).
 */
@Component
class SavingsReversalAction implements ApprovalAction {

    static final String TYPE = "savings_reversal";
    static final String REASON = "reason";

    private final SavingsRepository repo;
    private final SavingsServicer servicer;

    SavingsReversalAction(SavingsRepository repo, SavingsServicer servicer) {
        this.repo = repo;
        this.servicer = servicer;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "lending.savings_transaction";
    }

    @Override
    public String makerPermission() {
        return "lending.savings.withdraw";
    }

    @Override
    public String checkerPermission() {
        return "lending.savings.withdraw_approve";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.accountOfTxn(subjectId).flatMap(repo::account).map(AccountRow::version);
    }

    @Override
    public void execute(Execution e) {
        servicer.reverse(e.subjectId(), (String) e.payload().get(REASON), e.approvalId(), e.makerId(), e.checkerId());
    }
}
