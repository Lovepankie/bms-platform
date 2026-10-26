package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.approvals.ApprovalWaiting;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.approvals.RequestSupersededException;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.internal.StockApi.Correction;
import com.rincoltech.bms.retail.stock.internal.StockApi.CorrectionRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Correct the quantity on the stock list (issue #351, ADR-044): the administrator types the right quantity and a reason (an
 * entry mistake or a miscount). One {@code adjustment} movement for the difference at the product's cost, from its own source
 * type {@code retail.stock_correction}, so the reports that count shrinkage (stock-take adjustments by source) and usage never
 * see it; one row of {@code retail_stock_corrections} with the old and the new quantity; the ledger entry that keeps inventory
 * equal to the stock valuation; and an audit entry. Nothing is edited or deleted: the restock the mistake came from stays.
 */
@Service
class StockCorrectionService {

    static final String PERMISSION = "retail.stocktake.commit";
    static final String SOURCE = "retail.stock_correction";
    static final String PATH = "/api/v1/retail/stock/corrections";

    private final StockRepository repo;
    private final StockLedger ledger;
    private final RetailCatalogue catalogue;
    private final RetailBooks books;
    private final RetailBranchContext branches;
    private final Idempotency idempotency;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    // Lazy: the approvals module holds the correction action, which calls back into this service on approval.
    private final Approvals approvals;

    StockCorrectionService(
            StockRepository repo,
            StockLedger ledger,
            RetailCatalogue catalogue,
            RetailBooks books,
            RetailBranchContext branches,
            Idempotency idempotency,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            @Lazy Approvals approvals) {
        this.approvals = approvals;
        this.repo = repo;
        this.ledger = ledger;
        this.catalogue = catalogue;
        this.books = books;
        this.branches = branches;
        this.idempotency = idempotency;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * A correction made at once, or the request that now waits for an administrator (ADR-091). Stored as the idempotent answer; an
     * answer stored before ADR-091 was the bare correction, which still replays as this.
     */
    @JsonDeserialize(using = CorrectionOutcome.Reader.class)
    record CorrectionOutcome(Correction correction, ApprovalWaiting waiting) {

        /** Reads both shapes: {@code {correction, waiting}}, or the old flat correction (an object with {@code id}). */
        static final class Reader extends ValueDeserializer<CorrectionOutcome> {
            @Override
            public CorrectionOutcome deserialize(JsonParser p, DeserializationContext ctxt) {
                JsonNode n = ctxt.readTree(p);
                if (n.has("id")) {
                    return new CorrectionOutcome(ctxt.readTreeAsValue(n, Correction.class), null);
                }
                JsonNode correction = n.get("correction");
                JsonNode waiting = n.get("waiting");
                return new CorrectionOutcome(
                        correction == null || correction.isNull()
                                ? null
                                : ctxt.readTreeAsValue(correction, Correction.class),
                        waiting == null || waiting.isNull()
                                ? null
                                : ctxt.readTreeAsValue(waiting, ApprovalWaiting.class));
            }
        }
    }

    /** What a correction would do, measured under the balance row's lock. */
    private record Checked(ProductSnapshot product, BigDecimal old, BigDecimal difference, long cost) {}

    @Transactional
    Outcome<CorrectionOutcome> correct(String idempotencyKey, CorrectionRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH, r, CorrectionOutcome.class, () -> correctOrAsk(r));
    }

    /**
     * While stock corrections ask for approval (ADR-091) and the value of the difference at the branch's average cost reaches the
     * amount, the correction waits for an administrator and the quantity stays as it is.
     */
    private CorrectionOutcome correctOrAsk(CorrectionRequest r) {
        UUID branch = branches.resolveStocking(PERMISSION, r.branchId());
        Checked c = check(branch, r);
        long value = Quantities.value(c.difference().abs(), c.cost());
        // The correction's id is fixed now, so a request the approvals module runs at once (the switch went off
        // meanwhile) is
        // answered with the correction it made.
        UUID id = UUID.randomUUID();
        Approvals.Rule rule = approvals.rule(StockCorrectionAction.TYPE);
        if (rule.asks(value)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(StockCorrectionAction.CORRECTION_ID, id.toString());
            payload.put("branch_id", branch.toString());
            payload.put("product_id", c.product().id().toString());
            payload.put("new_qty", Quantities.format(r.newQty()));
            payload.put("expected_qty", Quantities.format(r.expectedQty()));
            payload.put("reason_code", r.reasonCode());
            payload.put("note", r.note());
            Approvals.Outcome o = approvals.request(
                    new ActionRequest(
                            StockCorrectionAction.TYPE,
                            branch,
                            c.product().id(),
                            null,
                            value,
                            tenant.profile().currency(),
                            payload),
                    rule);
            if (o.executed()) {
                return new CorrectionOutcome(repo.correction(id).orElseThrow(), null);
            }
            return new CorrectionOutcome(
                    null,
                    new ApprovalWaiting(
                            o.approvalId(),
                            StockCorrectionAction.TYPE,
                            value,
                            tenant.profile().currency()));
        }
        return new CorrectionOutcome(
                write(id, branch, r, c, CurrentPrincipal.require().userId()), null);
    }

