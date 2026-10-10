package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Advance;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.AdvancePage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.AdvanceReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.AdvanceRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Banking;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingExpected;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.CashDailyReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Expense;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpensePage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Repayment;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.RepaymentRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Savings;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsSuggestion;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.VoidRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Withdrawal;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.WithdrawalPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.WithdrawalRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cash book routes (chapter 7 section 7.11.21; FR-RET-18 to FR-RET-29): savings, banking,
 * withdrawals, expenses, advances and the reports. Every POST is money-moving and takes an
 * {@code Idempotency-Key}; a void takes {@code {reason}} and needs {@code retail.cashbook.void}.
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-cashbook")
class CashbookController {

    private static final String KEY = "Idempotency-Key";

    private final SavingsService savings;
    private final BankingService banking;
    private final ExpenseService expenses;
    private final AdvanceService advances;
    private final CashReportsService reports;

    CashbookController(
            SavingsService savings,
            BankingService banking,
            ExpenseService expenses,
            AdvanceService advances,
            CashReportsService reports) {
        this.savings = savings;
        this.banking = banking;
        this.expenses = expenses;
        this.advances = advances;
        this.reports = reports;
    }

    private static <T> ResponseEntity<T> created(Outcome<T> outcome, String location) {
        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create(location));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    private static <T> ResponseEntity<T> ok(Outcome<T> outcome) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    // ---------------------------------------------------------------------------------- savings

    @GetMapping("/savings/suggestion")
    @RequiresPermission("retail.savings.record")
    @Operation(
            summary = "The day's savings suggestion token, and the suggestion with profit read (FR-RET-18)",
            operationId = "getRetailSavingsSuggestion")
    SavingsSuggestion suggestion(
            @RequestParam(name = "branch_id", required = false) UUID branchId,
            @RequestParam(name = "date", required = false) LocalDate date) {
        return savings.suggestion(branchId, date);
    }

    @PostMapping("/savings")
    @RequiresPermission("retail.savings.record")
    @Operation(summary = "Record the day's savings (FR-RET-18 to FR-RET-20); M", operationId = "createRetailSavings")
    ResponseEntity<Savings> createSavings(
            @RequestHeader(name = KEY, required = false) String key, @Valid @RequestBody SavingsRequest request) {
        Outcome<Savings> outcome = savings.create(key, request);
        return created(outcome, "/api/v1/retail/savings/" + outcome.body().id());
    }

