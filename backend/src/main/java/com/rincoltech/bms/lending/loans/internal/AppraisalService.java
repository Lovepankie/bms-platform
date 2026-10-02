package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.AppraisalRow;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.ExposureLoan;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.PledgeRow;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Appraisal (FR-ORG-04, FR-ORG-05). Scores the loan with the tenant's weights and stores every
 * input, the exposure and the result as an immutable snapshot; the first appraisal moves the loan
 * to {@code appraised}, a later one only records a newer snapshot and appraiser.
 */
@Service
class AppraisalService {

    @Schema(name = "LoanAppraisalRequest")
    record AppraisalRequest(
            @PositiveOrZero @Schema(description = "Defaults to the member's declared monthly income")
            Long declaredMonthlyIncomeMinor,

            @PositiveOrZero Long monthlyObligationsMinor,
            @Size(max = 2000) String visitNotes) {}

    @Schema(name = "LoanAppraisal")
    record Appraisal(
            UUID id,
            UUID loanId,
            UUID appraisedBy,
            Long declaredMonthlyIncomeMinor,
            Long monthlyObligationsMinor,
            String visitNotes,
            int score,
            String band,
            Map<String, Object> components,
            List<String> flags,
            Map<String, Object> exposure,
            Map<String, Object> weights,
            String recommendation,
            Instant createdAt) {}

    @Schema(name = "LoanAppraisalList")
    record AppraisalList(List<Appraisal> items) {}

    private final LoanRepository repo;
    private final LoanService loans;
    private final MemberLookup members;
    private final ProductCatalog products;
    private final TenantSettings settings;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final ObjectMapper mapper;

