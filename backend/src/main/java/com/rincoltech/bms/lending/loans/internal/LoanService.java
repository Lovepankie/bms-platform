package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.collateral.CollateralLookup;
import com.rincoltech.bms.lending.collateral.CollateralLookup.CollateralSummary;
import com.rincoltech.bms.lending.loans.internal.LoanApi.CreateLoanRequest;
import com.rincoltech.bms.lending.loans.internal.LoanApi.Guarantor;
import com.rincoltech.bms.lending.loans.internal.LoanApi.GuarantorInput;
import com.rincoltech.bms.lending.loans.internal.LoanApi.LoanListItem;
import com.rincoltech.bms.lending.loans.internal.LoanApi.LoanPage;
import com.rincoltech.bms.lending.loans.internal.LoanApi.LoanResponse;
import com.rincoltech.bms.lending.loans.internal.LoanApi.Pledge;
import com.rincoltech.bms.lending.loans.internal.LoanApi.PledgeInput;
import com.rincoltech.bms.lending.loans.internal.LoanApi.ScheduleItem;
import com.rincoltech.bms.lending.loans.internal.LoanApi.StatusHistory;
import com.rincoltech.bms.lending.loans.internal.LoanApi.UpdateLoanRequest;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.GuarantorRow;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.PledgeRow;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.ProductTerms;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan origination up to the appraisal (FR-ORG-01 to FR-ORG-03) and the status moves of chapter 3
 * section 3.18 that belong to it: submit, return for correction, cancel. Every route checks the
 * loan's branch against its permission; a loan outside scope is a 404.
 */
