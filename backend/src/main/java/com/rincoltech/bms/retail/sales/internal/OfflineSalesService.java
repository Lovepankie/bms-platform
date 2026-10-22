package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Clash;
import com.rincoltech.bms.retail.sales.internal.SalesApi.ClashPage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Failure;
import com.rincoltech.bms.retail.sales.internal.SalesApi.FailurePage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.FailureRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        UUID by = CurrentPrincipal.require().userId();
        if (!repo.insertFailure(r, by)) {
            Failure existing = repo.failureByClient(r.clientSaleId(), true).orElseThrow();
            if (!existing.madeBy().equals(by)) {
                throw ApiException.rule(
                        "client_sale_id_reused", "This client sale id belongs to a sale someone else reported.");
            }
            if (existing.status().equals("retry")) {
                repo.reopen(existing.id(), r.code(), r.message());
            }
        }
        return repo.failureByClient(r.clientSaleId(), false).orElseThrow();
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
    @Transactional
    Failure retry(UUID id) {
        Failure f = decidable(id);
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

    /** Discards the refused sale with a reason: it is never recorded, and the decision is audited. */
    @Transactional
    Failure discard(UUID id, String reason) {
        Failure f = decidable(id);
        String trimmed = reason.trim();
        if (trimmed.length() < 5) {
            throw ApiException.validation(List.of(
                    new ApiException.FieldProblem("reason", "invalid", "Give a reason of at least 5 characters.")));
        }
        UUID by = CurrentPrincipal.require().userId();
        repo.decide(id, "discarded", by, trimmed);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "discarded");
        after.put("reason", trimmed);
        after.put("client_sale_id", f.clientSaleId());
        after.put("made_by", f.madeBy());
        after.put("total_minor", f.totalMinor());
        after.put("payment_method", f.paymentMethod());
        after.put("refusal_code", f.code());
        audit.record(new AuditLog.Entry(
                "retail.offline_sale.discarded", FAILURE, id, f.branchId(), Map.of("status", f.status()), after));
        return repo.failure(id, false).orElseThrow();
    }

    private Failure decidable(UUID id) {
        Principal principal = CurrentPrincipal.require();
        Failure f = repo.failure(id, true)
                .filter(x -> principal.may(RESOLVE, x.branchId()))
                .orElseThrow(ApiException::notFound);
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