    AppraisalService(
            LoanRepository repo,
            LoanService loans,
            MemberLookup members,
            ProductCatalog products,
            TenantSettings settings,
            AuditLog audit,
            BusinessClock clock,
            ObjectMapper mapper) {
        this.repo = repo;
        this.loans = loans;
        this.members = members;
        this.products = products;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional
    Appraisal appraise(UUID loanId, String ifMatch, AppraisalRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Principal principal = CurrentPrincipal.require();
        Loan loan = repo.lock(loanId)
                .filter(l -> principal.may("lending.loans.appraise", l.branchId()))
                .orElseThrow(ApiException::notFound);
        if (loan.version() != expected) {
            throw Versions.conflict(loan.version());
        }
        if (!loan.status().equals("submitted") && !loan.status().equals("appraised")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "Only a submitted application is appraised; this one is " + loan.status() + ".");
        }
        MemberSummary member = members.find(loan.memberId()).orElseThrow();
        Long income =
                r.declaredMonthlyIncomeMinor() != null ? r.declaredMonthlyIncomeMinor() : member.monthlyIncomeMinor();
        long obligations = r.monthlyObligationsMinor() == null ? 0 : r.monthlyObligationsMinor();

        // FR-ORG-05: the member's own other loans, loans they guarantee, and linked parties' loans.
        List<ExposureLoan> own = repo.exposureOfMembers(List.of(member.id()), loanId);
        List<ExposureLoan> guaranteed = repo.guaranteedBy(member.id());
        Set<UUID> linked = new LinkedHashSet<>(members.linkedMembers(member.id()));
        repo.guarantors(loanId).forEach(g -> linked.add(g.memberId()));
        guaranteed.forEach(g -> linked.add(g.memberId()));
        linked.remove(member.id());
        List<ExposureLoan> linkedLoans = repo.exposureOfMembers(List.copyOf(linked), loanId);

        LoanRepository.History history = repo.historyCounts(member.id());
        long pledged = repo.pledges(loanId).stream()
                .mapToLong(PledgeRow::pledgedValueMinor)
                .sum();
        Instant now = clock.now();
        var inputs = new CreditScore.Inputs(
                history.closed(),
                history.closed(),
                history.writtenOff(),
                monthlyInstalment(loan),
                income,
                obligations,
                pledged,
                loan.requestedPrincipalMinor(),
                own.stream().anyMatch(l -> l.status().equals("active") && l.daysPastDue() > 0),
                linkedLoans.stream().anyMatch(l -> l.daysPastDue() > 30),
                (int) own.stream().filter(l -> l.status().equals("active")).count(),
                member.createdAt() != null && member.createdAt().isAfter(now.minus(Duration.ofDays(30))),
                products.version(loan.productVersionId()).orElseThrow().minCollateralCoverBp());
        Map<String, Integer> weights = settings.appraisalWeights();
        CreditScore.Result result = CreditScore.score(inputs, weights);

        Map<String, Object> exposure = new LinkedHashMap<>();
        exposure.put(
                "own_loans", own.stream().map(AppraisalService::exposureView).toList());
        exposure.put(
                "guaranteed_loans",
                guaranteed.stream().map(AppraisalService::exposureView).toList());
        exposure.put(
                "linked_party_loans",
                linkedLoans.stream().map(AppraisalService::exposureView).toList());

        UUID id = UUID.randomUUID();
        repo.insertAppraisal(
                id,
                loanId,
                principal.userId(),
                income,
                obligations,
                blankToNull(r.visitNotes()),
                result,
                mapper.writeValueAsString(result.components()),
                mapper.writeValueAsString(exposure),
                mapper.writeValueAsString(weights));
        if (loan.status().equals("submitted")) {
            Map<String, Object> columns = new LinkedHashMap<>();
            columns.put("appraised_by", principal.userId());
            repo.move(loanId, "submitted", "appraised", principal.userId(), null, columns);
        } else {
            repo.reappraised(loanId, principal.userId());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("appraisal_id", id);
        after.put("score", result.score());
        after.put("band", result.band());
        after.put("recommendation", result.recommendation());
        after.put("flags", result.flags());
        audit.record(new AuditLog.Entry(
                "lending.loan.appraised",
                "lending.loan",
                loanId,
                loan.branchId(),
                Map.of("status", loan.status()),
                after));
        return list(loanId).items().getFirst();
    }

    @Transactional(readOnly = true)
    AppraisalList list(UUID loanId) {
        Principal principal = CurrentPrincipal.require();
        repo.find(loanId)
                .filter(l -> principal.may("lending.loans.read", l.branchId()))
                .orElseThrow(ApiException::notFound);
        return new AppraisalList(
                repo.appraisals(loanId).stream().map(a -> view(loanId, a)).toList());
    }

    /**
     * The loan's largest instalment as a monthly amount (section 3.18.1), from the same schedule
     * the application shows: its own copied terms and its version's added fees.
     */
    private long monthlyInstalment(Loan loan) {
        long largest = loans.scheduleOf(loan).stream()
                .mapToLong(ScheduleCalculator.Item::totalMinor)
                .max()
                .orElse(0);
        return CreditScore.monthly(
                largest,
                loan.repaymentPattern(),
                loan.instalmentFrequency(),
                loan.termUnit(),
                loan.requestedTermCount());
    }

    private static Map<String, Object> exposureView(ExposureLoan l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loan_id", l.loanId());
        m.put("member_id", l.memberId());
        m.put("loan_no", l.loanNo());
        m.put("status", l.status());
        m.put("outstanding_minor", l.outstandingMinor());
        m.put("days_past_due", l.daysPastDue());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Appraisal view(UUID loanId, AppraisalRow a) {
        return new Appraisal(
                a.id(),
                loanId,
                a.appraisedBy(),
                a.declaredMonthlyIncomeMinor(),
                a.monthlyObligationsMinor(),
                a.visitNotes(),
                a.score(),
                a.band(),
                mapper.readValue(a.componentsJson(), Map.class),
                a.flags(),
                mapper.readValue(a.exposureJson(), Map.class),
                mapper.readValue(a.weightsJson(), Map.class),
                a.recommendation(),
                a.createdAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
