package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
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
            String reservedQty,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = STANDARD_PACK)
            StandardPack standardPack) {}

    static final String STANDARD_PACK =
            "Issue #650: the pack the stock is shown in (packs and pieces); absent while the units"
                    + " capability is off or the item has no active pack holding more than one";

    @Schema(name = "RetailStandardPack", description = "An item's standard pack: its unit and the base units one holds")
    record StandardPack(
            String unit,

            @Schema(description = "Base units in one pack, three places, for example 24.000")
            String factor) {}

    @Schema(name = "RetailStockPage")
    record StockPage(
            UUID branchId,
            List<StockRow> items,

            @Schema(
                    description = "A quantity at or below this is low stock (FR-RET-03) for an item with no "
                            + "minimum set; the same for every branch")
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
            List<BranchBalance> balances,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = STANDARD_PACK)
            StandardPack standardPack) {}

    @Schema(name = "RetailAllBranchesStock")
    record AllBranchesPage(
            List<StockBranch> branches,
            List<AllBranchesRow> items,

            @Schema(
                    description = "A balance at or below this is low stock for an item with no minimum set; the "
                            + "same for every branch")
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
            name = "RetailQuickAddRequest",
            description =
                    "Issue #632: an item, its prices and how many there are at a branch, in one tap. With product_id, an item"
                            + " already in the list, only the quantity is added and the other item fields are ignored")
    record QuickAddRequest(
            @Schema(description = "An item already in the list, chosen instead of a new item of the same name")
            UUID productId,

            @Size(max = 300) @Schema(description = "The new item's name; required without product_id")
            String description,

            @Schema(description = "Left out: the item goes under General, made when the business has none")
            UUID categoryId,

            @Schema(description = "Left out: the shop's piece unit (Piece or pc), made when the business has none")
            UUID unitId,

            @PositiveOrZero @Max(RetailCatalogue.MAX_AMOUNT_MINOR) @Schema(description = "Required without product_id")
            Long sellMinor,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "Left out: 0, set by the first restock")
            Long costMinor,

            @PositiveOrZero
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    example = "600",
                    description =
                            "How many to add to the item's stock at the branch (retail.stocktake.commit there), as"
                                    + " opening stock against opening balance equity; more than 0 with product_id; left out, no"
                                    + " stock is recorded")
            BigDecimal qty,

            @Schema(description = "Where the stock is; defaults to the caller's one branch")
            UUID branchId,

            @Size(max = 300)
            @Schema(description = "As on a new item: to add a name that exists already, administrators only")
            String duplicateReason,

            @Pattern(regexp = "cash|mobile_money")
            @Schema(
                    description =
                            "Issue #646: the goods were just bought and paid from the branch's cash or mobile money"
                                    + " (retail.purchase.create there). qty above 0 and cost_minor above 0 are then needed, and the"
                                    + " stock is recorded as a purchase, as Buy stock does, never against opening balance equity."
                                    + " Left out: opening stock, as before")
            String paidFrom) {}

    @Schema(name = "RetailQuickAdd")
    record QuickAdd(
            UUID productId,
            String code,
            String description,
            String unit,

            @Schema(description = "False when the item was already in the list")
            boolean itemMade,

            @Schema(description = "True when this add made the General category")
            boolean categoryMade,

            @Schema(description = "True when this add made the Piece unit")
            boolean unitMade,

            UUID branchId,

            @Schema(type = "string", description = "The quantity added; absent when no stock was recorded")
            String qty,

            @Schema(type = "string", description = "What the branch holds now; absent when no stock was recorded")
            String totalQty,

            @Schema(
                    description =
                            "The opening movement of source retail.quick_add; absent when nothing was added or when"
                                    + " it was paid for (the purchase's movement)")
            UUID movementId,

            @Schema(description = "cash or mobile_money when the goods were paid for (issue #646); absent otherwise")
            String paidFrom,

            @Schema(description = "The purchase recorded when the goods were paid for, as on Buy stock")
            String purchaseNo,

            @Schema(description = "What left the cash or mobile money account; absent when not paid for")
            Long paidMinor) {}

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
                    description = "The quantity the form showed when it was opened; 409 stock_moved when it has "
                            + "changed since, with current_qty, the quantity now")
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

    @Schema(
            name = "RetailStocktakeCommitRequest",
            description = "Issue #636: the lines of a big drop the person ticked as right on Review the difference")
    record StocktakeCommitRequest(
            @Size(max = 2000)
            @Schema(
                    description =
                            "The product ids of the lines that take an item holding stock to 0, or to less than half of"
                                    + " it, that were ticked as right. Every such line must be here, or the commit is"
                                    + " refused with 409 big_drop_not_acknowledged")
            List<@NotNull UUID> acknowledged) {}

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
                    description = "The adjustment written at commit: counted less expected, the variance "
                            + "measured when the count was taken; null for a draft")
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

    @Schema(
            name = "RetailStocktakeDrafts",
            description = "Issue #681: the caller's own draft counts at a branch, newest first, with their lines")
    record StocktakeDrafts(List<Stocktake> items) {}
}