    @GetMapping("/savings")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Savings records, newest first", operationId = "listRetailSavings")
    SavingsPage listSavings(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "include_voided", required = false, defaultValue = "false") boolean includeVoided,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return savings.list(branchIds, from, to, includeVoided, limit, cursor);
    }

    @PostMapping("/savings/{savings_id}/void")
    @RequiresPermission("retail.cashbook.void")
    @Operation(summary = "Void a savings record and free its day (FR-RET-28); M", operationId = "voidRetailSavings")
    ResponseEntity<Savings> voidSavings(
            @RequestHeader(name = KEY, required = false) String key,
            @PathVariable("savings_id") UUID id,
            @Valid @RequestBody VoidRequest request) {
        return ok(savings.voidSavings(key, id, request));
    }

    // ---------------------------------------------------------------------------------- banking

    @GetMapping("/bankings/expected")
    @RequiresPermission("retail.banking.record")
    @Operation(
            summary = "The amount expected to be banked for a shop and day (FR-RET-21)",
            operationId = "getRetailBankingExpected")
    BankingExpected expected(
            @RequestParam(name = "branch_id", required = false) UUID branchId,
            @RequestParam(name = "date", required = false) LocalDate date) {
        return banking.expected(branchId, date);
    }

    @PostMapping("/bankings")
    @RequiresPermission("retail.banking.record")
    @Operation(summary = "Record cash banked (FR-RET-22); M", operationId = "createRetailBanking")
    ResponseEntity<Banking> createBanking(
            @RequestHeader(name = KEY, required = false) String key, @Valid @RequestBody BankingRequest request) {
        Outcome<Banking> outcome = banking.createBanking(key, request);
        return created(outcome, "/api/v1/retail/bankings/" + outcome.body().id());
    }

    @GetMapping("/bankings")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Banking records, newest first", operationId = "listRetailBankings")
    BankingPage listBankings(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "include_voided", required = false, defaultValue = "false") boolean includeVoided,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return banking.listBankings(branchIds, from, to, includeVoided, limit, cursor);
    }

    @PostMapping("/bankings/{banking_id}/void")
    @RequiresPermission("retail.cashbook.void")
    @Operation(summary = "Void a banking record (FR-RET-28); M", operationId = "voidRetailBanking")
    ResponseEntity<Banking> voidBanking(
            @RequestHeader(name = KEY, required = false) String key,
            @PathVariable("banking_id") UUID id,
            @Valid @RequestBody VoidRequest request) {
        return ok(banking.voidBanking(key, id, request));
    }

    // ------------------------------------------------------------------------------- withdrawals

    @PostMapping("/withdrawals")
    @RequiresPermission("retail.withdrawal.record")
    @Operation(summary = "Record cash withdrawn from the bank (FR-RET-25); M", operationId = "createRetailWithdrawal")
    ResponseEntity<Withdrawal> createWithdrawal(
            @RequestHeader(name = KEY, required = false) String key, @Valid @RequestBody WithdrawalRequest request) {
        Outcome<Withdrawal> outcome = banking.createWithdrawal(key, request);
        return created(outcome, "/api/v1/retail/withdrawals/" + outcome.body().id());
    }

    @GetMapping("/withdrawals")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Withdrawals from the bank, newest first", operationId = "listRetailWithdrawals")
    WithdrawalPage listWithdrawals(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "include_voided", required = false, defaultValue = "false") boolean includeVoided,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return banking.listWithdrawals(branchIds, from, to, includeVoided, limit, cursor);
    }

    @PostMapping("/withdrawals/{withdrawal_id}/void")
    @RequiresPermission("retail.cashbook.void")
    @Operation(summary = "Void a withdrawal (FR-RET-28); M", operationId = "voidRetailWithdrawal")
    ResponseEntity<Withdrawal> voidWithdrawal(
            @RequestHeader(name = KEY, required = false) String key,
            @PathVariable("withdrawal_id") UUID id,
            @Valid @RequestBody VoidRequest request) {
        return ok(banking.voidWithdrawal(key, id, request));
    }

    // ---------------------------------------------------------------------------------- expenses

    @PostMapping("/expenses")
    @RequiresPermission("retail.expense.record")
    @Operation(
            summary = "Record a company expense paid from the till (FR-RET-24); M",
            operationId = "createRetailExpense")
    ResponseEntity<Expense> createExpense(
            @RequestHeader(name = KEY, required = false) String key, @Valid @RequestBody ExpenseRequest request) {
        Outcome<Expense> outcome = expenses.create(key, request);
        return created(outcome, "/api/v1/retail/expenses/" + outcome.body().id());
    }

    @GetMapping("/expenses")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Expenses, newest first", operationId = "listRetailExpenses")
    ExpensePage listExpenses(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "category_id", required = false) UUID categoryId,
            @RequestParam(name = "item_id", required = false) UUID itemId,
            @RequestParam(name = "include_voided", required = false, defaultValue = "false") boolean includeVoided,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return expenses.list(branchIds, from, to, categoryId, itemId, includeVoided, limit, cursor);
    }

    @PostMapping("/expenses/{expense_id}/void")
    @RequiresPermission("retail.cashbook.void")
    @Operation(summary = "Void an expense (FR-RET-28); M", operationId = "voidRetailExpense")
    ResponseEntity<Expense> voidExpense(
            @RequestHeader(name = KEY, required = false) String key,
            @PathVariable("expense_id") UUID id,
            @Valid @RequestBody VoidRequest request) {
        return ok(expenses.voidExpense(key, id, request));
    }

    // ---------------------------------------------------------------------------------- advances

    @PostMapping("/advances")
    @RequiresPermission("retail.advance.create")
    @Operation(
            summary = "Record an advance to the owner or a related party (FR-RET-26); M",
            operationId = "createRetailAdvance")
    ResponseEntity<Advance> createAdvance(
            @RequestHeader(name = KEY, required = false) String key, @Valid @RequestBody AdvanceRequest request) {
        Outcome<Advance> outcome = advances.create(key, request);
        return created(outcome, "/api/v1/retail/advances/" + outcome.body().id());
    }

    @GetMapping("/advances")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Advances with their balances, newest first", operationId = "listRetailAdvances")
    AdvancePage listAdvances(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "party_id", required = false) UUID partyId,
            @RequestParam(name = "open_only", required = false, defaultValue = "false") boolean openOnly,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return advances.list(branchIds, partyId, openOnly, from, to, limit, cursor);
    }

    @GetMapping("/advances/{advance_id}")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "An advance with its repayments", operationId = "getRetailAdvance")
    Advance getAdvance(@PathVariable("advance_id") UUID id) {
        return advances.get(id);
    }

    @PostMapping("/advances/{advance_id}/repayments")
    @RequiresPermission("retail.advance.repay")
    @Operation(
            summary = "Record a repayment of an advance (FR-RET-26); M",
            operationId = "createRetailAdvanceRepayment")
    ResponseEntity<Repayment> repay(
            @RequestHeader(name = KEY, required = false) String key,
            @PathVariable("advance_id") UUID id,
            @Valid @RequestBody RepaymentRequest request) {
        Outcome<Repayment> outcome = advances.repay(key, id, request);
        return created(
                outcome,
                "/api/v1/retail/advances/" + id + "/repayments/"
                        + outcome.body().id());
    }

    @PostMapping("/advances/{advance_id}/void")
    @RequiresPermission("retail.cashbook.void")
    @Operation(summary = "Void an advance that has no repayments (FR-RET-28); M", operationId = "voidRetailAdvance")
    ResponseEntity<Advance> voidAdvance(
            @RequestHeader(name = KEY, required = false) String key,
            @PathVariable("advance_id") UUID id,
            @Valid @RequestBody VoidRequest request) {
        return ok(advances.voidAdvance(key, id, request));
    }

    @PostMapping("/advances/{advance_id}/repayments/{repayment_id}/void")
    @RequiresPermission("retail.cashbook.void")
    @Operation(
            summary = "Void a repayment and restore the balance (FR-RET-28); M",
            operationId = "voidRetailAdvanceRepayment")
    ResponseEntity<Repayment> voidRepayment(
            @RequestHeader(name = KEY, required = false) String key,
            @PathVariable("advance_id") UUID advanceId,
            @PathVariable("repayment_id") UUID repaymentId,
            @Valid @RequestBody VoidRequest request) {
        return ok(advances.voidRepayment(key, advanceId, repaymentId, request));
    }

    // ----------------------------------------------------------------------------------- reports

    @GetMapping("/reports/cash/daily")
    @RequiresPermission("retail.cashbook.read")
    @Operation(
            summary = "The daily cash summary per shop and day (FR-RET-27)",
            operationId = "getRetailCashDailyReport")
    CashDailyReport daily(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to) {
        return reports.daily(branchIds, from, to);
    }

    @GetMapping("/reports/cash/banking")
    @RequiresPermission("retail.cashbook.read")
    @Operation(
            summary = "Expected against banked per shop and day, with the running unbanked total (FR-RET-23)",
            operationId = "getRetailCashBankingReport")
    BankingReport bankingReport(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "flag", required = false) String flag) {
        return reports.banking(branchIds, from, to, flag);
    }

    @GetMapping("/reports/cash/expenses")
    @RequiresPermission("retail.cashbook.read")
    @Operation(
            summary = "Expenses by category, item, shop or month (FR-RET-29)",
            operationId = "getRetailCashExpensesReport")
    ExpenseReport expensesReport(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "group_by", required = false) String groupBy,
            @RequestParam(name = "include_voided", required = false, defaultValue = "false") boolean includeVoided) {
        return reports.expenses(branchIds, from, to, groupBy, includeVoided);
    }

    @GetMapping("/reports/cash/savings")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Savings per shop and day (FR-RET-29)", operationId = "getRetailCashSavingsReport")
    SavingsReport savingsReport(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to) {
        return reports.savings(branchIds, from, to);
    }

    @GetMapping("/reports/cash/advances")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Outstanding advances by party (FR-RET-29)", operationId = "getRetailCashAdvancesReport")
    AdvanceReport advancesReport(@RequestParam(name = "branch_id", required = false) List<UUID> branchIds) {
        return reports.advances(branchIds);
    }
}
