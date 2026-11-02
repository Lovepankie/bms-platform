package com.rincoltech.bms.retail.cashbook.internal;

import static com.rincoltech.bms.retail.cashbook.internal.CashbookSupport.mayProfit;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily savings (FR-RET-18 to FR-RET-20; ADR-022 decisions 11 and 12). The day's available amount is
 * its profit less its recorded expenses, never below zero, and no record may save more (ADR-080). The
 * suggestion is that amount times the tenant rate; a savings amount is half the profit, so every figure
 * that carries it is absent without {@code retail.profit.read}, the audit row carries flags only, and a
 * caller without that permission cannot send an amount at all (no equality oracle). ADR-115 (issue #768): when the Owner turns
 * on {@code salespeople_see_savings_amount}, such a caller sees the suggestion and the amounts saved since, and anyone recording
 * savings may change the amount within the cap; the profit, the expenses and the available amount stay hidden.
 */
@Service("retailSavingsService")
class SavingsService {

    static final String PATH = "/api/v1/retail/savings";
    static final String TABLE = "retail_daily_savings";

    private final CashbookRepository repo;
    private final CashbookSupport support;
    private final CashFigures figures;
    private final DailyProfits profits;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailBranchContext branchContext;
    private final AuditLog audit;

    SavingsService(
            CashbookRepository repo,
            CashbookSupport support,
            CashFigures figures,
            DailyProfits profits,
            RetailBooks books,
            Idempotency idempotency,
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
        return suggested(profitMinor, support.savingsRateBp());
    }

    static long suggested(long profitMinor, long rateBp) {
        if (profitMinor <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(profitMinor)
                .multiply(BigDecimal.valueOf(rateBp))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** The day's profit less its expenses, never below zero: the most the day may save (ADR-080). */
    static long available(long profitMinor, long expensesMinor) {
        return Math.max(0, profitMinor - expensesMinor);
    }

    /** The day's figures the suggestion and the cap rest on. */
    private record Day(long profit, long expenses, long available, long suggested) {}

    private Day day(UUID branch, LocalDate on) {
        long profit = profits.profitMinor(branch, on);
        long expenses = repo.activeExpensesMinor(branch, on);
        long available = available(profit, expenses);
        return new Day(profit, expenses, available, Math.min(available, suggested(available)));
    }

    @Transactional(readOnly = true)
    SavingsSuggestion suggestion(UUID branchId, LocalDate date) {
        UUID branch = branchContext.resolve(CashbookSupport.SAVINGS_RECORD, branchId);
        LocalDate on = support.businessDate(date, "date");
        Day d = day(branch, on);
        boolean gate = mayProfit(branch);
        // ADR-115: with the Owner's setting on, the suggestion shows without profit read; the profit, the expenses and
        // the
        // available amount (profit less expenses) never do.
        Instant since = support.salespeopleSavingsSince();
        boolean sees = gate || since != null;
        UUID existing = repo.activeSavings(branch, on).orElse(null);
        return new SavingsSuggestion(
                branch,
                on,
                figures.day(branch, on).totalSold(),
                support.suggestionToken(branch, on, d.suggested()),
                sees ? d.suggested() : null,
                gate ? d.profit() : null,
                gate ? d.expenses() : null,
                gate ? d.available() : null,
                existing,
                existing == null
                        ? null
                        : repo.savings(existing, false)
                                .map(x -> view(x, since).amountMinor())
                                .orElse(null),
                sees ? mayChange(branch, since) : null);
    }

    /** Whether a different amount is accepted: the overwrite permission, or the Owner's setting for everyone (ADR-115). */
    private static boolean mayChange(UUID branch, Instant since) {
        return since != null || CurrentPrincipal.require().may(CashbookSupport.SAVINGS_OVERWRITE, branch);
    }

    @Transactional
    Outcome<Savings> create(String key, SavingsRequest r) {
        return idempotency.once(key, "POST", PATH, r, Savings.class, () -> record(r));
    }

    private Savings record(SavingsRequest r) {
        UUID branch = branchContext.resolve(CashbookSupport.SAVINGS_RECORD, r.branchId());
        branchContext.requireSells(branch, "branch_id");
        LocalDate date = support.businessDate(r.businessDate(), "business_date");
        boolean profit = mayProfit(branch);
        // ADR-115: the Owner let salespeople see and change the amount; they still never see the profit or expenses.
        // Read once.
        Instant since = support.salespeopleSavingsSince();
        boolean salesperson = !profit && since != null;
        if (r.suggestionToken() == null || r.suggestionToken().isBlank()) {
            throw ApiException.rule(
                    "suggestion_token_required", "Read the suggestion first and send its token back with the record.");
        }
        if (!profit && !salesperson && (r.amountMinor() != null || r.overwriteReason() != null)) {
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
        repo.lockBranchDay(branch, date);
        Day d = day(branch, date);
        long suggested = d.suggested();
        if (!support.tokenMatches(branch, date, suggested, r.suggestionToken())) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("suggestion_token", support.suggestionToken(branch, date, suggested));
            if (profit || salesperson) {
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
        if (amount > d.available()) {
            throw aboveAvailable(d, date, profit);
        }
        boolean overwritten = typed && amount != suggested;
        String reason = null;
        Principal principal = CurrentPrincipal.require();
        if (overwritten) {
            if (!mayChange(branch, since)) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "permission_denied",
                        "Permission denied",
                        "You do not have permission to change the suggested savings amount.");
            }
            // Optional since #538: a short pick or nothing at all.
            reason = CashbookSupport.blankToNull(r.overwriteReason());
        }
        if (repo.activeSavings(branch, date).isPresent()) {
            throw savingsExist();
        }
        UUID id = UUID.randomUUID();
        UUID by = principal.userId();
        long sold = figures.day(branch, date).totalSold();
        UUID entry = books.post(CashPostings.savings(branch, date, id, amount))
                .map(PostedEntry::entryId)
                .orElse(null);
        try {
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
        } catch (DuplicateKeyException e) {
            // A simultaneous create won the partial unique index between the check and this insert.
            throw savingsExist();
        }
        // Flags only: an amount equal to the default would hand the profit to any reader of the audit log.
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("business_date", date.toString());
        after.put("overwritten", overwritten);
        after.put("default_applied", !typed);
        if (reason != null) {
            after.put("reason", reason);
        }
        if (salesperson || (overwritten && !principal.may(CashbookSupport.SAVINGS_OVERWRITE, branch))) {
            // ADR-115: the record was made under the Owner's setting; the actor is on the audit row itself.
            after.put("salespeople_setting", true);
        }
        audit.record(AuditLog.Entry.created("retail.savings.created", "retail.savings", id, branch, after));
        return view(repo.savings(id, false).orElseThrow(), since);
    }

    /**
     * A typed amount above the day's available amount (ADR-080). A caller with profit read is told the most the day allows,
     * its profit and its expenses. A salesperson typing under the Owner's setting (ADR-115) is told no figure at all: the
     * available amount is the profit less the expenses, which they could otherwise add back up to the profit.
     */
    private ApiException aboveAvailable(Day d, LocalDate date, boolean profit) {
        if (!profit) {
            String words = "That is more than this day allows. Save a smaller amount, or the suggested one.";
            return new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "savings_above_available",
                    "More than the day allows",
                    words,
                    List.of(new FieldProblem("amount_minor", "savings_above_available", words)));
        }
        String when = date.equals(support.today()) ? "today" : "for " + date.format(DAY);
        String detail = "You can save at most " + money(d.available()) + " " + when + " (profit " + money(d.profit())
                + " minus expenses " + money(d.expenses()) + ").";
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("available_minor", d.available());
        extra.put("daily_profit_minor", d.profit());
        extra.put("day_expenses_minor", d.expenses());
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "savings_above_available",
                "More than the day allows",
                detail,
                List.of(new FieldProblem("amount_minor", "savings_above_available", detail)),
                extra);
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** An amount as people write it: "UGX 4,000"; a currency with cents shows two decimals. */
    private String money(long minor) {
        String currency = support.currency();
        if ("UGX".equals(currency)) {
            return String.format(Locale.ROOT, "UGX %,d", minor);
        }
        return String.format(Locale.ROOT, "%s %,.2f", currency, minor / 100.0);
    }

    private static ApiException savingsExist() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "savings_exists",
                "Savings exist",
                "Savings are already recorded for this shop and day. Void that record to enter another.");
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
        Instant since = support.salespeopleSavingsSince();
        return new SavingsPage(items.stream().map(x -> view(x, since)).toList(), next);
    }

    /**
     * A savings row as the caller may see it: the suggestion needs profit read. The amount and the overwrite flag need profit
     * read, or (ADR-115) the Owner's setting on since {@code since}, a row saved at or after that moment, and
     * {@code retail.savings.record} in its branch: a row saved before the setting was turned on keeps its amount hidden.
     */
    static Savings view(SavingsRow r, Instant since) {
        boolean profit = mayProfit(r.branchId());
        boolean amount = profit
                || (since != null
                        && !r.createdAt().isBefore(since)
                        && CurrentPrincipal.require().may(CashbookSupport.SAVINGS_RECORD, r.branchId()));
        return new Savings(
                r.id(),
                r.branchId(),
                r.businessDate(),
                r.currency(),
                amount ? r.amountMinor() : null,
                profit ? r.suggestedMinor() : null,
                amount ? r.overwritten() : null,
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
        if (row.historical()) {
            throw CashbookSupport.historicalRecord();
        }
        String reason = CashbookSupport.reason(r);
        Instant at = support.now();
        LocalDate today = support.dayOf(at);
        if (row.journalEntryId() != null) {
            books.reverse(
                    row.journalEntryId(),
                    today,
                    CashPostings.ref("Void savings", id),
                    CashPostings.voidKey(CashPostings.SAVINGS, id));
        }
        repo.markVoided(TABLE, id, principal.userId(), reason, at);
        audit.record(new AuditLog.Entry(
                "retail.savings.voided",
                "retail.savings",
                id,
                row.branchId(),
                Map.of("business_date", row.businessDate().toString()),
                Map.of("reason", reason)));
        return view(repo.savings(id, false).orElseThrow(), support.salespeopleSavingsSince());
    }
}
