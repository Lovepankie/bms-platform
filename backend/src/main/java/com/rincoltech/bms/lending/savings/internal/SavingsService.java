package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Account;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.AccountPage;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.ActionOutcome;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.BalanceRow;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.BalancesReport;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.CloseRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.CreateProductRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.DepositResult;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.MoneyRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.MovementRow;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.MovementsReport;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.OpenAccountRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Product;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.ProductList;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.ReasonRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Statement;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.StatementLine;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Terms;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Total;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Transaction;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.TransactionPage;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.UpdateProductRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.WithdrawalRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsRepository.AccountRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff routes of savings (chapter 7 section 7.11.15): products, accounts, movements,
 * statement and reports. Branch scope by the route's permission (an account outside it is a 404),
 * the {@code Idempotency-Key} protocol on every money route, and the maker-checker requests of
 * chapter 8 section 8.4. The money events themselves are {@link SavingsServicer}'s.
 */
@Service
class SavingsService {

    static final String BASE = "/api/v1/lending/savings-accounts/";
    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;

    private final SavingsRepository repo;
    private final SavingsServicer servicer;
    private final Approvals approvals;
    private final Idempotency idempotency;
    private final MemberLookup members;
    private final CurrentTenant currentTenant;
    private final AuditLog audit;

    SavingsService(
            SavingsRepository repo,
            SavingsServicer servicer,
            Approvals approvals,
            Idempotency idempotency,
            MemberLookup members,
            CurrentTenant currentTenant,
            AuditLog audit) {
        this.repo = repo;
        this.servicer = servicer;
        this.approvals = approvals;
        this.idempotency = idempotency;
        this.members = members;
        this.currentTenant = currentTenant;
        this.audit = audit;
    }

    // ---- Products (FR-SAV-01) ---------------------------------------------------------------

    @Transactional(readOnly = true)
    ProductList products() {
        return new ProductList(repo.products());
    }

