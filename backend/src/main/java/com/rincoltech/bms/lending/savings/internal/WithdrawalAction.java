package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.savings.internal.SavingsRepository.AccountRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code savings_withdrawal} (chapter 8 section 8.4; FR-SAV-03, FR-SAV-07): requested with
 * {@code lending.savings.withdraw}, decided with {@code lending.savings.withdraw_approve}; below the
 * tenant's threshold it executes at once. A closure is the same action with {@code close} set: it
 * pays out the whole balance after interest to date. The checks run again at execution with the
 * account as it is then, and the money moves on the day of execution; a failure leaves the request
 * pending with the error (FR-APR-06).
 */
@Component
class WithdrawalAction implements ApprovalAction {

    static final String TYPE = "savings_withdrawal";
    static final String AMOUNT = "amount_minor";
    static final String METHOD = "payment_method_key";
    static final String REFERENCE = "external_reference";
    static final String CLOSE = "close";
    static final String REASON = "reason";

    private final SavingsRepository repo;
    private final SavingsServicer servicer;

    WithdrawalAction(SavingsRepository repo, SavingsServicer servicer) {
        this.repo = repo;
        this.servicer = servicer;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return SavingsServicer.SUBJECT;
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
        return true;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.account(subjectId).map(AccountRow::version);
    }

    @Override
    public void execute(Execution e) {
        String method = (String) e.payload().get(METHOD);
        String reference = (String) e.payload().get(REFERENCE);
        if (Boolean.TRUE.equals(e.payload().get(CLOSE))) {
            servicer.close(
                    e.subjectId(),
                    method,
                    reference,
                    (String) e.payload().get(REASON),
                    e.approvalId(),
                    e.makerId(),
                    e.checkerId());
            return;
        }
        AccountRow a = repo.lock(e.subjectId()).orElseThrow(ApiException::notFound);
        long amount = ((Number) e.payload().get(AMOUNT)).longValue();
        servicer.checkWithdrawal(a, amount, servicer.today(), method);
        servicer.withdraw(
                a, amount, servicer.today(), method, reference, e.approvalId(), e.makerId(), e.checkerId(), "staff");
    }
}