@Service
class LoanService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final Set<String> CANCELLABLE = Set.of("draft", "submitted", "appraised", "approved");

    private final LoanRepository repo;
    private final MemberLookup members;
    private final ProductCatalog products;
    private final CollateralLookup collateral;
    private final TenantSequences sequences;
    private final TenantSettings settings;
    private final CurrentTenant currentTenant;
    private final AuditLog audit;
    private final BusinessClock clock;

    LoanService(
            LoanRepository repo,
            MemberLookup members,
            ProductCatalog products,
            CollateralLookup collateral,
            TenantSequences sequences,
            TenantSettings settings,
            CurrentTenant currentTenant,
            AuditLog audit,
            BusinessClock clock) {
        this.repo = repo;
        this.members = members;
        this.products = products;
        this.collateral = collateral;
        this.sequences = sequences;
        this.settings = settings;
        this.currentTenant = currentTenant;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * FR-ORG-01: a draft for a member at the member's home branch (FR-BR-05), on the product's
     * current version, terms copied from it. The creator is the responsible officer (FR-CLN-01).
     */
    @Transactional
    LoanResponse create(CreateLoanRequest r) {
        Principal principal = CurrentPrincipal.require();
        MemberSummary member = members.find(r.memberId())
                .filter(m -> principal.may("lending.loans.create", m.branchId()))
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("member_id", "unknown_member", "No such member in your scope."))));
        if (!member.status().equals("active")) {
            throw ApiException.rule("member_not_active", "Only an active member can apply for a loan.");
        }
        ProductTerms product = products.current(r.productId())
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("product_id", "unknown_product", "No such loan product."))));
        if (!product.productStatus().equals("active")) {
            throw ApiException.rule("product_archived", "The product is archived and takes no new applications.");
        }
        int term = r.requestedTermCount() == null ? product.defaults().termCount() : r.requestedTermCount();
        checkAgainstProduct(product, r.requestedPrincipalMinor(), term);
        requireNotPast(r.proposedDisbursementDate());
        var t = product.defaults();
        Loan loan = new Loan(
                UUID.randomUUID(),
                "LN%06d".formatted(sequences.next("loan_no")),
                member.branchId(),
                member.id(),
                product.versionId(),
                principal.userId(),
                "draft",
                "staff",
                r.purposeCategory(),
                blankToNull(r.purposeText()),
                product.currency(),
                r.requestedPrincipalMinor(),
                term,
                null,
                null,
                t.termUnit(),
                t.interestMethod(),
                t.interestRateBp(),
                t.rateUnit(),
                t.repaymentPattern(),
                t.instalmentFrequency(),
                r.proposedDisbursementDate(),
                null,
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
        repo.insert(loan);
        repo.history(loan.id(), "draft", principal.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("loan_no", loan.loanNo());
        after.put("member_id", member.id());
        after.put("product_version_id", product.versionId());
        after.put("requested_principal_minor", loan.requestedPrincipalMinor());
        after.put("requested_term_count", term);
        audit.record(AuditLog.Entry.created("lending.loan.created", "lending.loan", loan.id(), loan.branchId(), after));
        return get(loan.id());
    }

    @Transactional(readOnly = true)
    LoanResponse get(UUID id) {
        return respond(inScope(id, "lending.loans.read"));
    }

    @Transactional(readOnly = true)
    StatusHistory history(UUID id) {
        inScope(id, "lending.loans.read");
        return new StatusHistory(repo.historyOf(id));
    }

    @Transactional(readOnly = true)
    LoanPage list(
            List<UUID> branchIds,
            List<String> statuses,
            UUID memberId,
            UUID officerId,
            UUID productId,
            Integer limit,
            String cursor) {
        Principal principal = CurrentPrincipal.require();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Instant afterCreated = null;
        UUID afterId = null;
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        if (after != null) {
            afterCreated = after.at();
            afterId = after.id();
        }
        List<LoanListItem> rows = repo.page(
                principal.branchFilter("lending.loans.read", branchIds),
                statuses,
                memberId,
                officerId,
                productId,
                afterCreated,
                afterId,
                size + 1);
        boolean more = rows.size() > size;
        List<LoanListItem> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new LoanPage(List.copyOf(items), next);
    }

    /** Draft only; omitted fields are unchanged; the new terms are checked against the loan's product version. */
    @Transactional
    LoanResponse update(UUID id, String ifMatch, UpdateLoanRequest r) {
        Loan before = lockForChange(id, "lending.loans.create", ifMatch);
        requireStatus(before, Set.of("draft"));
        long principal =
                r.requestedPrincipalMinor() == null ? before.requestedPrincipalMinor() : r.requestedPrincipalMinor();
        int term = r.requestedTermCount() == null ? before.requestedTermCount() : r.requestedTermCount();
        checkAgainstProduct(versionOf(before), principal, term);
        String purpose = r.purposeCategory() == null ? before.purposeCategory() : r.purposeCategory();
        String text = r.purposeText() == null ? before.purposeText() : blankToNull(r.purposeText());
        requireNotPast(r.proposedDisbursementDate());
        LocalDate proposed =
                r.proposedDisbursementDate() == null ? before.proposedDisbursementDate() : r.proposedDisbursementDate();
        repo.updateDraft(id, principal, term, purpose, text, proposed);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("requested_principal_minor", principal);
        after.put("requested_term_count", term);
        after.put("purpose_category", purpose);
        audit.record(new AuditLog.Entry(
                "lending.loan.updated",
                "lending.loan",
                id,
                before.branchId(),
                Map.of(
                        "requested_principal_minor", before.requestedPrincipalMinor(),
                        "requested_term_count", before.requestedTermCount(),
                        "purpose_category", before.purposeCategory()),
                after));
        return get(id);
    }

    /** FR-ORG-02: replaces the draft's guarantors; each is another member of the tenant. */
    @Transactional
    LoanResponse setGuarantors(UUID id, String ifMatch, List<GuarantorInput> input) {
        Loan loan = lockForChange(id, "lending.loans.create", ifMatch);
        requireStatus(loan, Set.of("draft"));
        List<FieldProblem> problems = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<GuarantorRow> rows = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            GuarantorInput g = input.get(i);
            String field = "guarantors[" + i + "].member_id";
            if (g.memberId().equals(loan.memberId())) {
                problems.add(new FieldProblem(
                        field, "guarantor_is_borrower", "The borrower cannot guarantee their own loan."));
            } else if (!seen.add(g.memberId())) {
                problems.add(new FieldProblem(field, "duplicate", "Listed twice."));
            } else if (members.find(g.memberId()).isEmpty()) {
                problems.add(new FieldProblem(field, "unknown_member", "No such member."));
            } else {
                rows.add(new GuarantorRow(
                        g.memberId(), g.guaranteedAmountMinor(), blankToNull(g.relationship()), "active"));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        List<GuarantorRow> was = repo.guarantors(id);
        repo.replaceGuarantors(id, rows);
        repo.touch(id);
        audit.record(new AuditLog.Entry(
                "lending.loan.guarantors_set",
                "lending.loan",
                id,
                loan.branchId(),
                Map.of("guarantors", was.stream().map(LoanService::auditView).toList()),
                Map.of("guarantors", rows.stream().map(LoanService::auditView).toList())));
        return get(id);
    }

    /**
     * FR-ORG-02: replaces the draft's pledged collateral. Items come from the borrower's register,
     * held (pledged or in custody), in the loan's currency, pledged at no more than their value and
     * to no other open loan. Each item's row is locked first, in id order, so two loans cannot take
     * the same item at once and a release cannot slip between the check and the write.
     */
    @Transactional
    LoanResponse setPledges(UUID id, String ifMatch, List<PledgeInput> input) {
        Loan loan = lockForChange(id, "lending.loans.create", ifMatch);
        requireStatus(loan, Set.of("draft"));
        ProductTerms product = versionOf(loan);
        Map<UUID, CollateralSummary> items =
                lockItems(input.stream().map(PledgeInput::collateralId).toList());
        List<FieldProblem> problems = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<PledgeRow> rows = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            PledgeInput p = input.get(i);
            String prefix = "collateral[" + i + "].";
            if (!seen.add(p.collateralId())) {
                problems.add(new FieldProblem(prefix + "collateral_id", "duplicate", "Listed twice."));
                continue;
            }
            Refusal refusal = pledgeRefusal(loan, product, items.get(p.collateralId()), p.pledgedValueMinor());
            if (refusal != null) {
                problems.add(new FieldProblem(prefix + refusal.field(), refusal.code(), refusal.message()));
            } else if (repo.pledgedElsewhere(p.collateralId(), id)) {
                throw alreadyPledged();
            } else {
                rows.add(new PledgeRow(p.collateralId(), p.pledgedValueMinor()));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        List<PledgeRow> was = repo.pledges(id);
        try {
            repo.replacePledges(id, rows);
        } catch (DuplicateKeyException e) {
            // The one-open-pledge index: the backstop behind the lock and the check above.
            throw alreadyPledged();
        }
        repo.touch(id);
        audit.record(new AuditLog.Entry(
                "lending.loan.collateral_set",
                "lending.loan",
                id,
                loan.branchId(),
                Map.of("collateral", was.stream().map(LoanService::auditView).toList()),
                Map.of("collateral", rows.stream().map(LoanService::auditView).toList())));
        return get(id);
    }

    /** Why an item cannot back a loan; {@code field} is the pledge input field the problem belongs to. */
    private record Refusal(String field, String code, String message) {}

    /** The pledge rules, applied when the pledge is set and again at submit (the item may have changed since). */
    private static Refusal pledgeRefusal(Loan loan, ProductTerms product, CollateralSummary item, long pledgedValue) {
        if (item == null || !item.memberId().equals(loan.memberId())) {
            return new Refusal("collateral_id", "unknown_collateral", "No such item in the borrower's register.");
        }
        if (!item.custodyStatus().equals("pledged") && !item.custodyStatus().equals("in_custody")) {
            return new Refusal("collateral_id", "collateral_not_held", "The item is " + item.custodyStatus() + ".");
        }
        if (!item.currency().equals(loan.currency())) {
            return new Refusal("collateral_id", "currency_mismatch", "The item is valued in " + item.currency() + ".");
        }
        if (item.collateralValueMinor() == null) {
            // A pledged value is only meaningful against the item's value, and cover (FR-ORG-07) and
            // exposure are measured from it, so every pledge needs a valued item.
            return new Refusal("collateral_id", "collateral_not_valued", "The item has no valuation or estimate.");
        }
        if (pledgedValue > item.collateralValueMinor()) {
            return new Refusal(
                    "pledged_value_minor",
                    "pledge_exceeds_value",
                    "The item is valued at " + item.collateralValueMinor() + ".");
        }
        return null;
    }

    /** Locks the items in id order (one order for every caller, so two pledges cannot deadlock). */
    private Map<UUID, CollateralSummary> lockItems(List<UUID> ids) {
        Map<UUID, CollateralSummary> items = new LinkedHashMap<>();
        ids.stream()
                .distinct()
                .sorted()
                .forEach(cid -> collateral.lockForPledge(cid).ifPresent(item -> items.put(cid, item)));
        return items;
    }

    private static ApiException alreadyPledged() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "collateral_already_pledged",
                "Collateral already pledged",
                "The item already secures another open loan.");
    }

    private static Map<String, Object> auditView(PledgeRow p) {
        return Map.of("collateral_id", p.collateralId(), "pledged_value_minor", p.pledgedValueMinor());
    }

    private static Map<String, Object> auditView(GuarantorRow g) {
        return Map.of("member_id", g.memberId(), "guaranteed_amount_minor", g.amountMinor());
    }

    /**
     * FR-ORG-03: freezes the terms. Everything the draft collected is checked again as it stands
     * now: the borrower and each guarantor are active and not blacklisted, each pledged item is
     * still the borrower's, held, in the loan's currency, valued and free, the product's guarantor
     * and collateral requirements are met, and the member's KYC is verified (FR-MEM-05, unless the
     * tenant allows it). Each refusal has its own code. A loan with no proposed disbursement date
     * takes today's, so the dates it quotes stop moving.
     */
    @Transactional
    LoanResponse submit(UUID id, String ifMatch) {
        Loan loan = lockForChange(id, "lending.loans.create", ifMatch);
        requireStatus(loan, Set.of("draft"));
        ProductTerms product = versionOf(loan);
        MemberSummary member = members.find(loan.memberId()).orElseThrow();
        if (!member.status().equals("active")) {
            throw ApiException.rule("member_not_active", "The member is " + member.status() + ".");
        }
        if (member.blacklisted()) {
            throw ApiException.rule("member_blacklisted", "The member is blacklisted.");
        }
        List<GuarantorRow> guarantors = repo.guarantors(id);
        if (product.requiresGuarantor() && guarantors.isEmpty()) {
            throw ApiException.rule("guarantor_required", "The product requires at least one guarantor.");
        }
        for (GuarantorRow g : guarantors) {
            MemberSummary guarantor = members.find(g.memberId()).orElseThrow();
            if (!guarantor.status().equals("active")) {
                throw ApiException.rule(
                        "guarantor_not_active",
                        "Guarantor " + guarantor.memberNo() + " is " + guarantor.status() + ".");
            }
            if (guarantor.blacklisted()) {
                throw ApiException.rule(
                        "guarantor_blacklisted", "Guarantor " + guarantor.memberNo() + " is blacklisted.");
            }
        }
        List<PledgeRow> pledges = repo.pledges(id);
        if (product.requiresCollateral() && pledges.isEmpty()) {
            throw ApiException.rule("collateral_required", "The product requires pledged collateral.");
        }
        Map<UUID, CollateralSummary> items =
                lockItems(pledges.stream().map(PledgeRow::collateralId).toList());
        for (PledgeRow p : pledges) {
            Refusal refusal = pledgeRefusal(loan, product, items.get(p.collateralId()), p.pledgedValueMinor());
            if (refusal != null) {
                throw ApiException.rule(refusal.code(), refusal.message());
            }
            if (repo.pledgedElsewhere(p.collateralId(), id)) {
                throw alreadyPledged();
            }
        }
        if (!member.kycStatus().equals("verified") && !settings.allowLoansBeforeKycVerified()) {
            throw ApiException.rule("kyc_not_verified", "The member's KYC is " + member.kycStatus() + ".");
        }
        UUID user = CurrentPrincipal.require().userId();
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("submitted_by", user);
        columns.put("submitted_at", LoanRepository.NOW);
        if (loan.proposedDisbursementDate() == null) {
            columns.put("proposed_disbursement_date", today());
        }
        repo.move(id, "draft", "submitted", user, null, columns);
        audit.record(transition(loan, "lending.loan.submitted", "draft", "submitted", null));
        return get(id);
    }

    /** Chapter 3 section 3.18: back to draft with a note; the appraisal no longer stands. */
    @Transactional
    LoanResponse returnForCorrection(UUID id, String ifMatch, String note) {
        Loan loan = lockForChange(id, "lending.loans.approve", ifMatch);
        requireStatus(loan, Set.of("submitted", "appraised"));
        UUID user = CurrentPrincipal.require().userId();
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("appraised_by", null);
        repo.move(id, loan.status(), "draft", user, note.trim(), columns);
        audit.record(transition(loan, "lending.loan.returned", loan.status(), "draft", note.trim()));
        return get(id);
    }

    /**
     * Chapter 3 section 3.18: a loan officer cancels their own draft; a holder of
     * lending.loans.approve (branch manager) cancels any application not yet disbursed.
     */
    @Transactional
    LoanResponse cancel(UUID id, String ifMatch, String reason) {
        Loan loan = lockForChange(id, "lending.loans.cancel", ifMatch);
        requireStatus(loan, CANCELLABLE);
        Principal principal = CurrentPrincipal.require();
        boolean manager = principal.may("lending.loans.approve", loan.branchId());
        boolean ownDraft = loan.status().equals("draft") && principal.userId().equals(loan.createdBy());
        if (!manager && !ownDraft) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Only a branch manager can cancel this application.");
        }
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("cancelled_reason", reason.trim());
        repo.move(id, loan.status(), "cancelled", principal.userId(), reason.trim(), columns);
        audit.record(transition(loan, "lending.loan.cancelled", loan.status(), "cancelled", reason.trim()));
        return get(id);
    }

    private ProductTerms versionOf(Loan loan) {
        return products.version(loan.productVersionId()).orElseThrow();
    }

    /** FR-ORG-01: principal and term within the product's limits, and the term divides into instalments. */
    private static void checkAgainstProduct(ProductTerms p, long principal, int term) {
        List<FieldProblem> problems = new ArrayList<>();
        if (principal < p.minPrincipalMinor()) {
            problems.add(new FieldProblem(
                    "requested_principal_minor", "below_product_minimum", "Below the product minimum."));
        }
        if (principal > p.maxPrincipalMinor()) {
            problems.add(new FieldProblem(
                    "requested_principal_minor", "above_product_maximum", "Above the product maximum."));
        }
        if (term < p.minTermCount()) {
            problems.add(
                    new FieldProblem("requested_term_count", "below_product_minimum", "Below the product minimum."));
        }
        if (term > p.maxTermCount()) {
            problems.add(
                    new FieldProblem("requested_term_count", "above_product_maximum", "Above the product maximum."));
        }
        if (!problems.isEmpty()) {
            String code = problems.getFirst().code();
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, code, "Outside the product's limits", "See errors.", problems);
        }
        ScheduleCalculator.instalments(p.withTermCount(term));
    }

    private LoanResponse respond(Loan l) {
        ProductTerms product = versionOf(l);
        String memberNo =
                members.find(l.memberId()).map(MemberSummary::memberNo).orElse(null);
        List<Guarantor> guarantors = repo.guarantors(l.id()).stream()
                .map(g -> new Guarantor(
                        g.memberId(),
                        members.find(g.memberId()).map(MemberSummary::memberNo).orElse(null),
                        g.amountMinor(),
                        g.relationship(),
                        g.status()))
                .toList();
        List<Pledge> pledges = repo.pledges(l.id()).stream()
                .map(p -> {
                    CollateralSummary c = collateral.find(p.collateralId()).orElse(null);
                    return new Pledge(
                            p.collateralId(),
                            c == null ? null : c.collateralType(),
                            p.pledgedValueMinor(),
                            c == null ? null : c.collateralValueMinor());
                })
                .toList();
        List<ScheduleItem> schedule =
                Set.of("draft", "submitted", "appraised", "approved").contains(l.status()) ? provisional(l) : List.of();
        return new LoanResponse(
                l.id(),
                l.loanNo(),
                l.branchId(),
                l.memberId(),
                memberNo,
                product.productId(),
                product.productCode(),
                l.productVersionId(),
                l.officerUserId(),
                l.status(),
                l.channel(),
                l.purposeCategory(),
                l.purposeText(),
                l.currency(),
                l.requestedPrincipalMinor(),
                l.requestedTermCount(),
                l.approvedPrincipalMinor(),
                l.approvedTermCount(),
                l.termUnit(),
                l.interestMethod(),
                l.interestRateBp(),
                l.rateUnit(),
                l.repaymentPattern(),
                l.instalmentFrequency(),
                l.proposedDisbursementDate(),
                l.submittedBy(),
                l.submittedAt(),
                l.appraisedBy(),
                l.approvedBy(),
                l.approvedAt(),
                l.rejectedReason(),
                l.cancelledReason(),
                guarantors,
                pledges,
                schedule,
                l.createdBy(),
                l.createdAt(),
                l.updatedAt(),
                l.version());
    }

    /**
     * FR-ORG-03: display only, from the loan's own copied terms and its version's fees, so it
     * matches the product preview and a later product edit cannot change it. Dates run from the
     * proposed disbursement date; a draft without one uses today, and submit stores it. Once
     * approved it previews the approved principal and term (FR-ORG-06).
     */
    private List<ScheduleItem> provisional(Loan l) {
        return scheduleOf(l).stream()
                .map(i -> new ScheduleItem(
                        i.no(), i.dueDate(), i.principalMinor(), i.interestMinor(), i.feeMinor(), i.totalMinor()))
                .toList();
    }

    List<ScheduleCalculator.Item> scheduleOf(Loan l) {
        var terms = new ScheduleCalculator.Terms(
                l.interestMethod(),
                l.interestRateBp(),
                l.rateUnit(),
                l.termUnit(),
                l.approvedTermCount() != null ? l.approvedTermCount() : l.requestedTermCount(),
                l.repaymentPattern(),
                l.instalmentFrequency());
        long principal = l.approvedPrincipalMinor() != null ? l.approvedPrincipalMinor() : l.requestedPrincipalMinor();
        return ScheduleCalculator.schedule(
                terms,
                principal,
                products.addedFeesMinor(l.productVersionId(), principal),
                l.proposedDisbursementDate() != null ? l.proposedDisbursementDate() : today());
    }

    private LocalDate today() {
        return clock.today(currentTenant.profile().timezone());
    }

    /** A proposed disbursement date is today or later. */
    private void requireNotPast(LocalDate proposed) {
        if (proposed != null && proposed.isBefore(today())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("proposed_disbursement_date", "in_the_past", "Must be today or later.")));
        }
    }

    private Loan inScope(UUID id, String permission) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id)
                .filter(l -> principal.may(permission, l.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    /** If-Match first, then scope (404 outside it), then version. */
    private Loan lockForChange(UUID id, String permission, String ifMatch) {
        int expected = Versions.fromIfMatch(ifMatch);
        Principal principal = CurrentPrincipal.require();
        Loan loan = repo.lock(id)
                .filter(l -> principal.may(permission, l.branchId()))
                .orElseThrow(ApiException::notFound);
        if (loan.version() != expected) {
            throw Versions.conflict(loan.version());
        }
        return loan;
    }

    private static void requireStatus(Loan loan, Set<String> allowed) {
        if (!allowed.contains(loan.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The loan is " + loan.status() + ".");
        }
    }

    private static AuditLog.Entry transition(Loan loan, String action, String from, String to, String reason) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", to);
        if (reason != null) {
            after.put("reason", reason);
        }
        return new AuditLog.Entry(action, "lending.loan", loan.id(), loan.branchId(), Map.of("status", from), after);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
