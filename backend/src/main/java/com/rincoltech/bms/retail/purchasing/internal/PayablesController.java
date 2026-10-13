package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.Bill;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.BillList;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.BillRequest;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.Match;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.UnbilledList;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.VoidRequest;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.OpenItems;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.Payables;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.Payment;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.PaymentList;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.PaymentRequest;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.ReverseRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingSettingsService.Settings;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingSettingsService.Update;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The supplier side of purchasing (purchasing capability; ADR-033, issue #198; chapter 7 section 7.11.20): supplier
 * invoices, supplier payments, what a supplier is owed, the payables report and the two tolerances. Each answers 404
 * {@code capability_not_enabled} while the capability is off. Invoices, payments and the reports are
 * {@code retail.payable.manage}, the administrator's; the tolerances are {@code core.settings.manage}.
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-payables")
class PayablesController {

    private final BillsService bills;
    private final PaymentsService payments;
    private final PayablesService payables;
    private final PurchasingSettingsService settings;

    PayablesController(
            BillsService bills,
            PaymentsService payments,
            PayablesService payables,
            PurchasingSettingsService settings) {
        this.bills = bills;
        this.payments = payments;
        this.payables = payables;
        this.settings = settings;
    }

    // ---- Supplier invoices -----------------------------------------------------------------

    @PostMapping("/supplier-bills")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "Record a supplier invoice matched to received lines: clears goods received not invoiced into trade"
                            + " creditors and posts the price variance (issue #198); M",
            operationId = "createRetailSupplierBill")
    ResponseEntity<Bill> createBill(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BillRequest request) {
        Outcome<Bill> outcome = bills.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/supplier-bills/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @PostMapping("/supplier-bills/match")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "What an invoice comes to against the receipts it bills, and whether it needs a reason, without recording"
                            + " it (issue #198)",
            operationId = "matchRetailSupplierBill")
    Match match(@Valid @RequestBody BillRequest request) {
        return bills.match(request);
    }

    @GetMapping("/supplier-bills")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "Supplier invoices in the caller's branch scope, newest first, without lines (issue #198)",
            operationId = "listRetailSupplierBills")
    BillList listBills(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "open", required = false, defaultValue = "false") boolean open,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return bills.list(branchIds, supplierId, status, open, from, to, q, limit, cursor);
    }

    @GetMapping("/supplier-bills/{bill_id}")
    @RequiresPermission("retail.payable.manage")
    @Operation(summary = "One supplier invoice with its lines (issue #198)", operationId = "getRetailSupplierBill")
    Bill getBill(@PathVariable("bill_id") UUID id) {
        return bills.get(id);
    }

    @PostMapping("/supplier-bills/{bill_id}/void")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "Void an invoice as a whole by reversal, once; refused while a payment stands against it (issue #198); M",
            operationId = "voidRetailSupplierBill")
    ResponseEntity<Bill> voidBill(
            @PathVariable("bill_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VoidRequest request) {
        Outcome<Bill> outcome = bills.voidBill(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/suppliers/{supplier_id}/unbilled-receipts")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "The received lines of a supplier that no invoice bills in full, which a new invoice is built from (issue #198)",
            operationId = "listRetailUnbilledReceipts")
    UnbilledList unbilled(@PathVariable("supplier_id") UUID supplierId) {
        return bills.unbilled(supplierId);
    }

    // ---- Supplier payments -----------------------------------------------------------------

    @PostMapping("/supplier-payments")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "Pay a supplier in cash, mobile money or bank, allocated to open invoices and credit restocks (issue #198); M",
            operationId = "createRetailSupplierPayment")
    ResponseEntity<Payment> createPayment(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        Outcome<Payment> outcome = payments.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/supplier-payments/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/supplier-payments")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "Supplier payments in the caller's branch scope, newest first, without allocations (issue #198)",
            operationId = "listRetailSupplierPayments")
    PaymentList listPayments(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return payments.list(branchIds, supplierId, status, from, to, q, limit, cursor);
    }

    @GetMapping("/supplier-payments/{payment_id}")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "One supplier payment with its allocations (issue #198)",
            operationId = "getRetailSupplierPayment")
    Payment getPayment(@PathVariable("payment_id") UUID id) {
        return payments.get(id);
    }

    @PostMapping("/supplier-payments/{payment_id}/reverse")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "Reverse a payment as a whole, once, with a reason; its allocations stop counting (issue #198); M",
            operationId = "reverseRetailSupplierPayment")
    ResponseEntity<Payment> reversePayment(
            @PathVariable("payment_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReverseRequest request) {
        Outcome<Payment> outcome = payments.reverse(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/suppliers/{supplier_id}/open-items")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "What a supplier is owed on invoices and credit restocks in the caller's scope, oldest due first (issue #198)",
            operationId = "listRetailSupplierOpenItems")
    OpenItems openItems(@PathVariable("supplier_id") UUID supplierId) {
        return payments.openItems(supplierId);
    }

    // ---- Report and settings ---------------------------------------------------------------

    @GetMapping("/reports/supplier-payables")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary = "Supplier balance and ageing as of today, with goods received and not yet invoiced (issue #198)",
            operationId = "getRetailSupplierPayables")
    Payables payables(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId) {
        return payables.report(branchIds, supplierId);
    }

    @GetMapping("/purchasing/settings")
    @RequiresPermission("core.settings.manage")
    @Operation(summary = "The purchasing tolerances (issue #198)", operationId = "getRetailPurchasingSettings")
    ResponseEntity<Settings> getSettings() {
        Settings s = settings.get();
        return ResponseEntity.ok().eTag(String.valueOf(s.version())).body(s);
    }

    @PutMapping(path = "/purchasing/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Change the purchasing tolerances under If-Match; audited with old and new values (issue #198)",
            operationId = "updateRetailPurchasingSettings")
    ResponseEntity<Settings> updateSettings(
            @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody Update request) {
        Settings s = settings.update(ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(s.version())).body(s);
    }
}
