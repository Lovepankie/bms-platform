package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.PackUnit;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.catalogue.RetailPricing;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Customer;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerBalance;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerList;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerPricingRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.OpenSale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Payment;
import com.rincoltech.bms.retail.sales.internal.SalesApi.PaymentList;
import com.rincoltech.bms.retail.sales.internal.SalesApi.PaymentRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.PaymentResult;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Resolution;
import com.rincoltech.bms.retail.sales.internal.SalesApi.ResolveRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.ResolvedLine;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLine;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLineRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SalePage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.UpdateCustomerRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.VoidRequest;
import com.rincoltech.bms.retail.sales.internal.SalesPricing.Chosen;
import com.rincoltech.bms.retail.sales.internal.SalesPricing.Decision;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.Header;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.LinePricing;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.NewLine;
import com.rincoltech.bms.retail.stock.BranchRoles;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.StockLedger.Recorded;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Sales and credit buyers (FR-RET-04, FR-RET-05, FR-RET-11, FR-RET-14). A sale and everything it
 * causes (lines with their snapshots, stock movements, two journal entries, the audit row, the
 * idempotency key) commit together or not at all.
 */
@Service
class SalesService {

    static final String SALE = "retail.sale";
    static final String SALE_VOID = "retail.sale_void";
    static final String PATH = "/api/v1/retail/sales";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final SalesRepository repo;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailBranchContext branches;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final BranchRoles roles;
    private final RetailCapabilities capabilities;
    private final SalesPricing pricing;
    private final RetailPricing priceLists;
    private final AccountsService accounts;
    private final ReturnsRepository returns;

    SalesService(
            SalesRepository repo,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            Idempotency idempotency,
            RetailBranchContext branches,
            TenantSequences sequences,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            BranchRoles roles,
            RetailCapabilities capabilities,
            SalesPricing pricing,
            RetailPricing priceLists,
            AccountsService accounts,
            ReturnsRepository returns) {
        this.repo = repo;
        this.catalogue = catalogue;
        this.stock = stock;
        this.books = books;
        this.idempotency = idempotency;
        this.branches = branches;
        this.sequences = sequences;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.roles = roles;
        this.capabilities = capabilities;
        this.pricing = pricing;
        this.priceLists = priceLists;
        this.accounts = accounts;
        this.returns = returns;
    }

    /** The general ledger account a payment method lands in (chapter 6 section 6.11.1). */
    static String accountFor(String method) {
        return switch (method) {
            case "cash" -> "cash_on_hand";
            case "mobile_money" -> "mobile_money";
            case "bank" -> "bank";
            case "credit" -> "trade_debtors";
            default -> throw new IllegalArgumentException(method);
        };
    }

    @Transactional
    Outcome<Sale> create(String idempotencyKey, SaleRequest r) {
        Outcome<Sale> outcome = idempotency.once(idempotencyKey, "POST", PATH, r, Sale.class, () -> record(r));
        return outcome;
    }

