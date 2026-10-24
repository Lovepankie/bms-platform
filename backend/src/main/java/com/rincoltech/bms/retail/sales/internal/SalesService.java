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
import com.rincoltech.bms.kernel.PushNotice;
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
import com.rincoltech.bms.retail.sales.internal.SalesApi.Device;
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
import com.rincoltech.bms.retail.stock.CreditPeriods;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
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
    private final ApplicationEventPublisher events;

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
            ReturnsRepository returns,
            ApplicationEventPublisher events) {
        this.events = events;
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

    /** How far ahead of the server a device clock may be and still count as now (ADR-067 decision 6). */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    /**
     * How far before prices_as_of a sale recorded offline may say it happened and still be dated as it says (ADR-067
     * decision 6); further before, the phone clock was behind.
     */
    static final Duration CLOCK_BEHIND_SKEW = Duration.ofMinutes(2);

    @Transactional
    Outcome<Sale> create(String idempotencyKey, SaleRequest r) {
        // ADR-067: a sale already recorded with this client sale id is the same sale, whatever key it comes with, so a
        // send after the key expired, or with a new key after a lost answer, answers it again and does nothing.
        boolean[] earlier = {false};
        Outcome<Sale> outcome = idempotency.once(idempotencyKey, "POST", PATH, r, Sale.class, () -> {
            if (r.clientSaleId() != null) {
                Optional<Sale> found = recordedAs(r);
                if (found.isPresent()) {
                    earlier[0] = true;
                    return found.get();
                }
            }
            return record(r);
        });
        return earlier[0] ? new Outcome<>(outcome.body(), true) : outcome;
    }

    /**
     * The sale recorded with this request's client sale id, when the caller made it with the same request; 422 when someone
     * else did, or when the request differs from the one recorded (a changed sale is never answered with the old one).
     */
    private Optional<Sale> recordedAs(SaleRequest r) {
        Optional<SalesRepository.ByClient> found = repo.byClientSaleId(r.clientSaleId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!found.get().createdBy().equals(CurrentPrincipal.require().userId())) {
            throw ApiException.rule(
                    "client_sale_id_reused", "This client sale id belongs to a sale someone else recorded.");
        }
        if (found.get().requestHash() != null && !found.get().requestHash().equals(requestHash(r))) {
            throw ApiException.rule(
                    "client_sale_conflict",
                    "A different sale was already recorded with this client sale id. Start a new sale for the changes.");
        }
        return Optional.of(
                visible(repo.find(found.get().saleId(), false).orElseThrow().sale()));
    }

    /**
     * The fingerprint of a request with a client sale id (ADR-067 decision 5): the SHA-256 of a fixed canonical text of what
     * the sale is (the branch, the payment method, the credit buyer, and per line the item, the pack, the quantity and the
     * price), never of how it was delivered or worded, so a sale whose answer was lost and that is sent again from the outbox
     * is the same request, a new field on the request changes nothing, and a changed sale is not the same.
     */
    static String requestHash(SaleRequest r) {
        StringBuilder text = new StringBuilder("v1")
                .append('|')
                .append(r.branchId())
                .append('|')
                .append(r.paymentMethod())
                .append('|')
                .append(r.customerId());
        for (SaleLineRequest l : r.lines()) {
            text.append("|line:")
                    .append(l.productId())
                    .append(',')
                    .append(l.unitId())
                    .append(',')
                    .append(
                            l.qty() == null
                                    ? null
                                    : l.qty().stripTrailingZeros().toPlainString())
                    .append(',')
                    .append(l.unitPriceMinor());
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A clash found while recording a sale made offline, written once the sale exists (ADR-067 decision 7). */
    record PendingClash(Integer lineNo, UUID productId, String kind, String words) {}

    /** FR-RET-04, FR-RET-05, FR-RET-11. */
    Sale record(SaleRequest r) {
        return record(r, null, null);
    }

    /**
     * How a line of a customer order was priced when the order was taken (issue #385): {@code source} is the order line's price
     * source (null without price lists), {@code overriddenBy} who changed the price then (the person who took the order), when
     * the source is override.
     */
    record Agreement(String source, UUID overriddenBy) {}

    /**
     * As {@link #record(SaleRequest)}, for the delivery of a customer order (issue #385, ADR-052): {@code creditDelta}, when not
     * null, is added to the sale's total to give what actually goes on the buyer's credit (less the part paid at the door, plus a
     * delivery fee left on the account), the amount the credit limit is checked against; {@code agreements}, one per line, means
     * the lines carry the prices agreed when the order was taken, already checked then, so the price floor, the price override
     * permission and the inactive product are not checked again (the stock is). A price changed when the order was taken stays an
     * override, for the person who changed it; an agreed price that today's price list would price otherwise is order_agreed,
     * which the Controls page does not count again. Every other rule of a sale holds.
     */
    Sale record(SaleRequest r, Long creditDelta, List<Agreement> agreements) {
        // ADR-067 decision 8: the refused offline sale's row is locked to the commit, so a manager's discard running
        // now
        // either finishes first and refuses this sale, or waits and finds it recorded. A discarded one is never
        // recorded.
        if (r.clientSaleId() != null
                && repo.lockFailure(r.clientSaleId())
                        .filter("discarded"::equals)
                        .isPresent()) {
            throw ApiException.rule(
                    "offline_sale_discarded",
                    "A manager discarded this sale, so it is not recorded. It is removed from the phone.");
        }
        boolean agreed = agreements != null;
        UUID branch = branches.resolveStocking("retail.sale.create", r.branchId());
        // ADR-033: a store never sells; with branch_roles off every branch acts as both.
        roles.requireMaySell(branch);
        branches.requireSells(branch, "branch_id");
        LocalDate today = clock.today(tenant.profile().timezone());
        boolean offline = r.offline();
        boolean credit = r.paymentMethod().equals("credit");
        List<PendingClash> clashes = offline ? new ArrayList<>() : null;
        // A phone clock ahead of the server cannot date its prices in the future: they are as of now at the latest.
        Instant pricesAsOf =
                r.pricesAsOf() == null || r.pricesAsOf().isBefore(clock.now()) ? r.pricesAsOf() : clock.now();
        Instant occurredAt = occurredAt(r, pricesAsOf, clashes);
        LocalDate occurredOn = occurredAt == null
                ? null
                : LocalDate.ofInstant(occurredAt, tenant.profile().timezone());
        if (occurredOn != null && r.saleDate() != null && !r.saleDate().equals(occurredOn)) {
            throw ApiException.rule(
                    "sale_date_mismatch", "The sale date is not the day the sale happened (" + occurredOn + ").");
        }
        LocalDate saleDate = occurredOn != null ? occurredOn : r.saleDate() == null ? today : r.saleDate();
        List<FieldProblem> problems = new ArrayList<>();
        if (saleDate.isAfter(today)) {
            problems.add(new FieldProblem("sale_date", "invalid", "A sale cannot be dated in the future."));
        }
        if (offline && credit) {
            // ADR-067 decision 1: the buyer's limit cannot be checked on a phone with no network.
            throw ApiException.rule(
                    "offline_credit_sale", "A credit sale cannot be made offline. Record it again when online.");
        }
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
                    "customer_required", "A credit sale names a customer from the list; a typed name is not enough.");
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
        // ADR-079: the balance rows are locked, in product order, before the lines read the branch's average cost, so a
        // delivery committing meanwhile cannot leave the sale costed at the average it replaced.
        r.lines().stream()
                .map(SaleLineRequest::productId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .filter(id -> catalogue.find(id).isPresent())
                .forEach(id -> stock.lock(branch, id));
        Priced priced = priceLines(
                branch, saleDate, buyer, r.priceListId(), r.lines(), problems, agreements, clashes, pricesAsOf);
        Chosen list = priced.list();
        List<NewLine> lines = priced.lines();
        long total = lines.stream().mapToLong(NewLine::lineTotalMinor).reduce(0, Math::addExact);
        long cost = lines.stream().mapToLong(NewLine::lineCostMinor).reduce(0, Math::addExact);
        AccountsService.Override creditOverride = account == null
                ? null
                : accounts.enforceLimit(
                        buyer.id(),
                        account,
                        creditDelta == null ? total : Math.addExact(total, creditDelta),
                        branch,
                        r.creditOverrideReason());
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
                1,
                null,
                false,
                null,
                r.clientSaleId() == null ? null : new Device(r.clientSaleId(), occurredAt, offline, List.of()));
        try {
            repo.insert(header, r.clientSaleId() == null ? null : requestHash(r));
        } catch (DuplicateKeyException e) {
            // ADR-067: another send of the same client sale id is being recorded right now; its retry finds the sale.
            // Any other unique violation is not that race and keeps its own error.
            if (r.clientSaleId() == null || !String.valueOf(e.getMessage()).contains("retail_sales_client_sale_id")) {
                throw e;
            }
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "client_sale_in_progress",
                    "Sale in progress",
                    "This sale is being recorded by another request; try again shortly.");
        }
        lines.forEach(l -> repo.insertLine(id, l));
        List<Movement> movements = lines.stream()
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
                .toList();
        if (offline) {
            // ADR-067 decision 7: the money was taken, so stock the branch did not have is written and flagged.
            for (StockLedger.Shortfall f : stock.recordAllowingShortfall(saleDate, movements)) {
                NewLine l = lines.get(f.index());
                clashes.add(new PendingClash(l.lineNo(), l.productId(), "stock_short", shortWords(f, l.qty())));
            }
        } else {
            stock.record(saleDate, movements);
        }
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
        if (r.clientSaleId() != null) {
            after.put("client_sale_id", r.clientSaleId());
            // A refused offline sale reported earlier (ADR-067 decision 8) is closed by the sale it became.
            repo.closeFailure(r.clientSaleId(), id);
        }
        if (offline) {
            after.put("recorded_offline", true);
            after.put("occurred_at", occurredAt.toString());
            if (!clashes.isEmpty()) {
                after.put(
                        "clashes",
                        clashes.stream().map(PendingClash::kind).distinct().toList());
            }
        }
        audit.record(AuditLog.Entry.created("retail.sale.created", SALE, id, branch, after));
        if (offline && !clashes.isEmpty()) {
            clashes.forEach(c -> repo.insertClash(id, branch, c.lineNo(), c.productId(), c.kind(), c.words()));
            // The administrators and the branch's managers with a device are told, once per sale (ADR-050, ADR-067).
            events.publishEvent(new PushNotice(
                    "retail.sale_clash",
                    id.toString(),
                    new PushNotice.Holders(OfflineSalesService.RESOLVE, branch, null),
                    Map.of("branch_id", branch.toString(), "path", "/staff/retail/sales")));
        }
        return visible(repo.find(id, false).orElseThrow().sale());
    }

    /**
     * When the sale happened (ADR-067 decision 6): null when the request does not say; up to {@link #CLOCK_SKEW} ahead is now;
     * further ahead an online request is refused and a sale recorded offline is dated now with a clock_ahead clash. A sale
     * recorded offline that says it happened before prices_as_of (the server's time when the phone last saved its prices)
     * had a phone clock behind: it is dated now with a clock_behind clash, never refused for a closed period because of it.
     */
    private Instant occurredAt(SaleRequest r, Instant pricesAsOf, List<PendingClash> clashes) {
        List<FieldProblem> problems = new ArrayList<>();
        if (r.offline() && r.clientSaleId() == null) {
            problems.add(
                    new FieldProblem("client_sale_id", "required", "A sale recorded offline carries its client id."));
        }
        if (r.offline() && r.occurredAt() == null) {
            problems.add(new FieldProblem("occurred_at", "required", "A sale recorded offline says when it happened."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        if (r.occurredAt() == null) {
            return null;
        }
        Instant now = clock.now();
        // Within CLOCK_BEHIND_SKEW of prices_as_of is the ordinary gap between two clocks, not a phone clock behind.
        if (clashes != null && pricesAsOf != null && r.occurredAt().isBefore(pricesAsOf.minus(CLOCK_BEHIND_SKEW))) {
            clashes.add(new PendingClash(
                    null,
                    null,
                    "clock_behind",
                    "The phone's clock was behind: the sale said it happened before the phone last saved its prices"
                            + " from the server, so it is dated when it reached the server."));
            return now;
        }
        if (!r.occurredAt().isAfter(now.plus(CLOCK_SKEW))) {
            return r.occurredAt().isAfter(now) ? now : r.occurredAt();
        }
        if (clashes == null) {
            throw ApiException.rule("occurred_at_in_future", "The time of the sale is in the future. Check the clock.");
        }
        long minutes = Duration.between(now, r.occurredAt()).toMinutes();
        clashes.add(new PendingClash(
                null,
                null,
                "clock_ahead",
                "The phone's clock was " + minutes
                        + " minutes ahead, so the sale is dated when it reached the server."));
        return now;
    }

    /** The words of a stock clash: what the branch had, what the sale took, and what it has now. Never a cost. */
    private String shortWords(StockLedger.Shortfall f, BigDecimal qty) {
        String item =
                catalogue.find(f.productId()).map(ProductSnapshot::description).orElse("An item");
        String kept = f.reserved().signum() > 0
                ? ", of which " + Quantities.format(f.reserved()) + " were kept for customer orders"
                : "";
        return item + " had " + Quantities.format(f.before()) + " in stock at this branch" + kept + " when this offline"
                + " sale reached the server; it took " + Quantities.format(qty) + ", so the stock is now "
                + Quantities.format(f.before().subtract(qty)) + ". Count it and correct it.";
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
        return priceLines(branch, saleDate, buyer, priceListId, requests, problems, null);
    }

    Priced priceLines(
            UUID branch,
            LocalDate saleDate,
            Customer buyer,
            UUID priceListId,
            List<SaleLineRequest> requests,
            List<FieldProblem> problems,
            List<Agreement> agreements) {
        return priceLines(branch, saleDate, buyer, priceListId, requests, problems, agreements, null, null);
    }

    /**
     * As above; {@code agreements} prices lines a customer order agreed on (issue #385): see {@link #record(SaleRequest, Long, List)}.
     * An entry may be null: that line is priced as a new one with every rule checked (a change of an order's items, issue #393,
     * where only the lines left as they were carry an agreement). With {@code clashes} not null the lines are those of a sale
     * recorded offline (ADR-067 decision 7): an item switched off, a price that changed and a price not above cost are added
     * there instead of refused, and the line keeps the price the device charged.
     */
    Priced priceLines(
            UUID branch,
            LocalDate saleDate,
            Customer buyer,
            UUID priceListId,
            List<SaleLineRequest> requests,
            List<FieldProblem> problems,
            List<Agreement> agreements,
            List<PendingClash> clashes,
            Instant pricesAsOf) {
        boolean offline = clashes != null;
        String offlineReason = offline ? offlineReason(pricesAsOf) : null;
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
            Agreement agreement = agreements == null ? null : agreements.get(i);
            boolean agreed = agreement != null;
            String field = "lines[" + i + "]";
            ProductSnapshot p = catalogue.find(l.productId()).orElse(null);
            if (p == null) {
                problems.add(new FieldProblem(field + ".product_id", "unknown_product", "No such product."));
                continue;
            }
            if (!p.active() && !agreed && offline) {
                clashes.add(new PendingClash(
                        i + 1,
                        p.id(),
                        "item_inactive",
                        p.description() + " was switched off while this sale was offline; it is recorded as sold."));
            } else if (!p.active() && !agreed) {
                problems.add(new FieldProblem(
                        field + ".product_id",
                        "product_inactive",
                        "This item is inactive, so it cannot be sold. Switch it on again on the Items screen first."));
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
            SaleUnit unit = saleUnit(field, p, l.unitId(), l.qty(), problems, agreed, clashes, i + 1);
            if (unit == null) {
                continue;
            }
            PackUnit pack = unit.pack();
            long defaultPrice = pack == null ? p.sellMinor() : Quantities.packPrice(p.sellMinor(), pack.factor());
            long price = l.unitPriceMinor() == null ? defaultPrice : l.unitPriceMinor();
            // ADR-067 decision 11: offline, the price rules of an online sale. A device price stands when it is the
            // catalogue or pack price in force when the phone saved its prices (or today's), or when the seller may
            // change prices and gave a reason; any other is recorded as charged with a price_typed clash.
            OfflinePrice offlinePrice = offline && l.unitPriceMinor() != null
                    ? offlinePrice(p, pack, l, defaultPrice, pricesAsOf, branch, principal)
                    : null;
            if (!priced && offlinePrice == OfflinePrice.TYPED) {
                clashes.add(typedClash(i + 1, p));
            } else if (!priced && offlinePrice == OfflinePrice.PHONE && l.unitPriceMinor() != defaultPrice) {
                clashes.add(priceClash(i + 1, p, "its price is different now"));
            }
            LinePricing linePricing = null;
            if (priced) {
                // ADR-033, issue #195: the server resolves the price; a different typed price is an override.
                Decision d = pricing.decide(
                        list, p.id(), pack == null ? p.unitId() : pack.unitId(), l.qty(), defaultPrice, saleDate);
                boolean devicePrice = offline && l.unitPriceMinor() != null;
                if (d.refused() && !devicePrice) {
                    throw ApiException.rule(
                            "no_price_in_list",
                            "The price list " + list.name() + " has no price for " + p.description() + " on line "
                                    + (i + 1) + ".");
                }
                price = d.priceMinor();
                String source = d.source();
                String reason = null;
                UUID overriddenBy = null;
                if (agreement != null && "override".equals(agreement.source()) && l.unitPriceMinor() != null) {
                    // A price changed when the customer order was taken (issue #385): an override once, for whoever
                    // changed it.
                    price = l.unitPriceMinor();
                    source = "override";
                    reason = blankToNull(l.priceOverrideReason());
                    overriddenBy = agreement.overriddenBy();
                } else if (agreed && l.unitPriceMinor() != null && l.unitPriceMinor() != d.priceMinor()) {
                    // The price agreed on the order, which today's list would price otherwise: kept, not a new change.
                    price = l.unitPriceMinor();
                    source = "order_agreed";
                    reason = blankToNull(l.priceOverrideReason());
                } else if (devicePrice && (d.refused() || l.unitPriceMinor() != d.priceMinor())) {
                    // ADR-067 decision 11: the price the customer paid on the phone stands, as an override that names
                    // the offline sale; it is never repriced. A price typed as online allows is an override with its
                    // reason and no clash; the saved price that the list prices otherwise is price_changed; any other
                    // typed price is price_typed.
                    if (offlinePrice == OfflinePrice.TYPED) {
                        clashes.add(typedClash(i + 1, p));
                    } else if (offlinePrice == OfflinePrice.PHONE || d.refused()) {
                        clashes.add(priceClash(
                                i + 1,
                                p,
                                d.refused()
                                        ? "the price list " + list.name() + " has no price for it now"
                                        : "its price is" + " different now"));
                    }
                    price = l.unitPriceMinor();
                    source = "override";
                    reason = offlinePrice == OfflinePrice.ALLOWED
                            ? clip(l.priceOverrideReason().trim())
                            : blankToNull(l.priceOverrideReason()) == null
                                    ? offlineReason
                                    : clip(l.priceOverrideReason().trim() + " (" + offlineReason + ")");
                } else if (l.unitPriceMinor() != null && l.unitPriceMinor() != d.priceMinor()) {
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
                linePricing =
                        new LinePricing(source, d.listId(), d.breakMinQty(), d.priceMinor(), reason, overriddenBy);
            }
            // ADR-079: the line keeps the branch's average cost per base unit at the moment of sale.
            long cost = stock.averageCost(branch, p.id());
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
                        cost,
                        total,
                        Quantities.value(baseQty, cost),
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
                    cost,
                    Quantities.value(l.qty(), price),
                    Quantities.value(l.qty(), cost),
                    null,
                    null,
                    null,
                    null,
                    linePricing));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        // ADR-020 decision 5: the message never carries the cost, which a sales caller cannot read. The prices of a
        // customer
        // order were held to the floor when it was taken; a cost that rose since does not block its delivery (issue
        // #385).
        if (!principal.may("retail.price.below_cost", branch)) {
            for (NewLine l : lines) {
                if (agreements != null && agreements.get(l.lineNo() - 1) != null) {
                    continue;
                }
                // A pack line is judged on its line total, never on the rounded price per base unit (spec 3.3).
                boolean below = l.saleUnitId() == null
                        ? l.unitPriceMinor() <= l.unitCostMinor()
                        : l.lineTotalMinor() <= l.lineCostMinor();
                if (below && offline) {
                    clashes.add(new PendingClash(
                            l.lineNo(),
                            l.productId(),
                            "below_cost",
                            "The price on line " + l.lineNo() + " is not above the item's cost; the offline sale is"
                                    + " recorded at the price the customer paid."));
                } else if (below) {
                    throw ApiException.rule(
                            "price_below_cost",
                            "The price on line " + l.lineNo() + " is too low: it must be above the product's cost.");
                }
            }
        }
        return new Priced(list, lines);
    }

    /**
     * How the device price of an offline line stands against the online price rules (ADR-067 decision 11): PHONE, the
     * catalogue or pack price in force at prices_as_of or today's; ALLOWED, another price typed by a holder of
     * retail.price.override with a reason; TYPED, any other.
     */
    enum OfflinePrice {
        PHONE,
        ALLOWED,
        TYPED
    }

    private OfflinePrice offlinePrice(
            ProductSnapshot p,
            PackUnit pack,
            SaleLineRequest l,
            long defaultPrice,
            Instant pricesAsOf,
            UUID branch,
            Principal principal) {
        long device = l.unitPriceMinor();
        if (device == defaultPrice) {
            return OfflinePrice.PHONE;
        }
        long saved = catalogue.sellMinorAt(p.id(), pricesAsOf == null ? clock.now() : pricesAsOf);
        if (device == (pack == null ? saved : Quantities.packPrice(saved, pack.factor()))) {
            return OfflinePrice.PHONE;
        }
        String reason = blankToNull(l.priceOverrideReason());
        return principal.may(SalesPricing.OVERRIDE, branch)
                        && reason != null
                        && reason.length() >= SalesPricing.MIN_REASON
                ? OfflinePrice.ALLOWED
                : OfflinePrice.TYPED;
    }

    private static PendingClash typedClash(int lineNo, ProductSnapshot p) {
        return new PendingClash(
                lineNo,
                p.id(),
                "price_typed",
                "The price of " + p.description() + " on line " + lineNo
                        + " was typed on the phone while offline, and is not the price the phone had saved, by someone who"
                        + " may not change prices or without a reason. The sale keeps the price the customer paid; check"
                        + " it.");
    }

    /** The override reason of a line whose price the device charged (ADR-067). */
    private static String offlineReason(Instant pricesAsOf) {
        return pricesAsOf == null
                ? "Price on the phone during an offline sale"
                : "Price on the phone during an offline sale (prices as of " + pricesAsOf + ")";
    }

    private static PendingClash priceClash(int lineNo, ProductSnapshot p, String why) {
        return new PendingClash(
                lineNo,
                p.id(),
                "price_changed",
                "The price of " + p.description() + " on line " + lineNo
                        + " was taken from the phone while offline, and " + why
                        + ". The sale keeps the price the customer paid.");
    }

    private static String clip(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300);
    }

    /** What a sale line's unit comes to: the pack (null for the base unit) and the quantity in base units. */
    private record SaleUnit(PackUnit pack, BigDecimal baseQty) {}

    /**
     * ADR-033: a pack. Stock moves in base units; the entered unit, quantity and price are kept on the line. Returns null
     * (with a field problem added) when the unit cannot be used; a quantity that does not convert exactly is refused.
     */
    private SaleUnit saleUnit(
            String field, ProductSnapshot p, UUID unitId, BigDecimal qty, List<FieldProblem> problems) {
        return saleUnit(field, p, unitId, qty, problems, false, null, 0);
    }

    /**
     * As above; {@code agreed} keeps a pack switched off since a customer order was taken (issue #385). With {@code clashes}
     * not null (a sale recorded offline, ADR-067 decision 7) a pack switched off, or pack sizes turned off for the business,
     * since the phone saved its copy records the line in that pack with a pack_inactive clash instead of refusing it.
     */
    private SaleUnit saleUnit(
            String field,
            ProductSnapshot p,
            UUID unitId,
            BigDecimal qty,
            List<FieldProblem> problems,
            boolean agreed,
            List<PendingClash> clashes,
            int lineNo) {
        if (unitId == null || unitId.equals(p.unitId())) {
            return new SaleUnit(null, qty);
        }
        boolean unitsOff = !capabilities.enabled(RetailCapabilities.UNITS);
        if (unitsOff && clashes == null) {
            problems.add(new FieldProblem(
                    field + ".unit_id", "capability_not_enabled", "Pack sizes are not enabled for this business."));
            return null;
        }
        PackUnit pack = catalogue.packUnit(p.id(), unitId).orElse(null);
        if (pack == null || !pack.active() && !agreed && clashes == null) {
            problems.add(
                    new FieldProblem(field + ".unit_id", "unknown_pack_unit", "This item is not sold in that unit."));
            return null;
        }
        if (clashes != null && (unitsOff || !pack.active() && !agreed)) {
            clashes.add(new PendingClash(
                    lineNo,
                    p.id(),
                    "pack_inactive",
                    (unitsOff
                                    ? "Pack sizes were turned off for the business"
                                    : "The pack size " + pack.unit() + " of " + p.description() + " was switched off")
                            + " while this sale was offline; line " + lineNo + " is recorded as sold in " + pack.unit()
                            + "."));
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
        branches.requireSells(branch, "branch_id");
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
            if (!p.active()) {
                problems.add(new FieldProblem(
                        field + ".product_id",
                        "product_inactive",
                        "This item is inactive, so it cannot be sold. Switch it on again on the Items screen first."));
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
        // ADR-055: without retail.sale.all_sellers in every branch shown, a seller's list is their own sales only.
        UUID own = SalesSummaryService.debtsOnly(owing)
                ? null
                : SalesSummaryService.ownSellerOnly(principal, filter, sellerId);
        if (own == null
                && sellerId != null
                && !sellerId.equals(principal.userId())
                && !SalesSummaryService.profitVisible(principal, filter)) {
            // A person's takings are management information, as in the salesperson level of the summary.
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Listing a salesperson's sales is for people who may read profit in every branch shown.");
        }
        if (own != null) {
            sellerId = own;
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
        CreditPeriods periods = capabilities.onPaymentPeriods();
        return new SalePage(items.stream().map(x -> visible(x, periods)).toList(), next);
    }

    @Transactional(readOnly = true)
    Sale get(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, false)
                .map(Header::sale)
                .filter(s -> principal.may("retail.sale.read", s.branchId()))
                // ADR-055: someone else's sale is not found by a seller who sees only their own figures.
                .filter(s -> principal.may(SalesSummaryService.ALL_SELLERS, s.branchId())
                        || java.util.Objects.equals(principal.userId(), s.createdBy())
                        || SalesSummaryService.stillOwed(s))
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
        if (s.writeOff() != null) {
            throw writtenOff("The unpaid part of this sale was written off, so it cannot be voided.");
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
        SalesRepository.OrderFee order = repo.orderFee(id).orElse(null);
        if (order != null && order.feePaidLater()) {
            throw ApiException.rule(
                    "order_fee_paid",
                    "This sale came from order " + order.orderNo()
                            + ", whose delivery fee was paid on the account later. It"
                            + " cannot be voided here; ask your accountant to correct it.");
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
        // Issue #385, ADR-052: the delivery fee of the customer order this sale came from is reversed with it.
        SalesRepository.OrderFee fee = order;
        if (fee != null && fee.feeEntryId() != null) {
            books.reverse(fee.feeEntryId(), today, "Void " + s.saleNo(), "retail.order_fee_void:" + fee.orderId());
            repo.orderEvent(
                    fee.orderId(),
                    "sale_voided",
                    "Sale " + s.saleNo() + " voided; the delivery fee of " + fee.feeMinor() + " was reversed.",
                    principal.userId());
        }
        repo.markVoided(id, principal.userId(), reason);
        audit.record(new AuditLog.Entry(
                "retail.sale.voided",
                SALE,
                id,
                s.branchId(),
                Map.of("status", "completed"),
                Map.of("status", "voided", "reason", reason)));
        // The administrators with a device are told (ADR-050); the person who voided it is not. A push never fails the
        // void.
        events.publishEvent(new PushNotice(
                "retail.sale_voided",
                id.toString(),
                new PushNotice.Holders("core.settings.manage", null, principal.userId()),
                Map.of("branch_id", s.branchId().toString(), "path", "/staff/retail/sales")));
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
    PaymentResult recordPayment(UUID saleId, PaymentRequest r) {
        Principal principal = CurrentPrincipal.require();
        Sale s = repo.find(saleId, true)
                .map(Header::sale)
                .filter(x -> principal.may("retail.sale.create", x.branchId()))
                .orElseThrow(ApiException::notFound);
        branches.requireSells(s.branchId(), "branch_id");
        if (s.writeOff() != null) {
            throw writtenOff("The unpaid part of this sale was written off, so it takes no payment.");
        }
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
        // One read of the business clock: the day and the moment the payment is entered come from the same instant
        // (ADR-043).
        Instant now = clock.now();
        LocalDate today = now.atZone(tenant.profile().timezone()).toLocalDate();
        LocalDate paidOn = r.paidOn() == null ? today : r.paidOn();
        if (paidOn.isAfter(today) || paidOn.isBefore(s.saleDate())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("paid_on", "invalid", "A payment is dated between the sale and today.")));
        }
        // A sale counted when the buyer pays (its counting day, the later of its date and its entry day, is on or after
        // the start) is paid on the day the payment
        // counts on, so the
        // payment carries that day (ADR-043). A sale dated before the rule started is paid as it always was.
        if (!s.historical()
                && capabilities.onPaymentPeriods().counts(s.saleDate(), s.createdAt())
                && !paidOn.equals(today)) {
            throw ApiException.validation(
                    List.of(
                            new FieldProblem(
                                    "paid_on",
                                    "backdated_payment",
                                    "Under the 'when the buyer pays' rule a payment is dated today. If you took the money earlier, ask your administrator.")));
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
                now,
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
        // ADR-055 (the owner's decision of 21 October 2026): a debt is open to every seller of the branch, but who took
        // each
        // payment is shown only to the owner (profit read there) or to the person who took it.
        Principal principal = CurrentPrincipal.require();
        boolean everyone = principal.may("retail.profit.read", s.branchId());
        return new PaymentList(repo.payments(s.id()).stream()
                .map(p -> everyone || principal.userId().equals(p.createdBy())
                        ? p
                        : new Payment(
                                p.id(),
                                p.saleId(),
                                p.amountMinor(),
                                p.currency(),
                                p.method(),
                                p.paidOn(),
                                p.journalEntryId(),
                                p.createdAt(),
                                null))
                .toList());
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
        List<SalesRepository.OpenRow> rows = repo.openSaleRows(customerId, filter);
        long owed = rows.stream().mapToLong(r -> r.sale().balanceMinor()).reduce(0, Math::addExact);
        // Which sales are imported, and which are counted when the buyer pays (their payments are dated today), is said
        // only
        // while customer accounts are on (issue #196, ADR-043); off, the answer is as before.
        boolean accountsOn = accounts.on();
        CreditPeriods periods = capabilities.onPaymentPeriods();
        List<OpenSale> open = rows.stream()
                .map(r -> {
                    OpenSale s = r.sale();
                    boolean imported = Boolean.TRUE.equals(s.historical());
                    return new OpenSale(
                            s.saleId(),
                            s.saleNo(),
                            s.branchId(),
                            s.saleDate(),
                            s.dueDate(),
                            s.totalMinor(),
                            s.paidMinor(),
                            s.balanceMinor(),
                            accountsOn ? s.historical() : null,
                            accountsOn && !imported && periods.counts(s.saleDate(), r.createdAt())
                                    ? Boolean.TRUE
                                    : null);
                })
                .toList();
        List<SalesApi.FeeOwed> fees = repo.unpaidOrderFees(customerId, filter);
        long feesOwed = fees.stream().mapToLong(SalesApi.FeeOwed::feeMinor).reduce(0, Math::addExact);
        return new CustomerBalance(
                customerId,
                tenant.profile().currency(),
                owed,
                open,
                fees.isEmpty() ? null : feesOwed,
                fees.isEmpty() ? null : fees);
    }

    /**
     * Cost and profit need {@code retail.profit.read} in the sale's branch (ADR-017; review F5). While the
     * {@code price_lists} capability is off a sale says nothing about price lists, whatever it was made with: the list
     * and the way each line was priced are left out (issue #195), so the capability off reads exactly as before.
     */
    Sale visible(Sale s) {
        return visible(s, capabilities.onPaymentPeriods());
    }

    /** As above, with the rule's start read once by a caller that shows a list. */
    Sale visible(Sale s, CreditPeriods periods) {
        Principal principal = CurrentPrincipal.require();
        boolean hideCost = !principal.may("retail.profit.read", s.branchId());
        // Who made or voided a sale is shown to those who may read profit there, and to the person themselves.
        UUID createdBy =
                hideCost && !java.util.Objects.equals(principal.userId(), s.createdBy()) ? null : s.createdBy();
        UUID voidedBy = hideCost && !java.util.Objects.equals(principal.userId(), s.voidedBy()) ? null : s.voidedBy();
        boolean hidePricing = !pricing.on();
        // A credit sale whose counting day (the later of its date and its entry day) is on or after the first day of
        // the rule "when the buyer pays" is paid on the day the payment
        // counts on (ADR-043).
        boolean counted =
                s.paymentMethod().equals("credit") && !s.historical() && periods.counts(s.saleDate(), s.createdAt());
        if (!hideCost && !hidePricing && counted == s.countedWhenPaid()) {
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
                s.version(),
                s.writeOff(),
                counted,
                s.deliveryFeeOwedMinor(),
                s.device());
    }

    /** 409 {@code sale_written_off}: what a sale whose unpaid part was written off refuses (ADR-043). */
    static ApiException writtenOff(String words) {
        return new ApiException(HttpStatus.CONFLICT, "sale_written_off", "Sale written off", words);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
