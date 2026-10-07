package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bodies of the retail reports (chapter 7 section 7.11.20). Cost fields are absent without retail.profit.read. */
final class ReportsApi {

    static final String COST_ONLY = "Present only with retail.profit.read";
    static final String PERCENT =
            "; expected profit over value at cost in basis points (2500 is 25 percent), rounded half up; absent when the value at cost is not above zero";

    private ReportsApi() {}

    @Schema(name = "RetailValuationRow")
    record ValuationRow(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            UUID categoryId,
            String category,
            String unit,
            String qty,
            boolean negative,
            long sellMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "qty times the current sell price; absent when amount_out_of_range")
            Long expectedSalesMinor,

            @Schema(
                    description =
                            "True when qty times a price is too large to hold; the row's values are absent and left out of the totals")
            boolean amountOutOfRange,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + "; qty times the current cost")
            Long valueAtCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; expected sales less value at cost; absent when amount_out_of_range")
            Long expectedProfitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + PERCENT)
            Long expectedProfitBp) {}

    @Schema(name = "RetailValuationBranch")
    record BranchTotal(
            UUID branchId,
            long expectedSalesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long valueAtCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; the inventory account's balance for the branch")
            Long inventoryAccountMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            COST_ONLY
                                    + "; value at cost less the inventory account: the revaluation difference of ADR-020 decision 8")
            Long revaluationDifferenceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; expected sales less value at cost")
            Long expectedProfitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + PERCENT)
            Long expectedProfitBp) {}

    @Schema(name = "RetailValuationCategory", description = "Totals of every reported branch for one category")
    record CategoryTotal(
            UUID categoryId,
            String category,
            long expectedSalesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long valueAtCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long expectedProfitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + PERCENT)
            Long expectedProfitBp) {}

    @Schema(name = "RetailValuation")
    record Valuation(
            LocalDate asOf,
            String currency,
            List<ValuationRow> rows,
            List<BranchTotal> branches,
            List<CategoryTotal> categories,
            long expectedSalesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long valueAtCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; expected sales less value at cost")
            Long expectedProfitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + PERCENT)
            Long expectedProfitBp) {}

    @Schema(name = "RetailDailyProfitRow")
    record ProfitRow(
            UUID branchId,
            LocalDate date,
            long salesMinor,
            long costOfSalesMinor,
            long grossProfitMinor,
            long usageCostMinor,
            long profitMinor) {}

    @Schema(name = "RetailDailyProfit")
    record DailyProfit(
            LocalDate from,
            LocalDate to,
            String currency,
            List<ProfitRow> rows,
            long salesMinor,
            long costOfSalesMinor,
            long usageCostMinor,
            long profitMinor) {}
}
