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
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.Account;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.AccountPayment;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.AccountPaymentList;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.AccountRequest;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.Allocation;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.AllocationRequest;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.BeforeSystemStart;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.HistoricalSale;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.PaymentRequest;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.ReverseRequest;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.Statement;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.StatementLine;
import com.rincoltech.bms.retail.sales.internal.AccountsRepository.AllocationRow;
import com.rincoltech.bms.retail.sales.internal.AccountsRepository.Line;
import com.rincoltech.bms.retail.sales.internal.AccountsRepository.OpenSale;
import com.rincoltech.bms.retail.sales.internal.AccountsRepository.Receipt;
import com.rincoltech.bms.retail.sales.internal.AccountsRepository.Terms;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Payment;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer accounts over the existing credit buyers and credit sales (capability {@code customer_accounts}; ADR-033,
 * issue #196; docs/specs/trade-capabilities.md section 5): the credit limit and the payment terms, the limit check a
 * credit sale makes, the statement, and payment on account. Everything here is skipped, and no route answers, while the
 * capability is off. Money is integer minor units; the business date is the tenant's own (Africa/Kampala by default).
 * Nothing posts, or reads, anything for imported credit sales (issue #96).
 */
@Service
class AccountsService {

    static final String CREDIT_OVERRIDE = "retail.credit.override";
    static final String RECEIPT = "retail.account_payment";
    static final String CUSTOMERS = "/api/v1/retail/customers";
    static final int MIN_REASON = 5;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int STATEMENT_DEFAULT_DAYS = 90;
    static final int STATEMENT_MAX_DAYS = 731;
    static final int STATEMENT_MAX_LINES = 2000;
    static final int MAX_ALLOCATIONS = 200;

    private final AccountsRepository repo;
    private final SalesRepository sales;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailBranchContext branches;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final RetailCapabilities capabilities;
    private final ReturnsRepository returns;

    AccountsService(
            AccountsRepository repo,
            SalesRepository sales,
            RetailBooks books,
            Idempotency idempotency,
            RetailBranchContext branches,
            TenantSequences sequences,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            RetailCapabilities capabilities,
            ReturnsRepository returns) {
        this.repo = repo;
        this.sales = sales;
        this.books = books;
        this.idempotency = idempotency;
        this.branches = branches;
        this.sequences = sequences;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.capabilities = capabilities;
        this.returns = returns;
    }

    /** True while the capability is on; off means a sale takes the path it took before accounts existed. */
    boolean on() {
        return capabilities.enabled(RetailCapabilities.CUSTOMER_ACCOUNTS);
    }

    // ---- What a credit sale asks -----------------------------------------------------------------

    /** What the sale needs from the buyer's account: the terms, read under the row lock that serialises the check. */
    record SaleAccount(Long creditLimitMinor, Integer termsDays) {
        /** The due date a credit sale takes when none was typed: the sale date plus the terms, or none. */
        LocalDate dueDate(LocalDate saleDate) {
            return termsDays == null ? null : saleDate.plusDays(termsDays);
        }
    }

    /**
     * Locks the buyer's row ({@code SELECT ... FOR UPDATE}) and returns the account. The sale calls this before it takes any
     * stock balance lock, so two shops selling to one buyer at once queue here and the second one sees the first one's sale
     * (spec 5.2). The caller has checked the buyer exists.
     */
    SaleAccount lockForSale(UUID customerId) {
        Terms t = repo.terms(customerId, true).orElseThrow(ApiException::notFound);
        return new SaleAccount(t.creditLimitMinor(), t.termsDays());
    }

    /** A sale taken above the limit by someone who may, with the reason: written to the audit trail with the sale. */
    record Override(
            long limitMinor, long exposureBeforeMinor, long exposureAfterMinor, long exceededByMinor, String reason) {
        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("credit_limit_minor", limitMinor);
            m.put("exposure_before_minor", exposureBeforeMinor);
            m.put("exposure_after_minor", exposureAfterMinor);
            m.put("exceeded_by_minor", exceededByMinor);
            m.put("reason", reason);
            return m;
        }
    }

    /**
     * The limit check (spec 5.2), under the buyer's row lock. Exposure is tenant-wide, imported credit sales included: what
     * the buyer owes in every branch plus this sale. Equal to the limit is allowed; above it is refused with 422
     * {@code credit_limit_exceeded} (naming only by how much) unless the user holds {@code retail.credit.override} at the
     * sale's branch, who must then give a reason of at least five characters. Returns the override to record, or null.
     */
    Override enforceLimit(UUID customerId, SaleAccount account, long saleTotal, UUID branch, String reason) {
        if (account.creditLimitMinor() == null) {
            return null;
        }
        long before = repo.exposure(customerId);
        long after = Math.addExact(before, saleTotal);
        long exceeded = after - account.creditLimitMinor();
        if (exceeded <= 0) {
            return null;
        }
        Principal principal = CurrentPrincipal.require();
        if (!principal.may(CREDIT_OVERRIDE, branch)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "credit_limit_exceeded",
                    "Credit limit exceeded",
                    "This sale takes the buyer above the credit limit by " + exceeded + ".",
                    List.of(),
                    Map.of("exceeded_by_minor", exceeded));
        }
        String why = SalesService.blankToNull(reason);
        if (why == null || why.length() < MIN_REASON) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "override_reason_required",
                    "Reason required",
                    "This sale takes the buyer above the credit limit by " + exceeded + ". Give a reason of at least "
                            + MIN_REASON + " characters to sell anyway.",
                    List.of(),
                    Map.of("exceeded_by_minor", exceeded));
        }
        return new Override(account.creditLimitMinor(), before, after, exceeded, why);
    }

    // ---- The account -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    Account account(UUID id) {
        capabilities.require(RetailCapabilities.CUSTOMER_ACCOUNTS);
        Terms t = repo.terms(id, false).orElseThrow(ApiException::notFound);
        return view(id, t);
    }

    private Account view(UUID id, Terms t) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.sale.read", null);
        long balance = sales.openSales(id, filter).stream()
                .mapToLong(SalesApi.OpenSale::balanceMinor)
                .reduce(0, Math::addExact);
        // The exposure spans every branch, so it is shown only to a caller whose scope does: nothing of another
        // branch's sales leaks through it.
        Long exposure = filter == null ? repo.exposure(id) : null;
        Long limit = t.creditLimitMinor();
        return new Account(
                id,
                t.name(),
                tenant.profile().currency(),
                limit,
                t.termsDays(),
                balance,
                exposure,
                exposure == null || limit == null ? null : Math.max(0, limit - exposure),
                exposure == null || limit == null ? null : exposure > limit,
                t.version());
    }

    /** The limit and the terms, replaced under If-Match; {@code retail.credit.manage} only (administrators). */
    @Transactional
    Account updateAccount(UUID id, String ifMatch, AccountRequest r) {
        capabilities.require(RetailCapabilities.CUSTOMER_ACCOUNTS);
        requireBothDecided(r);
        int expected = Versions.fromIfMatch(ifMatch);
        Terms before = repo.terms(id, true).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        if (Objects.equals(before.creditLimitMinor(), r.creditLimitMinor())
                && Objects.equals(before.termsDays(), r.termsDays())) {
            return view(id, before);
        }
        if (repo.updateAccount(id, r.creditLimitMinor(), r.termsDays(), expected) == 0) {
            throw repo.terms(id, false).map(c -> Versions.conflict(c.version())).orElseGet(ApiException::notFound);
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        if (!Objects.equals(before.creditLimitMinor(), r.creditLimitMinor())) {
            was.put("credit_limit_minor", before.creditLimitMinor());
            now.put("credit_limit_minor", r.creditLimitMinor());
        }
        if (!Objects.equals(before.termsDays(), r.termsDays())) {
            was.put("terms_days", before.termsDays());
            now.put("terms_days", r.termsDays());
        }
        audit.record(new AuditLog.Entry("retail.customer.account_changed", "retail.customer", id, null, was, now));
        return view(id, repo.terms(id, false).orElseThrow());
    }

    /**
     * The limit and the terms are each given or cleared on purpose, never left out: a body that names neither changes
     * nothing and is refused, so an empty request cannot wipe a buyer's limit.
     */
    private static void requireBothDecided(AccountRequest r) {
        List<FieldProblem> problems = new ArrayList<>();
        decided(
                problems,
                "credit_limit_minor",
                "clear_credit_limit",
                r.creditLimitMinor() != null,
                r.clearCreditLimit());
        decided(problems, "terms_days", "clear_terms_days", r.termsDays() != null, r.clearTermsDays());
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
    }

    private static void decided(
            List<FieldProblem> problems, String field, String clearField, boolean given, Boolean clear) {
        boolean cleared = Boolean.TRUE.equals(clear);
        if (!given && !cleared) {
            problems.add(new FieldProblem(
                    field, "required", "Give " + field + ", or set " + clearField + " to true to remove it."));
        } else if (given && cleared) {
            problems.add(new FieldProblem(field, "conflict", "Give " + field + " or " + clearField + ", not both."));
        }
    }

    // ---- The statement ---------------------------------------------------------------------------

    /**
     * Opening balance, the movements with a running balance, closing balance (spec 5.2). Built from sales, sale payments and
     * the receipts of payments on account in the caller's branch scope, as the customer balance is. Imported credit sales
     * (historical) are listed apart as "before system start"; the closing balance is what the ledger holds, and
     * {@code balance_minor} adds the imported sales, so it equals the customer balance.
     */
    @Transactional(readOnly = true)
    Statement statement(UUID id, LocalDate fromParam, LocalDate toParam) {
        capabilities.require(RetailCapabilities.CUSTOMER_ACCOUNTS);
        Terms t = repo.terms(id, false).orElseThrow(ApiException::notFound);
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate to = toParam == null ? today : toParam;
        LocalDate from = fromParam == null ? to.minusDays(STATEMENT_DEFAULT_DAYS - 1) : fromParam;
        if (from.isAfter(to) || from.plusDays(STATEMENT_MAX_DAYS).isBefore(to)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "from",
                    "invalid",
                    "from must be on or before to, at most " + STATEMENT_MAX_DAYS + " days apart.")));
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter("retail.sale.read", null);
        String zone = tenant.profile().timezone().getId();
        long opening = repo.opening(id, filter, zone, from);
        List<Line> raw = repo.lines(id, filter, zone, from, to, STATEMENT_MAX_LINES + 1);
        if (raw.size() > STATEMENT_MAX_LINES) {
            throw ApiException.rule(
                    "statement_too_long", "Choose a shorter range: more than " + STATEMENT_MAX_LINES + " lines.");
        }
        List<StatementLine> lines = new ArrayList<>();
        long running = opening;
        for (Line l : raw) {
            running = Math.addExact(running, l.debit() - l.credit());
            lines.add(new StatementLine(
                    l.date(),
                    l.kind(),
                    l.reference(),
                    l.saleId(),
                    l.receiptId(),
                    l.method(),
                    l.debit(),
                    l.credit(),
                    running));
        }
        List<AccountsRepository.Historical> imported = repo.historical(id, filter, to);
        BeforeSystemStart before = imported.isEmpty()
                ? null
                : new BeforeSystemStart(
                        imported.stream()
                                .map(h -> new HistoricalSale(
                                        h.saleId(), h.saleNo(), h.branchId(), h.saleDate(), h.totalMinor()))
                                .toList(),
                        imported.stream()
                                .mapToLong(AccountsRepository.Historical::totalMinor)
                                .reduce(0, Math::addExact));
        return new Statement(
                id,
                t.name(),
                tenant.profile().currency(),
                from,
                to,
                opening,
                lines,
                running,
                before,
                Math.addExact(running, before == null ? 0 : before.totalMinor()));
    }

    // ---- Payment on account ----------------------------------------------------------------------

    @Transactional
    Outcome<AccountPayment> pay(String idempotencyKey, UUID customerId, PaymentRequest r) {
        return idempotency.once(
                idempotencyKey,
                "POST",
                CUSTOMERS + "/" + customerId + "/payments",
                r,
                AccountPayment.class,
                () -> recordPayment(customerId, r));
    }

    /**
     * Money from a buyer that is not tied to one sale (spec 5.2). Allocated oldest first, or exactly as the cashier chose,
     * to the buyer's open credit sales in the caller's {@code retail.sale.create} scope; each allocation is an ordinary sale
     * payment. Imported (historical) sales are skipped, so a payment never credits trade debtors for an amount that was never
     * debited; what cannot be allocated is refused (422 {@code payment_exceeds_allocatable}), never held as a deposit. One
     * ledger entry posts in the receiving branch.
     */
    private AccountPayment recordPayment(UUID customerId, PaymentRequest r) {
        capabilities.require(RetailCapabilities.CUSTOMER_ACCOUNTS);
        Principal principal = CurrentPrincipal.require();
        UUID branch = branches.resolve("retail.sale.create", r.branchId());
        // The buyer's row is locked first: payments, and credit sales that check the limit, queue behind each other.
        repo.terms(customerId, true).orElseThrow(ApiException::notFound);
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate paidOn = r.paidOn() == null ? today : r.paidOn();
        if (paidOn.isAfter(today)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("paid_on", "invalid", "A payment cannot be dated in the future.")));
        }
        long amount = r.amountMinor();
        boolean manual = r.allocations() != null && !r.allocations().isEmpty();
        Map<UUID, Long> split = manual
                ? manualSplit(customerId, r.allocations(), amount, paidOn, principal)
                : oldestFirst(customerId, amount, paidOn, principal);
        // The sales were locked above, in the same order for every payment; read them back with their numbers.
        Map<UUID, OpenSale> byId = repo.lockSales(customerId, new ArrayList<>(split.keySet())).stream()
                .collect(Collectors.toMap(OpenSale::id, s -> s, (a, b) -> a, LinkedHashMap::new));
        UUID id = UUID.randomUUID();
        String receiptNo = "RC%08d".formatted(sequences.next("retail_account_payment_no"));
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(SalesService.accountFor(r.method()), amount));
        for (Map.Entry<UUID, Long> e : split.entrySet()) {
            legs.add(Leg.credit("trade_debtors", e.getValue()).withSubledger(SalesService.SALE, e.getKey()));
        }
        PostedEntry entry = books.post(new Posting(
                        branch,
                        paidOn,
                        receiptNo,
                        "Payment on account " + receiptNo,
                        RECEIPT,
                        id,
                        RECEIPT + ":" + id,
                        legs))
                .orElseThrow();
        String currency = tenant.profile().currency();
        Instant now = clock.now();
        repo.insertReceipt(new Receipt(
                id,
                receiptNo,
                customerId,
                branch,
                amount,
                currency,
                r.method(),
                paidOn,
                manual ? "manual" : "oldest_first",
                entry.entryId(),
                "posted",
                now,
                principal.userId(),
                null,
                null,
                null));
        List<Allocation> done = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : split.entrySet()) {
            OpenSale s = byId.get(e.getKey());
            UUID paymentId = UUID.randomUUID();
            sales.insertPayment(new Payment(
                    paymentId,
                    s.id(),
                    e.getValue(),
                    s.currency(),
                    r.method(),
                    paidOn,
                    entry.entryId(),
                    now,
                    principal.userId()));
            sales.addPaid(s.id(), e.getValue());
            repo.insertAllocation(UUID.randomUUID(), id, s.id(), paymentId, e.getValue());
            done.add(new Allocation(s.id(), s.saleNo(), s.branchId(), e.getValue(), paymentId));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receipt_no", receiptNo);
        after.put("customer_id", customerId);
        after.put("amount_minor", amount);
        after.put("method", r.method());
        after.put("allocation", manual ? "manual" : "oldest_first");
        after.put("sales", done.size());
        audit.record(AuditLog.Entry.created("retail.customer.account_payment_recorded", RECEIPT, id, branch, after));
        return new AccountPayment(
                id,
                receiptNo,
                customerId,
                branch,
                amount,
                currency,
                r.method(),
                paidOn,
                manual ? "manual" : "oldest_first",
                "posted",
                done,
                entry.entryId(),
                now,
                principal.userId(),
                null,
                null,
                null);
    }

    /** Oldest open sale first, within the caller's scope, until the amount is used; what is left is refused. */
    private Map<UUID, Long> oldestFirst(UUID customerId, long amount, LocalDate paidOn, Principal principal) {
        List<UUID> scope = principal.branchFilter("retail.sale.create", null);
        List<OpenSale> open = repo.allocatable(customerId, scope, paidOn);
        Map<UUID, Long> split = new LinkedHashMap<>();
        long left = amount;
        long allocatable = 0;
        for (OpenSale s : open) {
            allocatable = Math.addExact(allocatable, s.balanceMinor());
            if (left > 0) {
                long part = Math.min(left, s.balanceMinor());
                split.put(s.id(), part);
                left -= part;
            }
        }
        if (left > 0) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "payment_exceeds_allocatable",
                    "Payment too large",
                    "At most " + allocatable + " can be allocated to this buyer's open credit sales; the payment is "
                            + amount + ".",
                    List.of(),
                    Map.of("allocatable_minor", allocatable));
        }
        if (split.size() > MAX_ALLOCATIONS) {
            throw ApiException.rule(
                    "too_many_sales",
                    "This payment would settle more than " + MAX_ALLOCATIONS + " sales; record it in parts.");
        }
        return split;
    }

    /** The sales and amounts the cashier chose, checked one by one; they must add up to the payment. */
    private Map<UUID, Long> manualSplit(
            UUID customerId, List<AllocationRequest> chosen, long amount, LocalDate paidOn, Principal principal) {
        List<FieldProblem> problems = new ArrayList<>();
        Map<UUID, Long> split = new LinkedHashMap<>();
        for (int i = 0; i < chosen.size(); i++) {
            AllocationRequest a = chosen.get(i);
            if (split.putIfAbsent(a.saleId(), a.amountMinor()) != null) {
                problems.add(new FieldProblem(
                        "allocations[" + i + "].sale_id", "duplicate", "Each sale appears once; add the amounts."));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        Map<UUID, OpenSale> locked = repo.lockSales(customerId, new ArrayList<>(split.keySet())).stream()
                .collect(Collectors.toMap(OpenSale::id, s -> s));
        List<UUID> imported = repo.historicalAmong(new ArrayList<>(split.keySet()));
        long total = 0;
        for (int i = 0; i < chosen.size(); i++) {
            AllocationRequest a = chosen.get(i);
            String field = "allocations[" + i + "]";
            OpenSale s = locked.get(a.saleId());
            // A sale outside the caller's scope is as good as unknown: nothing of it is said.
            if (s == null || !principal.may("retail.sale.create", s.branchId())) {
                problems.add(new FieldProblem(
                        field + ".sale_id", "unknown_sale", "No such open credit sale of this buyer."));
                continue;
            }
            if (imported.contains(s.id())) {
                problems.add(new FieldProblem(
                        field + ".sale_id",
                        "sale_historical",
                        "An imported sale cannot be paid from an account payment; pay it on its own."));
            } else if (s.saleDate().isAfter(paidOn)) {
                problems.add(new FieldProblem(field + ".sale_id", "invalid", "The sale is dated after the payment."));
            } else if (a.amountMinor() > s.balanceMinor()) {
                problems.add(new FieldProblem(
                        field + ".amount_minor",
                        "payment_exceeds_balance",
                        "The sale's balance is " + s.balanceMinor() + "."));
            }
            total = Math.addExact(total, a.amountMinor());
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        if (total != amount) {
            throw ApiException.rule(
                    "allocations_must_equal_amount",
                    "The allocations add up to " + total + "; the payment is " + amount + ".");
        }
        return split;
    }

    @Transactional(readOnly = true)
    AccountPaymentList payments(UUID customerId, Integer limit, String cursor) {
        capabilities.require(RetailCapabilities.CUSTOMER_ACCOUNTS);
        repo.terms(customerId, false).orElseThrow(ApiException::notFound);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<UUID> scope = CurrentPrincipal.require().branchFilter("retail.sale.read", null);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<Receipt> rows = repo.receipts(
                customerId, scope, after == null ? null : after.at(), after == null ? null : after.id(), size + 1);
        boolean more = rows.size() > size;
        List<Receipt> page = more ? rows.subList(0, size) : rows;
        Map<UUID, List<Allocation>> parts = allocationsOf(page, scope);
        return new AccountPaymentList(
                page.stream()
                        .map(r -> view(r, parts.getOrDefault(r.id(), List.of())))
                        .toList(),
                more
                        ? Cursor.encode(page.getLast().createdAt() + "|"
                                + page.getLast().id())
                        : null);
    }

    private Map<UUID, List<Allocation>> allocationsOf(List<Receipt> receipts, List<UUID> scope) {
        Map<UUID, List<Allocation>> out = new HashMap<>();
        for (AllocationRow a :
                repo.allocations(receipts.stream().map(Receipt::id).toList(), scope)) {
            out.computeIfAbsent(a.receiptId(), k -> new ArrayList<>())
                    .add(new Allocation(a.saleId(), a.saleNo(), a.branchId(), a.amountMinor(), a.salePaymentId()));
        }
        return out;
    }

    private static AccountPayment view(Receipt r, List<Allocation> allocations) {
        return new AccountPayment(
                r.id(),
                r.receiptNo(),
                r.customerId(),
                r.branchId(),
                r.amountMinor(),
                r.currency(),
                r.method(),
                r.paidOn(),
                r.allocation(),
                r.status(),
                allocations,
                r.journalEntryId(),
                r.createdAt(),
                r.createdBy(),
                r.reversedAt(),
                r.reversedBy(),
                r.reverseReason());
    }

    // ---- Reversal --------------------------------------------------------------------------------

    @Transactional
    Outcome<AccountPayment> reverse(String idempotencyKey, UUID customerId, UUID receiptId, ReverseRequest r) {
        return idempotency.once(
                idempotencyKey,
                "POST",
                CUSTOMERS + "/" + customerId + "/payments/" + receiptId + "/reverse",
                r,
                AccountPayment.class,
                () -> reverseReceipt(customerId, receiptId, r));
    }

    /**
     * Undoes a payment on account as a whole (spec 5.2: reversible): the receipt's ledger entry is reversed in the receiving
     * branch, each allocated sale gets its paid amount back, and the receipt reads {@code reversed}. To allocate the money
     * differently, reverse it and record it again with the sales chosen. {@code retail.sale.void} at the receiving branch.
     */
    private AccountPayment reverseReceipt(UUID customerId, UUID receiptId, ReverseRequest r) {
        capabilities.require(RetailCapabilities.CUSTOMER_ACCOUNTS);
        Principal principal = CurrentPrincipal.require();
        String reason = r.reason().trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation(List.of(
                    new FieldProblem("reason", "invalid", "Give a reason of at least " + MIN_REASON + " characters.")));
        }
        repo.terms(customerId, true).orElseThrow(ApiException::notFound);
        Receipt receipt = repo.receipt(receiptId, customerId, true)
                .filter(x -> principal.may("retail.sale.void", x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (receipt.status().equals("reversed")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "receipt_reversed",
                    "Receipt reversed",
                    "This payment has been reversed already.");
        }
        List<AllocationRow> parts = repo.allAllocations(receiptId);
        // The reversal gives paid amounts back to the sales it paid, wherever they are: it needs the void permission in
        // each
        // of those branches as well as in the receiving one, as voiding the sale itself would.
        for (AllocationRow a : parts) {
            if (!principal.may("retail.sale.void", a.branchId())) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "permission_denied",
                        "Permission denied",
                        "This payment paid sales in a branch outside your scope, so you cannot reverse it.");
            }
        }
        Map<UUID, OpenSale> locked =
                repo
                        .lockSales(
                                customerId,
                                parts.stream().map(AllocationRow::saleId).toList())
                        .stream()
                        .collect(Collectors.toMap(OpenSale::id, s -> s));
        for (AllocationRow a : parts) {
            OpenSale s = locked.get(a.saleId());
            if (s == null || s.paidMinor() < a.amountMinor()) {
                throw ApiException.rule(
                        "payment_not_reversible",
                        "Sale " + a.saleNo() + " no longer holds this payment, so it cannot be reversed.");
            }
        }
        // Money refunded on a return of these sales was paid out of what this receipt brought in; giving the full
        // amount
        // back to the sale would leave the buyer owing money that was refunded. Void the return first (issue #201).
        // Read after
        // the sales are locked: a return takes the sale's lock to refund, so one that is committing now has committed
        // by here
        // and is seen, and none can start until this reversal ends.
        if (returns.hasRefundedMoney(parts.stream().map(AllocationRow::saleId).toList())) {
            throw ApiException.rule(
                    "payment_not_reversible",
                    "A sale this payment paid has money refunded on a return, so it cannot be reversed. Void that return first.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        PostedEntry reversal =
                books.reverse(receipt.journalEntryId(), today, receipt.receiptNo(), RECEIPT + "_void:" + receiptId);
        Instant now = clock.now();
        if (repo.markReversed(receiptId, principal.userId(), today, now, reason, reversal.entryId()) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "receipt_reversed",
                    "Receipt reversed",
                    "This payment has been reversed already.");
        }
        // After the receipt reads reversed: the sales guard lets paid_minor fall only inside this path (releasePaid
        // raises the
        // transaction-local setting to this receipt's id) and only by exactly what this receipt allocated to the sale.
        for (AllocationRow a : parts) {
            sales.releasePaid(a.saleId(), receiptId, a.amountMinor());
        }
        audit.record(new AuditLog.Entry(
                "retail.customer.account_payment_reversed",
                RECEIPT,
                receiptId,
                receipt.branchId(),
                Map.of("status", "posted"),
                Map.of("status", "reversed", "reason", reason, "amount_minor", receipt.amountMinor())));
        Receipt done = repo.receipt(receiptId, customerId, false).orElseThrow();
        return view(
                done,
                parts.stream()
                        .map(a -> new Allocation(
                                a.saleId(), a.saleNo(), a.branchId(), a.amountMinor(), a.salePaymentId()))
                        .toList());
    }
}
