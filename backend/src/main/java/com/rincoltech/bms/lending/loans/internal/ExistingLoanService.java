package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.approvals.RequestSupersededException;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.identity.StaffDirectory;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.GoLiveDate;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ExistingLoan;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ExistingLoanPage;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ExistingLoanSummary;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.GoLive;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.RecordRequest;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.Repayment;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ScheduleRow;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanRepository.Facts;
import com.rincoltech.bms.lending.loans.internal.LoanBooks.Leg;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Position;
import com.rincoltech.bms.lending.loans.internal.ServicingRepository.Txn;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.ProductTerms;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Existing loans brought in from a paper register (ADR-071; FR-EXL-01 to FR-EXL-07). A tenant administrator or branch
 * manager records the loan with its real disbursement date, terms and past repayments; the app builds the schedule from
 * that date, applies the repayments by the product's allocation order and shows the true position. The loan waits as
 * {@code brought_in}, counting nowhere, until a second person approves {@code loan_bring_in}; the approval writes the
 * schedule, the historic transactions (no journal, no receipt number) and one opening entry dated at go-live. Only a
 * loan disbursed before the business's go-live date may come in this way, so it cannot backdate new lending.
 */
@Service
class ExistingLoanService {

    static final String PERMISSION = "lending.loans.bring_in";
    static final String BASE = "/api/v1/lending/existing-loans";
    static final String OPENING_SOURCE = "loan_opening";

    private final LoanRepository loans;
    private final ServicingRepository servicing;
    private final ExistingLoanRepository repo;
    private final MemberLookup members;
    private final ProductCatalog products;
    private final TenantSequences sequences;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final Approvals approvals;
    private final GoLiveDate goLiveDate;
    private final StaffDirectory staff;
    private final LoanBooks books;
    private final Idempotency idempotency;

    ExistingLoanService(
            LoanRepository loans,
            ServicingRepository servicing,
            ExistingLoanRepository repo,
            MemberLookup members,
            ProductCatalog products,
            TenantSequences sequences,
            CurrentTenant currentTenant,
            BusinessClock clock,
            AuditLog audit,
            Approvals approvals,
            GoLiveDate goLiveDate,
            StaffDirectory staff,
            LoanBooks books,
            Idempotency idempotency) {
        this.loans = loans;
        this.servicing = servicing;
        this.repo = repo;
        this.members = members;
        this.products = products;
        this.sequences = sequences;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.audit = audit;
        this.approvals = approvals;
        this.goLiveDate = goLiveDate;
        this.staff = staff;
        this.books = books;
        this.idempotency = idempotency;
    }

    /** A loan as entered, before anything is saved: from the form or from one loan of the import. */
    record Draft(
            MemberSummary member,
            ProductTerms product,
            long principalMinor,
            LocalDate disbursementDate,
            int termCount,
            Long agreedTotalMinor,
            String purposeCategory,
            String registerRef,
            List<BringIn.Line> lines,
            String channel) {}

    /** A draft that passed every check, with the schedule and the position the register leaves. */
    record Checked(Draft draft, LocalDate goLive, List<ScheduleCalculator.Item> schedule, BringIn.Plan plan) {}

    // ---- Go-live (ADR-071 decision 5) ----------------------------------------------------------

    @Transactional(readOnly = true)
    GoLive goLive() {
        return goLiveNow();
    }

    /** The date the Owner or the operator set, else the business date of the first administrator sign-in, else none. */
    GoLive goLiveNow() {
        var set = goLiveDate.set();
        if (set.isPresent()) {
            return new GoLive(set.get(), "set");
        }
        ZoneId zone = currentTenant.profile().timezone();
        return staff.firstAdminSignIn()
                .map(at -> new GoLive(at.atZone(zone).toLocalDate(), "first_admin_sign_in"))
                .orElse(new GoLive(null, "none"));
    }

    // ---- Record (FR-EXL-01, FR-EXL-02) -------------------------------------------------------

    /** A check (dry run) saves nothing and needs no key; a record needs an Idempotency-Key and waits for a checker. */
    @Transactional
    Outcome<ExistingLoan> record(RecordRequest r, boolean dryRun, String key) {
        if (dryRun) {
            return new Outcome<>(view(check(draft(r)), null, null), false);
        }
        return idempotency.once(key, "POST", BASE, r, ExistingLoan.class, () -> {
            Checked checked = check(draft(r));
            return detail(create(checked));
        });
    }

