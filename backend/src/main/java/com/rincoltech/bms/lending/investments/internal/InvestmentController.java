package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.ActionOutcome;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Certificate;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.EarlyQuote;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.EarlyWithdrawalRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.FundingRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.InstructionRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Investment;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.InvestmentPage;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Maturities;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Metrics;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.OpenRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.PaymentRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.ReasonRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.RolloverRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.RolloverResult;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Schedule;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Statement;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.TransactionResult;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/lending/investments} (chapter 7 section 7.11.16; FR-INV-02 to FR-INV-12). Every
 * route that moves money requires an {@code Idempotency-Key}; a replay answers
 * {@code Idempotent-Replayed: true}.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/investments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-investments")
class InvestmentController {

    private final InvestmentService service;
    private final InvestmentReports reports;

    InvestmentController(InvestmentService service, InvestmentReports reports) {
        this.service = service;
        this.reports = reports;
    }

    @GetMapping
    @RequiresPermission("lending.investments.read")
    @Operation(
            summary = "List investments in the caller's branch scope; q searches account number, member number or"
                    + " name (chapter 14 investments register)",
            operationId = "listInvestments")
    InvestmentPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "member_id", required = false) UUID memberId,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam(name = "maturing_within_days", required = false) Integer maturingWithinDays,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(branchIds, statuses, memberId, productId, maturingWithinDays, q, limit, cursor);
    }

    @PostMapping
    @RequiresPermission("lending.investments.open")
    @Operation(summary = "Open an investment for a member, pending funding (FR-INV-02)", operationId = "openInvestment")
    ResponseEntity<Investment> open(@Valid @RequestBody OpenRequest request) {
        Investment created = service.open(request);
        return ResponseEntity.created(URI.create("/api/v1/lending/investments/" + created.id()))
                .body(created);
    }

    @GetMapping("/maturities")
    @RequiresPermission("lending.investments.read")
    @Operation(
            summary = "The maturity ladder (overdue, 7, 30, 90 days) and the investments maturing (FR-INV-12)",
            operationId = "listInvestmentMaturities")
    Maturities maturities(
            @RequestParam(name = "days", required = false) Integer days,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds) {
        return reports.maturities(days, branchIds);
    }

    @GetMapping("/metrics")
    @RequiresPermission("lending.investments.read")
    @Operation(
            summary = "Balances, flows, returns accrued and paid, the ladder and concentration (FR-INV-12)",
            operationId = "getInvestmentMetrics")
    Metrics metrics(
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds) {
        return reports.report(from, to, branchIds);
    }

    @GetMapping("/{investment_id}")
    @RequiresPermission("lending.investments.read")
    @Operation(summary = "An investment with its balances", operationId = "getInvestment")
    Investment get(@PathVariable("investment_id") UUID id) {
        return service.get(id);
    }

    @PutMapping("/{investment_id}/maturity-instruction")
    @RequiresPermission("lending.investments.open")
    @Operation(
            summary = "Record the member's choice at maturity: payout or rollover (FR-INV-05)",
            operationId = "setInvestmentMaturityInstruction")
    Investment instruct(@PathVariable("investment_id") UUID id, @Valid @RequestBody InstructionRequest request) {
        return service.instruct(id, request.instruction());
    }

    @PostMapping("/{investment_id}/funding")
    @RequiresPermission("lending.investments.fund")
    @Operation(
            summary = "Record funding (FR-INV-03); 201 when executed below the threshold, 202 when it waits for a"
                    + " checker",
            operationId = "requestInvestmentFunding")
    ResponseEntity<ActionOutcome> fund(
            @PathVariable("investment_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody FundingRequest request) {
        return action(service.requestFunding(id, key, request));
    }

    @GetMapping("/{investment_id}/schedule")
    @RequiresPermission("lending.investments.read")
    @Operation(summary = "The accrual and payout schedule (FR-INV-09)", operationId = "getInvestmentSchedule")
    Schedule schedule(@PathVariable("investment_id") UUID id) {
        return service.schedule(id);
    }

    @GetMapping("/{investment_id}/statement")
    @RequiresPermission("lending.investments.read")
    @Operation(
            summary = "Every money event with the principal and return payable after it (FR-INV-10)",
            operationId = "getInvestmentStatement")
    Statement statement(@PathVariable("investment_id") UUID id) {
        return service.statement(id);
    }

    @GetMapping("/{investment_id}/certificate")
    @RequiresPermission("lending.investments.read")
    @Operation(summary = "The investment certificate's data (FR-INV-10)", operationId = "getInvestmentCertificate")
    Certificate certificate(@PathVariable("investment_id") UUID id) {
        return service.certificate(id);
    }

    @PostMapping("/{investment_id}/return-payouts")
    @RequiresPermission("lending.investments.payout")
    @Operation(summary = "Pay the return that is due (FR-INV-04)", operationId = "payInvestmentReturn")
    ResponseEntity<TransactionResult> payReturn(
            @PathVariable("investment_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PaymentRequest request) {
        return created(service.payReturn(id, key, request));
    }

    @PostMapping("/{investment_id}/payout")
    @RequiresPermission("lending.investments.payout")
    @Operation(
            summary = "Pay a matured investment out: principal and unpaid return (FR-INV-05)",
            operationId = "payInvestmentOut")
    ResponseEntity<TransactionResult> payout(
            @PathVariable("investment_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PaymentRequest request) {
        return created(service.payout(id, key, request));
    }

    @PostMapping("/{investment_id}/rollover")
    @RequiresPermission("lending.investments.payout")
    @Operation(
            summary = "Roll a matured investment over on the product's current terms (FR-INV-05)",
            operationId = "rollInvestmentOver")
    ResponseEntity<RolloverResult> rollover(
            @PathVariable("investment_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RolloverRequest request) {
        return created(service.rollover(id, key, request));
    }

    @GetMapping("/{investment_id}/early-withdrawal-quote")
    @RequiresPermission("lending.investments.read")
    @Operation(
            summary = "What an early withdrawal pays as at a date, today by default (FR-INV-06, R-INV-6)",
            operationId = "getInvestmentEarlyWithdrawalQuote")
    EarlyQuote earlyQuote(
            @PathVariable("investment_id") UUID id,
            @RequestParam(name = "value_date", required = false) LocalDate valueDate) {
        return service.earlyQuote(id, valueDate);
    }

    @PostMapping("/{investment_id}/early-withdrawal")
    @RequiresPermission("lending.investments.payout")
    @Operation(
            summary = "Request an early withdrawal; always waits for a checker (FR-INV-06)",
            operationId = "requestInvestmentEarlyWithdrawal")
    ResponseEntity<ActionOutcome> earlyWithdrawal(
            @PathVariable("investment_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody EarlyWithdrawalRequest request) {
        return action(service.requestEarlyWithdrawal(id, key, request));
    }

    @PostMapping("/{investment_id}/transactions/{txn_id}/reverse")
    @RequiresPermission("lending.investments.payout")
    @Operation(
            summary = "Request the reversal of a funding, return payout or maturity payout (FR-INV-11)",
            operationId = "requestInvestmentTransactionReversal")
    ResponseEntity<ActionOutcome> reverse(
            @PathVariable("investment_id") UUID id,
            @PathVariable("txn_id") UUID txnId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReasonRequest request) {
        return action(service.requestReversal(id, txnId, key, request));
    }

    private static <T> ResponseEntity<T> created(Outcome<T> o) {
        var response = ResponseEntity.status(HttpStatus.CREATED);
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }

    private static ResponseEntity<ActionOutcome> action(Outcome<ActionOutcome> o) {
        var response = ResponseEntity.status(o.body().executed() ? HttpStatus.CREATED : HttpStatus.ACCEPTED);
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }
}
