package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = RetailCatalogue.PRICE_ONLY)
            Long sellMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #385: of qty, what open customer orders keep aside (not free to sell); absent when"
                                    + " nothing is kept")
            String reservedQty) {}

    @Schema(name = "RetailStockPage")
    record StockPage(
            UUID branchId,
            List<StockRow> items,

            @Schema(description = "A quantity at or below this is low stock (FR-RET-03); the same for every branch")
            String lowStockThreshold,

            String nextCursor) {}

    @Schema(name = "RetailStockBranch")
    record StockBranch(
            UUID id,
            String code,
            String name,
            boolean headOffice,

            @Schema(description = "A closed branch that still holds stock; shown only when include_closed is true")
            boolean closed) {}

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

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = RetailCatalogue.PRICE_ONLY)
            Long sellMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + " in every branch of the page")
            Long costMinor,

            @Schema(description = "One entry per branch of the page, in the page's branch order")
            List<BranchBalance> balances) {}

    @Schema(name = "RetailAllBranchesStock")
    record AllBranchesPage(
            List<StockBranch> branches,
            List<AllBranchesRow> items,

            @Schema(description = "A total at or below this is low stock; the same for every branch")
            String lowStockThreshold,

            @Schema(
                    description = "How many closed branches in the caller's scope still hold stock and are left out "
                            + "unless include_closed is true")
            int closedBranches,

            String nextCursor) {}

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
            UUID by,

            @JsonInclude(JsonInclude.Include.NON_NULL) MovementCorrection correction) {}

    @Schema(
            name = "RetailStockMovementCorrection",
            description = "Present on a quantity correction (issue #351): why, and the quantity before and after")
    record MovementCorrection(String reasonCode, String oldQty, String newQty) {}

    @Schema(name = "RetailStockMovementPage")
    record MovementPage(List<MovementRow> items, String nextCursor) {}

    @Schema(
            name = "RetailStockCorrectionRequest",
            description = "Issue #351: the right quantity of an item at a branch, with why it was wrong")
    record CorrectionRequest(
            @NotNull UUID branchId,
            @NotNull UUID productId,

            @NotNull
            @PositiveOrZero
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    example = "12.500",
                    description = "The quantity there should be, not the difference")
            BigDecimal newQty,

            @NotNull
            @PositiveOrZero
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    example = "12.5",
                    description =
                            "The quantity the form showed when it was opened; 409 stock_moved when it has changed since")
            BigDecimal expectedQty,

            @NotNull
            @Pattern(regexp = "entry_mistake|miscount")
            @Schema(description = "entry_mistake (a restock or sale was entered wrongly) or miscount")
            String reasonCode,

            @Size(max = 280) String note) {}

    @Schema(name = "RetailStockCorrection")
    record Correction(
            UUID id,
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String oldQty,
            String newQty,
            String reasonCode,
            String note,
            UUID movementId,
            Instant at,
            UUID by) {}

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
