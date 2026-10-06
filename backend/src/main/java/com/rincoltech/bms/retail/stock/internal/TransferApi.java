package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the {@code /retail/transfers} routes (FR-RET-16). Cost fields are absent without
 * {@code retail.profit.read} in the source or the destination branch.
 */
final class TransferApi {

    static final String COST_ONLY = "Present only with retail.profit.read in either branch";

    private TransferApi() {}

    @Schema(name = "RetailTransferLineRequest")
    record TransferLineRequest(
            @NotNull UUID productId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "2.5")
            BigDecimal qty) {}

    @Schema(name = "RetailTransferRequest")
    record TransferRequest(
            @Schema(description = "The source branch; defaults to the caller's one branch")
            UUID fromBranchId,

            @NotNull @Schema(description = "The destination branch; any active branch of the tenant")
            UUID toBranchId,

            @Schema(description = "Defaults to today; not in the future")
            LocalDate transferDate,

            @Size(max = 300) String note,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull TransferLineRequest> lines) {}

    @Schema(name = "RetailTransferLine")
    record TransferLine(
            int lineNo,
            UUID productId,
            String code,
            String description,
            String qty,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long unitCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long lineCostMinor) {}

    @Schema(name = "RetailTransfer")
    record Transfer(
            UUID id,
            UUID fromBranchId,
            UUID toBranchId,
            LocalDate transferDate,
            String note,

            @Schema(allowableValues = {"completed", "voided"})
            String status,

            String currency,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costTotalMinor,

            List<TransferLine> lines,
            Instant createdAt,
            UUID createdBy,
            Instant voidedAt,
            UUID voidedBy,
            String voidReason) {}

    @Schema(name = "RetailTransferVoidRequest")
    record VoidRequest(@NotBlank @Size(max = 300) String reason) {}

    @Schema(name = "RetailTransferPage")
    record TransferPage(List<Transfer> items, String nextCursor) {}
}
