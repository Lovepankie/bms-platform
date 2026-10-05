package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.loans.internal.LoanApi.DecisionRequest;
import com.rincoltech.bms.lending.loans.internal.LoanApi.LoanResponse;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.PledgeRow;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.ProductTerms;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The decision on an appraised loan (FR-ORG-06, FR-ORG-07, FR-APR-03) and the expiry of approvals
 * nobody disbursed (FR-ORG-08). The score recommends; only this decides.
 */
@Service
class DecisionService {

    static final String APPROVAL_EXPIRED = "approval_expired";

    private final LoanRepository repo;
    private final LoanService loans;
    private final MemberLookup members;
    private final ProductCatalog products;
    private final TenantSettings settings;
    private final AuditLog audit;
    private final BusinessClock clock;

    DecisionService(
            LoanRepository repo,
            LoanService loans,
            MemberLookup members,
            ProductCatalog products,
            TenantSettings settings,
            AuditLog audit,
            BusinessClock clock) {
        this.repo = repo;
        this.loans = loans;
        this.members = members;
        this.products = products;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    LoanResponse decide(UUID id, String ifMatch, DecisionRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Principal principal = CurrentPrincipal.require();
        Loan loan = repo.lock(id)
                .filter(l -> principal.may("lending.loans.approve", l.branchId()))
                .orElseThrow(ApiException::notFound);
        if (loan.version() != expected) {
            throw Versions.conflict(loan.version());
        }
        if (!loan.status().equals("appraised")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "Only an appraised application is decided; this one is " + loan.status() + ".");
        }
        if (r.decision().equals("reject")) {
            return reject(loan, principal.userId(), r);
        }
        return approve(loan, principal.userId(), r);
    }

    private LoanResponse reject(Loan loan, UUID user, DecisionRequest r) {
        if (r.note() == null || r.note().isBlank()) {
            throw ApiException.validation(List.of(new FieldProblem("note", "required", "A rejection needs a reason.")));
        }
        String reason = r.note().trim();
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("rejected_reason", reason);
        repo.move(loan.id(), "appraised", "rejected", user, reason, columns);
        audit.record(new AuditLog.Entry(
                "lending.loan.rejected",
                "lending.loan",
                loan.id(),
                loan.branchId(),
                Map.of("status", "appraised"),
                Map.of("status", "rejected", "reason", reason)));
        return loans.get(loan.id());
    }

    private LoanResponse approve(Loan loan, UUID user, DecisionRequest r) {
        // FR-APR-03: the database CHECK holds this too; the service says why.
        if (user.equals(loan.submittedBy()) || user.equals(loan.appraisedBy())) {
            throw ApiException.rule(
                    "self_approval_forbidden",
                    "The approver can be neither the user who submitted the application nor the one who appraised it.");
        }
        ProductTerms product = products.version(loan.productVersionId()).orElseThrow();
        long principal =
                r.approvedPrincipalMinor() != null ? r.approvedPrincipalMinor() : loan.requestedPrincipalMinor();
        int term = r.approvedTermCount() != null ? r.approvedTermCount() : loan.requestedTermCount();
        checkTerms(loan, product, principal, term);
        checkMemberAndCover(loan, product, principal);

        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("approved_principal_minor", principal);
        columns.put("approved_term_count", term);
        columns.put("approved_by", user);
        columns.put("approved_at", Timestamp.from(clock.now()));
        repo.move(loan.id(), "appraised", "approved", user, note, columns);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "approved");
        after.put("approved_principal_minor", principal);
        after.put("approved_term_count", term);
        if (note != null) {
            after.put("note", note);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "appraised");
        before.put("requested_principal_minor", loan.requestedPrincipalMinor());
        before.put("requested_term_count", loan.requestedTermCount());
        audit.record(
                new AuditLog.Entry("lending.loan.approved", "lending.loan", loan.id(), loan.branchId(), before, after));
        return loans.get(loan.id());
    }

    /** FR-ORG-06: at or below what was requested, never higher, and still inside the product's limits. */
    private static void checkTerms(Loan loan, ProductTerms product, long principal, int term) {
        List<FieldProblem> problems = new ArrayList<>();
        if (principal > loan.requestedPrincipalMinor()) {
            problems.add(new FieldProblem(
                    "approved_principal_minor", "above_requested_principal", "Above the requested principal."));
        } else if (principal < product.minPrincipalMinor()) {
            problems.add(new FieldProblem(
                    "approved_principal_minor", "below_product_minimum", "Below the product minimum."));
        }
        if (term > loan.requestedTermCount()) {
            problems.add(new FieldProblem("approved_term_count", "above_requested_term", "Above the requested term."));
        } else if (term < product.minTermCount()) {
            problems.add(
                    new FieldProblem("approved_term_count", "below_product_minimum", "Below the product minimum."));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    problems.getFirst().code(),
                    "Outside what may be approved",
                    "See errors.",
                    problems);
        }
        ScheduleCalculator.instalments(product.withTermCount(term));
    }

    /**
     * FR-ORG-07: each failing check has its own code and blocks the approval. The member's row is
     * locked first, so two approvals for one member run one after the other and the second counts
     * the first.
     */
    private void checkMemberAndCover(Loan loan, ProductTerms product, long principal) {
        MemberSummary member = members.lock(loan.memberId()).orElseThrow();
        if (member.blacklisted()) {
            throw ApiException.rule("member_blacklisted", "The member is blacklisted.");
        }
        if (!member.kycStatus().equals("verified") && !settings.allowLoansBeforeKycVerified()) {
            throw ApiException.rule("kyc_not_verified", "The member's KYC is " + member.kycStatus() + ".");
        }
        if (product.minCollateralCoverBp() != null) {
            long pledged = repo.pledges(loan.id()).stream()
                    .mapToLong(PledgeRow::pledgedValueMinor)
                    .sum();
            // pledged / principal >= bp / 10,000, kept in integers.
            if (Math.multiplyExact(pledged, 10_000L)
                    < Math.multiplyExact(principal, (long) product.minCollateralCoverBp())) {
                throw ApiException.rule(
                        "collateral_below_product_minimum",
                        "Pledged collateral covers less than the product's minimum for this principal.");
            }
        }
        Integer max = settings.maxActiveLoansPerMember();
        if (max != null && repo.approvedOrActiveLoans(loan.memberId()) >= max) {
            throw ApiException.rule(
                    "max_active_loans_reached",
                    "The member already has " + max + " approved or active loan(s), the tenant's limit.");
        }
    }

    /**
     * FR-ORG-08: runs inside the nightly task's per-tenant transaction. The bound comes from the
     * business clock, which also stamps {@code approved_at}, so tests can move the date.
     *
     * @return the number of approvals expired
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int expireOverdue() {
        List<Loan> overdue = repo.overdueApprovals(
                Timestamp.from(clock.now().minus(Duration.ofDays(settings.approvalValidityDays()))));
        for (Loan loan : overdue) {
            Map<String, Object> columns = new LinkedHashMap<>();
            columns.put("cancelled_reason", APPROVAL_EXPIRED);
            repo.move(loan.id(), "approved", "cancelled", null, APPROVAL_EXPIRED, columns);
            audit.record(
                    new AuditLog.Entry(
                            "lending.loan.approval_expired",
                            "lending.loan",
                            loan.id(),
                            loan.branchId(),
                            Map.of("status", "approved"),
                            Map.of("status", "cancelled", "reason", APPROVAL_EXPIRED)),
                    null,
                    "system");
        }
        return overdue.size();
    }
}
