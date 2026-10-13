package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.Account;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.AccountPayment;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.AccountPaymentList;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.AccountRequest;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.PaymentRequest;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.ReverseRequest;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.Statement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/retail/customers/{id}} account routes (customer_accounts capability; ADR-033, issue #196; chapter 7
 * section 7.11.20). Each answers 404 {@code capability_not_enabled} while the capability is off.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/customers/{customer_id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-customer-accounts")
class AccountsController {

    private final AccountsService service;

    AccountsController(AccountsService service) {
        this.service = service;
    }

    @GetMapping("/account")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "A credit buyer's account: limit, terms and what is owed (issue #196)",
            operationId = "getRetailCustomerAccount")
    ResponseEntity<Account> account(@PathVariable("customer_id") UUID id) {
        Account a = service.account(id);
        return ResponseEntity.ok().eTag(String.valueOf(a.version())).body(a);
    }

    @PutMapping("/account")
    @RequiresPermission("retail.credit.manage")
    @Operation(
            summary = "Set a credit buyer's limit and payment terms under If-Match (issue #196)",
            operationId = "setRetailCustomerAccount")
    ResponseEntity<Account> setAccount(
            @PathVariable("customer_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody AccountRequest request) {
        Account a = service.updateAccount(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(a.version())).body(a);
    }

    @GetMapping("/statement")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "Statement: opening balance, lines with a running balance, closing balance (issue #196)",
            operationId = "getRetailCustomerStatement")
    Statement statement(
            @PathVariable("customer_id") UUID id,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to) {
        return service.statement(id, from, to);
    }

    @PostMapping("/payments")
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary = "Payment on account: money from a credit buyer allocated to their open sales (issue #196); M",
            operationId = "payRetailCustomerAccount")
    ResponseEntity<AccountPayment> pay(
            @PathVariable("customer_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        Outcome<AccountPayment> outcome = service.pay(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create(
                "/api/v1/retail/customers/" + id + "/payments/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/payments")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "Payments on account of a credit buyer, newest first (issue #196)",
            operationId = "listRetailCustomerAccountPayments")
    AccountPaymentList payments(
            @PathVariable("customer_id") UUID id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.payments(id, limit, cursor);
    }

    @PostMapping("/payments/{payment_id}/reverse")
    @RequiresPermission("retail.sale.void")
    @Operation(
            summary = "Reverse a payment on account as a whole (issue #196); M",
            operationId = "reverseRetailCustomerAccountPayment")
    ResponseEntity<AccountPayment> reverse(
            @PathVariable("customer_id") UUID id,
            @PathVariable("payment_id") UUID paymentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReverseRequest request) {
        Outcome<AccountPayment> outcome = service.reverse(idempotencyKey, id, paymentId, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.OK);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
