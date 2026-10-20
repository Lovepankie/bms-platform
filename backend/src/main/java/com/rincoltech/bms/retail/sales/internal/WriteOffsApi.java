package com.rincoltech.bms.retail.sales.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Bodies of the write-off of a credit sale whose buyer never pays (ADR-043, issue #340). */
final class WriteOffsApi {

    private WriteOffsApi() {}

    @Schema(name = "RetailWriteOffRequest")
    record WriteOffRequest(
            @NotBlank
            @Size(min = 5, max = 300)
            @Schema(
                    description =
                            "Why the debt is written off; at least 5 characters, kept with the write-off and in the audit")
            String reason) {}

    @Schema(name = "RetailWriteOff")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WriteOff(
            UUID id,
            UUID saleId,
            String saleNo,
            UUID branchId,
            LocalDate writtenOffOn,
            String currency,

            @Schema(description = "What the buyer still owed, now cleared from trade debtors")
            long unpaidMinor,

            @Schema(
                    description =
                            "Present only with retail.profit.read in the sale's branch: the loss the profit report shows on this"
                                    + " day. With on_payment the unpaid share of the cost of goods; with on_sale the whole unpaid amount")
            Long lossMinor,

            @Schema(allowableValues = {"on_sale", "on_payment"})
            String creditSaleRecognition,

            String reason,
            Instant createdAt,
            UUID createdBy,
            UUID approvalId) {}

    @Schema(
            name = "RetailWriteOffOutcome",
            description =
                    "201 with the write-off when it was done; 202 with approval_id when it waits for a second administrator"
                            + " (the unpaid amount reached the tenant's approval threshold for retail_sale_write_off, or, with no"
                            + " threshold set, the business has two or more active administrators)")
    record WriteOffOutcome(
            boolean executed,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID approvalId,
            @JsonInclude(JsonInclude.Include.NON_NULL) WriteOff writeOff) {}
}
