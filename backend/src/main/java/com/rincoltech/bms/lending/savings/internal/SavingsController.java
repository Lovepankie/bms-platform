package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Account;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.AccountPage;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.ActionOutcome;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.CloseRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.DepositResult;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.MoneyRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.OpenAccountRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.ReasonRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Statement;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.TransactionPage;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.WithdrawalRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsIdempotency.Outcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
 * {@code /api/v1/lending/savings-accounts} (chapter 7 section 7.11.15; FR-SAV-02 to FR-SAV-07).
 * Every money route requires an {@code Idempotency-Key}; a replay answers
 * {@code Idempotent-Replayed: true}. A withdrawal, a closure and a reversal answer 201 when executed
 * and 202 when they wait for a checker.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/savings-accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-savings")
class SavingsController {

    private final SavingsService service;

    SavingsController(SavingsService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("lending.savings.read")
    @Operation(
            summary = "List savings accounts in the caller's branch scope, by account number",
            operationId = "listSavingsAccounts")
    AccountPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "member_id", required = false) UUID memberId,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.accounts(branchIds, memberId, statuses, productId, q, limit, cursor);
    }

    @PostMapping
    @RequiresPermission("lending.savings.open")
    @Operation(
            summary = "Open a savings account for a member (FR-SAV-02); a member may hold several",
            operationId = "openSavingsAccount")
    ResponseEntity<Account> open(@Valid @RequestBody OpenAccountRequest request) {
        Account created = service.open(request);
        return ResponseEntity.created(URI.create("/api/v1/lending/savings-accounts/" + created.id()))
                .body(created);
    }

    @GetMapping("/{account_id}")
    @RequiresPermission("lending.savings.read")
    @Operation(summary = "A savings account with its balance and accrued interest", operationId = "getSavingsAccount")
    Account get(@PathVariable("account_id") UUID id) {
        return service.account(id);
    }

    @GetMapping("/{account_id}/transactions")
    @RequiresPermission("lending.savings.read")
    @Operation(
            summary = "The account's movements with running balances, newest first (FR-SAV-04)",
            operationId = "listSavingsTransactions")
    TransactionPage transactions(
            @PathVariable("account_id") UUID id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.transactions(id, limit, cursor);
    }

    @GetMapping("/{account_id}/statement")
    @RequiresPermission("lending.savings.read")
    @Operation(
            summary = "The account statement for a range of value dates; the last three months by default",
            operationId = "getSavingsStatement")
    Statement statement(
            @PathVariable("account_id") UUID id,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to) {
        return service.statement(id, from, to);
    }

    @PostMapping("/{account_id}/deposits")
    @RequiresPermission("lending.savings.deposit")
    @Operation(summary = "Record a deposit (FR-SAV-03)", operationId = "recordSavingsDeposit")
    ResponseEntity<DepositResult> deposit(
            @PathVariable("account_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody MoneyRequest request) {
        Outcome<DepositResult> o = service.deposit(id, key, request);
        var response = ResponseEntity.status(HttpStatus.CREATED);
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }

    @PostMapping("/{account_id}/withdrawals")
    @RequiresPermission("lending.savings.withdraw")
    @Operation(
            summary = "Request a withdrawal (FR-SAV-03); 201 when executed below the threshold, 202 when it waits for a"
                    + " checker",
            operationId = "requestSavingsWithdrawal")
    ResponseEntity<ActionOutcome> withdraw(
            @PathVariable("account_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WithdrawalRequest request) {
        return action(service.withdraw(id, key, request));
    }

    @PostMapping("/{account_id}/transactions/{txn_id}/reverse")
    @RequiresPermission("lending.savings.withdraw")
    @Operation(
            summary = "Request the reversal of a deposit or a withdrawal (with its fee), with a reason; always checked",
            operationId = "requestSavingsReversal")
    ResponseEntity<ActionOutcome> reverse(
            @PathVariable("account_id") UUID id,
            @PathVariable("txn_id") UUID txnId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReasonRequest request) {
        return action(service.reverse(id, txnId, key, request));
    }

    @PostMapping("/{account_id}/close")
    @RequiresPermission("lending.savings.withdraw")
    @Operation(
            summary = "Request closure (FR-SAV-07): interest to date, then the whole balance paid out",
            operationId = "requestSavingsClosure")
    ResponseEntity<ActionOutcome> close(
            @PathVariable("account_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CloseRequest request) {
        return action(service.close(id, key, request));
    }

    @PostMapping("/{account_id}/reactivate")
    @RequiresPermission("lending.savings.withdraw_approve")
    @Operation(summary = "Reactivate a dormant account (FR-SAV-06)", operationId = "reactivateSavingsAccount")
    Account reactivate(@PathVariable("account_id") UUID id, @Valid @RequestBody ReasonRequest request) {
        return service.reactivate(id, request);
    }

    @PostMapping("/{account_id}/freeze")
    @RequiresPermission("lending.savings.withdraw_approve")
    @Operation(
            summary = "Freeze an account: no withdrawal or closure until unfrozen",
            operationId = "freezeSavingsAccount")
    Account freeze(@PathVariable("account_id") UUID id, @Valid @RequestBody ReasonRequest request) {
        return service.freeze(id, request);
    }

    @PostMapping("/{account_id}/unfreeze")
    @RequiresPermission("lending.savings.withdraw_approve")
    @Operation(summary = "Unfreeze an account", operationId = "unfreezeSavingsAccount")
    Account unfreeze(@PathVariable("account_id") UUID id, @Valid @RequestBody ReasonRequest request) {
        return service.unfreeze(id, request);
    }

    private static ResponseEntity<ActionOutcome> action(Outcome<ActionOutcome> o) {
        var response = ResponseEntity.status(o.body().executed() ? HttpStatus.CREATED : HttpStatus.ACCEPTED);
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }
}
