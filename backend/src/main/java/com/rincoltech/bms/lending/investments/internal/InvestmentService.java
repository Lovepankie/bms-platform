package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.ActionOutcome;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Certificate;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.EarlyQuote;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.EarlyWithdrawalRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.FundingRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Investment;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.InvestmentPage;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.InvestmentTransaction;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.OpenRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.PaymentRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Product;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.ReasonRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.RolloverRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.RolloverResult;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Schedule;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Statement;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.StatementLine;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.TransactionResult;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Inv;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Item;
import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.EarlySettlement;
import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.Period;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff routes of investments (chapter 7 section 7.11.16): branch scope by the route's
 * permission (an investment outside it is a 404), the {@code Idempotency-Key} protocol on every
 * route that moves money, and the maker-checker requests of chapter 8 section 8.4. The money events
 * themselves are {@link InvestmentServicer}'s.
 */
@Service
class InvestmentService {

    static final String BASE = "/api/v1/lending/investments/";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final InvestmentRepository repo;
    private final InvestmentServicer servicer;
    private final MemberLookup members;
    private final Approvals approvals;
    private final Idempotency idempotency;
    private final CurrentTenant currentTenant;
    private final Branches branches;
    private final AuditLog audit;

    InvestmentService(
            InvestmentRepository repo,
            InvestmentServicer servicer,
            MemberLookup members,
            Approvals approvals,
            Idempotency idempotency,
            CurrentTenant currentTenant,
            Branches branches,
            AuditLog audit) {
        this.repo = repo;
        this.servicer = servicer;
        this.members = members;
        this.approvals = approvals;
        this.idempotency = idempotency;
        this.currentTenant = currentTenant;
        this.branches = branches;
        this.audit = audit;
    }

    // ---- Opening (FR-INV-02) --------------------------------------------------------------