    @Transactional(readOnly = true)
    Product product(UUID id) {
        return repo.product(id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    Product createProduct(CreateProductRequest r) {
        checkTerms(r.terms());
        String currency =
                r.currency() != null ? r.currency() : currentTenant.profile().currency();
        UUID id = UUID.randomUUID();
        try {
            repo.insertProduct(
                    id,
                    r.code(),
                    currency,
                    r.terms(),
                    CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "duplicate_code",
                    "Duplicate code",
                    "A savings product with this code exists.");
        }
        Product created = repo.product(id).orElseThrow();
        audit.record(AuditLog.Entry.created(
                "lending.savings_product.created", "lending.savings_product", id, null, Map.of("code", r.code())));
        return created;
    }

    /** The interest terms of a product in use are refused with {@code product_in_use}. */
    @Transactional
    Product updateProduct(UUID id, String ifMatch, UpdateProductRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Product p = repo.lockProduct(id).orElseThrow(ApiException::notFound);
        if (p.version() != expected) {
            throw Versions.conflict(p.version());
        }
        Terms t = r.terms();
        checkTerms(t);
        boolean interestChanged = p.interestRateBp() != t.interestRateBp()
                || !p.interestCalc().equals(t.interestCalc())
                || !p.interestPosting().equals(t.interestPosting())
                || p.minBalanceForInterestMinor() != zero(t.minBalanceForInterestMinor());
        if (interestChanged && p.accounts() > 0) {
            throw ApiException.rule(
                    "product_in_use",
                    "The interest terms of a product with accounts do not change; create a new product instead.");
        }
        repo.updateProduct(id, t, r.status());
        Product updated = repo.product(id).orElseThrow();
        audit.record(new AuditLog.Entry(
                "lending.savings_product.updated",
                "lending.savings_product",
                id,
                null,
                Map.of("version", p.version(), "status", p.status()),
                Map.of("version", updated.version(), "status", updated.status())));
        return updated;
    }

    private static void checkTerms(Terms t) {
        if (t.interestCalc().equals("none") != (t.interestRateBp() == 0)) {
            throw ApiException.validation(List.of(new ApiException.FieldProblem(
                    "terms.interest_rate_bp",
                    "invalid",
                    "The rate is 0 exactly when the interest calculation is none.")));
        }
    }

    // ---- Accounts (FR-SAV-02) ---------------------------------------------------------------

    @Transactional
    Account open(OpenAccountRequest r) {
        Principal principal = CurrentPrincipal.require();
        MemberSummary member = members.lock(r.memberId())
                .filter(m -> principal.may("lending.savings.open", m.branchId()))
                .orElseThrow(ApiException::notFound);
        UUID branch = r.branchId() != null ? r.branchId() : member.branchId();
        // FR-BR-05: another branch only for a user with scope over both.
        if (!principal.may("lending.savings.open", branch)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Opening an account at this branch needs lending.savings.open there.");
        }
        UUID id = servicer.open(member, r.productId(), branch, servicer.today(), principal.userId());
        return view(repo.account(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    AccountPage accounts(
            List<UUID> branchIds,
            UUID memberId,
            List<String> statuses,
            UUID productId,
            String q,
            Integer limit,
            String cursor) {
        Principal principal = CurrentPrincipal.require();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        String after = Cursor.decode(cursor).orElse(null);
        List<AccountRow> rows = repo.page(
                principal.branchFilter("lending.savings.read", branchIds),
                memberId,
                statuses == null ? List.of() : statuses,
                productId,
                q == null || q.isBlank() ? null : q.trim(),
                after,
                size + 1);
        boolean more = rows.size() > size;
        List<AccountRow> page = more ? rows.subList(0, size) : rows;
        List<Account> items = page.stream().map(this::view).toList();
        return new AccountPage(items, more ? Cursor.encode(page.getLast().accountNo()) : null);
    }

    @Transactional(readOnly = true)
    Account account(UUID id) {
        return view(inScope(id));
    }

    @Transactional(readOnly = true)
    TransactionPage transactions(UUID id, Integer limit, String cursor) {
        inScope(id);
        int size = limit == null ? 50 : Math.clamp(limit, 1, 200);
        Integer before = Cursor.decode(cursor)
                .map(s -> {
                    try {
                        return Integer.valueOf(s);
                    } catch (NumberFormatException e) {
                        throw new ApiException(
                                HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Bad cursor.");
                    }
                })
                .orElse(null);
        List<Transaction> rows = repo.transactions(id, before, size + 1);
        boolean more = rows.size() > size;
        List<Transaction> items = more ? rows.subList(0, size) : rows;
        return new TransactionPage(
                List.copyOf(items),
                more ? Cursor.encode(String.valueOf(items.getLast().seq())) : null);
    }

    /** FR-DOC: the account statement for a range of value dates; the last 3 months to today by default. */
    @Transactional(readOnly = true)
    Statement statement(UUID id, LocalDate from, LocalDate to) {
        AccountRow a = inScope(id);
        LocalDate end = to != null ? to : servicer.today();
        LocalDate start = from != null ? from : end.minusMonths(3).plusDays(1);
        if (start.isAfter(end)) {
            throw ApiException.rule("invalid_range", "The start date is after the end date.");
        }
        long opening = repo.balanceBefore(id, start);
        List<StatementLine> lines = repo.statementLines(id, start, end, opening);
        long credits = lines.stream().mapToLong(StatementLine::creditMinor).sum();
        long debits = lines.stream().mapToLong(StatementLine::debitMinor).sum();
        return new Statement(
                a.id(),
                a.accountNo(),
                a.memberNo(),
                a.memberName(),
                a.productName(),
                a.currency(),
                start,
                end,
                opening,
                credits,
                debits,
                opening + credits - debits,
                lines);
    }

    // ---- Movements (FR-SAV-03) --------------------------------------------------------------

    @Transactional
    Outcome<DepositResult> deposit(UUID id, String key, MoneyRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/deposits", r, DepositResult.class, () -> {
            AccountRow a = lock(id, "lending.savings.deposit");
            LocalDate date = r.valueDate() != null ? r.valueDate() : servicer.today();
            servicer.checkDeposit(a, r.amountMinor(), date, r.paymentMethodKey());
            UUID txn = servicer.deposit(
                    a,
                    r.amountMinor(),
                    date,
                    r.paymentMethodKey(),
                    blankToNull(r.externalReference()),
                    CurrentPrincipal.require().userId(),
                    "staff");
            return new DepositResult(
                    repo.transaction(txn).orElseThrow(), view(repo.account(id).orElseThrow()));
        });
    }

    /** FR-SAV-03: executes at once below the tenant's threshold (FR-APR-04), else waits for a checker. */
    @Transactional
    Outcome<ActionOutcome> withdraw(UUID id, String key, WithdrawalRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/withdrawals", r, ActionOutcome.class, () -> {
            AccountRow a = lock(id, "lending.savings.withdraw");
            servicer.checkWithdrawal(a, r.amountMinor(), servicer.today(), r.paymentMethodKey());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(WithdrawalAction.AMOUNT, r.amountMinor());
            payload.put(WithdrawalAction.METHOD, r.paymentMethodKey());
            payload.put(WithdrawalAction.REFERENCE, blankToNull(r.externalReference()));
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    WithdrawalAction.TYPE, a.branchId(), a.id(), a.version(), r.amountMinor(), a.currency(), payload));
            return outcome(id, o, a.txnCount(), "withdrawal");
        });
    }

