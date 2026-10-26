package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ActionOutcome;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ItemWaiverRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-ARR-04, second half (#265): a waiver of all or part of a schedule item's fees or interest. It is the
 * {@code charge_waiver} approval with the accountant's permission on the checker ({@link WaiverAction#FEE_INTEREST}). The
 * rule is read once and passed to the approvals module (ADR-091): below the threshold, weighed against everything waived
 * on the loan with this request, it takes effect at once only for a maker who holds the accountant's permission; anyone
 * else always waits for a checker who does.
 */
@Service
class ItemWaiverService {

    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final LoanServicer servicer;
    private final Approvals approvals;
    private final Idempotency idempotency;

    ItemWaiverService(
            LoanRepository loans,
            ServicingRepository repo,
            LoanServicer servicer,
            Approvals approvals,
            Idempotency idempotency) {
        this.loans = loans;
        this.repo = repo;
        this.servicer = servicer;
        this.approvals = approvals;
        this.idempotency = idempotency;
    }

    @Transactional
    Outcome<ActionOutcome> requestWaiver(UUID id, int itemNo, String key, ItemWaiverRequest r) {
        return idempotency.once(
                key,
                "POST",
                ServicingService.BASE + id + "/schedule/" + itemNo + "/waive",
                r,
                ActionOutcome.class,
                () -> {
                    Principal principal = CurrentPrincipal.require();
                    Loan loan = loans.lock(id)
                            .filter(l -> principal.may("lending.charges.waive_request", l.branchId()))
                            .orElseThrow(ApiException::notFound);
                    String component = r.component().trim().toLowerCase(java.util.Locale.ROOT);
                    long amount = r.amountMinor();
                    servicer.checkItemWaiver(loan, repo.items(loan.id()), itemNo, component, amount);
                    long cumulative = Math.addExact(repo.waivedOnLoan(loan.id()), amount);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put(WaiverAction.ITEM_NO, itemNo);
                    payload.put(WaiverAction.COMPONENT, component);
                    payload.put(WaiverAction.AMOUNT, amount);
                    payload.put(WaiverAction.REASON, r.reason().trim());
                    Approvals.Rule rule = approvals.rule(WaiverAction.TYPE);
                    if (!principal.may(WaiverAction.FEE_INTEREST, loan.branchId())) {
                        // From 0, every amount asks: the approvals module runs at once only below the threshold (an
                        // amount
                        // under it), so a maker who is not the accountant always waits for one.
                        rule = new Approvals.Rule(true, 0);
                    }
                    Approvals.Outcome o = approvals.request(
                            new ActionRequest(
                                    WaiverAction.TYPE,
                                    loan.branchId(),
                                    loan.id(),
                                    loan.version(),
                                    amount,
                                    cumulative,
                                    loan.currency(),
                                    payload),
                            rule);
                    return new ActionOutcome(
                            id,
                            o.executed(),
                            o.approvalId(),
                            loans.find(id).orElseThrow().status());
                });
    }
}