    /** FR-INV-02: {@code pending_funding} at the member's branch, on the product's current terms. */
    @Transactional
    Investment open(OpenRequest r) {
        Principal principal = CurrentPrincipal.require();
        MemberSummary member = members.find(r.memberId())
                .filter(m -> principal.may("lending.investments.open", m.branchId()))
                .orElseThrow(() -> ApiException.rule("member_not_found", "No such member in your branches."));
        Product p = repo.product(r.productId())
                .orElseThrow(() -> ApiException.rule("product_not_found", "No such investment product."));
        InvestmentServicer.checkOpenable(member, p, r.amountMinor(), r.termMonths());
        UUID id = servicer.insert(
                member, p, r.amountMinor(), r.termMonths(), r.maturityInstruction(), "staff", principal.userId());
        return view(repo.find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    InvestmentPage list(
            List<UUID> branchIds,
            List<String> statuses,
            UUID memberId,
            UUID productId,
            Integer maturingWithinDays,
            String q,
            Integer limit,
            String cursor) {
        Principal principal = CurrentPrincipal.require();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        String after = Cursor.decode(cursor).orElse(null);
        LocalDate maturingBy =
                maturingWithinDays == null ? null : servicer.today().plusDays(maturingWithinDays);
        List<Inv> rows = repo.page(
                principal.branchFilter("lending.investments.read", branchIds),
                statuses,
                memberId,
                productId,
                maturingBy,
                q,
                after,
                size + 1);
        boolean more = rows.size() > size;
        List<Inv> page = more ? rows.subList(0, size) : rows;
        return new InvestmentPage(
                page.stream().map(this::view).toList(),
                more ? Cursor.encode(page.getLast().accountNo()) : null);
    }

    @Transactional(readOnly = true)
    Investment get(UUID id) {
        return view(inScope(id));
    }

    /** FR-INV-05: the member's choice, any time before the investment is settled. */
    @Transactional
    Investment instruct(UUID id, String instruction) {
        Inv inv = lock(id, "lending.investments.open");
        InvestmentServicer.requireStatus(inv, Set.of("pending_funding", "active", "matured"));
        repo.instruction(id, instruction);
        audit.record(new AuditLog.Entry(
                "lending.investment.instruction_set",
                InvestmentServicer.SUBJECT,
                id,
                inv.branchId(),
                inv.maturityInstruction() == null
                        ? Map.of()
                        : Map.of("maturity_instruction", inv.maturityInstruction()),
                Map.of("maturity_instruction", instruction)));
        return view(repo.find(id).orElseThrow());
    }

    // ---- Money routes ---------------------------------------------------------------------

    /** FR-INV-03: executes at once below the tenant's threshold (FR-APR-04), else waits for a checker. */
    @Transactional
    Outcome<ActionOutcome> requestFunding(UUID id, String key, FundingRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/funding", r, ActionOutcome.class, () -> {
            Inv inv = lock(id, "lending.investments.fund");
            servicer.checkFundable(inv, r.valueDate(), r.paymentMethodKey());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(FundingAction.DATE, r.valueDate().toString());
            payload.put(FundingAction.METHOD, r.paymentMethodKey());
            payload.put(FundingAction.REFERENCE, blankToNull(r.externalReference()));
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    FundingAction.TYPE,
                    inv.branchId(),
                    inv.id(),
                    inv.version(),
                    inv.principalMinor(),
                    inv.currency(),
                    payload));
            return outcome(id, o);
        });
    }

    /** FR-INV-04: pays the return due and unpaid. */
    @Transactional
    Outcome<TransactionResult> payReturn(UUID id, String key, PaymentRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/return-payouts", r, TransactionResult.class, () -> {
            Inv inv = lock(id, "lending.investments.payout");
            UUID txn = servicer.payReturn(
                    inv,
                    servicer.today(),
                    r.paymentMethodKey(),
                    blankToNull(r.externalReference()),
                    CurrentPrincipal.require().userId(),
                    "staff");
            return result(id, txn);
        });
    }

    /** FR-INV-05: principal plus unpaid return of a matured investment. */
    @Transactional
    Outcome<TransactionResult> payout(UUID id, String key, PaymentRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/payout", r, TransactionResult.class, () -> {
            Inv inv = lock(id, "lending.investments.payout");
            UUID txn = servicer.payout(
                    inv,
                    servicer.today(),
                    r.paymentMethodKey(),
                    blankToNull(r.externalReference()),
                    CurrentPrincipal.require().userId(),
                    "staff");
            return result(id, txn);
        });
    }

    /** FR-INV-05: a matured investment rolled over by staff on the product's current terms. */
    @Transactional
    Outcome<RolloverResult> rollover(UUID id, String key, RolloverRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/rollover", r, RolloverResult.class, () -> {
            Inv inv = lock(id, "lending.investments.payout");
            UUID next =
                    servicer.rollover(inv, r.mode(), CurrentPrincipal.require().userId(), "staff");
            return new RolloverResult(
                    view(repo.find(id).orElseThrow()), view(repo.find(next).orElseThrow()));
        });
    }

    /** FR-INV-06: R-INV-6 as at a date, today by default. */
    @Transactional(readOnly = true)
    EarlyQuote earlyQuote(UUID id, LocalDate valueDate) {
        Inv inv = inScope(id);
        LocalDate date = valueDate != null ? valueDate : servicer.today();
        EarlySettlement s = servicer.earlyQuote(inv, date);
        return new EarlyQuote(
                id,
                inv.currency(),
                date,
                inv.earlyWithdrawalRule(),
                s.principalMinor(),
                s.earnedMinor(),
                s.accruedMinor(),
                s.paidMinor(),
                s.penaltyMinor(),
                s.cashMinor());
    }

    /** FR-INV-06: always waits for a checker. */
    @Transactional
    Outcome<ActionOutcome> requestEarlyWithdrawal(UUID id, String key, EarlyWithdrawalRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/early-withdrawal", r, ActionOutcome.class, () -> {
            Inv inv = lock(id, "lending.investments.payout");
            EarlySettlement s = servicer.earlyQuote(inv, servicer.today());
            servicer.checkMethod(r.paymentMethodKey());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(EarlyWithdrawalAction.METHOD, r.paymentMethodKey());
            payload.put(EarlyWithdrawalAction.REFERENCE, blankToNull(r.externalReference()));
            payload.put(EarlyWithdrawalAction.REASON, r.reason().trim());
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    EarlyWithdrawalAction.TYPE,
                    inv.branchId(),
                    inv.id(),
                    inv.version(),
                    s.cashMinor(),
                    inv.currency(),
                    payload));
            return outcome(id, o);
        });
    }

    /** FR-INV-11: always waits for a checker. */
    @Transactional
    Outcome<ActionOutcome> requestReversal(UUID id, UUID txnId, String key, ReasonRequest r) {
        return idempotency.once(
                key, "POST", BASE + id + "/transactions/" + txnId + "/reverse", r, ActionOutcome.class, () -> {
                    Inv inv = lock(id, "lending.investments.payout");
                    InvestmentTransaction txn = repo.txn(txnId)
                            .filter(t -> t.investmentId().equals(id))
                            .orElseThrow(ApiException::notFound);
                    servicer.checkReversible(inv, txn);
                    Approvals.Outcome o = approvals.request(new ActionRequest(
                            InvestmentReversalAction.TYPE,
                            inv.branchId(),
                            txn.id(),
                            inv.version(),
                            txn.amountMinor(),
                            txn.currency(),
                            Map.of(InvestmentReversalAction.REASON, r.reason().trim())));
                    return outcome(id, o);
                });
    }

    // ---- Schedule, statement, certificate (FR-INV-09, FR-INV-10) --------------------------

    @Transactional(readOnly = true)
    Schedule schedule(UUID id) {
        Inv inv = inScope(id);
        List<Item> items = repo.items(id);
        List<Period> periods = items.stream()
                .map(i -> new Period(
                        i.periodNo(),
                        i.periodStart(),
                        i.periodEnd(),
                        i.openingBalanceMinor(),
                        i.returnMinor(),
                        i.payout()))
                .toList();
        Map<Integer, String> statuses = new LinkedHashMap<>();
        items.forEach(i -> statuses.put(i.periodNo(), i.status()));
        return new Schedule(
                id, inv.currency(), InvestmentProductService.rows(periods, statuses), InvestmentReturns.total(periods));
    }

    /** FR-INV-10: every money event in posting order with the principal and return payable after it. */
    @Transactional(readOnly = true)
    Statement statement(UUID id) {
        Inv inv = inScope(id);
        List<InvestmentTransaction> txns = repo.txns(id);
        Map<UUID, InvestmentTransaction> byId = new LinkedHashMap<>();
        txns.forEach(t -> byId.put(t.id(), t));
        long principal = 0;
        long payable = 0;
        List<StatementLine> lines = new ArrayList<>();
        for (InvestmentTransaction t : txns) {
            long[] effect = effect(t.txnType(), t);
            if (t.txnType().equals("reversal")) {
                InvestmentTransaction original = byId.get(t.reversesTxnId());
                long[] o = effect(original.txnType(), original);
                effect = new long[] {-o[0], -o[1]};
            }
            principal += effect[0];
            // An early withdrawal trues the return up or down and pays it: nothing stays payable.
            payable = t.txnType().equals("early_withdrawal") ? 0 : payable + effect[1];
            lines.add(new StatementLine(t, principal, payable));
        }
        return new Statement(view(inv), lines, principal, payable);
    }

    /** What a transaction does to principal held and to return payable. */
    private static long[] effect(String type, InvestmentTransaction t) {
        return switch (type) {
            case "funding", "rollover_in" -> new long[] {t.principalMinor(), 0};
            case "return_accrual" -> new long[] {0, t.returnMinor()};
            case "return_payout" -> new long[] {0, -t.returnMinor()};
            case "maturity_payout", "rollover_out" -> new long[] {-t.principalMinor(), -t.returnMinor()};
            case "early_withdrawal" -> new long[] {-t.principalMinor(), 0};
            default -> new long[] {0, 0};
        };
    }

    /** FR-INV-10: the data of the printed certificate, for a funded investment. */
    @Transactional(readOnly = true)
    Certificate certificate(UUID id) {
        Inv inv = inScope(id);
        if (inv.certificateNo() == null) {
            throw ApiException.rule("not_funded", "A certificate is issued once the investment is funded.");
        }
        String branchName = branches.all().stream()
                .filter(b -> b.id().equals(inv.branchId()))
                .findFirst()
                .map(Branches.Branch::name)
                .orElse("");
        return new Certificate(
                inv.certificateNo(),
                currentTenant.profile().name(),
                branchName,
                inv.accountNo(),
                inv.memberNo(),
                inv.memberName(),
                inv.productName(),
                inv.currency(),
                inv.principalMinor(),
                inv.returnRateBp(),
                inv.returnMethod(),
                inv.payoutFrequency(),
                inv.termMonths(),
                inv.startDate(),
                inv.maturityDate(),
                inv.agreedReturnMinor(),
                inv.principalMinor() + inv.agreedReturnMinor(),
                inv.maturityInstruction(),
                inv.earlyWithdrawalAllowed(),
                inv.earlyWithdrawalRule(),
                inv.earlyWithdrawalRateBp(),
                inv.earlyWithdrawalPenaltyBp(),
                servicer.today());
    }

    // ---- Shared ---------------------------------------------------------------------------

    Investment view(Inv i) {
        return new Investment(
                i.id(),
                i.accountNo(),
                i.branchId(),
                i.memberId(),
                i.memberNo(),
                i.memberName(),
                i.productId(),
                i.productCode(),
                i.productName(),
                i.productType(),
                i.currency(),
                i.status(),
                i.version(),
                i.principalMinor(),
                i.returnRateBp(),
                i.returnMethod(),
                i.termMonths(),
                i.payoutFrequency(),
                i.earlyWithdrawalAllowed(),
                i.earlyWithdrawalRule(),
                i.earlyWithdrawalRateBp(),
                i.earlyWithdrawalPenaltyBp(),
                i.startDate(),
                i.maturityDate(),
                i.agreedReturnMinor(),
                i.principalHeldMinor(),
                i.returnAccruedMinor(),
                i.returnDueMinor(),
                i.returnPaidMinor(),
                i.returnAvailableMinor(),
                i.maturityInstruction(),
                i.rolledOverFromId(),
                i.rolledOverToId(),
                i.certificateNo(),
                i.channel(),
                i.preMaturityRemindedOn(),
                i.postMaturityRemindedOn(),
                i.closedOn(),
                repo.pendingApproval(i.id()).orElse(null),
                i.createdAt());
    }

    private TransactionResult result(UUID id, UUID txn) {
        return new TransactionResult(
                repo.txn(txn).orElseThrow(), view(repo.find(id).orElseThrow()));
    }

    private ActionOutcome outcome(UUID id, Approvals.Outcome o) {
        return new ActionOutcome(
                id, o.executed(), o.approvalId(), repo.find(id).orElseThrow().status());
    }

    private Inv lock(UUID id, String permission) {
        Principal principal = CurrentPrincipal.require();
        return repo.lock(id)
                .filter(i -> principal.may(permission, i.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private Inv inScope(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id)
                .filter(i -> principal.may("lending.investments.read", i.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
