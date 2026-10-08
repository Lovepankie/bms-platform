package com.rincoltech.bms.retail.cashbook.internal;

import static com.rincoltech.bms.retail.cashbook.internal.CashbookSupport.mayProfit;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Principal.BranchScope;
import com.rincoltech.bms.retail.cashbook.internal.CashFigures.Day;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Banking;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingExpected;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.VoidRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Withdrawal;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.WithdrawalPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.WithdrawalRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.BankingRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.WithdrawalRow;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailIdempotency;
import com.rincoltech.bms.retail.stock.RetailIdempotency.Outcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash banked and cash withdrawn from the bank (FR-RET-21, FR-RET-22, FR-RET-25; ADR-022 decisions
 * 4 and 10). The expected amount is always computed here from the day's cash takings; a client may
 * display it but never supplies it. Banking is never refused for exceeding the cash on hand: it is
 * flagged. Everything net of savings and cash purchases is profit-derived and shown only with
 * {@code retail.profit.read}.
 */
@Service
class BankingService {

    static final String BANKING_PATH = "/api/v1/retail/bankings";
    static final String WITHDRAWAL_PATH = "/api/v1/retail/withdrawals";

    private final CashbookRepository repo;
    private final CashbookSupport support;
    private final CashFigures figures;
    private final RetailBooks books;
    private final RetailIdempotency idempotency;
    private final RetailBranchContext branchContext;
    private final Branches branches;
    private final CurrentTenant tenant;
    private final AuditLog audit;

    BankingService(
            CashbookRepository repo,
            CashbookSupport support,
            CashFigures figures,
            RetailBooks books,
            RetailIdempotency idempotency,
            RetailBranchContext branchContext,
            Branches branches,
            CurrentTenant tenant,
            AuditLog audit) {
        this.repo = repo;
        this.support = support;
        this.figures = figures;
        this.books = books;
        this.idempotency = idempotency;
        this.branchContext = branchContext;
        this.branches = branches;
        this.tenant = tenant;
        this.audit = audit;
    }

    /** The flag of a day: {@code not_banked}, {@code shortfall}, {@code surplus} or {@code ok} within the tolerance. */
    String flag(Day day) {
        return CashFlag.of(day.expectedToBank(), day.bankedNet(), support.toleranceMinor());
    }

    // ---------------------------------------------------------------------------------- expected

    @Transactional(readOnly = true)
    BankingExpected expected(UUID branchId, LocalDate date) {
        UUID branch = branchContext.resolve(CashbookSupport.BANKING_RECORD, branchId);
        LocalDate on = support.businessDate(date, "date");
        Day d = figures.day(branch, on);
        boolean profit = mayProfit(branch);
        return new BankingExpected(
                branch,
                on,
                d.cashTakings(),
                d.cashSaleVoids(),
                d.expenseVoids(),
                d.advanceVoids(),
                d.repaymentVoids(),
                profit ? d.cashPurchases() : null,
                profit ? d.savings() : null,
                profit ? d.savingsVoids() : null,
                d.expenses(),
                d.advancesOut(),
                d.repaymentsIn(),
                d.cashExpected(),
                profit ? d.expectedToBank() : null,
                repo.bankedSoFar(branch, on));
    }

    // ----------------------------------------------------------------------------------- banking

    @Transactional
    Outcome<Banking> createBanking(String key, BankingRequest r) {
        return idempotency.once(key, "POST", BANKING_PATH, r, Banking.class, () -> recordBanking(r));
    }

