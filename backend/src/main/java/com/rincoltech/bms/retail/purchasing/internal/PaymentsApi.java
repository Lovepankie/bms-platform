package com.rincoltech.bms.retail.purchasing.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the supplier payment and payables routes (purchasing capability; ADR-033, issue #198;
 * docs/specs/trade-capabilities.md sections 7.1 and 8). Amounts are integer minor units.
 */
final class PaymentsApi {

    static final String METHODS = "cash|mobile_money|bank";

    private PaymentsApi() {}

    @Schema(name = "RetailSupplierPaymentAllocationRequest")
    record AllocationRequest(
            @Schema(description = "A supplier invoice. Exactly one of bill_id and purchase_id")
            UUID billId,

            @Schema(description = "A credit restock of the supplier, which until now only raised trade creditors")
            UUID purchaseId,

            @NotNull @Positive @Max(OrdersApi.MAX_MINOR) Long amountMinor) {}

    @Schema(name = "RetailSupplierPaymentRequest")
    record PaymentRequest(
            @NotNull UUID supplierId,

            @Schema(
                    description =
                            "The branch whose till, mobile money or bank pays, inside the caller's retail.payable.manage scope;"
                                    + " absent means the one branch the scope covers")
            UUID branchId,

            @NotBlank @Pattern(regexp = METHODS) @Schema(description = METHODS)
            String method,

            @NotNull @Positive @Max(OrdersApi.MAX_MINOR) Long amountMinor,

            @Pattern(regexp = "oldest_first|manual")
            @Schema(
                    description =
                            "oldest_first (the default) pays the supplier's open items by due date, oldest first; manual pays the"
                                    + " allocations given. Either way the whole amount is allocated: nothing is held as a deposit")
            String allocation,

            @Size(max = 200) @Valid List<AllocationRequest> allocations,

            @Size(max = 60) @Schema(description = "A bank or mobile money reference")
            String reference,

            @Size(max = 300) String note) {}

    @Schema(name = "RetailSupplierPaymentReverseRequest")
    record ReverseRequest(
            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailSupplierPaymentAllocation")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Allocation(
            UUID billId, String billNo, String supplierRef, UUID purchaseId, String purchaseNo, long amountMinor) {}

    @Schema(name = "RetailSupplierPayment")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Payment(
            UUID id,
            String paymentNo,
            UUID branchId,
            UUID supplierId,
            String supplierName,
            LocalDate paidOn,
            String currency,
            String method,
            long amountMinor,
            String allocation,
            String reference,
            String note,

            @Schema(allowableValues = {"posted", "reversed"})
            String status,

            @Schema(description = "A list carries no allocations")
            List<Allocation> allocations,

            Instant createdAt,
            UUID createdBy,
            Instant reversedAt,
            LocalDate reversedOn,
            UUID reversedBy,
            String reverseReason,
            int version) {}

    @Schema(name = "RetailSupplierPaymentList")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PaymentList(List<Payment> items, String nextCursor) {}

    @Schema(
            name = "RetailSupplierOpenItem",
            description = "A supplier invoice or a credit restock that still owes something")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OpenItem(
            @Schema(allowableValues = {"bill", "restock"}) String kind,
            UUID id,

            @Schema(description = "Our number: the invoice number or the restock number")
            String number,

            @Schema(description = "The supplier's own invoice number; absent on a restock")
            String supplierRef,

            LocalDate date,

            @Schema(description = "A restock has no terms: it falls due the day it was bought")
            LocalDate dueDate,

            long totalMinor,
            long paidMinor,
            long openMinor,

            @Schema(description = "Days past the due date in the business time zone; 0 or less is not yet due")
            int daysPastDue) {}

    @Schema(name = "RetailSupplierOpenItems")
    record OpenItems(UUID supplierId, String supplierName, LocalDate asOf, List<OpenItem> items, long totalOpenMinor) {}

    @Schema(name = "RetailSupplierPayablesRow")
    record PayableRow(
            UUID supplierId,
            String supplierName,

            @Schema(description = "Owed on invoices and credit restocks, not yet due")
            long notDueMinor,

            long days1To30Minor,
            long days31To60Minor,
            long days61To90Minor,
            long over90Minor,

            @Schema(description = "Everything owed on invoices and credit restocks that stand")
            long owedMinor,

            @Schema(description = "Received and not yet invoiced: what sits in goods received not invoiced")
            long unbilledReceiptsMinor) {}

    @Schema(
            name = "RetailSupplierPayables",
            description =
                    "What is owed to each supplier as of today in the business time zone (Africa/Kampala unless the tenant has"
                            + " another), by days past the due date, with the goods received and not yet invoiced beside it."
                            + " The ageing buckets are those of the customer ageing")
    record Payables(LocalDate asOf, String currency, PayableRow totals, List<PayableRow> suppliers) {}
}