    private Draft draft(RecordRequest r) {
        Principal principal = CurrentPrincipal.require();
        MemberSummary member = members.find(r.memberId())
                .filter(m -> principal.may(PERMISSION, m.branchId()))
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("member_id", "unknown_member", "No such member in your scope."))));
        ProductTerms product = products.current(r.productId())
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("product_id", "unknown_product", "No such loan product."))));
        List<BringIn.Line> lines = new ArrayList<>();
        for (int i = 0; i < r.repayments().size(); i++) {
            Repayment p = r.repayments().get(i);
            lines.add(new BringIn.Line(
                    i + 1, p.valueDate(), p.amountMinor(), p.paymentMethodKey(), blankToNull(p.note())));
        }
        return new Draft(
                member,
                product,
                r.principalMinor(),
                r.disbursementDate(),
                r.termCount() == null ? product.defaults().termCount() : r.termCount(),
                r.agreedTotalMinor(),
                r.purposeCategory() == null ? "other" : r.purposeCategory(),
                blankToNull(r.registerRef()),
                List.copyOf(lines),
                "staff");
    }

    /**
     * Every rule of FR-EXL-02 and FR-EXL-03 on a draft, as 422 with the fields at fault; the import reports the same
     * refusals row by row. Reads only.
     */
    Checked check(Draft d) {
        GoLive goLive = goLiveNow();
        if (goLive.goLiveDate() == null) {
            throw ApiException.rule(
                    "go_live_not_set",
                    "The business has no go-live date yet: the Owner sets it in the settings, or it is the day an"
                            + " administrator first signs in.");
        }
        LocalDate live = goLive.goLiveDate();
        LocalDate today = today();
        List<FieldProblem> problems = new ArrayList<>();
        if (!d.member().status().equals("active")) {
            problems.add(new FieldProblem("member_id", "member_not_active", "Only an active member's loan comes in."));
        }
        ProductTerms p = d.product();
        if (!p.productStatus().equals("active")) {
            problems.add(new FieldProblem("product_id", "product_archived", "The product is archived."));
        }
        if (d.principalMinor() < p.minPrincipalMinor() || d.principalMinor() > p.maxPrincipalMinor()) {
            problems.add(new FieldProblem(
                    "principal_minor", "outside_product_limits", "The principal is outside the product's limits."));
        }
        if (d.termCount() < p.minTermCount() || d.termCount() > p.maxTermCount()) {
            problems.add(new FieldProblem(
                    "term_count", "outside_product_limits", "The term is outside the product's limits."));
        }
        if (d.agreedTotalMinor() != null && d.agreedTotalMinor() < d.principalMinor()) {
            problems.add(new FieldProblem(
                    "agreed_total_minor",
                    "agreed_total_below_principal",
                    "The agreed total is at least the principal."));
        }
        if (!d.disbursementDate().isBefore(live)) {
            problems.add(new FieldProblem(
                    "disbursement_date",
                    "disbursement_not_before_go_live",
                    "Only a loan disbursed before the go-live date (" + live + ") is brought in; record a new loan"
                            + " the usual way."));
        } else if (d.disbursementDate().isAfter(today)) {
            problems.add(new FieldProblem("disbursement_date", "value_date_in_future", "Today or earlier."));
        }
        for (BringIn.Line line : d.lines()) {
            String field = "repayments[" + (line.lineNo() - 1) + "].value_date";
            if (line.valueDate().isBefore(d.disbursementDate())) {
                problems.add(new FieldProblem(field, "before_disbursement", "Before the disbursement date."));
            } else if (!line.valueDate().isBefore(live)) {
                problems.add(new FieldProblem(
                        field,
                        "repayment_not_before_go_live",
                        "A repayment on or after the go-live date (" + live + ") is recorded as a normal repayment"
                                + " once the loan is approved."));
            } else if (line.valueDate().isAfter(today)) {
                problems.add(new FieldProblem(field, "value_date_in_future", "Today or earlier."));
            }
        }
        if (d.registerRef() != null && repo.registerRefTaken(d.registerRef())) {
            problems.add(new FieldProblem(
                    "register_ref", "register_ref_taken", "A loan with this register number is already in the app."));
        }
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        List<ScheduleCalculator.Item> schedule = schedule(
                p.withTermCount(d.termCount()),
                d.principalMinor(),
                d.agreedTotalMinor(),
                p.versionId(),
                d.disbursementDate());
        BringIn.Plan plan = BringIn.apply(
                BringIn.items(schedule),
                d.lines(),
                d.agreedTotalMinor() != null ? "flat" : p.defaults().interestMethod(),
                p.flatEarlySettlementRebate(),
                p.allocationOrder(),
                today);
        BringIn.Line over = plan.overpaid();
        if (over != null) {
            throw invalid(List.of(new FieldProblem(
                    "repayments[" + (over.lineNo() - 1) + "].amount_minor",
                    "repayments_exceed_balance",
                    "The repayments come to more than the loan owed; check the amounts against the register.")));
        }
        return new Checked(d, live, schedule, plan);
    }

    /** Saves the checked loan as {@code brought_in} and asks for the checker; returns the loan id. */
    UUID create(Checked c) {
        Principal principal = CurrentPrincipal.require();
        Draft d = c.draft();
        ProductTerms p = d.product();
        var t = p.defaults();
        Loan loan = new Loan(
                UUID.randomUUID(),
                "LN%06d".formatted(sequences.next("loan_no")),
                d.member().branchId(),
                d.member().id(),
                p.versionId(),
                principal.userId(),
                "brought_in",
                d.channel(),
                d.purposeCategory(),
                null,
                p.currency(),
                d.principalMinor(),
                d.termCount(),
                d.principalMinor(),
                d.termCount(),
                t.termUnit(),
                d.agreedTotalMinor() != null ? "flat" : t.interestMethod(),
                t.interestRateBp(),
                t.rateUnit(),
                t.repaymentPattern(),
                t.instalmentFrequency(),
                d.disbursementDate(),
                principal.userId(),
                null,
                null,
                null,
                null,
                null,
                null,
                principal.userId(),
                null,
                null,
                1);
        repo.insert(loan, d.disbursementDate(), c.goLive(), d.registerRef(), d.agreedTotalMinor());
        repo.insertLines(loan.id(), d.lines());
        loans.history(loan.id(), "brought_in", principal.userId());
        Position position = c.plan().position();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("loan_no", loan.loanNo());
        after.put("channel", d.channel());
        after.put("member_id", d.member().id());
        after.put("product_version_id", p.versionId());
        after.put("principal_minor", d.principalMinor());
        after.put("disbursement_date", d.disbursementDate().toString());
        after.put("term_count", d.termCount());
        after.put("agreed_total_minor", d.agreedTotalMinor());
        after.put("register_ref", d.registerRef());
        after.put("go_live_date", c.goLive().toString());
        after.put("repayments", d.lines().size());
        after.put("repaid_minor", c.plan().totalPaidMinor());
        after.put("principal_outstanding_minor", position.principalOutstandingMinor());
        after.put("days_past_due", position.daysPastDue());
        audit.record(AuditLog.Entry.created(
                "lending.loan.brought_in_recorded", LoanServicer.SUBJECT, loan.id(), loan.branchId(), after));
        request(loan.id(), loan.branchId(), 1, d.principalMinor(), p.currency(), c);
        return loan.id();
    }

    private void request(UUID loanId, UUID branchId, int version, long principal, String currency, Checked c) {
        Position position = c.plan().position();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(BringInAction.GO_LIVE, c.goLive().toString());
        payload.put("disbursement_date", c.draft().disbursementDate().toString());
        payload.put("repayments", c.draft().lines().size());
        payload.put("repaid_minor", c.plan().totalPaidMinor());
        payload.put("principal_outstanding_minor", position.principalOutstandingMinor());
        payload.put(
                "total_outstanding_minor",
                position.principalOutstandingMinor()
                        + position.interestOutstandingMinor()
                        + position.feesOutstandingMinor());
        payload.put("days_past_due", position.daysPastDue());
        approvals.request(
                new ActionRequest(BringInAction.TYPE, branchId, loanId, version, principal, currency, payload));
    }

    // ---- Approval (FR-EXL-04, FR-EXL-05) ---------------------------------------------------------

    /**
     * The checker's approval, in the approval's transaction: the schedule from the real date, the historic
     * disbursement and repayments with their allocation rows, the position, one opening entry dated at go-live for the
     * principal still owed, and the loan active (closed when the register shows it repaid in full).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void approve(UUID loanId, UUID approvalId, UUID makerId, UUID checkerId) {
        Loan loan = loans.lock(loanId).orElseThrow(ApiException::notFound);
        LoanServicer.requireStatus(loan, Set.of("brought_in"));
        Facts f = repo.facts(loanId).orElseThrow();
        LocalDate live = goLiveNow().goLiveDate();
        if (!f.goLiveOn().equals(live)) {
            throw new RequestSupersededException(
                    "go_live_changed",
                    "Go-live date changed",
                    "The go-live date is now " + live + ", not " + f.goLiveOn()
                            + " as when the loan was recorded. This request is closed; ask for approval again to check"
                            + " the loan against the new date.",
                    Map.of());
        }
        LocalDate today = today();
        ProductTerms product = products.version(loan.productVersionId()).orElseThrow();
        long principal = loan.approvedPrincipalMinor();
        List<ScheduleCalculator.Item> schedule =
                schedule(termsOf(loan), principal, f.agreedTotalMinor(), loan.productVersionId(), f.disbursementDate());
        servicing.insertItems(loanId, schedule, today);
        List<Item> items = servicing.items(loanId);
        BringIn.Plan plan = BringIn.apply(
                items,
                repo.lines(loanId),
                loan.interestMethod(),
                product.flatEarlySettlementRebate(),
                product.allocationOrder(),
                today);
        if (plan.overpaid() != null) {
            throw ApiException.rule(
                    "repayments_exceed_balance", "The repayments come to more than the loan owed; record it again.");
        }
        String source = "import".equals(loan.channel()) ? "import" : "staff";
        servicing.insertHistoricTxn(historic(
                loan, "disbursement", principal, f.disbursementDate(), null, null, approvalId, source, makerId));
        for (BringIn.Paid paid : plan.paid()) {
            BringIn.Line line = paid.line();
            Txn txn = historic(
                    loan,
                    "repayment",
                    line.amountMinor(),
                    line.valueDate(),
                    line.paymentMethodKey(),
                    line.note(),
                    approvalId,
                    source,
                    makerId);
            servicing.insertHistoricTxn(txn);
            servicing.insertAllocations(txn.id(), txn.id(), paid.applied().allocations());
        }
        servicing.saveItems(items, today);
        servicing.disbursed(loanId, f.disbursementDate(), schedule.getLast().dueDate(), principal);
        Position position = plan.position();
        servicing.position(loanId, position);
        servicing.receipts(loanId, plan.totalPaidMinor(), plan.lastRepaymentOn(), 0);
        UUID entryId = null;
        long owed = position.principalOutstandingMinor();
        if (owed > 0) {
            // ADR-071 decision 4: the loan enters the books at go-live as an opening balance; its old cash is not
            // posted again as today's cash.
            PostedEntry entry = books.post(
                    OPENING_SOURCE,
                    loan.branchId(),
                    f.goLiveOn(),
                    loan.loanNo(),
                    "Opening balance " + loan.loanNo(),
                    loanId,
                    "lending.opening:" + loanId,
                    List.of(
                            Leg.debit(LoanBooks.LOANS_RECEIVABLE, owed).forLoan(loanId),
                            Leg.credit(LoanBooks.OPENING_EQUITY, owed)));
            entryId = entry.entryId();
        }
        LocalDate broughtInOn = today.isAfter(f.goLiveOn()) ? today : f.goLiveOn();
        repo.approved(loanId, broughtInOn, entryId, checkerId);
        String to = position.settled() ? "closed" : "active";
        loans.move(loanId, "brought_in", to, checkerId, null, Map.of());
        if (position.settled()) {
            servicing.closedOn(loanId, plan.settledOn());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", to);
        after.put("disbursed_on", f.disbursementDate().toString());
        after.put("brought_in_on", broughtInOn.toString());
        after.put("go_live_date", f.goLiveOn().toString());
        after.put("principal_minor", principal);
        after.put("repayments", plan.paid().size());
        after.put("repaid_minor", plan.totalPaidMinor());
        after.put("principal_outstanding_minor", owed);
        after.put("interest_outstanding_minor", position.interestOutstandingMinor());
        after.put("fees_outstanding_minor", position.feesOutstandingMinor());
        after.put("days_past_due", position.daysPastDue());
        after.put("opening_entry_id", entryId);
        after.put("approval_request_id", approvalId);
        audit.record(new AuditLog.Entry(
                "lending.loan.brought_in",
                LoanServicer.SUBJECT,
                loanId,
                loan.branchId(),
                Map.of("status", "brought_in"),
                after));
    }

    private static Txn historic(
            Loan loan,
            String type,
            long amount,
            LocalDate valueDate,
            String method,
            String note,
            UUID approvalId,
            String source,
            UUID recordedBy) {
        return new Txn(
                UUID.randomUUID(),
                loan.branchId(),
                loan.id(),
                type,
                amount,
                loan.currency(),
                valueDate,
                method,
                note,
                null,
                null,
                null,
                null,
                approvalId,
                null,
                source,
                recordedBy,
                null);
    }

    // ---- Withdraw and ask again (FR-EXL-04) -----------------------------------------------------

    /** The maker (or anyone holding the permission in the branch) withdraws a loan still waiting; it ends cancelled. */
    @Transactional
    ExistingLoan cancel(UUID loanId, String reason) {
        Loan loan = lockInScope(loanId, PERMISSION);
        LoanServicer.requireStatus(loan, Set.of("brought_in"));
        UUID by = CurrentPrincipal.require().userId();
        loans.move(loanId, "brought_in", "cancelled", by, reason.trim(), Map.of("cancelled_reason", reason.trim()));
        audit.record(new AuditLog.Entry(
                "lending.loan.brought_in_cancelled",
                LoanServicer.SUBJECT,
                loanId,
                loan.branchId(),
                Map.of("status", "brought_in"),
                Map.of("status", "cancelled", "reason", reason.trim())));
        return detail(loanId);
    }

    /**
     * A new request after the last one was rejected, expired or went stale: the loan is checked again against today's
     * go-live date, which it then keeps.
     */
    @Transactional
    ExistingLoan askAgain(UUID loanId) {
        Loan loan = lockInScope(loanId, PERMISSION);
        LoanServicer.requireStatus(loan, Set.of("brought_in"));
        Facts f = repo.facts(loanId).orElseThrow();
        Draft d = new Draft(
                members.find(loan.memberId()).orElseThrow(),
                products.version(loan.productVersionId()).orElseThrow(),
                loan.approvedPrincipalMinor(),
                f.disbursementDate(),
                loan.approvedTermCount(),
                f.agreedTotalMinor(),
                loan.purposeCategory(),
                null,
                repo.lines(loanId),
                loan.channel());
        Checked c = check(d);
        int version = loan.version();
        if (!c.goLive().equals(f.goLiveOn())) {
            repo.goLiveOn(loanId, c.goLive());
            version++;
        }
        request(loanId, loan.branchId(), version, loan.approvedPrincipalMinor(), loan.currency(), c);
        return detail(loanId);
    }

    // ---- Reads -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    ExistingLoan get(UUID loanId) {
        inScope(loanId);
        return detail(loanId);
    }

    @Transactional(readOnly = true)
    ExistingLoanPage list(List<String> statuses) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> branches = principal.branchFilter("lending.loans.read", null);
        List<ExistingLoanSummary> rows = repo.list(branches, statuses, 200).stream()
                .map(s -> new ExistingLoanSummary(
                        s.loanId(),
                        s.loanNo(),
                        s.status(),
                        s.memberId(),
                        members.find(s.memberId()).map(MemberSummary::memberNo).orElse(null),
                        s.principalMinor(),
                        s.disbursementDate(),
                        s.registerRef(),
                        s.approvalRequestId()))
                .toList();
        return new ExistingLoanPage(rows);
    }

    /** The loan as recorded: while it waits, the position the register leaves; once approved, the loan as it stands. */
    ExistingLoan detail(UUID loanId) {
        Loan loan = loans.find(loanId).orElseThrow(ApiException::notFound);
        Facts f = repo.facts(loanId).filter(x -> x.goLiveOn() != null).orElseThrow(ApiException::notFound);
        ProductTerms product = products.version(loan.productVersionId()).orElseThrow();
        List<BringIn.Line> lines = repo.lines(loanId);
        LocalDate today = today();
        List<Item> items;
        Position position;
        long paid;
        LocalDate last;
        if (Set.of("brought_in", "cancelled").contains(loan.status())) {
            BringIn.Plan plan = BringIn.apply(
                    BringIn.items(schedule(
                            termsOf(loan),
                            loan.approvedPrincipalMinor(),
                            f.agreedTotalMinor(),
                            loan.productVersionId(),
                            f.disbursementDate())),
                    lines,
                    loan.interestMethod(),
                    product.flatEarlySettlementRebate(),
                    product.allocationOrder(),
                    today);
            items = plan.items();
            position = plan.position();
            paid = plan.totalPaidMinor();
            last = plan.lastRepaymentOn();
        } else {
            items = servicing.items(loanId);
            position = Servicing.position(items, today);
            var balances = servicing.balances(loanId).orElseThrow();
            paid = balances.totalPaidMinor();
            last = balances.lastRepaymentOn();
        }
        return new ExistingLoan(
                loanId,
                loan.loanNo(),
                loan.status(),
                loan.branchId(),
                loan.memberId(),
                members.find(loan.memberId()).map(MemberSummary::memberNo).orElse(null),
                product.productId(),
                product.productCode(),
                loan.currency(),
                loan.approvedPrincipalMinor(),
                f.disbursementDate(),
                loan.approvedTermCount(),
                f.agreedTotalMinor(),
                f.registerRef(),
                f.goLiveOn(),
                f.broughtInOn(),
                "brought_in".equals(loan.status())
                        ? repo.pendingApproval(loanId).orElse(null)
                        : null,
                repayments(lines),
                rows(items, today),
                position(position, paid, last));
    }

    /** The view of a check: nothing is saved, so there is no loan id, number or request. */
    ExistingLoan view(Checked c, UUID loanId, String loanNo) {
        Draft d = c.draft();
        return new ExistingLoan(
                loanId,
                loanNo,
                null,
                d.member().branchId(),
                d.member().id(),
                d.member().memberNo(),
                d.product().productId(),
                d.product().productCode(),
                d.product().currency(),
                d.principalMinor(),
                d.disbursementDate(),
                d.termCount(),
                d.agreedTotalMinor(),
                d.registerRef(),
                c.goLive(),
                null,
                null,
                repayments(d.lines()),
                rows(c.plan().items(), today()),
                position(
                        c.plan().position(), c.plan().totalPaidMinor(), c.plan().lastRepaymentOn()));
    }

    private static List<Repayment> repayments(List<BringIn.Line> lines) {
        return lines.stream()
                .map(l -> new Repayment(l.valueDate(), l.amountMinor(), l.paymentMethodKey(), l.note()))
                .toList();
    }

    private static List<ScheduleRow> rows(List<Item> items, LocalDate today) {
        return items.stream()
                .map(i -> new ScheduleRow(
                        i.no,
                        i.dueDate,
                        i.principalDue,
                        i.interestDue,
                        i.feesDue,
                        i.paidTotal(),
                        i.unpaidTotal(),
                        i.status(today)))
                .toList();
    }

    private static ExistingLoanApi.Position position(Position p, long paid, LocalDate last) {
        return new ExistingLoanApi.Position(
                p.principalOutstandingMinor(),
                p.interestOutstandingMinor(),
                p.feesOutstandingMinor(),
                p.principalOutstandingMinor()
                        + p.interestOutstandingMinor()
                        + p.feesOutstandingMinor()
                        + p.penaltiesOutstandingMinor(),
                p.arrearsMinor(),
                p.daysPastDue(),
                p.nextDueDate(),
                paid,
                last,
                p.settled());
    }

    // ---- Shared ------------------------------------------------------------------------------------

    /**
     * The schedule from the real disbursement date: the agreed total spread flat when the register gives one (no fee
     * added), else the product's terms with its fees added to the loan, exactly as a disbursement on that date builds it.
     * Fees deducted from the proceeds or paid upfront were settled in cash before go-live and are not recorded.
     */
    List<ScheduleCalculator.Item> schedule(
            ScheduleCalculator.Terms terms, long principal, Long agreedTotal, UUID versionId, LocalDate date) {
        return agreedTotal != null
                ? ScheduleCalculator.scheduleForTotal(terms, principal, agreedTotal, date)
                : ScheduleCalculator.schedule(terms, principal, products.addedFeesMinor(versionId, principal), date);
    }

    private static ScheduleCalculator.Terms termsOf(Loan l) {
        return new ScheduleCalculator.Terms(
                l.interestMethod(),
                l.interestRateBp(),
                l.rateUnit(),
                l.termUnit(),
                l.approvedTermCount(),
                l.repaymentPattern(),
                l.instalmentFrequency());
    }

    private Loan inScope(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return loans.find(id)
                .filter(l -> principal.may("lending.loans.read", l.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private Loan lockInScope(UUID id, String permission) {
        Principal principal = CurrentPrincipal.require();
        return loans.lock(id)
                .filter(l -> principal.may(permission, l.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    LocalDate today() {
        return clock.today(currentTenant.profile().timezone());
    }

    static ApiException invalid(List<FieldProblem> problems) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                problems.getFirst().code(),
                "The existing loan cannot be brought in",
                problems.getFirst().message(),
                problems);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