    /**
     * On approval (ADR-091): the correction runs only if the quantity is still the one the maker saw; otherwise the request
     * becomes stale with the reason, as a direct correction would get 409 {@code stock_moved}.
     */
    void correctApproved(UUID id, UUID branch, CorrectionRequest r, UUID makerId) {
        Checked c;
        try {
            c = check(branch, r);
        } catch (RequestSupersededException e) {
            throw e;
        } catch (ApiException e) {
            // The one stale policy (ADR-091): any refusal (the quantity moved, the branch no longer stocks) goes stale
            // with it.
            if (!e.status().is4xxClientError()) {
                throw e;
            }
            throw new RequestSupersededException(
                    e.code(),
                    e.title(),
                    "The correction can no longer be made as it was asked for: " + e.getMessage(),
                    e.extensions());
        }
        write(id, branch, r, c, makerId);
    }

    private Checked check(UUID branch, CorrectionRequest r) {
        if (!Quantities.exact(r.newQty())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("new_qty", "invalid", "At most three decimal places.")));
        }
        ProductSnapshot product = catalogue
                .find(r.productId())
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("product_id", "unknown_product", "No such product."))));
        // Under the balance row's lock, so the difference is measured from the quantity the shelf list shows now.
        BigDecimal old = ledger.lock(branch, product.id());
        if (old.compareTo(r.expectedQty()) != 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "stock_moved",
                    "Stock moved",
                    "The quantity changed while you were typing: it is " + plain(old) + " " + product.unit()
                            + " now, not " + plain(r.expectedQty())
                            + ". Look at the list again and type the right quantity.",
                    List.of(),
                    // Issue #370: the quantity now, so the form shows it at once instead of the one it was opened with.
                    Map.of("current_qty", Quantities.format(old)));
        }
        // A stock-take that is not committed yet measures its variance from the shelf as it stood when it was made, so
        // a correction now would be counted a second time when it is committed.
        if (repo.draftStocktakeCovers(branch, product.id())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "stocktake_in_progress",
                    "Stock count in progress",
                    "A stock count of this branch that counts " + product.description()
                            + " is not finished. Finish it first (commit it), then correct the quantity.");
        }
        BigDecimal difference = r.newQty().subtract(old);
        if (difference.signum() == 0) {
            throw ApiException.rule(
                    "quantity_unchanged",
                    "The quantity is " + plain(old) + " " + product.unit() + " already. Type the right quantity.");
        }
        // ADR-079: a correction moves stock at the branch's average, so the average stays as it is.
        // It is read under the balance lock taken above (ledger.lock): no delivery can change it first.
        return new Checked(product, old, difference, ledger.averageCost(branch, product.id()));
    }

    private Correction write(UUID id, UUID branch, CorrectionRequest r, Checked c, UUID by) {
        ProductSnapshot product = c.product();
        BigDecimal old = c.old();
        BigDecimal difference = c.difference();
        long cost = c.cost();
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        String words = ("entry_mistake".equals(r.reasonCode()) ? "Entry mistake" : "Miscount")
                + (note == null ? "" : ": " + note);
        LocalDate today = clock.today(tenant.profile().timezone());
        UUID movementId = ledger.record(
                        today,
                        List.of(new Movement(
                                branch, product.id(), "adjustment", difference, cost, SOURCE, id, null, null, words)))
                .getFirst();
        // The value moves between inventory and the shrinkage account, as a stock-take's variance does; the app's
        // reports read movements by source, so this entry is the only place a correction shares an account with a loss.
        long value = Quantities.value(difference.abs(), cost);
        Optional<PostedEntry> entry = books.post(new Posting(
                branch,
                today,
                "Correction " + id.toString().substring(0, 8),
                "Stock correction",
                SOURCE,
                id,
                SOURCE + ":" + id,
                difference.signum() < 0
                        ? List.of(Leg.debit("stock_shrinkage", value), Leg.credit("inventory", value))
                        : List.of(Leg.debit("inventory", value), Leg.credit("stock_shrinkage", value))));
        repo.insertCorrection(
                id,
                branch,
                product.id(),
                movementId,
                old,
                r.newQty(),
                r.reasonCode(),
                note,
                cost,
                entry.map(PostedEntry::entryId).orElse(null),
                by);
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("qty", Quantities.format(old));
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("qty", Quantities.format(r.newQty()));
        now.put("product_id", product.id());
        now.put("reason_code", r.reasonCode());
        if (note != null) {
            now.put("note", note);
        }
        audit.record(new AuditLog.Entry("retail.stock.corrected", SOURCE, id, branch, was, now));
        return repo.correction(id).orElseThrow();
    }

    private static String plain(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }
}
