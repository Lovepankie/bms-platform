package com.rincoltech.bms.retail.cashbook.internal;

import static com.rincoltech.bms.retail.cashbook.internal.CashbookSupport.mayProfit;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Savings;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsSuggestion;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.VoidRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.SavingsRow;
import com.rincoltech.bms.retail.reports.DailyProfits;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailIdempotency;
import com.rincoltech.bms.retail.stock.RetailIdempotency.Outcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily savings (FR-RET-18 to FR-RET-20; ADR-022 decisions 11 and 12). The suggestion is the day's
 * profit times the tenant rate; a savings amount is half the profit, so every figure that carries
 * it is absent without {@code retail.profit.read}, the audit row carries flags only, and a caller
 * without that permission cannot send an amount at all (no equality oracle).
 */
@Service
class SavingsService {

    static final String PATH = "/api/v1/retail/savings";
    static final String TABLE = "retail_daily_savings";

    private final CashbookRepository repo;
    private final CashbookSupport support;
    private final CashFigures figures;
    private final DailyProfits profits;
    private final RetailBooks books;
    private final RetailIdempotency idempotency;
    private final RetailBranchContext branchContext;
    private final AuditLog audit;

    SavingsService(
            CashbookRepository repo,
            CashbookSupport support,
            CashFigures figures,
            DailyProfits profits,
            RetailBooks books,
            RetailIdempotency idempotency,
            RetailBranchContext branchContext,
            AuditLog audit) {
        this.repo = repo;
        this.support = support;
        this.figures = figures;
        this.profits = profits;
        this.books = books;
        this.idempotency = idempotency;
        this.branchContext = branchContext;
        this.audit = audit;
    }

