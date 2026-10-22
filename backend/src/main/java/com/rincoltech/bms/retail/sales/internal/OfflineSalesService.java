package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Clash;
import com.rincoltech.bms.retail.sales.internal.SalesApi.ClashPage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Failure;
import com.rincoltech.bms.retail.sales.internal.SalesApi.FailurePage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.FailureRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.WaitingAccepted;
import com.rincoltech.bms.retail.sales.internal.SalesApi.WaitingPage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.WaitingRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.WaitingSale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The clashes of sales recorded offline and the refused ones the devices report (ADR-067 decisions 7 and 8). A clash is
 * resolved once with a note; a refused sale is sent again or discarded by a holder of {@link #RESOLVE} at its branch, and a
 * discard is audited. The sale itself is only ever recorded through the sale path.
 */
@Service
class OfflineSalesService {

    static final String RESOLVE = "retail.offline.resolve";
    static final String CLASH = "retail.sale_clash";
    static final String FAILURE = "retail.offline_failure";

    /** The largest sale request a device may report with a refusal, as JSON (ADR-067 decision 8). */
    static final int MAX_REQUEST_BYTES = 64 * 1024;

    private final OfflineSalesRepository repo;
    private final AuditLog audit;

    OfflineSalesService(OfflineSalesRepository repo, AuditLog audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    ClashPage clashes(String status, List<UUID> branchIds) {
        String s = status == null ? "open" : status;
        if (!List.of("open", "resolved", "all").contains(s)) {
            throw ApiException.validation(
                    List.of(new ApiException.FieldProblem("status", "invalid", "open, resolved or all")));
        }
        return new ClashPage(repo.clashes(CurrentPrincipal.require().branchFilter(RESOLVE, branchIds), s));
    }

    @Transactional
    Clash resolve(UUID clashId, String note) {
        Principal principal = CurrentPrincipal.require();
        Clash c = repo.clash(clashId, true)
                .filter(x -> principal.may(RESOLVE, x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (c.resolvedAt() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "clash_already_resolved",
                    "Already resolved",
                    "This clash is already resolved.");
        }
        String trimmed = note.trim();
        if (trimmed.length() < 5) {
            throw ApiException.validation(List.of(
                    new ApiException.FieldProblem("note", "invalid", "Say what was done, in at least 5 characters.")));
        }
        repo.resolveClash(clashId, principal.userId(), trimmed);
        audit.record(new AuditLog.Entry(
                "retail.sale_clash.resolved",
                CLASH,
                clashId,
                c.branchId(),
                Map.of("kind", c.kind(), "sale_id", c.saleId()),
                Map.of("note", trimmed)));
        return repo.clash(clashId, false).orElseThrow();
    }

    /**
     * The device of the person who made the sale reports a refusal. Idempotent on the client sale id: a second report of an
     * open one returns it; a retry refused again goes back to open with the new words; a decided one is returned as it is.
     */
    @Transactional
    Failure report(FailureRequest r) {
        if (repo.json(r.request()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new ApiException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "offline_failure_too_large",
                    "Request too large",
                    "The sale request is larger than 64 KB.");
        }
        Principal principal = CurrentPrincipal.require();
        UUID by = principal.userId();
        if (!repo.branchExists(r.branchId())) {
            throw ApiException.validation(
                    List.of(new ApiException.FieldProblem("branch_id", "unknown_branch", "No such branch.")));
        }
        if (!principal.may("retail.sale.create", r.branchId())) {
            // Only a sale the reporter could have made: never one at a branch they do not sell at.
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You do not sell at this branch, so you cannot report a sale made there.");
        }
        repo.dropWaiting(r.clientSaleId());
        // A sale already recorded under this id (its answer was lost, then a later send was refused) is the sale: the
        // report is kept closed by it, never open for a manager to discard.
        Optional<OfflineSalesRepository.Recorded> sale = repo.saleByClient(r.clientSaleId());
        if (sale.isPresent() && !sale.get().createdBy().equals(by)) {
            throw ApiException.rule(
                    "client_sale_id_reused", "This client sale id belongs to a sale someone else recorded.");
        }
        UUID saleId = sale.map(OfflineSalesRepository.Recorded::saleId).orElse(null);
        if (!repo.insertFailure(r, by, saleId)) {
            Failure existing = repo.failureByClient(r.clientSaleId(), true).orElseThrow();
            if (!existing.madeBy().equals(by)) {
                throw ApiException.rule(
                        "client_sale_id_reused", "This client sale id belongs to a sale someone else reported.");
            }
            if (saleId != null) {
                repo.closeAsRecorded(existing.id(), saleId);
            } else if (existing.status().equals("retry")) {
                repo.reopen(existing.id(), r.code(), r.message());
            }
        }
        return repo.failureByClient(r.clientSaleId(), false).orElseThrow();
    }

    /**
     * A phone's summary of the sales other people left queued on it (ADR-067 decision 9): kept per client sale id for the
     * managers to see as waiting on a phone. A sale already recorded or reported as refused is skipped; nothing is posted.
     */
    @Transactional
    WaitingAccepted reportWaiting(WaitingRequest r) {
        UUID by = CurrentPrincipal.require().userId();
        List<FieldProblem> problems = new java.util.ArrayList<>();
        for (int i = 0; i < r.items().size(); i++) {
            if (!repo.branchExists(r.items().get(i).branchId())) {
                problems.add(new FieldProblem("items[" + i + "].branch_id", "unknown_branch", "No such branch."));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        int accepted = 0;
        for (WaitingSale w : r.items()) {
            accepted += repo.upsertWaiting(w, by) ? 1 : 0;
        }
        return new WaitingAccepted(accepted);
    }

    @Transactional(readOnly = true)
    WaitingPage waiting(List<UUID> branchIds) {
        return new WaitingPage(repo.waiting(CurrentPrincipal.require().branchFilter(RESOLVE, branchIds)));
    }

    @Transactional(readOnly = true)
    FailurePage mine(List<UUID> clientSaleIds) {
        List<UUID> ids = clientSaleIds == null ? List.of() : clientSaleIds;
        if (ids.size() > 200) {
            throw ApiException.validation(
                    List.of(new ApiException.FieldProblem("client_sale_id", "invalid", "At most 200 at a time.")));
        }
        return new FailurePage(repo.mine(CurrentPrincipal.require().userId(), ids));
    }

    @Transactional(readOnly = true)
    FailurePage failures(List<String> statuses, List<UUID> branchIds) {
        List<String> asked = statuses == null || statuses.isEmpty() ? List.of("open", "retry") : statuses;
        if (!List.of("open", "retry", "discarded", "recorded").containsAll(asked)) {
            throw ApiException.validation(
                    List.of(new ApiException.FieldProblem("status", "invalid", "open, retry, discarded or recorded")));
        }
        return new FailurePage(repo.failures(CurrentPrincipal.require().branchFilter(RESOLVE, branchIds), asked));
    }

    /** Asks the device that reported it to send the sale again, once the cause is fixed. */
    @Transactional(noRollbackFor = ApiException.class)
    Failure retry(UUID id) {
        Failure f = decidable(id, null);
        if (f.status().equals("retry")) {
            return f;
        }
        UUID by = CurrentPrincipal.require().userId();
        repo.decide(id, "retry", by, null);
        audit.record(new AuditLog.Entry(
                "retail.offline_sale.retry",
                FAILURE,
                id,
                f.branchId(),
                Map.of("status", f.status()),
                Map.of("status", "retry", "client_sale_id", f.clientSaleId())));
        return repo.failure(id, false).orElseThrow();
    }

    /**
     * Discards the refused sale with a reason: it is never recorded, and the decision is audited. An administrator discarding
     * a sale they made themselves gives a second written reason, audited as self_discard (ADR-067 decision 11 d).
     */
    @Transactional(noRollbackFor = ApiException.class)
    Failure discard(UUID id, String reason, String selfReason) {
        String trimmed = reason.trim();
        String selfTrimmed = selfReason == null ? "" : selfReason.trim();
        Failure f = decidable(id, selfTrimmed);
        if (trimmed.length() < 5) {
            throw ApiException.validation(List.of(
                    new ApiException.FieldProblem("reason", "invalid", "Give a reason of at least 5 characters.")));
        }
        UUID by = CurrentPrincipal.require().userId();
        boolean self = f.madeBy().equals(by);
        if (self && selfTrimmed.equalsIgnoreCase(trimmed)) {
            throw ApiException.validation(List.of(new ApiException.FieldProblem(
                    "self_discard_reason",
                    "invalid",
                    "Say it again in other words: a second reason, not the same one.")));
        }
        repo.decide(id, "discarded", by, trimmed);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "discarded");
        after.put("reason", trimmed);
        if (self) {
            after.put("self_discard", true);
            after.put("self_discard_reason", selfTrimmed);
        }
        after.put("client_sale_id", f.clientSaleId());
        after.put("made_by", f.madeBy());
        after.put("total_minor", f.totalMinor());
        after.put("payment_method", f.paymentMethod());
        after.put("refusal_code", f.code());
        audit.record(new AuditLog.Entry(
                self ? "retail.offline_sale.self_discard" : "retail.offline_sale.discarded",
                FAILURE,
                id,
                f.branchId(),
                Map.of("status", f.status()),
                after));
        return repo.failure(id, false).orElseThrow();
    }

    /**
     * The failure, locked, when the caller may decide it. {@code selfReason} is null for a retry and the second reason (maybe
     * blank) for a discard.
     */
    private Failure decidable(UUID id, String selfReason) {
        Principal principal = CurrentPrincipal.require();
        Failure f = repo.failure(id, true)
                .filter(x -> principal.may(RESOLVE, x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (f.madeBy().equals(principal.userId())) {
            // ADR-067 decision 11 (d): a seller or a Supervisor never decides their own refused sale; an administrator
            // (the
            // Owner included) may discard it, with a second written reason.
            if (selfReason == null || !repo.isTenantAdmin(principal.userId())) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "own_offline_sale",
                        "Your own sale",
                        "Another manager decides a sale you made. You can send it again from your phone.");
            }
            if (selfReason.length() < 5) {
                throw ApiException.validation(List.of(new ApiException.FieldProblem(
                        "self_discard_reason",
                        "required",
                        "This is your own sale: give a second reason of at least 5 characters.")));
            }
        }
        Optional<OfflineSalesRepository.Recorded> sale = repo.saleByClient(f.clientSaleId());
        if (sale.isPresent()) {
            // The sale was recorded after all (a report that crossed its send): closed by it, never discarded. The
            // close
            // is kept although the request is refused.
            repo.closeAsRecorded(f.id(), sale.get().saleId());
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "offline_failure_decided",
                    "Already decided",
                    "This sale was already recorded.");
        }
        if (f.status().equals("discarded") || f.status().equals("recorded")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "offline_failure_decided",
                    "Already decided",
                    "This sale was already " + f.status() + ".");
        }
        return f;
    }
}
