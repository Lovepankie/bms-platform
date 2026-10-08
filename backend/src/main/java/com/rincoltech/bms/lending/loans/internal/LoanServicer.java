package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.LoanServicing;
import com.rincoltech.bms.lending.loans.internal.LoanBooks.Leg;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.Servicing.Allocation;
import com.rincoltech.bms.lending.loans.internal.Servicing.Applied;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Payment;
import com.rincoltech.bms.lending.loans.internal.Servicing.Position;
import com.rincoltech.bms.lending.loans.internal.Servicing.Quote;
import com.rincoltech.bms.lending.loans.internal.Servicing.Replay;
import com.rincoltech.bms.lending.loans.internal.ServicingRepository.Txn;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.FeeCharge;
import com.rincoltech.bms.lending.products.ProductCatalog.ProductTerms;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money events of a loan once approved (FR-DIS-02, FR-REP-03 to FR-REP-05, FR-LCL-01 to
 * FR-LCL-03): each writes its transaction, allocations and schedule changes, posts its journal
 * through {@code post_entry}, moves the loan's balances, DPD and status, and audits, all in the
 * caller's transaction. The caller holds no principal requirement: actors are parameters, so the
 * approval actions, the staff routes and {@link LoanServicing} share one implementation.
 */
@Service
class LoanServicer implements LoanServicing {

    static final String SUBJECT = "lending.loan";

    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final ProductCatalog products;
    private final LoanBooks books;
    private final Branches branches;
    private final TenantSequences sequences;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    LoanServicer(
            LoanRepository loans,
            ServicingRepository repo,
            ProductCatalog products,
            LoanBooks books,
            Branches branches,
            TenantSequences sequences,
            CurrentTenant currentTenant,
            BusinessClock clock,
            AuditLog audit) {
        this.loans = loans;
        this.repo = repo;
        this.products = products;
        this.books = books;
        this.branches = branches;
        this.sequences = sequences;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.audit = audit;
    }

    LocalDate today() {
        return clock.today(currentTenant.profile().timezone());
    }