    /** The day's profit times the rate, rounded half up; zero when the profit is not positive. */
    long suggested(long profitMinor) {
        if (profitMinor <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(profitMinor)
                .multiply(BigDecimal.valueOf(support.savingsRateBp()))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    @Transactional(readOnly = true)
    SavingsSuggestion suggestion(UUID branchId, LocalDate date) {
        UUID branch = branchContext.resolve(CashbookSupport.SAVINGS_RECORD, branchId);
        LocalDate on = support.businessDate(date, "date");
        long profit = profits.profitMinor(branch, on);
        long suggested = suggested(profit);
        boolean gate = mayProfit(branch);
        return new SavingsSuggestion(
                branch,
                on,
                figures.day(branch, on).totalSold(),
                support.suggestionToken(branch, on, suggested),
                gate ? suggested : null,
                gate ? profit : null,
                repo.activeSavings(branch, on).orElse(null));
    }

    @Transactional
    Outcome<Savings> create(String key, SavingsRequest r) {
        return idempotency.once(key, "POST", PATH, r, Savings.class, () -> record(r));
    }

    private Savings record(SavingsRequest r) {
        UUID branch = branchContext.resolve(CashbookSupport.SAVINGS_RECORD, r.branchId());
        LocalDate date = support.businessDate(r.businessDate(), "business_date");
        boolean profit = mayProfit(branch);
        if (r.suggestionToken() == null || r.suggestionToken().isBlank()) {
            throw ApiException.rule(
                    "suggestion_token_required", "Read the suggestion first and send its token back with the record.");
        }
        if (!profit && (r.amountMinor() != null || r.overwriteReason() != null)) {
            // The same answer for every value: an accepted-or-refused amount would be an equality
            // oracle for half the day's profit.
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "amount_requires_profit_access",
                    "Amount not allowed",
                    "Only the default can be recorded without profit access.",
                    List.of(new FieldProblem(
                            "amount_minor",
                            "amount_requires_profit_access",
                            "Only the default can be recorded without profit access.")));
        }
        long suggested = suggested(profits.profitMinor(branch, date));
        if (!support.suggestionToken(branch, date, suggested).equals(r.suggestionToken())) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("suggestion_token", support.suggestionToken(branch, date, suggested));
            if (profit) {
                extra.put("suggested_minor", suggested);
            }
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "suggestion_changed",
                    "Suggestion changed",
                    "The day's figures changed since this form was shown. Check the suggestion and save again.",
                    List.of(),
                    extra);
        }
        boolean typed = r.amountMinor() != null;
        long amount = typed ? r.amountMinor() : suggested;
        boolean overwritten = typed && amount != suggested;
        String reason = null;
        Principal principal = CurrentPrincipal.require();
        if (overwritten) {
            if (!principal.may(CashbookSupport.SAVINGS_OVERWRITE, branch)) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "permission_denied",
                        "Permission denied",
                        "You do not have permission to change the suggested savings amount.");
            }
            reason = CashbookSupport.blankToNull(r.overwriteReason());
            if (reason == null || reason.length() < 5) {
                throw ApiException.rule(
                        "reason_required", "Give a reason of at least 5 characters for changing the amount.");
            }
        }
        if (repo.activeSavings(branch, date).isPresent()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "savings_exists",
                    "Savings exist",
                    "Savings are already recorded for this shop and day. Void that record to enter another.");
        }
        UUID id = UUID.randomUUID();
        UUID by = principal.userId();
        long sold = figures.day(branch, date).totalSold();
        UUID entry = books.post(CashPostings.savings(branch, date, id, amount))
                .map(PostedEntry::entryId)
                .orElse(null);
        repo.insertSavings(
                id,
                branch,
                date,
                amount,
                support.currency(),
                suggested,
                overwritten,
                reason,
                sold,
                support.now(),
                by,
                entry);
        // Flags only: an amount equal to the default would hand the profit to any reader of the audit log.
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("business_date", date.toString());
        after.put("overwritten", overwritten);
        after.put("default_applied", !typed);
        if (overwritten) {
            after.put("reason", reason);
        }
        audit.record(AuditLog.Entry.created("retail.savings.created", "retail.savings", id, branch, after));
        return view(repo.savings(id, false).orElseThrow());
    }

    @Transactional(readOnly = true)
    SavingsPage list(
            List<UUID> branchIds, LocalDate from, LocalDate to, boolean includeVoided, Integer limit, String cursor) {
        List<UUID> filter = CurrentPrincipal.require().branchFilter(CashbookSupport.READ, branchIds);
        int size = support.limit(limit);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<SavingsRow> rows = repo.savingsPage(
                filter,
                from,
                to,
                includeVoided,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<SavingsRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new SavingsPage(items.stream().map(SavingsService::view).toList(), next);
    }

    /** A savings row as the caller may see it: the amount, suggestion and overwrite flag need profit read. */
    static Savings view(SavingsRow r) {
        boolean profit = mayProfit(r.branchId());
        return new Savings(
                r.id(),
                r.branchId(),
                r.businessDate(),
                r.currency(),
                profit ? r.amountMinor() : null,
                profit ? r.suggestedMinor() : null,
                profit ? r.overwritten() : null,
                r.totalSoldMinor(),
                r.by(),
                r.byName(),
                r.createdAt(),
                r.voided().voided(),
                r.voided().at(),
                r.voided().reason(),
                r.historical());
    }

    @Transactional
    Outcome<Savings> voidSavings(String key, UUID id, VoidRequest r) {
        return idempotency.once(key, "POST", PATH + "/" + id + "/void", r, Savings.class, () -> doVoid(id, r));
    }

    private Savings doVoid(UUID id, VoidRequest r) {
        Principal principal = CurrentPrincipal.require();
        SavingsRow row = repo.savings(id, true)
                .filter(x -> principal.may(CashbookSupport.VOID, x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (row.voided().voided()) {
            throw CashbookSupport.alreadyVoided();
        }
        String reason = CashbookSupport.reason(r);
        LocalDate today = support.today();
        if (row.journalEntryId() != null) {
            books.reverse(
                    row.journalEntryId(),
                    today,
                    CashPostings.ref("Void savings", id),
                    CashPostings.voidKey(CashPostings.SAVINGS, id));
        }
        repo.markVoided(TABLE, id, principal.userId(), reason);
        audit.record(new AuditLog.Entry(
                "retail.savings.voided",
                "retail.savings",
                id,
                row.branchId(),
                Map.of("business_date", row.businessDate().toString()),
                Map.of("reason", reason)));
        return view(repo.savings(id, false).orElseThrow());
    }
}
