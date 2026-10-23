package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/billing} on the business's own host (issue #480, ADR-075): the Owner's Plan and billing page, read only for
 * every administrator (the Owner is one, ADR-062), the receipt of each payment, and the overdue banner every signed-in staff
 * user sees. Payments are still recorded by the platform operator only.
 */
@RestController
@RequestMapping(path = "/api/v1/billing", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "billing")
class BillingController {

    private final BillingService billing;

    BillingController(BillingService billing) {
        this.billing = billing;
    }

    @Schema(name = "BillingPayment")
    record BillingPayment(
            UUID id,
            long amountMinor,
            String currency,

            @Schema(description = "mtn_momo, airtel_money, bank_transfer or other")
            String method,

            String reference,
            LocalDate paidOn,
            int months,
            LocalDate periodStart,
            LocalDate periodEnd,

            @Schema(description = "RT-<year>-<number>; null only if the receipt could not be issued")
            String receiptNo) {}

    @Schema(name = "BillingView")
    record BillingView(
            String planCode,
            String planName,
            String currency,

            @Schema(description = "The plan's monthly price today, in minor units, or null when none was entered")
            Long monthlyMinor,

            @Schema(description = "Months charged for a year, or null")
            Integer annualMonths,

            @Schema(description = "The yearly price, or null")
            Long annualMinor,

            @Schema(description = "trial, active, in_grace, overdue, suspended, cancelled or pilot")
            String state,

            @Schema(description = "The last day of the free month, or null")
            LocalDate trialEndsOn,

            @Schema(description = "The last day paid for, or null")
            LocalDate paidUntil,

            @Schema(description = "While overdue: the first day not paid for")
            LocalDate overdueSince,

            @Schema(description = "While overdue: the last day of the grace period")
            LocalDate payBy,

            int graceDays,

            @Schema(description = "The operator's words on how to pay, or null")
            String howToPay,

            @Schema(description = "Newest first") List<BillingPayment> payments) {}

    @Schema(name = "BillingNotice")
    record BillingNotice(
            @Schema(description = "none, in_grace or overdue")
            String state,

            LocalDate overdueSince,
            LocalDate payBy) {}

    @GetMapping
    @RequiresPermission("core.billing.read")
    @Operation(
            summary =
                    "The plan, its price, the subscription's state and dates, how to pay and the payments with their receipts (FR-SUB-12)",
            operationId = "billingView")
    BillingView view() {
        return billing.view();
    }

    @GetMapping("/notice")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "Whether the business is overdue, since when and the last day of its grace period (FR-SUB-09)",
            operationId = "billingNotice")
    BillingNotice notice() {
        return billing.notice();
    }

    @GetMapping(path = "/payments/{payment_id}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
    @RequiresPermission("core.billing.read")
    @Operation(summary = "The receipt of a payment as a PDF (FR-SUB-11)", operationId = "billingReceipt")
    ResponseEntity<byte[]> receipt(@PathVariable("payment_id") UUID paymentId) {
        BillingService.Receipt receipt = billing.receiptPdf(paymentId).orElseThrow(ApiException::notFound);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(receipt.fileName())
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(receipt.bytes());
    }
}
