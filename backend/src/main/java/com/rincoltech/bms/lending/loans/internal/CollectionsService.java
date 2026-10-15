package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.identity.StaffDirectory;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.Action;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ActionList;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ActionRequest;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ArrearsBucket;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ArrearsLine;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ArrearsList;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.DueList;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.PromiseList;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ReassignResult;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collections (FR-CLN-01 to FR-CLN-05, chapter 7 section 7.11.17): the responsible officer, the
 * collection actions of a loan, and the due, arrears and promise lists of chapter 14 section 14.4,
 * each in the caller's branch scope for its permission. The nightly settling of promises is
 * {@link #settlePromises}, called by the arrears run.
 */
@Service
class CollectionsService {

    static final Set<String> ACTION_TYPES = Set.of("call", "visit", "sms", "letter", "promise_to_pay", "other");
    static final List<String> BUCKETS = List.of("1_30", "31_60", "61_90", "over_90");
    static final Set<String> PROMISE_STATUSES = Set.of("pending", "kept", "broken");

    /** A loan that is still being collected keeps an officer; a finished one does not move. */
    private static final Set<String> REASSIGNABLE =
            Set.of("draft", "submitted", "appraised", "approved", "active", "written_off");

    private static final Set<String> COLLECTED = Set.of("active", "written_off");
    private static final int MAX_DAYS = 92;

    private final LoanRepository loans;
    private final CollectionsRepository repo;
    private final StaffDirectory staff;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    CollectionsService(
            LoanRepository loans,
            CollectionsRepository repo,
            StaffDirectory staff,
            CurrentTenant currentTenant,
            BusinessClock clock,
            AuditLog audit) {
        this.loans = loans;
        this.repo = repo;
        this.staff = staff;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.audit = audit;
    }

    private LocalDate today() {
        return clock.today(currentTenant.profile().timezone());
    }

    // ---- FR-CLN-01 ------------------------------------------------------------------------

    /**
     * Moves every loan to the officer, all or none: each must be in the caller's
     * {@code lending.collections.assign} scope (else 404) and still being collected, and the officer
     * must be an active staff user who can originate loans in each loan's branch.
     */
    @Transactional
    ReassignResult reassign(List<UUID> loanIds, UUID officerId) {
        Principal principal = CurrentPrincipal.require();
        int moved = 0;
        // Locked in id order, so two bulk moves never wait on each other in a cycle.
        for (UUID id : new TreeSet<>(loanIds)) {
            Loan loan = loans.lock(id)
                    .filter(l -> principal.may("lending.collections.assign", l.branchId()))
                    .orElseThrow(ApiException::notFound);
            LoanServicer.requireStatus(loan, REASSIGNABLE);
            if (!staff.mayAct(officerId, "lending.loans.create", loan.branchId())) {
                throw ApiException.rule(
                        "officer_not_eligible",
                        "The officer must be an active staff user who can create loans in the loan's branch.");
            }
            if (officerId.equals(loan.officerUserId())) {
                continue;
            }
            repo.reassign(loan.id(), officerId);
            audit.record(new AuditLog.Entry(
                    "lending.loan.officer_reassigned",
                    LoanServicer.SUBJECT,
                    loan.id(),
                    loan.branchId(),
                    Map.of("officer_user_id", loan.officerUserId().toString()),
                    Map.of("officer_user_id", officerId.toString())));
            moved++;
        }
        return new ReassignResult(officerId, moved);
    }

    // ---- FR-CLN-04 ------------------------------------------------------------------------

    @Transactional
    Action logAction(UUID loanId, ActionRequest r) {
        Principal principal = CurrentPrincipal.require();
        Loan loan = loans.find(loanId)
                .filter(l -> principal.may("lending.collections.log_action", l.branchId()))
                .orElseThrow(ApiException::notFound);
        LoanServicer.requireStatus(loan, COLLECTED);
        if (!ACTION_TYPES.contains(r.actionType())) {
            throw ApiException.rule("invalid_action_type", "The action is one of " + new TreeSet<>(ACTION_TYPES) + ".");
        }
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        boolean promise = r.actionType().equals("promise_to_pay");
        if (promise) {
            if (r.promisedDate() == null || r.promisedAmountMinor() == null) {
                throw ApiException.rule("promise_incomplete", "A promise to pay needs a promised date and amount.");
            }
            if (r.promisedDate().isBefore(today())) {
                throw ApiException.rule("promised_date_in_past", "The promised date is today or later.");
            }
        } else {
            if (r.promisedDate() != null || r.promisedAmountMinor() != null) {
                throw ApiException.rule(
                        "promise_fields_not_allowed", "Only a promise to pay carries a promised date and amount.");
            }
            if (note == null) {
                throw ApiException.rule("note_required", "Say what happened in the note.");
            }
        }
        UUID id = UUID.randomUUID();
        repo.insertAction(
                id, loan.id(), r.actionType(), note, r.promisedDate(), r.promisedAmountMinor(), principal.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("action_id", id);
        after.put("action_type", r.actionType());
        if (promise) {
            after.put("promised_date", r.promisedDate().toString());
            after.put("promised_amount_minor", r.promisedAmountMinor());
        }
        audit.record(AuditLog.Entry.created(
                "lending.loan.collection_action_logged", LoanServicer.SUBJECT, loan.id(), loan.branchId(), after));
        return repo.action(id);
    }

    @Transactional(readOnly = true)
    ActionList actions(UUID loanId) {
        Principal principal = CurrentPrincipal.require();
        loans.find(loanId)
                .filter(l -> principal.may("lending.collections.read", l.branchId()))
                .orElseThrow(ApiException::notFound);
        return new ActionList(repo.actions(loanId));
    }

    // ---- FR-CLN-02, FR-CLN-03, promises ---------------------------------------------------

    @Transactional(readOnly = true)
    DueList dueList(LocalDate from, LocalDate to, UUID officerId, List<UUID> branchIds) {
        LocalDate start = from == null ? today() : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            throw ApiException.rule("invalid_range", "date_to is on or after date_from.");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw ApiException.rule("range_too_long", "The range is at most " + MAX_DAYS + " days.");
        }
        return new DueList(start, end, repo.dueList(scope(branchIds), officerId, start, end));
    }

    @Transactional(readOnly = true)
    ArrearsList arrears(String bucket, UUID officerId, List<UUID> branchIds) {
        if (bucket != null && !BUCKETS.contains(bucket)) {
            throw ApiException.rule("invalid_bucket", "The bucket is one of " + BUCKETS + ".");
        }
        List<ArrearsLine> lines = repo.arrears(scope(branchIds), officerId);
        List<ArrearsBucket> buckets = new ArrayList<>();
        for (String b : BUCKETS) {
            if (bucket != null && !bucket.equals(b)) {
                continue;
            }
            List<ArrearsLine> in =
                    lines.stream().filter(l -> l.parBucket().equals(b)).toList();
            buckets.add(new ArrearsBucket(
                    b,
                    in.size(),
                    in.stream().mapToLong(ArrearsLine::arrearsMinor).sum(),
                    in));
        }
        return new ArrearsList(buckets);
    }

    @Transactional(readOnly = true)
    PromiseList promises(String status, UUID officerId, List<UUID> branchIds) {
        String s = status == null ? "pending" : status;
        if (!PROMISE_STATUSES.contains(s)) {
            throw ApiException.rule("invalid_promise_status", "The status is pending, kept or broken.");
        }
        return new PromiseList(repo.promises(scope(branchIds), officerId, s));
    }

    private static List<UUID> scope(List<UUID> requested) {
        return CurrentPrincipal.require().branchFilter("lending.collections.read", requested);
    }

    // ---- FR-CLN-05 ------------------------------------------------------------------------

    record Settled(int kept, int broken) {}

    /** Runs inside the tenant's job transaction, after the day's arrears. */
    Settled settlePromises(LocalDate today) {
        int kept = 0;
        int broken = 0;
        for (CollectionsRepository.OpenPromise p :
                repo.openPromises(currentTenant.profile().timezone().getId())) {
            String status = Promises.settle(p.paidMinor(), p.promisedMinor(), p.promisedDate(), today);
            if (status == null) {
                continue;
            }
            repo.settlePromise(p.id(), status);
            if (status.equals("kept")) {
                kept++;
            } else {
                broken++;
            }
        }
        return new Settled(kept, broken);
    }
}