    private Banking recordBanking(BankingRequest r) {
        UUID branch = branchContext.resolve(CashbookSupport.BANKING_RECORD, r.branchId());
        LocalDate date = support.businessDate(r.businessDate(), "business_date");
        Instant bankedAt = r.bankedAt() == null ? support.now() : r.bankedAt();
        if (bankedAt.atZone(tenant.profile().timezone()).toLocalDate().isAfter(support.today())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("banked_at", "future_date", "A date cannot be in the future.")));
        }
        long amount = r.amountMinor();
        boolean profit = mayProfit(branch);
        // The snapshot: what the screen showed, computed here and never taken from the client.
        long expected = figures.day(branch, date).expectedToBank();
        List<String> warnings = new ArrayList<>();
        if (amount > figures.cashOnHand(branch, support.today())) {
            warnings.add("cash_below_banked");
        }
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        UUID entry = books.post(CashPostings.banking(branch, date, id, amount))
                .map(PostedEntry::entryId)
                .orElseThrow();
        repo.insertBanking(
                id,
                branch,
                date,
                amount,
                support.currency(),
                expected,
                bankedAt,
                CashbookSupport.blankToNull(r.reference()),
                by,
                entry);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("business_date", date.toString());
        after.put("amount_minor", amount);
        audit.record(AuditLog.Entry.created("retail.banking.created", "retail.banking", id, branch, after));
        Day d = figures.day(branch, date);
        BankingRow row = repo.banking(id, false).orElseThrow();
        return view(row, profit, d.bankedNet() - d.expectedToBank(), flag(d), warnings);
    }

    @Transactional(readOnly = true)
    BankingPage listBankings(
            List<UUID> branchIds, LocalDate from, LocalDate to, boolean includeVoided, Integer limit, String cursor) {
        List<UUID> filter = CurrentPrincipal.require().branchFilter(CashbookSupport.READ, branchIds);
        int size = support.limit(limit);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<BankingRow> rows = repo.bankingPage(
                filter,
                from,
                to,
                includeVoided,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<BankingRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new BankingPage(
                items.stream()
                        .map(b -> view(b, mayProfit(b.branchId()), null, null, null))
                        .toList(),
                next);
    }

    static Banking view(BankingRow b, boolean profit, Long difference, String flag, List<String> warnings) {
        return new Banking(
                b.id(),
                b.branchId(),
                b.businessDate(),
                b.currency(),
                b.amountMinor(),
                profit ? b.expectedMinor() : null,
                b.bankedAt(),
                b.reference(),
                profit ? difference : null,
                profit ? flag : null,
                profit ? warnings : null,
                b.by(),
                b.byName(),
                b.createdAt(),
                b.voided().voided(),
                b.voided().at(),
                b.voided().reason(),
                b.historical());
    }

    @Transactional
    Outcome<Banking> voidBanking(String key, UUID id, VoidRequest r) {
        return idempotency.once(key, "POST", BANKING_PATH + "/" + id + "/void", r, Banking.class, () -> {
            Principal principal = CurrentPrincipal.require();
            BankingRow row = repo.banking(id, true)
                    .filter(x -> principal.may(CashbookSupport.VOID, x.branchId()))
                    .orElseThrow(ApiException::notFound);
            if (row.voided().voided()) {
                throw CashbookSupport.alreadyVoided();
            }
            if (row.historical()) {
                throw CashbookSupport.historicalRecord();
            }
            String reason = CashbookSupport.reason(r);
            Instant at = support.now();
            if (row.journalEntryId() != null) {
                books.reverse(
                        row.journalEntryId(),
                        support.dayOf(at),
                        CashPostings.ref("Void banked", id),
                        CashPostings.voidKey(CashPostings.BANKING, id));
            }
            repo.markVoided("retail_cash_bankings", id, principal.userId(), reason, at);
            audit.record(new AuditLog.Entry(
                    "retail.banking.voided",
                    "retail.banking",
                    id,
                    row.branchId(),
                    Map.of("business_date", row.businessDate().toString()),
                    Map.of("reason", reason)));
            return view(repo.banking(id, false).orElseThrow(), mayProfit(row.branchId()), null, null, null);
        });
    }

    // -------------------------------------------------------------------------------- withdrawals

    /**
     * The shop that receives withdrawn cash (ADR-022 decision 4): the one named, else the caller's one
     * branch, else the head office when it is in scope, else the request must name one.
     */
    private UUID withdrawalBranch(UUID requested) {
        if (requested != null) {
            return branchContext.resolve(CashbookSupport.WITHDRAWAL_RECORD, requested);
        }
        Principal principal = CurrentPrincipal.require();
        BranchScope scope = principal.scopeOf(CashbookSupport.WITHDRAWAL_RECORD).orElse(null);
        if (scope != null && !scope.all() && scope.branchIds().size() == 1) {
            return branchContext.resolve(CashbookSupport.WITHDRAWAL_RECORD, null);
        }
        UUID head = branches.all().stream()
                .filter(b -> b.headOffice() && b.active())
                .map(Branches.Branch::id)
                .findFirst()
                .orElse(null);
        if (head != null && principal.may(CashbookSupport.WITHDRAWAL_RECORD, head)) {
            return head;
        }
        return branchContext.resolve(CashbookSupport.WITHDRAWAL_RECORD, null);
    }

    @Transactional
    Outcome<Withdrawal> createWithdrawal(String key, WithdrawalRequest r) {
        return idempotency.once(key, "POST", WITHDRAWAL_PATH, r, Withdrawal.class, () -> recordWithdrawal(r));
    }

    private Withdrawal recordWithdrawal(WithdrawalRequest r) {
        UUID branch = withdrawalBranch(r.branchId());
        LocalDate date = support.businessDate(r.businessDate(), "business_date");
        Instant at = r.withdrawnAt() == null ? support.now() : r.withdrawnAt();
        if (at.atZone(tenant.profile().timezone()).toLocalDate().isAfter(support.today())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("withdrawn_at", "future_date", "A date cannot be in the future.")));
        }
        long amount = r.amountMinor();
        List<String> warnings = new ArrayList<>();
        if (figures.bankBalance(support.today()) - amount < 0) {
            warnings.add("bank_balance_negative");
        }
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        UUID entry = books.post(CashPostings.withdrawal(branch, date, id, amount))
                .map(PostedEntry::entryId)
                .orElseThrow();
        repo.insertWithdrawal(
                id, branch, date, amount, support.currency(), at, CashbookSupport.blankToNull(r.purpose()), by, entry);
        audit.record(AuditLog.Entry.created(
                "retail.withdrawal.created",
                "retail.withdrawal",
                id,
                branch,
                Map.of("business_date", date.toString(), "amount_minor", amount)));
        return view(repo.withdrawal(id, false).orElseThrow(), warnings);
    }

    @Transactional(readOnly = true)
    WithdrawalPage listWithdrawals(
            List<UUID> branchIds, LocalDate from, LocalDate to, boolean includeVoided, Integer limit, String cursor) {
        List<UUID> filter = CurrentPrincipal.require().branchFilter(CashbookSupport.READ, branchIds);
        int size = support.limit(limit);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<WithdrawalRow> rows = repo.withdrawalPage(
                filter,
                from,
                to,
                includeVoided,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<WithdrawalRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new WithdrawalPage(items.stream().map(w -> view(w, List.of())).toList(), next);
    }

    static Withdrawal view(WithdrawalRow w, List<String> warnings) {
        return new Withdrawal(
                w.id(),
                w.branchId(),
                w.businessDate(),
                w.currency(),
                w.amountMinor(),
                w.withdrawnAt(),
                w.purpose(),
                warnings,
                w.by(),
                w.byName(),
                w.createdAt(),
                w.voided().voided(),
                w.voided().at(),
                w.voided().reason(),
                w.historical());
    }

    @Transactional
    Outcome<Withdrawal> voidWithdrawal(String key, UUID id, VoidRequest r) {
        return idempotency.once(key, "POST", WITHDRAWAL_PATH + "/" + id + "/void", r, Withdrawal.class, () -> {
            Principal principal = CurrentPrincipal.require();
            WithdrawalRow row = repo.withdrawal(id, true)
                    .filter(x -> principal.may(CashbookSupport.VOID, x.branchId()))
                    .orElseThrow(ApiException::notFound);
            if (row.voided().voided()) {
                throw CashbookSupport.alreadyVoided();
            }
            if (row.historical()) {
                throw CashbookSupport.historicalRecord();
            }
            String reason = CashbookSupport.reason(r);
            Instant at = support.now();
            if (row.journalEntryId() != null) {
                books.reverse(
                        row.journalEntryId(),
                        support.dayOf(at),
                        CashPostings.ref("Void withdrawal", id),
                        CashPostings.voidKey(CashPostings.WITHDRAWAL, id));
            }
            repo.markVoided("retail_cash_withdrawals", id, principal.userId(), reason, at);
            audit.record(new AuditLog.Entry(
                    "retail.withdrawal.voided",
                    "retail.withdrawal",
                    id,
                    row.branchId(),
                    Map.of("business_date", row.businessDate().toString()),
                    Map.of("reason", reason)));
            return view(repo.withdrawal(id, false).orElseThrow(), List.of());
        });
    }
}
