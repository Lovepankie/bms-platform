package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.loans.internal.LoanIdempotency.Outcome;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ActionOutcome;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.DisbursementRequest;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.PayoffQuote;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ReasonRequest;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.RepaymentRequest;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.RepaymentResult;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.Schedule;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.TransactionList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
 * {@code /api/v1/lending/loans/{loan_id}/...}: disbursement, schedule, repayments, reversal, payoff
 * quote and write-off (chapter 7 section 7.11.13; FR-DIS, FR-REP, FR-LCL). Every money-moving route
 * requires an {@code Idempotency-Key}; a replay answers {@code Idempotent-Replayed: true}.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/loans/{loan_id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-loans")
class ServicingController {

    private final ServicingService service;

    ServicingController(ServicingService service) {
        this.service = service;
    }

    @PostMapping("/disbursements")
    @RequiresPermission("lending.disbursements.request")
    @Operation(
            summary = "Request disbursement of an approved loan (FR-DIS-01); 201 when executed below the threshold,"
                    + " 202 when it waits for a checker",
            operationId = "requestLoanDisbursement")
    ResponseEntity<ActionOutcome> disburse(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody DisbursementRequest request) {
        return action(service.requestDisbursement(id, key, request));
    }

    @GetMapping("/schedule")
    @RequiresPermission("lending.loans.read")
    @Operation(summary = "The repayment schedule with a totals row (FR-DIS-04)", operationId = "getLoanSchedule")
    Schedule schedule(@PathVariable("loan_id") UUID id) {
        return service.schedule(id);
    }

    @GetMapping("/transactions")
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary = "The loan's money events with their allocations, newest first",
            operationId = "listLoanTransactions")
    TransactionList transactions(@PathVariable("loan_id") UUID id) {
        return service.transactions(id);
    }

    @PostMapping("/repayments")
    @RequiresPermission("lending.repayments.create")
    @Operation(
            summary = "Record a repayment, allocated per R-ALLOC; a recovery on a written-off loan (FR-REP-01 to"
                    + " FR-REP-04, FR-LCL-03)",
            operationId = "recordLoanRepayment")
    ResponseEntity<RepaymentResult> repay(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RepaymentRequest request) {
        Outcome<RepaymentResult> o = service.repay(id, key, request);
        var response = ResponseEntity.status(HttpStatus.CREATED);
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }

    @PostMapping("/transactions/{txn_id}/reverse")
    @RequiresPermission("lending.repayments.reverse_request")
    @Operation(
            summary = "Request the reversal of a repayment or recovery, with a reason (FR-REP-05)",
            operationId = "requestLoanTransactionReversal")
    ResponseEntity<ActionOutcome> reverse(
            @PathVariable("loan_id") UUID id,
            @PathVariable("txn_id") UUID txnId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReasonRequest request) {
        return action(service.requestReversal(id, txnId, key, request));
    }

    @GetMapping("/payoff-quote")
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary = "The payoff amount as at a value date, today by default (FR-REP-06)",
            operationId = "getLoanPayoffQuote")
    PayoffQuote payoff(
            @PathVariable("loan_id") UUID id,
            @RequestParam(name = "value_date", required = false) LocalDate valueDate) {
        return service.payoff(id, valueDate);
    }

    @PostMapping("/write-off")
    @RequiresPermission("lending.loans.write_off_request")
    @Operation(
            summary = "Request write-off of an active loan, with a reason (FR-LCL-02)",
            operationId = "requestLoanWriteOff")
    ResponseEntity<ActionOutcome> writeOff(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReasonRequest request) {
        return action(service.requestWriteOff(id, key, request));
    }

    private static ResponseEntity<ActionOutcome> action(Outcome<ActionOutcome> o) {
        var response = ResponseEntity.status(o.body().executed() ? HttpStatus.CREATED : HttpStatus.ACCEPTED);
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }
}
