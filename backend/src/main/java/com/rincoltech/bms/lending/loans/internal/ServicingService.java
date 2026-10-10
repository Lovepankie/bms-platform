package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.Servicing.Quote;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ActionOutcome;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.DisbursementRequest;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.PayoffQuote;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ReasonRequest;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.RepaymentRequest;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.RepaymentResult;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.Schedule;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ScheduleRow;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.TransactionList;
import com.rincoltech.bms.lending.loans.internal.ServicingRepository.Txn;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff routes of loan servicing (chapter 7 section 7.11.13): branch scope by the route's
 * permission (a loan outside it is a 404), the {@code Idempotency-Key} protocol on every
 * money-moving route, and the maker-checker requests of chapter 8 section 8.4. The money events
 * themselves are {@link LoanServicer}'s.
 */
@Service
class ServicingService {

    static final String BASE = "/api/v1/lending/loans/";

    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final LoanServicer servicer;
    private final Approvals approvals;
    private final Idempotency idempotency;

    ServicingService(
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

    /** FR-DIS-01: executes at once below the tenant's threshold (FR-APR-04), else waits for a checker. */
    @Transactional
    Outcome<ActionOutcome> requestDisbursement(UUID id, String key, DisbursementRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/disbursements", r, ActionOutcome.class, () -> {
            Loan loan = lock(id, "lending.disbursements.request");
            servicer.checkDisbursable(loan, r.disbursementDate(), r.paymentMethodKey());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(DisbursementAction.DATE, r.disbursementDate().toString());
            payload.put(DisbursementAction.METHOD, r.paymentMethodKey());
            payload.put(DisbursementAction.REFERENCE, r.externalReference());
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    DisbursementAction.TYPE,
                    loan.branchId(),
                    loan.id(),
                    loan.version(),
                    loan.approvedPrincipalMinor(),
                    loan.currency(),
                    payload));
            return outcome(id, o);
        });
    }

    /** FR-REP-01 to FR-REP-04, FR-LCL-03. */
    @Transactional
    Outcome<RepaymentResult> repay(UUID id, String key, RepaymentRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/repayments", r, RepaymentResult.class, () -> {
            Principal principal = CurrentPrincipal.require();
            Loan loan = lock(id, "lending.repayments.create");
            servicer.checkRepayable(loan, r.valueDate(), r.paymentMethodKey());
            UUID txn = servicer.repay(
                    loan,
                    r.amountMinor(),
                    r.valueDate(),
                    r.paymentMethodKey(),
                    blankToNull(r.externalReference()),
                    principal.userId(),
                    "staff");
            return new RepaymentResult(
                    repo.transaction(id, txn).orElseThrow(),
                    loans.find(id).orElseThrow().status(),
                    repo.balances(id).orElseThrow());
        });
    }

    /** FR-REP-05: always waits for a checker (no threshold, chapter 8 section 8.4). */
    @Transactional
    Outcome<ActionOutcome> requestReversal(UUID id, UUID txnId, String key, ReasonRequest r) {
        return idempotency.once(
                key, "POST", BASE + id + "/transactions/" + txnId + "/reverse", r, ActionOutcome.class, () -> {
                    Loan loan = lock(id, "lending.repayments.reverse_request");
                    Txn txn = servicer.checkReversible(loan, repo.txn(txnId).orElseThrow(ApiException::notFound));
                    Approvals.Outcome o = approvals.request(new ActionRequest(
                            ReversalAction.TYPE,
                            loan.branchId(),
                            txn.id(),
                            loan.version(),
                            txn.amountMinor(),
                            txn.currency(),
                            Map.of(ReversalAction.REASON, r.reason().trim())));
                    return outcome(id, o);
                });
    }

    /** FR-LCL-02: always waits for a checker. */
    @Transactional
    Outcome<ActionOutcome> requestWriteOff(UUID id, String key, ReasonRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/write-off", r, ActionOutcome.class, () -> {
            Loan loan = lock(id, "lending.loans.write_off_request");
            long amount = servicer.writeOffAmount(loan);
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    WriteOffAction.TYPE,
                    loan.branchId(),
                    loan.id(),
                    loan.version(),
                    amount,
                    loan.currency(),
                    Map.of(WriteOffAction.REASON, r.reason().trim())));
            return outcome(id, o);
        });
    }

    /** FR-DIS-04: the items and a totals row. */
    @Transactional(readOnly = true)
    Schedule schedule(UUID id) {
        Loan loan = inScope(id);
        List<ScheduleRow> rows = repo.scheduleRows(id);
        return new Schedule(id, loan.currency(), rows, totals(rows));
    }

    @Transactional(readOnly = true)
    TransactionList transactions(UUID id) {
        inScope(id);
        return new TransactionList(repo.transactions(id));
    }

    /** FR-REP-06: R-PAYOFF on any value date not before disbursement; today when omitted. */
    @Transactional(readOnly = true)
    PayoffQuote payoff(UUID id, LocalDate valueDate) {
        Loan loan = inScope(id);
        LocalDate date = valueDate != null ? valueDate : servicer.today();
        LocalDate disbursed = repo.balances(id).orElseThrow().disbursedOn();
        if (disbursed != null && date.isBefore(disbursed)) {
            throw ApiException.rule("before_disbursement", "The value date is before the loan was disbursed.");
        }
        Quote q = servicer.quote(loan, date);
        return new PayoffQuote(
                id,
                loan.currency(),
                date,
                q.principalMinor(),
                q.interestMinor(),
                q.feesMinor(),
                q.penaltiesMinor(),
                q.rebateMinor(),
                q.totalMinor());
    }

    private ActionOutcome outcome(UUID id, Approvals.Outcome o) {
        return new ActionOutcome(
                id, o.executed(), o.approvalId(), loans.find(id).orElseThrow().status());
    }

    private Loan lock(UUID id, String permission) {
        Principal principal = CurrentPrincipal.require();
        return loans.lock(id)
                .filter(l -> principal.may(permission, l.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private Loan inScope(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return loans.find(id)
                .filter(l -> principal.may("lending.loans.read", l.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private static ScheduleRow totals(List<ScheduleRow> rows) {
        long[] t = new long[13];
        for (ScheduleRow r : rows) {
            long[] v = {
                r.principalDueMinor(),
                r.interestDueMinor(),
                r.feesDueMinor(),
                r.penaltiesDueMinor(),
                r.totalDueMinor(),
                r.principalPaidMinor(),
                r.interestPaidMinor(),
                r.feesPaidMinor(),
                r.penaltiesPaidMinor(),
                r.totalPaidMinor(),
                r.waivedMinor(),
                r.writtenOffMinor(),
                r.outstandingMinor()
            };
            for (int i = 0; i < v.length; i++) {
                t[i] = Math.addExact(t[i], v[i]);
            }
        }
        return new ScheduleRow(
                null, null, t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11], t[12], null,
                null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