    /** FR-SAV-07: the whole balance after interest to date; the threshold applies to the balance. */
    @Transactional
    Outcome<ActionOutcome> close(UUID id, String key, CloseRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/close", r, ActionOutcome.class, () -> {
            AccountRow a = lock(id, "lending.savings.withdraw");
            long amount = servicer.checkClosable(a, r.paymentMethodKey());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(WithdrawalAction.CLOSE, true);
            payload.put(WithdrawalAction.METHOD, r.paymentMethodKey());
            payload.put(WithdrawalAction.REFERENCE, blankToNull(r.externalReference()));
            payload.put(WithdrawalAction.REASON, blankToNull(r.reason()));
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    WithdrawalAction.TYPE, a.branchId(), a.id(), a.version(), amount, a.currency(), payload));
            return outcome(id, o, a.txnCount(), "withdrawal");
        });
    }

    /** Always waits for a checker (chapter 8 section 8.4, {@code savings_reversal}). */
    @Transactional
    Outcome<ActionOutcome> reverse(UUID id, UUID txnId, String key, ReasonRequest r) {
        return idempotency.once(
                key, "POST", BASE + id + "/transactions/" + txnId + "/reverse", r, ActionOutcome.class, () -> {
                    AccountRow a = lock(id, "lending.savings.withdraw");
                    Transaction t = repo.transaction(txnId)
                            .filter(x ->
                                    repo.accountOfTxn(txnId).map(id::equals).orElse(false))
                            .orElseThrow(ApiException::notFound);
                    servicer.checkReversible(a, t);
                    Approvals.Outcome o = approvals.request(new ActionRequest(
                            SavingsReversalAction.TYPE,
                            a.branchId(),
                            t.id(),
                            a.version(),
                            t.amountMinor(),
                            a.currency(),
                            Map.of(SavingsReversalAction.REASON, r.reason().trim())));
                    return outcome(id, o, a.txnCount(), "reversal");
                });
    }

    // ---- Status (FR-SAV-06) -----------------------------------------------------------------

    @Transactional
    Account freeze(UUID id, ReasonRequest r) {
        servicer.freeze(lock(id, "lending.savings.withdraw_approve"), r.reason().trim());
        return view(repo.account(id).orElseThrow());
    }

    @Transactional
    Account unfreeze(UUID id, ReasonRequest r) {
        servicer.unfreeze(
                lock(id, "lending.savings.withdraw_approve"), r.reason().trim());
        return view(repo.account(id).orElseThrow());
    }

    @Transactional
    Account reactivate(UUID id, ReasonRequest r) {
        servicer.reactivate(
                lock(id, "lending.savings.withdraw_approve"), r.reason().trim());
        return view(repo.account(id).orElseThrow());
    }

    // ---- Reports (chapter 14 section 14.5) ----------------------------------------------------

    @Transactional(readOnly = true)
    BalancesReport balances(LocalDate asAt, List<UUID> branchIds, UUID productId) {
        Principal principal = CurrentPrincipal.require();
        LocalDate on = asAt != null ? asAt : servicer.today();
        List<BalanceRow> rows =
                repo.balancesAsAt(on, principal.branchFilter("lending.reports.members", branchIds), productId);
        Map<String, Total> totals = new LinkedHashMap<>();
        long all = 0;
        for (BalanceRow row : rows) {
            totals.merge(
                    row.productCode() + "|" + row.branchId(),
                    new Total(row.branchId(), row.productCode(), 1, row.balanceMinor()),
                    (x, y) -> new Total(
                            x.branchId(), x.productCode(), x.accounts() + 1, x.balanceMinor() + y.balanceMinor()));
            all += row.balanceMinor();
        }
        return new BalancesReport(on, currentTenant.profile().currency(), rows, new ArrayList<>(totals.values()), all);
    }

    @Transactional(readOnly = true)
    MovementsReport movements(LocalDate from, LocalDate to, List<UUID> branchIds, UUID productId) {
        Principal principal = CurrentPrincipal.require();
        LocalDate end = to != null ? to : servicer.today();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw ApiException.rule("invalid_range", "The start date is after the end date.");
        }
        List<MovementRow> rows =
                repo.movements(start, end, principal.branchFilter("lending.reports.members", branchIds), productId);
        long[] t = new long[10];
        for (MovementRow r : rows) {
            long[] v = {
                r.openingMinor(),
                r.depositsMinor(),
                r.withdrawalsMinor(),
                r.interestMinor(),
                r.feesMinor(),
                r.reversalsInMinor(),
                r.reversalsOutMinor(),
                r.netMinor(),
                r.closingMinor(),
                0
            };
            for (int i = 0; i < v.length; i++) {
                t[i] = Math.addExact(t[i], v[i]);
            }
        }
        MovementRow total = new MovementRow(null, null, t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8]);
        return new MovementsReport(start, end, currentTenant.profile().currency(), rows, total);
    }

    // ---- Shared -----------------------------------------------------------------------------

    private ActionOutcome outcome(UUID id, Approvals.Outcome o, int seqBefore, String type) {
        Transaction txn = null;
        if (o.executed()) {
            txn = repo.transactions(id, null, 10).stream()
                    .filter(t -> t.seq() > seqBefore && t.txnType().equals(type))
                    .reduce((newer, older) -> older)
                    .orElse(null);
        }
        return new ActionOutcome(
                id, o.executed(), o.approvalId(), txn, view(repo.account(id).orElseThrow()));
    }

    Account view(AccountRow a) {
        return new Account(
                a.id(),
                a.version(),
                a.accountNo(),
                a.branchId(),
                a.memberId(),
                a.memberNo(),
                a.memberName(),
                a.productId(),
                a.productCode(),
                a.productName(),
                a.currency(),
                a.status(),
                a.statusReason(),
                a.balanceMinor(),
                a.holdMinor(),
                a.minBalanceMinor(),
                a.withdrawalFeeMinor(),
                a.status().equals("active") ? SavingsServicer.available(a) : 0,
                a.interestRateBp(),
                a.interestCalc(),
                a.interestPosting(),
                servicer.accrued(a),
                a.openedOn(),
                a.closedOn(),
                a.lastMemberTxnOn(),
                a.lastInterestPostedTo(),
                a.balancesThrough());
    }

    private AccountRow lock(UUID id, String permission) {
        Principal principal = CurrentPrincipal.require();
        return repo.lock(id)
                .filter(a -> principal.may(permission, a.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private AccountRow inScope(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return repo.account(id)
                .filter(a -> principal.may("lending.savings.read", a.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private static long zero(Long v) {
        return Objects.requireNonNullElse(v, 0L);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