    // ---- LoanServicing --------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void disburseApproved(
            UUID loanId, LocalDate disbursementDate, String paymentMethodKey, UUID makerId, UUID checkerId) {
        if (makerId.equals(checkerId)) {
            throw ApiException.rule("self_approval_forbidden", "The checker is never the maker.");
        }
        Loan loan = loans.lock(loanId).orElseThrow(ApiException::notFound);
        checkDisbursable(loan, disbursementDate, paymentMethodKey);
        disburse(loan, disbursementDate, paymentMethodKey, null, null, makerId, checkerId, "system");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordRepayment(
            UUID loanId, long amountMinor, LocalDate valueDate, String paymentMethodKey, UUID recordedBy) {
        Loan loan = loans.lock(loanId).orElseThrow(ApiException::notFound);
        checkRepayable(loan, valueDate, paymentMethodKey);
        return repay(loan, amountMinor, valueDate, paymentMethodKey, null, recordedBy, "system");
    }

    // ---- Disbursement (FR-DIS-01 to FR-DIS-03) --------------------------------------------

    /** The checks of a disbursement request, run again at execution. */
    void checkDisbursable(Loan loan, LocalDate date, String paymentMethodKey) {
        requireStatus(loan, Set.of("approved"));
        if (date.isAfter(today())) {
            throw ApiException.rule("value_date_in_future", "The disbursement date is today or earlier.");
        }
        books.methodAccount(paymentMethodKey);
    }

    /**
     * FR-DIS-02: schedule from the disbursement date with the approved terms, fees per FR-PRD-02,
     * the disbursement journal (and the upfront fee's), the loan {@code active}, history and audit.
     */
    void disburse(
            Loan loan,
            LocalDate date,
            String paymentMethodKey,
            String externalReference,
            UUID approvalId,
            UUID makerId,
            UUID checkerId,
            String source) {
        LocalDate today = today();
        long principal = loan.approvedPrincipalMinor();
        List<FeeCharge> fees = products.fees(loan.productVersionId(), principal);
        long deducted = sum(fees, "deducted_at_disbursement");
        long upfront = sum(fees, "paid_upfront");
        long added = sum(fees, "added_to_loan");
        if (deducted >= principal) {
            throw ApiException.rule("fees_exceed_principal", "The deducted fees leave nothing to disburse.");
        }
        List<ScheduleCalculator.Item> schedule = ScheduleCalculator.schedule(
                new ScheduleCalculator.Terms(
                        loan.interestMethod(),
                        loan.interestRateBp(),
                        loan.rateUnit(),
                        loan.termUnit(),
                        loan.approvedTermCount(),
                        loan.repaymentPattern(),
                        loan.instalmentFrequency()),
                principal,
                added,
                date);
        repo.insertItems(loan.id(), schedule, today);

        String method = books.methodAccount(paymentMethodKey);
        String voucher = number("VC", "voucher", loan.branchId());
        UUID txnId = UUID.randomUUID();
        PostedEntry entry = books.post(
                loan.branchId(),
                date,
                loan.loanNo(),
                "Disbursement " + voucher,
                txnId,
                "lending.disbursement:" + loan.id(),
                List.of(
                        Leg.debit(LoanBooks.LOANS_RECEIVABLE, principal).forLoan(loan.id()),
                        Leg.credit(method, principal - deducted),
                        Leg.credit(LoanBooks.FEE_INCOME, deducted)));
        repo.insertTxn(new Txn(
                txnId,
                loan.branchId(),
                loan.id(),
                "disbursement",
                principal,
                loan.currency(),
                date,
                paymentMethodKey,
                externalReference,
                voucher,
                null,
                null,
                entry.entryId(),
                approvalId,
                null,
                source,
                makerId,
                null));
        String feeReceipt = null;
        if (upfront > 0) {
            feeReceipt = number("RC", "receipt", loan.branchId());
            UUID feeTxn = UUID.randomUUID();
            PostedEntry feeEntry = books.post(
                    loan.branchId(),
                    date,
                    loan.loanNo(),
                    "Upfront fee " + feeReceipt,
                    feeTxn,
                    "lending.fee_upfront:" + loan.id(),
                    List.of(Leg.debit(method, upfront), Leg.credit(LoanBooks.FEE_INCOME, upfront)));
            repo.insertTxn(new Txn(
                    feeTxn,
                    loan.branchId(),
                    loan.id(),
                    "fee_upfront",
                    upfront,
                    loan.currency(),
                    date,
                    paymentMethodKey,
                    externalReference,
                    feeReceipt,
                    null,
                    null,
                    feeEntry.entryId(),
                    approvalId,
                    null,
                    source,
                    makerId,
                    null));
        }
        repo.disbursed(loan.id(), date, schedule.getLast().dueDate(), principal);
        loans.move(loan.id(), "approved", "active", checkerId != null ? checkerId : makerId, null, Map.of());
        refresh(loan.id(), repo.items(loan.id()), today);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "active");
        after.put("disbursed_on", date.toString());
        after.put("principal_minor", principal);
        after.put("fees_deducted_minor", deducted);
        after.put("fees_upfront_minor", upfront);
        after.put("fees_added_minor", added);
        after.put("payment_method_key", paymentMethodKey);
        after.put("voucher_no", voucher);
        if (feeReceipt != null) {
            after.put("fee_receipt_no", feeReceipt);
        }
        after.put("approval_request_id", approvalId);
        audit.record(new AuditLog.Entry(
                "lending.loan.disbursed", SUBJECT, loan.id(), loan.branchId(), Map.of("status", "approved"), after));
    }

    // ---- Repayment (FR-REP-01 to FR-REP-04, FR-LCL-01, FR-LCL-03) ------------------------

    void checkRepayable(Loan loan, LocalDate valueDate, String paymentMethodKey) {
        requireStatus(loan, Set.of("active", "written_off"));
        if (valueDate.isAfter(today())) {
            throw ApiException.rule("value_date_in_future", "The value date is today or earlier.");
        }
        var b = repo.balances(loan.id()).orElseThrow();
        if (b.disbursedOn() != null && valueDate.isBefore(b.disbursedOn())) {
            throw ApiException.rule("before_disbursement", "The value date is before the loan was disbursed.");
        }
        // Allocation follows value date order (R-ALLOC); a repayment dated before the latest one
        // would have to re-allocate it, so it is refused and recorded in order instead.
        if (b.lastRepaymentOn() != null && valueDate.isBefore(b.lastRepaymentOn())) {
            throw ApiException.rule(
                    "before_last_repayment",
                    "The value date is before the loan's latest repayment (" + b.lastRepaymentOn() + ").");
        }
        books.methodAccount(paymentMethodKey);
    }

    /** Records the repayment, or on a written-off loan the recovery; returns the transaction id. */
    UUID repay(
            Loan loan,
            long amountMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            UUID recordedBy,
            String source) {
        LocalDate today = today();
        String method = books.methodAccount(paymentMethodKey);
        String receipt = number("RC", "receipt", loan.branchId());
        UUID txnId = UUID.randomUUID();
        boolean recovery = loan.status().equals("written_off");
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(method, amountMinor));
        Applied applied = null;
        List<Item> items = null;
        if (recovery) {
            // FR-LCL-03: the written-off book is closed in the ledger; the money is income.
            legs.add(Leg.credit(LoanBooks.BAD_DEBT_RECOVERED, amountMinor));
        } else {
            ProductTerms product = terms(loan);
            items = repo.items(loan.id());
            applied = Servicing.apply(
                    items,
                    amountMinor,
                    valueDate,
                    loan.interestMethod(),
                    product.flatEarlySettlementRebate(),
                    product.allocationOrder());
            legs.addAll(creditLegs(loan.id(), applied.allocations()));
        }
        PostedEntry entry = books.post(
                loan.branchId(),
                valueDate,
                receipt,
                (recovery ? "Recovery " : "Repayment ") + loan.loanNo(),
                txnId,
                "lending.txn:" + txnId,
                legs);
        repo.insertTxn(new Txn(
                txnId,
                loan.branchId(),
                loan.id(),
                recovery ? "recovery" : "repayment",
                amountMinor,
                loan.currency(),
                valueDate,
                paymentMethodKey,
                externalReference,
                receipt,
                null,
                null,
                entry.entryId(),
                null,
                null,
                source,
                recordedBy,
                null));
        long credit = repo.creditBalance(loan.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("txn_type", recovery ? "recovery" : "repayment");
        after.put("amount_minor", amountMinor);
        after.put("value_date", valueDate.toString());
        after.put("receipt_no", receipt);
        if (!recovery) {
            repo.insertAllocations(txnId, txnId, applied.allocations());
            repo.saveItems(items, today);
            credit += applied.excessMinor();
            after.put("payoff", applied.payoff());
            after.put("excess_minor", applied.excessMinor());
        }
        repo.receipts(loan.id(), repo.totalPaid(loan.id()) + amountMinor, valueDate, credit);
        audit.record(AuditLog.Entry.created(
                recovery ? "lending.loan.recovery_recorded" : "lending.loan.repayment_recorded",
                SUBJECT,
                loan.id(),
                loan.branchId(),
                after));
        if (!recovery) {
            Position position = refresh(loan.id(), items, today);
            if (position.settled()) {
                close(loan, valueDate, recordedBy);
            }
        }
        return txnId;
    }

    /** FR-LCL-01: fully paid or waived; the pledges are released by the status move. */
    private void close(Loan loan, LocalDate on, UUID by) {
        loans.move(loan.id(), "active", "closed", by, null, Map.of());
        repo.closedOn(loan.id(), on);
        audit.record(new AuditLog.Entry(
                "lending.loan.closed",
                SUBJECT,
                loan.id(),
                loan.branchId(),
                Map.of("status", "active"),
                Map.of("status", "closed", "closed_on", on.toString())));
    }

    // ---- Reversal (FR-REP-05) -------------------------------------------------------------

    /** The checks of a reversal request, run again at execution. */
    Txn checkReversible(Loan loan, Txn txn) {
        if (!txn.loanId().equals(loan.id())) {
            throw ApiException.notFound();
        }
        if (!Set.of("repayment", "recovery").contains(txn.txnType())) {
            throw ApiException.rule("not_reversible", "Only a repayment or a recovery is reversed here.");
        }
        if (repo.reversed(txn.id())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "already_reversed",
                    "Already reversed",
                    "The transaction is reversed already.");
        }
        if (txn.txnType().equals("repayment") && !Set.of("active", "closed").contains(loan.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "A repayment on a " + loan.status() + " loan is not reversed.");
        }
        return txn;
    }

    /**
     * FR-REP-05: takes the repayment's money out and re-applies the later repayments in value
     * date order. When nothing else moves, the journal is the mirror of the original entry;
     * otherwise one entry posts the net difference.
     */
    void reverse(UUID txnId, String reason, UUID approvalId, UUID makerId, UUID checkerId) {
        Txn txn = repo.txn(txnId).orElseThrow(ApiException::notFound);
        Loan loan = loans.lock(txn.loanId()).orElseThrow(ApiException::notFound);
        checkReversible(loan, txn);
        LocalDate today = today();
        UUID reversalId = UUID.randomUUID();
        String key = "lending.txn:" + reversalId;
        Map<UUID, List<Allocation>> deltas = new LinkedHashMap<>();
        Replay replay = null;
        PostedEntry entry;
        if (txn.txnType().equals("recovery")) {
            entry = books.reverse(txn.journalEntryId(), today, txn.receiptNo(), key);
        } else {
            ProductTerms product = terms(loan);
            List<Txn> survivors = repo.activeRepayments(loan.id()).stream()
                    .filter(t -> !t.id().equals(txn.id()))
                    .toList();
            replay = Servicing.replay(
                    repo.items(loan.id()),
                    survivors.stream()
                            .map(t -> new Payment(t.id(), t.amountMinor(), t.valueDate()))
                            .toList(),
                    loan.interestMethod(),
                    product.flatEarlySettlementRebate(),
                    product.allocationOrder());
            Map<UUID, List<Allocation>> before = repo.allocationsByRepayment(loan.id());
            deltas.put(txn.id(), Servicing.difference(before.getOrDefault(txn.id(), List.of()), List.of()));
            boolean othersMoved = false;
            for (Txn s : survivors) {
                List<Allocation> d = Servicing.difference(
                        before.getOrDefault(s.id(), List.of()),
                        replay.allocations().get(s.id()));
                if (!d.isEmpty()) {
                    deltas.put(s.id(), d);
                    othersMoved = true;
                }
            }
            if (othersMoved) {
                List<Leg> legs = new ArrayList<>();
                legs.add(Leg.credit(books.methodAccount(txn.paymentMethodKey()), txn.amountMinor()));
                legs.addAll(netLegs(
                        loan.id(),
                        deltas.values().stream().flatMap(List::stream).toList()));
                entry = books.post(
                        loan.branchId(),
                        today,
                        txn.receiptNo(),
                        "Repayment reversal with re-allocation " + loan.loanNo(),
                        reversalId,
                        key,
                        legs);
            } else {
                entry = books.reverse(txn.journalEntryId(), today, txn.receiptNo(), key);
            }
        }
        repo.insertTxn(new Txn(
                reversalId,
                loan.branchId(),
                loan.id(),
                "reversal",
                txn.amountMinor(),
                txn.currency(),
                today,
                txn.paymentMethodKey(),
                null,
                null,
                reason,
                txn.id(),
                entry.entryId(),
                approvalId,
                null,
                "staff",
                makerId,
                null));
        deltas.forEach((appliesTo, rows) -> repo.insertAllocations(reversalId, appliesTo, rows));
        long credit = repo.creditBalance(loan.id());
        if (replay != null) {
            credit = replay.allocations().values().stream()
                    .flatMap(List::stream)
                    .filter(a -> a.component().equals(Servicing.OVERPAYMENT))
                    .mapToLong(Allocation::amountMinor)
                    .sum();
            repo.saveItems(replay.items(), today);
        }
        repo.receipts(
                loan.id(),
                repo.totalPaid(loan.id()) - txn.amountMinor(),
                repo.lastRepaymentOn(loan.id()).orElse(null),
                credit);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reversed_txn_id", txn.id());
        after.put("amount_minor", txn.amountMinor());
        after.put("reason", reason);
        after.put("re_allocated_repayments", Math.max(0, deltas.size() - 1));
        after.put("approval_request_id", approvalId);
        audit.record(
                AuditLog.Entry.created("lending.loan.repayment_reversed", SUBJECT, loan.id(), loan.branchId(), after));
        if (replay != null) {
            Position position = refresh(loan.id(), replay.items(), today);
            UUID by = checkerId != null ? checkerId : makerId;
            if (loan.status().equals("closed") && !position.settled()) {
                loans.move(loan.id(), "closed", "active", by, "repayment_reversed", Map.of());
                repo.closedOn(loan.id(), null);
            } else if (loan.status().equals("active") && position.settled()) {
                close(loan, today, by);
            }
        }
    }

    // ---- Write-off (FR-LCL-02) ------------------------------------------------------------

    /** Principal outstanding, the amount the request carries; refused when there is none. */
    long writeOffAmount(Loan loan) {
        requireStatus(loan, Set.of("active"));
        long principal = Servicing.position(repo.items(loan.id()), today()).principalOutstandingMinor();
        if (principal == 0) {
            throw ApiException.rule("nothing_to_write_off", "The loan has no principal outstanding.");
        }
        return principal;
    }

    void writeOff(UUID loanId, String reason, UUID approvalId, UUID makerId, UUID checkerId) {
        writeOff(loanId, reason, approvalId, makerId, checkerId, today());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeOffApproved(UUID loanId, LocalDate date, String reason, UUID makerId, UUID checkerId) {
        if (makerId.equals(checkerId)) {
            throw ApiException.rule("self_approval_forbidden", "The checker is never the maker.");
        }
        if (date.isAfter(today())) {
            throw ApiException.rule("value_date_in_future", "The write-off date is today or earlier.");
        }
        writeOff(loanId, reason, null, makerId, checkerId, date);
    }

    /** {@code today} is the write-off date: the business date for staff, an earlier one through the port. */
    private void writeOff(UUID loanId, String reason, UUID approvalId, UUID makerId, UUID checkerId, LocalDate today) {
        Loan loan = loans.lock(loanId).orElseThrow(ApiException::notFound);
        long principal = writeOffAmount(loan);
        List<Item> items = repo.items(loan.id());
        Position before = Servicing.position(items, today);
        UUID txnId = UUID.randomUUID();
        PostedEntry entry = books.post(
                loan.branchId(),
                today,
                loan.loanNo(),
                "Write-off " + loan.loanNo(),
                txnId,
                "lending.txn:" + txnId,
                List.of(
                        Leg.debit(LoanBooks.WRITE_OFF_EXPENSE, principal),
                        Leg.credit(LoanBooks.LOANS_RECEIVABLE, principal).forLoan(loan.id())));
        repo.insertTxn(new Txn(
                txnId,
                loan.branchId(),
                loan.id(),
                "write_off",
                principal,
                loan.currency(),
                today,
                null,
                null,
                null,
                reason,
                null,
                entry.entryId(),
                approvalId,
                null,
                "staff",
                makerId,
                null));
        repo.writeOffItems(items);
        repo.position(loan.id(), new Position(0, 0, 0, 0, 0, 0, null, true));
        repo.writtenOffOn(loan.id(), today);
        loans.move(loan.id(), "active", "written_off", checkerId != null ? checkerId : makerId, reason, Map.of());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "written_off");
        after.put("principal_minor", principal);
        after.put("interest_minor", before.interestOutstandingMinor());
        after.put("fees_minor", before.feesOutstandingMinor());
        after.put("penalties_minor", before.penaltiesOutstandingMinor());
        after.put("reason", reason);
        after.put("approval_request_id", approvalId);
        audit.record(new AuditLog.Entry(
                "lending.loan.written_off", SUBJECT, loan.id(), loan.branchId(), Map.of("status", "active"), after));
    }

    // ---- Payoff quote (FR-REP-06) ---------------------------------------------------------

    Quote quote(Loan loan, LocalDate valueDate) {
        requireStatus(loan, Set.of("active"));
        return Servicing.payoff(
                repo.items(loan.id()),
                valueDate,
                loan.interestMethod(),
                terms(loan).flatEarlySettlementRebate());
    }

    // ---- Shared ---------------------------------------------------------------------------

    /** Item statuses, balances, arrears and DPD as of {@code today} (R-DPD). */
    private Position refresh(UUID loanId, List<Item> items, LocalDate today) {
        repo.saveItems(items, today);
        Position position = Servicing.position(items, today);
        repo.position(loanId, position);
        return position;
    }

    /** A repayment's credits: one leg per component, with the loan as subledger where the account is controlled. */
    private static List<Leg> creditLegs(UUID loanId, List<Allocation> rows) {
        Map<String, Long> byComponent = new LinkedHashMap<>();
        for (Allocation a : rows) {
            if (!a.component().equals(Servicing.REBATE)) {
                byComponent.merge(a.component(), a.amountMinor(), Long::sum);
            }
        }
        List<Leg> legs = new ArrayList<>();
        byComponent.forEach((component, amount) ->
                legs.add(subledger(Leg.credit(LoanBooks.COMPONENT_ACCOUNTS.get(component), amount), loanId)));
        return legs;
    }

    /** The net of allocation differences by component: a negative sum is a debit, a positive one a credit. */
    private static List<Leg> netLegs(UUID loanId, List<Allocation> rows) {
        Map<String, Long> byComponent = new LinkedHashMap<>();
        for (Allocation a : rows) {
            if (!a.component().equals(Servicing.REBATE)) {
                byComponent.merge(a.component(), a.amountMinor(), Long::sum);
            }
        }
        List<Leg> legs = new ArrayList<>();
        byComponent.forEach((component, amount) -> {
            String account = LoanBooks.COMPONENT_ACCOUNTS.get(component);
            if (amount < 0) {
                legs.add(subledger(Leg.debit(account, -amount), loanId));
            } else if (amount > 0) {
                legs.add(subledger(Leg.credit(account, amount), loanId));
            }
        });
        return legs;
    }

    private static Leg subledger(Leg leg, UUID loanId) {
        return leg.systemKey().equals(LoanBooks.LOANS_RECEIVABLE)
                        || leg.systemKey().equals(LoanBooks.OVERPAYMENTS)
                ? leg.forLoan(loanId)
                : leg;
    }

    private ProductTerms terms(Loan loan) {
        return products.version(loan.productVersionId())
                .orElseThrow(() -> new IllegalStateException("loan " + loan.id() + " has no product version"));
    }

    /** FR-DOC-04: {@code RC-HQ-000123}, gap-free per branch (a row-locked tenant sequence). */
    private String number(String prefix, String sequence, UUID branchId) {
        String code = branches.all().stream()
                .filter(b -> b.id().equals(branchId))
                .findFirst()
                .orElseThrow()
                .code();
        return "%s-%s-%06d".formatted(prefix, code, sequences.next(sequence + ":" + code));
    }

    private static long sum(List<FeeCharge> fees, String timing) {
        return fees.stream()
                .filter(f -> f.timing().equals(timing))
                .mapToLong(FeeCharge::amountMinor)
                .reduce(0, Math::addExact);
    }

    static void requireStatus(Loan loan, Set<String> allowed) {
        if (!allowed.contains(loan.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The loan is " + loan.status() + ".");
        }
    }
}
