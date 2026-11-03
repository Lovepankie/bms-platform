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
import com.rincoltech.bms.retail.cashbook.internal.CashbookSupport.SalespeopleSavings;
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
 * caller without that permission cannot send an amount at all (no equality oracle). ADR-115 (issue #768): when the owner turns
 * on {@code salespeople_see_savings_amount}, such a caller sees the suggestion and the saved amount and may change the amount
 * within the cap; the profit, the expenses and the cap itself stay hidden, and an amount above the cap is refused in words that
 * do not state it. The profit can still be worked out from the suggestion (ADR-115, Worse).
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

    /**
     * Who is asking (issue #799, ADR-115): {@code TILL} through the till's routes ({@code retail.savings.record} and the
     * till), {@code SALES_STAFF} through the sales staff routes ({@code retail.sale.create}), which answer only while the
     * owner's setting is on and resolve the branch by the right to sell there. Everything after the branch is the same.
     */
    enum Caller {
        TILL,
        SALES_STAFF
    }

    private UUID resolveBranch(Caller caller, SalespeopleSavings setting, UUID requested) {
        if (caller == Caller.SALES_STAFF) {
            setting.requireOnForSalesStaff();
            return branchContext.resolve(CashbookSupport.SALE_CREATE, requested);
        }
        return branchContext.resolve(CashbookSupport.SAVINGS_RECORD, requested);
    }

    /**
     * The business date of a request; sales staff (issue #799) are held to this business month, the scope the owner gave them,
     * so no earlier day's suggestion (half its profit) reaches them and nothing they save falls outside the records they see.
     */
    private LocalDate businessDate(Caller caller, LocalDate requested, String field) {
        LocalDate date = support.businessDate(requested, field);
        if (caller == Caller.SALES_STAFF && date.isBefore(monthStart())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    field, "outside_this_month", "Savings can be opened and saved for days of this month only.")));
        }
        return date;
    }

    @Transactional(readOnly = true)
    SavingsSuggestion suggestion(UUID branchId, LocalDate date) {
        return suggestion(Caller.TILL, branchId, date);
    }

    @Transactional(readOnly = true)
    SavingsSuggestion suggestion(Caller caller, UUID branchId, LocalDate date) {
        // ADR-115: with the owner's setting on, the suggestion shows without profit read; the profit, the expenses and
        // the
        // cap never do, and the day's saved amount only as amountShown allows.
        SalespeopleSavings setting = support.salespeopleSavings();
        UUID branch = resolveBranch(caller, setting, branchId);
        LocalDate on = businessDate(caller, date, "date");
        Day d = day(branch, on);
        boolean gate = mayProfit(branch);
        boolean sees = gate || setting.on();
        LocalDate monthStart = monthStart();
        UUID existing = repo.activeSavings(branch, on).orElse(null);
        Long existingAmount = existing == null
                ? null
                : repo.savings(existing, false)
                        .filter(x -> amountShown(x, setting, monthStart))
                        .map(SavingsRow::amountMinor)
                        .orElse(null);
        return new SavingsSuggestion(
                branch,
                on,
                figures.day(branch, on).totalSold(),
                support.suggestionToken(branch, on, d.suggested()),
                // Issue #799 (the owner's request): once the day is saved, its suggestion is no longer sent to anyone;
                // the
                // screen shows only what was saved.
                sees && existing == null ? d.suggested() : null,
                gate ? d.profit() : null,
                gate ? d.expenses() : null,
                gate ? d.available() : null,
                existing,
                existingAmount,
                sees && existing == null && mayChangeAmount(branch, on, setting));
    }

    /**
     * ADR-115: the same rule for every caller, so no one gets more by holding less. Changing the suggestion before the day is
     * saved needs only {@code retail.savings.record} while the owner's setting is on; replacing a saved amount (the day has a
     * voided record) and any change while the setting is off need {@code retail.savings.overwrite}.
     */
    private boolean mayChangeAmount(UUID branch, LocalDate date, SalespeopleSavings setting) {
        return CurrentPrincipal.require().may(CashbookSupport.SAVINGS_OVERWRITE, branch)
                || (setting.on() && !repo.voidedSavingsExist(branch, date));
    }

    @Transactional
    Outcome<Savings> create(String key, SavingsRequest r) {
        return create(Caller.TILL, key, r);
    }

    @Transactional
    Outcome<Savings> create(Caller caller, String key, SavingsRequest r) {
        String path = caller == Caller.SALES_STAFF ? SALES_STAFF_PATH : PATH;
        // ADR-115: the owner let salespeople see and change the amount; they still never see the profit or expenses.
        // Read
        // once, and for sales staff checked before the replay, so a key replayed after the switch was turned off is
        // refused.
        SalespeopleSavings setting = support.salespeopleSavings();
        if (caller == Caller.SALES_STAFF) {
            setting.requireOnForSalesStaff();
        }
        return idempotency.once(key, "POST", path, r, Savings.class, () -> record(caller, setting, r));
    }

    /** The sales staff routes (issue #799). */
    static final String SALES_STAFF_PATH = PATH + "/sales-staff";

    private Savings record(Caller caller, SalespeopleSavings setting, SavingsRequest r) {
        UUID branch = resolveBranch(caller, setting, r.branchId());
        branchContext.requireSells(branch, "branch_id");
        LocalDate date = businessDate(caller, r.businessDate(), "business_date");
        boolean profit = mayProfit(branch);
        boolean salesperson = !profit && setting.on();
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
            // Issue #799: never for a day already saved; the 409 savings_exists that follows a fresh read says why.
            if ((profit || salesperson) && repo.activeSavings(branch, date).isEmpty()) {
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
            if (!mayChangeAmount(branch, date, setting)) {
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
        // ADR-115: the cap is judged last, after the permission and existing-record checks, so a request refused for
        // another reason never says whether its amount was above the cap.
        if (amount > d.available()) {
            throw aboveAvailable(d, date, profit);
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
        if (setting.on()) {
            // ADR-115: the record was made under the owner's setting; the actor is on the audit row itself.
            after.put("salespeople_setting", true);
        }
        audit.record(AuditLog.Entry.created("retail.savings.created", "retail.savings", id, branch, after));
        return view(repo.savings(id, false).orElseThrow(), setting, monthStart());
    }

    /**
     * A typed amount above the day's available amount (ADR-080). A caller with profit read is told the cap, the profit
     * and the expenses; a salesperson typing under the owner's setting (ADR-115) is told none of them, only that the amount
     * is too large, so the words never state the cap.
     */
    private ApiException aboveAvailable(Day d, LocalDate date, boolean profit) {
        boolean today = date.equals(support.today());
        String detail = profit
                ? "You can save at most " + money(d.available()) + " " + (today ? "today" : "for " + date.format(DAY))
                        + " (profit " + money(d.profit()) + " minus expenses " + money(d.expenses()) + ")."
                : "That amount is more than this shop can save " + (today ? "today" : "on " + date.format(DAY))
                        + ". Enter a smaller amount.";
        Map<String, Object> extra = new LinkedHashMap<>();
        if (profit) {
            extra.put("available_minor", d.available());
            extra.put("daily_profit_minor", d.profit());
            extra.put("day_expenses_minor", d.expenses());
        }
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
        return list(Caller.TILL, branchIds, from, to, includeVoided, limit, cursor);
    }

    /**
     * The savings records. Through the sales staff routes (issue #799) only while the owner's setting is on, only at the
     * branches where the caller may sell, and only this month's: an earlier {@code from} is moved to the first of the month.
     */
    @Transactional(readOnly = true)
    SavingsPage list(
            Caller caller,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            boolean includeVoided,
            Integer limit,
            String cursor) {
        SalespeopleSavings setting = support.salespeopleSavings();
        LocalDate monthStart = monthStart();
        LocalDate since = from;
        List<UUID> filter;
        if (caller == Caller.SALES_STAFF) {
            setting.requireOnForSalesStaff();
            // A named branch outside the selling scope is 404 unknown_branch, as on the suggestion and the save
            // (FR-RET-18).
            if (branchIds != null) {
                branchIds.forEach(b -> branchContext.resolve(CashbookSupport.SALE_CREATE, b));
            }
            filter = CurrentPrincipal.require().branchFilter(CashbookSupport.SALE_CREATE, branchIds);
            since = from == null || from.isBefore(monthStart) ? monthStart : from;
        } else {
            filter = CurrentPrincipal.require().branchFilter(CashbookSupport.READ, branchIds);
        }
        int size = support.limit(limit);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<SavingsRow> rows = repo.savingsPage(
                filter,
                since,
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
        return new SavingsPage(
                items.stream().map(x -> view(x, setting, monthStart)).toList(), next);
    }

    /** The first day of the business month: the month whose records sales staff see with their amounts (issue #799). */
    private LocalDate monthStart() {
        return support.today().withDayOfMonth(1);
    }

    /**
     * Whether the caller sees a record's amount and overwrite flag (ADR-115). Profit read sees every amount. Without it, only
     * while the owner's setting is on, and then: a record made after the setting was last turned on, to a holder of
     * {@code retail.savings.record} for its branch (issue #768); and every record dated this month, whenever it was made, to a
     * caller who may record savings or sell at its branch (issue #799, the owner asked for the whole month). A reader of the
     * cash book alone keeps the amount hidden.
     */
    static boolean amountShown(SavingsRow r, SalespeopleSavings setting, LocalDate monthStart) {
        if (mayProfit(r.branchId())) {
            return true;
        }
        if (!setting.on()) {
            return false;
        }
        Principal principal = CurrentPrincipal.require();
        boolean records = principal.may(CashbookSupport.SAVINGS_RECORD, r.branchId());
        if (records && setting.shows(r.createdAt())) {
            return true;
        }
        return (records || principal.may(CashbookSupport.SALE_CREATE, r.branchId()))
                && !r.businessDate().isBefore(monthStart);
    }

    /** A savings row as the caller may see it: the suggestion needs profit read; the amount follows {@link #amountShown}. */
    static Savings view(SavingsRow r, SalespeopleSavings setting, LocalDate monthStart) {
        boolean profit = mayProfit(r.branchId());
        boolean amount = amountShown(r, setting, monthStart);
        return new Savings(
                r.id(),
                r.branchId(),
                r.businessDate(),
                r.currency(),
                amount ? r.amountMinor() : null,
                // Issue #799: an active record is a saved day, whose suggestion is sent to no one, the Owner included;
                // a
                // voided record no longer saves its day and keeps it for profit read.
                profit && r.voided().voided() ? r.suggestedMinor() : null,
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
        return view(repo.savings(id, false).orElseThrow(), support.salespeopleSavings(), monthStart());
    }
}