    /** FR-RET-04, FR-RET-05, FR-RET-11. */
    Sale record(SaleRequest r) {
        UUID branch = branches.resolveStocking("retail.sale.create", r.branchId());
        // ADR-033: a store never sells; with branch_roles off every branch acts as both.
        roles.requireMaySell(branch);
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate saleDate = r.saleDate() == null ? today : r.saleDate();
        List<FieldProblem> problems = new ArrayList<>();
        if (saleDate.isAfter(today)) {
            problems.add(new FieldProblem("sale_date", "invalid", "A sale cannot be dated in the future."));
        }
        boolean credit = r.paymentMethod().equals("credit");
        String buyerName = blankToNull(r.buyerName());
        Customer buyer =
                r.customerId() == null ? null : repo.customer(r.customerId()).orElse(null);
        if (r.customerId() != null && buyer == null) {
            problems.add(new FieldProblem("customer_id", "unknown_customer", "No such customer."));
        }
        // ADR-033, issue #196: with customer accounts on, a credit sale names a customer record. A typed name alone
        // would
        // dodge every limit (V11 allowed it), so it is refused.
        boolean accountsOn = credit && accounts.on();
        if (accountsOn && r.customerId() == null) {
            throw ApiException.rule(
                    "customer_required",
                    "A credit sale names a credit buyer from the list; a typed name is not enough.");
        }
        if (credit && r.customerId() == null && buyerName == null) {
            problems.add(new FieldProblem("customer_id", "required", "A credit sale names its buyer."));
        }
        if (!credit && r.dueDate() != null) {
            problems.add(new FieldProblem("due_date", "invalid", "Only a credit sale has a payment date."));
        }
        if (r.dueDate() != null && r.dueDate().isBefore(saleDate)) {
            problems.add(new FieldProblem("due_date", "invalid", "The payment date cannot be before the sale."));
        }
        // The buyer's row is locked before any stock balance is (spec 5.2), so that two shops selling to one buyer
        // queue
        // here and the limit is checked against what the first one sold.
        AccountsService.SaleAccount account = accountsOn && buyer != null ? accounts.lockForSale(buyer.id()) : null;
        LocalDate dueDate = r.dueDate() != null || account == null ? r.dueDate() : account.dueDate(saleDate);
        String currency = tenant.profile().currency();
        Principal principal = CurrentPrincipal.require();
        Priced priced = priceLines(branch, saleDate, buyer, r.priceListId(), r.lines(), problems);
        Chosen list = priced.list();
        List<NewLine> lines = priced.lines();
        long total = lines.stream().mapToLong(NewLine::lineTotalMinor).reduce(0, Math::addExact);
        long cost = lines.stream().mapToLong(NewLine::lineCostMinor).reduce(0, Math::addExact);
        AccountsService.Override creditOverride = account == null
                ? null
                : accounts.enforceLimit(buyer.id(), account, total, branch, r.creditOverrideReason());
        UUID id = UUID.randomUUID();
        UUID by = principal.userId();
        String saleNo = "RS%08d".formatted(sequences.next("retail_sale_no"));
        Sale header = new Sale(
                id,
                saleNo,
                branch,
                saleDate,
                r.paymentMethod(),
                r.customerId(),
                buyerName,
                blankToNull(r.buyerContact()),
                dueDate,
                "completed",
                currency,
                total,
                credit ? 0 : total,
                credit ? total : 0,
                cost,
                total - cost,
                list == null ? null : list.id(),
                list == null ? null : list.name(),
                List.of(),
                false,
                null,
                by,
                null,
                null,
                null,
                1);
        repo.insert(header);
        lines.forEach(l -> repo.insertLine(id, l));
        stock.record(
                saleDate,
                lines.stream()
                        .map(l -> new Movement(
                                branch,
                                l.productId(),
                                "sale",
                                l.qty().negate(),
                                l.unitCostMinor(),
                                SALE,
                                id,
                                l.id(),
                                null,
                                null))
                        .toList());
        Leg receivable = Leg.debit(accountFor(r.paymentMethod()), total);
        if (credit) {
            receivable = receivable.withSubledger(SALE, id);
        }
        UUID saleEntry = books.post(new Posting(
                        branch,
                        saleDate,
                        saleNo,
                        "Sale " + saleNo,
                        SALE,
                        id,
                        "retail.sale:" + id,
                        List.of(receivable, Leg.credit("sales_revenue", total))))
                .map(PostedEntry::entryId)
                .orElse(null);
        UUID costEntry = books.post(new Posting(
                        branch,
                        saleDate,
                        saleNo,
                        "Cost of sale " + saleNo,
                        SALE,
                        id,
                        "retail.sale_cost:" + id,
                        List.of(Leg.debit("cost_of_goods_sold", cost), Leg.credit("inventory", cost))))
                .map(PostedEntry::entryId)
                .orElse(null);
        repo.setEntries(id, saleEntry, costEntry);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sale_no", saleNo);
        after.put("payment_method", r.paymentMethod());
        after.put("total_minor", total);
        after.put("lines", lines.size());
        if (list != null) {
            after.put("price_list_id", list.id());
        }
        // Issue #195: every changed price is in the audit entry of the sale, with the price the list resolved and why.
        List<Map<String, Object>> overrides = lines.stream()
                .filter(l -> l.pricing() != null && l.pricing().source().equals("override"))
                .map(l -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("line_no", l.lineNo());
                    o.put("product_id", l.productId());
                    if (l.pricing().priceListId() != null) {
                        o.put("price_list_id", l.pricing().priceListId());
                    }
                    o.put("resolved_unit_price_minor", l.pricing().resolvedUnitPriceMinor());
                    o.put("unit_price_minor", l.saleUnitId() == null ? l.unitPriceMinor() : l.saleUnitPriceMinor());
                    o.put("reason", l.pricing().overrideReason());
                    return o;
                })
                .toList();
        if (!overrides.isEmpty()) {
            after.put("price_overrides", overrides);
        }
        if (creditOverride != null) {
            // The sale went above the buyer's limit with a reason, by someone who may: said in the sale's own entry and
            // in an entry of its own that a search for overrides finds.
            after.put("credit_override", creditOverride.asMap());
            audit.record(
                    AuditLog.Entry.created("retail.credit.limit_overridden", SALE, id, branch, creditOverride.asMap()));
        }
        audit.record(AuditLog.Entry.created("retail.sale.created", SALE, id, branch, after));
        return visible(repo.find(id, false).orElseThrow().sale());
    }

    /** The lines of a sale or a quotation, priced and checked, and the list that priced them (null when none applied). */
    record Priced(Chosen list, List<NewLine> lines) {}

    /**
     * The server side of pricing a set of lines for {@code buyer} (ADR-033, issues #194 and #195): pack units, the
     * resolved price and any override, the price floor. A sale takes this before it moves stock; a quotation takes it
     * to offer the same prices a sale would take. Field problems are thrown as one validation error.
     */
    Priced priceLines(
            UUID branch,
            LocalDate saleDate,
            Customer buyer,
            UUID priceListId,
            List<SaleLineRequest> requests,
            List<FieldProblem> problems) {
        String currency = tenant.profile().currency();
        Principal principal = CurrentPrincipal.require();
        boolean priced = pricing.on();
        if (!priced && priceListId != null) {
            problems.add(new FieldProblem(
                    "price_list_id", "capability_not_enabled", "Price lists are not enabled for this business."));
        }
        Chosen list = priced ? pricing.choose(branch, priceListId, buyer, problems) : null;
        List<NewLine> lines = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            SaleLineRequest l = requests.get(i);
            String field = "lines[" + i + "]";
            ProductSnapshot p = catalogue.find(l.productId()).orElse(null);
            if (p == null) {
                problems.add(new FieldProblem(field + ".product_id", "unknown_product", "No such product."));
                continue;
            }
            if (!p.active()) {
                problems.add(new FieldProblem(field + ".product_id", "product_inactive", "The product is inactive."));
                continue;
            }
            if (!p.currency().equals(currency)) {
                problems.add(new FieldProblem(field + ".product_id", "currency_mismatch", "Priced in " + p.currency()));
                continue;
            }
            if (!Quantities.exact(l.qty())) {
                problems.add(new FieldProblem(field + ".qty", "invalid", "At most three decimal places."));
                continue;
            }
            SaleUnit unit = saleUnit(field, p, l.unitId(), l.qty(), problems);
            if (unit == null) {
                continue;
            }
            PackUnit pack = unit.pack();
            long defaultPrice = pack == null ? p.sellMinor() : Quantities.packPrice(p.sellMinor(), pack.factor());
            long price = l.unitPriceMinor() == null ? defaultPrice : l.unitPriceMinor();
            LinePricing linePricing = null;
            if (priced) {
                // ADR-033, issue #195: the server resolves the price; a different typed price is an override.
                Decision d = pricing.decide(
                        list, p.id(), pack == null ? p.unitId() : pack.unitId(), l.qty(), defaultPrice, saleDate);
                if (d.refused()) {
                    throw ApiException.rule(
                            "no_price_in_list",
                            "The price list " + list.name() + " has no price for " + p.description() + " on line "
                                    + (i + 1) + ".");
                }
                price = d.priceMinor();
                String source = d.source();
                String reason = null;
                if (l.unitPriceMinor() != null && l.unitPriceMinor() != d.priceMinor()) {
                    if (!principal.may(SalesPricing.OVERRIDE, branch)) {
                        throw new ApiException(
                                HttpStatus.FORBIDDEN,
                                "permission_denied",
                                "Permission denied",
                                "You do not have permission to change the price on line " + (i + 1) + ".");
                    }
                    reason = blankToNull(l.priceOverrideReason());
                    if (reason == null || reason.length() < SalesPricing.MIN_REASON) {
                        throw ApiException.rule(
                                "override_reason_required",
                                "Give a reason of at least " + SalesPricing.MIN_REASON
                                        + " characters for changing the price on line " + (i + 1) + ".");
                    }
                    price = l.unitPriceMinor();
                    source = "override";
                }
                linePricing = new LinePricing(source, d.listId(), d.breakMinQty(), d.priceMinor(), reason);
            }
            if (pack != null) {
                BigDecimal baseQty = unit.baseQty();
                long total = Quantities.value(l.qty(), price);
                long perBase = Quantities.perBase(total, baseQty);
                // The column CHECKs bound a price at 10^13 (review F7); a defaulted pack price or the derived price per
                // base unit can exceed it.
                if (price > RetailCatalogue.MAX_AMOUNT_MINOR || perBase > RetailCatalogue.MAX_AMOUNT_MINOR) {
                    throw ApiException.rule(
                            "amount_out_of_range", "The price on " + field + " is too large to compute.");
                }
                lines.add(new NewLine(
                        UUID.randomUUID(),
                        i + 1,
                        p.id(),
                        baseQty,
                        perBase,
                        p.costMinor(),
                        total,
                        Quantities.value(baseQty, p.costMinor()),
                        pack.unitId(),
                        l.qty(),
                        price,
                        pack.factor(),
                        linePricing));
                continue;
            }
            lines.add(new NewLine(
                    UUID.randomUUID(),
                    i + 1,
                    p.id(),
                    l.qty(),
                    price,
                    p.costMinor(),
                    Quantities.value(l.qty(), price),
                    Quantities.value(l.qty(), p.costMinor()),
                    null,
                    null,
                    null,
                    null,
                    linePricing));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        // ADR-020 decision 5: the message never carries the cost, which a sales caller cannot read.
        if (!principal.may("retail.price.below_cost", branch)) {
            for (NewLine l : lines) {
                // A pack line is judged on its line total, never on the rounded price per base unit (spec 3.3).
                boolean below = l.saleUnitId() == null
                        ? l.unitPriceMinor() <= l.unitCostMinor()
                        : l.lineTotalMinor() <= l.lineCostMinor();
                if (below) {
                    throw ApiException.rule(
                            "price_below_cost",
                            "The price on line " + l.lineNo() + " is too low: it must be above the product's cost.");
                }
            }
        }
        return new Priced(list, lines);
    }

    /** What a sale line's unit comes to: the pack (null for the base unit) and the quantity in base units. */
    private record SaleUnit(PackUnit pack, BigDecimal baseQty) {}

    /**
     * ADR-033: a pack. Stock moves in base units; the entered unit, quantity and price are kept on the line. Returns null
     * (with a field problem added) when the unit cannot be used; a quantity that does not convert exactly is refused.
     */
    private SaleUnit saleUnit(
            String field, ProductSnapshot p, UUID unitId, BigDecimal qty, List<FieldProblem> problems) {
        if (unitId == null || unitId.equals(p.unitId())) {
            return new SaleUnit(null, qty);
        }
        if (!capabilities.enabled(RetailCapabilities.UNITS)) {
            problems.add(new FieldProblem(
                    field + ".unit_id", "capability_not_enabled", "Pack sizes are not enabled for this business."));
            return null;
        }
        PackUnit pack = catalogue.packUnit(p.id(), unitId).orElse(null);
        if (pack == null || !pack.active()) {
            problems.add(
                    new FieldProblem(field + ".unit_id", "unknown_pack_unit", "This item is not sold in that unit."));
            return null;
        }
        BigDecimal baseQty = Quantities.toBase(qty, pack.factor())
                .orElseThrow(() -> ApiException.rule(
                        "quantity_not_representable",
                        qty + " " + pack.unit() + " does not convert exactly to thousandths of " + p.unit()
                                + ". Change the quantity."));
        return new SaleUnit(pack, baseQty);
    }

    /**
     * Issue #195: the price and its source for each line, as the sale would take them, without making a sale. The
     * cost is never in the answer. A strict list with no price for a line is reported on the line, not refused.
     */
    @Transactional(readOnly = true)
    Resolution resolve(ResolveRequest r) {
        capabilities.require(RetailCapabilities.PRICE_LISTS);
        UUID branch = branches.resolveStocking("retail.sale.create", r.branchId());
        // The same refusal as the sale: a store never sells, so it is not priced either (ADR-033).
        roles.requireMaySell(branch);
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate saleDate = r.saleDate() == null ? today : r.saleDate();
        List<FieldProblem> problems = new ArrayList<>();
        if (saleDate.isAfter(today)) {
            problems.add(new FieldProblem("sale_date", "invalid", "A sale cannot be dated in the future."));
        }
        Customer buyer =
                r.customerId() == null ? null : repo.customer(r.customerId()).orElse(null);
        if (r.customerId() != null && buyer == null) {
            problems.add(new FieldProblem("customer_id", "unknown_customer", "No such customer."));
        }
        Chosen list = pricing.choose(branch, r.priceListId(), buyer, problems);
        List<ResolvedLine> out = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            SalesApi.ResolveLineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            ProductSnapshot p = catalogue.find(l.productId()).orElse(null);
            if (p == null) {
                problems.add(new FieldProblem(field + ".product_id", "unknown_product", "No such product."));
                continue;
            }
            if (!Quantities.exact(l.qty())) {
                problems.add(new FieldProblem(field + ".qty", "invalid", "At most three decimal places."));
                continue;
            }
            SaleUnit unit = saleUnit(field, p, l.unitId(), l.qty(), problems);
            if (unit == null) {
                continue;
            }
            PackUnit pack = unit.pack();
            long defaultPrice = pack == null ? p.sellMinor() : Quantities.packPrice(p.sellMinor(), pack.factor());
            Decision d = pricing.decide(
                    list, p.id(), pack == null ? p.unitId() : pack.unitId(), l.qty(), defaultPrice, saleDate);
            out.add(new ResolvedLine(
                    p.id(),
                    pack == null ? null : pack.unitId(),
                    Quantities.format(l.qty()),
                    d.priceMinor(),
                    Quantities.value(l.qty(), d.priceMinor()),
                    d.source(),
                    d.listId(),
                    d.breakMinQty() == null ? null : Quantities.format(d.breakMinQty()),
                    d.refused() ? "no_price_in_list" : null));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return new Resolution(
                list == null ? null : list.id(),
                list == null ? null : list.name(),
                list == null ? null : list.origin(),
                out);
    }

    /** Issue #195: the group and the own price list of a credit buyer, replaced under If-Match. */
    @Transactional
    Customer setCustomerPricing(UUID id, String ifMatch, CustomerPricingRequest r) {
        capabilities.require(RetailCapabilities.PRICE_LISTS);
        int expected = Versions.fromIfMatch(ifMatch);
        Customer before = repo.customer(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        List<FieldProblem> problems = new ArrayList<>();
        // What the customer holds already may stay even if it was switched off since; a new choice must be on.
        if (r.customerGroupId() != null
                && !r.customerGroupId().equals(before.customerGroupId())
                && priceLists
                        .customerGroup(r.customerGroupId())
                        .filter(g -> g.active())
                        .isEmpty()) {
            problems.add(new FieldProblem(
                    "customer_group_id", "unknown_customer_group", "Choose a group that exists and is switched on."));
        }
        if (r.priceListId() != null
                && !r.priceListId().equals(before.priceListId())
                && priceLists.priceList(r.priceListId()).filter(l -> l.active()).isEmpty()) {
            problems.add(new FieldProblem(
                    "price_list_id", "unknown_price_list", "Choose a price list that exists and is switched on."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        if (!java.util.Objects.equals(before.customerGroupId(), r.customerGroupId())) {
            was.put("customer_group_id", before.customerGroupId());
            now.put("customer_group_id", r.customerGroupId());
        }
        if (!java.util.Objects.equals(before.priceListId(), r.priceListId())) {
            was.put("price_list_id", before.priceListId());
            now.put("price_list_id", r.priceListId());
        }
        if (now.isEmpty()) {
            return forCaller(before);
        }
        if (repo.updateCustomerPricing(id, r.customerGroupId(), r.priceListId(), expected) == 0) {
            throw repo.customer(id).map(c -> Versions.conflict(c.version())).orElseGet(ApiException::notFound);
        }
        audit.record(new AuditLog.Entry("retail.customer.pricing_changed", "retail.customer", id, null, was, now));
        return forCaller(repo.customer(id).orElseThrow());
    }

    static final java.util.Set<String> OWING_FILTERS = java.util.Set.of("owing", "overdue", "paid");

    static final java.util.Set<String> PAYMENT_METHODS = java.util.Set.of("cash", "mobile_money", "bank", "credit");

    @Transactional(readOnly = true)
    SalePage list(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            String paymentMethod,
            UUID productId,
            String buyer,
            String status,
            String owing,
            UUID sellerId,
            UUID categoryId,
            boolean newestFirst,
            Integer limit,
            String cursor) {
        if (owing != null && !OWING_FILTERS.contains(owing)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("owing", "invalid", "owing is owing, overdue or paid.")));
        }
        if (paymentMethod != null && !PAYMENT_METHODS.contains(paymentMethod)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "payment_method", "invalid", "payment_method is cash, mobile_money, bank or credit.")));
        }
        if (status != null && !status.equals("completed") && !status.equals("voided")) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is completed or voided.")));
        }
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.sale.read", branchIds);
        if (sellerId != null && !SalesSummaryService.profitVisible(principal, filter)) {
            // A person's takings are management information, as in the salesperson level of the summary.
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Listing a salesperson's sales is for people who may read profit in every branch shown.");
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Instant afterCreated = null;
        UUID afterId = null;
        // The list keeps its time order, so no rank travels, but the cursor still belongs to its search text.
        Search.Query search = Search.parse(buyer);
        Cursor.Key after =
                Cursor.decodeKey(Search.unwrap(search, cursor).cursor()).orElse(null);
        if (after != null) {
            String direction = newestFirst ? "desc:" : "asc:";
            if (!after.sortKey().startsWith("desc:") && !after.sortKey().startsWith("asc:")) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Invalid cursor.");
            }
            if (!after.sortKey().startsWith(direction)) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "cursor", "invalid", "The cursor was issued for the other newest_first direction.")));
            }
            afterCreated = new Cursor.Key(after.sortKey().substring(direction.length()), after.id()).at();
            afterId = after.id();
        }
        LocalDate today = owing == null ? null : clock.today(tenant.profile().timezone());
        List<Sale> rows = repo.page(
                filter,
                from,
                to,
                customerId,
                paymentMethod,
                productId,
                search,
                status,
                owing,
                today,
                sellerId,
                categoryId,
                newestFirst,
                afterCreated,
                afterId,
                size + 1);
        boolean more = rows.size() > size;
        List<Sale> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        0,
                        Cursor.encode((newestFirst ? "desc:" : "asc:")
                                + items.getLast().createdAt() + "|"
                                + items.getLast().id()))
                : null;
        return new SalePage(items.stream().map(this::visible).toList(), next);
    }

    @Transactional(readOnly = true)
    Sale get(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, false)
                .map(Header::sale)
                .filter(s -> principal.may("retail.sale.read", s.branchId()))
                .map(this::visible)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * FR-RET-04, FR-RET-11: a void reverses each stock movement with a {@code return} movement and
     * each journal entry with its reversal, under the sale's row lock. A credit sale that has taken
     * a payment is not voided.
     */
    @Transactional
    Sale voidSale(UUID id, VoidRequest r) {
        Principal principal = CurrentPrincipal.require();
        Header h = repo.find(id, true)
                .filter(x -> principal.may("retail.sale.void", x.sale().branchId()))
                .orElseThrow(ApiException::notFound);
        Sale s = h.sale();
        if (s.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "sale_voided", "Sale voided", "This sale has been voided already.");
        }
        if (s.paymentMethod().equals("credit") && s.paidMinor() > 0) {
            throw ApiException.rule("sale_has_payments", "A credit sale with payments against it cannot be voided.");
        }
        // Customer returns (issue #201) put part of the sale back already; a void would take it back a second time.
        // Void the
        // returns first.
        if (returns.hasCompleted(id)) {
            throw ApiException.rule(
                    "sale_has_returns", "A sale with returns against it cannot be voided. Void its returns first.");
        }
        String reason = r.reason().trim();
        List<Recorded> moved = stock.bySource(SALE, id);
        LocalDate today = clock.today(tenant.profile().timezone());
        stock.record(
                today,
                moved.stream()
                        .map(m -> new Movement(
                                m.branchId(),
                                m.productId(),
                                "return",
                                m.qty().negate(),
                                m.unitCostMinor(),
                                SALE_VOID,
                                id,
                                m.sourceLineId(),
                                m.id(),
                                reason))
                        .toList());
        if (h.saleEntryId() != null) {
            books.reverse(h.saleEntryId(), today, "Void " + s.saleNo(), "retail.sale_void:" + id);
        }
        if (h.costEntryId() != null) {
            books.reverse(h.costEntryId(), today, "Void " + s.saleNo(), "retail.sale_cost_void:" + id);
        }
        repo.markVoided(id, principal.userId(), reason);
        audit.record(new AuditLog.Entry(
                "retail.sale.voided",
                SALE,
                id,
                s.branchId(),
                Map.of("status", "completed"),
                Map.of("status", "voided", "reason", reason)));
        return visible(repo.find(id, false).orElseThrow().sale());
    }

    // ---- Payments against a credit sale ------------------------------------------------------

    @Transactional
    Outcome<PaymentResult> pay(String idempotencyKey, UUID saleId, PaymentRequest r) {
        return idempotency.once(
                idempotencyKey,
                "POST",
                PATH + "/" + saleId + "/payments",
                r,
                PaymentResult.class,
                () -> recordPayment(saleId, r));
    }

    /**
     * FR-RET-05, FR-RET-11: a payment, partial allowed, under the sale's row lock; it never takes
     * the paid amount past the total. Debits the payment method's account and credits trade
     * debtors with the sale as subledger, in the sale's branch.
     */
    private PaymentResult recordPayment(UUID saleId, PaymentRequest r) {
        Principal principal = CurrentPrincipal.require();
        Sale s = repo.find(saleId, true)
                .map(Header::sale)
                .filter(x -> principal.may("retail.sale.create", x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (!s.paymentMethod().equals("credit") || !s.status().equals("completed")) {
            throw ApiException.rule("sale_not_payable", "Only a completed credit sale takes payments.");
        }
        if (s.historical()) {
            // Issue #338: an imported credit sale has no debtor in the books (import runbook), so cash against it
            // would credit trade debtors for an amount that was never recorded. Same code as the account path.
            throw ApiException.rule(
                    "sale_historical",
                    "An imported sale cannot be paid from here. Record this payment with your accountant.");
        }
        long balance = s.totalMinor() - s.paidMinor();
        if (r.amountMinor() > balance) {
            throw ApiException.rule(
                    "payment_exceeds_balance", "The sale's balance is " + balance + "; the payment is larger.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate paidOn = r.paidOn() == null ? today : r.paidOn();
        if (paidOn.isAfter(today) || paidOn.isBefore(s.saleDate())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("paid_on", "invalid", "A payment is dated between the sale and today.")));
        }
        UUID id = UUID.randomUUID();
        PostedEntry entry = books.post(new Posting(
                        s.branchId(),
                        paidOn,
                        s.saleNo(),
                        "Payment on " + s.saleNo(),
                        "retail.sale_payment",
                        id,
                        "retail.sale_payment:" + id,
                        List.of(
                                Leg.debit(accountFor(r.method()), r.amountMinor()),
                                Leg.credit("trade_debtors", r.amountMinor()).withSubledger(SALE, saleId))))
                .orElseThrow();
        Payment payment = new Payment(
                id,
                saleId,
                r.amountMinor(),
                s.currency(),
                r.method(),
                paidOn,
                entry.entryId(),
                clock.now(),
                principal.userId());
        repo.insertPayment(payment);
        repo.addPaid(saleId, r.amountMinor());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("payment_id", id);
        after.put("amount_minor", r.amountMinor());
        after.put("method", r.method());
        after.put("paid_minor", s.paidMinor() + r.amountMinor());
        audit.record(new AuditLog.Entry(
                "retail.sale.payment_recorded",
                SALE,
                saleId,
                s.branchId(),
                Map.of("paid_minor", s.paidMinor()),
                after));
        return new PaymentResult(payment, s.paidMinor() + r.amountMinor(), balance - r.amountMinor());
    }

    @Transactional(readOnly = true)
    PaymentList payments(UUID saleId) {
        Sale s = get(saleId);
        return new PaymentList(repo.payments(s.id()));
    }

    // ---- Customers -------------------------------------------------------------------------

    @Transactional
    Customer createCustomer(CustomerRequest r) {
        Customer c = new Customer(
                UUID.randomUUID(), r.name().trim(), blankToNull(r.contact()), null, null, null, null, null, null, 1);
        repo.insertCustomer(c, CurrentPrincipal.require().userId());
        audit.record(AuditLog.Entry.created(
                "retail.customer.created", "retail.customer", c.id(), null, Map.of("name", c.name())));
        return forCaller(repo.customer(c.id()).orElseThrow());
    }

    /**
     * The group and list of a buyer are shown only while the capability is on and only to retail.pricelist.manage (they
     * say what the buyer pays); otherwise the buyer reads as before price lists.
     */
    private Customer forCaller(Customer c) {
        // The credit limit and the terms travel only while customer accounts are on (issue #196); off, the buyer reads
        // exactly as it did before them.
        boolean accountsOn = accounts.on();
        boolean showPricing = c.customerGroupId() == null && c.priceListId() == null
                || pricing.on() && CurrentPrincipal.require().hasPermission("retail.pricelist.manage");
        boolean showAccount = accountsOn || c.creditLimitMinor() == null && c.termsDays() == null;
        if (showPricing && showAccount) {
            return c;
        }
        return new Customer(
                c.id(),
                c.name(),
                c.contact(),
                c.createdAt(),
                c.balanceMinor(),
                showPricing ? c.customerGroupId() : null,
                showPricing ? c.priceListId() : null,
                accountsOn ? c.creditLimitMinor() : null,
                accountsOn ? c.termsDays() : null,
                c.version());
    }

    /** #146: edit a credit buyer's name or contact. Nothing else about a buyer changes, and none is deleted. */
    @Transactional
    Customer updateCustomer(UUID id, String ifMatch, UpdateCustomerRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Customer before = repo.customer(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = r.name() == null ? before.name() : r.name().trim();
        String contact = r.contact() == null ? before.contact() : blankToNull(r.contact());
        if (name.isEmpty()) {
            throw ApiException.validation(
                    List.of(new ApiException.FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        Map<String, Object> was = new java.util.LinkedHashMap<>();
        Map<String, Object> now = new java.util.LinkedHashMap<>();
        if (!name.equals(before.name())) {
            was.put("name", before.name());
            now.put("name", name);
        }
        if (!java.util.Objects.equals(contact, before.contact())) {
            now.put("contact_changed", true);
        }
        if (now.isEmpty()) {
            return forCaller(before);
        }
        if (repo.updateCustomer(id, name, contact, expected) == 0) {
            throw repo.customer(id).map(c -> Versions.conflict(c.version())).orElseGet(ApiException::notFound);
        }
        audit.record(new AuditLog.Entry("retail.customer.updated", "retail.customer", id, null, was, now));
        return forCaller(repo.customer(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    CustomerList customers(String query, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Search.Query search = Search.parse(query);
        Search.After position = Search.unwrap(search, cursor);
        com.rincoltech.bms.kernel.Cursor.Key after =
                com.rincoltech.bms.kernel.Cursor.decodeKey(position.cursor()).orElse(null);
        List<SalesRepository.CustomerRow> rows = repo.customers(
                search,
                size + 1,
                CurrentPrincipal.require().branchFilter("retail.sale.read", null),
                position.rank(),
                after);
        boolean more = rows.size() > size;
        List<SalesRepository.CustomerRow> page = more ? rows.subList(0, size) : rows;
        // The key is the database's own lower(name), so the cursor agrees with the ORDER BY.
        String next = more
                ? Search.wrap(
                        search,
                        page.getLast().rank(),
                        com.rincoltech.bms.kernel.Cursor.encode(page.getLast().sortKey() + "|"
                                + page.getLast().customer().id()))
                : null;
        return new CustomerList(
                page.stream()
                        .map(SalesRepository.CustomerRow::customer)
                        .map(this::forCaller)
                        .toList(),
                next);
    }

    /** FR-RET-05: what the customer owes on credit sales in the caller's branch scope. */
    @Transactional(readOnly = true)
    CustomerBalance balance(UUID customerId) {
        repo.customer(customerId).orElseThrow(ApiException::notFound);
        List<UUID> filter = CurrentPrincipal.require().branchFilter("retail.sale.read", null);
        List<OpenSale> open = repo.openSales(customerId, filter);
        long owed = open.stream().mapToLong(OpenSale::balanceMinor).reduce(0, Math::addExact);
        // Which sales are imported is said only while customer accounts are on (issue #196); off, the answer is as
        // before.
        if (!accounts.on()) {
            open = open.stream()
                    .map(s -> new OpenSale(
                            s.saleId(),
                            s.saleNo(),
                            s.branchId(),
                            s.saleDate(),
                            s.dueDate(),
                            s.totalMinor(),
                            s.paidMinor(),
                            s.balanceMinor(),
                            null))
                    .toList();
        }
        return new CustomerBalance(customerId, tenant.profile().currency(), owed, open);
    }

    /**
     * Cost and profit need {@code retail.profit.read} in the sale's branch (ADR-017; review F5). While the
     * {@code price_lists} capability is off a sale says nothing about price lists, whatever it was made with: the list
     * and the way each line was priced are left out (issue #195), so the capability off reads exactly as before.
     */
    Sale visible(Sale s) {
        Principal principal = CurrentPrincipal.require();
        boolean hideCost = !principal.may("retail.profit.read", s.branchId());
        // Who made or voided a sale is shown to those who may read profit there, and to the person themselves.
        UUID createdBy =
                hideCost && !java.util.Objects.equals(principal.userId(), s.createdBy()) ? null : s.createdBy();
        UUID voidedBy = hideCost && !java.util.Objects.equals(principal.userId(), s.voidedBy()) ? null : s.voidedBy();
        boolean hidePricing = !pricing.on();
        if (!hideCost && !hidePricing) {
            return s;
        }
        return new Sale(
                s.id(),
                s.saleNo(),
                s.branchId(),
                s.saleDate(),
                s.paymentMethod(),
                s.customerId(),
                s.buyerName(),
                s.buyerContact(),
                s.dueDate(),
                s.status(),
                s.currency(),
                s.totalMinor(),
                s.paidMinor(),
                s.balanceMinor(),
                hideCost ? null : s.costTotalMinor(),
                hideCost ? null : s.profitMinor(),
                hidePricing ? null : s.priceListId(),
                hidePricing ? null : s.priceList(),
                s.lines().stream()
                        .map(l -> new SaleLine(
                                l.id(),
                                l.lineNo(),
                                l.productId(),
                                l.code(),
                                l.description(),
                                l.qty(),
                                l.unitPriceMinor(),
                                l.lineTotalMinor(),
                                hideCost ? null : l.unitCostMinor(),
                                hideCost ? null : l.lineCostMinor(),
                                l.saleUnitId(),
                                l.saleUnit(),
                                l.saleQty(),
                                l.saleUnitPriceMinor(),
                                l.unitFactor(),
                                hidePricing ? null : l.price()))
                        .toList(),
                s.historical(),
                s.createdAt(),
                createdBy,
                s.voidedAt(),
                voidedBy,
                s.voidReason(),
                s.version());
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
