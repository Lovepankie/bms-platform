package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of the retail stock routes (chapter 7 section 7.11.20). Quantities
 * are decimal strings with three places; cost fields are absent without {@code retail.profit.read}.
 */
final class StockApi {

    static final String COST_ONLY = "Present only with retail.profit.read";

    private StockApi() {}

    @Schema(name = "RetailStockRow")
    record StockRow(
            UUID productId,
            String code,
            String description,
            UUID categoryId,
            String category,
            String unit,
            String qty,

            @Schema(description = "Below zero: flagged until a purchase or stock-take corrects it (ADR-020 decision 4)")
            boolean negative,

            long sellMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costMinor) {}

    @Schema(name = "RetailStockPage")
    record StockPage(UUID branchId, List<StockRow> items, String nextCursor) {}

    @Schema(name = "RetailStockBranch")
    record StockBranch(UUID id, String code, String name, boolean headOffice) {}

    @Schema(name = "RetailBranchBalance")
    record BranchBalance(
            UUID branchId,
            String qty,

            @Schema(description = "Below zero in this branch")
            boolean negative) {}

    @Schema(name = "RetailAllBranchesRow", description = "One product with its balance in every branch of the page")
    record AllBranchesRow(
            UUID productId,
            String code,
            String description,
            UUID categoryId,
            String category,
            String unit,

            @Schema(description = "The sum over the branches of the page")
            String totalQty,

            @Schema(description = "True when any branch's balance is below zero")
            boolean negative,

            long sellMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + " in every branch of the page")
            Long costMinor,

            @Schema(description = "One entry per branch of the page, in the page's branch order")
            List<BranchBalance> balances) {}

    @Schema(name = "RetailAllBranchesStock")
    record AllBranchesPage(List<StockBranch> branches, List<AllBranchesRow> items, String nextCursor) {}

    @Schema(name = "RetailStockMovement")
    record MovementRow(
            UUID id,
            Instant at,
            UUID branchId,
            UUID productId,
            String kind,
            String qty,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long unitCostMinor,

            String sourceType,
            UUID sourceId,
            UUID reversesMovementId,
            boolean historical,
            String note,
            UUID by) {}

    @Schema(name = "RetailStockMovementPage")
    record MovementPage(List<MovementRow> items, String nextCursor) {}

    @Schema(name = "RetailStocktakeLineRequest")
    record StocktakeLineRequest(
            @NotNull UUID productId,

            @NotNull @PositiveOrZero @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "12.500")
            BigDecimal countedQty) {}

    @Schema(name = "RetailStocktakeRequest")
    record StocktakeRequest(
            @Schema(description = "Defaults to the caller's one branch")
            UUID branchId,

            @NotEmpty @Size(max = 2000) List<@Valid @NotNull StocktakeLineRequest> lines,
            @Size(max = 300) String note) {}

    @Schema(name = "RetailStocktakeLine")
    record StocktakeLine(
            UUID productId,
            String code,
            String description,
            String countedQty,

            @Schema(description = "The balance when the count was recorded")
            String expectedQty,

            @Schema(description = "counted less expected") String varianceQty,

            @Schema(
                    description =
                            "The adjustment written at commit: counted less expected, the variance measured when the count was taken; null for a draft")
            String committedVarianceQty,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long unitCostMinor) {}

    @Schema(name = "RetailStocktake")
    record Stocktake(
            UUID id,
            UUID branchId,
            String status,
            String note,
            List<StocktakeLine> lines,
            Instant createdAt,
            UUID createdBy,
            Instant committedAt,
            UUID committedBy,
            UUID adjustmentEntryId,
            int version) {}
}
