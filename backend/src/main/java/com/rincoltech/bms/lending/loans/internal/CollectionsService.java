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
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.DueLine;
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
    ReassignResult reassign(List<UUID> loanIds, UUID officerId, String reason) {
        Principal principal = CurrentPrincipal.require();
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 5 || why.length() > 300) {
            throw ApiException.rule("reason_required", "Say why, in 5 to 300 characters.");
        }
        int moved = 0;
        Map<UUID, Boolean> eligible = new java.util.HashMap<>();
        // Locked in id order, so two bulk moves never wait on each other in a cycle.
        for (UUID id : new TreeSet<>(loanIds)) {
            Loan loan = loans.lock(id)
                    .filter(l -> principal.may("lending.collections.assign", l.branchId()))
                    .orElseThrow(ApiException::notFound);
            LoanServicer.requireStatus(loan, REASSIGNABLE);
            // One directory look-up per branch, not per loan (the review of #272).
            if (!eligible.computeIfAbsent(loan.branchId(), b -> staff.mayAct(officerId, "lending.loans.create", b))) {
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
                    Map.of("officer_user_id", officerId.toString(), "reason", why)));
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
            if (r.promisedDate().isAfter(today().plusDays(MAX_PROMISE_DAYS))) {
                throw ApiException.rule(
                        "promised_date_too_far", "The promised date is at most " + MAX_PROMISE_DAYS + " days ahead.");
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

    /** Lines a list returns by default, and at most (the review of #272: never the whole contact book at once). */
    static final int DEFAULT_LIMIT = 200;

    /** A promise is for a date at most this many days ahead. */
    static final int MAX_PROMISE_DAYS = 60;

    static final String CONTACT = "lending.members.contact";

    static final String ALL_OFFICERS = "lending.collections.all_officers";

    /**
     * ADR-064: in a branch where the caller lacks {@code lending.collections.all_officers} (a loan officer there) a
     * list covers only the caller's own portfolio, whatever officer was asked for; decided per branch, so a manager in
     * one branch who is a loan officer in another sees only their own loans in the other.
     */
    static CollectionsRepository.Officers officerFor(Principal principal, UUID requested) {
        return new CollectionsRepository.Officers(
                requested, principal.branchFilter(ALL_OFFICERS, null), principal.userId());
    }

    static final int MAX_LIMIT = 500;

    static int limit(Integer requested) {
        return requested == null ? DEFAULT_LIMIT : Math.clamp(requested, 1, MAX_LIMIT);
    }

    /**
     * A member's phone as the caller may see it: whole only with {@code lending.members.contact} in the loan's branch
     * (the people who call borrowers: administrators, branch managers, loan officers; ADR-064), else masked as
     * {@code +256*****0001}, so a cashier, an accountant or an auditor with collections read never takes the branch's
     * contact book.
     */
    static String phone(Principal principal, UUID branchId, String e164) {
        if (e164 == null || principal.may(CONTACT, branchId)) {
            return e164;
        }
        return e164.length() <= 8
                ? "*".repeat(e164.length())
                : e164.substring(0, 4) + "*".repeat(e164.length() - 8) + e164.substring(e164.length() - 4);
    }

    @Transactional(readOnly = true)
    DueList dueList(LocalDate from, LocalDate to, UUID officerId, List<UUID> branchIds, Integer limit) {
        LocalDate start = from == null ? today() : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            throw ApiException.rule("invalid_range", "date_to is on or after date_from.");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw ApiException.rule("range_too_long", "The range is at most " + MAX_DAYS + " days.");
        }
        Principal principal = CurrentPrincipal.require();
        int size = limit(limit);
        List<DueLine> lines = repo.dueList(scope(branchIds), officerFor(principal, officerId), start, end, size + 1);
        return new DueList(
                start,
                end,
                lines.stream()
                        .limit(size)
                        .map(l -> l.withPhone(phone(principal, l.branchId(), l.phone())))
                        .toList(),
                lines.size() > size);
    }

    @Transactional(readOnly = true)
    ArrearsList arrears(String bucket, UUID officerId, List<UUID> branchIds, Integer limit) {
        if (bucket != null && !BUCKETS.contains(bucket)) {
            throw ApiException.rule("invalid_bucket", "The bucket is one of " + BUCKETS + ".");
        }
        Principal principal = CurrentPrincipal.require();
        List<UUID> scope = scope(branchIds);
        int size = limit(limit);
        CollectionsRepository.Officers officer = officerFor(principal, officerId);
        List<ArrearsLine> lines = repo.arrears(scope, officer, bucket, size + 1);
        Map<String, long[]> totals = repo.arrearsTotals(scope, officer);
        List<ArrearsBucket> buckets = new ArrayList<>();
        for (String b : BUCKETS) {
            if (bucket != null && !bucket.equals(b)) {
                continue;
            }
            long[] t = totals.getOrDefault(b, new long[2]);
            buckets.add(new ArrearsBucket(
                    b,
                    (int) t[0],
                    t[1],
                    lines.stream()
                            .limit(size)
                            .filter(l -> l.parBucket().equals(b))
                            .map(l -> l.withPhone(phone(principal, l.branchId(), l.phone())))
                            .toList()));
        }
        return new ArrearsList(buckets, lines.size() > size);
    }

    @Transactional(readOnly = true)
    PromiseList promises(String status, UUID officerId, List<UUID> branchIds, Integer limit) {
        String s = status == null ? "pending" : status;
        if (!PROMISE_STATUSES.contains(s)) {
            throw ApiException.rule("invalid_promise_status", "The status is pending, kept or broken.");
        }
        int size = limit(limit);
        List<CollectionsApi.PromiseLine> lines =
                repo.promises(scope(branchIds), officerFor(CurrentPrincipal.require(), officerId), s, size + 1);
        return new PromiseList(lines.stream().limit(size).toList(), lines.size() > size);
    }

    private static List<UUID> scope(List<UUID> requested) {
        return CurrentPrincipal.require().branchFilter("lending.collections.read", requested);
    }

    // ---- FR-CLN-05 ------------------------------------------------------------------------

    record Settled(int kept, int broken) {}

    /** How far back a settled promise is looked at again when a later entry changes what was paid in its window. */
    static final int RESETTLE_DAYS = 31;

    /**
     * FR-CLN-05, run by the arrears run in its own transaction after the loans: every open promise, and every promise
     * due in the last {@link #RESETTLE_DAYS} days, is settled again from the payments that stand ({@link
     * Promises#settleAll}), each payment counted toward one promise only. Counts the promises that became kept or
     * broken.
     */
    @Transactional
    Settled settlePromises(LocalDate today) {
        LocalDate since = today.minusDays(RESETTLE_DAYS);
        List<CollectionsRepository.StoredPromise> stored =
                repo.promisesToSettle(currentTenant.profile().timezone().getId(), since);
        if (stored.isEmpty()) {
            return new Settled(0, 0);
        }
        List<UUID> loanIds =
                stored.stream().map(p -> p.promise().loanId()).distinct().toList();
        LocalDate from = stored.stream()
                .map(p -> p.promise().madeOn())
                .min(LocalDate::compareTo)
                .orElse(today);
        Map<UUID, String> now = Promises.settleAll(
                stored.stream()
                        .map(CollectionsRepository.StoredPromise::promise)
                        .toList(),
                repo.paymentsFor(loanIds, from),
                today);
        int kept = 0;
        int broken = 0;
        for (CollectionsRepository.StoredPromise p : stored) {
            String status = now.get(p.promise().id());
            boolean open = p.status().equals("pending") || !p.promisedDate().isBefore(since);
            if (!open
                    || status.equals(p.status())
                    || !repo.settlePromise(p.promise().id(), status, today)) {
                continue;
            }
            if (status.equals("kept")) {
                kept++;
            } else if (status.equals("broken")) {
                broken++;
            }
        }
        return new Settled(kept, broken);
    }
}
