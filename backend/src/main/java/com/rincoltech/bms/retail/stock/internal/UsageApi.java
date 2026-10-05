package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bodies of {@code POST /retail/usage} (FR-RET-07). Cost fields are absent without {@code retail.profit.read}. */
final class UsageApi {

    static final String COST_ONLY = "Present only with retail.profit.read";

    private UsageApi() {}

    @Schema(name = "RetailUsageLineRequest")
    record UsageLineRequest(
            @NotNull UUID productId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "1.5")
            BigDecimal qty) {}

    @Schema(name = "RetailUsageRequest")
    record UsageRequest(
            @Schema(description = "Defaults to the caller's one branch")
            UUID branchId,

            @NotNull @Pattern(regexp = "used|damaged") String kind,
            @NotBlank @Size(max = 300) String reason,

            @Schema(description = "Defaults to today; not in the future")
            LocalDate occurredOn,

            @NotEmpty @Size(max = 200) List<@Valid @NotNull UsageLineRequest> lines) {}

    @Schema(name = "RetailUsageLine")
    record UsageLine(
            int lineNo,
            UUID productId,
            String code,
            String description,
            String qty,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long unitCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long lineCostMinor) {}

    @Schema(name = "RetailUsageReport")
    record UsageReport(
            UUID id,
            UUID branchId,
            String kind,
            String reason,
            LocalDate occurredOn,
            String currency,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costTotalMinor,

            List<UsageLine> lines,
            Instant createdAt,
            UUID createdBy) {}
}
