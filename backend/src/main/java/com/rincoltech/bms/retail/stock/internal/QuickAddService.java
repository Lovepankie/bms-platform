package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.NewItem;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.QuickAddPurchase;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.internal.StockApi.QuickAdd;
import com.rincoltech.bms.retail.stock.internal.StockApi.QuickAddRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick add (issue #632, ADR-101): a shop owner needed nine steps on four screens to add one item and its stock. One request makes
 * the item through the catalogue's own rules (the code from the category, the name check, General and Piece made when missing)
 * and adds the quantity typed to the item's stock at the branch, in one transaction. The stock goes in as one {@code opening}
 * movement of its own source type, at the branch's average (for a new item, the buying price typed), and posts to inventory
 * against opening balance equity, as the import's opening stock does: starting stock is never profit and never lost or damaged
 * stock, and no other item is touched. An item already in the list (chosen instead of a duplicate) only gets the quantity added.
 * Goods just bought and paid from the branch's cash or mobile money (issue #646) are instead one purchase, recorded by
 * purchasing as Buy stock records it, so the cash goes down and nothing posts to opening balance equity.
 */
@Service
class QuickAddService {

    static final String PATH = "/api/v1/retail/quick-add";
    static final String COUNT = "retail.stocktake.commit";
    static final String BUY = "retail.purchase.create";
    static final String SOURCE = "retail.quick_add";
    static final String NOTE = "Quick add";

    private final RetailCatalogue catalogue;
    private final StockLedger ledger;
    private final RetailBooks books;
    private final QuickAddPurchase purchases;
    private final RetailBranchContext branches;
    private final Idempotency idempotency;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    QuickAddService(
            RetailCatalogue catalogue,
            StockLedger ledger,
            RetailBooks books,
            QuickAddPurchase purchases,
            RetailBranchContext branches,
            Idempotency idempotency,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.catalogue = catalogue;
        this.ledger = ledger;
        this.books = books;
        this.purchases = purchases;
        this.branches = branches;
        this.idempotency = idempotency;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    Outcome<QuickAdd> add(String idempotencyKey, QuickAddRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH, r, QuickAdd.class, () -> addNow(r));
    }

    private QuickAdd addNow(QuickAddRequest r) {
        List<FieldProblem> problems = new ArrayList<>();
        if (r.productId() == null) {
            if (r.description() == null || r.description().isBlank()) {
                problems.add(new FieldProblem("description", "required", "Type the item's name."));
            }
            if (r.sellMinor() == null) {
                problems.add(new FieldProblem("sell_minor", "required", "Type the selling price."));
            }
        } else if (r.qty() == null || r.qty().signum() <= 0) {
            problems.add(new FieldProblem("qty", "required", "Type how many you are adding, more than 0."));
        }
        boolean paid = r.paidFrom() != null;
        if (paid && r.productId() == null && (r.qty() == null || r.qty().signum() <= 0)) {
            problems.add(new FieldProblem("qty", "required", "Type how many you bought, more than 0."));
        }
        if (paid && (r.costMinor() == null || r.costMinor() <= 0)) {
            problems.add(new FieldProblem("cost_minor", "required", "Type what you paid for one."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        // The branch first: someone who may not count stock there is refused before anything is made.
        UUID branch = r.qty() == null ? null : branches.resolveStocking(COUNT, r.branchId());
        // Paid from cash: the money leaves under Buy stock's permission at that branch, checked before
        // anything is made. A 403 of its own: the branch is fine for the stock, only the money may not leave.
        if (paid && !CurrentPrincipal.require().may(BUY, branch)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Taking the money from cash needs the permission to buy stock at this branch.");
        }
        ProductSnapshot product;
        boolean categoryMade = false;
        boolean unitMade = false;
        if (r.productId() != null) {
            product = catalogue
                    .find(r.productId())
                    .orElseThrow(() -> ApiException.validation(
                            List.of(new FieldProblem("product_id", "unknown_product", "No such item."))));
            // A switched-off item takes no new stock here: it is switched back on first, on Items.
            if (!product.active()) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "product_id", "inactive_product", "This item is switched off. Switch it on first, on Items.")));
            }
        } else {
            NewItem made = catalogue.createItem(
                    r.description().trim(),
                    r.categoryId(),
                    r.unitId(),
                    r.costMinor() == null ? 0 : r.costMinor(),
                    r.sellMinor(),
                    r.duplicateReason());
            product = made.product();
            categoryMade = made.categoryMade();
            unitMade = made.unitMade();
        }
        UUID id = UUID.randomUUID();
        UUID movementId = null;
        String total = null;
        QuickAddPurchase.Bought bought = null;
        if (paid) {
            // The purchase locks the item, then its balance row (ADR-079 lock order); the total is read after it.
            bought = purchases.buy(branch, product.id(), r.qty(), r.costMinor(), r.paidFrom());
            total = Quantities.format(ledger.lock(branch, product.id()));
            audited(id, branch, product, r.qty(), r.costMinor(), r.paidFrom(), bought.purchaseId());
        } else if (branch != null) {
            // Under the balance row's lock: the quantity typed is added to what the branch holds now, exactly.
            BigDecimal before = ledger.lock(branch, product.id());
            total = Quantities.format(before.add(r.qty()));
            if (r.qty().signum() > 0) {
                movementId = addStock(id, branch, product, r.qty());
            }
        }
        return new QuickAdd(
                product.id(),
                product.code(),
                product.description(),
                product.unit(),
                r.productId() == null,
                categoryMade,
                unitMade,
                branch,
                r.qty() == null ? null : r.qty().stripTrailingZeros().toPlainString(),
                total == null
                        ? null
                        : new BigDecimal(total).stripTrailingZeros().toPlainString(),
                movementId,
                r.paidFrom(),
                bought == null ? null : bought.purchaseNo(),
                bought == null ? null : bought.totalMinor());
    }

    /** One opening movement at the branch's average and its entry against opening balance equity, as the import's opening stock. */
    private UUID addStock(UUID id, UUID branch, ProductSnapshot product, BigDecimal qty) {
        long cost = ledger.averageCost(branch, product.id());
        LocalDate today = clock.today(tenant.profile().timezone());
        UUID movementId = ledger.record(
                        today,
                        List.of(new Movement(branch, product.id(), "opening", qty, cost, SOURCE, id, null, null, NOTE)))
                .getFirst();
        long value = Quantities.value(qty, cost);
        books.post(new Posting(
                branch,
                today,
                "Quick add " + id.toString().substring(0, 8),
                "Opening stock from Quick add",
                SOURCE,
                id,
                SOURCE + ":" + id,
                List.of(Leg.debit("inventory", value), Leg.credit("opening_balance_equity", value))));
        audited(id, branch, product, qty, cost, null, null);
        return movementId;
    }

    private void audited(
            UUID id,
            UUID branch,
            ProductSnapshot product,
            BigDecimal qty,
            long cost,
            String paidFrom,
            UUID purchaseId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("product_id", product.id());
        after.put("qty", Quantities.format(qty));
        after.put("unit_cost_minor", cost);
        if (paidFrom != null) {
            after.put("paid_from", paidFrom);
            after.put("purchase_id", purchaseId);
        }
        audit.record(AuditLog.Entry.created("retail.stock.quick_added", SOURCE, id, branch, after));
    }
}
